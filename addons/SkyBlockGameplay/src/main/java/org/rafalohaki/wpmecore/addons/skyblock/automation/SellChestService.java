package org.rafalohaki.wpmecore.addons.skyblock.automation;

import org.rafalohaki.wpmecore.addons.skyblock.economy.AccountKey;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;

import org.rafalohaki.wpmecore.addons.skyblock.shop.ShopCatalog;


import org.rafalohaki.wpmecore.addons.skyblock.shared.QuantityMath;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Materials;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * SellChestService — in-memory registry, periodic 20s valuation engine,
 * and automated payout processor for SkyBlock Sell Chests.
 * Thread-safe for Folia regional threads and Paper servers.
 */
public final class SellChestService {

    public static final String CUSTOM_ITEM_TAG = "sell_chest";
    public static final NamespacedKey KEY_CUSTOM_ITEM = new NamespacedKey("wpme", "custom_item");
    public static final NamespacedKey KEY_CUSTOM_ITEM_ID = new NamespacedKey("wpme", "custom_item_id");
    public static final String DISPLAY_NAME_FORMAT = "<gold><bold>Skrzynia Autosprzedaży (Sell Chest)</bold></gold>";
    public static final String REASON = "SELL_CHEST_AUTO";
    public static final long TICK_INTERVAL_SECONDS = 20L;
    /** AUTO-1: budżet ponowień wypłaty po awarii bazy (≈ 5 × 5 s). */
    static final int DEPOSIT_ATTEMPTS = 5;
    static final long DEPOSIT_RETRY_SECONDS = 5L;

    public record ValuationResult(
            long totalValue,
            int itemsSold,
            @NotNull Map<Material, Integer> soldCounts
    ) {
        public ValuationResult {
            soldCounts = Map.copyOf(soldCounts);
        }
    }

    private final Plugin plugin;
    private final SellChestDao dao;
    private final ShopCatalog catalog;
    private final LedgerService ledger;
    private final MiniMessage miniMessage;
    private final SkylliaIntegration skyllia;
    private final NamespacedKey pluginKey;

    private final Map<SellChestRecord.LocationKey, SellChestRecord> byLocation = new ConcurrentHashMap<>();
    private volatile ScheduledTask tickingTask;

    public SellChestService(@NotNull Plugin plugin,
                            @NotNull SellChestDao dao,
                            @NotNull ShopCatalog catalog,
                            @NotNull LedgerService ledger,
                            @NotNull MiniMessage miniMessage,
                            @Nullable SkylliaIntegration skyllia) {
        this.plugin = plugin;
        this.dao = dao;
        this.catalog = catalog;
        this.ledger = ledger;
        this.miniMessage = miniMessage;
        this.skyllia = skyllia;
        NamespacedKey resolvedPluginKey = null;
        try {
            resolvedPluginKey = new NamespacedKey(plugin, "custom_item");
        } catch (Exception ignored) {
            // In unit tests where plugin mock has no name
        }
        this.pluginKey = resolvedPluginKey;
    }

    public SellChestService(@NotNull Plugin plugin,
                            @NotNull SellChestDao dao,
                            @NotNull ShopCatalog catalog,
                            @NotNull LedgerService ledger,
                            @NotNull MiniMessage miniMessage) {
        this(plugin, dao, catalog, ledger, miniMessage, null);
    }

    /**
     * Loads all sell chests from SQL database into memory cache on startup.
     */
    public @NotNull CompletableFuture<Void> initialize() {
        return dao.loadAll().thenAccept(list -> {
            byLocation.clear();
            for (SellChestRecord record : list) {
                byLocation.put(record.locationKey(), record);
            }
            plugin.getLogger().info("Loaded " + list.size() + " SellChest(s) into memory");
        }).exceptionally(err -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to load SellChests from database", err);
            return null;
        });
    }

    /**
     * Starts the periodic 20-second background ticking task.
     */
    public void startTicking() {
        if (tickingTask != null) {
            return;
        }
        try {
            tickingTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                    plugin, ignored -> tickAllChests(),
                    TICK_INTERVAL_SECONDS, TICK_INTERVAL_SECONDS, TimeUnit.SECONDS
            );
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Could not start SellChest async ticking task", ex);
        }
    }

    /**
     * Stops the periodic ticking task.
     */
    public void stopTicking() {
        ScheduledTask current = tickingTask;
        tickingTask = null;
        if (current != null) {
            try {
                current.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Iterates all active sell chests and dispatches valuation to their regional threads.
     */
    public void tickAllChests() {
        if (plugin.getServer() == null) {
            return;
        }
        for (SellChestRecord record : byLocation.values()) {
            World world = plugin.getServer().getWorld(record.world());
            if (world == null) {
                continue;
            }
            int chunkX = record.x() >> 4;
            int chunkZ = record.z() >> 4;
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                continue;
            }
            Location loc = new Location(world, record.x(), record.y(), record.z());
            try {
                plugin.getServer().getRegionScheduler().run(plugin, loc, task -> {
                    processChest(record, world);
                });
            } catch (Exception ex) {
                plugin.getLogger().log(Level.FINE, "Failed to run regional sell chest tick for " + record, ex);
            }
        }
    }

    /**
     * Evaluates and purges the chest on its regional thread, executing payouts and visual feedback.
     */
    public void processChest(@NotNull SellChestRecord record) {
        if (plugin.getServer() == null) {
            return;
        }
        World world = plugin.getServer().getWorld(record.world());
        if (world != null) {
            processChest(record, world);
        }
    }

    public void processChest(@NotNull SellChestRecord record, @NotNull World world) {
        int chunkX = record.x() >> 4;
        int chunkZ = record.z() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }
        Block block = world.getBlockAt(record.x(), record.y(), record.z());
        if (!(block.getState() instanceof Container container)) {
            return;
        }
        if (block.getState() instanceof TileState tileState) {
            PersistentDataContainer pdc = tileState.getPersistentDataContainer();
            String tag = pdc.get(KEY_CUSTOM_ITEM, PersistentDataType.STRING);
            String tagId = pdc.get(KEY_CUSTOM_ITEM_ID, PersistentDataType.STRING);
            if (!CUSTOM_ITEM_TAG.equals(tag) && !CUSTOM_ITEM_TAG.equals(tagId)) {
                return;
            }
        }

        ValuationResult result = evaluateAndPurge(container.getInventory());
        if (result.totalValue() > 0) {
            AccountKey islandKey = AccountKey.island(record.islandId());
            /*
             * AUTO-1: przedmioty są już skasowane, więc wypłata nie ma prawa
             * zniknąć razem z odrzuconym future. Identyfikator transakcji liczony
             * RAZ i ponawiany bez zmian — LedgerDao.mutate jest idempotentny po
             * transaction_id (duplikat wraca jako applied=false, bez podwójnego
             * uznania), więc powtórka po niejednoznacznej awarii jest bezpieczna.
             * Kolejność „wycena → kasowanie → wypłata” zostaje: odwrócenie jej
             * otwiera okno, w którym gracz wyjmuje przedmioty po wycenie.
             */
            depositWithRetry(islandKey, result.totalValue(),
                    "sellchest:" + UUID.randomUUID(), record, 1);

            Location loc = new Location(world, record.x() + 0.5, record.y() + 1.2, record.z() + 0.5);
            showVisualFeedback(world, loc, record, result);
        }
    }

    /**
     * Ponawia wypłatę tym samym identyfikatorem transakcji. Poddanie się zostawia
     * SEVERE z kwotą, wyspą i identyfikatorem — to jedyny ślad, po którym operator
     * może wyrównać saldo ręcznie.
     */
    void depositWithRetry(@NotNull AccountKey account, long amount,
                          @NotNull String transactionId, @NotNull SellChestRecord record,
                          int attempt) {
        ledger.deposit(account, amount, transactionId, REASON)
                .whenComplete((mutation, failure) -> {
                    if (failure == null) {
                        if (mutation != null && !mutation.applied() && attempt == 1) {
                            // applied=false przy pierwszej próbie to odmowa księgi
                            // (limit salda), a nie duplikat: przedmioty przepadły.
                            plugin.getLogger().log(Level.SEVERE,
                                    "Sell chest payout refused by the ledger: " + amount
                                            + " for island " + record.islandId()
                                            + " (transaction " + transactionId + ")");
                        }
                        return;
                    }
                    if (attempt >= DEPOSIT_ATTEMPTS) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Sell chest payout LOST after " + attempt + " attempts: "
                                        + amount + " for island " + record.islandId()
                                        + " (transaction " + transactionId
                                        + "); items were already removed from the chest",
                                failure);
                        return;
                    }
                    plugin.getLogger().log(Level.WARNING,
                            "Sell chest payout attempt " + attempt + " failed for island "
                                    + record.islandId() + "; retrying", failure);
                    scheduleDepositRetry(account, amount, transactionId, record, attempt + 1);
                });
    }

    private void scheduleDepositRetry(@NotNull AccountKey account, long amount,
                                      @NotNull String transactionId,
                                      @NotNull SellChestRecord record, int nextAttempt) {
        try {
            plugin.getServer().getAsyncScheduler().runDelayed(plugin,
                    ignored -> depositWithRetry(account, amount, transactionId, record, nextAttempt),
                    DEPOSIT_RETRY_SECONDS, TimeUnit.SECONDS);
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.SEVERE,
                    "Sell chest payout LOST (retry could not be scheduled): " + amount
                            + " for island " + record.islandId()
                            + " (transaction " + transactionId + ")", rejected);
        }
    }

    /**
     * Evaluates contents of the inventory, purges sellable items, and returns valuation.
     * Pure and safe for direct unit testing.
     */
    public @NotNull ValuationResult evaluateAndPurge(@NotNull Inventory inventory) {
        ItemStack[] contents = inventory.getStorageContents();
        long grossTotal = 0L;
        int itemCount = 0;
        Map<Material, Integer> soldCounts = new EnumMap<>(Material.class);
        boolean modified = false;

        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || Materials.isAir(stack.getType()) || !QuantityMath.isPlain(stack)) {
                continue;
            }
            ShopCatalog.Product product = catalog.product(stack.getType());
            if (product == null || product.sell() <= 0L) {
                continue;
            }
            int amount = stack.getAmount();
            if (amount <= 0) {
                continue;
            }
            long unitPrice = product.sell();
            try {
                long stackValue = Math.multiplyExact(unitPrice, (long) amount);
                grossTotal = Math.addExact(grossTotal, stackValue);
                itemCount = Math.addExact(itemCount, amount);
                soldCounts.merge(stack.getType(), amount, Integer::sum);
                contents[i] = null;
                modified = true;
            } catch (ArithmeticException overflow) {
                // Ignore overflowed stack to prevent math issues
            }
        }

        if (modified) {
            inventory.setStorageContents(contents);
        }

        return new ValuationResult(grossTotal, itemCount, soldCounts);
    }

    /**
     * Shows visual and audio feedback (particles, sound, ephemeral text display, action bar).
     */
    public void showVisualFeedback(@NotNull World world, @NotNull Location loc,
                                   @NotNull SellChestRecord record, @NotNull ValuationResult result) {
        try {
            world.spawnParticle(Particle.HAPPY_VILLAGER, loc, 10, 0.25, 0.25, 0.25, 0.05);
        } catch (Throwable ignored) {
        }

        try {
            world.playSound(
                    net.kyori.adventure.sound.Sound.sound(
                            net.kyori.adventure.key.Key.key("minecraft", "entity.experience_orb.pickup"),
                            net.kyori.adventure.sound.Sound.Source.BLOCK,
                            0.6f,
                            1.2f
                    ),
                    loc.getX(), loc.getY(), loc.getZ()
            );
        } catch (Throwable ignored) {
        }

        try {
            world.spawn(loc, TextDisplay.class, display -> {
                display.text(miniMessage.deserialize("<green><bold>+" + Ui.money(result.totalValue()) + "</bold></green>")
                        .decoration(TextDecoration.ITALIC, false));
                display.setBillboard(Display.Billboard.CENTER);
                display.setDefaultBackground(false);
                display.setShadowed(true);
                display.setPersistent(false);
                display.setViewRange(1.0f);

                try {
                    display.getScheduler().runDelayed(plugin, task -> {
                        if (display.isValid()) {
                            display.remove();
                        }
                    }, null, 40L);
                } catch (Throwable fallback) {
                    try {
                        plugin.getServer().getRegionScheduler().runDelayed(plugin, loc, task -> {
                            if (display.isValid()) {
                                display.remove();
                            }
                        }, 40L);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            Component message = miniMessage.deserialize(
                    "<gold>Skrzynia Autosprzedaży: <green>+<price></green> <gray>(<amount> szt.)</gray></gold>",
                    Placeholder.unparsed("price", Ui.money(result.totalValue())),
                    Placeholder.unparsed("amount", String.valueOf(result.itemsSold()))
            ).decoration(TextDecoration.ITALIC, false);

            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(loc) <= 256.0) {
                    player.sendActionBar(message);
                }
            }

            if (plugin.getServer() != null) {
                Player owner = plugin.getServer().getPlayer(record.ownerId());
                if (owner != null && owner.isOnline() && (!owner.getWorld().equals(world)
                        || owner.getLocation().distanceSquared(loc) > 256.0)) {
                    owner.sendActionBar(message);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Creates a custom SellChest item stack with PDC markers, custom name, and lore.
     */
    public static @NotNull ItemStack createSellChestItem(@NotNull MiniMessage miniMessage, int amount) {
        ItemStack item = new ItemStack(Material.CHEST, Math.max(1, amount));
        item.editMeta(meta -> {
            Component name = miniMessage.deserialize(DISPLAY_NAME_FORMAT)
                    .decoration(TextDecoration.ITALIC, false);
            meta.displayName(name);

            List<Component> lore = List.of(
                    miniMessage.deserialize("<gray>Automatycznie sprzedaje przedmioty co 20 sekund</gray>")
                            .decoration(TextDecoration.ITALIC, false),
                    miniMessage.deserialize("<gray>według oficjalnego cennika sklepu (/sklep).</gray>")
                            .decoration(TextDecoration.ITALIC, false),
                    miniMessage.deserialize("<gray>Zyski trafiają bezpośrednio do banku wyspy.</gray>")
                            .decoration(TextDecoration.ITALIC, false),
                    miniMessage.deserialize("<dark_gray>Postaw w dowolnym miejscu na swojej wyspie.</dark_gray>")
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

    public @NotNull ItemStack createSellChestItem(int amount) {
        return createSellChestItem(miniMessage, amount);
    }

    public @NotNull ItemStack createSellChestItem() {
        return createSellChestItem(1);
    }

    /**
     * Checks whether an item is a custom SellChest item via PDC tags.
     * Strict PDC validation to prevent anvil display name spoofing.
     */
    public boolean isSellChestItem(@Nullable ItemStack item) {
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
     * Registers a new SellChest in memory cache and persists it via DAO.
     */
    public @NotNull CompletableFuture<Void> registerSellChest(@NotNull SellChestRecord record) {
        byLocation.put(record.locationKey(), record);
        return dao.save(record);
    }

    /**
     * Unregisters a SellChest from memory and database by location.
     */
    public @NotNull CompletableFuture<Void> unregisterSellChest(@NotNull String world, int x, int y, int z) {
        byLocation.remove(new SellChestRecord.LocationKey(world, x, y, z));
        return dao.deleteByLocation(world, x, y, z);
    }

    /**
     * Unregisters all SellChests belonging to an island.
     */
    public @NotNull CompletableFuture<Void> unregisterByIsland(@NotNull UUID islandId) {
        byLocation.entrySet().removeIf(e -> e.getValue().islandId().equals(islandId));
        return dao.deleteByIsland(islandId);
    }

    public @Nullable SellChestRecord getSellChestOrNull(@NotNull String world, int x, int y, int z) {
        return byLocation.get(new SellChestRecord.LocationKey(world, x, y, z));
    }

    public @Nullable SellChestRecord getSellChestOrNull(@NotNull SellChestRecord.LocationKey key) {
        return byLocation.get(key);
    }

    public @NotNull Optional<SellChestRecord> getSellChest(@NotNull String world, int x, int y, int z) {
        return Optional.ofNullable(getSellChestOrNull(world, x, y, z));
    }

    public @NotNull Optional<SellChestRecord> getSellChest(@NotNull SellChestRecord.LocationKey key) {
        return Optional.ofNullable(getSellChestOrNull(key));
    }

    public boolean hasSellChest(@NotNull String world, int x, int y, int z) {
        return byLocation.containsKey(new SellChestRecord.LocationKey(world, x, y, z));
    }

    public int getSellChestCountForIsland(@NotNull UUID islandId) {
        int count = 0;
        for (SellChestRecord record : byLocation.values()) {
            if (record.islandId().equals(islandId)) {
                count++;
            }
        }
        return count;
    }

    public @NotNull Map<SellChestRecord.LocationKey, SellChestRecord> getByLocationCache() {
        return Collections.unmodifiableMap(byLocation);
    }

    public @Nullable SkylliaIntegration getSkyllia() {
        return skyllia;
    }
}
