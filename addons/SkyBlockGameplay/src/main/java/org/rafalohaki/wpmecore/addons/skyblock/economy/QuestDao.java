package org.rafalohaki.wpmecore.addons.skyblock.economy;


import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.PruneResult;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.QuestMutation;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.QuestStatus;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Quest and daily-claim domain of the ledger ({@code wpme_sb_quest_progress},
 * {@code wpme_sb_daily_claims}). Rewarding a quest also credits the island
 * account and writes a durable audit row, so {@link #incrementQuest} couples
 * its transaction with {@link BalanceDao}'s Connection-level helpers.
 */
final class QuestDao {

    private final SqlService sql;

    QuestDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    @NotNull CompletableFuture<PruneResult> pruneQuestHistory(@NotNull String periodCutoff) {
        return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () -> {
            int progress;
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM wpme_sb_quest_progress WHERE period_key < ?
                    """)) {
                statement.setString(1, periodCutoff);
                progress = statement.executeUpdate();
            }
            int claims;
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM wpme_sb_daily_claims WHERE period_key < ?
                    """)) {
                statement.setString(1, periodCutoff);
                claims = statement.executeUpdate();
            }
            return new PruneResult(progress, claims);
        }));
    }

    @NotNull CompletableFuture<Map<String, QuestStatus>> questStatuses(@NotNull AccountKey account,
                                                                        @NotNull String period) {
        return sql.query("""
                        SELECT quest_id, progress, rewarded
                        FROM wpme_sb_quest_progress
                        WHERE account_key = ? AND period_key = ?
                        """,
                row -> Map.entry(row.getString(1),
                        new QuestStatus(row.getLong(2), row.getInt(3) != 0)),
                account.toString(), period).thenApply(rows -> {
                    Map<String, QuestStatus> result = new HashMap<>();
                    rows.forEach(row -> result.put(row.getKey(), row.getValue()));
                    return result;
                });
    }

    @NotNull CompletableFuture<Integer> countCompletedQuestsInPeriod(
            @NotNull AccountKey account, @NotNull String startPeriod, @NotNull String endPeriod) {
        return sql.query("""
                SELECT COUNT(*)
                FROM wpme_sb_quest_progress
                WHERE account_key = ? AND period_key >= ? AND period_key <= ? AND rewarded = 1
                """,
                row -> row.getInt(1),
                account.toString(), startPeriod, endPeriod)
                .thenApply(rows -> rows.isEmpty() ? 0 : rows.getFirst());
    }

    @NotNull CompletableFuture<Boolean> isWeeklyMilestoneClaimed(
            @NotNull AccountKey account, @NotNull String weekKey) {
        return sql.query("""
                SELECT 1
                FROM wpme_sb_weekly_milestones
                WHERE account_key = ? AND week_key = ?
                """,
                row -> true,
                account.toString(), weekKey)
                .thenApply(rows -> !rows.isEmpty());
    }

    /**
     * Skład wyspy (status ACTIVE) — odbiorcy powiadomienia o tygodniowym kamieniu
     * milowym. Powiadomienie musi iść z bazy, a nie z migawki cache Skyllii: po
     * skasowaniu i odtworzeniu wyspy o tym samym {@code island_id} ten cache bywa
     * pusty, więc filtr po nim nie wysyłał komunikatu NIKOMU (E2E 2026-09-10:
     * Lotos szedł outboxem, więc defekt był cichy).
     */
    @NotNull CompletableFuture<Set<UUID>> activeIslandMemberIds(@NotNull UUID islandId) {
        return sql.query("""
                        SELECT player_uuid
                        FROM wpme_sb_island_membership
                        WHERE island_id = ? AND status = 'ACTIVE'
                        """,
                row -> UUID.fromString(row.getString(1)),
                islandId.toString())
                .thenApply(Set::copyOf);
    }

    @NotNull CompletableFuture<Boolean> claimWeeklyMilestone(
            @NotNull AccountKey account, @NotNull String weekKey) {
        return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () ->
                SqlSupport.insertIgnoringConstraint(connection, """
                        INSERT INTO wpme_sb_weekly_milestones (account_key, week_key, claimed_at)
                        VALUES (?, ?, ?)
                        """, "ON CONFLICT (account_key, week_key) DO NOTHING",
                        statement -> {
                            statement.setString(1, account.toString());
                            statement.setString(2, weekKey);
                            statement.setLong(3, System.currentTimeMillis());
                        })));
    }

    @NotNull CompletableFuture<QuestMutation> incrementQuest(
            @NotNull AccountKey account, @NotNull UUID ownerId,
            @NotNull String period, @NotNull String questId,
            long increment, long target, long reward,
            @NotNull String rewardTransactionId,
            @NotNull String batchTransactionId) {
        return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () -> {
            BalanceDao.ensureAccountWithAudit(connection, account, 0L,
                    "account:start:" + account);
            insertQuest(connection, account.toString(), period, questId);
            lockQuestRow(connection, account.toString(), period, questId);
            Optional<QuestBatch> existingBatch = selectQuestBatch(
                    connection, account.toString(), period, questId);
            if (existingBatch.isPresent()
                    && existingBatch.get().batchId().equals(batchTransactionId)) {
                requireQuestBatch(existingBatch.get(), batchTransactionId, ownerId,
                        increment, target, reward);
                QuestStatus existing = selectQuest(connection, account.toString(),
                        period, questId);
                return new QuestMutation(existing.progress(), existing.rewarded(),
                        existingBatch.get().rewardedNow(),
                        BalanceDao.selectBalance(connection, account.toString()));
            }
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE wpme_sb_quest_progress
                    SET progress = CASE
                            WHEN rewarded <> 0 THEN progress
                            WHEN progress + ? > ? THEN ?
                            ELSE progress + ?
                        END,
                        updated_at = ?
                    WHERE account_key = ? AND period_key = ? AND quest_id = ?
                    """)) {
                update.setLong(1, increment);
                update.setLong(2, target);
                update.setLong(3, target);
                update.setLong(4, increment);
                update.setLong(5, System.currentTimeMillis());
                update.setString(6, account.toString());
                update.setString(7, period);
                update.setString(8, questId);
                update.executeUpdate();
            }
            QuestStatus status = selectQuest(connection, account.toString(), period, questId);
            boolean rewardedNow = false;
            if (!status.rewarded() && status.progress() >= target) {
                try (PreparedStatement claim = connection.prepareStatement("""
                        UPDATE wpme_sb_quest_progress SET rewarded = 1, updated_at = ?
                        WHERE account_key = ? AND period_key = ? AND quest_id = ? AND rewarded = 0
                        """)) {
                    claim.setLong(1, System.currentTimeMillis());
                    claim.setString(2, account.toString());
                    claim.setString(3, period);
                    claim.setString(4, questId);
                    boolean questClaimed = claim.executeUpdate() == 1;
                    if (questClaimed) {
                        rewardedNow = insertDailyClaim(connection, ownerId, period, questId,
                                account.toString());
                    }
                }
                if (rewardedNow) {
                    if (BalanceDao.transactionExists(connection, rewardTransactionId)) {
                        throw new SQLException("daily claim exists with a duplicate reward audit "
                                + rewardTransactionId);
                    }
                    try (PreparedStatement deposit = connection.prepareStatement("""
                            UPDATE wpme_sb_accounts
                            SET balance_minor = balance_minor + ?, updated_at = ?
                            WHERE account_key = ? AND balance_minor <= ?
                            """)) {
                        deposit.setLong(1, reward);
                        deposit.setLong(2, System.currentTimeMillis());
                        deposit.setString(3, account.toString());
                        deposit.setLong(4, LedgerDao.MAX_BALANCE - reward);
                        if (deposit.executeUpdate() != 1) {
                            throw new SQLException("quest reward would exceed balance limit");
                        }
                    }
                    BalanceDao.insertTransaction(connection, rewardTransactionId,
                            account.toString(), null, reward, "daily_quest");
                }
                status = new QuestStatus(status.progress(), true);
            }
            markQuestBatch(connection, account.toString(), period, questId,
                    batchTransactionId, ownerId, increment, target, reward, rewardedNow);
            return new QuestMutation(status.progress(), status.rewarded(), rewardedNow,
                    BalanceDao.selectBalance(connection, account.toString()));
        }));
    }

    /** Resolves a quest batch whose durable COMMIT acknowledgement was lost. */
    @NotNull CompletableFuture<Optional<QuestMutation>> resolveQuestIncrementCommit(
            @NotNull AccountKey account, @NotNull UUID ownerId,
            @NotNull String period, @NotNull String questId,
            long increment, long target, long reward,
            @NotNull String batchTransactionId) {
        return sql.withConnection(connection -> {
            Optional<QuestBatch> batch = selectQuestBatch(
                    connection, account.toString(), period, questId);
            if (batch.isEmpty()) {
                return Optional.empty();
            }
            if (!batch.get().batchId().equals(batchTransactionId)) {
                return Optional.empty();
            }
            requireQuestBatch(batch.get(), batchTransactionId, ownerId,
                    increment, target, reward);
            QuestStatus status = selectQuest(connection, account.toString(), period, questId);
            return Optional.of(new QuestMutation(status.progress(), status.rewarded(),
                    batch.get().rewardedNow(),
                    BalanceDao.selectBalance(connection, account.toString())));
        });
    }

    // ---- private helpers ----

    private static void insertQuest(Connection connection, String account, String period,
                                    String quest) throws SQLException {
        SqlSupport.insertIgnoringConstraint(connection, """
                INSERT INTO wpme_sb_quest_progress
                    (account_key, period_key, quest_id, progress, rewarded, updated_at)
                VALUES (?, ?, ?, 0, 0, ?)
                """, "ON CONFLICT (account_key, period_key, quest_id) DO NOTHING",
                statement -> {
            statement.setString(1, account);
            statement.setString(2, period);
            statement.setString(3, quest);
            statement.setLong(4, System.currentTimeMillis());
        });
    }

    private static void lockQuestRow(Connection connection, String account,
                                     String period, String quest) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE wpme_sb_quest_progress
                SET updated_at = CASE
                    WHEN updated_at = 9223372036854775807 THEN 0
                    ELSE updated_at + 1
                END
                WHERE account_key = ? AND period_key = ? AND quest_id = ?
                """)) {
            statement.setString(1, account);
            statement.setString(2, period);
            statement.setString(3, quest);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("missing quest progress row while locking");
            }
        }
    }

    private static Optional<QuestBatch> selectQuestBatch(
            Connection connection, String account, String period, String quest)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT last_batch_id, last_batch_owner_id, last_batch_increment,
                       last_batch_target, last_batch_reward, last_batch_rewarded_now
                FROM wpme_sb_quest_progress
                WHERE account_key = ? AND period_key = ? AND quest_id = ?
                """)) {
            statement.setString(1, account);
            statement.setString(2, period);
            statement.setString(3, quest);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                String batchId = result.getString("last_batch_id");
                if (batchId == null) {
                    return Optional.empty();
                }
                String owner = result.getString("last_batch_owner_id");
                long increment = result.getLong("last_batch_increment");
                boolean missingIncrement = result.wasNull();
                long target = result.getLong("last_batch_target");
                boolean missingTarget = result.wasNull();
                long reward = result.getLong("last_batch_reward");
                boolean missingReward = result.wasNull();
                int rewardedNow = result.getInt("last_batch_rewarded_now");
                boolean missingRewardedNow = result.wasNull();
                if (owner == null || missingIncrement || missingTarget
                        || missingReward || missingRewardedNow) {
                    throw new SQLException("incomplete quest batch marker " + batchId);
                }
                return Optional.of(new QuestBatch(batchId, UUID.fromString(owner),
                        increment, target, reward, rewardedNow != 0));
            } catch (IllegalArgumentException malformedOwner) {
                throw new SQLException("malformed quest batch owner", malformedOwner);
            }
        }
    }

    private static void markQuestBatch(
            Connection connection, String account, String period, String quest,
            String batchId, UUID ownerId, long increment, long target, long reward,
            boolean rewardedNow) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE wpme_sb_quest_progress
                SET last_batch_id = ?, last_batch_owner_id = ?,
                    last_batch_increment = ?, last_batch_target = ?,
                    last_batch_reward = ?, last_batch_rewarded_now = ?, updated_at = ?
                WHERE account_key = ? AND period_key = ? AND quest_id = ?
                """)) {
            statement.setString(1, batchId);
            statement.setString(2, ownerId.toString());
            statement.setLong(3, increment);
            statement.setLong(4, target);
            statement.setLong(5, reward);
            statement.setInt(6, rewardedNow ? 1 : 0);
            statement.setLong(7, System.currentTimeMillis());
            statement.setString(8, account);
            statement.setString(9, period);
            statement.setString(10, quest);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("missing quest progress row while marking batch");
            }
        }
    }

    private static void requireQuestBatch(
            QuestBatch actual, String batchId, UUID ownerId,
            long increment, long target, long reward) throws SQLException {
        if (!actual.batchId().equals(batchId)
                || !actual.ownerId().equals(ownerId)
                || actual.increment() != increment
                || actual.target() != target
                || actual.reward() != reward) {
            throw new SQLException("conflicting quest batch " + batchId);
        }
    }

    private static boolean insertDailyClaim(Connection connection, UUID ownerId,
                                            String period, String quest,
                                            String islandAccount) throws SQLException {
        return SqlSupport.insertIgnoringConstraint(connection, """
                INSERT INTO wpme_sb_daily_claims
                    (owner_id, period_key, quest_id, island_account_key, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, "ON CONFLICT (owner_id, period_key, quest_id) DO NOTHING",
                statement -> {
            statement.setString(1, ownerId.toString());
            statement.setString(2, period);
            statement.setString(3, quest);
            statement.setString(4, islandAccount);
            statement.setLong(5, System.currentTimeMillis());
        });
    }

    private static QuestStatus selectQuest(Connection connection, String account, String period,
                                           String quest) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT progress, rewarded FROM wpme_sb_quest_progress
                WHERE account_key = ? AND period_key = ? AND quest_id = ?
                """)) {
            statement.setString(1, account);
            statement.setString(2, period);
            statement.setString(3, quest);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("missing quest progress row");
                }
                return new QuestStatus(result.getLong(1), result.getInt(2) != 0);
            }
        }
    }

    private record QuestBatch(@NotNull String batchId, @NotNull UUID ownerId,
                              long increment, long target, long reward,
                              boolean rewardedNow) { }
}
