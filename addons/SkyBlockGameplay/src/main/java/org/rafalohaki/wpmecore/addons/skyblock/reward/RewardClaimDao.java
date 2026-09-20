package org.rafalohaki.wpmecore.addons.skyblock.reward;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Idempotent reward claim foundation.
 * Each (profile_id, player_uuid, reward_id) can be claimed at most once.
 * operation_id is the durable idempotency key linking to profile_transitions / outbox.
 */
public final class RewardClaimDao {

    private final SqlService sql;

    public RewardClaimDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    public @NotNull CompletableFuture<Boolean> tryClaim(
            @NotNull UUID profileId, @NotNull UUID playerUuid,
            @NotNull String rewardId, @NotNull String operationId) {
        return sql.withConnection(conn -> SqlSupport.inTransaction(conn, () -> {
            // Check already claimed
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT 1 FROM wpme_sb_reward_claims WHERE profile_id = ? AND player_uuid = ? AND reward_id = ? LIMIT 1")) {
                check.setString(1, profileId.toString());
                check.setString(2, playerUuid.toString());
                check.setString(3, rewardId);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) return false;
                }
            }
            // Check operation_id uniqueness
            try (PreparedStatement checkOp = conn.prepareStatement(
                    "SELECT 1 FROM wpme_sb_reward_claims WHERE operation_id = ? LIMIT 1")) {
                checkOp.setString(1, operationId);
                try (ResultSet rs = checkOp.executeQuery()) {
                    if (rs.next()) return false;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO wpme_sb_reward_claims (profile_id, player_uuid, reward_id, operation_id, claimed_at) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, profileId.toString());
                ps.setString(2, playerUuid.toString());
                ps.setString(3, rewardId);
                ps.setString(4, operationId);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return true;
        }));
    }

    public @NotNull CompletableFuture<Optional<String>> findOperation(
            @NotNull UUID profileId, @NotNull UUID playerUuid, @NotNull String rewardId) {
        return sql.queryOne(
                "SELECT operation_id FROM wpme_sb_reward_claims WHERE profile_id = ? AND player_uuid = ? AND reward_id = ?",
                rs -> rs.getString(1),
                profileId.toString(), playerUuid.toString(), rewardId);
    }

    public @NotNull CompletableFuture<Boolean> isClaimed(
            @NotNull UUID profileId, @NotNull UUID playerUuid, @NotNull String rewardId) {
        return sql.exists(
                "SELECT 1 FROM wpme_sb_reward_claims WHERE profile_id = ? AND player_uuid = ? AND reward_id = ? LIMIT 1",
                profileId.toString(), playerUuid.toString(), rewardId);
    }
}
