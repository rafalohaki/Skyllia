package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Materials;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Rejestr minionków + jeden globalny dyspozytor asynchroniczny (wg spec §2.1):
 * odliczanie w RAM, wykonanie cyklu delegowane na wątek regionu chunka minionka.
 * Źródłem prawdy jest SQL; stan runtime jest flushowany cyklicznie i przy stop.
 */
public class MinionService {

    public static final long FLUSH_INTERVAL_MILLIS = 30_000L;
    private static final String CUSTOM_PREFIX = "custom:";
    /** Dół + 4 boki (spec §2.3: bezpośrednio przyległe bloki). */
    private static final int[][] ADJACENT_OFFSETS = {
            {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};

    public interface CycleObserver {
        void onCycle(@NotNull MinionRecord record, boolean generated, boolean storageFull);
    }

    private static final class MinionRuntime {
        final MinionStorage storage;
        volatile MinionRecord record;
        volatile long nextCycleAtMillis;
        volatile boolean dirty;

        MinionRuntime(@NotNull MinionStorage storage, @NotNull MinionRecord record) {
            this.storage = storage;
            this.record = record;
        }

        synchronized void updateRecord(@NotNull java.util.function.UnaryOperator<MinionRecord> updater) {
            this.record = updater.apply(this.record);
            this.dirty = true;
        }
    }

    private final org.bukkit.plugin.Plugin plugin;
    private final MinionDao dao;
    private final MinionsConfig config;
    private final CustomItemService customItems;
    private final Map<UUID, MinionRuntime> byId = new ConcurrentHashMap<>();
    private volatile ScheduledTask tickingTask;
    private volatile CycleObserver cycleObserver;
    private volatile long lastFlushAt = System.currentTimeMillis();

    public MinionService(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull MinionDao dao,
                         @NotNull MinionsConfig config, @Nullable CustomItemService customItems) {
        this.plugin = plugin;
        this.dao = dao;
        this.config = config;
        this.customItems = customItems;
    }

    public void setCycleObserver(@Nullable CycleObserver observer) {
        this.cycleObserver = observer;
    }

    public @NotNull CompletableFuture<Void> initialize() {
        return dao.loadAll().thenAccept(records -> {
            for (MinionRecord record : records) {
                byId.put(record.minionId(), newRuntime(record));
            }
            plugin.getLogger().info("Loaded " + records.size() + " minion(s) into memory");
        }).exceptionally(err -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to load minions from database", err);
            return null;
        });
    }

    public void startTicking() {
        if (tickingTask != null) {
            return;
        }
        try {
            tickingTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                    plugin, ignored -> tickAll(),
                    config.settings().tickIntervalSeconds(),
                    config.settings().tickIntervalSeconds(), TimeUnit.SECONDS);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Could not start minion ticking task", ex);
        }
    }

    public void stopTicking() {
        ScheduledTask current = tickingTask;
        tickingTask = null;
        if (current != null) {
            try {
                current.cancel();
            } catch (Exception ignored) {
            }
        }
        for (MinionRuntime runtime : byId.values()) {
            dao.save(currentRecord(runtime)).exceptionally(err -> {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to persist minion on shutdown", err);
                return null;
            });
        }
    }

    void tickAll() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, MinionRuntime> entry : byId.entrySet()) {
            MinionRuntime runtime = entry.getValue();
            if (now < runtime.nextCycleAtMillis) {
                continue;
            }
            long interval = intervalMillis(runtime.record);
            if (interval == Long.MAX_VALUE) {
                continue; // nieznany typ/tier: minionek zaparkowany
            }
            runtime.nextCycleAtMillis = now + interval;
            Server server = plugin.getServer();
            if (server == null) {
                continue;
            }
            World world = server.getWorld(runtime.record.world());
            if (world == null) {
                continue;
            }
            Location location = new Location(world, runtime.record.x(),
                    runtime.record.y(), runtime.record.z());
            try {
                server.getRegionScheduler().run(plugin, location,
                        task -> processCycle(entry.getKey()));
            } catch (Exception ex) {
                plugin.getLogger().log(Level.FINE,
                        "Minion region dispatch failed for " + entry.getKey(), ex);
            }
        }
        if (now - lastFlushAt >= FLUSH_INTERVAL_MILLIS) {
            lastFlushAt = now;
            flushDirty();
        }
    }

    /** Wykonywane na wątku regionu chunka minionka (spec §2.1). */
    void processCycle(@NotNull UUID minionId) {
        MinionRuntime runtime = byId.get(minionId);
        if (runtime == null) {
            return;
        }
        MinionRecord record = runtime.record;
        MinionsConfig.TypeDef type = config.type(record.typeId());
        MinionsConfig.TierDef tier = config.tier(record.typeId(), record.tier());
        if (type == null || tier == null) {
            return;
        }
        Server server = plugin.getServer();
        if (server == null) {
            return;
        }
        World world = server.getWorld(record.world());
        if (world == null || !world.isChunkLoaded(record.blockX() >> 4, record.blockZ() >> 4)) {
            return;
        }

        MinionStorage storage = runtime.storage;
        String primaryKey = type.primaryItem().name();
        long generated = 0L;
        if (!storage.canAccept(primaryKey, type.primaryAmount())) {
            depositInAdjacentChest(world, record, storage);
            if (!storage.canAccept(primaryKey, type.primaryAmount())) {
                // nawet po drenażu do skrzyni bufor pełny; odśwież snapshot, żeby
                // flush nie zmartwychwstał przedmiotów już zdeponowanych w skrzyni
                runtime.updateRecord(r -> r.withStorage(storage.snapshot()));
                notifyObserver(runtime.record, false, true);
                return;
            }
        }
        generated += storage.add(primaryKey, type.primaryAmount());
        String bonusKey = rollBonus(tier);
        if (bonusKey != null) {
            generated += storage.add(bonusKey, 1L);
        }
        if (record.compactorEnabled()) {
            MinionCompactor.compact(storage, config.compactMappings());
        }
        depositInAdjacentChest(world, record, storage);
        depositInLinkedChest(server, world, record, storage);
        boolean stillFull = !storage.canAccept(primaryKey, type.primaryAmount());
        long finalGenerated = generated;
        runtime.updateRecord(r -> r.withGeneration(finalGenerated, storage.snapshot()));
        notifyObserver(runtime.record, generated > 0L, stillFull);
    }

    public @NotNull CompletableFuture<Boolean> tryPlace(@NotNull MinionRecord record) {
        return tryPlace(record, 0);
    }

    /** @param extraSlots dodatkowe sloty z perku rangi stawiającego (RankPerks). */
    public @NotNull CompletableFuture<Boolean> tryPlace(@NotNull MinionRecord record, int extraSlots) {
        int limit = config.settings().maxMinionsPerIsland() + Math.max(0, extraSlots);
        if (countForIsland(record.islandId()) >= limit) {
            return CompletableFuture.completedFuture(false);
        }
        return dao.tryInsertWithinLimit(record, limit).thenApply(inserted -> {
            if (!inserted) {
                return false;
            }
            byId.put(record.minionId(), newRuntime(record));
            return true;
        });
    }

    public boolean setTier(@NotNull UUID minionId, int tier) {
        MinionRuntime runtime = byId.get(minionId);
        MinionsConfig.TierDef tierDef = runtime == null ? null : config.tier(runtime.record.typeId(), tier);
        if (tierDef == null) {
            return false;
        }
        runtime.storage.expandSlots(tierDef.storageSlots());
        runtime.updateRecord(r -> r.withTier(tier));
        return true;
    }

    public boolean setCompactorEnabled(@NotNull UUID minionId, boolean enabled) {
        MinionRuntime runtime = byId.get(minionId);
        if (runtime == null) {
            return false;
        }
        runtime.updateRecord(r -> r.withCompactor(enabled));
        return true;
    }

    public boolean activateFuel(@NotNull UUID minionId, @NotNull MinionsConfig.FuelDef fuel) {
        MinionRuntime runtime = byId.get(minionId);
        if (runtime == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        long base = fuel.id().equals(runtime.record.fuelType()) && runtime.record.fuelExpiresAt() > now
                ? runtime.record.fuelExpiresAt() : now;
        runtime.updateRecord(r -> r.withFuel(fuel.id(), base + fuel.durationSeconds() * 1000L));
        runtime.nextCycleAtMillis = Math.min(runtime.nextCycleAtMillis,
                now + intervalMillis(runtime.record));
        return true;
    }

    public @NotNull Optional<MinionRecord> snapshot(@NotNull UUID minionId) {
        MinionRuntime runtime = byId.get(minionId);
        return runtime == null ? Optional.empty() : Optional.of(currentRecord(runtime));
    }

    public @Nullable MinionStorage storage(@NotNull UUID minionId) {
        MinionRuntime runtime = byId.get(minionId);
        return runtime == null ? null : runtime.storage;
    }

    public void markDirty(@NotNull UUID minionId) {
        MinionRuntime runtime = byId.get(minionId);
        if (runtime != null) {
            runtime.dirty = true;
        }
    }

    public @NotNull List<MinionRecord> byIsland(@NotNull UUID islandId) {
        List<MinionRecord> found = new ArrayList<>();
        for (MinionRuntime runtime : byId.values()) {
            if (runtime.record.islandId().equals(islandId)) {
                found.add(currentRecord(runtime));
            }
        }
        return found;
    }

    public @NotNull List<MinionRecord> minionsInChunk(@NotNull String world, int chunkX, int chunkZ) {
        List<MinionRecord> found = new ArrayList<>();
        for (MinionRuntime runtime : byId.values()) {
            MinionRecord record = runtime.record;
            if (record.world().equals(world)
                    && record.blockX() >> 4 == chunkX && record.blockZ() >> 4 == chunkZ) {
                found.add(record);
            }
        }
        return found;
    }

    public int countForIsland(@NotNull UUID islandId) {
        int count = 0;
        for (MinionRuntime runtime : byId.values()) {
            if (runtime.record.islandId().equals(islandId)) {
                count++;
            }
        }
        return count;
    }

    public @NotNull Optional<MinionRecord> pickUp(@NotNull UUID minionId) {
        MinionRuntime runtime = byId.remove(minionId);
        if (runtime == null) {
            return Optional.empty();
        }
        dao.deleteById(minionId).exceptionally(err -> {
            plugin.getLogger().log(Level.WARNING, "Failed to delete minion " + minionId, err);
            return null;
        });
        return Optional.of(runtime.record.withStorage(runtime.storage.snapshot()));
    }

    public @NotNull List<MinionRecord> unregisterByIsland(@NotNull UUID islandId) {
        List<MinionRecord> removed = new ArrayList<>();
        byId.entrySet().removeIf(entry -> {
            if (entry.getValue().record.islandId().equals(islandId)) {
                removed.add(currentRecord(entry.getValue()));
                return true;
            }
            return false;
        });
        dao.deleteByIsland(islandId).exceptionally(err -> {
            plugin.getLogger().log(Level.WARNING, "Failed to delete minions of island " + islandId, err);
            return null;
        });
        return removed;
    }

    public void flushDirty() {
        for (MinionRuntime runtime : byId.values()) {
            if (!runtime.dirty) {
                continue;
            }
            runtime.dirty = false;
            dao.save(currentRecord(runtime)).exceptionally(err -> {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to persist minion " + runtime.record.minionId(), err);
                runtime.dirty = true;
                return null;
            });
        }
    }

    private @NotNull MinionRuntime newRuntime(@NotNull MinionRecord record) {
        MinionsConfig.TierDef tier = config.tier(record.typeId(), record.tier());
        int slots = tier != null ? tier.storageSlots()
                : Math.max(1, config.settings().defaultStorageSlots());
        MinionRuntime runtime = new MinionRuntime(
                MinionStorage.restore(slots, record.storage()), record);
        runtime.nextCycleAtMillis = System.currentTimeMillis() + intervalMillis(record);
        return runtime;
    }

    private @NotNull MinionRecord currentRecord(@NotNull MinionRuntime runtime) {
        Map<String, Long> snapshotStorage = runtime.storage.snapshot();
        MinionRecord record = runtime.record;
        return record.storage().equals(snapshotStorage)
                ? record
                : record.withStorage(snapshotStorage);
    }

    private long intervalMillis(@NotNull MinionRecord record) {
        MinionsConfig.TierDef tier = config.tier(record.typeId(), record.tier());
        if (tier == null) {
            return Long.MAX_VALUE;
        }
        MinionsConfig.FuelDef fuel = record.fuelType() == null
                ? null : config.fuels().get(record.fuelType());
        return MinionFuel.effectiveIntervalMillis(tier.intervalSeconds(),
                MinionFuel.multiplier(fuel, record.fuelExpiresAt(), System.currentTimeMillis()));
    }

    private @Nullable String rollBonus(@NotNull MinionsConfig.TierDef tier) {
        String bonusDrop = tier.bonusDrop();
        if (bonusDrop == null || tier.bonusChancePercent() <= 0.0) {
            return null;
        }
        return ThreadLocalRandom.current().nextDouble(100.0) < tier.bonusChancePercent()
                ? CUSTOM_PREFIX + bonusDrop : null;
    }

    private void depositInAdjacentChest(@NotNull World world, @NotNull MinionRecord record,
                                        @NotNull MinionStorage storage) {
        int bx = record.blockX();
        int by = record.blockY();
        int bz = record.blockZ();
        for (int[] offset : ADJACENT_OFFSETS) {
            Block block = world.getBlockAt(bx + offset[0], by + offset[1], bz + offset[2]);
            if (block.getState() instanceof Container container) {
                depositInto(container.getInventory(), storage);
                return;
            }
        }
    }

    /** Skrzynia linked przez współrzędne — zawsze przez RegionScheduler (spec §2.3). */
    private void depositInLinkedChest(@NotNull Server server, @NotNull World world,
                                      @NotNull MinionRecord record, @NotNull MinionStorage storage) {
        if (record.linkedChestX() == null || record.linkedChestY() == null || record.linkedChestZ() == null) {
            return;
        }
        Location chestLocation = new Location(world, record.linkedChestX(),
                record.linkedChestY(), record.linkedChestZ());
        if (!world.isChunkLoaded(chestLocation.getBlockX() >> 4, chestLocation.getBlockZ() >> 4)) {
            return;
        }
        server.getRegionScheduler().execute(plugin, chestLocation, () -> {
            if (chestLocation.getBlock().getState() instanceof Container container) {
                // MinionStorage jest synchronized: bezpieczne z wątku obcego regionu
                depositInto(container.getInventory(), storage);
            }
        });
    }

    private void depositInto(@NotNull Inventory inventory, @NotNull MinionStorage storage) {
        for (Map.Entry<String, Long> entry : storage.snapshot().entrySet()) {
            long deposited = depositStack(inventory, entry.getKey(), entry.getValue());
            if (deposited > 0L) {
                storage.remove(entry.getKey(), deposited);
            }
        }
    }

    private long depositStack(@NotNull Inventory inventory, @NotNull String key, long count) {
        long totalDeposited = 0L;
        while (count > 0L) {
            int batchSize = (int) Math.min(count, 64L);
            ItemStack stack = buildStack(key, batchSize);
            if (stack == null) {
                return totalDeposited;
            }
            var leftover = inventory.addItem(stack);
            int leftoverCount = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            int accepted = batchSize - leftoverCount;
            if (accepted <= 0) {
                break;
            }
            totalDeposited += accepted;
            count -= accepted;
        }
        return totalDeposited;
    }

    /** Pakietowa widoczność: testy podmieniają konstrukcję ItemStack (wymaga rejestru serwera). */
    @Nullable ItemStack buildStack(@NotNull String key, int amount) {
        if (key.startsWith(CUSTOM_PREFIX)) {
            if (customItems == null) {
                return null;
            }
            Optional<ItemStack> stack = customItems.create(key.substring(CUSTOM_PREFIX.length()));
            if (stack.isEmpty()) {
                return null;
            }
            ItemStack item = stack.get();
            item.setAmount(amount);
            return item;
        }
        Material material = Material.matchMaterial(key);
        if (material == null || Materials.isAir(material)) {
            return null;
        }
        // konstruktor (Material, int) woła Material.getMaxDurability() → RegistryAccess;
        // wariant 1-arg + setAmount działa bez serwera (jak Ui.item)
        ItemStack item = new ItemStack(material);
        item.setAmount(amount);
        return item;
    }

    private void notifyObserver(@NotNull MinionRecord record, boolean generated, boolean storageFull) {
        CycleObserver observer = cycleObserver;
        if (observer != null) {
            try {
                observer.onCycle(record, generated, storageFull);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.FINE, "Minion cycle observer failed", ex);
            }
        }
    }

    /** Tylko testy: wymusza natychmiastową gotowość cyklu. */
    void fastForwardForTests(@NotNull UUID minionId) {
        MinionRuntime runtime = byId.get(minionId);
        if (runtime != null) {
            runtime.nextCycleAtMillis = 0L;
        }
    }
}
