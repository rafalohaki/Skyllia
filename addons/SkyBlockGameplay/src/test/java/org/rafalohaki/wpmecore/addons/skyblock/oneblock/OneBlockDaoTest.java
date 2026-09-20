package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;

import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.api.service.SqlDialect;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises {@link OneBlockDao} against the real migrated SQLite schema:
 * the v3 DDL keeps the legacy {@code phase} column NOT NULL without a
 * default, so every INSERT must still provide a numeric phase.
 */
class OneBlockDaoTest {

    private static final List<String> PHASE_IDS = List.of(
            "poczatek", "podziemia", "zima", "pustynia", "dzungla", "pieklo", "kres");

    private SingleConnectionSqlService sql;
    private OneBlockDao dao;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        dao = new OneBlockDao(sql, PHASE_IDS);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void saveInsertsNewStateRow() {
        OneBlockState state = new OneBlockState(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "skyblock_world", 0, 64, 0, "zima", 5, 40, 2);

        dao.save(state).join();

        Row row = selectOneBlockRow("11111111-1111-1111-1111-111111111111");
        assertNotNull(row);
        assertEquals("zima", row.phaseId);
        assertEquals(5, row.progress);
        assertEquals(40, row.totalMined);
        assertEquals(2, row.dryStreak);
    }

    @Test
    void saveUpsertsExistingStateCountersAndPhaseId() {
        UUID islandId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        OneBlockState state = new OneBlockState(
                islandId, "skyblock_world", 10, 70, -20, "podziemia", 10, 100, 3);
        dao.save(state).join();

        state.setPhaseId("pieklo");
        state.incrementProgress();
        state.incrementProgress();
        state.incrementTotalMined();
        state.incrementDryStreak();
        dao.save(state).join();

        assertEquals(1L, scalar("SELECT COUNT(*) FROM wpme_sb_oneblock"));
        Row row = selectOneBlockRow("22222222-2222-2222-2222-222222222222");
        assertNotNull(row);
        assertEquals("pieklo", row.phaseId);
        assertEquals(12, row.progress);
        assertEquals(101, row.totalMined);
        assertEquals(4, row.dryStreak);
    }

    @Test
    void loadAllRoundTripsStateFields() {
        UUID first = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID second = UUID.fromString("44444444-4444-4444-4444-444444444444");
        dao.save(new OneBlockState(first, "skyblock_world", 1, 64, 1,
                "poczatek", 7, 250, 0)).join();
        dao.save(new OneBlockState(second, "sky_nether", -5, 40, 12,
                "kres", 60, 6100, 9)).join();

        Map<UUID, OneBlockState> loaded = new HashMap<>();
        for (OneBlockState state : dao.loadAll().join()) {
            loaded.put(state.islandId(), state);
        }

        assertEquals(2, loaded.size());
        OneBlockState firstState = loaded.get(first);
        assertEquals("poczatek", firstState.phaseId());
        assertEquals(7, firstState.progress());
        assertEquals(250, firstState.totalMined());
        assertEquals(0, firstState.dryStreak());
        assertEquals("skyblock_world", firstState.worldName());
        assertEquals(1, firstState.x());
        assertEquals(64, firstState.y());
        assertEquals(1, firstState.z());
        OneBlockState secondState = loaded.get(second);
        assertEquals("kres", secondState.phaseId());
        assertEquals(60, secondState.progress());
        assertEquals(6100, secondState.totalMined());
        assertEquals(9, secondState.dryStreak());
    }

    @Test
    void writesLegacyPhaseColumnAsChapterIndex() {
        dao.save(new OneBlockState(UUID.randomUUID(), "skyblock_world", 0, 64, 0,
                "poczatek", 0, 0, 0)).join();
        dao.save(new OneBlockState(UUID.randomUUID(), "skyblock_world", 0, 64, 0,
                "zima", 0, 0, 0)).join();
        dao.save(new OneBlockState(UUID.randomUUID(), "skyblock_world", 0, 64, 0,
                "kres", 0, 0, 0)).join();
        // Unknown chapter ids clamp onto the first chapter per save() contract.
        dao.save(new OneBlockState(UUID.randomUUID(), "skyblock_world", 0, 64, 0,
                "nieznany", 0, 0, 0)).join();

        Map<String, Integer> phaseById = sql.withConnection(connection -> {
            Map<String, Integer> rows = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT phase_id, phase FROM wpme_sb_oneblock");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.put(result.getString(1), result.getInt(2));
                }
            }
            return rows;
        }).join();

        assertEquals(1, phaseById.get("poczatek"));
        assertEquals(3, phaseById.get("zima"));
        assertEquals(7, phaseById.get("kres"));
        assertEquals(1, phaseById.get("nieznany"));
    }

    private record Row(String phaseId, int phase, int progress, int totalMined, int dryStreak) { }

    private Row selectOneBlockRow(String islandId) {
        return sql.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT phase_id, phase, progress, total_mined, dry_streak"
                            + " FROM wpme_sb_oneblock WHERE island_id = ?")) {
                statement.setString(1, islandId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next()
                            ? new Row(result.getString(1), result.getInt(2),
                                    result.getInt(3), result.getInt(4), result.getInt(5))
                            : null;
                }
            }
        }).join();
    }

    private long scalar(String query) {
        return sql.query(query, row -> row.getLong(1)).join().getFirst();
    }

    private static final class SingleConnectionSqlService implements SqlService {
        private final Connection connection;

        private SingleConnectionSqlService() throws SQLException {
            connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        }

        @Override public @NotNull SqlDialect dialect() { return SqlDialect.SQLITE; }
        @Override public boolean isEnabled() { return true; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isHealthy() { return true; }
        @Override public @NotNull Map<String, Object> poolStats() { return Map.of(); }

        @Override
        public void shutdown() {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Test cleanup.
            }
        }

        @Override
        public @NotNull CompletableFuture<Integer> update(@NotNull String query,
                                                           @NotNull Object... params) {
            return completed(() -> {
                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    bind(statement, params);
                    return statement.executeUpdate();
                }
            });
        }

        @Override
        public <T> @NotNull CompletableFuture<List<T>> query(
                @NotNull String query, @NotNull RowMapper<T> mapper,
                @NotNull Object... params) {
            return completed(() -> {
                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    bind(statement, params);
                    try (ResultSet result = statement.executeQuery()) {
                        List<T> rows = new ArrayList<>();
                        while (result.next()) {
                            rows.add(mapper.map(result));
                        }
                        return rows;
                    }
                }
            });
        }

        @Override
        public <T> @NotNull CompletableFuture<T> withConnection(
                @NotNull SqlAction<T> action) {
            return completed(() -> action.execute(connection));
        }

        @Override public @NotNull Object dataSource() { return connection; }

        private static void bind(PreparedStatement statement, Object... params)
                throws SQLException {
            for (int index = 0; index < params.length; index++) {
                statement.setObject(index + 1, params[index]);
            }
        }

        private static <T> CompletableFuture<T> completed(SqlSupplier<T> supplier) {
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @FunctionalInterface
        private interface SqlSupplier<T> {
            T get() throws Exception;
        }
    }
}
