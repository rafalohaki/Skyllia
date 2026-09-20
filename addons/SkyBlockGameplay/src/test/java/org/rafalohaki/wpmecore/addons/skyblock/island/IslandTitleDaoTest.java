package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zapis warunkowy wiersza tytułu (migracja #13). Ten DAO jest jedynym miejscem,
 * w którym rozstrzyga się, że spóźniony zapis nie cofa wiersza — a od tego
 * zależy cały kontrakt priorytetów w {@link IslandTitleService}.
 */
class IslandTitleDaoTest {

    private SingleConnectionSqlService sql;
    private IslandTitleDao dao;
    private final UUID island = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        dao = new IslandTitleDao.Sql(sql);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void missingRowReadsAsEmptyAndFirstWriteCreatesIt() {
        assertEquals(Optional.empty(), dao.find(island).join());

        assertTrue(dao.writeIfCurrent(island, "Rybacka", "prestige:1", null, 1_000L).join());

        assertEquals(new IslandTitleDao.Stored("Rybacka", "prestige:1"),
                dao.find(island).join().orElseThrow());
    }

    @Test
    void writeAgainstAStaleSourceIsRejected() {
        dao.writeIfCurrent(island, "Rybacka", "prestige:1", null, 1_000L).join();

        assertFalse(dao.writeIfCurrent(island, "Kupiecka", "prestige:2", "prestige:9", 2_000L).join(),
                "oczekiwane źródło nie zgadza się z wierszem — zapis musi przepaść");
        assertFalse(dao.writeIfCurrent(island, "Kupiecka", "prestige:2", null, 2_000L).join(),
                "wiersz już istnieje, więc wstawienie też nie może przejść");
        assertEquals(new IslandTitleDao.Stored("Rybacka", "prestige:1"),
                dao.find(island).join().orElseThrow());
    }

    @Test
    void writeAgainstTheCurrentSourceUpdatesTheRow() {
        dao.writeIfCurrent(island, "Rybacka", "prestige:1", null, 1_000L).join();

        assertTrue(dao.writeIfCurrent(island, "Mistrz Sezonu", "season:7:rank1",
                "prestige:1", 2_000L).join());

        assertEquals(new IslandTitleDao.Stored("Mistrz Sezonu", "season:7:rank1"),
                dao.find(island).join().orElseThrow());
        assertEquals(2_000L, updatedAt());
    }

    @Test
    void deleteOnlyMatchesItsOwnSource() {
        dao.writeIfCurrent(island, "Rybacka", "prestige:1", null, 1_000L).join();

        assertFalse(dao.deleteIfSource(island, "lotus:master").join());
        assertTrue(dao.find(island).join().isPresent());

        assertTrue(dao.deleteIfSource(island, "prestige:1").join());
        assertEquals(Optional.empty(), dao.find(island).join());
    }

    @Test
    void deleteAllDropsTheRowWhateverItsSource() {
        dao.writeIfCurrent(island, "Rybacka", "prestige:1", null, 1_000L).join();

        dao.deleteAll(island).join();

        assertEquals(Optional.empty(), dao.find(island).join(),
                "kasowanie wyspy nie negocjuje źródła — wiersz ma zniknąć");
    }

    private long updatedAt() {
        return sql.queryOne("SELECT updated_at FROM wpme_sb_island_titles WHERE island_id = ?",
                rs -> rs.getLong(1), island.toString()).join().orElse(-1L);
    }
}
