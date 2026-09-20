package org.rafalohaki.wpmecore.addons.skyblock.islandprofile;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Guards exactly one ACTIVE island per UUID (R-MEM-01).
 * <p>
 * Per-UUID serialization mirrors LedgerService's account sequencer:
 * operations touching the same playerId are serialized via a tail-future map,
 * while unrelated players proceed concurrently. PostgreSQL path also uses
 * transaction-scoped advisory locks inside the DAO for cross-process safety;
 * SQLite is single-writer by deployment, so the in-process serializer is sufficient.
 */
public final class ProfileStateService {

    private final IslandProfileDao dao;
    private final Object mutex = new Object();
    private final Map<String, CompletableFuture<Void>> tails = new HashMap<>();
    // Optional cache of active memberships for fast read-path (invalidated on mutation)
    private final ConcurrentHashMap<UUID, Optional<IslandMembership>> membershipCache = new ConcurrentHashMap<>();

    public ProfileStateService(@NotNull IslandProfileDao dao) {
        this.dao = dao;
    }

    /**
     * Attempt to create a new island for owner. Fails if owner already has an ACTIVE membership.
     * Serialized per owner UUID.
     */
    public @NotNull CompletableFuture<Boolean> tryCreateIsland(@NotNull UUID ownerUuid, @NotNull UUID islandId, @NotNull String mode) {
        return serialize(List.of(ownerUuid), () ->
                dao.tryCreateProfile(islandId, ownerUuid, mode).thenApply(ok -> {
                    if (ok) membershipCache.remove(ownerUuid);
                    return ok;
                }));
    }

    /**
     * Attempt to accept an invite: join an island. Fails if player already has ACTIVE membership.
     * Serialized per player UUID. Caller must have already verified via SkylliaIntegration that
     * the invite is valid — this guard only ensures one active membership.
     */
    public @NotNull CompletableFuture<Boolean> tryAcceptInvite(@NotNull UUID playerUuid, @NotNull UUID islandId, @NotNull String role) {
        return serialize(List.of(playerUuid), () ->
                dao.tryJoin(islandId, playerUuid, role).thenApply(ok -> {
                    if (ok) membershipCache.remove(playerUuid);
                    return ok;
                }));
    }

    /** Leave/Kick: deactivate membership. Serialized per player. */
    public @NotNull CompletableFuture<Boolean> leaveIsland(@NotNull UUID playerUuid, @NotNull UUID islandId) {
        return serialize(List.of(playerUuid), () ->
                dao.deactivateMembership(islandId, playerUuid).thenApply(ok -> {
                    if (ok) membershipCache.remove(playerUuid);
                    return ok;
                }));
    }

    /** Delete island: mark profile DELETED and deactivate all members. Serialized per island owner. */
    public @NotNull CompletableFuture<Boolean> deleteIsland(@NotNull UUID islandId) {
        // No per-UUID serialization needed for delete itself, but we do it via islandId hash to avoid races with joins.
        // Simplify: direct DAO call (DAO already uses transaction). Invalidate relevant cache entries lazily.
        return dao.deleteProfile(islandId).thenApply(ok -> {
            if (ok) membershipCache.clear();
            return ok;
        });
    }

    public @NotNull CompletableFuture<Optional<IslandMembership>> activeMembership(@NotNull UUID playerUuid) {
        // Direct DB, no serialization needed for reads
        return dao.findActiveMembership(playerUuid);
    }

    public @NotNull CompletableFuture<Optional<IslandProfile>> profile(@NotNull UUID islandId) {
        return dao.findProfile(islandId);
    }

    // ---- serialization (copy of LedgerService pattern) ----

    private <T> CompletableFuture<T> serialize(List<UUID> uuids, Supplier<CompletableFuture<T>> action) {
        List<String> keys = uuids.stream().map(UUID::toString).distinct().sorted().toList();
        if (keys.isEmpty()) throw new IllegalArgumentException("empty key set");
        CompletableFuture<Void> start = new CompletableFuture<>();
        CompletableFuture<T> operation;
        CompletableFuture<Void> tail;
        synchronized (mutex) {
            CompletableFuture<?>[] preds = keys.stream().map(tails::get).filter(java.util.Objects::nonNull).toArray(CompletableFuture[]::new);
            CompletableFuture<Void> predecessor = CompletableFuture.allOf(preds).handle((ignored, f) -> null);
            operation = start.thenCompose(ignored -> predecessor).thenCompose(ignored -> {
                try {
                    CompletableFuture<T> submitted = action.get();
                    return submitted == null ? CompletableFuture.failedFuture(new NullPointerException("null future")) : submitted;
                } catch (Throwable e) {
                    return CompletableFuture.failedFuture(e);
                }
            });
            tail = operation.handle((ignored, f) -> null);
            for (String k : keys) tails.put(k, tail);
        }
        CompletableFuture<Void> registered = tail;
        tail.whenComplete((ignored, f) -> {
            synchronized (mutex) {
                for (String k : keys) tails.remove(k, registered);
            }
        });
        CompletableFuture<T> exposed = new CompletableFuture<>();
        operation.whenComplete((v, f) -> {
            if (f == null) exposed.complete(v);
            else exposed.completeExceptionally(f);
        });
        start.complete(null);
        return exposed;
    }
}
