package org.rafalohaki.wpmecore.addons.skyblock.profile;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Journal for leave/kick/delete/reset transitions.
 * Each operation_id is the durable checkpoint for resumable lifecycle transitions.
 */
public final class ProfileTransitionDao {

    private final SqlService sql;

    public ProfileTransitionDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    public @NotNull CompletableFuture<Boolean> create(
            @NotNull String operationId, @NotNull UUID profileId, @NotNull UUID playerUuid,
            @NotNull String transitionType, @NotNull String fromStatus, @NotNull String toStatus,
            String checkpoint) {
        return sql.withConnection(conn -> SqlSupport.inTransaction(conn, () -> {
            boolean inserted = SqlSupport.insertIgnoringConstraint(conn, """
                    INSERT INTO wpme_sb_profile_transitions
                        (operation_id, profile_id, player_uuid, transition_type, from_status, to_status, checkpoint, result, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
                    """, "ON CONFLICT (operation_id) DO NOTHING", stmt -> {
                long now = System.currentTimeMillis();
                stmt.setString(1, operationId);
                stmt.setString(2, profileId.toString());
                stmt.setString(3, playerUuid.toString());
                stmt.setString(4, transitionType);
                stmt.setString(5, fromStatus);
                stmt.setString(6, toStatus);
                stmt.setString(7, checkpoint);
                stmt.setLong(8, now);
                stmt.setLong(9, now);
            });
            return inserted;
        }));
    }

    public @NotNull CompletableFuture<Optional<ProfileTransition>> find(@NotNull String operationId) {
        return sql.queryOne(
                "SELECT operation_id, profile_id, player_uuid, transition_type, from_status, to_status, checkpoint, result, created_at, updated_at FROM wpme_sb_profile_transitions WHERE operation_id = ?",
                rs -> new ProfileTransition(
                        rs.getString(1),
                        UUID.fromString(rs.getString(2)),
                        UUID.fromString(rs.getString(3)),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getLong(9),
                        rs.getLong(10)),
                operationId);
    }

    public @NotNull CompletableFuture<Boolean> updateCheckpoint(
            @NotNull String operationId, @NotNull String checkpoint) {
        return sql.update(
                "UPDATE wpme_sb_profile_transitions SET checkpoint = ?, updated_at = ? WHERE operation_id = ?",
                checkpoint, System.currentTimeMillis(), operationId).thenApply(c -> c == 1);
    }

    public @NotNull CompletableFuture<Boolean> complete(
            @NotNull String operationId, @NotNull String result) {
        return sql.update(
                "UPDATE wpme_sb_profile_transitions SET result = ?, checkpoint = 'COMPLETE', updated_at = ? WHERE operation_id = ? AND result IS NULL",
                result, System.currentTimeMillis(), operationId).thenApply(c -> c == 1);
    }

    /** M1-D: list pending (non-terminal) transitions for recovery. */
    public @NotNull CompletableFuture<java.util.List<ProfileTransition>> listPending(int limit) {
        return sql.query(
                "SELECT operation_id, profile_id, player_uuid, transition_type, from_status, to_status, checkpoint, result, created_at, updated_at FROM wpme_sb_profile_transitions WHERE result IS NULL ORDER BY created_at ASC LIMIT ?",
                rs -> new ProfileTransition(
                        rs.getString(1),
                        UUID.fromString(rs.getString(2)),
                        UUID.fromString(rs.getString(3)),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getLong(9),
                        rs.getLong(10)),
                limit);
    }

    public @NotNull CompletableFuture<java.util.List<ProfileTransition>> listByProfile(@NotNull UUID profileId, int limit) {
        return sql.query(
                "SELECT operation_id, profile_id, player_uuid, transition_type, from_status, to_status, checkpoint, result, created_at, updated_at FROM wpme_sb_profile_transitions WHERE profile_id = ? ORDER BY created_at DESC LIMIT ?",
                rs -> new ProfileTransition(
                        rs.getString(1),
                        UUID.fromString(rs.getString(2)),
                        UUID.fromString(rs.getString(3)),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getLong(9),
                        rs.getLong(10)),
                profileId.toString(), limit);
    }

    public @NotNull CompletableFuture<java.util.Optional<ProfileTransition>> findPendingByPlayer(@NotNull UUID playerUuid, @NotNull String transitionType) {
        return sql.queryOne(
                "SELECT operation_id, profile_id, player_uuid, transition_type, from_status, to_status, checkpoint, result, created_at, updated_at FROM wpme_sb_profile_transitions WHERE player_uuid = ? AND transition_type = ? AND result IS NULL ORDER BY created_at DESC LIMIT 1",
                rs -> new ProfileTransition(
                        rs.getString(1),
                        UUID.fromString(rs.getString(2)),
                        UUID.fromString(rs.getString(3)),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getLong(9),
                        rs.getLong(10)),
                playerUuid.toString(), transitionType);
    }

    public @NotNull CompletableFuture<Boolean> hasBlockingTransition(@NotNull UUID profileId, @NotNull UUID playerUuid) {
        return sql.exists(
                "SELECT 1 FROM wpme_sb_profile_transitions WHERE profile_id = ? AND player_uuid = ? AND result IS NULL AND transition_type IN ('DELETE','RESET','LEAVE','KICK') LIMIT 1",
                profileId.toString(), playerUuid.toString());
    }

    public @NotNull CompletableFuture<Boolean> hasBlockingTransitionForPlayer(@NotNull UUID playerUuid) {
        return sql.exists(
                "SELECT 1 FROM wpme_sb_profile_transitions WHERE player_uuid = ? AND result IS NULL AND transition_type IN ('DELETE','RESET','LEAVE','KICK') LIMIT 1",
                playerUuid.toString());
    }

    public @NotNull CompletableFuture<Boolean> quarantine(@NotNull String operationId, @NotNull String reason) {
        return sql.withConnection(conn -> SqlSupport.inTransaction(conn, () -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE wpme_sb_profile_transitions SET checkpoint = ?, result = 'QUARANTINED', updated_at = ? WHERE operation_id = ? AND result IS NULL")) {
                ps.setString(1, "QRTN_" + reason);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, operationId);
                return ps.executeUpdate() == 1;
            }
        }));
    }
}
