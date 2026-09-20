package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresja do defektu znalezionego 2026-09-10 na labie: {@code
 * wpme_sb_reward_claims} ma GLOBALNY unikalny indeks na {@code operation_id}
 * ({@code ux_wpme_sb_reward_claims_operation}), więc identyfikator operacji
 * bez UUID gracza zajmował jedno miejsce w całej sieci — pierwszy gracz
 * dostawał punkty, każdy następny zero (E2E: pierwszy 323 pkt, kolejni 0, mimo
 * wierszy w tabeli dnia).
 *
 * <p>Ten test pilnuje obu stron kontraktu: dwóch RÓŻNYCH graczy z tym samym
 * kanałem, dniem i surowym id musi dostać punkty (bo usługa dokłada UUID do
 * identyfikatora operacji), a powtórka u tego samego gracza musi zostać
 * odrzucona (idempotencja).
 */
class SeasonDailyPointsPlayerScopeTest {

    private static final int SEASON = 7;
    private static final ZoneId ZONE = ZoneOffset.UTC;

    private SingleConnectionSqlService sql;
    private SeasonDailyPointsService service;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        SeasonPointDao pointsDao = new SqlSeasonPointDao(sql, () -> SEASON);
        DefaultSeasonPointService points = new DefaultSeasonPointService(
                pointsDao, () -> SEASON, () -> 0L, ZONE,
                1.0D, () -> DayOfWeek.THURSDAY);
        SeasonDailyPointsDao dailyDao = new SeasonDailyPointsDao.Sql(sql, () -> SEASON);
        service = new SeasonDailyPointsService.Impl(dailyDao, points,
                SeasonDailyPointsCaps.load(null), Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZONE));
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void twoPlayersWithTheSameOperationIdBothGetPoints() {
        UUID first = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
        UUID second = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");

        assertEquals(100L, service.awardDaily(first, "quest", 100, "eco-daily:dzien_miner").join(),
                "pierwszy gracz musi dostać pełne punkty");
        assertEquals(100L, service.awardDaily(second, "quest", 100, "eco-daily:dzien_miner").join(),
                "drugi gracz z tym samym id operacji nie może zostać zablokowany globalnym indeksem");
        assertEquals(200L, pointsOf(first) + pointsOf(second));
    }

    @Test
    void repeatingTheSameOperationForTheSamePlayerIsIdempotent() {
        UUID player = UUID.fromString("cccccccc-1111-2222-3333-444444444444");
        assertEquals(50L, service.awardDaily(player, "quest", 50, "eco-daily:powtorka").join());
        assertEquals(0L, service.awardDaily(player, "quest", 50, "eco-daily:powtorka").join(),
                "powtórka tego samego zdarzenia nie może dublować punktów");
        assertEquals(50L, pointsOf(player));
    }

    @Test
    void aBareOperationIdWithoutPlayerIsStillRejectedGlobally() {
        // Kontrola negatywna: gdyby ktoś kiedyś ominął warstwę usługi i wpisał id
        // bez UUID, globalny indeks nadal odrzuci drugi wiersz — pokazujemy, że
        // wymóg „id niesie gracza" nie jest ozdobą.
        UUID first = UUID.fromString("dddddddd-1111-2222-3333-444444444444");
        UUID second = UUID.fromString("eeeeeeee-1111-2222-3333-444444444444");
        SeasonPointDao dao = new SqlSeasonPointDao(sql, () -> SEASON);
        assertTrue(dao.addPoints(first, SEASON, 10, "goly-id", false).join());
        assertFalse(dao.addPoints(second, SEASON, 10, "goly-id", false).join(),
                "globalny indeks operation_id nie zna gracza — dlatego id musi go nieść");
    }

    private long pointsOf(UUID player) {
        return sql.query("SELECT COALESCE(SUM(points), 0) FROM wpme_sb_season_points WHERE player_uuid = ?",
                result -> result.getLong(1), player.toString()).join().get(0);
    }
}
