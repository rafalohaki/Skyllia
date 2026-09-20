package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Lease-retry token machinery for outbox reconciliation. At most one retry
 * token exists per player, so a stale scheduler callback can never pile up
 * duplicate retries. Entity-scheduler rejections fall back to the global
 * region scheduler; every failure path stays fail-closed until the next join.
 */
final class LeaseRetryScheduler {

    static final int LEASE_RETRY_LIMIT = 40;
    static final long LEASE_RETRY_SLOW_DELAY_TICKS = 100L;
    private static final long LEASE_RETRY_DELAY_TICKS = 5L;

    @FunctionalInterface
    interface RetryAction {
        void retry(@NotNull Player player, int attempt);
    }

    private static final class RetryToken {
    }

    private final JavaPlugin plugin;
    private final PlayerOperationCoordinator coordinator;
    private final long rejectedRetryDelayTicks;
    private final BooleanSupplier gate;
    private final Consumer<PlayerOperationCoordinator.Lease> abandon;
    private final RetryAction retryAction;
    private final Map<UUID, RetryToken> leaseRetryTokens = new ConcurrentHashMap<>();

    LeaseRetryScheduler(@NotNull JavaPlugin plugin,
                        @NotNull PlayerOperationCoordinator coordinator,
                        long rejectedRetryDelayTicks,
                        @NotNull BooleanSupplier gate,
                        @NotNull Consumer<PlayerOperationCoordinator.Lease> abandon,
                        @NotNull RetryAction retryAction) {
        this.plugin = plugin;
        this.coordinator = coordinator;
        this.rejectedRetryDelayTicks = rejectedRetryDelayTicks;
        this.gate = gate;
        this.abandon = abandon;
        this.retryAction = retryAction;
    }

    boolean hasRetryToken(@NotNull UUID playerId) {
        return leaseRetryTokens.containsKey(playerId);
    }

    void cancelRetry(@NotNull UUID playerId) {
        leaseRetryTokens.remove(playerId);
    }

    void clear() {
        leaseRetryTokens.clear();
    }

    void scheduleLeaseRetry(@NotNull Player player, int attempt) {
        UUID playerId = player.getUniqueId();
        boolean slowRetry = attempt >= LEASE_RETRY_LIMIT;
        RetryToken token = new RetryToken();
        if (leaseRetryTokens.putIfAbsent(playerId, token) != null) {
            return;
        }
        if (attempt == LEASE_RETRY_LIMIT) {
            plugin.getLogger().warning("Inventory reconciliation is still waiting for "
                    + "the active economy lease of " + playerId
                    + "; recovery remains fail-closed and will retry with slow backoff");
        }
        int nextAttempt = slowRetry ? LEASE_RETRY_LIMIT + 1 : attempt + 1;
        long delayTicks = slowRetry
                ? LEASE_RETRY_SLOW_DELAY_TICKS : LEASE_RETRY_DELAY_TICKS;
        try {
            var scheduled = player.getScheduler().runDelayed(plugin, ignored -> {
                if (leaseRetryTokens.remove(playerId, token)) {
                    retryAction.retry(player, nextAttempt);
                }
            }, () -> scheduleGlobalLeaseRetry(
                    player, playerId, token, nextAttempt), delayTicks);
            if (scheduled == null) {
                scheduleGlobalLeaseRetry(player, playerId, token, nextAttempt);
            }
        } catch (RuntimeException rejected) {
            // SKYBLOCK-2-10: odrzucenie planowania to zawsze degradacja —
            // Level.FINE czynił regresję shutdownu niewidoczną w produkcji.
            // WARNING raz na minutę na gracza (dedup), fallback globalny działa.
            warnOncePerMinute(playerId,
                    "Player lease retry was rejected for " + playerId, rejected);
            scheduleGlobalLeaseRetry(player, playerId, token, nextAttempt);
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> lastRetryWarning =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RETRY_WARNING_DEDUP_MS = 60_000L;

    /** SKYBLOCK-2-10: WARNING z deduplikacją — spam masowego odrzucenia nie zaleje logu. */
    private void warnOncePerMinute(@NotNull UUID playerId, @NotNull String message,
                                   @NotNull RuntimeException cause) {
        long now = System.currentTimeMillis();
        Long last = lastRetryWarning.get(playerId);
        if (last == null || now - last >= RETRY_WARNING_DEDUP_MS) {
            if (lastRetryWarning.replace(playerId, last == null ? 0L : last, now)
                    || lastRetryWarning.putIfAbsent(playerId, now) == null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, message, cause);
            }
        }
    }

    private void scheduleGlobalLeaseRetry(Player player, UUID playerId,
                                          RetryToken rejectedToken, int nextAttempt) {
        RetryToken fallbackToken = new RetryToken();
        if (!leaseRetryTokens.replace(playerId, rejectedToken, fallbackToken)) {
            return;
        }
        if (!gate.getAsBoolean()) {
            leaseRetryTokens.remove(playerId, fallbackToken);
            return;
        }
        try {
            var scheduled = plugin.getServer().getGlobalRegionScheduler().runDelayed(
                    plugin, ignored -> {
                        if (leaseRetryTokens.remove(playerId, fallbackToken)) {
                            retryAction.retry(player, nextAttempt);
                        }
                    }, rejectedRetryDelayTicks);
            if (scheduled == null && leaseRetryTokens.remove(playerId, fallbackToken)) {
                plugin.getLogger().warning("Global inventory reconciliation retry was "
                        + "rejected for " + playerId
                        + "; recovery remains fail-closed until the next join");
            }
        } catch (RuntimeException rejected) {
            if (leaseRetryTokens.remove(playerId, fallbackToken)) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not schedule global inventory reconciliation retry for "
                                + playerId
                                + "; recovery remains fail-closed until the next join",
                        rejected);
            }
        }
    }
}
