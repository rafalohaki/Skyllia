package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;

import org.bukkit.Material;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exercises {@link OneBlockMilestoneDao} against the real migrated SQLite
 * schema (migration 7): loot is frozen once at roll time, claims are atomic
 * {@code UPDATE ... WHERE claimed_at IS NULL}, reverts only undo our own
 * timestamp. Also pins the {@link OneBlockLoot} wire format for custom items
 * whose ids contain colons.
 */
class OneBlockMilestoneDaoTest {

    private static final List<String> PHASE_IDS = List.of(
            "poczatek", "podziemia", "zima", "pustynia", "dzungla", "pieklo", "kres");

    private SqliteSql sql;
    private OneBlockMilestoneDao dao;
    private final UUID island = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SqliteSql();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        dao = new OneBlockMilestoneDao(sql);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void insertIfAbsentKeepsFirstLootFrozen() {
        dao.insertIfAbsent(island, "poczatek:100", "material:IRON_INGOT:8", 1L).join();
        dao.insertIfAbsent(island, "poczatek:100", "material:DIAMOND:99", 2L).join();

        List<OneBlockMilestoneDao.MilestoneRow> rows = dao.listUnclaimed(island).join();
        assertEquals(1, rows.size());
        assertEquals("material:IRON_INGOT:8", rows.getFirst().loot());
    }

    /**
     * The caller announces the reward only on 1. A counter can reach the same
     * milestone twice after a crash rewinds progress to the last checkpoint, and
     * announcing on the no-op would promise loot that DO NOTHING never stored.
     */
    @Test
    void insertIfAbsentReportsOneOnlyForTheRowItActuallyCreated() {
        assertEquals(1, dao.insertIfAbsent(island, "poczatek:100", "material:IRON_INGOT:8", 1L).join());
        assertEquals(0, dao.insertIfAbsent(island, "poczatek:100", "material:DIAMOND:99", 2L).join());
        assertEquals(1, dao.insertIfAbsent(island, "poczatek:200", "material:COAL:4", 3L).join());
    }

    @Test
    void claimUpdatesExactlyOneRowAndSecondClaimReturnsZero() {
        dao.insertIfAbsent(island, "poczatek:100", "material:IRON_INGOT:8", 1L).join();
        assertEquals(1, dao.claim(island, "poczatek:100", 50L).join());
        assertEquals(0, dao.claim(island, "poczatek:100", 60L).join());
        assertEquals(0, dao.listUnclaimed(island).join().size());
    }

    @Test
    void revertRestoresUnclaimedOnlyForMatchingTimestamp() {
        dao.insertIfAbsent(island, "poczatek:100", "material:IRON_INGOT:8", 1L).join();
        dao.claim(island, "poczatek:100", 50L).join();

        assertEquals(0, dao.revert(island, "poczatek:100", 999L).join());
        assertEquals(1, dao.revert(island, "poczatek:100", 50L).join());
        assertEquals(1, dao.listUnclaimed(island).join().size());
    }

    @Test
    void lootEncodingRoundTripsCustomItemsWithNamespacedIds() {
        List<OneBlockContent.MilestoneReward> loot = List.of(
                new OneBlockContent.MilestoneReward("skyblock:token/silver_lotus", null, 2, 40.0),
                new OneBlockContent.MilestoneReward(null, Material.IRON_INGOT, 8, 60.0));
        String encoded = OneBlockLoot.encode(loot);
        List<OneBlockLoot.Entry> decoded = OneBlockLoot.decode(encoded);
        assertEquals("skyblock:token/silver_lotus", decoded.get(0).item());
        assertEquals(2, decoded.get(0).amount());
        assertEquals(Material.IRON_INGOT, decoded.get(1).material());
        assertEquals(8, decoded.get(1).amount());
    }

    private static final class SqliteSql implements SqlService {
        private final Connection connection;

        private SqliteSql() throws SQLException {
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
