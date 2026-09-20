package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonPointDao;
import org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockTopRewardCoordinator.IslandScore;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Serwis na SQLite w pamięci: awans nagradza raz, brak awansu = brak wypłaty,
 * spadek xp nie obniża zapisanego poziomu.
 */
class IslandLevelServiceTest {

    private static final IslandLevel.Settings S = IslandLevel.Settings.DEFAULTS;

    private SingleConnectionSqlService sql;
    private LedgerService ledger;
    private IslandLevelService service;
    private final UUID island = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final List<IslandLevelService.LevelUp> levelUps = new ArrayList<>();

    // wejścia mutowalne — test kręci nimi między przeliczeniami
    private long bank;
    private int quests;
    private int minions;
    private int chapter;
    private long seasonPoints;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        ledger = new LedgerService(new LedgerDao(sql), 0L);
        service = new IslandLevelService(Logger.getLogger("test"), sql, ledger, S,
                () -> CompletableFuture.completedFuture(List.of(
                        new IslandScore(island, bank, quests, bank + 500L * quests, 1))),
                id -> minions,
                id -> chapter,
                () -> CompletableFuture.completedFuture(List.of(
                        new SeasonPointDao.TopRow(member, seasonPoints),
                        new SeasonPointDao.TopRow(UUID.randomUUID(), 9_999L))), // cudzy gracz bez wyspy
                player -> player.equals(member) ? Optional.of(island) : Optional.empty(),
                levelUps::add);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void levelUpPaysEveryCrossedLevelExactlyOnce() {
        // xp = 20000/100 + 2×250 + 1×500 + 100×2 = 1400 → poziom 1 (próg 2 to 1414)
        bank = 20_000L; quests = 0; minions = 2; chapter = 1; seasonPoints = 100L;

        service.recompute(Set.of(island)).join();

        // Pierwsze przeliczenie = punkt odniesienia: poziom zapisany, ale bez wypłaty
        // (istniejąca wyspa nie dostaje nagród za poziomy 1..N naraz — rollout 2026-09-06).
        IslandLevelService.Snapshot first = service.cached(island).orElseThrow();
        assertEquals(1, first.level());
        assertEquals(1_400L, first.xp());
        assertEquals(0L, ledger.authoritativeIslandBalance(island).join());
        assertEquals(0, levelUps.size());
        assertEquals(1, storedLevel());

        // te same dane → brak awansu, brak wypłaty, brak komunikatu
        service.recompute(Set.of(island)).join();
        assertEquals(0L, ledger.authoritativeIslandBalance(island).join());
        assertEquals(0, levelUps.size());

        // skok o dwa poziomy naraz: nagroda 2×200 + 3×200 = 1000 w jednym komunikacie
        minions = 8; // xp = 200 + 2000 + 500 + 200 = 2900 → poziom 3 (próg 2598, próg 4 to 4000)
        service.recompute(Set.of(island)).join();
        assertEquals(3, service.cached(island).orElseThrow().level());
        assertEquals(1_000L, ledger.authoritativeIslandBalance(island).join());
        assertEquals(new IslandLevelService.LevelUp(island, 3, 1_000L), levelUps.getLast());

        // ta sama nagroda po „restarcie” (nowy serwis, ten sam txId) nie płaci drugi raz
        sql.update("UPDATE wpme_sb_island_level SET level = 1 WHERE island_id = ?", island.toString()).join();
        service.recompute(Set.of(island)).join();
        assertEquals(1_000L, ledger.authoritativeIslandBalance(island).join());
        assertEquals(3, storedLevel());
    }

    @Test
    void belowFirstThresholdNothingIsPaid() {
        bank = 100L; quests = 0; minions = 0; chapter = 0; seasonPoints = 0L;

        service.recompute(Set.of(island)).join();

        assertEquals(0, service.cached(island).orElseThrow().level());
        assertEquals(0L, ledger.authoritativeIslandBalance(island).join());
        assertTrue(levelUps.isEmpty());
        assertEquals(0, storedLevel());
    }

    @Test
    void xpDropKeepsStoredLevel() {
        // punkt odniesienia bez nagrody (pierwsze przeliczenie), potem awans 0 → 2 = 200 + 400
        bank = 0L; quests = 0; minions = 0; chapter = 0; seasonPoints = 0L;
        service.recompute(Set.of(island)).join();
        bank = 0L; quests = 0; minions = 6; chapter = 0; seasonPoints = 0L; // 1500 xp → poziom 2
        service.recompute(Set.of(island)).join();
        assertEquals(2, service.cached(island).orElseThrow().level());
        long paid = ledger.authoritativeIslandBalance(island).join();
        assertEquals(600L, paid);

        minions = 0; // xp spada do 0
        service.recompute(Set.of(island)).join();

        IslandLevelService.Snapshot after = service.cached(island).orElseThrow();
        assertEquals(2, after.level());
        assertEquals(0L, after.xp());
        assertEquals(2, storedLevel());
        assertEquals(paid, ledger.authoritativeIslandBalance(island).join());
        assertEquals(1, levelUps.size());
        assertEquals(0.0D, IslandLevel.progress(S, after.level(), after.xp()));
    }

    private int storedLevel() {
        return sql.queryOne("SELECT level FROM wpme_sb_island_level WHERE island_id = ?",
                rs -> rs.getInt(1), island.toString()).join().orElse(-1);
    }
}
