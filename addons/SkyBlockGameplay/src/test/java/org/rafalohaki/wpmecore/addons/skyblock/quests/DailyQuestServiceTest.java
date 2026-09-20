package org.rafalohaki.wpmecore.addons.skyblock.quests;

import org.rafalohaki.wpmecore.addons.skyblock.economy.AccountKey;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;


import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.api.service.SqlDialect;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DailyQuestServiceTest {

    /** Canonical OneBlock chapter ids (oneblock.yml order), used for migration 7. */
    private static final List<String> PHASE_IDS = List.of(
            "poczatek", "podziemia", "zima", "pustynia", "dzungla", "pieklo", "kres");

    private JavaPlugin plugin;
    private CustomItemService customItemService;
    private SingleConnectionSqlService sql;
    private LedgerDao ledgerDao;
    private LedgerService ledgerService;
    private SkylliaIntegration skyllia;
    private MenuService menuService;
    private QuestCatalog catalog;
    private DailyQuestService questService;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(JavaPlugin.class);
        customItemService = mock(CustomItemService.class);
        sql = new SingleConnectionSqlService();
        ledgerDao = new LedgerDao(sql);
        ledgerDao.createSchema().join();
        ledgerService = new LedgerService(ledgerDao, 100L);
        ledgerService.init();

        skyllia = mock(SkylliaIntegration.class);
        menuService = mock(MenuService.class);

        catalog = new QuestCatalog(ZoneId.of("Europe/Warsaw"), 3, List.of(
                new QuestCatalog.Definition("miner", QuestCatalog.Type.BREAK, Set.of("STONE"), 100, 50,
                        Material.STONE, "Miner", List.of(), false,
                        List.of(new QuestCatalog.RewardItem("skyblock:token/silver_lotus", 1))),
                new QuestCatalog.Definition("farmer", QuestCatalog.Type.BREAK, Set.of("WHEAT"), 50, 30,
                        Material.WHEAT, "Farmer", List.of(), true,
                        List.of(new QuestCatalog.RewardItem("skyblock:token/silver_lotus", 2))),
                new QuestCatalog.Definition("hunter", QuestCatalog.Type.KILL, Set.of("ZOMBIE"), 10, 20,
                        Material.IRON_SWORD, "Hunter", List.of(), false,
                        List.of(new QuestCatalog.RewardItem("DIAMOND", 1)))
        ));

        questService = new DailyQuestService(plugin, menuService, MiniMessage.miniMessage(),
                ledgerService, skyllia, catalog, customItemService);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    @DisplayName("Should create custom items correctly")
    void testCreateItemStack() {
        ItemStack silverLotus = mock(ItemStack.class);
        when(customItemService.create("skyblock:token/silver_lotus"))
                .thenReturn(Optional.of(silverLotus));

        ItemStack customStack = questService.createItemStack("skyblock:token/silver_lotus", 3);
        assertNotNull(customStack);
        assertSame(silverLotus, customStack);
        verify(silverLotus).setAmount(3);

        // Invalid item
        assertNull(questService.createItemStack("NON_EXISTENT_MATERIAL_OR_CUSTOM_ITEM_123", 1));
        assertNull(questService.createItemStack("skyblock:token/silver_lotus", 0));
        assertNull(questService.createItemStack("skyblock:token/silver_lotus", -1));
    }

    @Test
    @DisplayName("Should give item to player inventory when space is available")
    void testGiveOrDropItemWithSpace() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);

        ItemStack item = mock(ItemStack.class);
        when(inventory.addItem(item)).thenReturn(new HashMap<>());

        questService.giveOrDropItem(player, item);

        verify(inventory).addItem(item);
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test
    @DisplayName("Should drop item at player location when inventory is full")
    void testGiveOrDropItemFullInventory() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        World world = mock(World.class);
        Location location = new Location(world, 10.0, 64.0, 10.0);

        when(player.getInventory()).thenReturn(inventory);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(location);

        ItemStack item = mock(ItemStack.class);
        HashMap<Integer, ItemStack> leftover = new HashMap<>();
        leftover.put(0, item);
        when(inventory.addItem(item)).thenReturn(leftover);

        questService.giveOrDropItem(player, item);

        verify(inventory).addItem(item);
        verify(world).dropItemNaturally(eq(location), eq(item));
        verify(player).sendMessage(any(Component.class));
    }

    @Test
    @DisplayName("Should track completed quests per week and claim milestone idempotently")
    void testWeeklyMilestoneSql() {
        UUID island1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID owner1 = UUID.fromString("11111111-1111-1111-1111-000000000001");
        AccountKey islandAccount1 = AccountKey.island(island1);

        String startPeriod = "2026-08-10"; // Monday
        String endPeriod = "2026-08-16";   // Sunday
        String weekKey = "2026-W33";

        // Initial state: 0 completed
        assertEquals(0, ledgerService.countCompletedQuestsInPeriod(island1, startPeriod, endPeriod).join());
        assertFalse(ledgerService.isWeeklyMilestoneClaimed(island1, weekKey).join());

        // Simulate completing 14 quests across the week (days 2026-08-10 to 2026-08-14)
        for (int dayOffset = 0; dayOffset < 5; dayOffset++) {
            String period = "2026-08-" + String.format("%02d", 10 + dayOffset);
            int questsOnDay = (dayOffset == 4) ? 2 : 3; // 3 + 3 + 3 + 3 + 2 = 14
            for (int q = 0; q < questsOnDay; q++) {
                String questId = "quest_" + dayOffset + "_" + q;
                ledgerDao.incrementQuest(islandAccount1, owner1, period, questId, 10L, 10L, 50L,
                        "reward:" + period + ":" + questId, "batch:" + period + ":" + questId).join();
            }
        }

        assertEquals(14, ledgerService.countCompletedQuestsInPeriod(island1, startPeriod, endPeriod).join());
        assertFalse(ledgerService.isWeeklyMilestoneClaimed(island1, weekKey).join());

        // Complete 15th quest on Saturday 2026-08-15
        ledgerDao.incrementQuest(islandAccount1, owner1, "2026-08-15", "quest_15", 10L, 10L, 50L,
                "reward:2026-08-15:quest_15", "batch:2026-08-15:quest_15").join();

        assertEquals(15, ledgerService.countCompletedQuestsInPeriod(island1, startPeriod, endPeriod).join());

        // Claim weekly milestone
        boolean claimedFirst = ledgerService.claimWeeklyMilestone(island1, weekKey).join();
        assertTrue(claimedFirst, "First milestone claim should succeed");
        assertTrue(ledgerService.isWeeklyMilestoneClaimed(island1, weekKey).join());

        // Idempotency: second claim in the same week should return false
        boolean claimedSecond = ledgerService.claimWeeklyMilestone(island1, weekKey).join();
        assertFalse(claimedSecond, "Second milestone claim in same week should return false");

        // Isolation: another island in the same week has not claimed
        UUID island2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        assertFalse(ledgerService.isWeeklyMilestoneClaimed(island2, weekKey).join());

        // Isolation: same island in another week has not claimed
        String nextWeekKey = "2026-W34";
        assertFalse(ledgerService.isWeeklyMilestoneClaimed(island1, nextWeekKey).join());
    }

    @Test
    @DisplayName("Should execute checkWeeklyMilestone and grant milestone in DB when reaching 15 quests")
    void testCheckWeeklyMilestoneGrant() {
        UUID islandId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID ownerId = UUID.fromString("33333333-3333-3333-3333-000000000001");
        AccountKey islandAccount = AccountKey.island(islandId);

        LocalDate today = catalog.today();
        LocalDate startOfWeek = catalog.startOfWeek(today);

        // Simulate completing 15 quests in the current week
        for (int i = 1; i <= 15; i++) {
            String period = catalog.period(startOfWeek);
            ledgerDao.incrementQuest(islandAccount, ownerId, period, "q_" + i, 1L, 1L, 10L,
                    "rew:q_" + i, "batch:q_" + i).join();
        }

        // Before check, DB does not have milestone claimed
        String weekKey = catalog.weekKey(today);
        assertFalse(ledgerService.isWeeklyMilestoneClaimed(islandId, weekKey).join());

        // Właściciel musi być rozwiązywalny — bez niego Lotos nie ma dokąd trafić.
        when(skyllia.ownerOf(islandId)).thenReturn(Optional.of(
                new org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandOwner(
                        ownerId, "Owner")));

        // Trigger milestone check
        questService.checkWeeklyMilestone(islandId).join();

        // Milestone should now be claimed in the database
        assertTrue(ledgerService.isWeeklyMilestoneClaimed(islandId, weekKey).join());
    }

    /**
     * E2E 2026-09-10: po {@code /is delete confirm} i odtworzeniu wyspy o tym
     * samym {@code island_id} cache Skyllii bywa pusty, więc filtr odbiorców po
     * {@code cachedIslandIdOf} nie wysyłał komunikatu NIKOMU — Lotos szedł
     * outboxem, więc defekt był cichy. Ten sam wzorzec co w trybie eco: odbiorcy
     * idą ze składu wyspy z bazy (status ACTIVE), a właściciel dostaje komunikat
     * zawsze.
     */
    @Test
    @DisplayName("Powiadomienie o milestone'cie dociera do składu wyspy z bazy, nie z cache Skyllii")
    void weeklyMilestoneMessageReachesMembersFromTheDatabase() {
        UUID islandId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID ownerId = UUID.fromString("55555555-5555-5555-5555-000000000001");
        UUID memberId = UUID.fromString("55555555-5555-5555-5555-000000000002");
        UUID strangerId = UUID.fromString("55555555-5555-5555-5555-000000000003");
        islandMembership(islandId, ownerId, ownerId, "OWNER");
        islandMembership(islandId, ownerId, memberId, "MEMBER");

        LocalDate today = catalog.today();
        for (int i = 1; i <= 15; i++) {
            ledgerDao.incrementQuest(AccountKey.island(islandId), ownerId, catalog.period(today),
                    "q_" + i, 1L, 1L, 10L, "rew:q_" + i, "batch:q_" + i).join();
        }

        Server server = mock(Server.class);
        io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler globalSched =
                mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        lenient().when(server.getGlobalRegionScheduler()).thenReturn(globalSched);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(globalSched).execute(eq(plugin), any(Runnable.class));

        Player owner = onlinePlayer(ownerId);
        Player member = onlinePlayer(memberId);
        Player stranger = onlinePlayer(strangerId);
        lenient().when(server.getOnlinePlayers()).thenAnswer(invocation -> List.of(owner, member, stranger));
        lenient().when(server.getPlayer(ownerId)).thenReturn(owner);
        // Cache Skyllii nie zna nikogo po odtworzeniu wyspy — stary filtr milczałby.
        when(skyllia.cachedIslandIdOf(any())).thenReturn(Optional.empty());
        when(skyllia.ownerOf(islandId)).thenReturn(Optional.of(
                new org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandOwner(
                        ownerId, "Owner")));
        when(customItemService.create(DailyQuestService.GOLD_LOTUS_ID))
                .thenReturn(Optional.of(mock(ItemStack.class)));

        questService.checkWeeklyMilestone(islandId).join();

        assertTrue(ledgerService.isWeeklyMilestoneClaimed(islandId, catalog.weekKey(today)).join());
        verify(owner).sendMessage(any(Component.class));
        verify(member).sendMessage(any(Component.class));
        verify(stranger, never()).sendMessage(any(Component.class));
    }

    /** Wiersz profilu (FK) + ACTIVE membership — skład wyspy czytany z bazy. */
    private void islandMembership(UUID islandId, UUID ownerId, UUID playerId, String role) {
        sql.update("""
                INSERT OR IGNORE INTO wpme_sb_island_profiles
                    (island_id, owner_uuid, mode, status, created_at, updated_at)
                VALUES (?, ?, 'CLASSIC', 'ACTIVE', ?, ?)
                """, islandId.toString(), ownerId.toString(), 1L, 1L).join();
        sql.update("""
                INSERT OR REPLACE INTO wpme_sb_island_membership
                    (island_id, player_uuid, role, status, joined_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """, islandId.toString(), playerId.toString(), role, 1L, 1L).join();
    }

    private Player onlinePlayer(UUID id) {
        Player player = mock(Player.class);
        lenient().when(player.getUniqueId()).thenReturn(id);
        PlayerInventory inventory = mock(PlayerInventory.class);
        lenient().when(player.getInventory()).thenReturn(inventory);
        lenient().when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        io.papermc.paper.threadedregions.scheduler.EntityScheduler scheduler =
                mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        lenient().when(player.getScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            ((java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>)
                    invocation.getArgument(1)).accept(null);
            return mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class);
        }).when(scheduler).run(eq(plugin), any(), any());
        return player;
    }

    @Test
    @DisplayName("Should deliver quest item rewards only to triggering player, not multiplying to all island members")
    void testNotifyCompletionRewardsOnlyTriggeringPlayer() {
        UUID islandId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID triggerPlayerId = UUID.fromString("44444444-4444-4444-4444-000000000001");
        UUID otherMemberId = UUID.fromString("44444444-4444-4444-4444-000000000002");

        Server server = mock(Server.class);
        io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler globalSched =
                mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalSched);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(globalSched).execute(eq(plugin), any(Runnable.class));

        Player triggerPlayer = mock(Player.class);
        when(triggerPlayer.getUniqueId()).thenReturn(triggerPlayerId);
        PlayerInventory triggerInv = mock(PlayerInventory.class);
        when(triggerPlayer.getInventory()).thenReturn(triggerInv);
        when(triggerInv.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        io.papermc.paper.threadedregions.scheduler.EntityScheduler triggerSched =
                mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        when(triggerPlayer.getScheduler()).thenReturn(triggerSched);
        doAnswer(inv -> {
            ((java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>) inv.getArgument(1)).accept(null);
            return mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class);
        }).when(triggerSched).run(eq(plugin), any(), any());

        Player otherMember = mock(Player.class);
        when(otherMember.getUniqueId()).thenReturn(otherMemberId);
        PlayerInventory otherInv = mock(PlayerInventory.class);
        when(otherMember.getInventory()).thenReturn(otherInv);
        io.papermc.paper.threadedregions.scheduler.EntityScheduler otherSched =
                mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        when(otherMember.getScheduler()).thenReturn(otherSched);
        doAnswer(inv -> {
            ((java.util.function.Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>) inv.getArgument(1)).accept(null);
            return mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class);
        }).when(otherSched).run(eq(plugin), any(), any());

        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(triggerPlayer, otherMember));
        when(server.getPlayer(triggerPlayerId)).thenReturn(triggerPlayer);
        when(skyllia.cachedIslandIdOf(triggerPlayerId)).thenReturn(Optional.of(islandId));
        when(skyllia.cachedIslandIdOf(otherMemberId)).thenReturn(Optional.of(islandId));

        ItemStack silverLotus = mock(ItemStack.class);
        when(customItemService.create("skyblock:token/silver_lotus")).thenReturn(Optional.of(silverLotus));

        DailyQuestService.QuestRowKey key = new DailyQuestService.QuestRowKey(islandId, "2026-08-15", "miner");
        DailyQuestService.QuestPayload payload = new DailyQuestService.QuestPayload(triggerPlayerId, 100L, 50L);
        DailyQuestService.ActiveBatch batch = new DailyQuestService.ActiveBatch("batch_1", payload, 10L);

        questService.notifyCompletion(key, batch);

        // Both players receive completion message, but only trigger player receives reward notification
        verify(triggerPlayer, times(2)).sendMessage(any(Component.class));
        verify(otherMember, times(1)).sendMessage(any(Component.class));

        // ONLY trigger player receives the reward items
        verify(triggerInv).addItem(silverLotus);
        verify(otherInv, never()).addItem(any(ItemStack.class));
    }

    @Test
    @DisplayName("F20: hasCompletedToday wygasa z dniem — wpis sprzed dni nie ukrywa dzisiejszego zadania")
    void completedTodayIsKeyedByDay() {
        // Nic nie czyściło dawnego Set<UUID>, więc pierwsze zadanie w życiu wyspy
        // gasiło etap „zadanie dnia" w przewodniku na zawsze — aż do restartu.
        UUID islandId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        // Quest spoza katalogu: notifyCompletion pomija wtedy nagrody przedmiotowe,
        // a getServer() == null pomija rozgłoszenie — zostaje sam znacznik dnia.
        DailyQuestService.QuestPayload payload =
                new DailyQuestService.QuestPayload(UUID.randomUUID(), 100L, 50L);
        DailyQuestService.ActiveBatch batch =
                new DailyQuestService.ActiveBatch("batch_f20", payload, 10L);

        questService.notifyCompletion(
                new DailyQuestService.QuestRowKey(islandId, "2026-08-15", "brak-w-katalogu"), batch);
        assertFalse(questService.hasCompletedToday(islandId),
                "zadanie zamknięte w innym dniu nie może liczyć się jako dzisiejsze");

        questService.notifyCompletion(new DailyQuestService.QuestRowKey(
                islandId, catalog.period(catalog.today()), "brak-w-katalogu"), batch);
        assertTrue(questService.hasCompletedToday(islandId),
                "zadanie zamknięte dziś musi zamykać etap przewodnika");
    }

    @Test
    @DisplayName("Tygodniowy kamień: jeden Lotos na wyspę przez outbox, nie per członek online (SKYBLOCK-1-4)")
    void weeklyMilestoneGrantsExactlyOneLotusPerIsland() {
        UUID islandId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID ownerId = UUID.fromString("55555555-5555-5555-5555-000000000001");
        UUID memberId = UUID.fromString("55555555-5555-5555-5555-000000000002");
        AccountKey islandAccount = AccountKey.island(islandId);

        LocalDate today = catalog.today();
        String period = catalog.period(catalog.startOfWeek(today));
        for (int i = 1; i <= 15; i++) {
            ledgerDao.incrementQuest(islandAccount, ownerId, period, "wq_" + i, 1L, 1L, 10L,
                    "wrew:q_" + i, "wbatch:q_" + i).join();
        }

        Server server = mock(Server.class);
        io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler globalSched =
                mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalSched);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(globalSched).execute(eq(plugin), any(Runnable.class));

        Player owner = mock(Player.class);
        Player member = mock(Player.class);
        when(owner.getUniqueId()).thenReturn(ownerId);
        when(member.getUniqueId()).thenReturn(memberId);
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of(owner, member));
        when(server.getPlayer(ownerId)).thenReturn(owner);
        when(skyllia.cachedIslandIdOf(ownerId)).thenReturn(Optional.of(islandId));
        when(skyllia.cachedIslandIdOf(memberId)).thenReturn(Optional.of(islandId));
        when(skyllia.ownerOf(islandId)).thenReturn(Optional.of(
                new org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandOwner(
                        ownerId, "Owner")));

        ItemStack goldLotus = mock(ItemStack.class);
        when(customItemService.create("skyblock:token/gold_lotus")).thenReturn(Optional.of(goldLotus));

        org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox outbox =
                mock(org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox.class);
        questService.setInventoryOutbox(outbox);

        questService.checkWeeklyMilestone(islandId).join();

        // Dokładnie jeden grant dla właściciela z deterministycznym id —
        // drugi online członek nie dostaje własnego egzemplarza.
        verify(outbox, times(1)).beginGrant(eq(owner), eq(0L),
                eq("milestone:" + islandId + ':' + catalog.weekKey(today)),
                eq("weekly_milestone"), any(ItemStack.class), any());
        verify(outbox, never()).beginGrant(eq(member), anyLong(), anyString(), anyString(),
                any(ItemStack.class), any());
        verify(owner, never()).getInventory();
    }

    @Test
    @DisplayName("Nagroda przedmiotowa właściciela offline: trwały dług outboxa, nie utrata (SKYBLOCK-1-5)")
    void questItemRewardForOfflineOwnerBecomesDurableOutboxDebt() {
        UUID islandId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        UUID ownerId = UUID.fromString("66666666-6666-6666-6666-000000000001");

        Server server = mock(Server.class);
        io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler globalSched =
                mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getGlobalRegionScheduler()).thenReturn(globalSched);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(globalSched).execute(eq(plugin), any(Runnable.class));
        when(server.getOnlinePlayers()).thenAnswer(inv -> List.of());
        when(server.getPlayer(ownerId)).thenReturn(null); // właściciel wyszedł przed flushem

        ItemStack silverLotus = mock(ItemStack.class);
        when(customItemService.create("skyblock:token/silver_lotus")).thenReturn(Optional.of(silverLotus));

        org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox outbox =
                mock(org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox.class);
        questService.setInventoryOutbox(outbox);

        DailyQuestService.QuestRowKey key = new DailyQuestService.QuestRowKey(islandId,
                catalog.period(catalog.today()), "miner");
        DailyQuestService.QuestPayload payload = new DailyQuestService.QuestPayload(ownerId, 100L, 50L);
        DailyQuestService.ActiveBatch batch = new DailyQuestService.ActiveBatch("batch_off", payload, 10L);

        questService.notifyCompletion(key, batch);

        // Dług trwały z deterministycznym id — rozliczy się przy wejściu właściciela.
        verify(outbox, times(1)).beginOfflineGrant(eq(ownerId), eq(0L),
                eq("quest:" + catalog.period(catalog.today()) + ":miner:" + ownerId
                        + ":items:skyblock:token/silver_lotus"),
                eq("quest_reward_items"), any(ItemStack.class), any());
        verify(outbox, never()).beginGrant(any(), anyLong(), anyString(), anyString(),
                any(ItemStack.class), any());
    }

    private static final class SingleConnectionSqlService implements SqlService {
        private final Connection connection;

        private SingleConnectionSqlService() throws SQLException {
            connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        }

        @Override public SqlDialect dialect() { return SqlDialect.SQLITE; }
        @Override public boolean isEnabled() { return true; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isHealthy() { return true; }
        @Override public Map<String, Object> poolStats() { return Map.of(); }

        @Override
        public void shutdown() {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }

        @Override
        public CompletableFuture<Integer> update(String query, Object... params) {
            return completed(() -> {
                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    bind(statement, params);
                    return statement.executeUpdate();
                }
            });
        }

        @Override
        public <T> CompletableFuture<List<T>> query(String query, RowMapper<T> mapper, Object... params) {
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
        public <T> CompletableFuture<T> withConnection(SqlAction<T> action) {
            return completed(() -> action.execute(connection));
        }

        @Override public Object dataSource() { return connection; }

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
