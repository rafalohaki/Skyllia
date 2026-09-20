package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresja anomalii V1/F: wiersz punktów sezonowych wpisany do SQLite z zewnątrz
 * (bezpośredni INSERT przy działającym serwerze) był widoczny dla {@code points()},
 * ale {@code rank()} i {@code top()} wisiały / zwracały pustkę.
 *
 * <p>Przyczyna: stary {@code rank()} wywoływał blokujące {@code points(...).join()}
 * wewnątrz {@code thenApply}. Dla SQLite Hikari wymusza pool=1, a egzekutor
 * {@code HikariSqlService} ma dokładnie tyle samo wątków — kontynuacja wykonywała
 * się na jedynym wątku Sql-*, a {@code join()} czekało na zadanie {@code points()}
 * ustawione w kolejce za... zadaniem, które samo trzymało ten wątek. Deadlock:
 * przyszła ranga nigdy się nie kończyła, a cały egzekutor dodatku stawał, więc
 * kolejne {@code top()}/{@code points()} wisiyły (GUI: brak pozycji, puste linie, 30 s+).
 *
 * <p>Symulacja produkcyjna: jedno połączenie plikowe SQLite + jednowątkowy
 * egzekutor (jak pool=1), pisarz zewnętrzny na osobnym, surowym połączeniu JDBC.
 */
class SeasonPointDaoVisibilityTest {

    private static final int SEASON = 4;

    @TempDir
    Path tempDir;

    private PoolOneSqlService sql;
    private Connection externalWriter;
    private SqlSeasonPointDao dao;

    @BeforeEach
    void setUp() throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("season-points-test.db");
        sql = new PoolOneSqlService(jdbcUrl);
        try (var stmt = sql.connection().createStatement()) {
            stmt.executeUpdate(SkyBlockSchemaMigrator.SEASON_POINTS_DDL);
            stmt.executeUpdate(SkyBlockSchemaMigrator.REWARD_CLAIMS_DDL);
            stmt.executeUpdate(SkyBlockSchemaMigrator.REWARD_CLAIMS_OP_IDX_DDL);
        }
        externalWriter = DriverManager.getConnection(jdbcUrl);
        dao = new SqlSeasonPointDao(sql, () -> SEASON);
    }

    @AfterEach
    void tearDown() throws Exception {
        externalWriter.close();
        sql.shutdown();
    }

    /** Wiersz 77 pkt wpisany z zewnątrz musi być widoczny dla points(), rank() i top(). */
    @Test
    void externallyProvisionedRowIsVisibleToRankAndTop() throws Exception {
        UUID rival = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID victim = UUID.fromString("33333333-3333-3333-3333-333333333333");

        assertTrue(dao.addPoints(rival, SEASON, 50L, "op:rival", true).get(5, TimeUnit.SECONDS));

        // Zewnętrzny INSERT przy "działającym serwerze" — poza pulą.
        try (PreparedStatement insert = externalWriter.prepareStatement(
                "INSERT INTO wpme_sb_season_points (season_id, player_uuid, points, quests_done, updated_at) "
                        + "VALUES (?, ?, 77, 1, ?)")) {
            insert.setInt(1, SEASON);
            insert.setString(2, victim.toString());
            insert.setLong(3, System.currentTimeMillis());
            assertEquals(1, insert.executeUpdate());
        }

        // Widoczność dla pointsOf — to zawsze działało.
        assertEquals(77L, dao.points(victim, SEASON).get(5, TimeUnit.SECONDS));

        // Determinizm: zajmij jedyny wątek egzekutora, dopóki rank() nie zbuduje
        // swojego łańcucha — wtedy kontynuacja NA PEWNO wykona się na wątku Sql-*.
        CountDownLatch hold = new CountDownLatch(1);
        ExecutorService backend = sql.backend();
        backend.execute(() -> {
            try {
                hold.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        CompletableFuture<Optional<Integer>> victimRank = dao.rank(victim, SEASON);
        hold.countDown();

        // Stary kod: deadlock — TimeoutException + zakleszczony egzekutor.
        // Naprawiony kod: nikt nie ma więcej punktów → pozycja #1.
        assertEquals(Optional.of(1), victimRank.get(5, TimeUnit.SECONDS));

        // TOP też widzi wiersz zewnętrzny, w prawidłowej kolejności.
        List<SeasonPointDao.TopRow> top = dao.top(SEASON, 10).get(5, TimeUnit.SECONDS);
        assertEquals(List.of(
                new SeasonPointDao.TopRow(victim, 77L),
                new SeasonPointDao.TopRow(rival, 50L)), top);

        // Egzekutor żyje po całej awanturze (stary kod zostawił go zakleszczonego).
        assertEquals(77L, dao.points(victim, SEASON).get(5, TimeUnit.SECONDS));
    }

    /** Semantyka rangi dla zwykłego przepływu bez zmian (łącznie ze stanem braku wiersza). */
    @Test
    void normalFlowRankSemanticsPreserved() throws Exception {
        UUID gold = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID silver = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID bronze = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID newcomer = UUID.fromString("77777777-7777-7777-7777-777777777777");

        dao.addPoints(gold, SEASON, 100L, "op:g", true).get(5, TimeUnit.SECONDS);
        dao.addPoints(silver, SEASON, 60L, "op:s", true).get(5, TimeUnit.SECONDS);
        dao.addPoints(bronze, SEASON, 60L, "op:b", true).get(5, TimeUnit.SECONDS);

        assertEquals(Optional.of(1), dao.rank(gold, SEASON).get(5, TimeUnit.SECONDS));
        // Remis 60/60: COUNT(points > 60) = 1 (gold ma 100) → obie #2, jak w starym SQL.
        assertEquals(Optional.of(2), dao.rank(silver, SEASON).get(5, TimeUnit.SECONDS));
        assertEquals(Optional.of(2), dao.rank(bronze, SEASON).get(5, TimeUnit.SECONDS));
        // Gracz bez wiersza, ale z rywalami powyżej: COUNT(*) > 0 → COUNT + 1.
        assertEquals(Optional.of(4), dao.rank(newcomer, SEASON).get(5, TimeUnit.SECONDS));

        // Pusty sezon: brak punktów własnych i nic powyżej → Optional.empty().
        UUID stranger = UUID.fromString("88888888-8888-8888-8888-888888888888");
        assertEquals(Optional.empty(), dao.rank(stranger, SEASON + 1).get(5, TimeUnit.SECONDS));
        assertTrue(dao.top(SEASON + 1, 10).get(5, TimeUnit.SECONDS).isEmpty());
    }

    /**
     * Podobno jak produkcyjny {@code HikariSqlService}: SQLite wymusza pool=1,
     * więc egzekutor ma jeden wątek; każde zapytanie leci asynchronicznie przez
     * {@link CompletableFuture#supplyAsync} na wspólnym połączeniu plikowym.
     */
    private static final class PoolOneSqlService implements SqlService {

        private final Connection connection;
        private final ExecutorService backend =
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "SeasonTest-Sql-1");
                    thread.setDaemon(true);
                    return thread;
                });

        PoolOneSqlService(@NotNull String jdbcUrl) throws SQLException {
            this.connection = DriverManager.getConnection(jdbcUrl);
        }

        Connection connection() {
            return connection;
        }

        ExecutorService backend() {
            return backend;
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
