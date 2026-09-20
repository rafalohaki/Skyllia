package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.BalancePair;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.Mutation;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.Transfer;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Account and transfer domain of the ledger. Owns {@code wpme_sb_accounts} and
 * {@code wpme_sb_transactions}; every account mutation writes a durable audit
 * row. The Connection-level helpers at the bottom are package-private so that
 * {@link QuestDao} and {@link InventoryDao} can couple their own writes with
 * account/audit state inside one JDBC transaction.
 */
final class BalanceDao {

    private final SqlService sql;

    BalanceDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    @NotNull CompletableFuture<Map<String, Long>> loadBalances() {
        return sql.query("SELECT account_key, balance_minor FROM wpme_sb_accounts",
                row -> Map.entry(row.getString(1), row.getLong(2)))
                .thenApply(rows -> {
                    Map<String, Long> balances = new HashMap<>();
                    rows.forEach(row -> balances.put(row.getKey(), row.getValue()));
                    return balances;
                });
    }

    @NotNull CompletableFuture<Long> balance(@NotNull AccountKey account) {
        return sql.queryOne(
                        "SELECT balance_minor FROM wpme_sb_accounts WHERE account_key = ?",
                        row -> row.getLong(1), account.toString())
                .thenApply(value -> value.orElse(0L));
    }

    @NotNull CompletableFuture<BalancePair> currentBalances(
            @NotNull AccountKey source, @NotNull AccountKey target) {
        return sql.withConnection(connection -> new BalancePair(
                selectBalance(connection, source.toString()),
                selectBalance(connection, target.toString())));
    }

    @NotNull CompletableFuture<BalancePair> currentBalancesOrZero(
            @NotNull AccountKey source, @NotNull AccountKey target) {
        return sql.withConnection(connection -> new BalancePair(
                selectBalanceOrZero(connection, source.toString()),
                selectBalanceOrZero(connection, target.toString())));
    }

    @NotNull CompletableFuture<Mutation> ensureAccount(@NotNull AccountKey account,
                                                        long initialBalance,
                                                        @NotNull String transactionId) {
        return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () -> {
            boolean inserted = ensureAccountWithAudit(connection, account, initialBalance,
                    transactionId);
            return new Mutation(inserted, false, selectBalance(connection, account.toString()));
        }));
    }

    @NotNull CompletableFuture<Mutation> mutate(@NotNull AccountKey account,
                                                 long initialBalance,
                                                 long delta,
                                                 @NotNull String transactionId,
                                                 @NotNull String reason) {
        return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () -> {
            if (transactionExists(connection, transactionId)) {
                return new Mutation(false, false, selectBalance(connection, account.toString()));
            }
            ensureAccountWithAudit(connection, account, initialBalance,
                    "account:start:" + account);
            int changed;
            if (delta < 0L) {
                long amount = Math.negateExact(delta);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE wpme_sb_accounts
                        SET balance_minor = balance_minor - ?, updated_at = ?
                        WHERE account_key = ? AND balance_minor >= ?
                        """)) {
                    statement.setLong(1, amount);
                    statement.setLong(2, System.currentTimeMillis());
                    statement.setString(3, account.toString());
                    statement.setLong(4, amount);
                    changed = statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE wpme_sb_accounts
                        SET balance_minor = balance_minor + ?, updated_at = ?
                        WHERE account_key = ? AND balance_minor <= ?
                        """)) {
                    statement.setLong(1, delta);
                    statement.setLong(2, System.currentTimeMillis());
                    statement.setString(3, account.toString());
                    statement.setLong(4, LedgerDao.MAX_BALANCE - delta);
                    changed = statement.executeUpdate();
                }
            }
            if (changed != 1) {
                return new Mutation(false, delta < 0L,
                        selectBalance(connection, account.toString()));
            }
            insertTransaction(connection, transactionId, account.toString(), null, delta, reason);
            return new Mutation(true, false, selectBalance(connection, account.toString()));
        }));
    }

    /**
     * Resolves the outcome of an ordinary mutation whose completion signal was
     * lost after the JDBC action started. A matching audit row is proof that
     * the mutation committed; an absent row means there is no such proof.
     * Conflicting data is never treated as a retry or a success.
     */
    @NotNull CompletableFuture<Optional<Mutation>> resolveMutationCommit(
            @NotNull AccountKey account, long delta,
            @NotNull String transactionId, @NotNull String reason) {
        return sql.withConnection(connection -> {
            Optional<TransactionEntry> entry = selectTransaction(
                    connection, transactionId);
            if (entry.isEmpty()) {
                return Optional.empty();
            }
            requireTransaction(entry.get(), transactionId, account.toString(),
                    null, delta, reason);
            return Optional.of(new Mutation(true, false,
                    selectBalance(connection, account.toString())));
        });
    }

    @NotNull CompletableFuture<Transfer> transfer(@NotNull AccountKey source,
                                                   @NotNull AccountKey target,
                                                   long sourceInitialBalance,
                                                   long amount,
                                                   @NotNull String transactionId,
                                                   @NotNull String reason) {
        return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () -> {
            if (transactionExists(connection, transactionId)
                    || transactionExists(connection, transactionId + ":debit")) {
                return balances(connection, source, target, false, false);
            }
            ensureTransferAccounts(connection, source, sourceInitialBalance, target);
            lockAccountRows(connection, source, target);
            if (source.equals(target) || amount == 0L) {
                insertTransaction(connection, transactionId, source.toString(), target.toString(),
                        0L, reason);
                return balances(connection, source, target, true, false, 0L);
            }
            try (PreparedStatement withdraw = connection.prepareStatement("""
                    UPDATE wpme_sb_accounts
                    SET balance_minor = balance_minor - ?, updated_at = ?
                    WHERE account_key = ? AND balance_minor >= ?
                    """)) {
                withdraw.setLong(1, amount);
                withdraw.setLong(2, System.currentTimeMillis());
                withdraw.setString(3, source.toString());
                withdraw.setLong(4, amount);
                if (withdraw.executeUpdate() != 1) {
                    return balances(connection, source, target, false, true);
                }
            }
            try (PreparedStatement deposit = connection.prepareStatement("""
                    UPDATE wpme_sb_accounts
                    SET balance_minor = balance_minor + ?, updated_at = ?
                    WHERE account_key = ? AND balance_minor <= ?
                    """)) {
                deposit.setLong(1, amount);
                deposit.setLong(2, System.currentTimeMillis());
                deposit.setString(3, target.toString());
                deposit.setLong(4, LedgerDao.MAX_BALANCE - amount);
                if (deposit.executeUpdate() != 1) {
                    throw new SQLException("target account balance limit exceeded");
                }
            }
            insertTransaction(connection, transactionId + ":debit", source.toString(),
                    target.toString(), -amount, reason);
            insertTransaction(connection, transactionId + ":credit", target.toString(),
                    source.toString(), amount, reason);
            return balances(connection, source, target, true, false, amount);
        }));
    }

    /** Moves the authoritative current source balance in one SQL transaction. */
    @NotNull CompletableFuture<Transfer> transferAll(@NotNull AccountKey source,
                                                      @NotNull AccountKey target,
                                                      long sourceInitialBalance,
                                                      @NotNull String transactionId,
                                                      @NotNull String reason) {
        if (source.equals(target)) {
            throw new IllegalArgumentException("transfer-all source and target must differ");
        }
        return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () -> {
            Optional<Long> existing = transferAllAuditAmount(
                    connection, source, target, transactionId, reason);
            if (existing.isPresent()) {
                return balances(connection, source, target,
                        false, false, existing.get());
            }
            ensureTransferAccounts(connection, source, sourceInitialBalance, target);
            lockAccountRows(connection, source, target);
            long amount = selectBalance(connection, source.toString());
            requireValidBalance(amount, "source");
            if (amount == 0L) {
                return balances(connection, source, target, false, true);
            }
            long targetBalance = selectBalance(connection, target.toString());
            requireValidBalance(targetBalance, "target");
            if (targetBalance > LedgerDao.MAX_BALANCE - amount) {
                throw new SQLException("target account balance limit exceeded");
            }
            try (PreparedStatement withdraw = connection.prepareStatement("""
                    UPDATE wpme_sb_accounts
                    SET balance_minor = 0, updated_at = ?
                    WHERE account_key = ? AND balance_minor = ?
                    """)) {
                withdraw.setLong(1, System.currentTimeMillis());
                withdraw.setString(2, source.toString());
                withdraw.setLong(3, amount);
                if (withdraw.executeUpdate() != 1) {
                    throw new SQLException("source balance changed while locked");
                }
            }
            try (PreparedStatement deposit = connection.prepareStatement("""
                    UPDATE wpme_sb_accounts
                    SET balance_minor = balance_minor + ?, updated_at = ?
                    WHERE account_key = ? AND balance_minor <= ?
                    """)) {
                deposit.setLong(1, amount);
                deposit.setLong(2, System.currentTimeMillis());
                deposit.setString(3, target.toString());
                deposit.setLong(4, LedgerDao.MAX_BALANCE - amount);
                if (deposit.executeUpdate() != 1) {
                    throw new SQLException("target account balance limit exceeded");
                }
            }
            insertTransaction(connection, transactionId + ":debit", source.toString(),
                    target.toString(), Math.negateExact(amount), reason);
            insertTransaction(connection, transactionId + ":credit", target.toString(),
                    source.toString(), amount, reason);
            return balances(connection, source, target, true, false, amount);
        }));
    }

    /** Resolves a transfer-all by deriving its exact amount from the balanced audit pair. */
    @NotNull CompletableFuture<Optional<Transfer>> resolveTransferAllCommit(
            @NotNull AccountKey source, @NotNull AccountKey target,
            long sourceInitialBalance,
            @NotNull String transactionId, @NotNull String reason) {
        return sql.withConnection(connection -> {
            Optional<Long> amount = transferAllAuditAmount(
                    connection, source, target, transactionId, reason);
            if (amount.isPresent()) {
                return Optional.of(balances(connection, source, target,
                        true, false, amount.get()));
            }
            Optional<Long> storedSource = selectBalanceOptional(
                    connection, source.toString());
            if (storedSource.isEmpty() && sourceInitialBalance > 0L) {
                return Optional.empty();
            }
            long sourceBalance = storedSource.orElse(0L);
            if (sourceBalance == 0L) {
                return Optional.of(currentBalances(connection, source, target,
                        false, true, 0L));
            }
            return Optional.empty();
        });
    }

    /**
     * Resolves a transfer after an exceptional completion without replaying it.
     * Normal transfers require the balanced debit+credit pair; self/zero
     * transfers require their single zero-delta audit row. Any partial or
     * contradictory shape is an integrity failure.
     */
    @NotNull CompletableFuture<Optional<Transfer>> resolveTransferCommit(
            @NotNull AccountKey source, @NotNull AccountKey target, long amount,
            @NotNull String transactionId, @NotNull String reason) {
        return sql.withConnection(connection -> {
            Optional<TransactionEntry> base = selectTransaction(
                    connection, transactionId);
            Optional<TransactionEntry> debit = selectTransaction(
                    connection, transactionId + ":debit");
            Optional<TransactionEntry> credit = selectTransaction(
                    connection, transactionId + ":credit");

            if (base.isEmpty() && debit.isEmpty() && credit.isEmpty()) {
                return Optional.empty();
            }
            if (source.equals(target) || amount == 0L) {
                if (base.isEmpty() || debit.isPresent() || credit.isPresent()) {
                    throw new SQLException("partial or conflicting transfer audit "
                            + transactionId);
                }
                requireTransaction(base.get(), transactionId, source.toString(),
                        target.toString(), 0L, reason);
            } else {
                if (base.isPresent() || debit.isEmpty() || credit.isEmpty()) {
                    throw new SQLException("partial or conflicting transfer audit "
                            + transactionId);
                }
                requireTransaction(debit.get(), transactionId + ":debit",
                        source.toString(), target.toString(),
                        Math.negateExact(amount), reason);
                requireTransaction(credit.get(), transactionId + ":credit",
                        target.toString(), source.toString(), amount, reason);
            }
            return Optional.of(balances(connection, source, target,
                    true, false, amount));
        });
    }

    // ---- Connection-level helpers (package-private: used by QuestDao/InventoryDao) ----

    static boolean ensureAccountWithAudit(Connection connection, AccountKey account,
                                          long initialBalance, String transactionId)
            throws SQLException {
        boolean inserted = insertAccount(connection, account.toString(), initialBalance);
        if (inserted && initialBalance > 0L) {
            insertTransaction(connection, transactionId, account.toString(), null,
                    initialBalance, "account_start");
        }
        return inserted;
    }

    static void ensureTransferAccounts(
            Connection connection, AccountKey source, long sourceInitialBalance,
            AccountKey target) throws SQLException {
        if (source.equals(target)) {
            ensureAccountWithAudit(connection, source, sourceInitialBalance,
                    "account:start:" + source);
            return;
        }
        if (source.toString().compareTo(target.toString()) <= 0) {
            ensureAccountWithAudit(connection, source, sourceInitialBalance,
                    "account:start:" + source);
            ensureAccountWithAudit(connection, target, 0L,
                    "account:start:" + target);
        } else {
            ensureAccountWithAudit(connection, target, 0L,
                    "account:start:" + target);
            ensureAccountWithAudit(connection, source, sourceInitialBalance,
                    "account:start:" + source);
        }
    }

    static void insertTransaction(Connection connection, String id, String account,
                                  String counterparty, long delta, String reason)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO wpme_sb_transactions
                    (transaction_id, account_key, counterparty_key, delta_minor, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, account);
            statement.setString(3, counterparty);
            statement.setLong(4, delta);
            statement.setString(5, reason);
            statement.setLong(6, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    static boolean transactionExists(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM wpme_sb_transactions WHERE transaction_id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    static long selectBalance(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_minor FROM wpme_sb_accounts WHERE account_key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("missing ledger account " + key);
                }
                return result.getLong(1);
            }
        }
    }

    static int updateBalance(Connection connection, String account, long delta)
            throws SQLException {
        if (delta < 0L) {
            long amount = Math.negateExact(delta);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE wpme_sb_accounts
                    SET balance_minor = balance_minor - ?, updated_at = ?
                    WHERE account_key = ? AND balance_minor >= ?
                    """)) {
                statement.setLong(1, amount);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, account);
                statement.setLong(4, amount);
                return statement.executeUpdate();
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE wpme_sb_accounts
                SET balance_minor = balance_minor + ?, updated_at = ?
                WHERE account_key = ? AND balance_minor <= ?
                """)) {
            statement.setLong(1, delta);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, account);
            statement.setLong(4, LedgerDao.MAX_BALANCE - delta);
            return statement.executeUpdate();
        }
    }

    // ---- private helpers ----

    private static boolean insertAccount(Connection connection, String key, long initialBalance)
            throws SQLException {
        return SqlSupport.insertIgnoringConstraint(connection, """
                INSERT INTO wpme_sb_accounts (account_key, balance_minor, updated_at)
                VALUES (?, ?, ?)
                """, "ON CONFLICT (account_key) DO NOTHING", statement -> {
            statement.setString(1, key);
            statement.setLong(2, initialBalance);
            statement.setLong(3, System.currentTimeMillis());
        });
    }

    private static long selectBalanceOrZero(Connection connection, String key)
            throws SQLException {
        return selectBalanceOptional(connection, key).orElse(0L);
    }

    private static Optional<Long> selectBalanceOptional(Connection connection, String key)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance_minor FROM wpme_sb_accounts WHERE account_key = ?")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getLong(1)) : Optional.empty();
            }
        }
    }

    private static void requireValidBalance(long balance, String side) throws SQLException {
        if (balance < 0L || balance > LedgerDao.MAX_BALANCE) {
            throw new SQLException("corrupt " + side + " account balance " + balance);
        }
    }

    private static Optional<TransactionEntry> selectTransaction(
            Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT transaction_id, account_key, counterparty_key,
                       delta_minor, reason
                FROM wpme_sb_transactions
                WHERE transaction_id = ?
                """)) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new TransactionEntry(
                        result.getString("transaction_id"),
                        result.getString("account_key"),
                        result.getString("counterparty_key"),
                        result.getLong("delta_minor"),
                        result.getString("reason")));
            }
        }
    }

    private static void requireTransaction(
            TransactionEntry actual, String transactionId, String account,
            String counterparty, long delta, String reason) throws SQLException {
        if (!actual.transactionId().equals(transactionId)
                || !actual.account().equals(account)
                || !Objects.equals(actual.counterparty(), counterparty)
                || actual.delta() != delta
                || !actual.reason().equals(reason)) {
            throw new SQLException("conflicting ledger transaction "
                    + transactionId);
        }
    }

    private static Optional<Long> transferAllAuditAmount(
            Connection connection, AccountKey source, AccountKey target,
            String transactionId, String reason) throws SQLException {
        Optional<TransactionEntry> base = selectTransaction(connection, transactionId);
        Optional<TransactionEntry> debit = selectTransaction(
                connection, transactionId + ":debit");
        Optional<TransactionEntry> credit = selectTransaction(
                connection, transactionId + ":credit");
        if (base.isEmpty() && debit.isEmpty() && credit.isEmpty()) {
            return Optional.empty();
        }
        if (base.isPresent() || debit.isEmpty() || credit.isEmpty()) {
            throw new SQLException("partial or conflicting transfer-all audit "
                    + transactionId);
        }
        TransactionEntry debitEntry = debit.get();
        TransactionEntry creditEntry = credit.get();
        if (!debitEntry.transactionId().equals(transactionId + ":debit")
                || !debitEntry.account().equals(source.toString())
                || !Objects.equals(debitEntry.counterparty(), target.toString())
                || !debitEntry.reason().equals(reason)
                || debitEntry.delta() >= 0L
                || !creditEntry.transactionId().equals(transactionId + ":credit")
                || !creditEntry.account().equals(target.toString())
                || !Objects.equals(creditEntry.counterparty(), source.toString())
                || !creditEntry.reason().equals(reason)
                || creditEntry.delta() <= 0L
                || creditEntry.delta() > LedgerDao.MAX_BALANCE
                || debitEntry.delta() == Long.MIN_VALUE
                || Math.negateExact(debitEntry.delta()) != creditEntry.delta()) {
            throw new SQLException("conflicting transfer-all audit " + transactionId);
        }
        return Optional.of(creditEntry.delta());
    }

    private static void lockAccountRows(Connection connection,
                                        AccountKey first, AccountKey second)
            throws SQLException {
        if (first.toString().compareTo(second.toString()) <= 0) {
            lockAccountRow(connection, first);
            lockAccountRow(connection, second);
        } else {
            lockAccountRow(connection, second);
            lockAccountRow(connection, first);
        }
    }

    private static void lockAccountRow(Connection connection, AccountKey account)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE wpme_sb_accounts
                SET updated_at = CASE
                    WHEN updated_at = 9223372036854775807 THEN 0
                    ELSE updated_at + 1
                END
                WHERE account_key = ?
                """)) {
            statement.setString(1, account.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("missing ledger account while locking " + account);
            }
        }
    }

    private static Transfer balances(Connection connection, AccountKey source, AccountKey target,
                                     boolean applied, boolean insufficient) throws SQLException {
        return balances(connection, source, target, applied, insufficient, 0L);
    }

    private static Transfer balances(Connection connection, AccountKey source, AccountKey target,
                                     boolean applied, boolean insufficient,
                                     long transferredAmount) throws SQLException {
        return new Transfer(applied, insufficient,
                selectBalance(connection, source.toString()),
                selectBalance(connection, target.toString()), transferredAmount);
    }

    private static Transfer currentBalances(
            Connection connection, AccountKey source, AccountKey target,
            boolean applied, boolean insufficient, long transferredAmount)
            throws SQLException {
        long sourceBalance = selectBalanceOrZero(connection, source.toString());
        long targetBalance = selectBalanceOrZero(connection, target.toString());
        requireValidBalance(sourceBalance, "source");
        requireValidBalance(targetBalance, "target");
        return new Transfer(applied, insufficient, sourceBalance,
                targetBalance, transferredAmount);
    }

    private record TransactionEntry(@NotNull String transactionId,
                                    @NotNull String account,
                                    String counterparty,
                                    long delta,
                                    @NotNull String reason) { }
}
