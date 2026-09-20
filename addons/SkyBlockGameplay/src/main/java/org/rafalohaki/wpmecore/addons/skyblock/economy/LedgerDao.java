package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;


import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * SQL-authoritative, minor-unit ledger. Every mutation is one JDBC transaction.
 *
 * <p>This class is the stable surface of the ledger: it owns the schema DDL,
 * the domain value types (referenced across the addon and its tests) and the
 * schema creation. The implementation is delegated to three cohesive DAOs —
 * {@link BalanceDao} (accounts/transfers + audit), {@link QuestDao}
 * (quests/daily claims) and {@link InventoryDao} (inventory outbox) — with the
 * SQL primitives shared in {@link SqlSupport}.
 */
public final class LedgerDao {

    static final long MAX_BALANCE = 9_000_000_000_000_000L;

    public static final String ACCOUNTS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_accounts (
                account_key VARCHAR(48) NOT NULL PRIMARY KEY,
                balance_minor BIGINT NOT NULL DEFAULT 0,
                updated_at BIGINT NOT NULL
            )
            """;
    public static final String TRANSACTIONS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_transactions (
                transaction_id VARCHAR(128) NOT NULL PRIMARY KEY,
                account_key VARCHAR(48) NOT NULL,
                counterparty_key VARCHAR(48) NULL,
                delta_minor BIGINT NOT NULL,
                reason VARCHAR(64) NOT NULL,
                created_at BIGINT NOT NULL
            )
            """;
    public static final String QUESTS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_quest_progress (
                account_key VARCHAR(48) NOT NULL,
                period_key VARCHAR(16) NOT NULL,
                quest_id VARCHAR(48) NOT NULL,
                progress BIGINT NOT NULL DEFAULT 0,
                rewarded SMALLINT NOT NULL DEFAULT 0,
                last_batch_id VARCHAR(128) NULL,
                last_batch_owner_id VARCHAR(36) NULL,
                last_batch_increment BIGINT NULL,
                last_batch_target BIGINT NULL,
                last_batch_reward BIGINT NULL,
                last_batch_rewarded_now SMALLINT NULL,
                updated_at BIGINT NOT NULL,
                PRIMARY KEY (account_key, period_key, quest_id)
            )
            """;
    public static final String DAILY_CLAIMS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_daily_claims (
                owner_id VARCHAR(36) NOT NULL,
                period_key VARCHAR(16) NOT NULL,
                quest_id VARCHAR(48) NOT NULL,
                island_account_key VARCHAR(48) NOT NULL,
                created_at BIGINT NOT NULL,
                PRIMARY KEY (owner_id, period_key, quest_id)
            )
            """;
    public static final String INVENTORY_OUTBOX_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_inventory_outbox (
                operation_id VARCHAR(128) NOT NULL PRIMARY KEY,
                player_id VARCHAR(36) NOT NULL,
                operation_type VARCHAR(24) NOT NULL,
                transaction_id VARCHAR(128) NOT NULL UNIQUE,
                status VARCHAR(16) NOT NULL,
                item_payload MEDIUMBLOB NULL,
                auxiliary_type VARCHAR(24) NULL,
                auxiliary_key VARCHAR(128) NULL,
                auxiliary_value BIGINT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """;
    /**
     * Pozycje do usunięcia w ramach jednej operacji.
     *
     * <p>{@code custom_item_id} wchodzi w klucz główny, bo wszystkie surowce
     * SkyBlocka mają materiał bazowy {@code PAPER} — bez tego receptura z czterema
     * kryształami dałaby cztery kolidujące wiersze. Jest {@code NOT NULL DEFAULT ''},
     * a nie {@code NULL}, bo SQLite traktuje wartości NULL w indeksie unikalnym jako
     * różne, więc dopuszczenie NULL-a po cichu wyłączyłoby wymuszanie unikalności.
     *
     * <p>{@code baseline_count} nazywa się tak, a nie {@code baseline_plain}, bo dla
     * linii customowej zlicza stosy z metadanymi.
     */
    public static final String INVENTORY_OUTBOX_LINES_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_inventory_outbox_lines (
                operation_id VARCHAR(128) NOT NULL,
                material_key VARCHAR(64) NOT NULL,
                custom_item_id VARCHAR(64) NOT NULL DEFAULT '',
                remove_amount INTEGER NOT NULL,
                baseline_count INTEGER NOT NULL,
                PRIMARY KEY (operation_id, material_key, custom_item_id),
                FOREIGN KEY (operation_id)
                    REFERENCES wpme_sb_inventory_outbox(operation_id)
                    ON DELETE CASCADE
            )
            """;

    private final SqlService sql;
    private final BalanceDao balances;
    private final QuestDao quests;
    private final InventoryDao inventory;

    public LedgerDao(@NotNull SqlService sql) {
        this.sql = sql;
        this.balances = new BalanceDao(sql);
        this.quests = new QuestDao(sql);
        this.inventory = new InventoryDao(sql);
    }

    public @NotNull CompletableFuture<Void> createSchema() {
        return new SkyBlockSchemaMigrator(sql).migrate();
    }

    // ---- delegation: BalanceDao ----

    @NotNull CompletableFuture<Map<String, Long>> loadBalances() {
        return balances.loadBalances();
    }

    @NotNull CompletableFuture<Long> balance(@NotNull AccountKey account) {
        return balances.balance(account);
    }

    @NotNull CompletableFuture<BalancePair> currentBalances(
            @NotNull AccountKey source, @NotNull AccountKey target) {
        return balances.currentBalances(source, target);
    }

    @NotNull CompletableFuture<BalancePair> currentBalancesOrZero(
            @NotNull AccountKey source, @NotNull AccountKey target) {
        return balances.currentBalancesOrZero(source, target);
    }

    @NotNull CompletableFuture<Mutation> ensureAccount(@NotNull AccountKey account,
                                                        long initialBalance,
                                                        @NotNull String transactionId) {
        return balances.ensureAccount(account, initialBalance, transactionId);
    }

    @NotNull CompletableFuture<Mutation> mutate(@NotNull AccountKey account,
                                                 long initialBalance,
                                                 long delta,
                                                 @NotNull String transactionId,
                                                 @NotNull String reason) {
        return balances.mutate(account, initialBalance, delta, transactionId, reason);
    }

    @NotNull CompletableFuture<Optional<Mutation>> resolveMutationCommit(
            @NotNull AccountKey account, long delta,
            @NotNull String transactionId, @NotNull String reason) {
        return balances.resolveMutationCommit(account, delta, transactionId, reason);
    }

    @NotNull CompletableFuture<Transfer> transfer(@NotNull AccountKey source,
                                                   @NotNull AccountKey target,
                                                   long sourceInitialBalance,
                                                   long amount,
                                                   @NotNull String transactionId,
                                                   @NotNull String reason) {
        return balances.transfer(source, target, sourceInitialBalance, amount,
                transactionId, reason);
    }

    @NotNull CompletableFuture<Transfer> transferAll(@NotNull AccountKey source,
                                                      @NotNull AccountKey target,
                                                      long sourceInitialBalance,
                                                      @NotNull String transactionId,
                                                      @NotNull String reason) {
        return balances.transferAll(source, target, sourceInitialBalance,
                transactionId, reason);
    }

    @NotNull CompletableFuture<Optional<Transfer>> resolveTransferAllCommit(
            @NotNull AccountKey source, @NotNull AccountKey target,
            long sourceInitialBalance,
            @NotNull String transactionId, @NotNull String reason) {
        return balances.resolveTransferAllCommit(source, target, sourceInitialBalance,
                transactionId, reason);
    }

    @NotNull CompletableFuture<Optional<Transfer>> resolveTransferCommit(
            @NotNull AccountKey source, @NotNull AccountKey target, long amount,
            @NotNull String transactionId, @NotNull String reason) {
        return balances.resolveTransferCommit(source, target, amount, transactionId, reason);
    }

    // ---- delegation: QuestDao ----

    @NotNull CompletableFuture<PruneResult> pruneQuestHistory(@NotNull String periodCutoff) {
        return quests.pruneQuestHistory(periodCutoff);
    }

    /** LEDGER-1: retencja domkniętych wierszy outboxu ekwipunku. */
    @NotNull CompletableFuture<Integer> pruneSettledInventoryOperations(long cutoffMillis) {
        return inventory.pruneSettledInventoryOperations(cutoffMillis);
    }

    @NotNull CompletableFuture<Map<String, QuestStatus>> questStatuses(
            @NotNull AccountKey account, @NotNull String period) {
        return quests.questStatuses(account, period);
    }

    @NotNull CompletableFuture<Integer> countCompletedQuestsInPeriod(
            @NotNull AccountKey account, @NotNull String startPeriod, @NotNull String endPeriod) {
        return quests.countCompletedQuestsInPeriod(account, startPeriod, endPeriod);
    }

    @NotNull CompletableFuture<Boolean> isWeeklyMilestoneClaimed(
            @NotNull AccountKey account, @NotNull String weekKey) {
        return quests.isWeeklyMilestoneClaimed(account, weekKey);
    }

    @NotNull CompletableFuture<Boolean> claimWeeklyMilestone(
            @NotNull AccountKey account, @NotNull String weekKey) {
        return quests.claimWeeklyMilestone(account, weekKey);
    }

    /** Skład wyspy (ACTIVE) — odbiorcy powiadomienia o tygodniowym kamieniu milowym. */
    @NotNull CompletableFuture<Set<UUID>> activeIslandMemberIds(@NotNull UUID islandId) {
        return quests.activeIslandMemberIds(islandId);
    }

    public @NotNull CompletableFuture<QuestMutation> incrementQuest(
            @NotNull AccountKey account, @NotNull UUID ownerId,
            @NotNull String period, @NotNull String questId,
            long increment, long target, long reward,
            @NotNull String rewardTransactionId,
            @NotNull String batchTransactionId) {
        return quests.incrementQuest(account, ownerId, period, questId,
                increment, target, reward, rewardTransactionId, batchTransactionId);
    }

    @NotNull CompletableFuture<Optional<QuestMutation>> resolveQuestIncrementCommit(
            @NotNull AccountKey account, @NotNull UUID ownerId,
            @NotNull String period, @NotNull String questId,
            long increment, long target, long reward,
            @NotNull String batchTransactionId) {
        return quests.resolveQuestIncrementCommit(account, ownerId, period, questId,
                increment, target, reward, batchTransactionId);
    }

    // ---- delegation: InventoryDao ----

    @NotNull CompletableFuture<InventoryMutation> mutateWithInventoryOperation(
            @NotNull AccountKey account, long initialBalance, long delta,
            @NotNull String transactionId, @NotNull String reason,
            @NotNull InventoryOperation operation) {
        return inventory.mutateWithInventoryOperation(account, initialBalance, delta,
                transactionId, reason, operation);
    }

    @NotNull CompletableFuture<Optional<InventoryOperation>> inventoryOperation(
            @NotNull String operationId) {
        return inventory.inventoryOperation(operationId);
    }

    @NotNull CompletableFuture<List<InventoryOperation>> pendingInventoryOperations(
            @NotNull UUID playerId) {
        return inventory.pendingInventoryOperations(playerId);
    }

    @NotNull CompletableFuture<List<UUID>> pendingInventoryPlayers() {
        return inventory.pendingInventoryPlayers();
    }

    @NotNull CompletableFuture<Boolean> transitionInventoryOperation(
            @NotNull String operationId, @NotNull InventoryStatus expected,
            @NotNull InventoryStatus target) {
        return inventory.transitionInventoryOperation(operationId, expected, target);
    }

    // ---- value types ----

    public record Mutation(boolean applied, boolean insufficient, long balance) { }
    record BalancePair(long sourceBalance, long targetBalance) { }
    record Transfer(boolean applied, boolean insufficient, long sourceBalance,
                    long targetBalance, long transferredAmount) { }
    public record QuestStatus(long progress, boolean rewarded) { }
    public record QuestMutation(long progress, boolean rewarded, boolean rewardedNow, long accountBalance) { }
    public record PruneResult(int questProgressRows, int dailyClaimRows) { }

    enum InventoryOperationType {
        GRANT,
        REMOVE
    }

    /**
     * Stany operacji ekwipunku. Zapisywane po nazwie do {@code VARCHAR(16)}.
     *
     * <p>Kwarantanna ma dwie wartości, a nie jedną, bo przejście nadpisuje status
     * w miejscu, a tabela ma tylko jedną kolumnę statusu. Samo {@code QUARANTINED}
     * zgubiłoby informację, czy grant zdążył dojść do {@link #DELIVERED} — czyli
     * dokładnie tę, która rozstrzyga, czy przedmiot jest już u gracza.
     */
    enum InventoryStatus {
        PENDING,
        DELIVERED,
        COMPLETE,
        /** Odstawiona operacja, która przed odstawieniem czekała na dostarczenie. */
        QRTN_PENDING,
        /** Odstawiona operacja, która przed odstawieniem była już dostarczona. */
        QRTN_DELIVERED;

        boolean isQuarantined() {
            return this == QRTN_PENDING || this == QRTN_DELIVERED;
        }

        /**
         * Lista zezwoleń, celowo nie lista zakazów: przy liście zakazów każdy nowy
         * stan po cichu zabraniał właściwego przejścia, a odmowa leci wyjątkiem
         * z miejsca, z którego dzierżawa ekwipunku już się nie zwolni.
         *
         * <p>{@code switch} jest wyczerpujący, więc dopisanie stałej przestaje
         * kompilować zamiast milcząco zwracać {@code false}.
         */
        boolean canTransitionTo(@NotNull InventoryStatus target) {
            return switch (this) {
                case PENDING -> target == DELIVERED || target == COMPLETE
                        || target == QRTN_PENDING;
                case DELIVERED -> target == COMPLETE || target == QRTN_DELIVERED;
                case QRTN_PENDING -> target == PENDING || target == COMPLETE;
                case QRTN_DELIVERED -> target == DELIVERED || target == COMPLETE;
                case COMPLETE -> false;
            };
        }
    }

    /**
     * Jedna pozycja do usunięcia w ramach operacji.
     *
     * @param materialKey   klucz materiału bazowego, np. {@code minecraft:paper}
     * @param customItemId  identyfikator z CustomItems albo <b>pusty łańcuch</b> dla
     *                      zwykłego przedmiotu. Pusty, nie {@code null}: kolumna wchodzi
     *                      w klucz główny, a SQLite traktuje wartości NULL w indeksie
     *                      unikalnym jako różne, więc dopuszczenie NULL-a po cichu
     *                      wyłączyłoby wymuszanie unikalności
     * @param baselineCount ile sztuk gracz miał w chwili zdjęcia migawki, liczone tym
     *                      samym predykatem, którym potem liczy zbieżność
     */
    record InventoryLine(@NotNull String materialKey, @NotNull String customItemId,
                         int removeAmount, int baselineCount) {

        boolean isCustom() {
            return !customItemId.isEmpty();
        }
    }

    public record InventoryOperation(
            @NotNull String operationId,
            @NotNull UUID playerId,
            @NotNull InventoryOperationType type,
            @NotNull String transactionId,
            @NotNull InventoryStatus status,
            byte[] itemPayload,
            String auxiliaryType,
            String auxiliaryKey,
            Long auxiliaryValue,
            @NotNull List<InventoryLine> lines,
            long createdAt,
            long updatedAt) {

        public InventoryOperation {
            itemPayload = itemPayload == null ? null : itemPayload.clone();
            lines = List.copyOf(lines);
        }

        @Override
        public byte[] itemPayload() {
            return itemPayload == null ? null : itemPayload.clone();
        }
    }

    record InventoryMutation(@NotNull Mutation mutation, InventoryOperation operation) { }
}
