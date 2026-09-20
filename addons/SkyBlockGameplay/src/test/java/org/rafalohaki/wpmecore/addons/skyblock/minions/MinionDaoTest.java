package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;

import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;


import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinionDaoTest {

    private SingleConnectionSqlService sql;
    private MinionDao dao;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        dao = new MinionDao(sql);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    private static MinionRecord sample(UUID islandId, String world, double x, double y, double z) {
        return new MinionRecord(UUID.randomUUID(), islandId, "diamond", 3, world,
                x, y, z, Map.of("DIAMOND", 12L), null, 0L, true,
                null, null, null, 40L,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    @Test
    void insertAndLoadRoundTripsAllColumns() {
        UUID islandId = UUID.randomUUID();
        MinionRecord record = sample(islandId, "skyblock_world", 12.5, 64.0, -7.5);
        assertTrue(dao.tryInsertWithinLimit(record, 5).join());

        MinionRecord loaded = dao.loadAll().join().getFirst();
        assertEquals(record.minionId(), loaded.minionId());
        assertEquals(islandId, loaded.islandId());
        assertEquals("diamond", loaded.typeId());
        assertEquals(3, loaded.tier());
        assertEquals("skyblock_world", loaded.world());
        assertEquals(12.5, loaded.x());
        assertEquals(Map.of("DIAMOND", 12L), loaded.storage());
        assertTrue(loaded.compactorEnabled());
        assertEquals(40L, loaded.totalGenerated());
        assertNull(loaded.fuelType());
        assertNull(loaded.linkedChestX());
    }

    @Test
    void tryInsertWithinLimitRejectsInsertPastCapAtomically() {
        UUID islandId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            assertTrue(dao.tryInsertWithinLimit(sample(islandId, "w", i, 64, i), 5).join());
        }
        assertFalse(dao.tryInsertWithinLimit(sample(islandId, "w", 99, 64, 99), 5).join());
        assertEquals(5, dao.loadAll().join().size());
    }

    @Test
    void saveUpsertsStorageAndTier() {
        UUID islandId = UUID.randomUUID();
        MinionRecord record = sample(islandId, "w", 1.5, 64, 1.5);
        dao.tryInsertWithinLimit(record, 5).join();

        MinionRecord updated = record.withTier(4)
                .withStorage(Map.of("DIAMOND_BLOCK", 2L))
                .withFuel("coal", System.currentTimeMillis() + 60_000L);
        dao.save(updated).join();

        MinionRecord loaded = dao.findById(record.minionId()).join().orElseThrow();
        assertEquals(4, loaded.tier());
        assertEquals(Map.of("DIAMOND_BLOCK", 2L), loaded.storage());
        assertEquals("coal", loaded.fuelType());
        assertTrue(loaded.fuelExpiresAt() > System.currentTimeMillis());
    }

    @Test
    void deleteByIslandRemovesOnlyThatIsland() {
        UUID islandA = UUID.randomUUID();
        UUID islandB = UUID.randomUUID();
        dao.tryInsertWithinLimit(sample(islandA, "w", 1, 64, 1), 5).join();
        dao.tryInsertWithinLimit(sample(islandB, "w", 2, 64, 2), 5).join();

        dao.deleteByIsland(islandA).join();

        assertEquals(1, dao.loadAll().join().size());
        assertEquals(islandB, dao.loadAll().join().getFirst().islandId());
        assertTrue(dao.findByIsland(islandA).join().isEmpty());
    }

}
