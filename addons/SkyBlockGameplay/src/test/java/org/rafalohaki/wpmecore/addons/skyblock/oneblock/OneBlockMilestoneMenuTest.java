package org.rafalohaki.wpmecore.addons.skyblock.oneblock;


import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandBounds;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandCapabilities;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.LifecycleState;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OneBlockMilestoneMenuTest {

    private static final String LOOT = "item:skyblock:token/silver_lotus:8";

    private JavaPlugin plugin;
    private MenuService menus;
    private OneBlockMilestoneDao milestoneDao;
    private CustomItemService customItems;
    private SkylliaIntegration skyllia;
    private Player player;
    private PlayerInventory inventory;
    private OneBlockMilestoneMenu milestoneMenu;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        menus = mock(MenuService.class);
        milestoneDao = mock(OneBlockMilestoneDao.class);
        customItems = mock(CustomItemService.class);
        skyllia = mock(SkylliaIntegration.class);
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);

        // Folia: EntityScheduler.run(Plugin, Consumer<ScheduledTask>, Runnable) —
        // w teście consumer wykonuje się natychmiast (wątek gracza symulowany synchronicznie).
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        lenient().when(player.getScheduler()).thenReturn(entityScheduler);
        lenient().doAnswer(inv -> {
            Consumer<ScheduledTask> task = inv.getArgument(1);
            task.accept(null);
            return mock(ScheduledTask.class);
        }).when(entityScheduler).run(any(), any(), any());

        lenient().when(player.getInventory()).thenReturn(inventory);
        lenient().when(milestoneDao.listUnclaimed(any()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

        milestoneMenu = new OneBlockMilestoneMenu(plugin, menus, MiniMessage.miniMessage(),
                milestoneDao, customItems, skyllia);
    }

    private static OneBlockMilestoneDao.MilestoneRow row() {
        return new OneBlockMilestoneDao.MilestoneRow("poczatek:100", LOOT, 1L);
    }

    private static IslandView view(UUID islandId) {
        return new IslandView(new IslandSnapshot(islandId, UUID.randomUUID(),
                LifecycleState.active(), IslandBounds.of(0, 0, 100),
                IslandCapabilities.allEnabled(), 1L), IslandRole.OWNER);
    }

    @Test
    void claimGrantsLootWhenInventoryHasRoom() {
        UUID islandId = UUID.randomUUID();
        ItemStack granted = mock(ItemStack.class);
        when(customItems.create("skyblock:token/silver_lotus")).thenReturn(Optional.of(granted));
        when(milestoneDao.claim(eq(islandId), eq("poczatek:100"), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(1));
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());

        milestoneMenu.claim(player, islandId, row());

        verify(granted).setAmount(8);
        verify(inventory).addItem(any(ItemStack[].class));
        verify(inventory, never()).removeItem(any(ItemStack[].class));
        verify(milestoneDao, never()).revert(any(), anyString(), anyLong());
        verify(player).sendMessage(any(Component.class));
        verify(player).playSound(any(Sound.class));
    }

    @Test
    void fullInventoryRevertsClaimWithSameTimestamp() {
        UUID islandId = UUID.randomUUID();
        ItemStack granted = mock(ItemStack.class);
        when(granted.getAmount()).thenReturn(8);
        when(customItems.create("skyblock:token/silver_lotus")).thenReturn(Optional.of(granted));
        when(milestoneDao.claim(eq(islandId), eq("poczatek:100"), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(1));
        ItemStack leftover = mock(ItemStack.class);
        when(leftover.getAmount()).thenReturn(8); // nic się nie zmieściło
        HashMap<Integer, ItemStack> overflow = new HashMap<>();
        overflow.put(0, leftover);
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(overflow);

        milestoneMenu.claim(player, islandId, row());

        // nic nie weszło ⇒ nic nie trzeba zdejmować
        verify(inventory, never()).removeItem(any(ItemStack[].class));
        // revert musi cofać dokładnie ten timestamp, którym claimował wiersz
        ArgumentCaptor<Long> claimTimestamp = ArgumentCaptor.forClass(Long.class);
        verify(milestoneDao).claim(eq(islandId), eq("poczatek:100"), claimTimestamp.capture());
        ArgumentCaptor<Long> revertTimestamp = ArgumentCaptor.forClass(Long.class);
        verify(milestoneDao).revert(eq(islandId), eq("poczatek:100"), revertTimestamp.capture());
        assertEquals(claimTimestamp.getValue(), revertTimestamp.getValue());
        verify(player).sendMessage(any(Component.class));
    }

    @Test
    void partialOverflowRollsBackExactlyWhatWasAddedBeforeRevert() {
        UUID islandId = UUID.randomUUID();
        // silver (8) wchodzi w całości, gold (4) mieści tylko 3 — leftover 1 pod indeksem 1
        OneBlockMilestoneDao.MilestoneRow row = new OneBlockMilestoneDao.MilestoneRow(
                "poczatek:100", "item:skyblock:token/silver_lotus:8;item:skyblock:token/gold_lotus:4", 1L);
        ItemStack silver = mock(ItemStack.class);
        ItemStack gold = mock(ItemStack.class);
        when(silver.getAmount()).thenReturn(8);
        when(gold.getAmount()).thenReturn(4);
        when(customItems.create("skyblock:token/silver_lotus")).thenReturn(Optional.of(silver));
        when(customItems.create("skyblock:token/gold_lotus")).thenReturn(Optional.of(gold));
        when(milestoneDao.claim(eq(islandId), eq("poczatek:100"), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(1));
        ItemStack leftover = mock(ItemStack.class);
        when(leftover.getAmount()).thenReturn(1);
        HashMap<Integer, ItemStack> overflow = new HashMap<>();
        overflow.put(1, leftover);
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(overflow);

        milestoneMenu.claim(player, islandId, row);

        // zdejmij dokładnie dodane kwoty PRZED revert — inaczej re-claim duplikuje loot
        ArgumentCaptor<ItemStack[]> takeBack = ArgumentCaptor.forClass(ItemStack[].class);
        InOrder order = inOrder(inventory, milestoneDao);
        order.verify(inventory).removeItem(takeBack.capture());
        order.verify(milestoneDao).revert(eq(islandId), eq("poczatek:100"), anyLong());
        ItemStack[] removed = takeBack.getValue();
        assertEquals(2, removed.length);
        assertSame(silver, removed[0]);
        assertSame(gold, removed[1]);
        verify(silver, times(2)).setAmount(8); // budowa stacku + pełny zwrot (wszedł cały)
        verify(gold).setAmount(4);             // budowa stacku
        verify(gold).setAmount(3);             // zwrot 4 − 1 leftover
        verify(player).sendMessage(any(Component.class));
    }

    /**
     * Gracz wychodzi między UPDATE claim a planowaniem wydania: EntityScheduler.run
     * zwraca null i nie wykonuje taska. Wiersz musi wrócić revert-em z tym samym
     * timestampem, inaczej nagroda przepada bezpowrotnie (SKYBLOCK-2-3).
     */
    @Test
    void retiredPlayerBetweenClaimAndSchedulingRevertsTheClaimRow() {
        UUID islandId = UUID.randomUUID();
        when(milestoneDao.claim(eq(islandId), eq("poczatek:100"), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(1));
        lenient().when(milestoneDao.revert(any(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(1));
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        // Wycofana encja: run() zwraca null, task nigdy się nie wykonuje.
        when(player.getScheduler()).thenReturn(mock(EntityScheduler.class));

        milestoneMenu.claim(player, islandId, row());

        ArgumentCaptor<Long> claimTimestamp = ArgumentCaptor.forClass(Long.class);
        verify(milestoneDao).claim(eq(islandId), eq("poczatek:100"), claimTimestamp.capture());
        ArgumentCaptor<Long> revertTimestamp = ArgumentCaptor.forClass(Long.class);
        verify(milestoneDao).revert(eq(islandId), eq("poczatek:100"), revertTimestamp.capture());
        assertEquals(claimTimestamp.getValue(), revertTimestamp.getValue());
        verify(inventory, never()).addItem(any(ItemStack[].class));
    }

    @Test
    void losingClaimRaceSkipsItemsAndRevertsNothing() {
        UUID islandId = UUID.randomUUID();
        when(milestoneDao.claim(eq(islandId), eq("poczatek:100"), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(0));

        milestoneMenu.claim(player, islandId, row());

        verify(customItems, never()).create(anyString());
        verify(inventory, never()).addItem(any(ItemStack[].class));
        verify(milestoneDao, never()).revert(any(), anyString(), anyLong());
        verify(player).sendMessage(any(Component.class)); // "już odebrana" — klik nie jest cichy
        verify(milestoneDao).listUnclaimed(islandId); // przegrany wyścig = tylko odświeżenie
    }

    @Test
    void unknownItemIdIsLoggedAndClaimStillConsumes() {
        UUID islandId = UUID.randomUUID();
        OneBlockMilestoneDao.MilestoneRow row = new OneBlockMilestoneDao.MilestoneRow(
                "poczatek:100", "item:skyblock:brak/niema:2", 1L);
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(customItems.create("skyblock:brak/niema")).thenReturn(Optional.empty());
        when(milestoneDao.claim(eq(islandId), eq("poczatek:100"), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(1));
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());

        milestoneMenu.claim(player, islandId, row);

        verify(logger).warning(contains("skyblock:brak/niema"));
        verify(milestoneDao, never()).revert(any(), anyString(), anyLong());
        verify(player).sendMessage(any(Component.class)); // claim i tak konsumuje (safety net)
    }

    @Test
    void openForWithoutIslandExplainsHowToGetRewards() {
        when(skyllia.islandOf(any())).thenReturn(Optional.empty());

        milestoneMenu.openFor(player);

        verify(player).sendMessage(any(Component.class));
        verify(milestoneDao, never()).listUnclaimed(any());
        verify(menus, never()).ofRows(anyInt(), any());
    }

    @Test
    void openWithNothingUnclaimedEncouragesMining() {
        UUID islandId = UUID.randomUUID();
        when(skyllia.islandOf(any())).thenReturn(Optional.of(view(islandId)));

        milestoneMenu.openFor(player);

        verify(player).sendMessage(any(Component.class));
        verify(menus, never()).ofRows(anyInt(), any());
    }

    // ------------------------------------------------------------------
    // Layout (A3): kamień milowy i przycisk zamknięcia nie mogą dzielić
    // slotu. Menu renderuje prawdziwe ItemStacki, więc konstrukcje są
    // przechwycone (wzorzec IslandCreationMenusStaleReadyTest, bez MockBukkit).
    // ------------------------------------------------------------------

    /** Nagrody podglądowe; liczba wpisów steruje rozmiarem menu. */
    private static List<OneBlockMilestoneDao.MilestoneRow> milestones(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new OneBlockMilestoneDao.MilestoneRow("kamien:" + i, LOOT, 1L))
                .toList();
    }

    /** Otwiera menu z przechwyconymi konstrukcjami ItemStack (poza serwerem wymagają rejestrów). */
    private void openingMenu(@NotNull Runnable action) {
        Ui.init(null); // fail-open: żadnej paczki zasobów w testach
        try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class,
                (mock, context) -> {
                    Material material = context.arguments().isEmpty()
                            ? Material.STONE
                            : (Material) context.arguments().get(0);
                    when(mock.getType()).thenReturn(material);
                    when(mock.clone()).thenReturn(mock);
                })) {
            action.run();
        }
    }

    /** Menu-podszywacz: trzyma slot treści osobno od slotu przycisku zamknięcia. */
    private static final class RecordingMenu implements MenuService.Menu {

        private final int rows;
        private final Map<Integer, ItemStack> items = new HashMap<>();
        private final Map<Integer, MenuService.ClickHandler> handlers = new HashMap<>();
        private int closeSlot = -1;

        private RecordingMenu(int rows) {
            this.rows = rows;
        }

        @Override
        public int size() {
            return rows * 9;
        }

        @Override
        public boolean add(@NotNull ItemStack icon, @NotNull MenuService.ClickHandler handler) {
            throw new UnsupportedOperationException("menu kamieni milowych używa jawnych slotów");
        }

        @Override
        public @NotNull MenuService.Menu set(int slot, @NotNull ItemStack icon,
                                             @NotNull MenuService.ClickHandler handler) {
            items.put(slot, icon);
            handlers.put(slot, handler);
            return this;
        }

        @Override
        public @NotNull MenuService.Menu decoration(int slot, @NotNull ItemStack icon) {
            items.put(slot, icon);
            return this;
        }

        @Override
        public @NotNull MenuService.Menu close(int slot, @NotNull ItemStack icon) {
            closeSlot = slot;
            return set(slot, icon, (viewer, click) -> viewer.closeInventory());
        }

        @Override
        public void open(@NotNull Player viewer) {
        }
    }

    /**
     * Regresja A3: siedem kamieni milowych wypełnia cały wiersz treści, a
     * zamknięcie stało w slocie 13 ({@code size()-5}), czyli na czwartym
     * kamieniu — gracz nie mógł go odebrać.
     */
    @Test
    void sevenMilestonesLeaveTheCloseButtonItsOwnSlot() {
        UUID islandId = UUID.randomUUID();
        RecordingMenu menu = new RecordingMenu(2);
        when(menus.ofRows(eq(2), any())).thenReturn(menu);
        when(milestoneDao.listUnclaimed(islandId))
                .thenReturn(CompletableFuture.completedFuture(milestones(7)));

        openingMenu(() -> milestoneMenu.open(player, islandId));

        for (int slot = 10; slot <= 16; slot++) {
            assertNotNull(menu.handlers.get(slot),
                    "kamień milowy na slocie " + slot + " ma być klikalny");
        }
        assertEquals(17, menu.closeSlot,
                "zamknięcie ma wylądować w wolnym narożniku, nie na kamieniu milowym");
        assertEquals(Material.BLACK_STAINED_GLASS_PANE, menu.items.get(4).getType(),
                "bez nadmiaru nagłówek zostaje tłem ramy");
    }

    /** Bardzo długa lista: nadmiaru nie chowamy po cichu — nagłówek go zapowiada. */
    @Test
    void oversizedListAnnouncesHowManyRewardsStayHidden() {
        UUID islandId = UUID.randomUUID();
        RecordingMenu menu = new RecordingMenu(6);
        when(menus.ofRows(eq(6), any())).thenReturn(menu);
        when(milestoneDao.listUnclaimed(islandId))
                .thenReturn(CompletableFuture.completedFuture(milestones(40)));

        openingMenu(() -> milestoneMenu.open(player, islandId));

        // 6 rzędów × 7 slotów treści = 35 nagród; pozostałe 5 sygnalizuje nagłówek.
        assertEquals(Material.PAPER, menu.items.get(4).getType(),
                "nadmiar nagród ma być widoczny w nagłówku menu");
        assertEquals(53, menu.closeSlot, "zamknięcie w prawym dolnym narożniku");
    }
}
