package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-1: dzienny licznik punktów na prawdziwym SQLite. Dowodzi, że migracja #11
 * tworzy tabelę z PK {@code (player_uuid, day, channel, operation_id)} i indeksem
 * sumy dnia: powtórka tego samego zdarzenia w tym samym dniu nie dubluje punktów,
 * nowy dzień i inny kanał to osobne pule.
 */
class SeasonDailyPointsDaoTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DAY = "2026-09-10";

    private SingleConnectionSqlService sql;
    private SeasonDailyPointsDao dao;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        dao = new SeasonDailyPointsDao.Sql(sql, () -> 7);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    private long scalar(String query) {
        return sql.query(query, result -> result.getLong(1)).join().get(0);
    }

    @Test
    void migrationCreatesTheTableWithTheLookupIndex() {
        assertFalse(sql.query("""
                        SELECT name FROM sqlite_master
                        WHERE type = 'table' AND name = 'wpme_sb_season_daily_points'
                        """, result -> result.getString(1)).join().isEmpty(),
                "migracja #11 musi utworzyć tabelę dnia");
        assertFalse(sql.query("""
                        SELECT name FROM sqlite_master
                        WHERE type = 'index'
                          AND name = 'idx_wpme_sb_season_daily_points_lookup'
                        """, result -> result.getString(1)).join().isEmpty(),
                "suma dnia musi mieć indeks (player_uuid, day, channel)");
    }

    @Test
    void sameOperationInTheSameDayIsIgnored() {
        assertTrue(dao.insertIfAbsent(PLAYER, DAY, "fish", "emf-fish:x:1", 12).join());
        assertFalse(dao.insertIfAbsent(PLAYER, DAY, "fish", "emf-fish:x:1", 12).join(),
                "PK (gracz, dzień, kanał, operationId) czyni powtórkę no-opem");
        assertEquals(12L, dao.sumToday(PLAYER, DAY, "fish").join());
        assertEquals(1L, scalar("SELECT COUNT(*) FROM wpme_sb_season_daily_points"));
    }

    @Test
    void nextDayAndOtherChannelAreSeparatePools() {
        dao.insertIfAbsent(PLAYER, DAY, "fish", "op-1", 100).join();
        assertTrue(dao.insertIfAbsent(PLAYER, DAY, "quest", "op-1", 40).join());
        assertTrue(dao.insertIfAbsent(PLAYER, "2026-09-11", "fish", "op-1", 30).join());

        assertEquals(100L, dao.sumToday(PLAYER, DAY, "fish").join());
        assertEquals(40L, dao.sumToday(PLAYER, DAY, "quest").join());
        assertEquals(30L, dao.sumToday(PLAYER, "2026-09-11", "fish").join());
    }

    @Test
    void emptyDayHasNoPoints() {
        dao.insertIfAbsent(PLAYER, DAY, "fish", "op-1", 100).join();
        assertEquals(0L, dao.sumToday(PLAYER, DAY, "quest").join());
        assertEquals(0L, dao.sumToday(UUID.randomUUID(), DAY, "fish").join());
    }

    @Test
    void sumQueryUsesTheDayIndexInsteadOfScanningTheTable() {
        List<String> plan = sql.query("""
                EXPLAIN QUERY PLAN
                SELECT COALESCE(SUM(points), 0) FROM wpme_sb_season_daily_points
                WHERE player_uuid = 'x' AND day = '2026-09-10' AND channel = 'fish'
                """, result -> {
            StringBuilder line = new StringBuilder();
            int columns = result.getMetaData().getColumnCount();
            for (int index = 1; index <= columns; index++) {
                line.append(result.getString(index)).append(' ');
            }
            return line.toString();
        }).join();

        String detail = String.join(" | ", plan);
        assertTrue(detail.contains("idx_wpme_sb_season_daily_points_lookup"),
                "suma dnia musi iść indeksem, a była: " + detail);
        assertFalse(detail.contains("SCAN wpme_sb_season_daily_points"),
                "pełny skan tabeli dnia na region threadu: " + detail);
    }
}
