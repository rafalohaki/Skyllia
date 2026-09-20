package org.rafalohaki.wpmecore.addons.skyblock.profile;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * M1-D: Snapshot safety before cleaning profile state.
 * Every destructive transition (leave/kick/delete/reset) writes an immutable snapshot
 * with server/profile scope and SHA-256 checksum before mutating membership.
 * <p>
 * The snapshot is immutable, server-scoped, and checksummed. It is the only
 * recovery anchor if the subsequent membership/profile delete fails or is
 * interrupted (crash). The snapshot payload is deliberately opaque bytes — the
 * service never stores IP addresses or secrets (M1-D analytics contract).
 */
public final class ProfileSnapshotService {

    private final ProfileSnapshotDao dao;
    private final String serverScope;

    public ProfileSnapshotService(@NotNull ProfileSnapshotDao dao, @NotNull String serverScope) {
        this.dao = dao;
        this.serverScope = serverScope;
    }

    /**
     * Create a snapshot with explicit bytes. Returns the snapshotId and its sha256.
     */
    public @NotNull CompletableFuture<SnapshotResult> snapshot(
            @NotNull UUID profileId,
            @NotNull UUID playerUuid,
            @NotNull String snapshotType,
            @Nullable byte[] payload,
            @NotNull String correlationId) {
        String snapshotId = snapshotType.toLowerCase() + ":" + profileId + ":" + playerUuid + ":" + correlationId + ":" + UUID.randomUUID().toString().substring(0, 8);
        byte[] safePayload = payload == null ? new byte[0] : payload.clone();
        return dao.create(snapshotId, profileId, playerUuid, serverScope, snapshotType, safePayload)
                .thenApply(sha -> new SnapshotResult(snapshotId, sha, true));
    }

    /**
     * Snapshot before cleaning profile state. Called for leave/kick/delete/reset.
     * Captures a minimal durable record (profileId, playerUuid, serverScope) as
     * payload — no IP, no secrets — and returns its checksum for audit.
     * <p>
     * If the player is online we also serialize a compact inventory fingerprint
     * (material counts) as payload; offline path still writes the marker payload
     * so the operation is never destructive without a snapshot.
     */
    public @NotNull CompletableFuture<SnapshotResult> snapshotBeforeClean(
            @NotNull UUID profileId,
            @NotNull UUID playerUuid,
            @NotNull String transitionType,
            @NotNull String operationId,
            @Nullable Player onlinePlayer) {
        byte[] payload = buildPayload(profileId, playerUuid, transitionType, operationId, onlinePlayer);
        String snapshotType = "PRE_" + transitionType.toUpperCase(java.util.Locale.ROOT);
        return snapshot(profileId, playerUuid, snapshotType, payload, operationId);
    }

    /**
     * Snapshot for creation flow — captures profileId/mode before skyllia create
     * so a crashed creation can be reconciled (no orphan islands).
     */
    public @NotNull CompletableFuture<SnapshotResult> snapshotBeforeCreate(
            @NotNull UUID profileId,
            @NotNull UUID playerUuid,
            @NotNull String mode,
            @NotNull String operationId) {
        String json = "{\"profileId\":\"" + profileId + "\",\"playerUuid\":\"" + playerUuid + "\",\"mode\":\"" + mode + "\",\"op\":\"" + operationId + "\",\"serverScope\":\"" + serverScope + "\"}";
        return snapshot(profileId, playerUuid, "PRE_CREATE", json.getBytes(StandardCharsets.UTF_8), operationId);
    }

    public @NotNull CompletableFuture<java.util.Optional<ProfileSnapshot>> find(@NotNull String snapshotId) {
        return dao.find(snapshotId);
    }

    public @NotNull CompletableFuture<Boolean> verify(@NotNull String snapshotId) {
        return dao.verifyChecksum(snapshotId);
    }

    private byte[] buildPayload(UUID profileId, UUID playerUuid, String transitionType, String operationId, @Nullable Player player) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"profileId\":\"").append(profileId).append("\"");
        sb.append(",\"playerUuid\":\"").append(playerUuid).append("\"");
        sb.append(",\"op\":\"").append(operationId).append("\"");
        sb.append(",\"type\":\"").append(transitionType).append("\"");
        sb.append(",\"serverScope\":\"").append(serverScope).append("\"");
        sb.append(",\"ts\":").append(System.currentTimeMillis());
        if (player != null) {
            try {
                int totalItems = 0;
                for (ItemStack is : player.getInventory().getContents()) {
                    if (is != null && !is.getType().isAir()) totalItems += is.getAmount();
                }
                int enderItems = 0;
                for (ItemStack is : player.getEnderChest().getContents()) {
                    if (is != null && !is.getType().isAir()) enderItems += is.getAmount();
                }
                sb.append(",\"invItems\":").append(totalItems);
                sb.append(",\"enderItems\":").append(enderItems);
                sb.append(",\"level\":").append(player.getLevel());
                sb.append(",\"exp\":").append(player.getExp());
            } catch (Exception ignored) {
                // Inventory read can fail off thread; keep marker payload
            }
        }
        sb.append("}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record SnapshotResult(@NotNull String snapshotId, @NotNull String sha256, boolean created) { }
}
