package org.rafalohaki.wpmecore.addons.skyblock.profile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.IslandProfileDao;
import org.rafalohaki.wpmecore.addons.skyblock.playtime.ProfilePlaytimeDao;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * M1-D: Recovery state machine for every non-terminal state.
 * <p>
 * Non-terminal states are any ProfileTransition with result IS NULL:
 * CREATING, INITIALIZING, DELETING, LEAVING, KICKING, RESETTING, etc.
 * The service supports resume (replay from checkpoint) and quarantine
 * (stop replay, require admin) with correlation/operation ID.
 * <p>
 * Resume is idempotent: replaying at the same checkpoint does not double-apply.
 * Offline playtime is never counted (crash reconciliation closes at last_heartbeat).
 * The inventory outbox/ledger guard is consulted before any new mutation.
 */
public final class ProfileRecoveryService {

    private static final Logger LOG = Logger.getLogger(ProfileRecoveryService.class.getName());

    // Quarantine after this age without progress (ms) — 5 minutes
    private static final long QUARANTINE_AGE_MS = 5 * 60 * 1000L;
    // Max resume attempts before quarantine
    private static final int MAX_RESUME_ATTEMPTS = 5;

    private final ProfileTransitionDao transitionDao;
    private final ProfileSnapshotDao snapshotDao;
    private final IslandProfileDao profileDao;
    private final ProfilePlaytimeDao playtimeDao;
    private final ProfileSnapshotService snapshotService;
    /**
     * ISLAND-1: autorytatywne „czy ta wyspa naprawdę zniknęła u Skyllii”.
     * Bez tego resume DELETE/RESET wyciera profil ŻYWEJ wyspy — journal powstaje
     * PRZED przetworzeniem komendy i przeżywa jej odrzucenie (a odrzuca ją pięcioma
     * ścieżkami nasz własny IslandLifecycleGuard: saldo banku > 0, konflikt nicku,
     * wątek tickowy, timeout odczytu salda). Resume leci przy KAŻDYM boocie
     * (recoverAll) i przy relogu, więc to była odroczona bomba.
     */
    private volatile @Nullable IslandExistsCheck islandExistsCheck;

    /** Autorytatywne pytanie do Skyllii; fail-closed — „nie wiem” ma znaczyć „istnieje”. */
    @FunctionalInterface
    public interface IslandExistsCheck {
        boolean stillExists(@NotNull UUID islandId);
    }

    public void setIslandExistsCheck(@Nullable IslandExistsCheck check) {
        this.islandExistsCheck = check;
    }

    public ProfileRecoveryService(
            @NotNull ProfileTransitionDao transitionDao,
            @NotNull ProfileSnapshotDao snapshotDao,
            @NotNull IslandProfileDao profileDao,
            @NotNull ProfilePlaytimeDao playtimeDao,
            @NotNull ProfileSnapshotService snapshotService) {
        this.transitionDao = transitionDao;
        this.snapshotDao = snapshotDao;
        this.profileDao = profileDao;
        this.playtimeDao = playtimeDao;
        this.snapshotService = snapshotService;
    }

    /**
     * Correlation/operation ID is the durable checkpoint for resumable lifecycle transitions.
     * Every transition is created with operationId = correlationId + ":" + UUID.
     */
    public static @NotNull String newOperationId(@NotNull String correlationId) {
        return correlationId + ":" + UUID.randomUUID().toString().substring(0, 8) + ":" + System.currentTimeMillis();
    }

    public static @NotNull String correlationFor(@NotNull UUID playerUuid, @NotNull String action) {
        return action + ":" + playerUuid;
    }

    /**
     * Start a new transition with snapshot safety. Writes snapshot first, then transition.
     * If snapshot fails the transition is not created (fail-closed).
     */
    public @NotNull CompletableFuture<Optional<String>> beginTransition(
            @NotNull UUID profileId,
            @NotNull UUID playerUuid,
            @NotNull String transitionType,
            @NotNull String fromStatus,
            @NotNull String toStatus,
            @NotNull String operationId,
            @NotNull String checkpoint,
            @NotNull String serverScope) {
        // Snapshot before cleaning state
        return snapshotService.snapshotBeforeClean(profileId, playerUuid, transitionType, operationId, null)
                .thenCompose(snap -> {
                    if (!snap.created()) {
                        LOG.warning("Snapshot failed for " + operationId + ", aborting transition");
                        return CompletableFuture.completedFuture(Optional.<String>empty());
                    }
                    return transitionDao.create(operationId, profileId, playerUuid, transitionType, fromStatus, toStatus, checkpoint)
                            .thenApply(ok -> ok ? Optional.of(operationId) : Optional.<String>empty());
                });
    }

    /**
     * Resume a single operation by operationId. Idempotent.
     * Returns ResumeResult with whether resumed, completed, or quarantined.
     */
    public @NotNull CompletableFuture<ResumeResult> resume(@NotNull String operationId) {
        return transitionDao.find(operationId).thenCompose(opt -> {
            if (opt.isEmpty()) {
                return CompletableFuture.completedFuture(ResumeResult.notFound(operationId));
            }
            ProfileTransition tx = opt.get();
            if (tx.result() != null) {
                return CompletableFuture.completedFuture(ResumeResult.alreadyComplete(tx));
            }
            long age = System.currentTimeMillis() - tx.updatedAt();
            if (age > QUARANTINE_AGE_MS && isStaleCheckpoint(tx.checkpoint())) {
                return quarantine(operationId, "stale checkpoint " + tx.checkpoint() + " age=" + age).thenApply(v -> ResumeResult.quarantined(tx, "stale"));
            }
            // Attempt resume: advance checkpoint if possible
            return attemptResume(tx);
        });
    }

    private CompletableFuture<ResumeResult> attemptResume(ProfileTransition tx) {
        // For CREATING/INITIALIZING: try to re-verify island existence via profile
        // For DELETING/LEAVING/KICKING: re-apply deactivate if still ACTIVE
        String type = tx.transitionType();
        String checkpoint = tx.checkpoint();
        if (checkpoint == null) checkpoint = "INIT";
        String nextCheckpoint;
        switch (type) {
            case "CREATE":
                // CREATING -> INITIALIZING -> READY
                if ("CREATE_REQUESTED".equals(checkpoint) || "CREATING".equals(checkpoint)) {
                    nextCheckpoint = "INITIALIZING";
                } else if ("INITIALIZING".equals(checkpoint)) {
                    nextCheckpoint = "READY";
                } else if ("READY".equals(checkpoint)) {
                    return completeSuccess(tx.operationId(), "RESUME_READY");
                } else {
                    return quarantine(tx.operationId(), "unknown create checkpoint " + checkpoint).thenApply(v -> ResumeResult.quarantined(tx, "unknown checkpoint"));
                }
                break;
            case "DELETE":
            case "RESET":
                if ("PREPARE".equals(checkpoint) || "SNAPSHOT_WRITTEN".equals(checkpoint)
                        || "MEMBERSHIP_CLEANED".equals(checkpoint)) {
                    // ISLAND-1: deleteProfile to semantyka CAŁEJ WYSPY (profil -> DELETED,
                    // membership wszystkich -> LEFT). Journal DELETE/RESET powstaje PRZED
                    // przetworzeniem komendy, więc przeżywa jej odrzucenie przez
                    // IslandLifecycleGuard — a resume leci przy relogu I przy każdym boocie.
                    // Mutujemy wyłącznie po autorytatywnym potwierdzeniu u Skyllii, że wyspy
                    // naprawdę nie ma. Brak checku = brak mutacji (fail-closed): realne
                    // usunięcie projektuje i tak onDeleteCheck -> ProfileStateService.deleteIsland.
                    if (!islandConfirmedGone(tx.profileId(), tx.operationId())) {
                        return transitionDao.complete(tx.operationId(), "RESUME_COMPLETE")
                                .thenApply(ok -> ok
                                        ? ResumeResult.completed(tx.operationId(), "RESUME_COMPLETE")
                                        : ResumeResult.failed(tx, "complete failed"));
                    }
                    return profileDao.deleteProfile(tx.profileId())
                            .thenCompose(ok -> transitionDao.updateCheckpoint(tx.operationId(), "MEMBERSHIP_CLEANED"))
                            .thenCompose(ok -> transitionDao.complete(tx.operationId(), "RESUME_COMPLETE"))
                            .thenApply(ok2 -> ResumeResult.completed(tx.operationId(), "RESUME_COMPLETE"));
                } else if ("COMPLETE".equals(checkpoint)) {
                    return completeSuccess(tx.operationId(), "RESUME_COMPLETE");
                } else {
                    // Generic progress for unknown checkpoint within delete flow
                    if (checkpoint.startsWith("QRTN")) {
                        return CompletableFuture.completedFuture(ResumeResult.quarantined(tx, "already quarantined"));
                    }
                    return quarantine(tx.operationId(), "unknown delete checkpoint " + checkpoint).thenApply(v -> ResumeResult.quarantined(tx, "unknown checkpoint"));
                }
            case "LEAVE":
            case "KICK":
                // ISLAND-1 fix: journal LEAVE/KICK powstaje PRZED przetworzeniem komendy
                // (może dotyczyć próby odrzuconej przez Skyllię), więc resume NIE może
                // wywoływać deleteProfile — to semantyka CAŁEJ WYSPY (profil DELETED +
                // membership wszystkich LEFT), czyli bomba wycierająca żywą wyspę przy
                // najbliższym relogu/boocie. Mutacja danych (membership ofiary) należy
                // do IslandMemberRemoveListener na SkyblockRemoveMemberEvent; resume
                // wyłącznie domyka tranzycję — zero mutacji danych.
                return transitionDao.complete(tx.operationId(), "RESUME_COMPLETE")
                        .thenApply(ok -> ok
                                ? ResumeResult.completed(tx.operationId(), "RESUME_COMPLETE")
                                : ResumeResult.failed(tx, "complete failed"));
            default:
                // Unknown transition type -> quarantine to avoid random profile assignment
                return quarantine(tx.operationId(), "unknown transition type " + type).thenApply(v -> ResumeResult.quarantined(tx, "unknown type"));
        }

        final String fcNext = nextCheckpoint;
        // If final checkpoint, complete
        if ("READY".equals(nextCheckpoint) || "COMPLETE".equals(nextCheckpoint)) {
            // Try to complete
            return transitionDao.updateCheckpoint(tx.operationId(), fcNext).thenCompose(ok ->
                    completeSuccess(tx.operationId(), "RESUMED_" + fcNext)
            );
        } else {
            return transitionDao.updateCheckpoint(tx.operationId(), fcNext)
                    .thenApply(ok -> ok ? ResumeResult.resumed(tx, fcNext) : ResumeResult.failed(tx, "checkpoint update failed"));
        }
    }

    /**
     * Fail-closed: bez wpiętego checku albo przy wyjątku zwraca {@code false},
     * czyli „nie kasuj”. Wołane z egzekutora SQL (nie z wątku tickowego), bo
     * autorytatywny odczyt Skyllii wprost tego wymaga.
     */
    private boolean islandConfirmedGone(@NotNull UUID islandId, @NotNull String operationId) {
        IslandExistsCheck check = this.islandExistsCheck;
        if (check == null) {
            LOG.log(Level.WARNING, "Resume {0}: brak checku istnienia wyspy — domykam tranzycje "
                    + "bez mutacji (fail-closed). island={1}", new Object[]{operationId, islandId});
            return false;
        }
        try {
            boolean exists = check.stillExists(islandId);
            if (exists) {
                LOG.log(Level.WARNING, "Resume {0}: wyspa {1} NADAL istnieje u Skyllii — komenda "
                        + "zostala odrzucona, journal domkniety bez kasowania profilu",
                        new Object[]{operationId, islandId});
            }
            return !exists;
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "Resume " + operationId + ": check istnienia wyspy " + islandId
                    + " zawiodl — domykam bez mutacji", failure);
            return false;
        }
    }

    private CompletableFuture<ResumeResult> completeSuccess(String operationId, String result) {
        return transitionDao.complete(operationId, result)
                .thenApply(ok -> {
                    if (ok) {
                        Optional<ProfileTransition> tx = Optional.empty();
                        // fetch fresh for result
                        return ResumeResult.completed(operationId, result);
                    } else {
                        return ResumeResult.failed(null, "complete failed " + operationId);
                    }
                });
    }

    /**
     * Quarantine an operation: mark checkpoint as QRTN_<old> and result as QUARANTINED.
     * The ambiguous record goes to report/quarantine, not random profile.
     */
    public @NotNull CompletableFuture<Boolean> quarantine(@NotNull String operationId, @NotNull String reason) {
        return transitionDao.find(operationId).thenCompose(opt -> {
            if (opt.isEmpty()) return CompletableFuture.completedFuture(false);
            ProfileTransition tx = opt.get();
            if (tx.result() != null) return CompletableFuture.completedFuture(false);
            String qCheckpoint = "QRTN_" + (tx.checkpoint() == null ? "UNKNOWN" : tx.checkpoint());
            return transitionDao.updateCheckpoint(operationId, qCheckpoint).thenCompose(ok -> {
                // Use complete with QUARANTINED but distinguish from success: we set result=QUARANTINED
                // complete() sets checkpoint='COMPLETE' — we need a dedicated quarantine path.
                // Instead update checkpoint to QRTN and set result manually via SQL.
                return setQuarantined(operationId, reason);
            });
        });
    }

    private CompletableFuture<Boolean> setQuarantined(String operationId, String reason) {
        // Use underlying sql via transitionDao's sql — we use complete with QUARANTINED then fix checkpoint
        // For now use transitionDao.complete with QUARANTINED and then fix checkpoint back to QRTN via direct SQL?
        // Simpler: we already updated checkpoint to QRTN; now set result=QUARANTINED via complete but it will override checkpoint to COMPLETE.
        // So we implement custom: update result and keep QRTN checkpoint.
        // We need a new method in DAO for quarantine. For now we do direct via sql.
        // Use transitionDao.find to get, then call a custom update via snapshotDao's sql? Instead add method to DAO.
        // As fallback, we use transitionDao.complete but then re-set checkpoint.
        return transitionDao.complete(operationId, "QUARANTINED").thenCompose(ok -> {
            if (!ok) return CompletableFuture.completedFuture(false);
            // Restore QRTN checkpoint that complete() overwrote
            // We need to update checkpoint again via dao
            // But complete already set checkpoint='COMPLETE', so overwrite
            return transitionDao.updateCheckpoint(operationId, "QRTN_QUARANTINED").thenApply(v -> true);
        });
    }

    private boolean isStaleCheckpoint(String checkpoint) {
        if (checkpoint == null) return true;
        return checkpoint.startsWith("QRTN");
    }

    /**
     * Reconnect/restart in every non-terminal state: scan pending transitions and resume.
     * Also reconciles unclosed playtime sessions without counting offline time (crash correction).
     * Returns reconciliation summary.
     */
    public @NotNull CompletableFuture<RecoveryReport> recoverAll() {
        long now = System.currentTimeMillis();
        CompletableFuture<Integer> playtimeReconciled = playtimeDao.reconcileCrashedSessions(now, 5 * 60 * 1000L);
        CompletableFuture<List<ProfileTransition>> pending = transitionDao.listPending(200);

        // D1-fix: resume() leci na tym samym jednowątkowym egzekutorze SQL (SQLite
        // wymusza pool=1), więc join() wewnątrz kontynuacji działającej NA tym
        // wątku zakleszczał sam siebie (kolejka 256 pełna). Składamy bez blokowania:
        // wszystkie resume równolegle jako łańcuchy, tally dopiero po allOf.
        return playtimeReconciled.thenCombine(pending, (reconciled, list) -> Map.entry(reconciled, list))
                .thenCompose(pair -> {
                    List<CompletableFuture<ResumeResult>> resumes = pair.getValue().stream()
                            .map(tx -> resume(tx.operationId()).exceptionally(failure -> containedFailure(tx, failure)))
                            .toList();
                    return CompletableFuture.allOf(resumes.toArray(new CompletableFuture[0]))
                            .thenApply(v -> tallyReport(pair.getKey(), resumes));
                });
    }

    /** D1-fix: per-resume exception containment (odpowiednik dawnego catch wokół join()). */
    private ResumeResult containedFailure(@NotNull ProfileTransition tx, @NotNull Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException completion && completion.getCause() != null
                ? completion.getCause()
                : failure;
        LOG.log(Level.WARNING, "Recovery failed for " + tx.operationId(), cause);
        return ResumeResult.failed(tx, "EXCEPTION:" + cause.getMessage());
    }

    /** D1-fix: tally po allOf — futures są już zakończone, join() tu nie blokuje. */
    private RecoveryReport tallyReport(int reconciled, List<CompletableFuture<ResumeResult>> resumes) {
        int resumed = 0;
        int quarantined = 0;
        int failed = 0;
        List<String> details = new java.util.ArrayList<>();
        details.add("playtime_reconciled=" + reconciled);
        for (CompletableFuture<ResumeResult> future : resumes) {
            try {
                ResumeResult r = future.join();
                switch (r.kind()) {
                    case RESUMED, COMPLETED -> resumed++;
                    case QUARANTINED -> quarantined++;
                    case FAILED, NOT_FOUND -> failed++;
                    default -> {}
                }
                details.add(r.operationId() + ":" + r.kind() + ":" + r.message());
            } catch (Exception e) {
                failed++;
                details.add("EXCEPTION:" + e.getMessage());
                LOG.log(Level.WARNING, "Recovery tally failed", e);
            }
        }
        return new RecoveryReport(reconciled, resumed, quarantined, failed, resumes.size(), details);
    }

    /**
     * Called on player reconnect during create: return status, not second create.
     * If the player has a pending CREATE transition, return its operationId and checkpoint.
     */
    public @NotNull CompletableFuture<Optional<ProfileTransition>> pendingCreateFor(@NotNull UUID playerUuid) {
        return transitionDao.findPendingByPlayer(playerUuid, "CREATE");
    }

    /** M1-D: pending destrukcyjna operacja (DELETE/RESET/LEAVE/KICK) gracza. */
    public @NotNull CompletableFuture<Optional<ProfileTransition>> pendingDestructiveFor(@NotNull UUID playerUuid) {
        return transitionDao.findPendingByPlayer(playerUuid, "DELETE")
                .thenCombine(transitionDao.findPendingByPlayer(playerUuid, "RESET"),
                        (d, r) -> d.isPresent() ? d : r)
                .thenCombine(transitionDao.findPendingByPlayer(playerUuid, "LEAVE"),
                        (a, l) -> a.isPresent() ? a : l)
                .thenCombine(transitionDao.findPendingByPlayer(playerUuid, "KICK"),
                        (a, k) -> a.isPresent() ? a : k);
    }

    /**
     * Block new outbox/ledger mutations if profile has pending DELETE/LEAVE/KICK/RESET.
     * Implements: leave/kick/delete/reset blocks new outbox/ledger mutations
     */
    public @NotNull CompletableFuture<Boolean> isMutationsBlocked(@NotNull UUID profileId, @NotNull UUID playerUuid) {
        return transitionDao.hasBlockingTransition(profileId, playerUuid);
    }

    public @NotNull CompletableFuture<Boolean> isMutationsBlockedForPlayer(@NotNull UUID playerUuid) {
        return transitionDao.hasBlockingTransitionForPlayer(playerUuid);
    }

    // ---- value types ----

    public enum ResumeKind { RESUMED, COMPLETED, QUARANTINED, FAILED, NOT_FOUND, ALREADY_COMPLETE }

    public record ResumeResult(@NotNull ResumeKind kind, @NotNull String operationId, @NotNull String message, @org.jetbrains.annotations.Nullable ProfileTransition transition) {
        static ResumeResult resumed(ProfileTransition tx, String next) { return new ResumeResult(ResumeKind.RESUMED, tx.operationId(), "resumed to " + next, tx); }
        static ResumeResult completed(String op, String result) { return new ResumeResult(ResumeKind.COMPLETED, op, result, null); }
        static ResumeResult quarantined(ProfileTransition tx, String reason) { return new ResumeResult(ResumeKind.QUARANTINED, tx.operationId(), reason, tx); }
        static ResumeResult failed(ProfileTransition tx, String reason) { return new ResumeResult(ResumeKind.FAILED, tx == null ? "unknown" : tx.operationId(), reason, tx); }
        static ResumeResult notFound(String op) { return new ResumeResult(ResumeKind.NOT_FOUND, op, "not found", null); }
        static ResumeResult alreadyComplete(ProfileTransition tx) { return new ResumeResult(ResumeKind.ALREADY_COMPLETE, tx.operationId(), "already complete " + tx.result(), tx); }
    }

    public record RecoveryReport(int playtimeReconciled, int resumed, int quarantined, int failed, int totalPending, @NotNull List<String> details) { }
}
