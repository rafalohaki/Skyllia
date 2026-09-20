package org.rafalohaki.wpmecore.addons.skyblock.profile;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.IslandProfileDao;
import org.rafalohaki.wpmecore.addons.skyblock.playtime.ProfilePlaytimeDao;
import org.rafalohaki.wpmecore.api.service.SqlDialect;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresja D1 (produkcja 2026-08-26): {@code recoverAll()} wykonywał
 * {@code resume(...).join()} wewnątrz kontynuacji {@code thenCombine}, która
 * sama biegnie na jedynym wątku egzekutora SQL. SQLite wymusza Hikari pool=1,
 * więc egzekutor ma dokładnie jeden wątek Sql-*: zadanie {@code find()} z
 * {@code resume()} ustawiało się w kolejce ZA zadaniem, które samo trzymało
 * ten wątek przez {@code join()} — samozakleszczenie, kolejka (256) pełna,
 * cały SQL dodatku stawał przy każdym starcie /sbadmin recovery.
 *
 * <p>Symulacja produkcyjna jak w {@code SeasonPointDaoVisibilityTest}:
 * jedno połączenie plikowe SQLite + jednowątkowy egzekutor (jak pool=1).
 * Stary kod: {@code recoverAll()} nigdy się nie kończył (TimeoutException w
 * teście). Naprawiony kod (thenCompose/allOf bez join w kontynuacji): raport
 * recovery z pełnym tally semantyki jak dawniej.
 */
class ProfileRecoveryRecoverAllNoDeadlockTest {

    @TempDir
    Path tempDir;

    private PoolOneSqlService sql;
    private IslandProfileDao profileDao;
    private ProfileTransitionDao transitionDao;
    private ProfileSnapshotDao snapshotDao;
    private ProfileRecoveryService recoveryService;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("recovery-deadlock-test.db");
        sql = new PoolOneSqlService(jdbcUrl);
        new SkyBlockSchemaMigrator(sql).migrate().get(10, TimeUnit.SECONDS);
        profileDao = new IslandProfileDao(sql);
        transitionDao = new ProfileTransitionDao(sql);
        snapshotDao = new ProfileSnapshotDao(sql);
        recoveryService = new ProfileRecoveryService(
                transitionDao,
                snapshotDao,
                profileDao,
                new ProfilePlaytimeDao(sql),
                new ProfileSnapshotService(snapshotDao, "test-scope"));
        // Domyslnie w testach: Skyllia potwierdza, ze wyspy NIE MA — dopiero wtedy
        // resume DELETE/RESET wolno dotknac danych (ISLAND-1).
        recoveryService.setIslandExistsCheck(islandId -> false);
    }

    @AfterEach
    void tearDown() throws Exception {
        sql.shutdown();
    }

    /**
     * Pending DELETE (crash po snapshocie) musi zostać wznowiony mimo że
     * kontynuacja recoverAll wykonuje się NA jedynym wątku Sql-*, i to BEZ
     * blokowania — raport kompletny, membership wyczyszczony, egzekutor żyje.
     */
    @Test
    void recoverAllCompletesOnSingleThreadSqlExecutorInsteadOfDeadlocking() throws Exception {
        UUID islandId = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();

        // seed: aktywny profil (profil + membership OWNER) + journal DELETE utknął w SNAPSHOT_WRITTEN
        assertTrue(profileDao.tryCreateProfile(islandId, playerUuid, "CLASSIC")
                .get(10, TimeUnit.SECONDS));
        String op = "op:delete:" + playerUuid;
        assertTrue(transitionDao.create(op, islandId, playerUuid, "DELETE", "ACTIVE", "LEFT", "SNAPSHOT_WRITTEN")
                .get(10, TimeUnit.SECONDS));

        // Stary kod: self-deadlock na jedynym wątku Sql-*. Timeout strzegłby testu.
        var report = recoveryService.recoverAll().get(30, TimeUnit.SECONDS);

        // tally jak w starej implementacji: jedna operacja, wznowiona do końca
        assertEquals(1, report.totalPending());
        assertEquals(1, report.resumed());
        assertEquals(0, report.quarantined());
        assertEquals(0, report.failed());

        // semantyka M1-D: resume faktycznie czyści membership i domyka transition
        assertTrue(profileDao.findActiveMembership(playerUuid).get(10, TimeUnit.SECONDS).isEmpty(),
                "resume DELETE musi dezaktywować membership");
        assertEquals("RESUME_COMPLETE", transitionDao.find(op).get(10, TimeUnit.SECONDS).orElseThrow().result());

        // egzekutor żyje po recovery (stary kod zostawiał go zakleszczonego)
        assertTrue(transitionDao.listPending(100).get(10, TimeUnit.SECONDS).isEmpty());
    }

    /**
     * Jak produkcyjny {@code HikariSqlService}: SQLite wymusza pool=1, więc
     * egzekutor ma jeden wątek; każde zapytanie leci asynchronicznie przez
     * {@link CompletableFuture#supplyAsync} na wspólnym połączeniu plikowym.
     */
    private static final class PoolOneSqlService implements SqlService {

        private final Connection connection;
        private final ExecutorService backend =
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "RecoveryTest-Sql-1");
                    thread.setDaemon(true);
                    return thread;
                });

        PoolOneSqlService(@NotNull String jdbcUrl) throws SQLException {
            this.connection = DriverManager.getConnection(jdbcUrl);
        }

        @Override public @NotNull SqlDialect dialect() { return SqlDialect.SQLITE; }
        @Override public boolean isEnabled() { return true; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isHealthy() { return true; }
        @Override public @NotNull Map<String, Object> poolStats() { return Map.of(); }

        @Override
        public @NotNull CompletableFuture<Integer> update(@NotNull String query,
                                                          @NotNull Object... params) {
            return supply(() -> {
                try (PreparedStatement statement = prepare(query, params)) {
                    return statement.executeUpdate();
                }
            });
        }

        @Override
        public <T> @NotNull CompletableFuture<List<T>> query(@NotNull String query,
                                                             @NotNull RowMapper<T> mapper,
                                                             @NotNull Object... params) {
            return supply(() -> {
                List<T> rows = new ArrayList<>();
                try (PreparedStatement statement = prepare(query, params);
                     ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        rows.add(mapper.map(results));
                    }
                }
                return rows;
            });
        }

        @Override
        public <T> @NotNull CompletableFuture<T> withConnection(@NotNull SqlAction<T> action) {
            return supply(() -> action.execute(connection));
        }

        @Override public @NotNull Object dataSource() { return connection; }

        @Override
        public void shutdown() {
            backend.shutdown();
            try {
                if (!backend.awaitTermination(5, TimeUnit.SECONDS)) {
                    backend.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                backend.shutdownNow();
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // test teardown only
            }
        }

        private PreparedStatement prepare(String query, Object... params) throws SQLException {
            PreparedStatement statement = connection.prepareStatement(query);
            for (int index = 0; index < params.length; index++) {
                statement.setObject(index + 1, params[index]);
            }
            return statement;
        }

        private <T> CompletableFuture<T> supply(SqlSupplier<T> supplier) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return supplier.get();
                } catch (Exception failure) {
                    throw new RuntimeException("SQL failed", failure);
                }
            }, backend);
        }

        @FunctionalInterface
        private interface SqlSupplier<T> {
            T get() throws Exception;
        }
    }
}
