package org.rafalohaki.wpmecore.addons.skyblock.hub;


import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Config-owned SkyBlock hub spawn, fixed displays and exact-location launch pads. */
public final class SkyBlockHub implements Listener {

    private static final long FALL_PROTECTION_MILLIS = 12_000L;

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage;
    private final HubSettings settings;
    private final Map<HubBlock, JumpPadDefinition> pads;
    /**
     * Zostaje przy własnych mapach zamiast {@code CooldownService}, bo żadna z nich
     * nie jest bucketem o stałej długości:
     * <ul>
     *   <li>{@code launchCooldowns} — długość bierze się z wyrzutni ({@link
     *       JumpPadDefinition#cooldownMillis()}), ale wpis jest jeden na gracza, więc
     *       wszystkie platformy dzielą jedno okno. {@code getOrCreate} kluczuje po
     *       samym namespace, więc albo pierwsza platforma na zawsze narzuciłaby swoją
     *       długość, albo bucket per długość rozbiłby wspólne okno na kilka.
     *   <li>{@code fallProtection} — jednorazowy termin konsumowany przy obrażeniach
     *       od upadku, a nie limit częstotliwości.
     * </ul>
     * Oba i tak są sprzątane w {@code onQuit}, więc nie ma tu wycieku do naprawienia.
     */
    private final Map<UUID, Long> launchCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> fallProtection = new ConcurrentHashMap<>();
    private volatile Location loginSpawn;

    public SkyBlockHub(@NotNull JavaPlugin plugin, @NotNull MiniMessage miniMessage,
                @NotNull HubSettings settings) {
        this.plugin = plugin;
        this.miniMessage = miniMessage;
        this.settings = settings;
        Map<HubBlock, JumpPadDefinition> index =
                new HashMap<>();
        for (JumpPadDefinition pad : settings.jumpPads()) {
            for (HubBlock block : pad.blocks()) {
                index.put(block, pad);
            }
        }
        this.pads = Map.copyOf(index);
    }

    private final NamespacedKey hubHologramKey = new NamespacedKey("skyblock", "hub_hologram");
    private final NamespacedKey legacyHubHologramKey = new NamespacedKey("wpme", "hub_hologram");
    private final NamespacedKey leaderboardKey = new NamespacedKey("skyblock", "hub_leaderboard");

    public void start() {
        loginSpawn = spawnLocation().map(Location::clone).orElse(null);
        installWorldSpawn();
        pads.forEach(this::schedulePadInstallation);
        sweepSpawnLeaderboardDisplays();
        plugin.getLogger().info("SkyBlock hub enabled: jump_pad_blocks=" + pads.size()
                + ", spawn=" + settings.spawn().world() + " "
                + settings.spawn().x() + "," + settings.spawn().y() + ","
                + settings.spawn().z());
    }

    private void sweepSpawnLeaderboardDisplays() {
        Optional<Location> spawn = spawnLocation();
        if (spawn.isEmpty()) {
            return;
        }
        World world = spawn.get().getWorld();
        for (Location loc : List.of(
                spawn.get(),
                new Location(world, 23.4, 98.0, -3.5),
                new Location(world, 23.4, 98.0, 6.5))) {
            world.getChunkAtAsync(loc, this::sweepOrphanedDisplays);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!isSpawnWorld(event.getWorld())) {
            return;
        }
        sweepOrphanedDisplays(event.getChunk());
    }

    private void sweepOrphanedDisplays(@NotNull Chunk chunk) {
        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Display display)) {
                continue;
            }
            var container = display.getPersistentDataContainer();
            if (container.has(hubHologramKey)
                    || container.has(legacyHubHologramKey)
                    || container.has(leaderboardKey)) {
                display.remove();
                removed++;
            }
        }
        if (removed > 0) {
            plugin.getLogger().info("Wyczyszczono " + removed
                    + " osieroconych encji Display z chunku ("
                    + chunk.getX() + "," + chunk.getZ() + ") w hubie.");
        }
    }

    /** SKYBLOCK-1-8: świat huba załadowany po starcie addonu — odśwież spawn i marker świata. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        if (event.getWorld().getName().equals(settings.spawn().world())
                && loginSpawn == null) {
            loginSpawn = spawnLocation().map(Location::clone).orElse(null);
            installWorldSpawn();
            plugin.getLogger().info("Świat huba '" + settings.spawn().world()
                    + "' załadowany po starcie — loginSpawn aktywny.");
        }
    }

    public int onboardingDelayTicks() {
        return settings.onboardingDelayTicks();
    }

    public boolean openMenuOnFirstJoin() {
        return settings.openMenuOnFirstJoin();
    }

    public boolean openHelpDialogOnFirstJoin() {
        return settings.openHelpDialogOnFirstJoin();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        // Select the hub as part of the server's login placement transaction.
        // A teleport from PlayerJoinEvent races Moonrise's chunk-loader setup,
        // while a delayed teleport can swallow the player's first command or
        // close a GUI opened immediately after joining. SKYBLOCK-1-8: świat huba
        // bywa ładowany PO starcie addonu — sztywne cache z start() martwiało
        // join-spawn do restartu. Lookup jest tani (mapa światów + współrzędne),
        // a loginSpawn zostaje jako fallback, gdy świat wciąż nie istnieje.
        applyJoinSpawn(settings.teleportOnJoin(), resolveLoginSpawn(),
                event::setSpawnLocation);
    }

    /** SKYBLOCK-1-8: świeży lookup; null gdy świat jeszcze nie istnieje. */
    private @Nullable Location resolveLoginSpawn() {
        return spawnLocation().map(Location::clone).orElse(loginSpawn);
    }

    static void applyJoinSpawn(boolean enabled,
                               @Nullable Location configuredSpawn,
                               @NotNull Consumer<Location> setter) {
        if (enabled && configuredSpawn != null) {
            setter.accept(configuredSpawn.clone());
        }
    }

    public void teleportWithConfirmation(@NotNull Player player) {
        teleport(player, true);
    }

    private void teleport(Player player, boolean confirm) {
        Optional<Location> resolved = spawnLocation();
        if (resolved.isEmpty()) {
            player.sendMessage(Component.text(
                    "Spawn SkyBlock jest chwilowo niedostępny.", NamedTextColor.RED));
            return;
        }
        Location target = resolved.orElseThrow();
        CompletableFuture<Boolean> transfer;
        try {
            transfer = player.teleportAsync(target);
        } catch (RuntimeException failure) {
            completeTeleport(player, confirm, null, failure);
            return;
        }
        transfer.whenComplete((success, failure) ->
                completeTeleport(player, confirm, success, failure));
    }

    private void completeTeleport(Player player,
                                  boolean confirm,
                                  Boolean success,
                                  Throwable failure) {
        schedulePlayer(player, () -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to teleport player " + player.getUniqueId()
                                + " to the SkyBlock hub", failure);
                player.sendMessage(Component.text(
                        "Nie udało się przenieść na spawn SkyBlock.",
                        NamedTextColor.RED));
            } else if (!Boolean.TRUE.equals(success)) {
                player.sendMessage(Component.text(
                        "Teleport na spawn SkyBlock został anulowany.",
                        NamedTextColor.RED));
            } else if (confirm) {
                player.sendMessage(Component.text(
                        "Przeniesiono na spawn SkyBlock.", NamedTextColor.GREEN));
            }
        });
    }

    private Optional<Location> spawnLocation() {
        SpawnPoint spawn = settings.spawn();
        World world = plugin.getServer().getWorld(spawn.world());
        if (world == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(world, spawn.x(), spawn.y(), spawn.z(),
                spawn.yaw(), spawn.pitch()));
    }

    private void installWorldSpawn() {
        Optional<Location> resolved = spawnLocation();
        if (resolved.isEmpty()) {
            plugin.getLogger().severe("SkyBlock hub world is not loaded: "
                    + settings.spawn().world());
            return;
        }
        Location location = resolved.orElseThrow();
        plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
            if (!location.getWorld().setSpawnLocation(location)) {
                plugin.getLogger().warning("The world rejected the configured hub spawn: "
                        + location);
            }
        });
    }


private void schedulePadInstallation(HubBlock position,
                                         JumpPadDefinition pad) {
        World world = plugin.getServer().getWorld(position.world());
        if (world == null) {
            plugin.getLogger().warning("Skipping jump pad " + pad.id()
                    + ": world is not loaded: " + position.world());
            return;
        }
        Location location = new Location(world, position.x(), position.y(), position.z());
        plugin.getServer().getRegionScheduler().run(plugin, location, task -> {
            Block block = world.getBlockAt(position.x(), position.y(), position.z());
            if (block.getType() == pad.plateMaterial()) {
                return;
            }
            if (!block.getType().isAir()) {
                plugin.getLogger().warning("Jump pad " + pad.id()
                        + " did not overwrite " + block.getType() + " at " + position);
                return;
            }
            Block support = block.getRelative(0, -1, 0);
            if (!support.getType().isSolid()) {
                plugin.getLogger().warning("Jump pad " + pad.id()
                        + " has no solid support at " + position);
                return;
            }
            block.setType(pad.plateMaterial(), false);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        HubBlock key = new HubBlock(
                to.getWorld().getName(), to.getBlockX(), to.getBlockY(), to.getBlockZ());
        JumpPadDefinition pad = pads.get(key);
        if (pad == null || to.getBlock().getType() != pad.plateMaterial()) {
            return;
        }
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long nextAllowed = launchCooldowns.get(player.getUniqueId());
        if (nextAllowed != null && nextAllowed > now) {
            return;
        }
        launchCooldowns.put(player.getUniqueId(), now + pad.cooldownMillis());
        fallProtection.put(player.getUniqueId(), now + FALL_PROTECTION_MILLIS);
        player.setVelocity(new Vector(
                pad.velocityX(), pad.velocityY(), pad.velocityZ()));
        player.getWorld().playSound(player.getLocation(),
                Sound.ENTITY_BREEZE_JUMP, 0.8F, 1.15F);
        player.getWorld().spawnParticle(Particle.GUST,
                player.getLocation().add(0.0D, 0.2D, 0.0D),
                3, 0.35D, 0.05D, 0.35D, 0.02D);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodLevelChange(org.bukkit.event.entity.FoodLevelChangeEvent event) {
        if (!settings.disableHunger()) {
            return;
        }
        if (event.getEntity() instanceof Player player && isSpawnWorld(player.getWorld())) {
            event.setCancelled(true);
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (settings.invulnerable() && isSpawnWorld(player.getWorld())) {
            event.setCancelled(true);
            if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
                teleport(player, false);
            } else if (event.getCause() == EntityDamageEvent.DamageCause.FIRE
                    || event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                    || event.getCause() == EntityDamageEvent.DamageCause.LAVA) {
                player.setFireTicks(0);
            }
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        Long protectedUntil = fallProtection.remove(player.getUniqueId());
        if (protectedUntil != null && protectedUntil >= System.currentTimeMillis()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (!settings.healOnEntry()) {
            return;
        }
        Location to = event.getTo();
        if (to != null && isSpawnWorld(to.getWorld())) {
            Player player = event.getPlayer();
            schedulePlayer(player, () -> applySpawnStats(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        if (!settings.healOnEntry()) {
            return;
        }
        Player player = event.getPlayer();
        if (isSpawnWorld(player.getWorld())) {
            schedulePlayer(player, () -> applySpawnStats(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        if (!settings.healOnEntry()) {
            return;
        }
        Player player = event.getPlayer();
        if (isSpawnWorld(player.getWorld())) {
            schedulePlayer(player, () -> applySpawnStats(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        if (!settings.healOnEntry()) {
            return;
        }
        Location respawnLoc = event.getRespawnLocation();
        if (isSpawnWorld(respawnLoc.getWorld())) {
            Player player = event.getPlayer();
            schedulePlayer(player, () -> applySpawnStats(player));
        }
    }

    private void applySpawnStats(@NotNull Player player) {
        if (player.isOnline()) {
            var maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                player.setHealth(maxHealth.getValue());
            }
            player.setFoodLevel(20);
            player.setSaturation(20.0F);
            player.setFireTicks(0);
        }
    }

    /*
     * hub.protect-spawn. Świat spawnu jest wyłącznie hubem — wyspy graczy leżą
     * w osobnych wymiarach sky-*, więc blokada kopania i stawiania nie może tu
     * kolidować z rozgrywką ani z liczeniem celów. Wiadra są osobnym zdarzeniem
     * i najczęstszym sposobem szpecenia hubu, dlatego lecą tą samą bramką.
     */
    private boolean protectionBlocks(@NotNull Player player) {
        return settings.protectSpawn()
                && isSpawnWorld(player.getWorld())
                && !player.hasPermission("skyblockgameplay.admin");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHubBlockBreak(BlockBreakEvent event) {
        if (protectionBlocks(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHubBlockPlace(BlockPlaceEvent event) {
        if (protectionBlocks(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHubBucketEmpty(PlayerBucketEmptyEvent event) {
        if (protectionBlocks(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    boolean isSpawnWorld(@Nullable World world) {
        return world != null && world.getName().equals(settings.spawn().world());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        launchCooldowns.remove(player);
        fallProtection.remove(player);
    }

    private void schedulePlayer(Player player, Runnable action) {
        try {
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline() && plugin.isEnabled()) {
                    action.run();
                }
            }, null);
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.FINE,
                    "Hub player task was rejected for " + player.getUniqueId(), rejected);
        }
    }
}
