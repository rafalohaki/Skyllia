package org.rafalohaki.wpmecore.addons.skyblock.minions;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinionServiceTest {

    private Plugin plugin;
    private Server server;
    private World world;
    private MinionDao dao;
    private MinionsConfig config;
    private MinionService service;
    private RegionScheduler regionScheduler;

    private MinionsConfig.TierDef tier(double intervalSeconds, String bonusDrop, double chance) {
        return new MinionsConfig.TierDef(1, intervalSeconds, 2, 0L, 0, bonusDrop, chance);
    }

    private MinionsConfig.TypeDef diamondType() {
        Map<Integer, MinionsConfig.TierDef> tiers = new HashMap<>();
        for (int t = 1; t <= 5; t++) {
            tiers.put(t, tier(t * 10.0, t >= 4 ? "skyblock:crystal/aquamarine" : null,
                    t >= 4 ? 100.0 : 0.0));
        }
        return new MinionsConfig.TypeDef("diamond", "<aqua>Minionek</aqua>", "texture",
                null, Material.DIAMOND, 1, tiers);
    }

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        server = mock(Server.class);
        world = mock(World.class);
        regionScheduler = mock(RegionScheduler.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        when(plugin.getName()).thenReturn("SkyBlockGameplay");
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        when(server.getRegionScheduler()).thenReturn(regionScheduler);
        when(server.getWorld("w")).thenReturn(world);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(asyncScheduler.runAtFixedRate(any(), any(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledTask.class));

        dao = mock(MinionDao.class);
        when(dao.tryInsertWithinLimit(any(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(true));
        when(dao.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(dao.loadAll()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(dao.deleteByIsland(any())).thenReturn(CompletableFuture.completedFuture(null));

        config = new MinionsConfig(
                new MinionsConfig.Settings(5, 1, 1, 2, 2),
                Map.of("coal", new MinionsConfig.FuelDef("coal", Material.COAL, null, 3600L, 2.0)),
                Map.of(Material.DIAMOND, Material.DIAMOND_BLOCK),
                Map.of("diamond", diamondType()));
        service = new MinionService(plugin, dao, config, null);
    }

    private MinionRecord placedMinion(UUID islandId) {
        MinionRecord record = new MinionRecord(UUID.randomUUID(), islandId, "diamond", 1,
                "w", 10.5, 64.0, 10.5, Map.of(), null, 0L, false,
                null, null, null, 0L,
                System.currentTimeMillis(), System.currentTimeMillis());
        assertTrue(service.tryPlace(record).join());
        return record;
    }

    @Test
    void tryPlaceEnforcesIslandLimitInMemory() {
        UUID islandId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            assertTrue(service.tryPlace(new MinionRecord(UUID.randomUUID(), islandId, "diamond", 1,
                    "w", i, 64, i, Map.of(), null, 0L, false, null, null, null, 0L,
                    System.currentTimeMillis(), System.currentTimeMillis())).join());
        }
        MinionRecord sixth = new MinionRecord(UUID.randomUUID(), islandId, "diamond", 1,
                "w", 99, 64, 99, Map.of(), null, 0L, false, null, null, null, 0L,
                System.currentTimeMillis(), System.currentTimeMillis());
        assertFalse(service.tryPlace(sixth).join());
    }

    @Test
    void tickAllDispatchesDueMinionToRegionThread() {
        MinionRecord record = placedMinion(UUID.randomUUID());
        service.fastForwardForTests(record.minionId()); // wymuś zaległy cykl bez snu

        service.tickAll();

        verify(regionScheduler, atLeastOnce()).run(eq(plugin), any(Location.class), any());
    }

    @Test
    void processCycleGeneratesPrimaryIntoStorage() {
        MinionRecord record = placedMinion(UUID.randomUUID());
        Block empty = mockBlockNotContainer();
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(empty);

        service.processCycle(record.minionId());

        assertEquals(1L, service.storage(record.minionId()).count("DIAMOND"));
        assertEquals(1L, service.snapshot(record.minionId()).orElseThrow().totalGenerated());
    }

    @Test
    void processCycleDepositsIntoAdjacentChest() {
        // buildStack nadpisany: realna konstrukcja ItemStack wymaga RegistryAccess,
        // który jest celowo nieobecny w domyślnym suite (wykluczenie mockbukkit)
        MinionService depositService = new MinionService(plugin, dao, config, null) {
            @Override
            ItemStack buildStack(String key, int amount) {
                ItemStack stack = mock(ItemStack.class);
                when(stack.getType()).thenReturn(Material.DIAMOND);
                when(stack.getAmount()).thenReturn(amount);
                return stack;
            }
        };
        MinionRecord record = new MinionRecord(UUID.randomUUID(), UUID.randomUUID(), "diamond", 1,
                "w", 10.5, 64.0, 10.5, Map.of(), null, 0L, false,
                null, null, null, 0L,
                System.currentTimeMillis(), System.currentTimeMillis());
        assertTrue(depositService.tryPlace(record).join());
        Inventory inventory = mock(Inventory.class);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>()); // wszystko przyjęte
        // mocki tworzone PRZED when() — zagnieżdżone when() w thenReturn psuje Mockito
        Block empty = mockBlockNotContainer();
        Block chestBlock = mockBlockContainer(inventory);
        // kolejność ma znaczenie: najpierw stub "catch-all", potem konkretny blok pod minionkiem
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(empty);
        when(world.getBlockAt(10, 63, 10)).thenReturn(chestBlock);

        depositService.processCycle(record.minionId());

        assertEquals(0L, depositService.storage(record.minionId()).count("DIAMOND"));
        verify(inventory).addItem(any(ItemStack.class));
    }

    @Test
    void processCycleSkipsGenerationWhenStorageFull() {
        MinionRecord record = placedMinion(UUID.randomUUID());
        Block empty = mockBlockNotContainer();
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(empty);
        MinionStorage storage = service.storage(record.minionId());
        storage.add("DIAMOND", 64L); // slot 1 pełny; slot 2 zajęty
        storage.add("COBBLESTONE", 1L);

        service.processCycle(record.minionId());

        assertEquals(0L, service.snapshot(record.minionId()).orElseThrow().totalGenerated());
        assertEquals(64L, storage.count("DIAMOND"));
    }

    @Test
    void compactorConvertsNineDiamondsWhenEnabled() {
        MinionRecord record = new MinionRecord(UUID.randomUUID(), UUID.randomUUID(), "diamond", 1,
                "w", 10.5, 64, 10.5, Map.of("DIAMOND", 9L), null, 0L, true,
                null, null, null, 0L,
                System.currentTimeMillis(), System.currentTimeMillis());
        assertTrue(service.tryPlace(record).join());
        Block empty = mockBlockNotContainer();
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(empty);

        service.processCycle(record.minionId());

        MinionStorage storage = service.storage(record.minionId());
        assertEquals(1L, storage.count("DIAMOND_BLOCK"));
        assertEquals(1L, storage.count("DIAMOND")); // 9 + 1 wygenerowany = 10 -> 1 blok + 1 reszta
    }

    @Test
    void unregisterByIslandRemovesRuntimesAndDeletesRows() {
        UUID islandId = UUID.randomUUID();
        MinionRecord record = placedMinion(islandId);

        List<MinionRecord> removed = service.unregisterByIsland(islandId);

        assertEquals(1, removed.size());
        assertEquals(record.minionId(), removed.getFirst().minionId());
        assertTrue(service.snapshot(record.minionId()).isEmpty());
        verify(dao).deleteByIsland(islandId);
    }

    @Test
    void activateFuelSpeedsUpNextCycle() {
        MinionRecord record = placedMinion(UUID.randomUUID());
        assertTrue(service.activateFuel(record.minionId(), config.fuels().get("coal")));
        assertTrue(service.snapshot(record.minionId()).orElseThrow().fuelExpiresAt() > System.currentTimeMillis());
        assertNotNull(record);
    }

    private Block mockBlockNotContainer() {
        Block block = mock(Block.class);
        when(block.getState()).thenReturn(mock(BlockState.class));
        return block;
    }

    private Block mockBlockContainer(Inventory inventory) {
        Block block = mock(Block.class);
        Container container = mock(Container.class);
        when(container.getInventory()).thenReturn(inventory);
        when(block.getState()).thenReturn(container);
        return block;
    }
}
