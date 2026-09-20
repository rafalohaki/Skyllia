package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;

import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;


import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.rafalohaki.wpmecore.addons.skyblock.SkyBlockSettings;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandTitleDao;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandTitleService;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandBounds;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandCapabilities;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.LifecycleState;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItem;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.SqlDialect;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("mockbukkit")
class SkyBlockTopRewardCoordinatorTest {

    /** Canonical OneBlock chapter ids (oneblock.yml order), used for migration 7. */
    private static final List<String> PHASE_IDS = List.of(
            "poczatek", "podziemia", "zima", "pustynia", "dzungla", "pieklo", "kres");

    private ServerMock server;
    private JavaPlugin plugin;
    private SingleConnectionSqlService sql;
    private LedgerService ledger;
    private SkylliaIntegration skyllia;
    private MiniMessage miniMessage;
    private MockCustomItemService customItemService;
    private SkyBlockTopRewardCoordinator coordinator;

    private final UUID island1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID island2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID island3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID island4 = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private PlayerMock player1;
    private PlayerMock player2;
    private PlayerMock player3;
    private PlayerMock player4;
    private PlayerMock playerNoIsland;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("TopRewardTestPlugin");
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();

        ledger = new LedgerService(new LedgerDao(sql), 1000L);
        ledger.init();

        skyllia = mock(SkylliaIntegration.class);
        miniMessage = MiniMessage.miniMessage();
        customItemService = new MockCustomItemService();
        customItemService.register("skyblock:token/diamond_lotus", Material.PAPER, "<aqua>Diamentowy Lotos</aqua>");

        coordinator = new SkyBlockTopRewardCoordinator(
                plugin, sql, ledger, skyllia, miniMessage, customItemService);

        player1 = server.addPlayer();
        player2 = server.addPlayer();
        player3 = server.addPlayer();
        player4 = server.addPlayer();
        playerNoIsland = server.addPlayer();

        mockPlayerIsland(player1, island1);
        mockPlayerIsland(player2, island2);
        mockPlayerIsland(player3, island3);
        mockPlayerIsland(player4, island4);
        when(skyllia.islandOf(playerNoIsland.getUniqueId())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        sql.shutdown();
    }



    private void seedBank(UUID islandId, long minor) {
        sql.update("INSERT INTO wpme_sb_accounts (account_key, balance_minor, updated_at) VALUES (?, ?, ?)",
                "island:" + islandId, minor, System.currentTimeMillis()).join();
    }

    private void seedMembership(UUID islandId, UUID playerId) {
        // membership ma FK na profil wyspy — najpierw profil, potem członkostwo
        sql.update("INSERT INTO wpme_sb_island_profiles (island_id, owner_uuid, mode, status, created_at, updated_at)"
                        + " VALUES (?, ?, 'ONEBLOCK', 'ACTIVE', ?, ?)",
                islandId.toString(), playerId.toString(),
                System.currentTimeMillis(), System.currentTimeMillis()).join();
        sql.update("INSERT INTO wpme_sb_island_membership (island_id, player_uuid, role, status, joined_at, updated_at)"
                        + " VALUES (?, ?, 'OWNER', 'ACTIVE', ?, ?)",
                islandId.toString(), playerId.toString(),
                System.currentTimeMillis(), System.currentTimeMillis()).join();
    }

    private void seedSeasonQuests(UUID playerId, int questsDone) {
        sql.update("INSERT INTO wpme_sb_season_points (season_id, player_uuid, points, quests_done, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                1, playerId.toString(), questsDone * 100L, questsDone, System.currentTimeMillis()).join();
    }

    /** Wiersz w tabeli wyłączonego systemu — musi zostać zignorowany. */
    private void seedDeadQuestProgress(UUID islandId, int rows) {
        for (int i = 0; i < rows; i++) {
            sql.update("INSERT INTO wpme_sb_quest_progress (account_key, period_key, quest_id, progress, rewarded, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    "island:" + islandId, "2026-08-15", "dead_" + i, 10L, 1, System.currentTimeMillis()).join();
        }
    }

    private void mockPlayerIsland(Player player, UUID islandId) {
        IslandSnapshot snapshot = new IslandSnapshot(
                islandId, player.getUniqueId(), LifecycleState.active(),
                IslandBounds.of(0, 0, 100.0), IslandCapabilities.allEnabled(), 1L);
        IslandView view = new IslandView(snapshot, IslandRole.OWNER);
        when(skyllia.islandOf(player.getUniqueId())).thenReturn(Optional.of(view));
    }

    private void setupIslandData() {
        // Island 1: Bank 10,000, 10 completed quests -> Score: 10,000 + 10 * 500 = 15,000
        sql.update("INSERT INTO wpme_sb_accounts (account_key, balance_minor, updated_at) VALUES (?, ?, ?)",
                "island:" + island1, 10_000L, System.currentTimeMillis()).join();
        for (int i = 1; i <= 10; i++) {
            sql.update("INSERT INTO wpme_sb_quest_progress (account_key, period_key, quest_id, progress, rewarded, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                    "island:" + island1, "2026-08-15", "quest_" + i, 10L, 1, System.currentTimeMillis()).join();
        }

        // Od 2026-09-10 składnik „zadania" pochodzi z ŻYWEGO źródła: liczby
        // ukończonych zadań sezonowych CZŁONKÓW wyspy
        // (wpme_sb_season_points.quests_done). Tabela wpme_sb_quest_progress
        // należy do wyłączonego w trybie eco DailyQuestService — fixture wstawia
        // więc jej wiersze jako DOWÓD MARTWEGO ŹRÓDŁA (nie mogą wpływać na wynik).
        seedMembership(island1, player1.getUniqueId());
        seedMembership(island2, player2.getUniqueId());
        seedMembership(island3, player3.getUniqueId());
        seedMembership(island4, player4.getUniqueId());
        // Banki wysp (island1 wpisany wyżej): 2 → 50k, 3 → 20k, 4 → 5k.
        seedBank(island2, 50_000L);
        seedBank(island3, 20_000L);
        seedBank(island4, 5_000L);
        seedSeasonQuests(player1.getUniqueId(), 10);
        seedSeasonQuests(player2.getUniqueId(), 2);
        seedSeasonQuests(player3.getUniqueId(), 5);
        seedSeasonQuests(player4.getUniqueId(), 1);
        // Śmieci po natywnym systemie: gdyby ranking znów sięgnął do tej tabeli,
        // liczby poniżej rozjechałyby asercje (island1 ma 10 zadań, ta tabela doda 99).
        seedDeadQuestProgress(island1, 99);

        // Nagrody wychodzą dopiero po zamknięciu sezonu — fixture odtwarza ten moment.
        // Próg czterech wysp z dorobkiem odpowiada dokładnie temu, co wstawiono wyżej.
        coordinator.setSeasonEligibility(
                new SkyBlockTopRewardCoordinator.SeasonEligibility(true, 1L, 4));
    }

    @Test
    @DisplayName("Scoring queries and ranking calculation correctly orders top islands")
    void scoringQueriesAndRankingCalculation() {
        setupIslandData();

        List<SkyBlockTopRewardCoordinator.IslandScore> ranking = coordinator.calculateRanking().join();
        assertEquals(4, ranking.size());

        // 1st place: Island 2 (51,000)
        SkyBlockTopRewardCoordinator.IslandScore first = ranking.get(0);
        assertEquals(island2, first.islandId());
        assertEquals(50_000L, first.bankBalance());
        assertEquals(2, first.completedQuests());
        assertEquals(51_000L, first.totalScore());
        assertEquals(1, first.rank());

        // 2nd place: Island 3 (22,500)
        SkyBlockTopRewardCoordinator.IslandScore second = ranking.get(1);
        assertEquals(island3, second.islandId());
        assertEquals(20_000L, second.bankBalance());
        assertEquals(5, second.completedQuests());
        assertEquals(22_500L, second.totalScore());
        assertEquals(2, second.rank());

        // 3rd place: Island 1 (15,000)
        SkyBlockTopRewardCoordinator.IslandScore third = ranking.get(2);
        assertEquals(island1, third.islandId());
        assertEquals(10_000L, third.bankBalance());
        assertEquals(10, third.completedQuests());
        assertEquals(15_000L, third.totalScore());
        assertEquals(3, third.rank());

        // 4th place: Island 4 (5,500)
        SkyBlockTopRewardCoordinator.IslandScore fourth = ranking.get(3);
        assertEquals(island4, fourth.islandId());
        assertEquals(5_000L, fourth.bankBalance());
        assertEquals(1, fourth.completedQuests());
        assertEquals(5_500L, fourth.totalScore());
        assertEquals(4, fourth.rank());

        // Top 3 limit
        List<SkyBlockTopRewardCoordinator.IslandScore> top3 = coordinator.getTopIslands(3).join();
        assertEquals(3, top3.size());
        assertEquals(island2, top3.get(0).islandId());
        assertEquals(island3, top3.get(1).islandId());
        assertEquals(island1, top3.get(2).islandId());
    }

    @Test
    @DisplayName("Top 3 islands can claim tiered rewards (Lotus + coins)")
    void top3IslandsCanClaimTieredRewards() {
        setupIslandData();

        // 1st place player (Island 2): 3x Lotus + 50,000 coins
        SkyBlockTopRewardCoordinator.ClaimResult result1 = coordinator.claimReward(player2).join();
        server.getScheduler().performOneTick();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, result1.status());
        assertEquals(1, result1.rankPosition());
        assertEquals(3, result1.diamondLotusGiven());
        assertEquals(50_000L, result1.coinsGiven());
        assertEquals(3, countCustomItems(player2, "skyblock:token/diamond_lotus"));
        assertEquals(50_000L + 1000L, ledger.playerBalance(player2.getUniqueId())); // 50k reward + 1k starting balance

        // 2nd place player (Island 3): 2x Lotus + 30,000 coins
        SkyBlockTopRewardCoordinator.ClaimResult result2 = coordinator.claimReward(player3).join();
        server.getScheduler().performOneTick();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, result2.status());
        assertEquals(2, result2.rankPosition());
        assertEquals(2, result2.diamondLotusGiven());
        assertEquals(30_000L, result2.coinsGiven());
        assertEquals(2, countCustomItems(player3, "skyblock:token/diamond_lotus"));
        assertEquals(30_000L + 1000L, ledger.playerBalance(player3.getUniqueId()));

        // 3rd place player (Island 1): 1x Lotus + 15,000 coins
        SkyBlockTopRewardCoordinator.ClaimResult result3 = coordinator.claimReward(player1).join();
        server.getScheduler().performOneTick();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, result3.status());
        assertEquals(3, result3.rankPosition());
        assertEquals(1, result3.diamondLotusGiven());
        assertEquals(15_000L, result3.coinsGiven());
        assertEquals(1, countCustomItems(player1, "skyblock:token/diamond_lotus"));
        assertEquals(15_000L + 1000L, ledger.playerBalance(player1.getUniqueId()));
    }

    @Test
    @DisplayName("A running season hands out nothing, not even to the leader")
    void aRunningSeasonDeliversNothingToTheLeader() {
        setupIslandData();
        coordinator.setSeasonEligibility(
                SkyBlockTopRewardCoordinator.SeasonEligibility.CLOSED);

        SkyBlockTopRewardCoordinator.ClaimResult result = coordinator.claimReward(player2).join();
        server.getScheduler().performOneTick();

        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SEASON_OPEN, result.status());
        assertEquals(0, countCustomItems(player2, "skyblock:token/diamond_lotus"),
                "lider trwającego sezonu nie może dostać lotosów");
        assertEquals(0L, ledger.playerBalance(player2.getUniqueId()),
                "ani monet — odmowa nie zakłada nawet konta");
        assertFalse(coordinator.isClaimed(1, player2.getUniqueId()).join(),
                "odmowa nie może zużyć jednorazowego roszczenia gracza");
    }

    @Test
    @DisplayName("BUSY outbox nie przepala nagrody sezonowej (SKYBLOCK-1-6)")
    void busyOutboxDoesNotBurnTheSeasonalClaim() {
        setupIslandData();
        org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox busyOutbox =
                mock(org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox.class);
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Consumer<org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox.Outcome> callback =
                    inv.getArgument(5);
            callback.accept(org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox.Outcome.BUSY);
            return null;
        }).when(busyOutbox).beginGrant(any(), anyLong(), anyString(), anyString(), any(), any());
        coordinator.setInventoryOutbox(busyOutbox);

        SkyBlockTopRewardCoordinator.ClaimResult result = coordinator.claimReward(player2).join();
        server.getScheduler().performOneTick();

        // Wpis roszczenia nie może powstać, dopóki wypłata nie doszła do skutku —
        // inaczej ponowna próba kończy się ALREADY_CLAIMED i nagroda przepada.
        assertFalse(coordinator.isClaimed(1, player2.getUniqueId()).join(),
                "claim nie może być zapisany przy BUSY outboxie");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, result.status());

        // Gdy outbox się zwolni, ten sam gracz musi móc odebrać nagrodę.
        SkyBlockTopRewardCoordinator.ClaimResult retry = coordinator.claimReward(player2).join();
        server.getScheduler().performOneTick();
        org.junit.jupiter.api.Assertions.assertNotEquals(
                SkyBlockTopRewardCoordinator.ClaimStatus.ALREADY_CLAIMED, retry.status());
    }

    @Test
    @DisplayName("Claiming reward is idempotent per player per season")
    void claimRewardIsIdempotent() {
        setupIslandData();

        // First claim succeeds
        SkyBlockTopRewardCoordinator.ClaimResult first = coordinator.claimReward(player2).join();
        server.getScheduler().performOneTick();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, first.status());
        assertTrue(coordinator.isClaimed(1, player2.getUniqueId()).join());

        // Repeated claim is rejected
        SkyBlockTopRewardCoordinator.ClaimResult second = coordinator.claimReward(player2).join();
        server.getScheduler().performOneTick();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.ALREADY_CLAIMED, second.status());
        assertEquals(3, countCustomItems(player2, "skyblock:token/diamond_lotus")); // No extra items
        assertEquals(50_000L + 1000L, ledger.playerBalance(player2.getUniqueId())); // No extra coins
    }

    @Test
    @DisplayName("SKYBLOCK-1-7: second member of the winning island gets no parallel payout")
    void secondIslandMemberDoesNotGetAParallelPayout() {
        setupIslandData();
        // Kolega z tej samej zwycięskiej wyspy (island2, miejsce #1).
        PlayerMock teammate = server.addPlayer();
        mockPlayerIsland(teammate, island2);
        // Realny gracz z historią ma już konto — dedup wypłaty ma zwrócić czysty
        // no-op, a nie potknąć się o brak konta.
        ledger.ensurePlayer(teammate.getUniqueId()).join();
        long teammateStart = ledger.playerBalance(teammate.getUniqueId());

        // Pierwszy członek odbiera pełną pulę.
        SkyBlockTopRewardCoordinator.ClaimResult first = coordinator.claimReward(player2).join();
        server.getScheduler().performOneTick();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, first.status());
        assertEquals(3, countCustomItems(player2, "skyblock:token/diamond_lotus"));
        assertEquals(50_000L + 1000L, ledger.playerBalance(player2.getUniqueId()));

        // Drugi członek TEJ SAMEJ wyspy: idempotencja per-wyspa (txid + operationId)
        // czyni wypłatę no-opem — zero drugiej puli monet i zero drugiego kompletu
        // lotosów, a ALREADY_CLAIMED jest prawdziwe.
        SkyBlockTopRewardCoordinator.ClaimResult second = coordinator.claimReward(teammate).join();
        server.getScheduler().performOneTick();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.ALREADY_CLAIMED, second.status());
        assertEquals(0, countCustomItems(teammate, "skyblock:token/diamond_lotus"),
                "kolega z wyspy nie może dostać drugiego kompletu lotosów");
        assertEquals(teammateStart, ledger.playerBalance(teammate.getUniqueId()),
                "kolega z wyspy nie może dostać drugiej puli monet");
    }

    @Test
    @DisplayName("Non-eligible players (rank 4+ or no island) cannot claim rewards")
    void nonEligiblePlayersCannotClaim() {
        setupIslandData();

        // Island 4 is rank 4
        SkyBlockTopRewardCoordinator.ClaimResult rank4Result = coordinator.claimReward(player4).join();
        server.getScheduler().performOneTick();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.NOT_IN_TOP, rank4Result.status());
        assertEquals(0, countCustomItems(player4, "skyblock:token/diamond_lotus"));
        assertEquals(0L, ledger.playerBalance(player4.getUniqueId()));

        // Player without island
        SkyBlockTopRewardCoordinator.ClaimResult noIslandResult = coordinator.claimReward(playerNoIsland).join();
        server.getScheduler().performOneTick();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.NO_ISLAND, noIslandResult.status());
        assertEquals(0, countCustomItems(playerNoIsland, "skyblock:token/diamond_lotus"));
    }

    @Test
    @DisplayName("Season transition advances season ID and resets claim status for new season")
    void seasonTransitionAdvancesSeasonAndResetsClaims() {
        setupIslandData();

        assertEquals(1, coordinator.getCurrentSeason().join());

        // Player 2 claims for Season 1
        coordinator.claimReward(player2).join();
        server.getScheduler().performOneTick();
        assertTrue(coordinator.isClaimed(1, player2.getUniqueId()).join());
        assertFalse(coordinator.isClaimed(2, player2.getUniqueId()).join());

        // Advance to Season 2
        int nextSeason = coordinator.endSeason().join();
        assertEquals(2, nextSeason);
        assertEquals(2, coordinator.getCurrentSeason().join());

        // Player 2 can claim again for Season 2
        SkyBlockTopRewardCoordinator.ClaimResult s2Claim = coordinator.claimReward(player2).join();
        server.getScheduler().performOneTick();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, s2Claim.status());
        assertTrue(coordinator.isClaimed(2, player2.getUniqueId()).join());

        // Player 2 now has 6 lotuses total (3 from s1 + 3 from s2)
        assertEquals(6, countCustomItems(player2, "skyblock:token/diamond_lotus"));
    }

    @Test
    @DisplayName("Fallback token creation sets PDC key and lore without custom item service")
    void fallbackTokenCreationSetsPdcKeyAndLore() {
        SkyBlockTopRewardCoordinator fallbackCoordinator = new SkyBlockTopRewardCoordinator(
                plugin, sql, ledger, skyllia, miniMessage, null);

        ItemStack lotus = fallbackCoordinator.createDiamondLotus(3);
        assertNotNull(lotus);
        assertEquals(Material.PAPER, lotus.getType());
        assertEquals(3, lotus.getAmount());
        assertTrue(lotus.hasItemMeta());
        assertNotNull(lotus.getItemMeta().displayName());

        String customId = lotus.getItemMeta().getPersistentDataContainer()
                .get(SkyBlockTopRewardCoordinator.KEY_CUSTOM_ITEM_ID, PersistentDataType.STRING);
        assertEquals("skyblock:token/diamond_lotus", customId);
    }

    @Test
    @DisplayName("showRankingSummary executes cleanly for player and console")
    void showRankingSummaryExecutesCleanly() {
        setupIslandData();
        coordinator.showRankingSummary(player2).join();
        coordinator.showRankingSummary(server.getConsoleSender()).join();
    }

    /**
     * Paczka miejsc 5/10/50: miejsce z własnym wpisem w {@code season.top-rewards}
     * bierze swoją nagrodę, a {@code season.title-rewards} nadaje mu tytuł wyspy
     * w trwałym magazynie (migracja #13). Miejsce bez wpisu (tu: #7) spada na
     * wpis progu (rank 4) i nie dostaje żadnego tytułu.
     */
    @Test
    @DisplayName("Miejsca 10 i 50 biorą własną nagrodę i nadają tytuł wyspy")
    void exactRankRewardsGrantTheConfiguredIslandTitle() {
        seedFiftyIslands();
        IslandTitleService titles = new IslandTitleService(new IslandTitleDao.Sql(sql));
        coordinator.setTopRewards(List.of(
                new SkyBlockSettings.TopReward(1, 3, 50_000L),
                new SkyBlockSettings.TopReward(2, 2, 30_000L),
                new SkyBlockSettings.TopReward(3, 1, 15_000L),
                new SkyBlockSettings.TopReward(4, 1, 5_000L),
                new SkyBlockSettings.TopReward(10, 1, 2_000L),
                new SkyBlockSettings.TopReward(50, 1, 1_000L)));
        coordinator.setTitleRewards(Map.of(10, "Wyspiarz", 50, "<gray>Weteran Pustki"));
        coordinator.setIslandTitles(titles);
        // 50 wysp z dorobkiem, więc każdy próg poniżej najniższego wyniku jest spełniony.
        coordinator.setSeasonEligibility(
                new SkyBlockTopRewardCoordinator.SeasonEligibility(true, 1L, 50, 1_000L));

        PlayerMock rankSeventh = playerAtRank(7);
        PlayerMock rankTenth = playerAtRank(10);
        PlayerMock rankFiftieth = playerAtRank(50);

        SkyBlockTopRewardCoordinator.ClaimResult middle = coordinator.claimReward(rankSeventh).join();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, middle.status());
        assertEquals(7, middle.rankPosition());
        assertEquals(5_000L, middle.coinsGiven(), "miejsce bez wpisu dostaje nagrodę progu");
        assertTrue(titles.titleOf(islandOfRank(7)).join().isEmpty(),
                "próg nie nadaje tytułu wyspy");

        SkyBlockTopRewardCoordinator.ClaimResult tenth = coordinator.claimReward(rankTenth).join();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, tenth.status());
        assertEquals(10, tenth.rankPosition());
        assertEquals(2_000L, tenth.coinsGiven(), "własny wpis wygrywa z progiem");
        assertEquals("Wyspiarz", titles.titleOf(islandOfRank(10)).join().orElse(null));
        assertEquals("season:1:rank10",
                titles.cachedSourceId(islandOfRank(10)).orElse(null));

        SkyBlockTopRewardCoordinator.ClaimResult fiftieth = coordinator.claimReward(rankFiftieth).join();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, fiftieth.status());
        assertEquals(50, fiftieth.rankPosition());
        assertEquals(1_000L, fiftieth.coinsGiven());
        assertEquals("<gray>Weteran Pustki",
                titles.titleOf(islandOfRank(50)).join().orElse(null));
        assertEquals("season:1:rank50",
                titles.cachedSourceId(islandOfRank(50)).orElse(null));
        server.getScheduler().performOneTick();
        List<String> rankFiftyMessages = drainedMessages(rankFiftieth).stream()
                .map(SkyBlockTopRewardCoordinatorTest::stripColors)
                .toList();
        assertTrue(rankFiftyMessages.stream().anyMatch(text -> text.contains("Nowy tytuł wyspy")),
                "gracz musi się dowiedzieć, że dostał tytuł wyspy: " + rankFiftyMessages);
        assertTrue(rankFiftyMessages.stream().anyMatch(text -> text.contains("MIEJSCE #50")),
                "miejsce 50 z własnym wpisem nie jest „progiem sezonu”: " + rankFiftyMessages);

        // Powtórka: roszczenie i tytuł już są, więc nic drugi raz nie schodzi.
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.ALREADY_CLAIMED,
                coordinator.claimReward(rankFiftieth).join().status());
        assertEquals("<gray>Weteran Pustki",
                titles.titleOf(islandOfRank(50)).join().orElse(null));

        /*
         * Widok /nagrody musi pokazywać nowe progi i dawać przycisk odbioru
         * miejscu, którego roszczenie przeszłoby bramkę — inaczej paczka
         * 5/10/50 istnieje tylko w konfiguracji.
         */
        PlayerMock rankTenthTeammate = playerAtRank(10);
        coordinator.showRankingSummary(rankTenthTeammate).join();
        server.getScheduler().performOneTick();
        List<String> summary = drainedMessages(rankTenthTeammate).stream()
                .map(SkyBlockTopRewardCoordinatorTest::stripColors)
                .toList();
        assertTrue(summary.stream().anyMatch(text -> text.contains("#50 Miejsce")),
                "podsumowanie musi wymieniać miejsca z konfiguracji: " + summary);
        assertTrue(summary.stream().anyMatch(text -> text.contains("tytuł wyspy")),
                "podsumowanie musi mówić, że to miejsce daje tytuł: " + summary);
        assertTrue(summary.stream().anyMatch(text -> text.contains("KLIKNIJ TUTAJ")),
                "miejsce z własnym wpisem musi mieć przycisk odbioru: " + summary);
    }

    /**
     * Konfiguracja PRODUKCYJNA miejsc 10 i 50 ma {@code lotus: 0} i {@code coins: 0}
     * — nagroda jest czysto kosmetyczna (część kolekcji i/lub tytuł wyspy).
     *
     * <p>Regresja: {@code grantSeasonLotus} wołało {@code createDiamondLotus(0)},
     * a ta przepuszczała kwotę przez {@code Math.max(1, amount)} — miejsce 10
     * dostawało więc 1 Diamentowy Lotos, czyli darmową 1/3 zlewu na tytuł Władcy
     * Lotosu. Test poprzedni (z {@code lotus: 1}) tego nie widział, bo sam
     * ustawiał jedynkę; ten używa dokładnie wartości z produkcji.
     */
    @Test
    @DisplayName("Miejsca 10 i 50 z lotus: 0 nie dostają ani jednego Lotosu")
    void zeroLotusPlacesGrantNothingButStillGetTheirTitle() {
        seedFiftyIslands();
        IslandTitleService titles = new IslandTitleService(new IslandTitleDao.Sql(sql));
        coordinator.setTopRewards(List.of(
                new SkyBlockSettings.TopReward(1, 3, 50_000L),
                new SkyBlockSettings.TopReward(2, 2, 30_000L),
                new SkyBlockSettings.TopReward(3, 1, 15_000L),
                new SkyBlockSettings.TopReward(4, 1, 5_000L),
                new SkyBlockSettings.TopReward(10, 0, 0L),
                new SkyBlockSettings.TopReward(50, 0, 0L)));
        coordinator.setTitleRewards(Map.of(10, "Zasluzony dla wysp", 50, "Weteran Wysp"));
        coordinator.setIslandTitles(titles);
        coordinator.setSeasonEligibility(
                new SkyBlockTopRewardCoordinator.SeasonEligibility(true, 1L, 50, 1_000L));

        PlayerMock rankTenth = playerAtRank(10);
        SkyBlockTopRewardCoordinator.ClaimResult tenth = coordinator.claimReward(rankTenth).join();
        server.getScheduler().performOneTick();

        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, tenth.status());
        assertEquals(10, tenth.rankPosition());
        assertEquals(0, tenth.diamondLotusGiven(), "lotus: 0 = zero sztuk w wyniku");
        assertEquals(0L, tenth.coinsGiven(), "coins: 0 = zero monet w wyniku");
        assertEquals(0, countCustomItems(rankTenth, "skyblock:token/diamond_lotus"),
                "miejsce 10 nie moze dostac Lotosu, ktorego nie ma w konfiguracji");
        assertEquals(1_000L, ledger.playerBalance(rankTenth.getUniqueId()),
                "nagroda kosmetyczna nie rusza nawet portfela (zostaje saldo startowe)");
        assertEquals("Zasluzony dla wysp", titles.titleOf(islandOfRank(10)).join().orElse(null),
                "tytul wyspy nadal dziala — to jest nagroda tego miejsca");
        assertEquals("season:1:rank10", titles.cachedSourceId(islandOfRank(10)).orElse(null));

        PlayerMock rankFiftieth = playerAtRank(50);
        SkyBlockTopRewardCoordinator.ClaimResult fiftieth = coordinator.claimReward(rankFiftieth).join();
        server.getScheduler().performOneTick();

        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, fiftieth.status());
        assertEquals(0, fiftieth.diamondLotusGiven());
        assertEquals(0, countCustomItems(rankFiftieth, "skyblock:token/diamond_lotus"),
                "miejsce 50 tez nie dostaje Lotosu");
        assertEquals("Weteran Wysp", titles.titleOf(islandOfRank(50)).join().orElse(null));
    }

    /**
     * Ścieżka PRODUKCYJNA (z outboxem) dla {@code lotus: 0}: nic nie może trafić
     * do kolejki, a nagroda musi się domknąć (SUCCESS + roszczenie zapisane +
     * tytuł nadany).
     *
     * <p>Rozdzielone od testu powyżej celowo: tamten działa bez outboxa, więc
     * szedł fallbackiem {@code deliverDirectly} i NIE pokrywał kodu, który na
     * produkcji faktycznie wydaje przedmiot (kontrola negatywna 2026-09-11
     * pokazała, że bez tego test przechodził nawet z cofniętą poprawką).
     */
    @Test
    @DisplayName("Z outboxem: lotus: 0 nie kolejkuje niczego, ale nagroda się domyka")
    void zeroLotusWithOutboxQueuesNothingAndStillCompletes() {
        seedFiftyIslands();
        IslandTitleService titles = new IslandTitleService(new IslandTitleDao.Sql(sql));
        org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox outbox =
                mock(org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox.class);
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Consumer<org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox.Outcome> cb =
                    inv.getArgument(5);
            cb.accept(org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox.Outcome.SUCCESS);
            return null;
        }).when(outbox).beginGrant(any(), anyLong(), anyString(), anyString(), any(), any());

        coordinator.setInventoryOutbox(outbox);
        coordinator.setTopRewards(List.of(
                new SkyBlockSettings.TopReward(1, 3, 50_000L),
                new SkyBlockSettings.TopReward(4, 1, 5_000L),
                new SkyBlockSettings.TopReward(10, 0, 0L),
                new SkyBlockSettings.TopReward(50, 0, 0L)));
        coordinator.setTitleRewards(Map.of(10, "Zasluzony dla wysp", 50, "Weteran Wysp"));
        coordinator.setIslandTitles(titles);
        coordinator.setSeasonEligibility(
                new SkyBlockTopRewardCoordinator.SeasonEligibility(true, 1L, 50, 1_000L));

        PlayerMock rankTenth = playerAtRank(10);
        SkyBlockTopRewardCoordinator.ClaimResult tenth = coordinator.claimReward(rankTenth).join();
        server.getScheduler().performOneTick();

        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, tenth.status(),
                "brak Lotosow do wydania nie jest bledem roszczenia — nagroda to tytul");
        org.mockito.Mockito.verify(outbox, org.mockito.Mockito.never())
                .beginGrant(any(), anyLong(), anyString(),
                        org.mockito.ArgumentMatchers.eq("seasonal_lotus"), any(), any());
        assertTrue(coordinator.isClaimed(1, rankTenth.getUniqueId()).join(),
                "roszczenie musi zostac zapisane, inaczej gracz traci nagrode");
        assertEquals("Zasluzony dla wysp", titles.titleOf(islandOfRank(10)).join().orElse(null));

        // Kontrola: miejsce z niezerowym Lotosem NADAL go dostaje (bramka nie tlumi wszystkiego).
        PlayerMock rankFirst = playerAtRank(1);
        SkyBlockTopRewardCoordinator.ClaimResult first = coordinator.claimReward(rankFirst).join();
        server.getScheduler().performOneTick();
        assertEquals(SkyBlockTopRewardCoordinator.ClaimStatus.SUCCESS, first.status());
        assertEquals(3, first.diamondLotusGiven(), "miejsce 1 ma lotus: 3 i tyle ma dostac");
        org.mockito.Mockito.verify(outbox, org.mockito.Mockito.times(1))
                .beginGrant(any(), anyLong(), anyString(),
                        org.mockito.ArgumentMatchers.eq("seasonal_lotus"), any(), any());
    }

    /**
     * 50 wysp z niepowtarzalnym dorobkiem: wyspa {@code i} ma w banku
     * {@code (60 − i) × 1000}, więc jej miejsce w rankingu to dokładnie {@code i}.
     * Wynik każdej wyspy jest powyżej progu sezonu (1 000).
     */
    private void seedFiftyIslands() {
        for (int rank = 1; rank <= 50; rank++) {
            seedMembership(islandOfRank(rank), UUID.randomUUID());
            seedBank(islandOfRank(rank), (60L - rank) * 1_000L);
        }
    }

    /** Gracz przypisany do wyspy o wskazanym miejscu (tylko do ścieżki roszczenia). */
    private PlayerMock playerAtRank(int rank) {
        PlayerMock player = server.addPlayer();
        mockPlayerIsland(player, islandOfRank(rank));
        return player;
    }

    private UUID islandOfRank(int rank) {
        return UUID.nameUUIDFromBytes(("rank-" + rank).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Komunikaty gracza jako zwykły tekst — asercje nie zależą od kolorów. */
    private static List<String> drainedMessages(PlayerMock player) {
        List<String> texts = new ArrayList<>();
        for (String message = player.nextMessage();
             message != null;
             message = player.nextMessage()) {
            texts.add(message);
        }
        return texts;
    }

    /**
     * Zdejmuje kody kolorów z komunikatu. Gradienty MiniMessage barwią każdy
     * znak osobno, więc surowy napis z MockBukkita nie zawiera szukanego słowa
     * w jednym kawałku.
     */
    private static String stripColors(String message) {
        return message.replaceAll("§.", "");
    }

    private int countCustomItems(PlayerMock player, String customId) {
        int total = 0;
        NamespacedKey key = SkyBlockTopRewardCoordinator.KEY_CUSTOM_ITEM_ID;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && !item.getType().isAir() && item.hasItemMeta()) {
                String id = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
                if (customId.equalsIgnoreCase(id)) {
                    total += item.getAmount();
                }
            }
        }
        return total;
    }

    // ---- Helper Mock CustomItemService ----

    static class MockCustomItemService implements CustomItemService {
        private final Map<String, CustomItem> definitions = new HashMap<>();
        private final NamespacedKey idKey = SkyBlockTopRewardCoordinator.KEY_CUSTOM_ITEM_ID;

        void register(String id, Material material, String name) {
            definitions.put(id.toLowerCase(), new CustomItem(id, material, name, List.of(), false, Map.of()));
        }

        @Override
        public @NotNull Collection<CustomItem> all() {
            return definitions.values();
        }

        @Override
        public @NotNull Optional<CustomItem> byId(@NotNull String id) {
            return Optional.ofNullable(definitions.get(id.toLowerCase()));
        }

        @Override
        public @NotNull Optional<ItemStack> create(@NotNull String id) {
            CustomItem item = definitions.get(id.toLowerCase());
            if (item == null) {
                return Optional.empty();
            }
            ItemStack stack = new ItemStack(item.material());
            stack.editMeta(meta -> {
                meta.displayName(MiniMessage.miniMessage().deserialize(item.name()));
                meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, item.id());
            });
            return Optional.of(stack);
        }

        @Override
        public String idOf(ItemStack stack) {
            if (stack == null || !stack.hasItemMeta()) {
                return null;
            }
            return stack.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        }
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
            } catch (SQLException ignored) {}
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

        private static void bind(PreparedStatement statement, Object... params) throws SQLException {
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
