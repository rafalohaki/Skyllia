package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.InventoryLine;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.InventoryMutation;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.InventoryOperation;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.InventoryOperationType;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.InventoryStatus;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.Mutation;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Inventory-outbox domain of the ledger ({@code wpme_sb_inventory_outbox} and
 * its material lines). {@link #mutateWithInventoryOperation} persists the
 * balance mutation and the inventory side effect in one JDBC transaction, so
 * it couples with {@link BalanceDao}'s Connection-level helpers.
 */
final class InventoryDao {

    /**
     * Statusy, których odtwarzanie po starcie nie ma sensu: zakończone oraz
     * odstawione do dead letter queue. Lista jest wyprowadzona z enuma, a nie
     * wpisana w dwa zapytania, żeby kolejny dopisany stan nie rozjechał ich po cichu.
     */
    private static final String SETTLED_STATUSES = settledStatuses();

    private static final String PENDING_FOR_PLAYER_SQL = """
            SELECT operation_id, player_id, operation_type, transaction_id, status,
                   item_payload, auxiliary_type, auxiliary_key, auxiliary_value,
                   created_at, updated_at
            FROM wpme_sb_inventory_outbox
            WHERE player_id = ? AND status NOT IN (%s)
            ORDER BY created_at, operation_id
            """.formatted(SETTLED_STATUSES);

    private static final String PENDING_PLAYERS_SQL = """
            SELECT DISTINCT player_id
            FROM wpme_sb_inventory_outbox
            WHERE status NOT IN (%s)
            """.formatted(SETTLED_STATUSES);

    private static String settledStatuses() {
        StringBuilder list = new StringBuilder();
        for (InventoryStatus status : InventoryStatus.values()) {
            if (status != InventoryStatus.COMPLETE && !status.isQuarantined()) {
                continue;
            }
            if (!list.isEmpty()) {
                list.append(", ");
            }
            list.append('\'').append(status.name()).append('\'');
        }
        return list.toString();
    }

    private final SqlService sql;

    InventoryDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    /**
     * Applies a player balance mutation and persists its inventory side effect
     * in the same database transaction. A caller that loses the completion
     * signal can resolve the operation by {@code operationId}; it must never
     * compensate based only on an exceptional future.
     */
    @NotNull CompletableFuture<InventoryMutation> mutateWithInventoryOperation(
            @NotNull AccountKey account, long initialBalance, long delta,
            @NotNull String transactionId, @NotNull String reason,
            @NotNull InventoryOperation operation) {
        return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () -> {
            Optional<InventoryOperation> existing = selectInventoryOperation(
                    connection, operation.operationId());
            if (BalanceDao.transactionExists(connection, transactionId)) {
                if (existing.isEmpty()) {
                    throw new SQLException("ledger transaction exists without inventory outbox "
                            + transactionId);
                }
                /*
                 * A2-25: gałąź idempotencji zwracała cudzy wiersz. Identyfikatory
                 * nagród wyspowych są wspólne dla wszystkich członków, a
                 * transactionId == operationId, więc drugi członek trafiał
                 * dokładnie tutaj i dostawał operację kolegi jako „swoją”.
                 * Fail-closed: odmowa zamiast cichego przejęcia.
                 */
                if (!existing.get().playerId().equals(operation.playerId())) {
                    throw new SQLException("inventory outbox " + operation.operationId()
                            + " belongs to " + existing.get().playerId()
                            + ", refusing to reuse it for " + operation.playerId());
                }
                return new InventoryMutation(
                        new Mutation(false, false,
                                BalanceDao.selectBalance(connection, account.toString())),
                        existing.get());
            }
            if (existing.isPresent()) {
                throw new SQLException("inventory outbox exists without ledger transaction "
                        + operation.operationId());
            }

            BalanceDao.ensureAccountWithAudit(connection, account, initialBalance,
                    "account:start:" + account);
            int changed = BalanceDao.updateBalance(connection, account.toString(), delta);
            if (changed != 1) {
                return new InventoryMutation(
                        new Mutation(false, delta < 0L,
                                BalanceDao.selectBalance(connection, account.toString())),
                        null);
            }
            BalanceDao.insertTransaction(connection, transactionId, account.toString(), null,
                    delta, reason);
            insertInventoryOperation(connection, operation);
            return new InventoryMutation(
                    new Mutation(true, false,
                            BalanceDao.selectBalance(connection, account.toString())),
                    operation);
        }));
    }

    @NotNull CompletableFuture<Optional<InventoryOperation>> inventoryOperation(
            @NotNull String operationId) {
        return sql.withConnection(connection ->
                selectInventoryOperation(connection, operationId));
    }

    @NotNull CompletableFuture<List<InventoryOperation>> pendingInventoryOperations(
            @NotNull UUID playerId) {
        return sql.withConnection(connection -> {
            List<InventoryOperation> operations = new ArrayList<>();
            try (PreparedStatement statement =
                         connection.prepareStatement(PENDING_FOR_PLAYER_SQL)) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        operations.add(mapInventoryOperation(
                                connection, result));
                    }
                }
            }
            return List.copyOf(operations);
        });
    }

    @NotNull CompletableFuture<List<UUID>> pendingInventoryPlayers() {
        return sql.query(PENDING_PLAYERS_SQL, row -> UUID.fromString(row.getString(1)));
    }

    /**
     * LEDGER-1: retencja outboxu. Przy {@code COMPLETE} zerowane były tylko
     * kolumny, a wiersz zostawał — tabela rosła bez końca (jedyna retencja
     * w kodzie dotyczyła questów). Kasujemy wyłącznie {@code COMPLETE}:
     * wiersze w kwarantannie są jedynym dowodem dla operatora i zostają.
     *
     * <p>Linie idą pierwsze, jawnie: {@code ON DELETE CASCADE} w SQLite działa
     * tylko przy {@code PRAGMA foreign_keys=ON}, a to ustawienie jest
     * per-połączenie.
     *
     * @param cutoffMillis znacznik czasu; wiersze starsze niż on znikają
     * @return liczba usuniętych operacji
     */
    @NotNull CompletableFuture<Integer> pruneSettledInventoryOperations(long cutoffMillis) {
        return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM wpme_sb_inventory_outbox_lines
                    WHERE operation_id IN (
                        SELECT operation_id FROM wpme_sb_inventory_outbox
                        WHERE status = ? AND updated_at < ?)
                    """)) {
                statement.setString(1, InventoryStatus.COMPLETE.name());
                statement.setLong(2, cutoffMillis);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM wpme_sb_inventory_outbox
                    WHERE status = ? AND updated_at < ?
                    """)) {
                statement.setString(1, InventoryStatus.COMPLETE.name());
                statement.setLong(2, cutoffMillis);
                return statement.executeUpdate();
            }
        }));
    }

    @NotNull CompletableFuture<Boolean> transitionInventoryOperation(
            @NotNull String operationId, @NotNull InventoryStatus expected,
            @NotNull InventoryStatus target) {
        return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () -> {
            int changed;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE wpme_sb_inventory_outbox
                    SET status = ?, updated_at = ?
                    WHERE operation_id = ? AND status = ?
                    """)) {
                statement.setString(1, target.name());
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, operationId);
                statement.setString(4, expected.name());
                changed = statement.executeUpdate();
            }
            if (target == InventoryStatus.COMPLETE) {
                /*
                 * The ledger transaction remains the permanent audit trail.
                 * Component blobs and material baselines are crash-recovery
                 * data only and are erased as soon as they are no longer
                 * needed.
                 */
                try (PreparedStatement clear = connection.prepareStatement("""
                        UPDATE wpme_sb_inventory_outbox
                        SET item_payload = NULL, auxiliary_type = NULL,
                            auxiliary_key = NULL, auxiliary_value = NULL
                        WHERE operation_id = ? AND status = 'COMPLETE'
                        """)) {
                    clear.setString(1, operationId);
                    clear.executeUpdate();
                }
                try (PreparedStatement delete = connection.prepareStatement("""
                        DELETE FROM wpme_sb_inventory_outbox_lines
                        WHERE operation_id = ?
                        """)) {
                    delete.setString(1, operationId);
                    delete.executeUpdate();
                }
            }
            return changed == 1;
        }));
    }

    // ---- private helpers ----

    private static void insertInventoryOperation(Connection connection,
                                                 InventoryOperation operation)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO wpme_sb_inventory_outbox
                    (operation_id, player_id, operation_type, transaction_id, status,
                     item_payload, auxiliary_type, auxiliary_key, auxiliary_value,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, operation.operationId());
            statement.setString(2, operation.playerId().toString());
            statement.setString(3, operation.type().name());
            statement.setString(4, operation.transactionId());
            statement.setString(5, operation.status().name());
            statement.setBytes(6, operation.itemPayload());
            statement.setString(7, operation.auxiliaryType());
            statement.setString(8, operation.auxiliaryKey());
            if (operation.auxiliaryValue() == null) {
                statement.setNull(9, java.sql.Types.BIGINT);
            } else {
                statement.setLong(9, operation.auxiliaryValue());
            }
            statement.setLong(10, operation.createdAt());
            statement.setLong(11, operation.updatedAt());
            statement.executeUpdate();
        }
        if (operation.lines().isEmpty()) {
            return;
        }
        // Jedno przygotowanie na całą operację, nie na linię: przy limicie 128
        // linii to była setka zapytań w transakcji trzymającej saldo.
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO wpme_sb_inventory_outbox_lines
                    (operation_id, material_key, custom_item_id,
                     remove_amount, baseline_count)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            for (InventoryLine line : operation.lines()) {
                statement.setString(1, operation.operationId());
                statement.setString(2, line.materialKey());
                statement.setString(3, line.customItemId());
                statement.setInt(4, line.removeAmount());
                statement.setInt(5, line.baselineCount());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static Optional<InventoryOperation> selectInventoryOperation(
            Connection connection, String operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, player_id, operation_type, transaction_id, status,
                       item_payload, auxiliary_type, auxiliary_key, auxiliary_value,
                       created_at, updated_at
                FROM wpme_sb_inventory_outbox
                WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapInventoryOperation(connection, result));
            }
        }
    }

    private static InventoryOperation mapInventoryOperation(
            Connection connection, ResultSet result) throws SQLException {
        String operationId = result.getString("operation_id");
        List<InventoryLine> lines = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT material_key, custom_item_id, remove_amount, baseline_count
                FROM wpme_sb_inventory_outbox_lines
                WHERE operation_id = ?
                ORDER BY material_key, custom_item_id
                """)) {
            statement.setString(1, operationId);
            try (ResultSet lineRows = statement.executeQuery()) {
                while (lineRows.next()) {
                    lines.add(new InventoryLine(lineRows.getString(1),
                            lineRows.getString(2), lineRows.getInt(3),
                            lineRows.getInt(4)));
                }
            }
        }
        long auxiliaryValue = result.getLong("auxiliary_value");
        Long nullableAuxiliaryValue = result.wasNull() ? null : auxiliaryValue;
        return new InventoryOperation(operationId,
                UUID.fromString(result.getString("player_id")),
                InventoryOperationType.valueOf(result.getString("operation_type")),
                result.getString("transaction_id"),
                InventoryStatus.valueOf(result.getString("status")),
                result.getBytes("item_payload"),
                result.getString("auxiliary_type"),
                result.getString("auxiliary_key"),
                nullableAuxiliaryValue,
                List.copyOf(lines),
                result.getLong("created_at"),
                result.getLong("updated_at"));
    }
}
