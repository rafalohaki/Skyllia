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

class ChunkerDaoTest {

    private SingleConnectionSqlService sql;
    private ChunkerDao dao;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        dao = new ChunkerDao(sql);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void createAndLoadAll() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ChunkerRecord record = new ChunkerRecord(
                islandId, "skyblock_world", 10, 20, 160, 64, 320, ownerId);

        dao.create(record).join();

        List<ChunkerRecord> all = dao.loadAll().join();
        assertEquals(1, all.size());
        ChunkerRecord loaded = all.getFirst();
        assertEquals(islandId, loaded.islandId());
        assertEquals("skyblock_world", loaded.world());
        assertEquals(10, loaded.chunkX());
        assertEquals(20, loaded.chunkZ());
        assertEquals(160, loaded.chestX());
        assertEquals(64, loaded.chestY());
        assertEquals(320, loaded.chestZ());
        assertEquals(ownerId, loaded.ownerId());
        assertEquals(new ChunkerRecord.ChunkKey("skyblock_world", 10, 20), loaded.chunkKey());
        assertEquals(new ChunkerRecord.LocationKey("skyblock_world", 160, 64, 320), loaded.chestLocationKey());
    }

    @Test
    void saveUpsertUpdatesExistingChunker() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ChunkerRecord record = new ChunkerRecord(
                islandId, "skyblock_world", 5, 5, 80, 70, 80, ownerId);
        dao.save(record).join();

        UUID newOwner = UUID.randomUUID();
        ChunkerRecord updated = new ChunkerRecord(
                islandId, "skyblock_world", 5, 5, 85, 75, 85, newOwner);
        dao.save(updated).join();

        List<ChunkerRecord> all = dao.loadAll().join();
        assertEquals(1, all.size());
        assertEquals(85, all.getFirst().chestX());
        assertEquals(75, all.getFirst().chestY());
        assertEquals(85, all.getFirst().chestZ());
        assertEquals(newOwner, all.getFirst().ownerId());
    }

    @Test
    void updateModifiesExistingChunker() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ChunkerRecord record = new ChunkerRecord(
                islandId, "skyblock_world", 1, 1, 16, 60, 16, ownerId);
        dao.create(record).join();

        UUID newOwner = UUID.randomUUID();
        ChunkerRecord updated = new ChunkerRecord(
                islandId, "skyblock_world", 1, 1, 18, 62, 18, newOwner);
        dao.update(updated).join();

        Optional<ChunkerRecord> found = dao.findByChunk("skyblock_world", 1, 1).join();
        assertTrue(found.isPresent());
        assertEquals(18, found.get().chestX());
        assertEquals(62, found.get().chestY());
        assertEquals(18, found.get().chestZ());
        assertEquals(newOwner, found.get().ownerId());
    }

    @Test
    void findByIslandAndFindByChunk() {
        UUID island1 = UUID.randomUUID();
        UUID island2 = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        dao.create(new ChunkerRecord(island1, "skyblock_world", 1, 1, 16, 64, 16, owner)).join();
        dao.create(new ChunkerRecord(island1, "skyblock_world", 2, 2, 32, 64, 32, owner)).join();
        dao.create(new ChunkerRecord(island2, "skyblock_world", 3, 3, 48, 64, 48, owner)).join();

        List<ChunkerRecord> island1Chunkers = dao.findByIsland(island1).join();
        assertEquals(2, island1Chunkers.size());

        List<ChunkerRecord> island2Chunkers = dao.findByIsland(island2).join();
        assertEquals(1, island2Chunkers.size());

        Optional<ChunkerRecord> chunk1 = dao.findByChunk("skyblock_world", 1, 1).join();
        assertTrue(chunk1.isPresent());
        assertEquals(island1, chunk1.get().islandId());

        Optional<ChunkerRecord> missing = dao.findByChunk("skyblock_world", 99, 99).join();
        assertFalse(missing.isPresent());
    }

    @Test
    void deleteByIsland() {
        UUID island1 = UUID.randomUUID();
        UUID island2 = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        dao.create(new ChunkerRecord(island1, "world", 1, 1, 16, 64, 16, owner)).join();
        dao.create(new ChunkerRecord(island1, "world", 2, 2, 32, 64, 32, owner)).join();
        dao.create(new ChunkerRecord(island2, "world", 3, 3, 48, 64, 48, owner)).join();

        dao.deleteByIsland(island1).join();

        assertEquals(0, dao.findByIsland(island1).join().size());
        assertEquals(1, dao.findByIsland(island2).join().size());
    }

    @Test
    void deleteByLocation() {
        UUID island = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        dao.create(new ChunkerRecord(island, "world", 1, 1, 16, 64, 16, owner)).join();
        dao.create(new ChunkerRecord(island, "world", 2, 2, 32, 64, 32, owner)).join();

        dao.deleteByLocation("world", 16, 64, 16).join();

        assertEquals(1, dao.loadAll().join().size());
        assertFalse(dao.findByChunk("world", 1, 1).join().isPresent());
        assertTrue(dao.findByChunk("world", 2, 2).join().isPresent());
    }

    @Test
    void deleteByChunk() {
        UUID island = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        dao.create(new ChunkerRecord(island, "world", 1, 1, 16, 64, 16, owner)).join();

        dao.deleteByChunk("world", 1, 1).join();

        assertTrue(dao.loadAll().join().isEmpty());
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
