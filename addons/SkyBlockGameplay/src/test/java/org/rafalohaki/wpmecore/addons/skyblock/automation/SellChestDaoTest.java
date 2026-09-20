package org.rafalohaki.wpmecore.addons.skyblock.automation;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SellChestDaoTest {

    private SingleConnectionSqlService sql;
    private SellChestDao dao;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        dao = new SellChestDao(sql);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void createAndLoadAll() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        SellChestRecord record = new SellChestRecord(
                islandId, "skyblock_world", 100, 64, 200, ownerId, now);

        dao.create(record).join();

        List<SellChestRecord> all = dao.loadAll().join();
        assertEquals(1, all.size());
        SellChestRecord loaded = all.getFirst();
        assertEquals(islandId, loaded.islandId());
        assertEquals("skyblock_world", loaded.world());
        assertEquals(100, loaded.x());
        assertEquals(64, loaded.y());
        assertEquals(200, loaded.z());
        assertEquals(ownerId, loaded.ownerId());
        assertEquals(now, loaded.createdAt());
        assertEquals(new SellChestRecord.LocationKey("skyblock_world", 100, 64, 200), loaded.locationKey());
    }

    @Test
    void saveUpsertUpdatesExistingSellChest() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        SellChestRecord record = new SellChestRecord(
                islandId, "skyblock_world", 10, 65, 10, ownerId, 1000L);
        dao.save(record).join();

        UUID newOwner = UUID.randomUUID();
        SellChestRecord updated = new SellChestRecord(
                islandId, "skyblock_world", 10, 65, 10, newOwner, 2000L);
        dao.save(updated).join();

        List<SellChestRecord> all = dao.loadAll().join();
        assertEquals(1, all.size());
        assertEquals(newOwner, all.getFirst().ownerId());
        assertEquals(2000L, all.getFirst().createdAt());
    }

    @Test
    void updateModifiesExistingSellChest() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        SellChestRecord record = new SellChestRecord(
                islandId, "skyblock_world", 50, 70, 50, ownerId, 5000L);
        dao.create(record).join();

        UUID newOwner = UUID.randomUUID();
        SellChestRecord updated = new SellChestRecord(
                islandId, "skyblock_world", 50, 70, 50, newOwner, 9000L);
        dao.update(updated).join();

        Optional<SellChestRecord> found = dao.findByLocation("skyblock_world", 50, 70, 50).join();
        assertTrue(found.isPresent());
        assertEquals(newOwner, found.get().ownerId());
        assertEquals(9000L, found.get().createdAt());
    }

    @Test
    void findByIslandAndFindByLocation() {
        UUID island1 = UUID.randomUUID();
        UUID island2 = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        dao.create(new SellChestRecord(island1, "world", 1, 64, 1, owner, 100L)).join();
        dao.create(new SellChestRecord(island1, "world", 2, 64, 2, owner, 200L)).join();
        dao.create(new SellChestRecord(island2, "world", 3, 64, 3, owner, 300L)).join();

        List<SellChestRecord> island1Chests = dao.findByIsland(island1).join();
        assertEquals(2, island1Chests.size());

        List<SellChestRecord> island2Chests = dao.findByIsland(island2).join();
        assertEquals(1, island2Chests.size());

        Optional<SellChestRecord> chest = dao.findByLocation("world", 1, 64, 1).join();
        assertTrue(chest.isPresent());
        assertEquals(island1, chest.get().islandId());

        Optional<SellChestRecord> missing = dao.findByLocation("world", 99, 99, 99).join();
        assertFalse(missing.isPresent());
    }

    @Test
    void deleteByIsland() {
        UUID island1 = UUID.randomUUID();
        UUID island2 = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        dao.create(new SellChestRecord(island1, "world", 1, 64, 1, owner, 100L)).join();
        dao.create(new SellChestRecord(island1, "world", 2, 64, 2, owner, 200L)).join();
        dao.create(new SellChestRecord(island2, "world", 3, 64, 3, owner, 300L)).join();

        dao.deleteByIsland(island1).join();

        assertEquals(0, dao.findByIsland(island1).join().size());
        assertEquals(1, dao.findByIsland(island2).join().size());
    }

    @Test
    void deleteByLocation() {
        UUID island = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        dao.create(new SellChestRecord(island, "world", 10, 64, 10, owner, 100L)).join();
        dao.create(new SellChestRecord(island, "world", 20, 64, 20, owner, 200L)).join();

        dao.deleteByLocation("world", 10, 64, 10).join();

        assertEquals(1, dao.loadAll().join().size());
        assertFalse(dao.findByLocation("world", 10, 64, 10).join().isPresent());
        assertTrue(dao.findByLocation("world", 20, 64, 20).join().isPresent());
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
