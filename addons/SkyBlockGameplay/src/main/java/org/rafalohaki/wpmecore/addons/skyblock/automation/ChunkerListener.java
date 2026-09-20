package org.rafalohaki.wpmecore.addons.skyblock.automation;

import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.Optional;
import java.util.UUID;

/**
 * ChunkerListener — event-driven chunk hopper logic without ground entity scanning.
 * Intercepts ItemSpawnEvent directly on the regional chunk thread and routes into the linked container.
 */
public final class ChunkerListener implements Listener {

    private static final BlockFace[] ADJACENT_FACES = {
            BlockFace.DOWN,
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST,
            BlockFace.UP
    };

    private final Plugin plugin;
    private final ChunkerService chunkerService;
    private final MiniMessage miniMessage;
    private final SkylliaIntegration skyllia;

    public ChunkerListener(@NotNull Plugin plugin,
                           @NotNull ChunkerService chunkerService,
                           @NotNull MiniMessage miniMessage,
                           @Nullable SkylliaIntegration skyllia) {
        this.plugin = plugin;
        this.chunkerService = chunkerService;
        this.miniMessage = miniMessage;
        this.skyllia = skyllia;
    }

    public ChunkerListener(@NotNull Plugin plugin,
                           @NotNull ChunkerService chunkerService,
                           @NotNull MiniMessage miniMessage) {
        this(plugin, chunkerService, miniMessage, null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack itemInHand = event.getItemInHand();
        if (!chunkerService.isChunkerItem(itemInHand)) {
            return;
        }

        Block placed = event.getBlockPlaced();
        Player player = event.getPlayer();

        Block targetContainer = resolveLinkedContainer(event.getBlockAgainst(), placed);
        if (targetContainer == null) {
            event.setCancelled(true);
            player.sendMessage(miniMessage.deserialize("<red>Lej Chunkowy musi zostać postawiony na skrzyni lub obok pojemnika!</red>")
                    .decoration(TextDecoration.ITALIC, false));
            return;
        }

        String world = placed.getWorld().getName();
        int chunkX = placed.getX() >> 4;
        int chunkZ = placed.getZ() >> 4;

        int containerChunkX = targetContainer.getX() >> 4;
        int containerChunkZ = targetContainer.getZ() >> 4;
        if (containerChunkX != chunkX || containerChunkZ != chunkZ) {
            event.setCancelled(true);
            player.sendMessage(miniMessage.deserialize("<red>Połączona skrzynia musi znajdować się w tym samym chunku!</red>")
                    .decoration(TextDecoration.ITALIC, false));
            return;
        }

        if (chunkerService.hasChunker(world, chunkX, chunkZ)) {
            event.setCancelled(true);
            player.sendMessage(miniMessage.deserialize("<red>W tym chunku znajduje się już aktywny Lej Chunkowy!</red>")
                    .decoration(TextDecoration.ITALIC, false));
            return;
        }

        /*
         * AUTO-2 (ten sam defekt co w SellChestListener): bez wyspy islandId
         * zostawał UUID-em gracza, a rekord leja wskazywał konto-widmo
         * island:<uuid-gracza>. Odmawiamy postawienia jak MinionListener.
         */
        UUID islandId = player.getUniqueId();
        if (skyllia != null) {
            Optional<IslandView> islandOpt = skyllia.islandAt(player.getUniqueId(), placed.getLocation());
            if (islandOpt.isEmpty()) {
                event.setCancelled(true);
                player.sendMessage(miniMessage.deserialize(
                                "<red>Lej Chunkowy można stawiać tylko na własnej wyspie.</red>")
                        .decoration(TextDecoration.ITALIC, false));
                return;
            }
            islandId = islandOpt.get().islandId();
        }

        ChunkerRecord record = new ChunkerRecord(
                islandId,
                world,
                chunkX,
                chunkZ,
                targetContainer.getX(),
                targetContainer.getY(),
                targetContainer.getZ(),
                player.getUniqueId()
        );

        // Store persistent PDC marker on the placed hopper TileState
        BlockState state = placed.getState();
        if (state instanceof TileState tileState) {
            PersistentDataContainer pdc = tileState.getPersistentDataContainer();
            pdc.set(ChunkerService.KEY_CUSTOM_ITEM, PersistentDataType.STRING, ChunkerService.CUSTOM_ITEM_TAG);
            pdc.set(ChunkerService.KEY_CUSTOM_ITEM_ID, PersistentDataType.STRING, ChunkerService.CUSTOM_ITEM_TAG);
            tileState.update();
        }

        chunkerService.registerChunker(record, placed.getLocation());
        player.sendMessage(miniMessage.deserialize("<green>Pomyślnie aktywowano Lej Chunkowy dla tego chunka!</green>")
                .decoration(TextDecoration.ITALIC, false));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String world = block.getWorld().getName();
        int blockX = block.getX();
        int blockY = block.getY();
        int blockZ = block.getZ();
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;

        // 1. Check if the broken block is explicitly tracked as a Chunker hopper in memory
        Optional<ChunkerRecord> byHopper = chunkerService.getChunkerByHopper(world, blockX, blockY, blockZ);

        // 2. Check if the broken block has the persistent PDC marker on its TileState
        boolean hasTileMarker = false;
        BlockState state = block.getState();
        if (state instanceof TileState tileState) {
            PersistentDataContainer pdc = tileState.getPersistentDataContainer();
            if (ChunkerService.CUSTOM_ITEM_TAG.equals(pdc.get(ChunkerService.KEY_CUSTOM_ITEM, PersistentDataType.STRING))
                    || ChunkerService.CUSTOM_ITEM_TAG.equals(pdc.get(ChunkerService.KEY_CUSTOM_ITEM_ID, PersistentDataType.STRING))) {
                hasTileMarker = true;
            }
        }

        if (byHopper.isPresent() || hasTileMarker) {
            ChunkerRecord record = byHopper.orElseGet(() -> chunkerService.getChunkerOrNull(world, chunkX, chunkZ));
            if (record != null) {
                chunkerService.unregisterChunker(record.world(), record.chunkX(), record.chunkZ());
            } else {
                chunkerService.unregisterChunker(world, chunkX, chunkZ);
            }
            /*
             * AUTO-3: osierocony rekord + dowolny blok postawiony w tym miejscu
             * dawał darmowy Lej Chunkowy (15 000 coins) co rozbicie. Wypłata tylko
             * wtedy, gdy łamany blok naprawdę jest lejem; inaczej sam rekord
             * znika i nic nie wypada.
             */
            if (block.getType() != Material.HOPPER) {
                return;
            }
            event.setDropItems(false);
            block.getWorld().dropItemNaturally(block.getLocation(), chunkerService.createChunkerItem(1));
            event.getPlayer().sendMessage(miniMessage.deserialize("<yellow>Zdeaktywowano i usunięto Lej Chunkowy.</yellow>")
                    .decoration(TextDecoration.ITALIC, false));
            return;
        }

        // 3. Check if the broken block is the linked container/chest
        Optional<ChunkerRecord> byChest = chunkerService.getChunkerByChest(world, blockX, blockY, blockZ);
        if (byChest.isPresent()) {
            ChunkerRecord record = byChest.get();
            chunkerService.unregisterChunker(record.world(), record.chunkX(), record.chunkZ());
            event.getPlayer().sendMessage(miniMessage.deserialize("<yellow>Zdeaktywowano Lej Chunkowy (usunięto połączoną skrzynię).</yellow>")
                    .decoration(TextDecoration.ITALIC, false));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item itemEntity = event.getEntity();
        Location loc = itemEntity.getLocation();
        if (loc.getWorld() == null) {
            return;
        }

        String world = loc.getWorld().getName();
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;

        // Zero-allocation hot path lookup
        ChunkerRecord record = chunkerService.getChunkerOrNull(world, chunkX, chunkZ);
        if (record == null) {
            return;
        }

        ItemStack item = itemEntity.getItemStack();
        if (item.getType() == Material.AIR || item.getAmount() <= 0) {
            return;
        }

        ChunkerService.TransferResult result = chunkerService.transferToContainer(record, item, loc.getWorld());
        switch (result.status()) {
            case SUCCESS -> event.setCancelled(true);
            case PARTIAL -> {
                if (result.remaining() != null) {
                    itemEntity.setItemStack(result.remaining());
                }
                chunkerService.tryNotifyFailure(record, "Skrzynia Leja Chunkowego zapełnia się!");
                event.setCancelled(false);
            }
            case FULL -> {
                chunkerService.tryNotifyFailure(record, "Skrzynia Leja Chunkowego jest pełna!");
                event.setCancelled(false);
            }
            case INVALID_CONTAINER -> {
                chunkerService.tryNotifyFailure(record, "Nie odnaleziono skrzyni połączonej z Lejem Chunkowym!");
                event.setCancelled(false);
            }
        }
    }

    private @Nullable Block resolveLinkedContainer(@Nullable Block against, @NotNull Block placed) {
        if (against != null && !against.equals(placed) && against.getState() instanceof Container) {
            return against;
        }

        for (BlockFace face : ADJACENT_FACES) {
            Block candidate = placed.getRelative(face);
            if (candidate.getState() instanceof Container && !candidate.equals(placed)) {
                return candidate;
            }
        }

        return null;
    }
}
