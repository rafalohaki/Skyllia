package org.rafalohaki.wpmecore.addons.skyblock.profile;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Durable inventory/enderchest/XP snapshots for profile transitions.
 * Each snapshot is immutable, checksummed, and server-scoped.
 */
public final class ProfileSnapshotDao {

    private final SqlService sql;

    public ProfileSnapshotDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    public @NotNull CompletableFuture<String> create(
            @NotNull String snapshotId,
            @NotNull UUID profileId,
            @NotNull UUID playerUuid,
            @NotNull String serverScope,
            @NotNull String snapshotType,
            byte[] payload) {

        String payloadSha = sha256(payload == null ? new byte[0] : payload);
        String contentSha = sha256(payload == null ? new byte[0] : payload); // same for now; future: strip metadata

        return sql.withConnection(conn -> SqlSupport.inTransaction(conn, () -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO wpme_sb_profile_snapshots
                        (snapshot_id, profile_id, player_uuid, server_scope, snapshot_type, payload, payload_sha256, content_sha256, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, snapshotId);
                ps.setString(2, profileId.toString());
                ps.setString(3, playerUuid.toString());
                ps.setString(4, serverScope);
                ps.setString(5, snapshotType);
                ps.setBytes(6, payload);
                ps.setString(7, payloadSha);
                ps.setString(8, contentSha);
                ps.setLong(9, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return payloadSha;
        }));
    }

    public @NotNull CompletableFuture<Optional<ProfileSnapshot>> find(@NotNull String snapshotId) {
        return sql.queryOne(
                "SELECT snapshot_id, profile_id, player_uuid, server_scope, snapshot_type, payload, payload_sha256, content_sha256, created_at FROM wpme_sb_profile_snapshots WHERE snapshot_id = ?",
                rs -> new ProfileSnapshot(
                        rs.getString(1),
                        UUID.fromString(rs.getString(2)),
                        UUID.fromString(rs.getString(3)),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getBytes(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getLong(9)),
                snapshotId);
    }

    public @NotNull CompletableFuture<Boolean> verifyChecksum(@NotNull String snapshotId) {
        return find(snapshotId).thenApply(opt -> {
            if (opt.isEmpty()) return false;
            ProfileSnapshot snap = opt.get();
            byte[] payload = snap.payload() == null ? new byte[0] : snap.payload();
            String computed = sha256(payload);
            return computed.equals(snap.payloadSha256());
        });
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
