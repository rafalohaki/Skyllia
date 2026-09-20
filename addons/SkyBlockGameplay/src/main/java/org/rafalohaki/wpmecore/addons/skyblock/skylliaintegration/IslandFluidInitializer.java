package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.event.SkyblockCreateEvent;
import fr.euphyllia.skyllia.api.event.teleport.PlayerTeleportIslandEvent;
import fr.euphyllia.skyllia.api.skyblock.Island;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Automatyczny inicjalizator fizyki płynów, generatorów oraz startowej skrzynki z przedmiotami na wyspie.
 * <p>
 * 1. Płyny: Gdy schemat wyspy jest wklejany bez fizyki (setBlockData applyPhysics=false),
 * woda i lawa pozostają statycznymi blokami bez zaplanowanych ticków płynów.
 * Ten listener po utworzeniu wyspy lub teleportacji gracza wykonuje bezpieczny,
 * lokalny skan wokół centrum wyspy i aktywuje fizykę oraz ticki płynów (woda, lawa).
 * <p>
 * 2. Skrzynka startowa: Weryfikuje obecność skrzynki ze startowymi przedmiotami (lód, lawa,
 * nasiona, trzcina cukrowa, kaktus, grzyby, sadzonka, chleb, nici, pochodnie) i zasila ją.
 */
public final class IslandFluidInitializer implements Listener {

    private final JavaPlugin plugin;
    private final java.util.function.BiConsumer<UUID, Location> onOneBlockDetected;
    private final AtomicBoolean registered = new AtomicBoolean(true);

    public IslandFluidInitializer(@NotNull JavaPlugin plugin,
                                  @Nullable java.util.function.BiConsumer<UUID, Location> onOneBlockDetected) {
        this.plugin = plugin;
        this.onOneBlockDetected = onOneBlockDetected;
    }

    public static @NotNull IslandFluidInitializer register(@NotNull JavaPlugin plugin,
                                                            @Nullable java.util.function.BiConsumer<UUID, Location> onOneBlockDetected) {
        IslandFluidInitializer initializer = new IslandFluidInitializer(plugin, onOneBlockDetected);
        Bukkit.getPluginManager().registerEvents(initializer, plugin);
        return initializer;
    }

    public static @NotNull IslandFluidInitializer register(@NotNull JavaPlugin plugin) {
        return register(plugin, null);
    }

    public void unregister() {
        if (registered.compareAndSet(true, false)) {
            HandlerList.unregisterAll(this);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandCreate(SkyblockCreateEvent event) {
        Island island = event.getIsland();
        if (island == null) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            if (Boolean.TRUE.equals(SkylliaAPI.isWorldSkyblock(world.getName()))) {
                Location center = island.getCenterLocation(world);
                if (center != null) {
                    scheduleIslandInit(island.getId(), center, 15L);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIslandTeleport(PlayerTeleportIslandEvent event) {
        Location to = event.getTo();
        if (to != null && to.getWorld() != null) {
            scheduleIslandInit(null, to, 5L);
        }
    }

    private void scheduleIslandInit(@Nullable UUID islandId, @NotNull Location loc, long delayTicks) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        Bukkit.getRegionScheduler().runDelayed(plugin, loc, scheduledTask -> {
            initializeStarterChestIfPresent(loc);
            if (islandId != null && onOneBlockDetected != null) {
                checkOneBlockWithRetry(islandId, loc, 5);
            }
        }, delayTicks);
    }

    private void checkOneBlockWithRetry(@NotNull UUID islandId, @NotNull Location loc, int retriesLeft) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        int cx = loc.getBlockX();
        int cy = loc.getBlockY();
        int cz = loc.getBlockZ();
        if (world.getBlockAt(cx, cy, cz).getType() == Material.GRASS_BLOCK
                && world.getBlockAt(cx, cy - 1, cz).getType() == Material.BEDROCK) {
            onOneBlockDetected.accept(islandId, new Location(world, cx, cy, cz));
            return;
        }
        if (retriesLeft > 0) {
            Bukkit.getRegionScheduler().runDelayed(plugin, loc,
                    task -> checkOneBlockWithRetry(islandId, loc, retriesLeft - 1), 10L);
        }
    }

    private void initializeStarterChestIfPresent(@NotNull Location center) {
        World world = center.getWorld();
        if (world == null || world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        // 1. Sprawdź, czy skrzynka już istnieje w promieniu centrum wyspy
        for (int y = cy - 2; y <= cy + 3; y++) {
            for (int x = cx - 4; x <= cx + 4; x++) {
                for (int z = cz - 4; z <= cz + 4; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.CHEST) {
                        if (block.getState() instanceof Chest chest) {
                            Inventory inv = chest.getBlockInventory();
                            if (inv.isEmpty()) {
                                populateStarterItems(inv);
                            }
                            return;
                        }
                    }
                }
            }
        }

        // 2. Jeśli skrzynki nie znaleziono, postaw ją obok centrum na stabilnym podłożu
        Block target = world.getBlockAt(cx - 1, cy, cz);
        Block below = world.getBlockAt(cx - 1, cy - 1, cz);
        if (target.getType() == Material.AIR && below.getType().isSolid()) {
            target.setType(Material.CHEST, true);
            if (target.getState() instanceof Chest chest) {
                populateStarterItems(chest.getBlockInventory());
            }
        }
    }

    private void populateStarterItems(@NotNull Inventory inv) {
        inv.setItem(0, new ItemStack(Material.LAVA_BUCKET, 1));
        inv.setItem(1, new ItemStack(Material.LAVA_BUCKET, 1));
        inv.setItem(2, new ItemStack(Material.WATER_BUCKET, 1));
        inv.setItem(3, new ItemStack(Material.WATER_BUCKET, 1));
        inv.setItem(4, new ItemStack(Material.ICE, 2));
        inv.setItem(5, new ItemStack(Material.OAK_SAPLING, 2));
        inv.setItem(6, new ItemStack(Material.BONE_MEAL, 16));
        inv.setItem(7, new ItemStack(Material.BREAD, 16));
        inv.setItem(8, new ItemStack(Material.TORCH, 16));
        inv.setItem(9, new ItemStack(Material.SUGAR_CANE, 1));
        inv.setItem(10, new ItemStack(Material.CACTUS, 1));
        inv.setItem(11, new ItemStack(Material.PUMPKIN_SEEDS, 1));
        inv.setItem(12, new ItemStack(Material.MELON_SEEDS, 1));
        inv.setItem(13, new ItemStack(Material.RED_MUSHROOM, 1));
        inv.setItem(14, new ItemStack(Material.BROWN_MUSHROOM, 1));
        inv.setItem(15, new ItemStack(Material.STRING, 12));
        inv.setItem(16, new ItemStack(Material.IRON_PICKAXE, 1));
    }
}
