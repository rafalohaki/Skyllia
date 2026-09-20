package org.rafalohaki.wpmecore.addons.skyblock.automation;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.TileState;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
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

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChunkerServiceTest {

    private Plugin plugin;
    private ChunkerDao dao;
    private MiniMessage miniMessage;
    private SkylliaIntegration skyllia;
    private ChunkerService service;
    private ChunkerListener listener;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("SkyBlockGameplay");
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());

        dao = mock(ChunkerDao.class);
        when(dao.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(dao.create(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(dao.deleteByChunk(any(), anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(null));
        when(dao.deleteByLocation(any(), anyInt(), anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(null));
        when(dao.deleteByIsland(any())).thenReturn(CompletableFuture.completedFuture(null));

        miniMessage = MiniMessage.miniMessage();
        skyllia = mock(SkylliaIntegration.class);
        service = new ChunkerService(plugin, dao, miniMessage, skyllia);
        listener = new ChunkerListener(plugin, service, miniMessage, skyllia);
    }

    private ItemStack mockItemStack(Material material, int amount) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.clone()).thenReturn(stack);
        return stack;
    }

    @Test
    void testStrictPdcValidationRejectsAnvilSpoofing() {
        ItemStack itemMock = mock(ItemStack.class);
        ItemMeta metaMock = mock(ItemMeta.class);
        PersistentDataContainer pdcMock = mock(PersistentDataContainer.class);

        when(itemMock.getType()).thenReturn(Material.HOPPER);
        when(itemMock.hasItemMeta()).thenReturn(true);
        when(itemMock.getItemMeta()).thenReturn(metaMock);
        when(metaMock.getPersistentDataContainer()).thenReturn(pdcMock);

        // When PDC tag is set -> accepted
        when(pdcMock.get(ChunkerService.KEY_CUSTOM_ITEM, PersistentDataType.STRING))
                .thenReturn(ChunkerService.CUSTOM_ITEM_TAG);
        assertTrue(service.isChunkerItem(itemMock));

        // When PDC tag is missing, even with renamed anvil display name -> strictly rejected!
        when(pdcMock.get(ChunkerService.KEY_CUSTOM_ITEM, PersistentDataType.STRING)).thenReturn(null);
        when(pdcMock.get(ChunkerService.KEY_CUSTOM_ITEM_ID, PersistentDataType.STRING)).thenReturn(null);
        when(metaMock.hasDisplayName()).thenReturn(true);
        when(metaMock.displayName()).thenReturn(miniMessage.deserialize("<gold><bold>Lej Chunkowy (Chunker)</bold></gold>"));

        assertFalse(service.isChunkerItem(itemMock), "Anvil renamed item without PDC tags must be rejected");

        // When PDC tag KEY_CUSTOM_ITEM_ID is set -> accepted
        when(pdcMock.get(ChunkerService.KEY_CUSTOM_ITEM_ID, PersistentDataType.STRING))
                .thenReturn(ChunkerService.CUSTOM_ITEM_TAG);
        assertTrue(service.isChunkerItem(itemMock));

        // Null and AIR
        assertFalse(service.isChunkerItem(null));
        ItemStack airItem = mock(ItemStack.class);
        when(airItem.getType()).thenReturn(Material.AIR);
        assertFalse(service.isChunkerItem(airItem));
    }

    @Test
    void testGetChunkerOrNullZeroAllocation() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ChunkerRecord record = new ChunkerRecord(
                islandId, "skyblock_world", 5, 5, 80, 64, 80, ownerId);

        assertNull(service.getChunkerOrNull("skyblock_world", 5, 5));
        service.registerChunker(record).join();
        assertNotNull(service.getChunkerOrNull("skyblock_world", 5, 5));
        assertEquals(record, service.getChunkerOrNull("skyblock_world", 5, 5));
        assertEquals(record, service.getChunkerOrNull(new ChunkerRecord.ChunkKey("skyblock_world", 5, 5)));
        assertNull(service.getChunkerOrNull("skyblock_world", 6, 6));
    }

    @Test
    void testInitializeLoadsAllFromDao() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ChunkerRecord record = new ChunkerRecord(
                islandId, "skyblock_world", 2, 3, 32, 64, 48, ownerId);
        when(dao.loadAll()).thenReturn(CompletableFuture.completedFuture(List.of(record)));

        service.initialize().join();

        assertTrue(service.hasChunker("skyblock_world", 2, 3));
        Optional<ChunkerRecord> opt = service.getChunker("skyblock_world", 2, 3);
        assertTrue(opt.isPresent());
        assertEquals(islandId, opt.get().islandId());
        assertEquals(32, opt.get().chestX());

        Optional<ChunkerRecord> byChest = service.getChunkerByChest("skyblock_world", 32, 64, 48);
        assertTrue(byChest.isPresent());
        assertEquals(record, byChest.get());
    }

    @Test
    void testRegisterAndUnregisterChunker() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ChunkerRecord record = new ChunkerRecord(
                islandId, "skyblock_world", 0, 0, 5, 64, 5, ownerId);

        service.registerChunker(record).join();
        verify(dao).save(record);
        assertTrue(service.hasChunker("skyblock_world", 0, 0));
        assertEquals(1, service.getChunkerCountForIsland(islandId));

        service.unregisterChunker("skyblock_world", 0, 0).join();
        verify(dao).deleteByChunk("skyblock_world", 0, 0);
        assertFalse(service.hasChunker("skyblock_world", 0, 0));
        assertEquals(0, service.getChunkerCountForIsland(islandId));
    }

    @Test
    void testUnregisterByIsland() {
        UUID island1 = UUID.randomUUID();
        UUID island2 = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        ChunkerRecord r1 = new ChunkerRecord(island1, "skyblock_world", 1, 1, 16, 64, 16, owner);
        ChunkerRecord r2 = new ChunkerRecord(island1, "skyblock_world", 2, 2, 32, 64, 32, owner);
        ChunkerRecord r3 = new ChunkerRecord(island2, "skyblock_world", 3, 3, 48, 64, 48, owner);

        service.registerChunker(r1).join();
        service.registerChunker(r2).join();
        service.registerChunker(r3).join();

        assertEquals(2, service.getChunkerCountForIsland(island1));
        assertEquals(1, service.getChunkerCountForIsland(island2));

        service.unregisterByIsland(island1).join();
        verify(dao).deleteByIsland(island1);
        assertEquals(0, service.getChunkerCountForIsland(island1));
        assertEquals(1, service.getChunkerCountForIsland(island2));
        assertTrue(service.hasChunker("skyblock_world", 3, 3));
        assertFalse(service.hasChunker("skyblock_world", 1, 1));
    }

    @Test
    void testTransferToContainerSuccess() {
        World world = mock(World.class);
        Block block = mock(Block.class);
        Chest chest = mock(Chest.class);
        Inventory inventory = mock(Inventory.class);

        when(world.getBlockAt(10, 64, 10)).thenReturn(block);
        when(block.getState()).thenReturn(chest);
        when(chest.getInventory()).thenReturn(inventory);

        // Inventory accepts all items (empty leftover map)
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

        ChunkerRecord record = new ChunkerRecord(
                UUID.randomUUID(), "skyblock_world", 0, 0, 10, 64, 10, UUID.randomUUID());

        ItemStack item = mockItemStack(Material.COBBLESTONE, 32);
        ChunkerService.TransferResult result = service.transferToContainer(record, item, world);

        assertEquals(ChunkerService.TransferResult.TransferStatus.SUCCESS, result.status());
    }

    @Test
    void testTransferToContainerFull() {
        World world = mock(World.class);
        Block block = mock(Block.class);
        Chest chest = mock(Chest.class);
        Inventory inventory = mock(Inventory.class);

        when(world.getBlockAt(12, 64, 12)).thenReturn(block);
        when(block.getState()).thenReturn(chest);
        when(chest.getInventory()).thenReturn(inventory);

        ItemStack item = mockItemStack(Material.COBBLESTONE, 16);
        HashMap<Integer, ItemStack> leftover = new HashMap<>();
        leftover.put(0, item);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(leftover);

        ChunkerRecord record = new ChunkerRecord(
                UUID.randomUUID(), "skyblock_world", 0, 0, 12, 64, 12, UUID.randomUUID());

        ChunkerService.TransferResult result = service.transferToContainer(record, item, world);

        assertEquals(ChunkerService.TransferResult.TransferStatus.FULL, result.status());
        assertNotNull(result.remaining());
        assertEquals(16, result.remaining().getAmount());
    }

    @Test
    void testTransferToContainerPartial() {
        World world = mock(World.class);
        Block block = mock(Block.class);
        Chest chest = mock(Chest.class);
        Inventory inventory = mock(Inventory.class);

        when(world.getBlockAt(14, 64, 14)).thenReturn(block);
        when(block.getState()).thenReturn(chest);
        when(chest.getInventory()).thenReturn(inventory);

        ItemStack leftoverStack = mockItemStack(Material.COBBLESTONE, 6);
        HashMap<Integer, ItemStack> leftover = new HashMap<>();
        leftover.put(0, leftoverStack);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(leftover);

        ChunkerRecord record = new ChunkerRecord(
                UUID.randomUUID(), "skyblock_world", 0, 0, 14, 64, 14, UUID.randomUUID());

        ItemStack item = mockItemStack(Material.COBBLESTONE, 10);
        ChunkerService.TransferResult result = service.transferToContainer(record, item, world);

        assertEquals(ChunkerService.TransferResult.TransferStatus.PARTIAL, result.status());
        assertNotNull(result.remaining());
        assertEquals(6, result.remaining().getAmount());
    }

    @Test
    void testTransferToInvalidContainer() {
        World world = mock(World.class);
        Block block = mock(Block.class);
        BlockState state = mock(BlockState.class); // Not a Container

        when(world.getBlockAt(20, 64, 20)).thenReturn(block);
        when(block.getState()).thenReturn(state);

        ChunkerRecord record = new ChunkerRecord(
                UUID.randomUUID(), "skyblock_world", 1, 1, 20, 64, 20, UUID.randomUUID());

        ItemStack item = mockItemStack(Material.IRON_INGOT, 5);
        ChunkerService.TransferResult result = service.transferToContainer(record, item, world);

        assertEquals(ChunkerService.TransferResult.TransferStatus.INVALID_CONTAINER, result.status());
    }

    @Test
    void testBlockPlaceListenerSuccessAgainstChest() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);

        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Chunk chunk = mock(Chunk.class);
        when(chunk.getX()).thenReturn(0);
        when(chunk.getZ()).thenReturn(0);

        Block chestBlock = mock(Block.class);
        Chest chestState = mock(Chest.class);
        when(chestBlock.getState()).thenReturn(chestState);
        when(chestBlock.getX()).thenReturn(0);
        when(chestBlock.getY()).thenReturn(64);
        when(chestBlock.getZ()).thenReturn(0);

        Block hopperBlock = mock(Block.class);
        TileState hopperTileState = mock(TileState.class);
        PersistentDataContainer hopperPdc = mock(PersistentDataContainer.class);
        when(hopperTileState.getPersistentDataContainer()).thenReturn(hopperPdc);
        when(hopperBlock.getState()).thenReturn(hopperTileState);
        when(hopperBlock.getWorld()).thenReturn(world);
        when(hopperBlock.getChunk()).thenReturn(chunk);
        when(hopperBlock.getX()).thenReturn(0);
        when(hopperBlock.getY()).thenReturn(65);
        when(hopperBlock.getZ()).thenReturn(0);
        Location hopperLoc = new Location(world, 0, 65, 0);
        when(hopperBlock.getLocation()).thenReturn(hopperLoc);

        ItemStack chunkerItem = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(chunkerItem.getType()).thenReturn(Material.HOPPER);
        when(chunkerItem.hasItemMeta()).thenReturn(true);
        when(chunkerItem.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(ChunkerService.KEY_CUSTOM_ITEM, PersistentDataType.STRING)).thenReturn(ChunkerService.CUSTOM_ITEM_TAG);

        UUID islandId = UUID.randomUUID();
        IslandView view = mock(IslandView.class);
        when(view.islandId()).thenReturn(islandId);
        when(view.ownerId()).thenReturn(playerId);
        when(skyllia.islandAt(eq(playerId), any(Location.class)))
                .thenReturn(Optional.of(view));

        BlockPlaceEvent event = new BlockPlaceEvent(
                hopperBlock,
                mock(BlockState.class),
                chestBlock,
                chunkerItem,
                player,
                true,
                EquipmentSlot.HAND
        );

        listener.onBlockPlace(event);

        assertFalse(event.isCancelled());
        assertTrue(service.hasChunker("skyblock_world", 0, 0));
        Optional<ChunkerRecord> opt = service.getChunker("skyblock_world", 0, 0);
        assertTrue(opt.isPresent());
        assertEquals(0, opt.get().chestX());
        assertEquals(64, opt.get().chestY());
        assertEquals(0, opt.get().chestZ());
        assertEquals(islandId, opt.get().islandId());
        verify(hopperPdc).set(eq(ChunkerService.KEY_CUSTOM_ITEM), eq(PersistentDataType.STRING), eq(ChunkerService.CUSTOM_ITEM_TAG));
        verify(hopperTileState).update();
    }

    @Test
    void testBlockPlaceListenerRejectsCrossChunkContainer() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        // Hopper placed at (15, 65, 0) -> Chunk 0
        Block hopperBlock = mock(Block.class);
        when(hopperBlock.getWorld()).thenReturn(world);
        when(hopperBlock.getX()).thenReturn(15);
        when(hopperBlock.getY()).thenReturn(65);
        when(hopperBlock.getZ()).thenReturn(0);

        // Chest placed at (16, 64, 0) -> Chunk 1 (Cross-chunk!)
        Block chestBlock = mock(Block.class);
        Chest chestState = mock(Chest.class);
        when(chestBlock.getState()).thenReturn(chestState);
        when(chestBlock.getX()).thenReturn(16);
        when(chestBlock.getY()).thenReturn(64);
        when(chestBlock.getZ()).thenReturn(0);

        ItemStack chunkerItem = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(chunkerItem.getType()).thenReturn(Material.HOPPER);
        when(chunkerItem.hasItemMeta()).thenReturn(true);
        when(chunkerItem.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(ChunkerService.KEY_CUSTOM_ITEM, PersistentDataType.STRING)).thenReturn(ChunkerService.CUSTOM_ITEM_TAG);

        BlockPlaceEvent event = new BlockPlaceEvent(
                hopperBlock,
                mock(BlockState.class),
                chestBlock,
                chunkerItem,
                player,
                true,
                EquipmentSlot.HAND
        );

        listener.onBlockPlace(event);

        assertTrue(event.isCancelled(), "Cross-chunk placement must be rejected for Folia thread-safety");
        assertFalse(service.hasChunker("skyblock_world", 0, 0));
        assertFalse(service.hasChunker("skyblock_world", 1, 0));
    }

    @Test
    void testBlockPlaceListenerRejectsWithoutContainer() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Block stoneBlock = mock(Block.class);
        BlockState stoneState = mock(BlockState.class);
        when(stoneBlock.getState()).thenReturn(stoneState);

        Block hopperBlock = mock(Block.class);
        when(hopperBlock.getWorld()).thenReturn(world);
        when(hopperBlock.getX()).thenReturn(5);
        when(hopperBlock.getY()).thenReturn(65);
        when(hopperBlock.getZ()).thenReturn(5);
        for (BlockFace face : BlockFace.values()) {
            Block rel = mock(Block.class);
            when(rel.getState()).thenReturn(stoneState);
            when(hopperBlock.getRelative(face)).thenReturn(rel);
        }

        ItemStack chunkerItem = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(chunkerItem.getType()).thenReturn(Material.HOPPER);
        when(chunkerItem.hasItemMeta()).thenReturn(true);
        when(chunkerItem.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(ChunkerService.KEY_CUSTOM_ITEM, PersistentDataType.STRING)).thenReturn(ChunkerService.CUSTOM_ITEM_TAG);

        BlockPlaceEvent event = new BlockPlaceEvent(
                hopperBlock,
                mock(BlockState.class),
                stoneBlock,
                chunkerItem,
                player,
                true,
                EquipmentSlot.HAND
        );

        listener.onBlockPlace(event);

        assertTrue(event.isCancelled());
        assertFalse(service.hasChunker("skyblock_world", 0, 0));
    }

    @Test
    void testBlockPlaceListenerRejectsDuplicateInSameChunk() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Block chestBlock = mock(Block.class);
        Chest chestState = mock(Chest.class);
        when(chestBlock.getState()).thenReturn(chestState);
        when(chestBlock.getX()).thenReturn(0);
        when(chestBlock.getY()).thenReturn(64);
        when(chestBlock.getZ()).thenReturn(0);

        Block hopperBlock = mock(Block.class);
        when(hopperBlock.getWorld()).thenReturn(world);
        when(hopperBlock.getX()).thenReturn(0);
        when(hopperBlock.getY()).thenReturn(65);
        when(hopperBlock.getZ()).thenReturn(0);
        Location hopperLoc = new Location(world, 0, 65, 0);
        when(hopperBlock.getLocation()).thenReturn(hopperLoc);

        ItemStack chunkerItem = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(chunkerItem.getType()).thenReturn(Material.HOPPER);
        when(chunkerItem.hasItemMeta()).thenReturn(true);
        when(chunkerItem.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(ChunkerService.KEY_CUSTOM_ITEM, PersistentDataType.STRING)).thenReturn(ChunkerService.CUSTOM_ITEM_TAG);

        IslandView islandView = mock(IslandView.class);
        when(islandView.islandId()).thenReturn(UUID.randomUUID());
        when(skyllia.islandAt(any(UUID.class), any(Location.class))).thenReturn(Optional.of(islandView));

        // First place
        BlockPlaceEvent place1 = new BlockPlaceEvent(
                hopperBlock, mock(BlockState.class), chestBlock, chunkerItem, player, true, EquipmentSlot.HAND);
        listener.onBlockPlace(place1);
        assertFalse(place1.isCancelled());
        assertTrue(service.hasChunker("skyblock_world", 0, 0));

        // Second place in same chunk
        BlockPlaceEvent place2 = new BlockPlaceEvent(
                hopperBlock, mock(BlockState.class), chestBlock, chunkerItem, player, true, EquipmentSlot.HAND);
        listener.onBlockPlace(place2);
        assertTrue(place2.isCancelled());
    }

    @Test
    void testBlockBreakListenerUnregistersAndDropsItem() {
        ChunkerService spyService = spy(service);
        ItemStack droppedItem = mockItemStack(Material.HOPPER, 1);
        doReturn(droppedItem).when(spyService).createChunkerItem(1);
        ChunkerListener spyListener = new ChunkerListener(plugin, spyService, miniMessage, skyllia);

        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Block hopperBlock = mock(Block.class);
        when(hopperBlock.getWorld()).thenReturn(world);
        when(hopperBlock.getType()).thenReturn(Material.HOPPER);
        when(hopperBlock.getX()).thenReturn(0);
        when(hopperBlock.getY()).thenReturn(65);
        when(hopperBlock.getZ()).thenReturn(0);
        Location hopperLoc = new Location(world, 0, 65, 0);
        when(hopperBlock.getLocation()).thenReturn(hopperLoc);

        ChunkerRecord record = new ChunkerRecord(
                UUID.randomUUID(), "skyblock_world", 0, 0, 0, 64, 0, UUID.randomUUID());
        spyService.registerChunker(record, hopperBlock.getLocation()).join();
        assertTrue(spyService.hasChunker("skyblock_world", 0, 0));

        BlockBreakEvent breakEvent = new BlockBreakEvent(hopperBlock, player);
        spyListener.onBlockBreak(breakEvent);

        assertFalse(breakEvent.isDropItems());
        assertFalse(spyService.hasChunker("skyblock_world", 0, 0));
        verify(world).dropItemNaturally(eq(hopperLoc), eq(droppedItem));
    }

    @Test
    void testBreakingRegularVanillaHopperDoesNotUnregisterChunker() {
        ChunkerService spyService = spy(service);
        ChunkerListener spyListener = new ChunkerListener(plugin, spyService, miniMessage, skyllia);

        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        // Chunker is registered at (0, 65, 0)
        Location chunkerHopperLoc = new Location(world, 0, 65, 0);
        ChunkerRecord record = new ChunkerRecord(
                UUID.randomUUID(), "skyblock_world", 0, 0, 0, 64, 0, UUID.randomUUID());
        spyService.registerChunker(record, chunkerHopperLoc).join();
        assertTrue(spyService.hasChunker("skyblock_world", 0, 0));

        // Player breaks a different, regular vanilla hopper at (5, 65, 5) in the SAME chunk
        Block regularHopper = mock(Block.class);
        BlockState state = mock(BlockState.class); // Non-tagged tile state or regular block
        when(regularHopper.getState()).thenReturn(state);
        when(regularHopper.getWorld()).thenReturn(world);
        when(regularHopper.getType()).thenReturn(Material.HOPPER);
        when(regularHopper.getX()).thenReturn(5);
        when(regularHopper.getY()).thenReturn(65);
        when(regularHopper.getZ()).thenReturn(5);
        Location regLoc = new Location(world, 5, 65, 5);
        when(regularHopper.getLocation()).thenReturn(regLoc);

        BlockBreakEvent breakRegular = new BlockBreakEvent(regularHopper, player);
        spyListener.onBlockBreak(breakRegular);

        // Regular hopper break must NOT unregister the chunker!
        assertTrue(breakRegular.isDropItems(), "Regular hopper break must retain default drops");
        assertTrue(spyService.hasChunker("skyblock_world", 0, 0), "Active chunker must NOT be unregistered");
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    void testBlockBreakListenerWhenChestBroken() {
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Block chestBlock = mock(Block.class);
        when(chestBlock.getWorld()).thenReturn(world);
        when(chestBlock.getType()).thenReturn(Material.CHEST);
        when(chestBlock.getX()).thenReturn(0);
        when(chestBlock.getY()).thenReturn(64);
        when(chestBlock.getZ()).thenReturn(0);

        ChunkerRecord record = new ChunkerRecord(
                UUID.randomUUID(), "skyblock_world", 0, 0, 0, 64, 0, UUID.randomUUID());
        service.registerChunker(record).join();
        assertTrue(service.hasChunker("skyblock_world", 0, 0));

        BlockBreakEvent breakChest = new BlockBreakEvent(chestBlock, player);
        listener.onBlockBreak(breakChest);

        assertFalse(service.hasChunker("skyblock_world", 0, 0));
    }

    /**
     * AUTO-2. Bez wyspy stary kod zapisywał rekord z {@code islandId} równym
     * UUID-owi gracza. Na starym kodzie test pada: lej dawał się postawić.
     */
    @Test
    void placingOutsideAnIslandIsRefused() {
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerId);

        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Block chestBlock = mock(Block.class);
        when(chestBlock.getState()).thenReturn(mock(Chest.class));
        when(chestBlock.getX()).thenReturn(0);
        when(chestBlock.getY()).thenReturn(64);
        when(chestBlock.getZ()).thenReturn(0);

        Block hopperBlock = mock(Block.class);
        when(hopperBlock.getWorld()).thenReturn(world);
        when(hopperBlock.getX()).thenReturn(0);
        when(hopperBlock.getY()).thenReturn(65);
        when(hopperBlock.getZ()).thenReturn(0);
        when(hopperBlock.getLocation()).thenReturn(new Location(world, 0, 65, 0));

        ItemStack chunkerItem = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(chunkerItem.getType()).thenReturn(Material.HOPPER);
        when(chunkerItem.hasItemMeta()).thenReturn(true);
        when(chunkerItem.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(ChunkerService.KEY_CUSTOM_ITEM, PersistentDataType.STRING))
                .thenReturn(ChunkerService.CUSTOM_ITEM_TAG);

        when(skyllia.islandAt(eq(playerId), any(Location.class))).thenReturn(Optional.empty());

        BlockPlaceEvent event = new BlockPlaceEvent(hopperBlock, mock(BlockState.class),
                chestBlock, chunkerItem, player, true, EquipmentSlot.HAND);

        listener.onBlockPlace(event);

        assertTrue(event.isCancelled(), "bez wyspy nie wolno postawić leja");
        assertFalse(service.hasChunker("skyblock_world", 0, 0));
    }

    /**
     * AUTO-3. Osierocony rekord + zwykła ziemia w miejscu leja dawały darmowy
     * Lej Chunkowy (15 000 coins) co rozbicie. Na starym kodzie test pada.
     */
    @Test
    void breakingDirtOnAnOrphanedRecordOnlyUnregistersAndDropsNothing() {
        ChunkerService spyService = spy(service);
        ChunkerListener spyListener = new ChunkerListener(plugin, spyService, miniMessage, skyllia);

        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Location hopperLoc = new Location(world, 0, 65, 0);
        ChunkerRecord record = new ChunkerRecord(
                UUID.randomUUID(), "skyblock_world", 0, 0, 0, 64, 0, UUID.randomUUID());
        spyService.registerChunker(record, hopperLoc).join();

        // Lej wyleciał w powietrze; gracz postawił tam ziemię.
        Block dirt = mock(Block.class);
        when(dirt.getState()).thenReturn(mock(BlockState.class));
        when(dirt.getWorld()).thenReturn(world);
        when(dirt.getType()).thenReturn(Material.DIRT);
        when(dirt.getX()).thenReturn(0);
        when(dirt.getY()).thenReturn(65);
        when(dirt.getZ()).thenReturn(0);
        when(dirt.getLocation()).thenReturn(hopperLoc);

        BlockBreakEvent event = new BlockBreakEvent(dirt, player);
        spyListener.onBlockBreak(event);

        verify(world, never()).dropItemNaturally(any(), any());
        verify(spyService, never()).createChunkerItem(anyInt());
        assertTrue(event.isDropItems(), "ziemia ma wypaść normalnie");
        assertFalse(spyService.hasChunker("skyblock_world", 0, 0),
                "osierocony rekord musi zniknąć, żeby exploit nie był powtarzalny");
    }

    @Test
    void testItemSpawnInterceptionSuccess() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Block chestBlock = mock(Block.class);
        Chest chestState = mock(Chest.class);
        Inventory inventory = mock(Inventory.class);
        when(chestBlock.getState()).thenReturn(chestState);
        when(chestState.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>()); // Fits all
        when(world.getBlockAt(4, 64, 4)).thenReturn(chestBlock);

        ChunkerRecord record = new ChunkerRecord(
                UUID.randomUUID(), "skyblock_world", 0, 0, 4, 64, 4, UUID.randomUUID());
        service.registerChunker(record).join();

        Item itemEntity = mock(Item.class);
        Location spawnLoc = new Location(world, 5.5, 65.0, 5.5);
        when(itemEntity.getLocation()).thenReturn(spawnLoc);
        ItemStack itemStack = mockItemStack(Material.IRON_INGOT, 16);
        when(itemEntity.getItemStack()).thenReturn(itemStack);

        ItemSpawnEvent event = new ItemSpawnEvent(itemEntity);
        listener.onItemSpawn(event);

        assertTrue(event.isCancelled());
        verify(inventory).addItem(any(ItemStack.class));
    }

    @Test
    void testItemSpawnInterceptionWhenFullAllowsDrop() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Block chestBlock = mock(Block.class);
        Chest chestState = mock(Chest.class);
        Inventory inventory = mock(Inventory.class);
        when(chestBlock.getState()).thenReturn(chestState);
        when(chestState.getInventory()).thenReturn(inventory);

        ItemStack itemStack = mockItemStack(Material.IRON_INGOT, 16);
        HashMap<Integer, ItemStack> fullLeftover = new HashMap<>();
        fullLeftover.put(0, itemStack);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(fullLeftover); // Full
        when(world.getBlockAt(6, 64, 6)).thenReturn(chestBlock);

        ChunkerRecord record = new ChunkerRecord(
                UUID.randomUUID(), "skyblock_world", 0, 0, 6, 64, 6, UUID.randomUUID());
        service.registerChunker(record).join();

        Item itemEntity = mock(Item.class);
        Location spawnLoc = new Location(world, 7.5, 65.0, 7.5);
        when(itemEntity.getLocation()).thenReturn(spawnLoc);
        when(itemEntity.getItemStack()).thenReturn(itemStack);

        ItemSpawnEvent event = new ItemSpawnEvent(itemEntity);
        listener.onItemSpawn(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void testItemSpawnInChunkWithoutChunkerIgnored() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");

        Item itemEntity = mock(Item.class);
        Location spawnLoc = new Location(world, 160.0, 65.0, 160.0); // Chunk (10, 10)
        when(itemEntity.getLocation()).thenReturn(spawnLoc);
        ItemStack itemStack = mockItemStack(Material.GOLD_INGOT, 8);
        when(itemEntity.getItemStack()).thenReturn(itemStack);

        ItemSpawnEvent event = new ItemSpawnEvent(itemEntity);
        listener.onItemSpawn(event);

        assertFalse(event.isCancelled());
    }
}
