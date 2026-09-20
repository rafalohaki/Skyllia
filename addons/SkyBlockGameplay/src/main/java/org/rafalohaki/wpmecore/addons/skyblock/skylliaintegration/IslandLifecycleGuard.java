package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import fr.euphyllia.skyllia.api.event.PrepareSkyblockCreateEvent;
import fr.euphyllia.skyllia.api.event.SkyblockDeleteEvent;
import fr.euphyllia.skyllia.api.event.SkyblockRemoveMemberEvent;
import fr.euphyllia.skyllia.api.skyblock.enums.RemovalCause;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.ProfileStateService;
import org.rafalohaki.wpmecore.api.identity.IdentityService;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Pojedyncza brama bezpieczeństwa banku przy usuwaniu wyspy: weta
 * {@link SkyblockDeleteEvent} (HIGHEST, cancellable), gdy autorytatywny saldo
 * konta wyspy jest dodatnie. To jedyny pozostały fragment dawnego lifecycle
 * machinery — po przejściu na publiczne API Skyllia nie wystawia już tokenów
 * operacji ani trójeventowego potwierdzenia create/disable, więc śledzenie
 * operationId, reconcile i blokada tworzenia zostały świadomie porzucone.
 *
 * <p>Balans odczytywany jest autorytatywnie (JDBC) przez wstrzykniętą funkcję
 * {@code ledger::authoritativeIslandBalance}; wymaga to wątku poza tickami
 * (Skyllia odpala delete async), stąd asercja w {@link #onDeleteCheck}.
 */
public final class IslandLifecycleGuard implements Listener {

    private final JavaPlugin plugin;
    private final SkylliaIntegration skyllia;
    private final Function<UUID, CompletableFuture<Long>> islandBalance;
    private final MiniMessage miniMessage;
    private final Consumer<UUID> onIslandDelete;
    private final AtomicBoolean registered = new AtomicBoolean(true);
    private volatile @Nullable IdentityService identityService;
    private volatile @Nullable ProfileStateService profileService;
    private volatile @Nullable org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileSnapshotService snapshotService;
    private volatile @Nullable org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileTransitionDao transitionDao;
    // ISLAND-5: bounded retry domknięcia journala DELETE (wyścig z async insertem)
    private volatile long[] finishPendingDeleteRetryDelaysMs = {1000L, 1000L};

    IslandLifecycleGuard(@NotNull JavaPlugin plugin, @NotNull SkylliaIntegration skyllia,
                         @NotNull Function<UUID, CompletableFuture<Long>> islandBalance,
                         @NotNull MiniMessage miniMessage,
                         @Nullable Consumer<UUID> onIslandDelete) {
        this.plugin = plugin;
        this.skyllia = skyllia;
        this.islandBalance = islandBalance;
        this.miniMessage = miniMessage;
        this.onIslandDelete = onIslandDelete;
    }

    public static @NotNull Optional<IslandLifecycleGuard> register(
            @NotNull JavaPlugin owner, @NotNull SkylliaIntegration skyllia,
            @NotNull Function<UUID, CompletableFuture<Long>> islandBalance,
            @NotNull MiniMessage miniMessage,
            @Nullable Consumer<UUID> onIslandDelete) {
        IslandLifecycleGuard guard = new IslandLifecycleGuard(owner, skyllia, islandBalance, miniMessage, onIslandDelete);
        Bukkit.getPluginManager().registerEvent(SkyblockDeleteEvent.class, guard, EventPriority.HIGHEST,
                (listener, event) -> guard.onDeleteCheck((SkyblockDeleteEvent) event), owner, false);
        Bukkit.getPluginManager().registerEvent(SkyblockRemoveMemberEvent.class, guard, EventPriority.MONITOR,
                (listener, event) -> guard.onRemoveMember((SkyblockRemoveMemberEvent) event), owner, false);
        Bukkit.getPluginManager().registerEvent(PrepareSkyblockCreateEvent.class, guard, EventPriority.HIGHEST,
                (listener, event) -> guard.onPrepareCreate((PrepareSkyblockCreateEvent) event), owner, false);
        return Optional.of(guard);
    }

    public void setIdentityService(@Nullable IdentityService identityService) {
        this.identityService = identityService;
    }

    public void setProfileStateService(@Nullable ProfileStateService profileStateService) {
        this.profileService = profileStateService;
    }

    public void setSnapshotService(@Nullable org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public void setTransitionDao(@Nullable org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileTransitionDao transitionDao) {
        this.transitionDao = transitionDao;
    }

    /**
     * M1-D fix (delete-confirm): domyka oczekującą tranzycję DELETE gracza,
     * gdy Skyllia realnie obsłużyła (result=DELETED) albo odrzuciła
     * (result=CANCELLED) usunięcie wyspy. Bez tego wiersz z result=NULL
     * blokował graczowi wszystkie operacje destrukcyjne aż do rejoinu.
     *
     * <p>ISLAND-5 fix: insert journala jest asynchroniczny (command-preprocess),
     * więc pierwszy lookup może być pusty, mimo że wiersz jest w locie —
     * rezygnacja po pierwszym pustym lookupie zostawiała tranzycję NULL i
     * blokowała gracza do relogu. Teraz: bounded retry wg
     * {@code finishPendingDeleteRetryDelaysMs} (domyślnie 2×1 s).
     */
    CompletableFuture<Boolean> finishPendingDelete(@Nullable UUID ownerUuid, @NotNull String result) {
        return finishPendingDeleteAttempt(ownerUuid, result, 0);
    }

    /** Pakietowe dla testów — opóźnienia retry (ISLAND-5). */
    void setFinishPendingDeleteRetryDelaysMs(long... delaysMs) {
        this.finishPendingDeleteRetryDelaysMs = delaysMs.clone();
    }

    private CompletableFuture<Boolean> finishPendingDeleteAttempt(@Nullable UUID ownerUuid, @NotNull String result, int attempt) {
        var dao = this.transitionDao;
        if (dao == null || ownerUuid == null) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        return dao.findPendingByPlayer(ownerUuid, "DELETE")
                .thenCompose(opt -> opt.isEmpty()
                        ? retryPendingDeleteLookup(ownerUuid, result, attempt)
                        : dao.complete(opt.get().operationId(), result))
                .whenComplete((ok, ex) -> {
                    if (ex != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to finish pending DELETE transition for " + ownerUuid, ex);
                    }
                });
    }

    private CompletableFuture<Boolean> retryPendingDeleteLookup(@NotNull UUID ownerUuid, @NotNull String result, int attempt) {
        long[] delays = this.finishPendingDeleteRetryDelaysMs;
        if (attempt >= delays.length) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        CompletableFuture<Boolean> next = new CompletableFuture<>();
        // delayedExecutor = wątek CompletableFuture (scheduler domyślny), nie regionowy
        CompletableFuture.delayedExecutor(delays[attempt], TimeUnit.MILLISECONDS)
                .execute(() -> finishPendingDeleteAttempt(ownerUuid, result, attempt + 1)
                        .whenComplete((value, failure) -> {
                            if (failure != null) {
                                next.completeExceptionally(failure);
                            } else {
                                next.complete(value);
                            }
                        }));
        return next;
    }

    /** Zdejmuje listener; bezpieczne do wielokrotnego wołania. */
    public void unregister() {
        if (registered.compareAndSet(true, false)) {
            HandlerList.unregisterAll(this);
        }
    }

    private void onPrepareCreate(PrepareSkyblockCreateEvent event) {
        if (event.isCancelled()) {
            return;
        }
        // Prepare fires on tick thread in current Skyllia build; we cannot block there.
        // Authoritative one-island + conflict checks are enforced post-create via
        // IslandProfileCreationListener (DB constraint) and command preprocess.
        // If Prepare ever fires async, the same guards still apply via post-create.
    }

    private void onDeleteCheck(SkyblockDeleteEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (Bukkit.isGlobalTickThread()) {
            // SKYBLOCK-2-6: globalny tick-thread to nie jedyny wątek ticka —
            // na Folii/Canvas większość kodu biegnie na regionowych wątkach
            // tickowych. Blokujący odczyt salda nie może odbyć się na żadnym.
            event.setCancelled(true);
            plugin.getLogger().severe("SkyblockDeleteEvent fired on a tick thread for "
                    + event.getIsland().getId() + "; blocking delete as a safe default");
            finishPendingDelete(ownerUuidOf(event), "CANCELLED");
            return;
        }
        try {
            UUID islandId = event.getIsland().getId();
            // M1-A conflict gate: owner conflicted -> block delete until resolved
            IdentityService identity = this.identityService;
            if (identity != null) {
                var owner = event.getIsland().getOwner();
                UUID ownerId = owner != null ? owner.getMojangId() : null;
                if (ownerId != null) {
                    try {
                        boolean conflicted = identity.isConflicted(ownerId)
                                .get(2L, TimeUnit.SECONDS);
                        if (conflicted) {
                            event.setCancelled(true);
                            plugin.getLogger().warning("Blocked deletion of island " + islandId
                                    + " because owner " + ownerId + " has a nick conflict");
                            notifyConflictedOwner(islandId, ownerId);
                            finishPendingDelete(ownerId, "CANCELLED");
                            return;
                        }
                    } catch (Exception conflictCheck) {
                        event.setCancelled(true);
                        plugin.getLogger().log(Level.SEVERE,
                                "Failed to verify identity conflict before delete; blocking", conflictCheck);
                        finishPendingDelete(ownerId, "CANCELLED");
                        return;
                    }
                }
            }
            long balance = islandBalance.apply(islandId).get(5L, TimeUnit.SECONDS);
            if (balance > 0L) {
                event.setCancelled(true);
                plugin.getLogger().warning("Blocked deletion of island " + islandId
                        + " because its bank still contains " + balance + " coins");
                notifyOnlineMembers(islandId, balance);
                finishPendingDelete(ownerUuidOf(event), "CANCELLED");
            } else {
                // M1-D: snapshot safety before cleaning profile state
                var snapService = this.snapshotService;
                UUID ownerId = ownerUuidOf(event);
                if (snapService != null) {
                    try {
                        if (ownerId != null) {
                            String opId = "delete:" + islandId + ":" + System.currentTimeMillis();
                            // D2-fix: bezgraniczny join() na przyszłości z egzekutora SQL
                            // mógł zawiesić wątek na zawsze; limit jak przy odczycie salda.
                            snapService.snapshotBeforeClean(islandId, ownerId, "DELETE", opId, null)
                                    .exceptionally(ex -> {
                                        plugin.getLogger().log(Level.WARNING, "Snapshot before delete failed for " + islandId, ex);
                                        return null;
                                    }).get(5L, TimeUnit.SECONDS);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Snapshot before delete threw for " + islandId, e);
                    }
                }
                resetMemberInventories(event.getIsland());
                if (onIslandDelete != null) {
                    onIslandDelete.accept(islandId);
                }
                ProfileStateService profiles = this.profileService;
                if (profiles != null) {
                    profiles.deleteIsland(islandId).exceptionally(ex -> {
                        plugin.getLogger().log(Level.WARNING, "Failed to deactivate island profile " + islandId, ex);
                        return false;
                    });
                }
                finishPendingDelete(ownerId, "DELETED");
            }
        } catch (Exception failure) {
            event.setCancelled(true);
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to verify island bank balance before delete; operation blocked", failure);
            try {
                finishPendingDelete(ownerUuidOf(event), "CANCELLED");
            } catch (RuntimeException ignored) {
                // best-effort — nie przesłaniamy pierwotnego błędu
            }
        }
    }

    private @Nullable UUID ownerUuidOf(@NotNull SkyblockDeleteEvent event) {
        try {
            var owner = event.getIsland().getOwner();
            return owner != null ? owner.getMojangId() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void resetMemberInventories(@NotNull fr.euphyllia.skyllia.api.skyblock.Island island) {
        java.util.Set<UUID> memberUuids = new java.util.HashSet<>();
        fr.euphyllia.skyllia.api.skyblock.Players owner = island.getOwner();
        if (owner != null && owner.getMojangId() != null) {
            memberUuids.add(owner.getMojangId());
        }
        if (island.getMembers() != null) {
            for (fr.euphyllia.skyllia.api.skyblock.Players member : island.getMembers()) {
                if (member != null && member.getMojangId() != null) {
                    memberUuids.add(member.getMojangId());
                }
            }
        }

        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                for (UUID uuid : memberUuids) {
                    Player online = Bukkit.getPlayer(uuid);
                    if (online != null && online.isOnline()) {
                        online.getScheduler().run(plugin, task -> {
                            online.getInventory().clear();
                            online.getInventory().setArmorContents(null);
                            online.getInventory().setItemInOffHand(null);
                            online.getEnderChest().clear();
                            online.setExp(0.0F);
                            online.setLevel(0);
                            for (var effect : online.getActivePotionEffects()) {
                                online.removePotionEffect(effect.getType());
                            }
                            online.sendMessage(miniMessage.deserialize(
                                    "<red>Twoja wyspa została usunięta.</red> <gray>Twój ekwipunek oraz skrzynia kresu zostały wyczyszczone.</gray>"));
                        }, null);
                    }
                }
            });
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.FINE,
                    "Failed to schedule inventory reset after island delete", rejected);
        }
    }

    private void notifyConflictedOwner(UUID islandId, UUID ownerId) {
        notifyOnlineMembers(islandId, Ui.component(miniMessage,
                "<red>Nie można usunąć wyspy: wykryto konflikt nicku dla właściciela. "
                        + "Administrator musi rozstrzygnąć konflikt przed operacją.</red>"));
    }

    private void notifyOnlineMembers(UUID islandId, long balance) {
        notifyOnlineMembers(islandId, Ui.component(miniMessage,
                "<red>Nie można usunąć wyspy: w banku pozostaje "
                        + "<gold><balance></gold>. Właściciel lub "
                        + "współwłaściciel musi najpierw wypłacić "
                        + "środki przez <yellow>/bank</yellow>.</red>",
                Placeholder.unparsed("balance", Ui.money(balance))));
    }

    private void notifyOnlineMembers(UUID islandId, net.kyori.adventure.text.Component message) {
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    boolean member = skyllia.cachedIslandIdOf(player.getUniqueId())
                            .map(id -> id.equals(islandId))
                            .orElse(false);
                    if (member) {
                        player.getScheduler().run(plugin,
                                ignored -> player.sendMessage(message), null);
                    }
                }
            });
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.FINE,
                    "Island lifecycle warning scheduling was rejected", rejected);
        }
    }

    private void onRemoveMember(@NotNull SkyblockRemoveMemberEvent event) {
        var removed = event.getRemovedPlayer();
        if (removed == null || removed.getMojangId() == null) {
            return;
        }
        UUID uuid = removed.getMojangId();
        var cause = event.getCause();
        if (cause == RemovalCause.ISLAND_DELETED) {
            // Obsłużone bezpośrednio przez onDeleteCheck -> resetMemberInventories
            return;
        }
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                Player online = Bukkit.getPlayer(uuid);
                if (online != null && online.isOnline()) {
                    online.getScheduler().run(plugin, task -> {
                        online.getInventory().clear();
                        online.getInventory().setArmorContents(null);
                        online.getInventory().setItemInOffHand(null);
                        online.getEnderChest().clear();
                        online.setExp(0.0F);
                        online.setLevel(0);
                        for (var effect : online.getActivePotionEffects()) {
                            online.removePotionEffect(effect.getType());
                        }
                        if (cause == RemovalCause.LEAVE) {
                            online.sendMessage(miniMessage.deserialize(
                                    "<yellow>Opuszczono zespół wyspy.</yellow> <gray>Twój ekwipunek oraz skrzynia kresu zostały wyczyszczone.</gray>"));
                        } else {
                            online.sendMessage(miniMessage.deserialize(
                                    "<red>Zostałeś usunięty z zespołu wyspy.</red> <gray>Twój ekwipunek oraz skrzynia kresu zostały wyczyszczone.</gray>"));
                        }
                    }, null);
                }
            });
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.FINE,
                    "Failed to schedule inventory reset after member removal", rejected);
        }
    }
}
