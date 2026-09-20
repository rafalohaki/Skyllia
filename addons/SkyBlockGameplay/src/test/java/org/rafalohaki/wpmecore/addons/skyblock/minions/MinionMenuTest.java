package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;


import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinionMenuTest {

    private MinionService service;
    private LedgerService ledger;
    private SkylliaIntegration skyllia;
    private MinionDisplayRenderer renderer;
    private MinionMenu menu;
    private Player player;
    private PlayerInventory playerInventory;
    private UUID minionId;
    private MinionRecord record;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("SkyBlockGameplay");
        when(plugin.getServer()).thenReturn(mock(Server.class));
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getGlobal());
        service = mock(MinionService.class);
        ledger = mock(LedgerService.class);
        skyllia = mock(SkylliaIntegration.class);
        renderer = mock(MinionDisplayRenderer.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        playerInventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(playerInventory);
        io.papermc.paper.threadedregions.scheduler.EntityScheduler entityScheduler =
                mock(io.papermc.paper.threadedregions.scheduler.EntityScheduler.class);
        doAnswer(inv -> {
            Object task = inv.getArgument(1);
            if (task instanceof Runnable runnable) {
                runnable.run();
            } else if (task instanceof java.util.function.Consumer<?> consumer) {
                consumer.accept(null);
            }
            return null;
        }).when(entityScheduler).run(any(), any(), any());
        when(player.getScheduler()).thenReturn(entityScheduler);
        when(playerInventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

        MenuService menus = mock(MenuService.class);
        Map<Integer, MinionsConfig.TierDef> tiers = Map.of(
                1, new MinionsConfig.TierDef(1, 10.0, 2, 0L, 0, null, 0.0),
                2, new MinionsConfig.TierDef(2, 8.0, 4, 500L, 32, null, 0.0));
        MinionsConfig config = new MinionsConfig(
                new MinionsConfig.Settings(5, 1, 1, 2, 2), Map.of(), Map.of(),
                Map.of("diamond", new MinionsConfig.TypeDef("diamond", "<aqua>M</aqua>", "tex",
                        null, Material.DIAMOND, 1, tiers)));
        // stackFor nadpisany: realna konstrukcja ItemStack wymaga RegistryAccess serwera
        menu = new MinionMenu(plugin, menus, MiniMessage.miniMessage(),
                service, ledger, skyllia, config, null, renderer) {
            @Override
            ItemStack stackFor(String key, int amount) {
                ItemStack stack = mock(ItemStack.class);
                when(stack.getType()).thenReturn(Material.DIAMOND);
                when(stack.getAmount()).thenReturn(amount);
                when(stack.clone()).thenReturn(stack);
                return stack;
            }
        };

        minionId = UUID.randomUUID();
        record = new MinionRecord(minionId, UUID.randomUUID(), "diamond", 1,
                "w", 1, 64, 1, Map.of(), null, 0L, false, null, null, null, 0L,
                System.currentTimeMillis(), System.currentTimeMillis());
        when(service.snapshot(minionId)).thenReturn(Optional.of(record));
        when(service.storage(minionId)).thenReturn(MinionStorage.empty(4));
        // MINION-10: podnoszenie wymaga teraz roli — domyślnie właściciel tej wyspy.
        when(skyllia.islandOf(any(UUID.class))).thenReturn(Optional.of(ownerView(record.islandId())));
    }

    private static IslandView ownerView(UUID islandId) {
        return new IslandView(new IslandSnapshot(islandId, islandId, null, null, null, 0L), IslandRole.OWNER);
    }

    @Test
    void collectAllMovesStorageToPlayerInventory() {
        MinionStorage storage = MinionStorage.empty(4);
        storage.add("DIAMOND", 10L);
        when(service.storage(minionId)).thenReturn(storage);

        menu.collectAllClick(minionId, null).onClick(player, ClickType.LEFT);

        org.junit.jupiter.api.Assertions.assertEquals(0L, storage.count("DIAMOND"));
        verify(playerInventory).addItem(any(ItemStack.class));
        verify(service).markDirty(minionId);
    }

    @Test
    void compactorSlotTogglesState() {
        when(service.setCompactorEnabled(minionId, true)).thenReturn(true);

        menu.compactorClick(minionId, null).onClick(player, ClickType.LEFT);

        verify(service).setCompactorEnabled(minionId, true);
    }

    @Test
    void pickupDespawnsReturnsItemAndCloses() {
        when(service.pickUp(minionId)).thenReturn(Optional.of(record));

        menu.pickUpClick(minionId).onClick(player, ClickType.LEFT);

        verify(service).pickUp(minionId);
        verify(player).closeInventory();
    }

    /**
     * Podniesienie minionka rozdziela magazyn na dwie drogi: to, co weszło graczowi
     * do ekwipunku, i resztę zakodowaną w przedmiocie. Każda sztuka musi pojechać
     * DOKŁADNIE jedną z nich — inaczej albo znika, albo się mnoży.
     */
    /** {@code Inventory#addItem} zwraca konkretny {@link HashMap} — mock musi oddać to samo. */
    private static HashMap<Integer, ItemStack> refused(int amount) {
        ItemStack returned = mock(ItemStack.class);
        when(returned.getAmount()).thenReturn(amount);
        HashMap<Integer, ItemStack> leftover = new HashMap<>();
        leftover.put(0, returned);
        return leftover;
    }

    private static HashMap<Integer, ItemStack> refusedWhole(ItemStack offered) {
        HashMap<Integer, ItemStack> leftover = new HashMap<>();
        leftover.put(0, offered);
        return leftover;
    }

    @Test
    void storageThatFitsLeavesNothingInThePickedUpItem() {
        when(playerInventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

        Map<String, Long> travelling = menu.handOverStorage(player, Map.of("DIAMOND", 10L));

        org.junit.jupiter.api.Assertions.assertTrue(travelling.isEmpty(),
                "gracz dostał wszystko, więc przedmiot nie może nieść drugiej kopii: " + travelling);
    }

    @Test
    void storageThatDoesNotFitTravelsInTheItemInstead() {
        // Ekwipunek pełny: addItem oddaje cały stack z powrotem.
        when(playerInventory.addItem(any(ItemStack.class)))
                .thenAnswer(inv -> refusedWhole(inv.getArgument(0)));

        Map<String, Long> travelling = menu.handOverStorage(player, Map.of("DIAMOND", 10L));

        org.junit.jupiter.api.Assertions.assertEquals(Map.of("DIAMOND", 10L), travelling,
                "nic nie weszło do EQ, więc całość musi pojechać w przedmiocie");
    }

    @Test
    void partialFitNeitherLosesNorDuplicates() {
        java.util.concurrent.atomic.AtomicInteger call = new java.util.concurrent.atomic.AtomicInteger();
        when(playerInventory.addItem(any(ItemStack.class))).thenAnswer(inv ->
                // pierwsze podanie: przyjęte 6 z 10; kolejne: już nic się nie mieści
                call.getAndIncrement() == 0 ? refused(4) : refusedWhole(inv.getArgument(0)));

        Map<String, Long> travelling = menu.handOverStorage(player, Map.of("DIAMOND", 10L));

        org.junit.jupiter.api.Assertions.assertEquals(Map.of("DIAMOND", 4L), travelling,
                "sześć sztuk weszło do EQ, więc w przedmiocie mogą zostać dokładnie cztery");
    }

    @Test
    void aFullInventoryIsExplainedInsteadOfSilentlyStashingTheLoot() {
        when(service.pickUp(minionId)).thenReturn(Optional.of(
                record.withStorage(Map.of("DIAMOND", 10L))));
        when(playerInventory.addItem(any(ItemStack.class)))
                .thenAnswer(inv -> refusedWhole(inv.getArgument(0)));

        menu.pickUpClick(minionId).onClick(player, ClickType.LEFT);

        org.mockito.ArgumentCaptor<net.kyori.adventure.text.Component> said =
                org.mockito.ArgumentCaptor.forClass(net.kyori.adventure.text.Component.class);
        verify(player, org.mockito.Mockito.atLeastOnce()).sendMessage(said.capture());
        String all = said.getAllValues().stream()
                .map(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText()::serialize)
                .reduce("", (a, b) -> a + "\n" + b);
        org.junit.jupiter.api.Assertions.assertTrue(all.contains("minionku"),
                "gracz musi wiedzieć, gdzie jest urobek, którego nie przyjął ekwipunek: " + all);
    }

    @Test
    void upgradeWithdrawsIslandMoneyAfterRoleCheck() {
        UUID islandId = record.islandId();
        when(skyllia.islandOf(any(UUID.class))).thenReturn(Optional.of(new IslandView(
                new IslandSnapshot(islandId, islandId, null, null, null, 0L), IslandRole.OWNER)));
        when(ledger.authoritativeIslandBalance(islandId))
                .thenReturn(CompletableFuture.completedFuture(1000L));
        when(ledger.withdrawIsland(eq(islandId), eq(500L), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new LedgerDao.Mutation(true, false, 500L)));
        when(service.setTier(minionId, 2)).thenReturn(true);
        ItemStack diamonds = mock(ItemStack.class);
        when(diamonds.getType()).thenReturn(Material.DIAMOND);
        when(diamonds.getAmount()).thenReturn(64);
        when(playerInventory.getStorageContents()).thenReturn(new ItemStack[]{diamonds});

        menu.upgradeClick(minionId, null).onClick(player, ClickType.LEFT);

        verify(ledger, timeout(1000)).withdrawIsland(eq(islandId), eq(500L), anyString(), anyString());
        verify(service, timeout(1000)).setTier(minionId, 2);
    }

    @Test
    void pickUpIsRefusedForSomeoneWithoutIslandRole() {
        when(skyllia.islandOf(any(UUID.class))).thenReturn(Optional.empty());

        menu.pickUpClick(minionId).onClick(player, ClickType.LEFT);

        verify(service, never()).pickUp(any(UUID.class));
    }

    @Test
    void pickUpIsRefusedForMemberOfAnotherIsland() {
        when(skyllia.islandOf(any(UUID.class))).thenReturn(Optional.of(ownerView(UUID.randomUUID())));

        menu.pickUpClick(minionId).onClick(player, ClickType.LEFT);

        verify(service, never()).pickUp(any(UUID.class));
    }

    /** MINION-9: nieznany typ = brak przedmiotu do oddania, więc wiersz nie może zniknąć. */
    @Test
    void pickUpOfUnknownTypeLeavesTheMinionInPlace() {
        MinionRecord ghost = new MinionRecord(minionId, record.islandId(), "ghost", 1,
                "w", 1, 64, 1, Map.of(), null, 0L, false, null, null, null, 0L,
                System.currentTimeMillis(), System.currentTimeMillis());
        when(service.snapshot(minionId)).thenReturn(Optional.of(ghost));

        menu.pickUpClick(minionId).onClick(player, ClickType.LEFT);

        verify(service, never()).pickUp(any(UUID.class));
    }

    /** MINION-6: drugie kliknięcie w trakcie wypłaty nie może zapłacić drugi raz. */
    @Test
    void doubleClickDuringUpgradePaysOnce() {
        UUID islandId = record.islandId();
        CompletableFuture<Long> pendingBalance = new CompletableFuture<>();
        when(ledger.authoritativeIslandBalance(islandId)).thenReturn(pendingBalance);
        when(ledger.withdrawIsland(eq(islandId), eq(500L), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new LedgerDao.Mutation(true, false, 500L)));
        when(service.setTier(minionId, 2)).thenReturn(true);
        ItemStack diamonds = mock(ItemStack.class);
        when(diamonds.getType()).thenReturn(Material.DIAMOND);
        when(diamonds.getAmount()).thenReturn(64);
        when(playerInventory.getStorageContents()).thenReturn(new ItemStack[]{diamonds});

        menu.upgradeClick(minionId, null).onClick(player, ClickType.LEFT);
        menu.upgradeClick(minionId, null).onClick(player, ClickType.LEFT);
        pendingBalance.complete(1000L);

        verify(ledger, timeout(1000).times(1)).withdrawIsland(eq(islandId), eq(500L), anyString(), anyString());
        verify(service, timeout(1000).times(1)).setTier(minionId, 2);
    }

    /** MINION-7: składniki wyrzucone między sprawdzeniem a wypłatą = brak ulepszenia i brak przelewu. */
    @Test
    void ingredientsDroppedBeforePaymentAbortTheUpgrade() {
        UUID islandId = record.islandId();
        CompletableFuture<Long> pendingBalance = new CompletableFuture<>();
        when(ledger.authoritativeIslandBalance(islandId)).thenReturn(pendingBalance);
        ItemStack diamonds = mock(ItemStack.class);
        when(diamonds.getType()).thenReturn(Material.DIAMOND);
        when(diamonds.getAmount()).thenReturn(64);
        when(playerInventory.getStorageContents()).thenReturn(new ItemStack[]{diamonds});

        menu.upgradeClick(minionId, null).onClick(player, ClickType.LEFT);
        when(playerInventory.getStorageContents()).thenReturn(new ItemStack[]{});
        pendingBalance.complete(1000L);

        verify(ledger, never()).withdrawIsland(any(UUID.class), anyLong(), anyString(), anyString());
        verify(service, never()).setTier(any(UUID.class), anyInt());
    }

    /** MINION-8: odrzucona aktywacja nie może zjeść paliwa z kursora. */
    @Test
    void rejectedRefuelKeepsTheFuelOnTheCursor() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getServer()).thenReturn(mock(Server.class));
        Map<Integer, MinionsConfig.TierDef> tiers = Map.of(
                1, new MinionsConfig.TierDef(1, 10.0, 2, 0L, 0, null, 0.0));
        MinionsConfig fuelled = new MinionsConfig(
                new MinionsConfig.Settings(5, 1, 1, 2, 2),
                Map.of("coal", new MinionsConfig.FuelDef("coal", Material.COAL, null, 3600L, 1.5)),
                Map.of(),
                Map.of("diamond", new MinionsConfig.TypeDef("diamond", "<aqua>M</aqua>", "tex",
                        null, Material.DIAMOND, 1, tiers)));
        MinionMenu fuelMenu = new MinionMenu(plugin, mock(MenuService.class), MiniMessage.miniMessage(),
                service, ledger, skyllia, fuelled, null, renderer);
        ItemStack coal = mock(ItemStack.class);
        when(coal.getType()).thenReturn(Material.COAL);
        when(coal.getAmount()).thenReturn(3);
        when(player.getItemOnCursor()).thenReturn(coal);
        when(service.activateFuel(eq(minionId), any())).thenReturn(false);

        fuelMenu.refuelClick(minionId, null).onClick(player, ClickType.LEFT);

        verify(coal, never()).setAmount(anyInt());
        verify(player, never()).setItemOnCursor(any());

        when(service.activateFuel(eq(minionId), any())).thenReturn(true);
        fuelMenu.refuelClick(minionId, null).onClick(player, ClickType.LEFT);
        verify(coal).setAmount(2);
    }
}
