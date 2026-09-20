package org.rafalohaki.wpmecore.addons.skyblock.reward;

import org.bukkit.permissions.Permissible;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.SkyBlockSettings;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.perks.RankPerks;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Codzienna nagroda na SQLite w pamięci: seria, cap, reset, idempotencja, lotos, bonus rangi. */
class DailyRewardServiceTest {

    private static final SkyBlockSettings.DailyRewardSettings SETTINGS =
            new SkyBlockSettings.DailyRewardSettings(true, 250L, 50L, 7, "skyblock:token/silver_lotus");
    private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 1);

    private SingleConnectionSqlService sql;
    private LedgerService ledger;
    private DailyRewardService service;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        ledger = new LedgerService(new LedgerDao(sql), 0L);
        service = new DailyRewardService(sql, ledger, SETTINGS);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    private DailyRewardService.Claim claim(UUID player, LocalDate day) {
        Optional<DailyRewardService.Claim> claim = service.claim(player, day, 0).join();
        assertTrue(claim.isPresent(), "odbiór " + day + " powinien się udać");
        return claim.get();
    }

    @Test
    void firstClaimStartsStreakAtOneAndPaysBasePlusOneDay() {
        UUID player = UUID.randomUUID();

        DailyRewardService.Claim claim = claim(player, DAY_1);

        assertEquals(1, claim.streak());
        assertEquals(300L, claim.coins());
        assertFalse(claim.lotus());
        assertEquals(300L, ledger.playerBalance(player));
    }

    @Test
    void consecutiveDaysGrowStreakAndCoinsUpToCap() {
        UUID player = UUID.randomUUID();
        for (int day = 1; day <= 9; day++) {
            DailyRewardService.Claim claim = claim(player, DAY_1.plusDays(day - 1));
            assertEquals(day, claim.streak(), "seria dnia " + day);
            assertEquals(250L + 50L * Math.min(day, 7), claim.coins(), "kwota dnia " + day);
        }
        assertEquals(600L, service.coinsFor(7, 0));
        assertEquals(600L, service.coinsFor(30, 0));
    }

    @Test
    void twoDayGapResetsStreakToOne() {
        UUID player = UUID.randomUUID();
        claim(player, DAY_1);
        assertEquals(2, claim(player, DAY_1.plusDays(1)).streak());

        DailyRewardService.Claim afterGap = claim(player, DAY_1.plusDays(3));

        assertEquals(1, afterGap.streak());
        assertEquals(300L, afterGap.coins());
    }

    @Test
    void secondClaimSameDayIsEmptyAndPaysNothing() {
        UUID player = UUID.randomUUID();
        claim(player, DAY_1);

        assertTrue(service.claim(player, DAY_1, 0).join().isEmpty(), "drugi odbiór tego samego dnia");

        assertEquals(300L, ledger.playerBalance(player), "bez drugiej wypłaty");
        DailyRewardService.Row row = service.find(player).join().orElseThrow();
        assertEquals(1, row.claimedTotal());
        assertEquals(DAY_1.toString(), row.lastClaimDay());
    }

    @Test
    void everySeventhDayOfStreakGrantsLotus() {
        UUID player = UUID.randomUUID();
        for (int day = 1; day <= 14; day++) {
            DailyRewardService.Claim claim = claim(player, DAY_1.plusDays(day - 1));
            assertEquals(day % 7 == 0, claim.lotus(), "lotos dnia " + day);
        }
    }

    @Test
    void rankBonusFromPermissionNodeRaisesCoins() {
        Permissible svip = mock(Permissible.class);
        when(svip.hasPermission(anyString())).thenReturn(false);
        when(svip.hasPermission("skyblockgameplay.daily.bonus.20")).thenReturn(true);
        Permissible nobody = mock(Permissible.class);
        when(nobody.hasPermission(anyString())).thenReturn(false);

        assertEquals(20, RankPerks.dailyBonusPercent(svip));
        assertEquals(0, RankPerks.dailyBonusPercent(nobody));

        UUID player = UUID.randomUUID();
        DailyRewardService.Claim claim = service.claim(player, DAY_1, RankPerks.dailyBonusPercent(svip))
                .join().orElseThrow();
        assertEquals(360L, claim.coins(), "300 × 1,2");
        assertEquals(360L, ledger.playerBalance(player));
    }
}
