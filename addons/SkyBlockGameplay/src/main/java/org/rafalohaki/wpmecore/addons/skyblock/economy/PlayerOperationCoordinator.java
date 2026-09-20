package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One per-player serialization point for every custom economy mutation.
 * Inventory-sensitive leases additionally freeze ordinary item movement/use
 * until the durable outbox reaches a safe checkpoint.
 */
public final class PlayerOperationCoordinator implements Listener {

    private final ConcurrentHashMap<UUID, Lease> leases = new ConcurrentHashMap<>();
    private final Set<UUID> inventoryGuards = ConcurrentHashMap.newKeySet();
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean inventoryGateClosed = true;
    private volatile boolean stopped;

    @NotNull Optional<Lease> tryAcquire(@NotNull UUID playerId, boolean inventorySensitive) {
        if (stopped) {
            return Optional.empty();
        }
        Lease candidate = new Lease(playerId, sequence.incrementAndGet(), inventorySensitive);
        if (leases.putIfAbsent(playerId, candidate) != null) {
            return Optional.empty();
        }
        if (stopped) {
            leases.remove(playerId, candidate);
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    void release(@NotNull Lease lease) {
        if (!stopped) {
            leases.remove(lease.playerId(), lease);
        }
    }

    boolean owns(@NotNull Lease lease) {
        return leases.get(lease.playerId()) == lease;
    }

    boolean isBusy(@NotNull UUID playerId) {
        return leases.containsKey(playerId);
    }

    boolean isInventorySensitiveBusy(@NotNull UUID playerId) {
        Lease lease = leases.get(playerId);
        return lease != null && lease.inventorySensitive();
    }

    void guardInventory(@NotNull UUID playerId) {
        inventoryGuards.add(playerId);
    }

    void releaseInventoryGuard(@NotNull UUID playerId) {
        if (!stopped) {
            inventoryGuards.remove(playerId);
        }
    }

    void openInventoryGate() {
        if (!stopped) {
            inventoryGateClosed = false;
        }
    }

    void closeInventoryGate() {
        inventoryGateClosed = true;
    }

    boolean isInventoryMovementBlocked(@NotNull UUID playerId) {
        if (inventoryGateClosed || inventoryGuards.contains(playerId)) {
            return true;
        }
        Lease lease = leases.get(playerId);
        return lease != null && lease.inventorySensitive();
    }

    boolean hasOutstandingInventoryProtection() {
        return !inventoryGuards.isEmpty()
                || leases.values().stream().anyMatch(Lease::inventorySensitive);
    }

    public void stop() {
        stopped = true;
        inventoryGateClosed = true;
    }

    private boolean inventoryLocked(UUID playerId) {
        return isInventoryMovementBlocked(playerId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && inventoryLocked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && inventoryLocked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (inventoryLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (inventoryLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHeldSlot(PlayerItemHeldEvent event) {
        if (inventoryLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (inventoryLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (inventoryLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (inventoryLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (inventoryLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (inventoryLocked(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player
                && inventoryLocked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && inventoryLocked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    record Lease(@NotNull UUID playerId, long token, boolean inventorySensitive) { }
}
