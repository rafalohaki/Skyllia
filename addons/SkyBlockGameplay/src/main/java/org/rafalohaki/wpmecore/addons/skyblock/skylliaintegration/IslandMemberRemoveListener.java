package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import fr.euphyllia.skyllia.api.event.SkyblockRemoveMemberEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.IslandProfileDao;
import org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileTransition;
import org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileTransitionDao;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * ISLAND-2 fix: projekcja usunięcia członka z wyspy (leave/kick). Skyllia
 * zdejmuje gracza u siebie; WpmeCore musi (1) zdezaktywować membership OFIARY
 * (ACTIVE → LEFT) i (2) domknąć oczekujące tranzycje LEAVE/KICK tej wyspy.
 * Journal LEAVE/KICK zapisuje UUID WYKONAWCY (przy kicku: właściciela) i powstaje
 * PRZED przetworzeniem komendy — bez domknięcia wykonawca siedzi zablokowany
 * (hasBlockingTransitionForPlayer: depozyty/wypłaty/destrukcyjne komendy) do relogu.
 *
 * <p>Wzorzec {@link IslandOwnerTransferListener}: DAO wyłącznie asynchronicznie
 * (SqlService własny executor), zero blokowania wątku regionu, na którym Skyllia
 * może odpalić event; logowanie w whenComplete.
 */
public final class IslandMemberRemoveListener implements Listener {

    /** Wynik domknięcia tranzycji LEAVE/KICK przez ten listener. */
    static final String CLOSE_RESULT = "MEMBER_REMOVED";
    /** Journal powstaje w chwili komendy, więc pending wiersze są świeże — limit skanu. */
    private static final int TRANSITION_SCAN_LIMIT = 50;

    private final JavaPlugin plugin;
    private final IslandProfileDao profileDao;
    private final ProfileTransitionDao transitionDao;
    /** Odstępy ponowień skanu journala; testy skracają, żeby nie czekać sekund. */
    private volatile long[] closeRetryDelaysMs = {1000L, 2000L};

    /** Seam testowy — wzorzec {@code IslandLifecycleGuard.setFinishPendingDeleteRetryDelaysMs}. */
    void setCloseRetryDelaysMs(long... delaysMs) {
        this.closeRetryDelaysMs = delaysMs.clone();
    }

    IslandMemberRemoveListener(@NotNull JavaPlugin plugin,
                               @NotNull IslandProfileDao profileDao,
                               @NotNull ProfileTransitionDao transitionDao) {
        this.plugin = plugin;
        this.profileDao = profileDao;
        this.transitionDao = transitionDao;
    }

    public static @NotNull IslandMemberRemoveListener register(@NotNull JavaPlugin plugin,
                                                               @NotNull IslandProfileDao profileDao,
                                                               @NotNull ProfileTransitionDao transitionDao) {
        var listener = new IslandMemberRemoveListener(plugin, profileDao, transitionDao);
        Bukkit.getPluginManager().registerEvent(
                SkyblockRemoveMemberEvent.class, listener, EventPriority.MONITOR,
                (l, e) -> ((IslandMemberRemoveListener) l).onMemberRemoved((SkyblockRemoveMemberEvent) e),
                plugin, false);
        return listener;
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    private void onMemberRemoved(@NotNull SkyblockRemoveMemberEvent event) {
        var island = event.getIsland();
        if (island == null || island.getId() == null) {
            return;
        }
        var removed = event.getRemovedPlayer();
        if (removed == null || removed.getMojangId() == null) {
            return;
        }
        onMemberRemoved(island.getId(), removed.getMojangId());
    }

    /** Pakietowe dla testów — logika bez zależności od Bukkit. Wyłącznie async DAO. */
    @NotNull CompletableFuture<Void> onMemberRemoved(@NotNull UUID islandId, @NotNull UUID removedPlayerUuid) {
        CompletableFuture<Void> work = profileDao.deactivateMembership(islandId, removedPlayerUuid)
                .thenCompose(deactivated -> {
                    if (!deactivated) {
                        plugin.getLogger().fine("No ACTIVE membership row for " + removedPlayerUuid
                                + " on island " + islandId + " (already LEFT?) — closing journal only");
                    }
                    return closePendingLeaveKickTransitions(islandId);
                });
        work.whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING,
                        "Member-remove projection failed for island " + islandId
                                + " player " + removedPlayerUuid, failure);
            }
        });
        return work;
    }

    /**
     * Domknij pendingowe LEAVE/KICK tej wyspy (player_uuid = wykonawcy) — odblokowuje ekonomię od razu.
     *
     * <p>Bounded retry, bo journal jest wstawiany asynchronicznie (activeMembership →
     * snapshotBeforeClean → transitionDao.create, SQLite pool=1), a Skyllia odpala event
     * równolegle na AsyncScheduler. Pierwszy skan potrafi trafić w pustkę i wtedy wiersz
     * zostaje z {@code result IS NULL}, czyli wykonawca kicka siedzi zablokowany na
     * ekonomii i komendach destrukcyjnych aż do relogu — dokładnie objaw, który ISLAND-2
     * miał usunąć. Ten sam wzorzec co {@code IslandLifecycleGuard.finishPendingDelete}.
     */
    private @NotNull CompletableFuture<Void> closePendingLeaveKickTransitions(@NotNull UUID islandId) {
        return closeAttempt(islandId, 0);
    }

    private @NotNull CompletableFuture<Void> closeAttempt(@NotNull UUID islandId, int attempt) {
        long[] delays = this.closeRetryDelaysMs;
        return closeOnce(islandId).thenCompose(closed -> {
            if (closed > 0) {
                return CompletableFuture.completedFuture((Void) null);
            }
            if (attempt >= delays.length) {
                plugin.getLogger().warning("Brak pendingowej tranzycji LEAVE/KICK dla wyspy " + islandId
                        + " po " + (attempt + 1) + " probach — wykonawca odblokuje sie dopiero przy relogu");
                return CompletableFuture.completedFuture((Void) null);
            }
            CompletableFuture<Void> delayed = new CompletableFuture<>();
            // delayedExecutor = domyślny scheduler CompletableFuture, nie wątek regionu Folii
            CompletableFuture.delayedExecutor(delays[attempt], java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute(() -> closeAttempt(islandId, attempt + 1)
                            .whenComplete((ignored, failure) -> {
                                if (failure != null) {
                                    delayed.completeExceptionally(failure);
                                } else {
                                    delayed.complete(null);
                                }
                            }));
            return delayed;
        });
    }

    private @NotNull CompletableFuture<Integer> closeOnce(@NotNull UUID islandId) {
        return transitionDao.listByProfile(islandId, TRANSITION_SCAN_LIMIT)
                .thenCompose(list -> {
                    java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
                    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                    for (ProfileTransition tx : list) {
                        boolean pendingLeaveOrKick = tx.result() == null
                                && ("LEAVE".equals(tx.transitionType()) || "KICK".equals(tx.transitionType()));
                        if (!pendingLeaveOrKick) {
                            continue;
                        }
                        // DELETE/RESET świadomie poza tym listenerem — mają własny obieg
                        // domknięcia w IslandLifecycleGuard.finishPendingDelete.
                        chain = chain.thenCompose(ignored -> transitionDao.complete(tx.operationId(), CLOSE_RESULT)
                                .thenApply(ok -> {
                                    if (Boolean.TRUE.equals(ok)) {
                                        closed.incrementAndGet();
                                        plugin.getLogger().info("Closed pending " + tx.transitionType()
                                                + " transition " + tx.operationId() + " (member removed from island "
                                                + islandId + ")");
                                    }
                                    return null;
                                }));
                    }
                    return chain.thenApply(ignored -> closed.get());
                });
    }
}
