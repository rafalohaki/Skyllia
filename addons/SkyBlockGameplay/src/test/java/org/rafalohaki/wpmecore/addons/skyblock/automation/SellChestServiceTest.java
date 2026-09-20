package org.rafalohaki.wpmecore.addons.skyblock.automation;

import org.rafalohaki.wpmecore.addons.skyblock.economy.AccountKey;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;

import org.rafalohaki.wpmecore.addons.skyblock.shop.ShopCatalog;


import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellChestServiceTest {

    private Plugin plugin;
    private Server server;
    private AsyncScheduler asyncScheduler;
    private RegionScheduler regionScheduler;
    private SellChestDao dao;
    private ShopCatalog catalog;
    private LedgerService ledger;
    private MiniMessage miniMessage;
    private SkylliaIntegration skyllia;
    private SellChestService service;
    private SellChestListener listener;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        server = mock(Server.class);
        asyncScheduler = mock(AsyncScheduler.class);
        regionScheduler = mock(RegionScheduler.class);

        when(plugin.getName()).thenReturn("SkyBlockGameplay");
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        when(server.getRegionScheduler()).thenReturn(regionScheduler);

        dao = mock(SellChestDao.class);
        when(dao.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(dao.create(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(dao.deleteByLocation(any(), anyInt(), anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(null));
        when(dao.deleteByIsland(any())).thenReturn(CompletableFuture.completedFuture(null));

        catalog = mock(ShopCatalog.class);
        ledger = mock(LedgerService.class);
        when(ledger.deposit(any(AccountKey.class), anyLong(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(new LedgerDao.Mutation(true, false, 1000L)));

        miniMessage = MiniMessage.miniMessage();
        skyllia = mock(SkylliaIntegration.class);

        service = new SellChestService(plugin, dao, catalog, ledger, miniMessage, skyllia);
        listener = new SellChestListener(plugin, service, miniMessage, skyllia);
    }

    private ItemStack mockItemStack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.clone()).thenReturn(stack);
        when(stack.hasItemMeta()).thenReturn(false);
        try {
            when(stack.isSimilar(any(ItemStack.class))).thenAnswer(invocation -> {
                ItemStack other = invocation.getArgument(0);
                return other != null && other.getType() == material;
            });
        } catch (Exception ignored) {
        }
        return stack;
    }

    private ItemStack mockCustomMetaItemStack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.clone()).thenReturn(stack);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(stack.isSimilar(any(ItemStack.class))).thenReturn(false);
        return stack;
    }

    @Test
    void testStrictPdcValidationRejectsAnvilSpoofing() {
        ItemStack itemMock = mock(ItemStack.class);
        ItemMeta metaMock = mock(ItemMeta.class);
        PersistentDataContainer pdcMock = mock(PersistentDataContainer.class);

        when(itemMock.getType()).thenReturn(Material.CHEST);
        when(itemMock.hasItemMeta()).thenReturn(true);
        when(itemMock.getItemMeta()).thenReturn(metaMock);
        when(metaMock.getPersistentDataContainer()).thenReturn(pdcMock);

        // When PDC tag is set -> accepted
        when(pdcMock.get(SellChestService.KEY_CUSTOM_ITEM, PersistentDataType.STRING))
                .thenReturn(SellChestService.CUSTOM_ITEM_TAG);
        assertTrue(service.isSellChestItem(itemMock));

        // When PDC tag is missing, even with renamed anvil display name -> strictly rejected!
        when(pdcMock.get(SellChestService.KEY_CUSTOM_ITEM, PersistentDataType.STRING)).thenReturn(null);
        when(pdcMock.get(SellChestService.KEY_CUSTOM_ITEM_ID, PersistentDataType.STRING)).thenReturn(null);
        when(metaMock.hasDisplayName()).thenReturn(true);
        when(metaMock.displayName()).thenReturn(miniMessage.deserialize("<gold><bold>Skrzynia Autosprzedaży (Sell Chest)</bold></gold>"));

        assertFalse(service.isSellChestItem(itemMock), "Anvil renamed item without PDC tags must be rejected");

        // When PDC tag KEY_CUSTOM_ITEM_ID is set -> accepted
        when(pdcMock.get(SellChestService.KEY_CUSTOM_ITEM_ID, PersistentDataType.STRING))
                .thenReturn(SellChestService.CUSTOM_ITEM_TAG);
        assertTrue(service.isSellChestItem(itemMock));

        // Null and AIR
        assertFalse(service.isSellChestItem(null));
        ItemStack airItem = mock(ItemStack.class);
        when(airItem.getType()).thenReturn(Material.AIR);
        assertFalse(service.isSellChestItem(airItem));
    }

    @Test
    void testInitializeLoadsAllFromDao() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        SellChestRecord record = new SellChestRecord(
                islandId, "skyblock_world", 10, 64, 20, ownerId, System.currentTimeMillis());
        when(dao.loadAll()).thenReturn(CompletableFuture.completedFuture(List.of(record)));

        service.initialize().join();

        assertTrue(service.hasSellChest("skyblock_world", 10, 64, 20));
        Optional<SellChestRecord> opt = service.getSellChest("skyblock_world", 10, 64, 20);
        assertTrue(opt.isPresent());
        assertEquals(islandId, opt.get().islandId());
        assertEquals(10, opt.get().x());
        assertEquals(64, opt.get().y());
        assertEquals(20, opt.get().z());
        assertEquals(record, service.getSellChestOrNull("skyblock_world", 10, 64, 20));
    }

    @Test
    void testRegisterAndUnregisterSellChest() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        SellChestRecord record = new SellChestRecord(
                islandId, "skyblock_world", 5, 64, 5, ownerId, System.currentTimeMillis());

        service.registerSellChest(record).join();
        verify(dao).save(record);
        assertTrue(service.hasSellChest("skyblock_world", 5, 64, 5));
        assertEquals(1, service.getSellChestCountForIsland(islandId));

        service.unregisterSellChest("skyblock_world", 5, 64, 5).join();
        verify(dao).deleteByLocation("skyblock_world", 5, 64, 5);
        assertFalse(service.hasSellChest("skyblock_world", 5, 64, 5));
        assertEquals(0, service.getSellChestCountForIsland(islandId));
    }

    @Test
    void testUnregisterByIsland() {
        UUID island1 = UUID.randomUUID();
        UUID island2 = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        SellChestRecord r1 = new SellChestRecord(island1, "skyblock_world", 1, 64, 1, owner, 1000L);
        SellChestRecord r2 = new SellChestRecord(island1, "skyblock_world", 2, 64, 2, owner, 2000L);
        SellChestRecord r3 = new SellChestRecord(island2, "skyblock_world", 3, 64, 3, owner, 3000L);

        service.registerSellChest(r1).join();
        service.registerSellChest(r2).join();
        service.registerSellChest(r3).join();

        assertEquals(2, service.getSellChestCountForIsland(island1));
        assertEquals(1, service.getSellChestCountForIsland(island2));

        service.unregisterByIsland(island1).join();
        verify(dao).deleteByIsland(island1);
        assertEquals(0, service.getSellChestCountForIsland(island1));
        assertEquals(1, service.getSellChestCountForIsland(island2));
        assertTrue(service.hasSellChest("skyblock_world", 3, 64, 3));
        assertFalse(service.hasSellChest("skyblock_world", 1, 64, 1));
    }

    @Test
    void testEvaluateAndPurgeSingleMaterial() {
        ShopCatalog.Product cobbleProduct = new ShopCatalog.Product("cobble", Material.COBBLESTONE, 10L, 5L, null, null, 0);
        when(catalog.product(Material.COBBLESTONE)).thenReturn(cobbleProduct);

        Inventory inventory = mock(Inventory.class);
        ItemStack stack = mockItemStack(Material.COBBLESTONE, 64);
        ItemStack[] contents = new ItemStack[]{stack, null, null};
        when(inventory.getStorageContents()).thenReturn(contents);

        SellChestService.ValuationResult result = service.evaluateAndPurge(inventory);

        assertEquals(320L, result.totalValue()); // 64 * 5
        assertEquals(64, result.itemsSold());
        assertEquals(Map.of(Material.COBBLESTONE, 64), result.soldCounts());
        assertNull(contents[0], "Sold item stack should be purged from storage contents");
        verify(inventory).setStorageContents(contents);
    }

    @Test
    void testEvaluateAndPurgeMultipleMaterials() {
        ShopCatalog.Product wheatProduct = new ShopCatalog.Product("wheat", Material.WHEAT, 20L, 10L, null, null, 0);
        ShopCatalog.Product diamondProduct = new ShopCatalog.Product("diamond", Material.DIAMOND, 200L, 100L, null, null, 0);

        when(catalog.product(Material.WHEAT)).thenReturn(wheatProduct);
        when(catalog.product(Material.DIAMOND)).thenReturn(diamondProduct);

        Inventory inventory = mock(Inventory.class);
        ItemStack wheatStack = mockItemStack(Material.WHEAT, 30);
        ItemStack diamondStack = mockItemStack(Material.DIAMOND, 5);
        ItemStack[] contents = new ItemStack[]{wheatStack, diamondStack, null};
        when(inventory.getStorageContents()).thenReturn(contents);

        SellChestService.ValuationResult result = service.evaluateAndPurge(inventory);

        assertEquals(800L, result.totalValue()); // 30*10 + 5*100 = 300 + 500 = 800
        assertEquals(35, result.itemsSold());
        assertEquals(30, result.soldCounts().get(Material.WHEAT));
        assertEquals(5, result.soldCounts().get(Material.DIAMOND));
        assertNull(contents[0]);
        assertNull(contents[1]);
        verify(inventory).setStorageContents(contents);
    }

    @Test
    void testEvaluateAndPurgeIgnoresUnsellableItems() {
        // Bedrock is not in catalog or has sell = 0
        ShopCatalog.Product bedrockProduct = new ShopCatalog.Product("bedrock", Material.BEDROCK, 500L, 0L, null, null, 0);
        when(catalog.product(Material.BEDROCK)).thenReturn(bedrockProduct);
        when(catalog.product(Material.BARRIER)).thenReturn(null);

        Inventory inventory = mock(Inventory.class);
        ItemStack bedrockStack = mockItemStack(Material.BEDROCK, 10);
        ItemStack barrierStack = mockItemStack(Material.BARRIER, 5);
        ItemStack[] contents = new ItemStack[]{bedrockStack, barrierStack};
        when(inventory.getStorageContents()).thenReturn(contents);

        SellChestService.ValuationResult result = service.evaluateAndPurge(inventory);

        assertEquals(0L, result.totalValue());
        assertEquals(0, result.itemsSold());
        assertTrue(result.soldCounts().isEmpty());
        assertNotNull(contents[0], "Unsellable items must not be purged");
        assertNotNull(contents[1], "Unsellable items must not be purged");
        verify(inventory, never()).setStorageContents(any());
    }

    @Test
    void testEvaluateAndPurgeIgnoresCustomMetaAndPdcItems() {
        ShopCatalog.Product diamondProduct = new ShopCatalog.Product("diamond", Material.DIAMOND, 200L, 100L, null, null, 0);
        when(catalog.product(Material.DIAMOND)).thenReturn(diamondProduct);

        Inventory inventory = mock(Inventory.class);
        ItemStack customDiamond = mockCustomMetaItemStack(Material.DIAMOND, 1); // Has custom meta/lore
        ItemStack[] contents = new ItemStack[]{customDiamond};
        when(inventory.getStorageContents()).thenReturn(contents);

        SellChestService.ValuationResult result = service.evaluateAndPurge(inventory);

        assertEquals(0L, result.totalValue(), "Items with custom meta must NOT be auto-sold");
        assertEquals(0, result.itemsSold());
        assertNotNull(contents[0], "Custom items must be preserved in chest");
        verify(inventory, never()).setStorageContents(any());
    }

    @Test
    void testProcessChestExecutesPayoutToLedger() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        SellChestRecord record = new SellChestRecord(
                islandId, "skyblock_world", 15, 64, 15, ownerId, System.currentTimeMillis());

        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(server.getWorld("skyblock_world")).thenReturn(world);

        Block block = mock(Block.class);
        Chest chest = mock(Chest.class);
        PersistentDataContainer chestPdc = mock(PersistentDataContainer.class);
        Inventory inventory = mock(Inventory.class);

        when(world.getBlockAt(15, 64, 15)).thenReturn(block);
        when(block.getState()).thenReturn(chest);
        when(chest.getPersistentDataContainer()).thenReturn(chestPdc);
        when(chestPdc.get(SellChestService.KEY_CUSTOM_ITEM, PersistentDataType.STRING))
                .thenReturn(SellChestService.CUSTOM_ITEM_TAG);
        when(chest.getInventory()).thenReturn(inventory);

        ShopCatalog.Product wheatProduct = new ShopCatalog.Product("wheat", Material.WHEAT, 20L, 10L, null, null, 0);
        when(catalog.product(Material.WHEAT)).thenReturn(wheatProduct);

        ItemStack wheatStack = mockItemStack(Material.WHEAT, 50);
        ItemStack[] contents = new ItemStack[]{wheatStack};
        when(inventory.getStorageContents()).thenReturn(contents);

        service.processChest(record, world);

        // Verify payout to island account key
        AccountKey expectedKey = AccountKey.island(islandId);
        verify(ledger).deposit(eq(expectedKey), eq(500L), anyString(), eq(SellChestService.REASON));
    }

    /**
     * AUTO-1. {@code evaluateAndPurge} kasuje przedmioty ze skrzyni, a stary kod
     * wołał {@code ledger.deposit(...)} jak instrukcję — zwrócony future szedł do
     * kosza bez {@code .exceptionally}. Awaria bazy = przedmioty znikają, bank bez
     * zmian, cisza w konsoli. Na starym kodzie ten test pada: nie ma ani drugiej
     * próby, ani zaplanowanego ponowienia.
     */
    @Test
    void payoutFailureIsRetriedWithTheSameTransactionId() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        SellChestRecord record = new SellChestRecord(
                islandId, "skyblock_world", 15, 64, 15, ownerId, System.currentTimeMillis());

        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(server.getWorld("skyblock_world")).thenReturn(world);

        Block block = mock(Block.class);
        Chest chest = mock(Chest.class);
        PersistentDataContainer chestPdc = mock(PersistentDataContainer.class);
        Inventory inventory = mock(Inventory.class);

        when(world.getBlockAt(15, 64, 15)).thenReturn(block);
        when(block.getState()).thenReturn(chest);
        when(chest.getPersistentDataContainer()).thenReturn(chestPdc);
        when(chestPdc.get(SellChestService.KEY_CUSTOM_ITEM, PersistentDataType.STRING))
                .thenReturn(SellChestService.CUSTOM_ITEM_TAG);
        when(chest.getInventory()).thenReturn(inventory);

        ShopCatalog.Product wheatProduct = new ShopCatalog.Product("wheat", Material.WHEAT, 20L, 10L, null, null, 0);
        when(catalog.product(Material.WHEAT)).thenReturn(wheatProduct);
        // Atrapa stosu MUSI powstać poza when(...): stubowanie w środku
        // niedokończonego when() wywala UnfinishedStubbingException.
        ItemStack wheatStack = mockItemStack(Material.WHEAT, 50);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{wheatStack});

        when(ledger.deposit(any(AccountKey.class), anyLong(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("baza offline")))
                .thenReturn(CompletableFuture.completedFuture(
                        new LedgerDao.Mutation(true, false, 500L)));

        service.processChest(record, world);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Consumer<ScheduledTask>> retry =
                org.mockito.ArgumentCaptor.forClass(Consumer.class);
        verify(asyncScheduler).runDelayed(eq(plugin), retry.capture(),
                eq(SellChestService.DEPOSIT_RETRY_SECONDS), eq(TimeUnit.SECONDS));
        retry.getValue().accept(null);

        org.mockito.ArgumentCaptor<String> transactionIds =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ledger, org.mockito.Mockito.times(2)).deposit(eq(AccountKey.island(islandId)),
                eq(500L), transactionIds.capture(), eq(SellChestService.REASON));
        assertEquals(transactionIds.getAllValues().get(0), transactionIds.getAllValues().get(1),
                "ponowienie tym samym txid — inaczej duplikat uznaje kwotę drugi raz");
    }

    @Test
    void testProcessChestNoOpWhenEmptyOrUnsellable() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        SellChestRecord record = new SellChestRecord(
                islandId, "skyblock_world", 10, 64, 10, ownerId, System.currentTimeMillis());

        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");
        when(world.isChunkLoaded(0, 0)).thenReturn(true);

        Block block = mock(Block.class);
        Chest chest = mock(Chest.class);
        PersistentDataContainer chestPdc = mock(PersistentDataContainer.class);
        Inventory inventory = mock(Inventory.class);

        when(world.getBlockAt(10, 64, 10)).thenReturn(block);
        when(block.getState()).thenReturn(chest);
        when(chest.getPersistentDataContainer()).thenReturn(chestPdc);
        when(chestPdc.get(SellChestService.KEY_CUSTOM_ITEM, PersistentDataType.STRING))
                .thenReturn(SellChestService.CUSTOM_ITEM_TAG);
        when(chest.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{null, null});

        service.processChest(record, world);

        verify(ledger, never()).deposit(any(), anyLong(), anyString(), anyString());
    }

    @Test
    void testTickAllChestsDispatchesToRegionalScheduler() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        SellChestRecord record = new SellChestRecord(
                islandId, "skyblock_world", 32, 64, 48, ownerId, System.currentTimeMillis());
        service.registerSellChest(record).join();

        World world = mock(World.class);
        when(server.getWorld("skyblock_world")).thenReturn(world);
        when(world.isChunkLoaded(2, 3)).thenReturn(true); // 32>>4 = 2, 48>>4 = 3

        service.tickAllChests();

        verify(regionScheduler).run(eq(plugin), any(Location.class), any(Consumer.class));
    }

    @Test
    void testStartAndStopTicking() {
        ScheduledTask mockTask = mock(ScheduledTask.class);
        when(asyncScheduler.runAtFixedRate(eq(plugin), any(), eq(20L), eq(20L), eq(TimeUnit.SECONDS)))
                .thenReturn(mockTask);

        service.startTicking();
        verify(asyncScheduler).runAtFixedRate(eq(plugin), any(), eq(20L), eq(20L), eq(TimeUnit.SECONDS));

        service.stopTicking();
        verify(mockTask).cancel();
    }

    @Test
    void testBlockPlaceListenerSuccess() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);

        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Block placedBlock = mock(Block.class);
        TileState tileState = mock(TileState.class);
        Chest chestState = mock(Chest.class);
        PersistentDataContainer tilePdc = mock(PersistentDataContainer.class);

        when(placedBlock.getState()).thenReturn(chestState);
        when(chestState.getPersistentDataContainer()).thenReturn(tilePdc);
        when(placedBlock.getWorld()).thenReturn(world);
        when(placedBlock.getX()).thenReturn(5);
        when(placedBlock.getY()).thenReturn(64);
        when(placedBlock.getZ()).thenReturn(5);
        Location loc = new Location(world, 5, 64, 5);
        when(placedBlock.getLocation()).thenReturn(loc);

        ItemStack sellChestItem = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(sellChestItem.getType()).thenReturn(Material.CHEST);
        when(sellChestItem.hasItemMeta()).thenReturn(true);
        when(sellChestItem.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(SellChestService.KEY_CUSTOM_ITEM, PersistentDataType.STRING)).thenReturn(SellChestService.CUSTOM_ITEM_TAG);

        UUID islandId = UUID.randomUUID();
        IslandView islandView = mock(IslandView.class);
        when(islandView.islandId()).thenReturn(islandId);
        when(skyllia.islandAt(eq(playerId), any(Location.class))).thenReturn(Optional.of(islandView));

        BlockPlaceEvent event = new BlockPlaceEvent(
                placedBlock,
                mock(BlockState.class),
                mock(Block.class),
                sellChestItem,
                player,
                true,
                EquipmentSlot.HAND
        );

        listener.onBlockPlace(event);

        assertFalse(event.isCancelled());
        assertTrue(service.hasSellChest("skyblock_world", 5, 64, 5));
        Optional<SellChestRecord> opt = service.getSellChest("skyblock_world", 5, 64, 5);
        assertTrue(opt.isPresent());
        assertEquals(islandId, opt.get().islandId());
        assertEquals(5, opt.get().x());
        assertEquals(64, opt.get().y());
        assertEquals(5, opt.get().z());
        verify(tilePdc).set(eq(SellChestService.KEY_CUSTOM_ITEM), eq(PersistentDataType.STRING), eq(SellChestService.CUSTOM_ITEM_TAG));
        verify(chestState).update();
    }

    @Test
    void testBlockPlaceListenerRejectsNonContainer() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Block placedBlock = mock(Block.class);
        BlockState nonContainerState = mock(BlockState.class); // Not a container
        when(placedBlock.getState()).thenReturn(nonContainerState);
        when(placedBlock.getWorld()).thenReturn(world);
        when(placedBlock.getX()).thenReturn(7);
        when(placedBlock.getY()).thenReturn(64);
        when(placedBlock.getZ()).thenReturn(7);

        ItemStack sellChestItem = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(sellChestItem.getType()).thenReturn(Material.CHEST);
        when(sellChestItem.hasItemMeta()).thenReturn(true);
        when(sellChestItem.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(SellChestService.KEY_CUSTOM_ITEM, PersistentDataType.STRING)).thenReturn(SellChestService.CUSTOM_ITEM_TAG);

        BlockPlaceEvent event = new BlockPlaceEvent(
                placedBlock,
                mock(BlockState.class),
                mock(Block.class),
                sellChestItem,
                player,
                true,
                EquipmentSlot.HAND
        );

        listener.onBlockPlace(event);

        assertTrue(event.isCancelled());
        assertFalse(service.hasSellChest("skyblock_world", 7, 64, 7));
    }

    @Test
    void testBlockPlaceListenerRejectsDuplicateAtLocation() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Block placedBlock = mock(Block.class);
        Chest chestState = mock(Chest.class);
        PersistentDataContainer tilePdc = mock(PersistentDataContainer.class);
        when(placedBlock.getState()).thenReturn(chestState);
        when(chestState.getPersistentDataContainer()).thenReturn(tilePdc);
        when(placedBlock.getWorld()).thenReturn(world);
        when(placedBlock.getX()).thenReturn(8);
        when(placedBlock.getY()).thenReturn(64);
        when(placedBlock.getZ()).thenReturn(8);
        when(placedBlock.getLocation()).thenReturn(new Location(world, 8, 64, 8));

        ItemStack sellChestItem = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(sellChestItem.getType()).thenReturn(Material.CHEST);
        when(sellChestItem.hasItemMeta()).thenReturn(true);
        when(sellChestItem.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(SellChestService.KEY_CUSTOM_ITEM, PersistentDataType.STRING)).thenReturn(SellChestService.CUSTOM_ITEM_TAG);

        IslandView islandView = mock(IslandView.class);
        when(islandView.islandId()).thenReturn(UUID.randomUUID());
        when(skyllia.islandAt(any(UUID.class), any(Location.class))).thenReturn(Optional.of(islandView));

        // First placement
        BlockPlaceEvent place1 = new BlockPlaceEvent(
                placedBlock, mock(BlockState.class), mock(Block.class), sellChestItem, player, true, EquipmentSlot.HAND);
        listener.onBlockPlace(place1);
        assertFalse(place1.isCancelled());
        assertTrue(service.hasSellChest("skyblock_world", 8, 64, 8));

        // Second placement at same location
        BlockPlaceEvent place2 = new BlockPlaceEvent(
                placedBlock, mock(BlockState.class), mock(Block.class), sellChestItem, player, true, EquipmentSlot.HAND);
        listener.onBlockPlace(place2);
        assertTrue(place2.isCancelled());
    }

    @Test
    void testBlockBreakListenerUnregistersAndDropsItem() {
        SellChestService spyService = spy(service);
        ItemStack droppedItem = mockItemStack(Material.CHEST, 1);
        doReturn(droppedItem).when(spyService).createSellChestItem(1);
        SellChestListener spyListener = new SellChestListener(plugin, spyService, miniMessage, skyllia);

        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Block chestBlock = mock(Block.class);
        // AUTO-3: wypłata wymaga, żeby łamany blok NAPRAWDĘ był pojemnikiem.
        Chest brokenChest = mock(Chest.class);
        when(brokenChest.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(chestBlock.getState()).thenReturn(brokenChest);
        when(chestBlock.getWorld()).thenReturn(world);
        when(chestBlock.getType()).thenReturn(Material.CHEST);
        when(chestBlock.getX()).thenReturn(12);
        when(chestBlock.getY()).thenReturn(64);
        when(chestBlock.getZ()).thenReturn(12);
        Location chestLoc = new Location(world, 12, 64, 12);
        when(chestBlock.getLocation()).thenReturn(chestLoc);

        SellChestRecord record = new SellChestRecord(
                UUID.randomUUID(), "skyblock_world", 12, 64, 12, UUID.randomUUID(), 1000L);
        spyService.registerSellChest(record).join();
        assertTrue(spyService.hasSellChest("skyblock_world", 12, 64, 12));

        BlockBreakEvent breakEvent = new BlockBreakEvent(chestBlock, player);
        spyListener.onBlockBreak(breakEvent);

        assertFalse(breakEvent.isDropItems());
        assertFalse(spyService.hasSellChest("skyblock_world", 12, 64, 12));
        verify(world).dropItemNaturally(eq(chestLoc), eq(droppedItem));
    }

    @Test
    void testBreakingRegularVanillaChestDoesNotUnregisterSellChest() {
        SellChestService spyService = spy(service);
        SellChestListener spyListener = new SellChestListener(plugin, spyService, miniMessage, skyllia);

        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        // SellChest at (0, 64, 0)
        SellChestRecord record = new SellChestRecord(
                UUID.randomUUID(), "skyblock_world", 0, 64, 0, UUID.randomUUID(), 1000L);
        spyService.registerSellChest(record).join();
        assertTrue(spyService.hasSellChest("skyblock_world", 0, 64, 0));

        // Player breaks a regular chest at (5, 64, 5)
        Block regularChest = mock(Block.class);
        BlockState state = mock(BlockState.class);
        when(regularChest.getState()).thenReturn(state);
        when(regularChest.getWorld()).thenReturn(world);
        when(regularChest.getType()).thenReturn(Material.CHEST);
        when(regularChest.getX()).thenReturn(5);
        when(regularChest.getY()).thenReturn(64);
        when(regularChest.getZ()).thenReturn(5);
        Location regLoc = new Location(world, 5, 64, 5);
        when(regularChest.getLocation()).thenReturn(regLoc);

        BlockBreakEvent breakRegular = new BlockBreakEvent(regularChest, player);
        spyListener.onBlockBreak(breakRegular);

        assertTrue(breakRegular.isDropItems(), "Regular chest break must retain default drops");
        assertTrue(spyService.hasSellChest("skyblock_world", 0, 64, 0), "Active sell chest must NOT be unregistered");
        verify(world, never()).dropItemNaturally(any(), any());
    }

    /**
     * AUTO-2. Przy pustym {@code islandAt()} stary kod zostawiał {@code islandId}
     * równy UUID-owi gracza i rejestrował skrzynię wypłacającą na konto-widmo
     * {@code island:<uuid-gracza>}, którego żaden {@code /bank} nie odczyta.
     * Na starym kodzie test pada: postawienie przechodziło.
     */
    @Test
    void placingOutsideAnIslandIsRefusedInsteadOfPayingAGhostAccount() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);

        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Block placedBlock = mock(Block.class);
        Chest chestState = mock(Chest.class);
        when(placedBlock.getState()).thenReturn(chestState);
        when(placedBlock.getWorld()).thenReturn(world);
        when(placedBlock.getX()).thenReturn(21);
        when(placedBlock.getY()).thenReturn(64);
        when(placedBlock.getZ()).thenReturn(21);
        when(placedBlock.getLocation()).thenReturn(new Location(world, 21, 64, 21));

        ItemStack sellChestItem = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(sellChestItem.getType()).thenReturn(Material.CHEST);
        when(sellChestItem.hasItemMeta()).thenReturn(true);
        when(sellChestItem.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(SellChestService.KEY_CUSTOM_ITEM, PersistentDataType.STRING))
                .thenReturn(SellChestService.CUSTOM_ITEM_TAG);

        when(skyllia.islandAt(eq(playerId), any(Location.class))).thenReturn(Optional.empty());

        BlockPlaceEvent event = new BlockPlaceEvent(placedBlock, mock(BlockState.class),
                mock(Block.class), sellChestItem, player, true, EquipmentSlot.HAND);

        listener.onBlockPlace(event);

        assertTrue(event.isCancelled(), "bez wyspy nie wolno postawić skrzyni");
        assertFalse(service.hasSellChest("skyblock_world", 21, 64, 21));
    }

    /**
     * AUTO-3. Warunek {@code recordOpt.isPresent() || hasTileMarker} nie sprawdzał,
     * czy łamany blok JEST tą skrzynią, więc na osieroconym rekordzie gracz w kółko
     * dostawał darmowy sell-chest (25 000 coins) rozbijając postawioną tam ziemię.
     * Na starym kodzie test pada: przedmiot wypadał.
     */
    @Test
    void breakingDirtOnAnOrphanedRecordOnlyUnregistersAndDropsNothing() {
        SellChestService spyService = spy(service);
        SellChestListener spyListener = new SellChestListener(plugin, spyService, miniMessage, skyllia);

        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        // Rekord został po wysadzeniu skrzyni TNT (BlockBreakEvent się nie odpalił).
        SellChestRecord orphan = new SellChestRecord(
                UUID.randomUUID(), "skyblock_world", 30, 64, 30, UUID.randomUUID(), 1000L);
        spyService.registerSellChest(orphan).join();

        // W tym miejscu leży teraz zwykła ziemia.
        Block dirt = mock(Block.class);
        when(dirt.getState()).thenReturn(mock(BlockState.class));
        when(dirt.getWorld()).thenReturn(world);
        when(dirt.getType()).thenReturn(Material.DIRT);
        when(dirt.getX()).thenReturn(30);
        when(dirt.getY()).thenReturn(64);
        when(dirt.getZ()).thenReturn(30);
        when(dirt.getLocation()).thenReturn(new Location(world, 30, 64, 30));

        BlockBreakEvent event = new BlockBreakEvent(dirt, player);
        spyListener.onBlockBreak(event);

        verify(world, never()).dropItemNaturally(any(), any());
        verify(spyService, never()).createSellChestItem(anyInt());
        assertTrue(event.isDropItems(), "ziemia ma wypaść normalnie");
        assertFalse(spyService.hasSellChest("skyblock_world", 30, 64, 30),
                "osierocony rekord musi zniknąć, żeby exploit nie był powtarzalny");
    }
}
