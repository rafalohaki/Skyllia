package org.rafalohaki.wpmecore.addons.skyblock.automation;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * ChunkerService — in-memory chunk-hopper registry and item routing engine.
 * Thread-safe for Folia regional threads and Paper servers.
 */
public final class ChunkerService {

    public static final String CUSTOM_ITEM_TAG = "chunker";
    public static final NamespacedKey KEY_CUSTOM_ITEM = new NamespacedKey("wpme", "custom_item");
    public static final NamespacedKey KEY_CUSTOM_ITEM_ID = new NamespacedKey("wpme", "custom_item_id");
    public static final String DISPLAY_NAME_FORMAT = "<gold><bold>Lej Chunkowy (Chunker)</bold></gold>";
    public static final long FAILURE_COOLDOWN_MS = 5_000L;

    public record TransferResult(@NotNull TransferStatus status, @Nullable ItemStack remaining) {
        public enum TransferStatus {
            SUCCESS,
            PARTIAL,
            FULL,
            INVALID_CONTAINER
        }
    }

    private final Plugin plugin;
    private final ChunkerDao dao;
    private final MiniMessage miniMessage;
    private final SkylliaIntegration skyllia;
    private final NamespacedKey pluginKey;

    private final Map<ChunkerRecord.ChunkKey, ChunkerRecord> byChunk = new ConcurrentHashMap<>();
    private final Map<ChunkerRecord.LocationKey, ChunkerRecord> byHopperLocation = new ConcurrentHashMap<>();
    private final Map<ChunkerRecord.LocationKey, ChunkerRecord> byChestLocation = new ConcurrentHashMap<>();
    private final Map<ChunkerRecord.ChunkKey, Long> failureCooldowns = new ConcurrentHashMap<>();

    public ChunkerService(@NotNull Plugin plugin,
                          @NotNull ChunkerDao dao,
                          @NotNull MiniMessage miniMessage,
                          @Nullable SkylliaIntegration skyllia) {
        this.plugin = plugin;
        this.dao = dao;
        this.miniMessage = miniMessage;
        this.skyllia = skyllia;
        NamespacedKey resolvedPluginKey = null;
        try {
            resolvedPluginKey = new NamespacedKey(plugin, "custom_item");
        } catch (Exception ignored) {
            // In unit test mocks where plugin is mock without proper Name
        }
        this.pluginKey = resolvedPluginKey;
    }

    public ChunkerService(@NotNull Plugin plugin,
                          @NotNull ChunkerDao dao,
                          @NotNull MiniMessage miniMessage) {
        this(plugin, dao, miniMessage, null);
    }

    /**
     * Loads all chunkers from SQL database into memory cache on startup.
     */
    public @NotNull CompletableFuture<Void> initialize() {
        return dao.loadAll().thenAccept(list -> {
            byChunk.clear();
            byChestLocation.clear();
            for (ChunkerRecord record : list) {
                byChunk.put(record.chunkKey(), record);
                byChestLocation.put(record.chestLocationKey(), record);
            }
            plugin.getLogger().info("Loaded " + list.size() + " Chunker(s) into memory");
        }).exceptionally(err -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Chunkers from database", err);
            return null;
        });
    }

    /**
     * Creates a custom Chunker item stack with PDC markers, custom name, and lore.
     */
    public static @NotNull ItemStack createChunkerItem(@NotNull MiniMessage miniMessage, int amount) {
        ItemStack item = new ItemStack(Material.HOPPER, Math.max(1, amount));
        item.editMeta(meta -> {
            Component name = miniMessage.deserialize(DISPLAY_NAME_FORMAT)
                    .decoration(TextDecoration.ITALIC, false);
            meta.displayName(name);

            List<Component> lore = List.of(
                    miniMessage.deserialize("<gray>Automatycznie zbiera upuszczone przedmioty</gray>")
                            .decoration(TextDecoration.ITALIC, false),
                    miniMessage.deserialize("<gray>z całego chunka i przenosi je do połączonej skrzyni.</gray>")
                            .decoration(TextDecoration.ITALIC, false),
                    miniMessage.deserialize("<dark_gray>Postaw na skrzyni lub obok skrzyni.</dark_gray>")
                            .decoration(TextDecoration.ITALIC, false)
            );
            meta.lore(lore);
            meta.setEnchantmentGlintOverride(true);

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(KEY_CUSTOM_ITEM, PersistentDataType.STRING, CUSTOM_ITEM_TAG);
            pdc.set(KEY_CUSTOM_ITEM_ID, PersistentDataType.STRING, CUSTOM_ITEM_TAG);
        });
        return item;
    }

    public @NotNull ItemStack createChunkerItem(int amount) {
        return createChunkerItem(miniMessage, amount);
    }

    public @NotNull ItemStack createChunkerItem() {
        return createChunkerItem(1);
    }

    /**
     * Checks whether an item is a custom Chunker item via PDC tags.
     * Strict PDC validation to prevent anvil display name spoofing.
     */
    public boolean isChunkerItem(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return false;
        }
        var meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc != null) {
            if (CUSTOM_ITEM_TAG.equals(pdc.get(KEY_CUSTOM_ITEM, PersistentDataType.STRING))
                    || CUSTOM_ITEM_TAG.equals(pdc.get(KEY_CUSTOM_ITEM_ID, PersistentDataType.STRING))) {
                return true;
            }
            return pluginKey != null && CUSTOM_ITEM_TAG.equals(pdc.get(pluginKey, PersistentDataType.STRING));
        }
        return false;
    }

    /**
     * Registers a new chunker in memory cache and persists it via DAO.
     */
    public @NotNull CompletableFuture<Void> registerChunker(@NotNull ChunkerRecord record,
                                                            @Nullable Location hopperLocation) {
        byChunk.put(record.chunkKey(), record);
        byChestLocation.put(record.chestLocationKey(), record);
        if (hopperLocation != null && hopperLocation.getWorld() != null) {
            byHopperLocation.put(new ChunkerRecord.LocationKey(
                    hopperLocation.getWorld().getName(),
                    hopperLocation.getBlockX(),
                    hopperLocation.getBlockY(),
                    hopperLocation.getBlockZ()), record);
        }
        return dao.save(record);
    }

    public @NotNull CompletableFuture<Void> registerChunker(@NotNull ChunkerRecord record) {
        return registerChunker(record, null);
    }

    /**
     * Unregisters a chunker from memory and database by chunk coordinates.
     */
    public @NotNull CompletableFuture<Void> unregisterChunker(@NotNull String world, int chunkX, int chunkZ) {
        ChunkerRecord.ChunkKey chunkKey = new ChunkerRecord.ChunkKey(world, chunkX, chunkZ);
        ChunkerRecord removed = byChunk.remove(chunkKey);
        failureCooldowns.remove(chunkKey);
        if (removed != null) {
            byChestLocation.remove(removed.chestLocationKey());
            byHopperLocation.entrySet().removeIf(e -> e.getValue().chunkKey().equals(chunkKey));
        }
        return dao.deleteByChunk(world, chunkX, chunkZ);
    }

    /**
     * Unregisters a chunker by chest location.
     */
    public @NotNull CompletableFuture<Void> unregisterByLocation(@NotNull String world, int chestX, int chestY, int chestZ) {
        ChunkerRecord.LocationKey locKey = new ChunkerRecord.LocationKey(world, chestX, chestY, chestZ);
        ChunkerRecord removed = byChestLocation.remove(locKey);
        if (removed != null) {
            byChunk.remove(removed.chunkKey());
            failureCooldowns.remove(removed.chunkKey());
            byHopperLocation.entrySet().removeIf(e -> e.getValue().chunkKey().equals(removed.chunkKey()));
            return dao.deleteByChunk(world, removed.chunkX(), removed.chunkZ());
        }
        return dao.deleteByLocation(world, chestX, chestY, chestZ);
    }

    /**
     * Unregisters all chunkers belonging to an island.
     */
    public @NotNull CompletableFuture<Void> unregisterByIsland(@NotNull UUID islandId) {
        byChunk.entrySet().removeIf(e -> {
            if (e.getValue().islandId().equals(islandId)) {
                failureCooldowns.remove(e.getKey());
                return true;
            }
            return false;
        });
        byChestLocation.entrySet().removeIf(e -> e.getValue().islandId().equals(islandId));
        byHopperLocation.entrySet().removeIf(e -> e.getValue().islandId().equals(islandId));
        return dao.deleteByIsland(islandId);
    }

    public @Nullable ChunkerRecord getChunkerOrNull(@NotNull String world, int chunkX, int chunkZ) {
        return byChunk.get(new ChunkerRecord.ChunkKey(world, chunkX, chunkZ));
    }

    public @Nullable ChunkerRecord getChunkerOrNull(@NotNull ChunkerRecord.ChunkKey chunkKey) {
        return byChunk.get(chunkKey);
    }

    public @NotNull Optional<ChunkerRecord> getChunker(@NotNull String world, int chunkX, int chunkZ) {
        return Optional.ofNullable(getChunkerOrNull(world, chunkX, chunkZ));
    }

    public @NotNull Optional<ChunkerRecord> getChunker(@NotNull ChunkerRecord.ChunkKey chunkKey) {
        return Optional.ofNullable(getChunkerOrNull(chunkKey));
    }

    public @NotNull Optional<ChunkerRecord> getChunkerByChest(@NotNull String world, int x, int y, int z) {
        return Optional.ofNullable(byChestLocation.get(new ChunkerRecord.LocationKey(world, x, y, z)));
    }

    public @NotNull Optional<ChunkerRecord> getChunkerByHopper(@NotNull String world, int x, int y, int z) {
        return Optional.ofNullable(byHopperLocation.get(new ChunkerRecord.LocationKey(world, x, y, z)));
    }

    public boolean hasChunker(@NotNull String world, int chunkX, int chunkZ) {
        return byChunk.containsKey(new ChunkerRecord.ChunkKey(world, chunkX, chunkZ));
    }

    public int getChunkerCountForIsland(@NotNull UUID islandId) {
        int count = 0;
        for (ChunkerRecord record : byChunk.values()) {
            if (record.islandId().equals(islandId)) {
                count++;
            }
        }
        return count;
    }

    public @NotNull Map<ChunkerRecord.ChunkKey, ChunkerRecord> getByChunkCache() {
        return Collections.unmodifiableMap(byChunk);
    }

    /**
     * Routes an ItemStack into the target container on the chunk's regional thread.
     */
    public @NotNull TransferResult transferToContainer(@NotNull ChunkerRecord record,
                                                       @NotNull ItemStack itemStack,
                                                       @NotNull World world) {
        Block block = world.getBlockAt(record.chestX(), record.chestY(), record.chestZ());
        if (!(block.getState() instanceof Container container)) {
            return new TransferResult(TransferResult.TransferStatus.INVALID_CONTAINER, itemStack);
        }

        Inventory inv = container.getInventory();
        HashMap<Integer, ItemStack> leftovers = inv.addItem(itemStack.clone());
        if (leftovers.isEmpty()) {
            return new TransferResult(TransferResult.TransferStatus.SUCCESS, null);
        }

        int leftoverCount = 0;
        ItemStack sample = null;
        for (ItemStack stack : leftovers.values()) {
            if (stack != null && stack.getAmount() > 0) {
                leftoverCount += stack.getAmount();
                sample = stack;
            }
        }

        if (leftoverCount >= itemStack.getAmount()) {
            return new TransferResult(TransferResult.TransferStatus.FULL, itemStack);
        }

        if (sample != null) {
            ItemStack partial = sample.clone();
            partial.setAmount(leftoverCount);
            return new TransferResult(TransferResult.TransferStatus.PARTIAL, partial);
        }

        return new TransferResult(TransferResult.TransferStatus.PARTIAL, itemStack);
    }

    /**
     * Sends failure feedback to owner with a 5-second cooldown to avoid chat spam.
     */
    public boolean tryNotifyFailure(@NotNull ChunkerRecord record, @NotNull String message) {
        long now = System.currentTimeMillis();
        Long last = failureCooldowns.get(record.chunkKey());
        if (last != null && (now - last) < FAILURE_COOLDOWN_MS) {
            return false;
        }
        failureCooldowns.put(record.chunkKey(), now);
        try {
            if (plugin.getServer() != null) {
                Player owner = plugin.getServer().getPlayer(record.ownerId());
                if (owner != null && owner.isOnline()) {
                    owner.sendMessage(miniMessage.deserialize("<red>" + message + "</red>")
                            .decoration(TextDecoration.ITALIC, false));
                }
            }
        } catch (Exception ignored) {
            // Protect against uninitialized Bukkit server in unit tests
        }
        return true;
    }

    public @Nullable SkylliaIntegration getSkyllia() {
        return skyllia;
    }
}
