package org.rafalohaki.wpmecore.addons.skyblock.profile;

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
 * DAO for wpme_sb_profile_accounts — profile-isolated wallet.
 * Each (profile_id, player_uuid) is an independent balance domain.
 * Balance keys are NOT shared across islands/profiles.
 */
public final class ProfileAccountDao {

    public static final String DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_profile_accounts (
                profile_id VARCHAR(36) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                balance_minor BIGINT NOT NULL DEFAULT 0,
                revision BIGINT NOT NULL DEFAULT 0,
                updated_at BIGINT NOT NULL,
                PRIMARY KEY (profile_id, player_uuid)
            )
            """;

    private final SqlService sql;

    public ProfileAccountDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    public @NotNull CompletableFuture<Optional<Long>> findBalance(
            @NotNull UUID profileId, @NotNull UUID playerUuid) {
        return sql.withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT balance_minor FROM wpme_sb_profile_accounts WHERE profile_id = ? AND player_uuid = ?")) {
                ps.setString(1, profileId.toString());
                ps.setString(2, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return Optional.of(rs.getLong(1));
                    return Optional.empty();
                }
            }
        });
    }

    public @NotNull CompletableFuture<Long> balance(
            @NotNull UUID profileId, @NotNull UUID playerUuid) {
        return findBalance(profileId, playerUuid).thenApply(v -> v.orElse(0L));
    }

    public @NotNull CompletableFuture<Long> ensureAccount(
            @NotNull UUID profileId, @NotNull UUID playerUuid, long initialBalance) {
        return sql.withConnection(conn -> SqlSupport.inTransaction(conn, () -> {
            // Try insert ignoring constraint
            boolean inserted = SqlSupport.insertIgnoringConstraint(conn, """
                    INSERT INTO wpme_sb_profile_accounts (profile_id, player_uuid, balance_minor, revision, updated_at)
                    VALUES (?, ?, ?, 0, ?)
                    """, "ON CONFLICT (profile_id, player_uuid) DO NOTHING", stmt -> {
                stmt.setString(1, profileId.toString());
                stmt.setString(2, playerUuid.toString());
                stmt.setLong(3, initialBalance);
                stmt.setLong(4, System.currentTimeMillis());
            });
            // Return current balance
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT balance_minor FROM wpme_sb_profile_accounts WHERE profile_id = ? AND player_uuid = ?")) {
                ps.setString(1, profileId.toString());
                ps.setString(2, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("missing profile account after ensure");
                    return rs.getLong(1);
                }
            }
        }));
    }

    /**
     * Idempotent mutate with transaction_id audit in wpme_sb_transactions using profile-scoped key.
     * transactionId must be unique for idempotency.
     */
    public @NotNull CompletableFuture<ProfileMutation> mutate(
            @NotNull UUID profileId, @NotNull UUID playerUuid,
            long initialBalance, long delta,
            @NotNull String transactionId, @NotNull String reason) {
        String accountKey = "profile:" + profileId + ":" + playerUuid;
        return sql.withConnection(conn -> SqlSupport.inTransaction(conn, () -> {
            // Idempotency: if transaction already exists, return current balance without mutating
            try (PreparedStatement check = conn.prepareStatement(
                    "SELECT 1 FROM wpme_sb_transactions WHERE transaction_id = ? LIMIT 1")) {
                check.setString(1, transactionId);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        long bal = selectBalance(conn, profileId, playerUuid);
                        return new ProfileMutation(false, false, bal);
                    }
                }
            }
            // Ensure account exists
            SqlSupport.insertIgnoringConstraint(conn, """
                    INSERT INTO wpme_sb_profile_accounts (profile_id, player_uuid, balance_minor, revision, updated_at)
                    VALUES (?, ?, ?, 0, ?)
                    """, "ON CONFLICT (profile_id, player_uuid) DO NOTHING", stmt -> {
                stmt.setString(1, profileId.toString());
                stmt.setString(2, playerUuid.toString());
                stmt.setLong(3, initialBalance);
                stmt.setLong(4, System.currentTimeMillis());
            });

            long current = selectBalance(conn, profileId, playerUuid);
            long updated;
            if (delta < 0) {
                long amount = Math.negateExact(delta);
                if (current < amount) {
                    return new ProfileMutation(false, true, current);
                }
                updated = current - amount;
            } else {
                if (current > Long.MAX_VALUE - delta) throw new SQLException("balance overflow");
                // check ledger max
                if (current + delta > 9_000_000_000_000_000L) throw new SQLException("balance limit exceeded");
                updated = current + delta;
            }

            // Optimistic revision bump + balance update
            try (PreparedStatement upd = conn.prepareStatement("""
                    UPDATE wpme_sb_profile_accounts
                    SET balance_minor = ?, revision = revision + 1, updated_at = ?
                    WHERE profile_id = ? AND player_uuid = ?
                    """)) {
                upd.setLong(1, updated);
                upd.setLong(2, System.currentTimeMillis());
                upd.setString(3, profileId.toString());
                upd.setString(4, playerUuid.toString());
                if (upd.executeUpdate() != 1) throw new SQLException("profile account update failed");
            }

            // Audit
            try (PreparedStatement ins = conn.prepareStatement("""
                    INSERT INTO wpme_sb_transactions (transaction_id, account_key, counterparty_key, delta_minor, reason, created_at)
                    VALUES (?, ?, NULL, ?, ?, ?)
                    """)) {
                ins.setString(1, transactionId);
                ins.setString(2, accountKey);
                ins.setLong(3, delta);
                ins.setString(4, reason);
                ins.setLong(5, System.currentTimeMillis());
                ins.executeUpdate();
            }

            return new ProfileMutation(true, false, updated);
        }));
    }

    private static long selectBalance(Connection conn, UUID profileId, UUID playerUuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT balance_minor FROM wpme_sb_profile_accounts WHERE profile_id = ? AND player_uuid = ?")) {
            ps.setString(1, profileId.toString());
            ps.setString(2, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("missing profile account " + profileId + "/" + playerUuid);
                return rs.getLong(1);
            }
        }
    }

    public record ProfileMutation(boolean applied, boolean insufficient, long balance) {}
}
