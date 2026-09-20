package org.rafalohaki.wpmecore.addons.skyblock.playtime;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Durable per-profile playtime store.
 * Distinguishes active vs AFK, idempotent session close, crash correction.
 */
public final class ProfilePlaytimeDao {

    private final SqlService sql;

    public ProfilePlaytimeDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    public record PlaytimeRow(
            @NotNull UUID profileId,
            @NotNull UUID playerUuid,
            long totalActiveMs,
            long totalAfkMs,
            Long sessionStart,
            Long lastHeartbeat,
            long revision,
            long updatedAt) {
    }

    public @NotNull CompletableFuture<Optional<PlaytimeRow>> find(
            @NotNull UUID profileId, @NotNull UUID playerUuid) {
        return sql.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT profile_id, player_uuid, total_active_ms, total_afk_ms, session_start, last_heartbeat, revision, updated_at FROM wpme_sb_playtime WHERE profile_id = ? AND player_uuid = ?")) {
                ps.setString(1, profileId.toString());
                ps.setString(2, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    return Optional.of(map(rs));
                }
            }
        });
    }

    private static PlaytimeRow map(ResultSet rs) throws SQLException {
        Long sessionStart = rs.getObject(5) == null ? null : rs.getLong(5);
        if (rs.wasNull()) sessionStart = null;
        Long lastHeartbeat = rs.getObject(6) == null ? null : rs.getLong(6);
        if (rs.wasNull()) lastHeartbeat = null;
        return new PlaytimeRow(
                UUID.fromString(rs.getString(1)),
                UUID.fromString(rs.getString(2)),
                rs.getLong(3),
                rs.getLong(4),
                sessionStart,
                lastHeartbeat,
                rs.getLong(7),
                rs.getLong(8)
        );
    }

    /**
     * Start a session idempotently. If already in session, no-op.
     */
    public @NotNull CompletableFuture<Boolean> startSession(
            @NotNull UUID profileId, @NotNull UUID playerUuid, long now) {
        return sql.withConnection(conn -> SqlSupport.inTransaction(conn, () -> {
            Optional<PlaytimeRow> existing = findSync(conn, profileId, playerUuid);
            if (existing.isPresent() && existing.get().sessionStart() != null) {
                return false; // already in session — idempotent no-op
            }
            if (existing.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO wpme_sb_playtime (profile_id, player_uuid, total_active_ms, total_afk_ms, session_start, last_heartbeat, revision, updated_at) VALUES (?, ?, 0, 0, ?, ?, 0, ?)")) {
                    ps.setString(1, profileId.toString());
                    ps.setString(2, playerUuid.toString());
                    ps.setLong(3, now);
                    ps.setLong(4, now);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                }
                return true;
            } else {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE wpme_sb_playtime SET session_start = ?, last_heartbeat = ?, revision = revision + 1, updated_at = ? WHERE profile_id = ? AND player_uuid = ? AND session_start IS NULL")) {
                    ps.setLong(1, now);
                    ps.setLong(2, now);
                    ps.setLong(3, now);
                    ps.setString(4, profileId.toString());
                    ps.setString(5, playerUuid.toString());
                    return ps.executeUpdate() == 1;
                }
            }
        }));
    }

    /**
     * Record heartbeat. Adds delta from last_heartbeat to total counters.
     * isAfk determines which counter gets the delta.
     * Returns false if no active session.
     */
    public @NotNull CompletableFuture<Boolean> heartbeat(
            @NotNull UUID profileId, @NotNull UUID playerUuid, long now, boolean isAfk) {
        return sql.withConnection(conn -> SqlSupport.inTransaction(conn, () -> {
            Optional<PlaytimeRow> opt = findSync(conn, profileId, playerUuid);
            if (opt.isEmpty() || opt.get().sessionStart() == null || opt.get().lastHeartbeat() == null) {
                return false;
            }
            PlaytimeRow row = opt.get();
            long delta = Math.max(0L, now - row.lastHeartbeat());
            // Cap delta to 10 minutes to avoid clock jumps inflating time beyond tolerance
            if (delta > 600_000L) delta = 600_000L;

            String column = isAfk ? "total_afk_ms" : "total_active_ms";
            // We use dynamic column via string concat — safe because column is controlled enum
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE wpme_sb_playtime SET " + column + " = " + column + " + ?, last_heartbeat = ?, revision = revision + 1, updated_at = ? WHERE profile_id = ? AND player_uuid = ? AND revision = ?")) {
                ps.setLong(1, delta);
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.setString(4, profileId.toString());
                ps.setString(5, playerUuid.toString());
                ps.setLong(6, row.revision());
                if (ps.executeUpdate() != 1) return false;
            }
            return true;
        }));
    }

    /**
     * Idempotent session close. If already closed, no-op (returns false, does not double count).
     * Closes at last_heartbeat — not at now — so offline time is not inflated (crash correction).
     * The small gap between last heartbeat and quit is within tolerance and not counted.
     */
    public @NotNull CompletableFuture<Boolean> endSession(
            @NotNull UUID profileId, @NotNull UUID playerUuid, long now) {
        return sql.withConnection(conn -> SqlSupport.inTransaction(conn, () -> {
            Optional<PlaytimeRow> opt = findSync(conn, profileId, playerUuid);
            if (opt.isEmpty() || opt.get().sessionStart() == null) {
                return false; // already closed — idempotent
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE wpme_sb_playtime SET session_start = NULL, last_heartbeat = NULL, revision = revision + 1, updated_at = ? WHERE profile_id = ? AND player_uuid = ? AND session_start IS NOT NULL")) {
                ps.setLong(1, now);
                ps.setString(2, profileId.toString());
                ps.setString(3, playerUuid.toString());
                return ps.executeUpdate() == 1;
            }
        }));
    }

    /**
     * Crash correction: close abandoned sessions without counting offline time.
     * Any session where last_heartbeat is older than threshold (e.g. 5min) is considered crashed.
     * It is closed at last_heartbeat, not at now, so offline time is not inflated.
     * Returns count of reconciled rows.
     */
    public @NotNull CompletableFuture<Integer> reconcileCrashedSessions(long now, long abandonThresholdMs) {
        return sql.withConnection(conn -> SqlSupport.inTransaction(conn, () -> {
            long cutoff = now - abandonThresholdMs;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE wpme_sb_playtime SET session_start = NULL, last_heartbeat = NULL, revision = revision + 1, updated_at = ? WHERE session_start IS NOT NULL AND last_heartbeat IS NOT NULL AND last_heartbeat < ?")) {
                ps.setLong(1, now);
                ps.setLong(2, cutoff);
                return ps.executeUpdate();
            }
        }));
    }

    public @NotNull CompletableFuture<Long> totalActive(@NotNull UUID profileId, @NotNull UUID playerUuid) {
        return find(profileId, playerUuid).thenApply(r -> r.map(PlaytimeRow::totalActiveMs).orElse(0L));
    }

    public @NotNull CompletableFuture<Long> totalAfk(@NotNull UUID profileId, @NotNull UUID playerUuid) {
        return find(profileId, playerUuid).thenApply(r -> r.map(PlaytimeRow::totalAfkMs).orElse(0L));
    }

    private Optional<PlaytimeRow> findSync(Connection conn, UUID profileId, UUID playerUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT profile_id, player_uuid, total_active_ms, total_afk_ms, session_start, last_heartbeat, revision, updated_at FROM wpme_sb_playtime WHERE profile_id = ? AND player_uuid = ?")) {
            ps.setString(1, profileId.toString());
            ps.setString(2, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(map(rs));
            }
        }
    }
}
