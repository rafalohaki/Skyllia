package org.rafalohaki.wpmecore.addons.skyblock.playtime;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * High-level playtime service that distinguishes active vs AFK and handles crash reconciliation.
 * Wrap ProfilePlaytimeDao with domain semantics.
 */
public final class ProfilePlaytimeService {

    private final ProfilePlaytimeDao dao;

    // Threshold for considering a session abandoned (e.g. 5 min without heartbeat = crash)
    private static final long ABANDON_THRESHOLD_MS = 5 * 60 * 1000L;

    public ProfilePlaytimeService(@NotNull ProfilePlaytimeDao dao) {
        this.dao = dao;
    }

    public @NotNull CompletableFuture<Boolean> onJoin(@NotNull UUID profileId, @NotNull UUID playerUuid, long now) {
        return dao.startSession(profileId, playerUuid, now);
    }

    public @NotNull CompletableFuture<Boolean> onHeartbeat(@NotNull UUID profileId, @NotNull UUID playerUuid, long now, boolean isAfk) {
        return dao.heartbeat(profileId, playerUuid, now, isAfk);
    }

    public @NotNull CompletableFuture<Boolean> onQuit(@NotNull UUID profileId, @NotNull UUID playerUuid, long now) {
        return dao.endSession(profileId, playerUuid, now);
    }

    /**
     * Idempotent: calling twice does not double count.
     */
    public @NotNull CompletableFuture<Boolean> closeSessionIdempotent(@NotNull UUID profileId, @NotNull UUID playerUuid, long now) {
        return dao.endSession(profileId, playerUuid, now);
    }

    public @NotNull CompletableFuture<Integer> reconcileCrashedSessions(long now) {
        return dao.reconcileCrashedSessions(now, ABANDON_THRESHOLD_MS);
    }

    public @NotNull CompletableFuture<ProfilePlaytimeDao.PlaytimeRow> playtime(@NotNull UUID profileId, @NotNull UUID playerUuid) {
        return dao.find(profileId, playerUuid).thenApply(opt -> opt.orElse(
                new ProfilePlaytimeDao.PlaytimeRow(profileId, playerUuid, 0L, 0L, null, null, 0L, 0L)));
    }
}
