package org.rafalohaki.wpmecore.addons.skyblock.minions;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinionListenerTest {

    private Plugin plugin;
    private Server server;
    private World world;
    private RegionScheduler regionScheduler;
    private MinionService service;
    private MinionDisplayRenderer renderer;
    private MinionMenu menu;
    private MinionsConfig config;
    private SkylliaIntegration skyllia;
    private MinionListener listener;
    private Player player;
    private PlayerInventory inventory;
    private UUID islandId;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        server = mock(Server.class);
        world = mock(World.class);
        when(plugin.getName()).thenReturn("SkyBlockGameplay");
        when(plugin.getServer()).thenReturn(server);
        when(server.getWorld("w")).thenReturn(world);
        regionScheduler = mock(RegionScheduler.class);
        when(server.getRegionScheduler()).thenReturn(regionScheduler);
        when(world.getName()).thenReturn("w");

        service = mock(MinionService.class);
        renderer = mock(MinionDisplayRenderer.class);
        menu = mock(MinionMenu.class);
        skyllia = mock(SkylliaIntegration.class);
        when(service.tryPlace(any(), anyInt())).thenReturn(CompletableFuture.completedFuture(true));

        Map<Integer, MinionsConfig.TierDef> tiers = Map.of(
                1, new MinionsConfig.TierDef(1, 10.0, 2, 0, 0, null, 0.0));
        config = new MinionsConfig(new MinionsConfig.Settings(5, 1, 1, 2, 2),
                Map.of(), Map.of(),
                Map.of("diamond", new MinionsConfig.TypeDef("diamond", "<aqua>M</aqua>", "tex",
                        null, Material.DIAMOND, 1, tiers)));
        listener = new MinionListener(plugin, service, renderer, menu, config,
                MiniMessage.miniMessage(), skyllia);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        doAnswer(inv -> {
            Object task = inv.getArgument(1);
            if (task instanceof Runnable runnable) {
                runnable.run();
            } else if (task instanceof Consumer<?> consumer) {
                consumer.accept(null);
            }
            return null;
        }).when(entityScheduler).run(any(), any(), any());
        when(player.getScheduler()).thenReturn(entityScheduler);
        islandId = UUID.randomUUID();
        IslandView view = new IslandView(
                new IslandSnapshot(islandId, islandId, null, null, null, 0L), IslandRole.OWNER);
        when(skyllia.islandAt(eq(player.getUniqueId()), any(Location.class)))
                .thenReturn(Optional.of(view));
        when(skyllia.islandOf(player.getUniqueId())).thenReturn(Optional.of(view));
    }

    private ItemStack minionItemStack() {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = MinionDisplayRendererTest.RecordingPdc.create();
        pdc.set(MinionItem.KEY_MINION_ITEM, PersistentDataType.STRING, MinionItem.TAG);
        pdc.set(MinionItem.KEY_MINION_TYPE, PersistentDataType.STRING, "diamond");
        pdc.set(MinionItem.KEY_MINION_TIER, PersistentDataType.INTEGER, 1);
        pdc.set(MinionItem.KEY_MINION_COMPACTOR, PersistentDataType.BYTE, (byte) 0);
        pdc.set(MinionItem.KEY_MINION_FUEL_TYPE, PersistentDataType.STRING, "");
        pdc.set(MinionItem.KEY_MINION_FUEL_EXPIRES, PersistentDataType.LONG, 0L);
        pdc.set(MinionItem.KEY_MINION_STORAGE, PersistentDataType.STRING, "");
        when(item.getType()).thenReturn(Material.PLAYER_HEAD);
        when(item.getAmount()).thenReturn(1);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(item.clone()).thenReturn(item);
        return item;
    }

    private ItemStack minionItemStack(String typeId, int tier) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = MinionDisplayRendererTest.RecordingPdc.create();
        pdc.set(MinionItem.KEY_MINION_ITEM, PersistentDataType.STRING, MinionItem.TAG);
        pdc.set(MinionItem.KEY_MINION_TYPE, PersistentDataType.STRING, typeId);
        pdc.set(MinionItem.KEY_MINION_TIER, PersistentDataType.INTEGER, tier);
        pdc.set(MinionItem.KEY_MINION_COMPACTOR, PersistentDataType.BYTE, (byte) 0);
        pdc.set(MinionItem.KEY_MINION_FUEL_TYPE, PersistentDataType.STRING, "");
        pdc.set(MinionItem.KEY_MINION_FUEL_EXPIRES, PersistentDataType.LONG, 0L);
        pdc.set(MinionItem.KEY_MINION_STORAGE, PersistentDataType.STRING, "");
        when(item.getType()).thenReturn(Material.PLAYER_HEAD);
        when(item.getAmount()).thenReturn(1);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(item.clone()).thenReturn(item);
        return item;
    }

    @Test
    void placingMinionItemRegistersRecordAndCancelsEvent() {
        Block clicked = mock(Block.class);
        Block target = placedAirBlock();
        when(clicked.getRelative(BlockFace.UP)).thenReturn(target);
        when(clicked.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        ItemStack held = minionItemStack();
        when(inventory.getItemInMainHand()).thenReturn(held);
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                held, clicked, BlockFace.UP, EquipmentSlot.HAND);

        listener.onMinionItemPlace(event);

        org.junit.jupiter.api.Assertions.assertTrue(event.isCancelled());
        verify(service).tryPlace(any(MinionRecord.class), anyInt());
    }

    /**
     * MINION-5. Przedmiot był zdejmowany dopiero w callbacku po round-tripie SQL,
     * więc przełączenie slotu hotbara (albo rozłączenie — callback „retired” był
     * {@code null}) zostawiało minionka w ekwipunku mimo rekordu w bazie.
     * Na starym kodzie test pada: ręka jest czyszczona po {@code tryPlace}, a przy
     * atrapie zwracającej niedokończony future w ogóle nie jest.
     */
    @Test
    void minionItemIsTakenBeforeTheDatabaseRoundTrip() {
        Block clicked = mock(Block.class);
        Block target = placedAirBlock();
        when(clicked.getRelative(BlockFace.UP)).thenReturn(target);
        when(clicked.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        ItemStack held = minionItemStack();
        when(inventory.getItemInMainHand()).thenReturn(held);
        // tryPlace nigdy się nie kończy: symuluje wolną bazę / rozłączenie gracza.
        when(service.tryPlace(any(), anyInt())).thenReturn(new CompletableFuture<>());

        listener.onMinionItemPlace(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                held, clicked, BlockFace.UP, EquipmentSlot.HAND));

        verify(inventory).setItemInMainHand(null);
        verify(service).tryPlace(any(MinionRecord.class), anyInt());
    }

    /**
     * MINION-5, wariant złośliwy: {@code isMinionItem} nie porównywał ani typu,
     * ani tieru, więc stawiając taniego minionka można było zjeść droższego
     * trzymanego w ręce. Na starym kodzie test pada — droższy stos był zdejmowany.
     */
    @Test
    void aDifferentMinionInHandIsNeverConsumed() {
        Block clicked = mock(Block.class);
        Block target = placedAirBlock();
        when(clicked.getRelative(BlockFace.UP)).thenReturn(target);
        when(clicked.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        // W zdarzeniu tani minionek tier 1, w ręce już inny (tier 5).
        ItemStack pricier = minionItemStack("diamond", 5);
        when(inventory.getItemInMainHand()).thenReturn(pricier);

        ItemStack cheaper = minionItemStack("diamond", 1);
        listener.onMinionItemPlace(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                cheaper, clicked, BlockFace.UP, EquipmentSlot.HAND));

        verify(inventory, never()).setItemInMainHand(any());
        verify(service, never()).tryPlace(any(MinionRecord.class), anyInt());
    }

    /**
     * MINION-5: odmowa (limit wysp) musi oddać przedmiot, skoro zdejmujemy go
     * przed zapisem.
     */
    @Test
    void refusedPlacementGivesTheMinionBack() {
        Block clicked = mock(Block.class);
        Block target = placedAirBlock();
        when(clicked.getRelative(BlockFace.UP)).thenReturn(target);
        when(clicked.getLocation()).thenReturn(new Location(world, 10, 64, 10));
        ItemStack held = minionItemStack();
        when(inventory.getItemInMainHand()).thenReturn(held);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new java.util.HashMap<>());
        when(service.tryPlace(any(), anyInt())).thenReturn(CompletableFuture.completedFuture(false));

        listener.onMinionItemPlace(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                held, clicked, BlockFace.UP, EquipmentSlot.HAND));

        verify(inventory).setItemInMainHand(null);
        verify(inventory).addItem(held);
    }

    private Block placedAirBlock() {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.AIR);
        World blockWorld = mock(World.class);
        when(blockWorld.getName()).thenReturn("w");
        when(block.getWorld()).thenReturn(blockWorld);
        when(block.getLocation()).thenReturn(new Location(blockWorld, 10, 65, 10));
        return block;
    }

    @Test
    void interactionClickOpensMenuForMemberOfOwningIsland() {
        Interaction interaction = mock(Interaction.class);
        PersistentDataContainer pdc = MinionDisplayRendererTest.RecordingPdc.create();
        UUID minionId = UUID.randomUUID();
        pdc.set(MinionDisplayRenderer.KEY_ENTITY_MINION, PersistentDataType.STRING,
                minionId.toString());
        when(interaction.getPersistentDataContainer()).thenReturn(pdc);
        MinionRecord record = new MinionRecord(minionId, islandId, "diamond", 1,
                "w", 10, 64, 10, Map.of(), null, 0L, false, null, null, null, 0L,
                System.currentTimeMillis(), System.currentTimeMillis());
        when(service.snapshot(minionId)).thenReturn(Optional.of(record));

        listener.onMinionInteract(new PlayerInteractAtEntityEvent(
                player, interaction, new org.bukkit.util.Vector(), EquipmentSlot.HAND));

        verify(menu).open(player, minionId);
    }

    @Test
    void interactionClickRejectedForForeignPlayer() {
        UUID minionId = UUID.randomUUID();
        UUID otherIsland = UUID.randomUUID();
        Interaction interaction = mock(Interaction.class);
        PersistentDataContainer pdc = MinionDisplayRendererTest.RecordingPdc.create();
        pdc.set(MinionDisplayRenderer.KEY_ENTITY_MINION, PersistentDataType.STRING,
                minionId.toString());
        when(interaction.getPersistentDataContainer()).thenReturn(pdc);
        MinionRecord record = new MinionRecord(minionId, otherIsland, "diamond", 1,
                "w", 10, 64, 10, Map.of(), null, 0L, false, null, null, null, 0L,
                System.currentTimeMillis(), System.currentTimeMillis());
        when(service.snapshot(minionId)).thenReturn(Optional.of(record));

        listener.onMinionInteract(new PlayerInteractAtEntityEvent(
                player, interaction, new org.bukkit.util.Vector(), EquipmentSlot.HAND));

        verify(menu, never()).open(any(), any());
        verify(player).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void chunkLoadRerendersMinionsInThatChunk() {
        Chunk chunk = mock(Chunk.class);
        when(chunk.getWorld()).thenReturn(world);
        when(chunk.getX()).thenReturn(0);
        when(chunk.getZ()).thenReturn(0);
        MinionRecord record = new MinionRecord(UUID.randomUUID(), islandId, "diamond", 1,
                "w", 10.5, 64, 10.5, Map.of(), null, 0L, false, null, null, null, 0L,
                System.currentTimeMillis(), System.currentTimeMillis());
        when(service.minionsInChunk("w", 0, 0)).thenReturn(List.of(record));

        listener.onChunkLoad(new ChunkLoadEvent(chunk, false));

        // regionScheduler jest mockiem — lambda renderu trzeba wykonać ręcznie
        ArgumentCaptor<Consumer<ScheduledTask>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(regionScheduler).run(eq(plugin), any(Location.class), captor.capture());
        captor.getValue().accept(mock(ScheduledTask.class));
        verify(renderer).render(record);
    }
}
