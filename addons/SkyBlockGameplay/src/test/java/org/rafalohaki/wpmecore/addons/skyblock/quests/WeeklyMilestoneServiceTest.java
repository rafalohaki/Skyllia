package org.rafalohaki.wpmecore.addons.skyblock.quests;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonDailyPointsCaps;
import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonDailyPointsDao;
import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonDailyPointsService;
import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonPointService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandOwner;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tygodniowy kamień milowy w trybie eco: 15 ukończonych zadań dnia na wyspę =
 * 1 Złoty Lotos dla właściciela.
 *
 * <p>Test stoi na prawdziwym schemacie SQLite (migrator) i prawdziwej księdze:
 * liczy wiersze {@code wpme_sb_season_daily_points} PO CZŁONKACH wyspy, a
 * idempotencję tygodnia zostawia tabeli {@code wpme_sb_weekly_milestones} —
 * dokładnie tej, na której opiera się natywna pętla. Skyllia i outbox są
 * atrapami, żeby wynik nie zależał od serwera.
 */
class WeeklyMilestoneServiceTest {

    private static final UUID ISLAND = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-000000000001");
    private static final UUID MEMBER = UUID.fromString("11111111-1111-1111-1111-000000000002");
    /** Czwartek 2026-09-10, tydzień ISO 2026-W37 (poniedziałek 2026-09-07). */
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final LocalDate MONDAY = LocalDate.parse("2026-09-07");
    private static final String WEEK_KEY = "2026-W37";

    private SingleConnectionSqlService sql;
    private LedgerService ledger;
    private SkylliaIntegration skyllia;
    private JavaPlugin plugin;
    private InventoryOutbox outbox;
    private CustomItemService customItems;
    private ItemStack lotus;
    private WeeklyMilestoneService service;
    private SeasonDailyPointsDao dailyDao;
    private int questDayCounter;

    @BeforeEach
    void setUp() throws Exception {
        sql = new SingleConnectionSqlService();
        LedgerDao ledgerDao = new LedgerDao(sql);
        ledgerDao.createSchema().join();
        ledger = new LedgerService(ledgerDao, 100L);
        ledger.init();
        dailyDao = new SeasonDailyPointsDao.Sql(sql, () -> 37);
        // Wyspa musi istnieć w profilach — membership ma FK na island_profiles.
        sql.update("""
                INSERT INTO wpme_sb_island_profiles
                    (island_id, owner_uuid, mode, status, created_at, updated_at)
                VALUES (?, ?, 'CLASSIC', 'ACTIVE', ?, ?)
                """, ISLAND.toString(), OWNER.toString(), 1L, 1L).join();

        JavaPlugin plugin = mock(JavaPlugin.class);
        lenient().when(plugin.getLogger())
                .thenReturn(java.util.logging.Logger.getLogger("weekly-milestone-test"));
        this.plugin = plugin;
        skyllia = mock(SkylliaIntegration.class);
        lenient().when(skyllia.cachedIslandIdOf(OWNER)).thenReturn(Optional.of(ISLAND));
        lenient().when(skyllia.cachedIslandIdOf(MEMBER)).thenReturn(Optional.of(ISLAND));
        lenient().when(skyllia.ownerOf(ISLAND))
                .thenReturn(Optional.of(new IslandOwner(OWNER, "Wlasciciel")));
        outbox = mock(InventoryOutbox.class);
        customItems = mock(CustomItemService.class);
        lotus = mock(ItemStack.class);
        lenient().when(customItems.create(WeeklyMilestoneService.GOLD_LOTUS_ID))
                .thenReturn(Optional.of(lotus));

        service = new WeeklyMilestoneService(plugin, ledger, skyllia, outbox, customItems,
                MiniMessage.miniMessage(), sql, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    @DisplayName("14 zadań dnia w tygodniu nie płaci — próg to dokładnie 15")
    void fourteenQuestDaysLeaveTheMilestoneUnclaimed() {
        membership(OWNER, "OWNER");
        questDays(OWNER, 14);

        service.tryClaimAfterDaily(OWNER).join();

        assertFalse(ledger.isWeeklyMilestoneClaimed(ISLAND, WEEK_KEY).join(),
                "14 < 15 — tabela milestone'u musi zostać pusta");
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("15. zadanie dnia płaci 1 Złoty Lotos właścicielowi, powtórka w tym samym tygodniu nic nie dodaje")
    void fifteenthQuestDayPaysOneLotusOnce() {
        membership(OWNER, "OWNER");
        questDays(OWNER, 14);
        service.tryClaimAfterDaily(OWNER).join();
        assertFalse(ledger.isWeeklyMilestoneClaimed(ISLAND, WEEK_KEY).join());

        questDays(OWNER, 1);
        service.tryClaimAfterDaily(OWNER).join();

        assertTrue(ledger.isWeeklyMilestoneClaimed(ISLAND, WEEK_KEY).join(),
                "15. zadanie domyka tydzień");
        ArgumentCaptor<ItemStack> granted = ArgumentCaptor.forClass(ItemStack.class);
        verify(outbox).beginOfflineGrant(eq(OWNER), eq(0L), eq("milestone:" + ISLAND + ':' + WEEK_KEY),
                eq("weekly_milestone"), granted.capture(), any());
        verify(granted.getValue()).setAmount(1);

        // Trzecie i czwarte podejście w tym samym tygodniu: tabela już ma wpis.
        questDays(OWNER, 1);
        service.tryClaimAfterDaily(OWNER).join();
        service.tryClaimAfterDaily(MEMBER).join();
        verify(outbox, times(1)).beginOfflineGrant(any(), anyLong(), anyString(), anyString(),
                any(), any());
        verify(outbox, never()).beginGrant(any(), anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Zadania dnia liczą się po członkach wyspy, nie tylko po właścicielu")
    void memberQuestDaysCountTowardTheIslandGoal() {
        membership(OWNER, "OWNER");
        membership(MEMBER, "MEMBER");
        questDays(MEMBER, 14);

        service.tryClaimAfterDaily(MEMBER).join();
        assertFalse(ledger.isWeeklyMilestoneClaimed(ISLAND, WEEK_KEY).join(),
                "14 zadań członka to jeszcze nie 15");

        questDays(MEMBER, 1);
        service.tryClaimAfterDaily(OWNER).join();

        assertTrue(ledger.isWeeklyMilestoneClaimed(ISLAND, WEEK_KEY).join(),
                "15. zadanie członka domyka tydzień wyspy");
        verify(outbox).beginOfflineGrant(eq(OWNER), eq(0L), eq("milestone:" + ISLAND + ':' + WEEK_KEY),
                eq("weekly_milestone"), any(), any());
    }

    @Test
    @DisplayName("Gracz bez wyspy nie rusza ani licznika, ani nagrody")
    void playerWithoutAnIslandDoesNothing() {
        UUID landless = UUID.randomUUID();

        service.tryClaimAfterDaily(landless).join();

        assertFalse(ledger.isWeeklyMilestoneClaimed(ISLAND, WEEK_KEY).join());
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("Nieznany właściciel nie przepala nagrody — claim czeka na kolejne zadanie")
    void unknownOwnerLeavesTheWeekUnclaimed() {
        membership(OWNER, "OWNER");
        questDays(OWNER, 15);
        when(skyllia.ownerOf(ISLAND)).thenReturn(Optional.empty());

        service.tryClaimAfterDaily(OWNER).join();

        assertFalse(ledger.isWeeklyMilestoneClaimed(ISLAND, WEEK_KEY).join(),
                "bez właściciela nie ma komu wydać Lotosu — claim zostaje na później");
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("Zadania poprzedniego tygodnia nie liczą się do bieżącego")
    void previousWeekQuestDaysDoNotCount() {
        membership(OWNER, "OWNER");
        for (int i = 0; i < 15; i++) {
            questDay(OWNER, MONDAY.minusDays(7).plusDays(i % 5), "previous" + i);
        }

        service.tryClaimAfterDaily(OWNER).join();

        assertFalse(ledger.isWeeklyMilestoneClaimed(ISLAND, WEEK_KEY).join(),
                "tydzień 2026-W36 nie domyka 2026-W37");
        verifyNoInteractions(outbox);
    }

    @Test
    @DisplayName("Dekorator kanału dnia sprawdza próg tylko po punktach kanału quest")
    void watcherChecksTheMilestoneOnlyOnTheQuestChannel() {
        membership(OWNER, "OWNER");
        questDays(OWNER, 14);
        SeasonPointService seasonPoints = mock(SeasonPointService.class);
        lenient().when(seasonPoints.award(any(), anyLong(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));
        lenient().when(seasonPoints.award(any(), anyLong(), anyString(), anyBoolean()))
                .thenReturn(CompletableFuture.completedFuture(true));
        SeasonDailyPointsService watched = service.watchingQuestAwards(
                SeasonDailyPointsService.create(
                        new SeasonDailyPointsDao.Sql(sql, () -> 37), seasonPoints,
                        SeasonDailyPointsCaps.NONE, Clock.fixed(NOW, ZoneOffset.UTC)));

        assertTrue(watched.awardDaily(OWNER, "fish", 5L, "eco-fish:1").join() > 0L);
        assertFalse(ledger.isWeeklyMilestoneClaimed(ISLAND, WEEK_KEY).join(),
                "kanał wędki to nie zadanie dnia — milestone milczy");

        assertTrue(watched.awardDaily(OWNER, WeeklyMilestoneService.QUEST_CHANNEL, 10L,
                "eco-daily:15").join() > 0L);
        assertTrue(ledger.isWeeklyMilestoneClaimed(ISLAND, WEEK_KEY).join(),
                "15. zadanie dnia przechodzi przez dekorator do claimu");
    }

    @Test
    @DisplayName("Klucz tygodnia to rok tygodniowy ISO w formacie wspólnym z QuestCatalog")
    void weekKeyUsesIsoWeekBasedYear() {
        assertEquals("2026-W37", WeeklyMilestoneService.weekKey(LocalDate.parse("2026-09-07")));
        assertEquals("2026-W37", WeeklyMilestoneService.weekKey(LocalDate.parse("2026-09-13")));
        // 2027-01-01 (piątek) należy jeszcze do tygodnia 53 roku 2026.
        assertEquals("2026-W53", WeeklyMilestoneService.weekKey(LocalDate.parse("2027-01-01")));
    }

    /**
     * E2E 2026-09-10: po {@code /is delete confirm} i ponownym utworzeniu wyspy o
     * tym samym {@code island_id} cache Skyllii bywa pusty, więc filtr odbiorców
     * po {@code cachedIslandIdOf} nie wysyłał komunikatu NIKOMU (nagroda szła
     * outboxem, więc defekt był cichy). Odbiorcy muszą iść ze składu wyspy z bazy.
     */
    @Test
    @DisplayName("Komunikat dociera do składu wyspy z bazy, choćby cache Skyllii był pusty")
    void milestoneMessageReachesMembersFromTheDatabaseNotTheSkylliaCache() {
        membership(OWNER, "OWNER");
        membership(MEMBER, "MEMBER");
        questDays(MEMBER, 15);
        Player owner = onlinePlayer(OWNER);
        Player member = onlinePlayer(MEMBER);
        Player stranger = onlinePlayer(UUID.randomUUID());
        givenBroadcastServer(List.of(owner, member, stranger));
        // Cache po odtworzeniu wyspy: zna tylko gracza, który wywołał sprawdzenie.
        lenient().when(skyllia.cachedIslandIdOf(MEMBER)).thenReturn(Optional.empty());

        service.tryClaimAfterDaily(OWNER).join();

        String toOwner = MiniMessage.miniMessage().serialize(capturedMessage(owner));
        assertTrue(toOwner.contains("TYGODNIOWY KAMIEŃ MILOWY"), toOwner);
        assertTrue(MiniMessage.miniMessage().serialize(capturedMessage(member))
                .contains("TYGODNIOWY KAMIEŃ MILOWY"),
                "członek ACTIVE z bazy dostaje komunikat mimo pustego cache");
        verify(stranger, never()).sendMessage(any(Component.class));
    }

    /** Właściciel dostaje komunikat zawsze — nawet gdy jego wiersz membership jest niespójny. */
    @Test
    @DisplayName("Właściciel bez wiersza membership też dostaje komunikat")
    void ownerIsNotifiedEvenWithoutAMembershipRow() {
        membership(MEMBER, "MEMBER");
        questDays(MEMBER, 15);
        Player owner = onlinePlayer(OWNER);
        givenBroadcastServer(List.of(owner));

        service.tryClaimAfterDaily(OWNER).join();

        assertTrue(MiniMessage.miniMessage().serialize(capturedMessage(owner))
                .contains("TYGODNIOWY KAMIEŃ MILOWY"));
    }

    /** Brak graczy online = cisza; dług outboxa i tak powstaje (bez zmian). */
    @Test
    @DisplayName("Bez graczy online nikt nie dostaje komunikatu, a Lotos czeka w outboxie")
    void noOnlineMembersStillQueuesTheLotusInTheOutbox() {
        membership(OWNER, "OWNER");
        questDays(OWNER, 15);
        givenBroadcastServer(List.of());

        service.tryClaimAfterDaily(OWNER).join();

        assertTrue(ledger.isWeeklyMilestoneClaimed(ISLAND, WEEK_KEY).join());
        verify(outbox).beginOfflineGrant(eq(OWNER), eq(0L), eq("milestone:" + ISLAND + ':' + WEEK_KEY),
                eq("weekly_milestone"), any(), any());
    }

    /** Serwer z globalnym schedulerem wykonującym zadania od razu (wzorzec DailyQuestServiceTest). */
    private void givenBroadcastServer(List<Player> online) {
        Server server = mock(Server.class);
        io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler global =
                mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        lenient().when(server.getGlobalRegionScheduler()).thenReturn(global);
        lenient().when(server.getOnlinePlayers()).thenAnswer(invocation -> online);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(global).execute(eq(plugin), any(Runnable.class));
    }

    private Player onlinePlayer(UUID id) {
        Player player = mock(Player.class);
        lenient().when(player.getUniqueId()).thenReturn(id);
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

    private static Component capturedMessage(Player player) {
        ArgumentCaptor<Component> message = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(message.capture());
        return message.getValue();
    }

    private void membership(UUID player, String role) {
        sql.update("""
                INSERT INTO wpme_sb_island_membership
                    (island_id, player_uuid, role, status, joined_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """, ISLAND.toString(), player.toString(), role, 1L, 1L).join();
    }

    /** Dopisuje {@code count} ukończonych zadań dnia (osobny wiersz = osobne zadanie). */
    private void questDays(UUID player, int count) {
        for (int i = 0; i < count; i++) {
            int slot = questDayCounter++;
            questDay(player, MONDAY.plusDays(slot % 5), "task" + slot);
        }
    }

    private void questDay(UUID player, LocalDate day, String operationId) {
        dailyDao.insertIfAbsent(player, day.toString(), WeeklyMilestoneService.QUEST_CHANNEL,
                        "eco-daily:" + operationId, 10L)
                .join();
    }
}
