package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Player data has no supported asynchronous durability API. A FIFO queue
 * serializes only the checkpoints forced by the outbox, so competing regions
 * wait without blocking or polling SQL. The winning save remains synchronous
 * on its owning entity region, as required by Paper. Every request keeps the
 * inventory lease until the durable operation advances or is protected for
 * rejoin.
 */
final class CheckpointCoordinator {

    private static final long SLOW_CHECKPOINT_MILLIS = 50L;
    private static final long SLOW_CHECKPOINT_WARNING_INTERVAL_MILLIS = 30_000L;

    private record CheckpointRequest(
            @NotNull Player player,
            @NotNull PlayerOperationCoordinator.Lease lease,
            @NotNull String operationId,
            @NotNull Runnable afterCheckpoint) { }

    /** Point-in-time checkpoint metrics for the shutdown report. */
    record Snapshot(boolean checkpointInProgress, int queuedCount,
                    long maxQueueDepth, long count, long totalMillis,
                    long maxMillis) { }

    private final JavaPlugin plugin;
    private final InventoryOutbox.PlayerDataCheckpoint checkpointWriter;
    private final PlayerOperationCoordinator coordinator;
    private final Set<String> checkpointRequiredOperations;
    private final BooleanSupplier gate;
    private final Consumer<PlayerOperationCoordinator.Lease> abandon;
    private final InventoryOutbox.CheckpointExhausted exhausted;
    /**
     * Budżet ponowień zapisu playerdaty, per operacja.
     *
     * <p>{@code Player#saveData()} blokuje wątek regionu na I/O dysku i nie ma
     * wariantu asynchronicznego, więc ponawianie go co sekundę bez końca przy
     * padającym dysku obciąża cały region, a nie tylko jednego gracza.
     * Poddanie się jest bezpieczne: Paper pisze do pliku tymczasowego i dopiero
     * potem atomowo podmienia właściwy, z rotacją {@code .dat_old}, więc nieudany
     * zapis zostawia poprzednie dane nietknięte.
     */
    private final Map<String, Integer> checkpointAttempts = new ConcurrentHashMap<>();
    private final Set<String> queuedCheckpointOperations =
            ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<CheckpointRequest> checkpointQueue =
            new ConcurrentLinkedQueue<>();
    private final AtomicBoolean checkpointDrainActive = new AtomicBoolean();
    private final AtomicBoolean checkpointInProgress = new AtomicBoolean();
    private final LongAdder checkpointCount = new LongAdder();
    private final LongAdder checkpointTotalNanos = new LongAdder();
    private final AtomicLong checkpointMaxNanos = new AtomicLong();
    private final AtomicLong checkpointMaxQueueDepth = new AtomicLong();
    private final AtomicLong lastSlowCheckpointWarningMillis = new AtomicLong();

    CheckpointCoordinator(@NotNull JavaPlugin plugin,
                          @NotNull InventoryOutbox.PlayerDataCheckpoint checkpointWriter,
                          @NotNull PlayerOperationCoordinator coordinator,
                          @NotNull Set<String> checkpointRequiredOperations,
                          @NotNull BooleanSupplier gate,
                          @NotNull Consumer<PlayerOperationCoordinator.Lease> abandon,
                          @NotNull InventoryOutbox.CheckpointExhausted exhausted) {
        this.plugin = plugin;
        this.checkpointWriter = checkpointWriter;
        this.coordinator = coordinator;
        this.checkpointRequiredOperations = checkpointRequiredOperations;
        this.gate = gate;
        this.abandon = abandon;
        this.exhausted = exhausted;
    }

    void enqueue(@NotNull Player player, @NotNull PlayerOperationCoordinator.Lease lease,
                 @NotNull String operationId, @NotNull Runnable afterCheckpoint) {
        if (!coordinator.owns(lease) || !gate.getAsBoolean()) {
            coordinator.release(lease);
            return;
        }
        if (!queuedCheckpointOperations.add(operationId)) {
            return;
        }
        checkpointQueue.add(new CheckpointRequest(
                player, lease, operationId, afterCheckpoint));
        checkpointMaxQueueDepth.accumulateAndGet(
                queuedCheckpointOperations.size(), Math::max);
        drainCheckpointQueue();
    }

    Snapshot snapshot() {
        return new Snapshot(checkpointInProgress.get(),
                queuedCheckpointOperations.size(), checkpointMaxQueueDepth.get(),
                checkpointCount.sum(),
                TimeUnit.NANOSECONDS.toMillis(checkpointTotalNanos.sum()),
                TimeUnit.NANOSECONDS.toMillis(checkpointMaxNanos.get()));
    }

    private void drainCheckpointQueue() {
        if (!checkpointDrainActive.compareAndSet(false, true)) {
            return;
        }
        scheduleNextCheckpoint();
    }

    private void scheduleNextCheckpoint() {
        CheckpointRequest request;
        while ((request = checkpointQueue.poll()) != null) {
            if (!queuedCheckpointOperations.contains(request.operationId())) {
                continue;
            }
            if (!coordinator.owns(request.lease())
                    || !gate.getAsBoolean()) {
                queuedCheckpointOperations.remove(request.operationId());
                abandon.accept(request.lease());
                continue;
            }
            try {
                CheckpointRequest scheduledRequest = request;
                var scheduled = request.player().getScheduler().run(plugin,
                        ignored -> executeCheckpoint(scheduledRequest),
                        () -> rejectQueuedCheckpoint(scheduledRequest));
                if (scheduled == null) {
                    rejectQueuedCheckpoint(request);
                }
            } catch (RuntimeException rejected) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not schedule playerdata checkpoint for "
                                + request.operationId()
                                + "; recovery remains fail-closed until join",
                        rejected);
                rejectQueuedCheckpoint(request);
            }
            return;
        }
        checkpointDrainActive.set(false);
        if (!checkpointQueue.isEmpty()) {
            drainCheckpointQueue();
        }
    }

    private void executeCheckpoint(CheckpointRequest request) {
        if (!queuedCheckpointOperations.contains(request.operationId())) {
            finishCheckpointDrain();
            return;
        }
        if (!coordinator.owns(request.lease()) || !gate.getAsBoolean()
                || !request.player().isOnline()) {
            rejectQueuedCheckpoint(request);
            return;
        }
        long startedNanos = System.nanoTime();
        RuntimeException checkpointFailure = null;
        checkpointInProgress.set(true);
        try {
            checkpointWriter.save(request.player());
        } catch (RuntimeException failure) {
            checkpointFailure = failure;
            plugin.getLogger().log(Level.SEVERE,
                    "Playerdata checkpoint failed for inventory operation "
                            + request.operationId()
                            + "; the durable operation remains pending",
                    failure);
        } finally {
            long elapsedNanos = Math.max(0L, System.nanoTime() - startedNanos);
            checkpointCount.increment();
            checkpointTotalNanos.add(elapsedNanos);
            checkpointMaxNanos.accumulateAndGet(elapsedNanos, Math::max);
            checkpointInProgress.set(false);
            warnIfSlowCheckpoint(request.operationId(), elapsedNanos);
        }
        if (checkpointFailure != null) {
            retryFailedCheckpointLater(request);
            finishCheckpointDrain();
            return;
        }

        queuedCheckpointOperations.remove(request.operationId());
        checkpointRequiredOperations.remove(request.operationId());
        checkpointAttempts.remove(request.operationId());
        finishCheckpointDrain();
        if (coordinator.owns(request.lease()) && gate.getAsBoolean()) {
            request.afterCheckpoint().run();
        }
    }

    private void retryFailedCheckpointLater(CheckpointRequest request) {
        if (!queuedCheckpointOperations.remove(request.operationId())) {
            return;
        }
        if (!coordinator.owns(request.lease())
                || !gate.getAsBoolean()) {
            checkpointAttempts.remove(request.operationId());
            abandon.accept(request.lease());
            return;
        }
        int attempts = checkpointAttempts.merge(request.operationId(), 1, Integer::sum);
        ConvergenceRetryPolicy.Step step = ConvergenceRetryPolicy.next(attempts - 1, true);
        if (step.crossedIntoSlowMode()) {
            plugin.getLogger().warning("Playerdata checkpoint for "
                    + request.operationId() + " has failed " + attempts
                    + " times; backing off before giving up");
        }
        if (step.decision() == ConvergenceRetryPolicy.Decision.QUARANTINE) {
            /*
             * Poddanie się nie uszkadza playerdaty: Paper zapisuje do pliku
             * tymczasowego i podmienia go atomowo, więc nieudany zapis zostawia
             * poprzednie dane nienaruszone. Status w bazie nie ruszy do przodu,
             * bo to właśnie checkpoint miał go odblokować.
             */
            plugin.getLogger().severe("Giving up on the playerdata checkpoint for "
                    + request.operationId() + " after " + attempts
                    + " failures; quarantining the operation");
            checkpointAttempts.remove(request.operationId());
            exhausted.onExhausted(request.player(), request.lease(),
                    request.operationId(),
                    "playerdata checkpoint failed " + attempts + " times");
            return;
        }
        try {
            var scheduled = request.player().getScheduler().runDelayed(plugin, ignored -> {
                if (!coordinator.owns(request.lease())
                        || !gate.getAsBoolean()) {
                    abandon.accept(request.lease());
                    return;
                }
                if (queuedCheckpointOperations.add(request.operationId())) {
                    checkpointQueue.add(request);
                    checkpointMaxQueueDepth.accumulateAndGet(
                            queuedCheckpointOperations.size(), Math::max);
                    drainCheckpointQueue();
                }
            }, () -> abandon.accept(request.lease()),
                    step.delayTicks());
            if (scheduled == null) {
                abandon.accept(request.lease());
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not retry failed playerdata checkpoint for "
                            + request.operationId()
                            + "; recovery remains fail-closed until join",
                    rejected);
            abandon.accept(request.lease());
        }
    }

    private void rejectQueuedCheckpoint(CheckpointRequest request) {
        if (!queuedCheckpointOperations.remove(request.operationId())) {
            return;
        }
        checkpointAttempts.remove(request.operationId());
        abandon.accept(request.lease());
        finishCheckpointDrain();
    }

    private void finishCheckpointDrain() {
        checkpointDrainActive.set(false);
        drainCheckpointQueue();
    }

    private void warnIfSlowCheckpoint(String operationId, long elapsedNanos) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        if (elapsedMillis < SLOW_CHECKPOINT_MILLIS) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = lastSlowCheckpointWarningMillis.get();
        if (now - previous >= SLOW_CHECKPOINT_WARNING_INTERVAL_MILLIS
                && lastSlowCheckpointWarningMillis.compareAndSet(previous, now)) {
            plugin.getLogger().warning("Slow synchronous playerdata checkpoint: operation="
                    + operationId + ", duration_ms=" + elapsedMillis
                    + ". Check storage latency and economy load.");
        }
    }
}
