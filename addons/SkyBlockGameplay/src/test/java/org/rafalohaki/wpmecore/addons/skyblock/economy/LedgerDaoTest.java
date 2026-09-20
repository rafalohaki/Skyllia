package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerDaoTest {

    /** Canonical OneBlock chapter ids (oneblock.yml order), used for migration 7. */
    private static final List<String> PHASE_IDS = List.of(
            "poczatek", "podziemia", "zima", "pustynia", "dzungla", "pieklo", "kres");

    private TestSqlService sql;
    private LedgerDao dao;

    @BeforeEach
    void setUp() throws Exception {
        sql = new TestSqlService();
        dao = new LedgerDao(sql);
        dao.createSchema().join();
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void mutationsAreDurableAuditedAndIdempotent() {
        UUID playerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        AccountKey player = AccountKey.player(playerId);

        LedgerDao.Mutation created = dao.ensureAccount(player, 100L,
                "account:start:" + player).join();
        LedgerDao.Mutation repeated = dao.ensureAccount(player, 100L,
                "account:start:" + player).join();
        LedgerDao.Mutation withdrawn = dao.mutate(player, 100L, -30L,
                "test:withdraw", "test").join();
        LedgerDao.Mutation retry = dao.mutate(player, 100L, -30L,
                "test:withdraw", "test").join();
        LedgerDao.Mutation insufficient = dao.mutate(player, 100L, -80L,
                "test:too_much", "test").join();

        assertTrue(created.applied());
        assertFalse(repeated.applied());
        assertEquals(100L, repeated.balance());
        assertTrue(withdrawn.applied());
        assertEquals(70L, withdrawn.balance());
        assertFalse(retry.applied());
        assertEquals(70L, retry.balance());
        assertTrue(insufficient.insufficient());
        assertEquals(70L, insufficient.balance());
        assertEquals(70L, dao.balance(player).join());
        assertEquals(0L, dao.balance(AccountKey.island(UUID.fromString(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))).join());
        assertEquals(2L, count("SELECT COUNT(*) FROM wpme_sb_transactions"));
    }

    @Test
    void transferWritesBalancedEntriesAndRetryCannotMoveMoneyTwice() {
        AccountKey player = AccountKey.player(UUID.fromString(
                "22222222-2222-2222-2222-222222222222"));
        AccountKey island = AccountKey.island(UUID.fromString(
                "33333333-3333-3333-3333-333333333333"));
        dao.ensureAccount(player, 100L, "account:start:" + player).join();

        LedgerDao.Transfer moved = dao.transfer(player, island, 100L, 40L,
                "test:transfer", "bank_deposit").join();
        LedgerDao.Transfer retry = dao.transfer(player, island, 100L, 40L,
                "test:transfer", "bank_deposit").join();

        assertTrue(moved.applied());
        assertEquals(60L, moved.sourceBalance());
        assertEquals(40L, moved.targetBalance());
        assertFalse(retry.applied());
        assertEquals(60L, retry.sourceBalance());
        assertEquals(40L, retry.targetBalance());
        assertEquals(0L, scalar("""
                SELECT COALESCE(SUM(delta_minor), 0) FROM wpme_sb_transactions
                WHERE transaction_id IN ('test:transfer:debit', 'test:transfer:credit')
                """));
        assertEquals(2L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id LIKE 'test:transfer:%'
                """));
    }

    @Test
    void transferAllMovesAuthoritativeBalanceAndReportsTheExactAmount() {
        AccountKey player = AccountKey.player(UUID.fromString(
                "22222222-2222-2222-2222-000000000001"));
        AccountKey island = AccountKey.island(UUID.fromString(
                "33333333-3333-3333-3333-000000000001"));
        dao.ensureAccount(player, 100L, "account:start:" + player).join();
        dao.mutate(player, 100L, 25L, "test:external-credit", "test").join();

        LedgerDao.Transfer moved = dao.transferAll(player, island, 100L,
                "test:transfer-all", "bank_deposit_all").join();
        LedgerDao.Transfer retry = dao.transferAll(player, island, 100L,
                "test:transfer-all", "bank_deposit_all").join();

        assertTrue(moved.applied());
        assertEquals(125L, moved.transferredAmount());
        assertEquals(0L, moved.sourceBalance());
        assertEquals(125L, moved.targetBalance());
        assertFalse(retry.applied());
        assertEquals(125L, retry.transferredAmount());
        assertEquals(0L, retry.sourceBalance());
        assertEquals(125L, retry.targetBalance());
    }

    @Test
    void transferAllRollsBackWhenTargetWouldExceedBalanceLimit() {
        AccountKey source = AccountKey.player(UUID.fromString(
                "22222222-2222-2222-2222-000000000002"));
        AccountKey target = AccountKey.island(UUID.fromString(
                "33333333-3333-3333-3333-000000000002"));
        dao.ensureAccount(source, 50L, "account:start:" + source).join();
        dao.ensureAccount(target, LedgerDao.MAX_BALANCE,
                "account:start:" + target).join();

        assertThrows(CompletionException.class, () -> dao.transferAll(
                source, target, 50L, "test:transfer-all-overflow",
                "bank_deposit_all").join());
        assertEquals(50L, dao.balance(source).join());
        assertEquals(LedgerDao.MAX_BALANCE, dao.balance(target).join());
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id LIKE 'test:transfer-all-overflow:%'
                """));
    }

    @Test
    void transferAllEmptyNoOpIsResolvedFromAuthoritativeStateWithoutAuditGrowth() {
        AccountKey island = AccountKey.island(UUID.fromString(
                "33333333-3333-3333-3333-000000000003"));
        AccountKey player = AccountKey.player(UUID.fromString(
                "22222222-2222-2222-2222-000000000003"));
        dao.ensureAccount(island, 0L, "account:start:" + island).join();

        LedgerDao.Transfer empty = dao.transferAll(island, player, 0L,
                "test:transfer-all-empty", "bank_withdraw_all").join();
        LedgerDao.Transfer resolved = dao.resolveTransferAllCommit(island, player,
                0L, "test:transfer-all-empty", "bank_withdraw_all")
                .join().orElseThrow();

        assertFalse(empty.applied());
        assertTrue(empty.insufficient());
        assertFalse(resolved.applied());
        assertTrue(resolved.insufficient());
        assertEquals(0L, resolved.transferredAmount());
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id LIKE 'test:transfer-all-empty%'
                """));
    }

    @Test
    void transferAllRejectsCorruptSourceBalanceBeforeMutation() {
        AccountKey source = AccountKey.player(UUID.fromString(
                "22222222-2222-2222-2222-000000000004"));
        AccountKey target = AccountKey.island(UUID.fromString(
                "33333333-3333-3333-3333-000000000004"));
        dao.ensureAccount(source, 10L, "account:start:" + source).join();
        sql.update("UPDATE wpme_sb_accounts SET balance_minor = -1 WHERE account_key = ?",
                source.toString()).join();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> dao.transferAll(source, target, 10L,
                        "test:transfer-all-corrupt", "bank_deposit_all").join());
        SQLException corruption = assertInstanceOf(SQLException.class, failure.getCause());
        assertTrue(corruption.getMessage().contains("corrupt source account balance"));
        assertEquals(-1L, dao.balance(source).join());
        assertEquals(0L, dao.balance(target).join());
    }

    @Test
    void questProgressAndRewardCommitInTheSameTransactionOnlyOnce() {
        AccountKey island = AccountKey.island(UUID.fromString(
                "44444444-4444-4444-4444-444444444444"));
        UUID ownerId = UUID.fromString("44444444-4444-4444-4444-000000000001");

        LedgerDao.QuestMutation partial = dao.incrementQuest(island, ownerId, "2026-07-23",
                "miner", 2L, 3L, 50L, "quest:reward", "quest:batch:partial").join();
        LedgerDao.QuestMutation complete = dao.incrementQuest(island, ownerId, "2026-07-23",
                "miner", 1L, 3L, 50L, "quest:reward", "quest:batch:complete").join();
        LedgerDao.QuestMutation retry = dao.incrementQuest(island, ownerId, "2026-07-23",
                "miner", 10L, 3L, 50L, "quest:reward", "quest:batch:retry").join();

        assertFalse(partial.rewarded());
        assertTrue(complete.rewarded());
        assertTrue(complete.rewardedNow());
        assertEquals(50L, complete.accountBalance());
        assertTrue(retry.rewarded());
        assertFalse(retry.rewardedNow());
        assertEquals(50L, retry.accountBalance());
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id = 'quest:reward'
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE reason = 'quest_progress'
                """));
    }

    @Test
    void serviceResolvesQuestBatchWhenCommitAcknowledgementWasLost() throws Exception {
        UUID islandId = UUID.fromString("44444444-4444-4444-4444-000000000021");
        UUID ownerId = UUID.fromString("44444444-4444-4444-4444-000000000022");
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        sql.failAfterNextConnectionAction(
                new IllegalStateException("simulated lost quest acknowledgement"));

        LedgerDao.QuestMutation resolved = ledger.incrementQuest(islandId, ownerId,
                "2026-07-23", "miner", 3L, 3L, 50L,
                "quest:progress:lost-ack").join();

        assertEquals(3L, resolved.progress());
        assertTrue(resolved.rewarded());
        assertTrue(resolved.rewardedNow());
        assertEquals(50L, resolved.accountBalance());
        assertEquals(50L, ledger.islandBalance(islandId));
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE reason = 'daily_quest'
                """));
    }

    @Test
    void retryWithSameQuestBatchAfterAmbiguousResolverCannotApplyTwice() throws Exception {
        UUID islandId = UUID.fromString("44444444-4444-4444-4444-000000000023");
        UUID ownerId = UUID.fromString("44444444-4444-4444-4444-000000000024");
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        sql.failAfterNextAndBeforeFollowingConnectionActions(
                new IllegalStateException("simulated lost quest acknowledgement"),
                new IllegalStateException("simulated resolver outage"));

        assertThrows(CompletionException.class, () -> ledger.incrementQuest(
                islandId, ownerId, "2026-07-23", "miner", 2L, 10L, 50L,
                "quest:progress:ambiguous-retry").join());

        LedgerDao.QuestMutation retry = ledger.incrementQuest(
                islandId, ownerId, "2026-07-23", "miner", 2L, 10L, 50L,
                "quest:progress:ambiguous-retry").join();
        assertEquals(2L, retry.progress());
        assertFalse(retry.rewarded());
        assertFalse(retry.rewardedNow());
    }

    @Test
    void sameQuestBatchIdWithDifferentPayloadFailsClosed() {
        AccountKey island = AccountKey.island(UUID.fromString(
                "44444444-4444-4444-4444-000000000025"));
        UUID ownerId = UUID.fromString("44444444-4444-4444-4444-000000000026");
        String batchId = "quest:progress:payload-conflict";
        dao.incrementQuest(island, ownerId, "2026-07-23", "miner",
                2L, 10L, 50L, "quest:payload-conflict", batchId).join();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> dao.incrementQuest(island, ownerId, "2026-07-23", "miner",
                        3L, 10L, 50L, "quest:payload-conflict", batchId).join());
        SQLException conflict = assertInstanceOf(SQLException.class, failure.getCause());
        assertTrue(conflict.getMessage().contains("conflicting quest batch"));
        assertEquals(2L, dao.questStatuses(island, "2026-07-23")
                .join().get("miner").progress());
    }

    @Test
    void dailyRewardClaimFollowsOwnerAcrossIslandReset() {
        UUID ownerId = UUID.fromString("44444444-4444-4444-4444-000000000002");
        AccountKey firstIsland = AccountKey.island(UUID.fromString(
                "44444444-4444-4444-4444-000000000003"));
        AccountKey replacementIsland = AccountKey.island(UUID.fromString(
                "44444444-4444-4444-4444-000000000004"));
        String transaction = "quest:2026-07-23:miner:" + ownerId;

        LedgerDao.QuestMutation first = dao.incrementQuest(firstIsland, ownerId,
                "2026-07-23", "miner", 3L, 3L, 50L, transaction,
                "quest:batch:first-island").join();
        LedgerDao.QuestMutation replacement = dao.incrementQuest(replacementIsland, ownerId,
                "2026-07-23", "miner", 3L, 3L, 50L, transaction,
                "quest:batch:replacement-island").join();

        assertTrue(first.rewardedNow());
        assertEquals(50L, first.accountBalance());
        assertTrue(replacement.rewarded());
        assertFalse(replacement.rewardedNow());
        assertEquals(0L, replacement.accountBalance());
        assertEquals(1L, count("SELECT COUNT(*) FROM wpme_sb_daily_claims"));
        assertEquals(1L, count("SELECT COUNT(*) FROM wpme_sb_transactions "
                + "WHERE transaction_id = '" + transaction + "'"));
    }

    @Test
    void retentionPrunesExpiredQuestState() {
        UUID ownerId = UUID.fromString("44444444-4444-4444-4444-000000000031");
        AccountKey oldIsland = AccountKey.island(UUID.fromString(
                "44444444-4444-4444-4444-000000000032"));
        AccountKey currentIsland = AccountKey.island(UUID.fromString(
                "44444444-4444-4444-4444-000000000033"));
        dao.incrementQuest(oldIsland, ownerId, "2026-01-01", "miner",
                1L, 1L, 10L, "quest:old:reward", "quest:old:batch").join();
        dao.incrementQuest(currentIsland, ownerId, "2026-07-23", "miner",
                1L, 2L, 10L, "quest:current:reward", "quest:current:batch").join();

        LedgerDao.PruneResult pruned = dao.pruneQuestHistory("2026-07-01").join();

        assertEquals(1, pruned.questProgressRows());
        assertEquals(1, pruned.dailyClaimRows());
        assertEquals(1L, count("SELECT COUNT(*) FROM wpme_sb_quest_progress"));
        assertEquals(0L, count("SELECT COUNT(*) FROM wpme_sb_daily_claims"));
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id = 'quest:old:reward'
                """));
    }

    @Test
    void inventoryGrantDebitAndPendingReceiptCommitAtomicallyAndRetryIsIdempotent() {
        UUID playerId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        AccountKey player = AccountKey.player(playerId);
        LedgerDao.InventoryOperation operation = operation(
                "shop:buy:55555555", playerId,
                LedgerDao.InventoryOperationType.GRANT, new byte[]{1, 2, 3}, List.of());

        LedgerDao.InventoryMutation first = dao.mutateWithInventoryOperation(
                player, 100L, -25L, operation.transactionId(),
                "server_shop_buy", operation).join();
        LedgerDao.InventoryMutation retry = dao.mutateWithInventoryOperation(
                player, 100L, -25L, operation.transactionId(),
                "server_shop_buy", operation).join();

        assertTrue(first.mutation().applied());
        assertEquals(75L, first.mutation().balance());
        assertFalse(retry.mutation().applied());
        assertEquals(75L, retry.mutation().balance());
        assertEquals(operation.operationId(), retry.operation().operationId());
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id = 'shop:buy:55555555'
                """));
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM wpme_sb_inventory_outbox
                WHERE operation_id = 'shop:buy:55555555'
                """));

        assertTrue(dao.transitionInventoryOperation(operation.operationId(),
                LedgerDao.InventoryStatus.PENDING,
                LedgerDao.InventoryStatus.DELIVERED).join());
        assertFalse(dao.transitionInventoryOperation(operation.operationId(),
                LedgerDao.InventoryStatus.PENDING,
                LedgerDao.InventoryStatus.DELIVERED).join());
        LedgerDao restartedAfterPlayerSave = new LedgerDao(sql);
        assertEquals(LedgerDao.InventoryStatus.DELIVERED,
                restartedAfterPlayerSave.inventoryOperation(operation.operationId())
                        .join().orElseThrow().status());
        assertTrue(restartedAfterPlayerSave.transitionInventoryOperation(operation.operationId(),
                LedgerDao.InventoryStatus.DELIVERED,
                LedgerDao.InventoryStatus.COMPLETE).join());
        assertTrue(dao.pendingInventoryOperations(playerId).join().isEmpty());
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM wpme_sb_inventory_outbox
                WHERE operation_id = 'shop:buy:55555555'
                  AND status = 'COMPLETE' AND item_payload IS NULL
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM wpme_sb_inventory_outbox_lines
                WHERE operation_id = 'shop:buy:55555555'
                """));
    }

    /**
     * LEDGER-1. Wiersz outboxu przy {@code COMPLETE} miał tylko zerowane kolumny
     * i zostawał na zawsze — jedyna retencja w kodzie dotyczyła questów. Na starym
     * kodzie ten test się nie kompiluje (brak metody), a bez retencji tabela rośnie
     * bez końca: ~600 k wierszy po miesiącu przy 200 graczach.
     */
    @Test
    void retentionRemovesSettledOutboxRowsAndKeepsEverythingElse() {
        UUID settledPlayer = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
        UUID openPlayer = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
        UUID quarantinedPlayer = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");

        LedgerDao.InventoryOperation settled = operation("retention:settled", settledPlayer,
                LedgerDao.InventoryOperationType.GRANT, new byte[]{1}, List.of());
        dao.mutateWithInventoryOperation(AccountKey.player(settledPlayer), 100L, -1L,
                settled.transactionId(), "test", settled).join();
        assertTrue(dao.transitionInventoryOperation(settled.operationId(),
                LedgerDao.InventoryStatus.PENDING,
                LedgerDao.InventoryStatus.COMPLETE).join());

        LedgerDao.InventoryOperation open = operation("retention:open", openPlayer,
                LedgerDao.InventoryOperationType.GRANT, new byte[]{2}, List.of());
        dao.mutateWithInventoryOperation(AccountKey.player(openPlayer), 100L, -1L,
                open.transactionId(), "test", open).join();

        LedgerDao.InventoryOperation quarantined = operation("retention:quarantined",
                quarantinedPlayer, LedgerDao.InventoryOperationType.GRANT,
                new byte[]{3}, List.of());
        dao.mutateWithInventoryOperation(AccountKey.player(quarantinedPlayer), 100L, -1L,
                quarantined.transactionId(), "test", quarantined).join();
        assertTrue(dao.transitionInventoryOperation(quarantined.operationId(),
                LedgerDao.InventoryStatus.PENDING,
                LedgerDao.InventoryStatus.QRTN_PENDING).join());

        int removed = dao.pruneSettledInventoryOperations(
                System.currentTimeMillis() + 60_000L).join();

        assertEquals(1, removed);
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM wpme_sb_inventory_outbox
                WHERE operation_id = 'retention:settled'
                """));
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM wpme_sb_inventory_outbox
                WHERE operation_id = 'retention:open'
                """), "operacja w toku nie ma prawa zniknąć");
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM wpme_sb_inventory_outbox
                WHERE operation_id = 'retention:quarantined'
                """), "kwarantanna jest jedynym dowodem dla operatora i zostaje");
        // Audyt księgi zostaje nietknięty — retencja dotyczy wyłącznie outboxu.
        assertEquals(3L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id LIKE 'retention:%'
                """));
    }

    /**
     * A2-25. Gałąź idempotencji zwracała wiersz operacji <b>innego</b> gracza,
     * bo {@code operationId} nagród wyspowych jest wspólny dla wszystkich członków
     * ({@code "season_reward:lotus:<sezon>:<wyspa>"}), a {@code transactionId}
     * równa się {@code operationId}. Na starym kodzie ten test pada: drugi członek
     * dostawał operację pierwszego jako własną.
     */
    @Test
    void islandWideOperationIdIsRefusedForASecondMemberInsteadOfHandingOverTheReward() {
        UUID memberA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
        UUID memberB = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
        String islandWide = "season_reward:lotus:1:cccccccc-0000-0000-0000-000000000001";

        LedgerDao.InventoryOperation forA = operation(islandWide, memberA,
                LedgerDao.InventoryOperationType.GRANT, new byte[]{1}, List.of());
        assertTrue(dao.mutateWithInventoryOperation(AccountKey.player(memberA), 100L, -1L,
                islandWide, "seasonal_lotus", forA).join().mutation().applied());

        LedgerDao.InventoryOperation forB = operation(islandWide, memberB,
                LedgerDao.InventoryOperationType.GRANT, new byte[]{2}, List.of());
        CompletionException failure = assertThrows(CompletionException.class, () ->
                dao.mutateWithInventoryOperation(AccountKey.player(memberB), 100L, -1L,
                        islandWide, "seasonal_lotus", forB).join());

        SQLException conflict = assertInstanceOf(SQLException.class, failure.getCause());
        assertTrue(conflict.getMessage().contains("belongs to"), conflict.getMessage());
        assertEquals(memberA.toString(), sql.query("""
                SELECT player_id FROM wpme_sb_inventory_outbox WHERE operation_id = ?
                """, row -> row.getString(1), islandWide).join().getFirst(),
                "wiersz pierwszego gracza musi zostać nietknięty");
    }

    @Test
    void insufficientGrantCannotLeaveAnOutboxWithoutItsLedgerMutation() {
        UUID playerId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        AccountKey player = AccountKey.player(playerId);
        LedgerDao.InventoryOperation operation = operation(
                "wand:buy:66666666", playerId,
                LedgerDao.InventoryOperationType.GRANT, new byte[]{4, 5, 6}, List.of());

        LedgerDao.InventoryMutation result = dao.mutateWithInventoryOperation(
                player, 100L, -101L, operation.transactionId(),
                "wand_buy", operation).join();

        assertFalse(result.mutation().applied());
        assertTrue(result.mutation().insufficient());
        assertEquals(100L, result.mutation().balance());
        assertEquals(null, result.operation());
        assertEquals(Optional.empty(), dao.inventoryOperation(operation.operationId()).join());
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id = 'wand:buy:66666666'
                """));
    }

    @Test
    void saleCreditAndRemovalPlanSurviveAServiceRestart() {
        UUID playerId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        AccountKey player = AccountKey.player(playerId);
        LedgerDao.InventoryOperation operation = operation(
                "wand:sell:77777777", playerId,
                LedgerDao.InventoryOperationType.REMOVE, null,
                List.of(new LedgerDao.InventoryLine("minecraft:cobblestone", "", 32, 80)));

        LedgerDao.InventoryMutation result = dao.mutateWithInventoryOperation(
                player, 100L, 40L, operation.transactionId(),
                "sell_wand", operation).join();

        assertTrue(result.mutation().applied());
        assertEquals(140L, result.mutation().balance());
        // A freshly constructed DAO represents plugin/service restart.
        LedgerDao restarted = new LedgerDao(sql);
        List<LedgerDao.InventoryOperation> recovered =
                restarted.pendingInventoryOperations(playerId).join();
        assertEquals(1, recovered.size());
        assertEquals(operation.operationId(), recovered.getFirst().operationId());
        assertEquals(operation.lines(), recovered.getFirst().lines());
        assertEquals(List.of(playerId), restarted.pendingInventoryPlayers().join());
    }

    @Test
    void serviceResolvesMutationWhenCommitSucceededButAcknowledgementWasLost()
            throws Exception {
        UUID playerId = UUID.fromString("aaaaaaaa-1111-2222-3333-bbbbbbbbbbbb");
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        sql.failAfterNextConnectionAction(
                new IllegalStateException("simulated lost mutation acknowledgement"));

        LedgerDao.Mutation resolved = ledger.depositPlayer(playerId, 25L,
                "test:ambiguous-mutation", "vault_deposit").join();

        assertTrue(resolved.applied());
        assertFalse(resolved.insufficient());
        assertEquals(125L, resolved.balance());
        assertEquals(125L, ledger.playerBalance(playerId));
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id = 'test:ambiguous-mutation'
                """));
    }

    @Test
    void cancellingExposedFutureCannotReleaseTheInternalAccountSequence()
            throws Exception {
        UUID playerId = UUID.fromString("aaaaaaaa-1111-2222-3333-000000000001");
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        ledger.ensurePlayer(playerId).join();
        CompletableFuture<Void> releaseFirst = sql.delayNextConnectionCompletion();

        CompletableFuture<LedgerDao.Mutation> first = ledger.depositPlayer(
                playerId, 10L, "test:serialized-first", "test");
        CompletableFuture<LedgerDao.Mutation> second = ledger.withdrawPlayer(
                playerId, 5L, "test:serialized-second", "test");

        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertTrue(first.cancel(false));
        assertTrue(first.isCancelled());
        assertFalse(second.isDone());
        assertEquals(1L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id = 'test:serialized-first'
                """));
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id = 'test:serialized-second'
                """));
        releaseFirst.complete(null);
        second.get(5L, TimeUnit.SECONDS);
        assertEquals(105L, ledger.playerBalance(playerId));
        assertEquals(105L, dao.balance(AccountKey.player(playerId)).join());
    }

    @Test
    void queuedIslandWithdrawalRechecksRoleImmediatelyBeforeSql()
            throws Exception {
        UUID islandId = UUID.fromString("aaaaaaaa-1111-2222-3333-000000000010");
        UUID funderId = UUID.fromString("aaaaaaaa-1111-2222-3333-000000000011");
        UUID recipientId = UUID.fromString("aaaaaaaa-1111-2222-3333-000000000012");
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        assertTrue(ledger.transferToIsland(funderId, islandId, 40L,
                "test:authorization-fund").join().applied());

        CompletableFuture<Void> releaseFirst = sql.delayNextConnectionCompletion();
        CompletableFuture<LedgerDao.Transfer> first = ledger.transferToIsland(
                funderId, islandId, 10L, "test:authorization-hold");
        AtomicBoolean authorized = new AtomicBoolean(true);
        AtomicBoolean authorizationStarted = new AtomicBoolean();
        CompletableFuture<LedgerDao.Transfer> queued = ledger.transferFromIsland(
                islandId, recipientId, 25L, "test:authorization-queued",
                () -> {
                    authorizationStarted.set(true);
                    return CompletableFuture.completedFuture(authorized.get());
                });

        assertFalse(first.isDone());
        assertFalse(queued.isDone());
        assertFalse(authorizationStarted.get());
        authorized.set(false);
        releaseFirst.complete(null);

        assertTrue(first.get(5L, TimeUnit.SECONDS).applied());
        assertTrue(authorizationStarted.get());
        LedgerDao.Transfer rejected = queued.get(5L, TimeUnit.SECONDS);
        assertFalse(rejected.applied());
        assertFalse(rejected.insufficient());
        assertEquals(50L, rejected.sourceBalance());
        assertEquals(0L, rejected.targetBalance());
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id LIKE 'test:authorization-queued:%'
                """));
    }

    @Test
    void queuedIslandWithdrawalRechecksPermissionAfterReachingQueueHead()
            throws Exception {
        UUID islandId = UUID.fromString("aaaaaaaa-1111-2222-3333-000000000020");
        UUID funderId = UUID.fromString("aaaaaaaa-1111-2222-3333-000000000021");
        UUID recipientId = UUID.fromString("aaaaaaaa-1111-2222-3333-000000000022");
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        assertTrue(ledger.transferToIsland(funderId, islandId, 40L,
                "test:permission-fund").join().applied());

        CompletableFuture<Void> releaseFirst = sql.delayNextConnectionCompletion();
        CompletableFuture<LedgerDao.Transfer> first = ledger.transferToIsland(
                funderId, islandId, 10L, "test:permission-hold");
        AtomicBoolean permission = new AtomicBoolean(true);
        AtomicBoolean authorizationStarted = new AtomicBoolean();
        CompletableFuture<LedgerDao.Transfer> queued = ledger.transferAllFromIsland(
                islandId, recipientId, "test:permission-queued",
                () -> {
                    authorizationStarted.set(true);
                    return CompletableFuture.completedFuture(permission.get());
                });

        assertFalse(first.isDone());
        assertFalse(queued.isDone());
        assertFalse(authorizationStarted.get());
        permission.set(false);
        releaseFirst.complete(null);

        assertTrue(first.get(5L, TimeUnit.SECONDS).applied());
        assertTrue(authorizationStarted.get());
        LedgerDao.Transfer rejected = queued.get(5L, TimeUnit.SECONDS);
        assertFalse(rejected.applied());
        assertFalse(rejected.insufficient());
        assertEquals(50L, rejected.sourceBalance());
        assertEquals(0L, rejected.targetBalance());
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id LIKE 'test:permission-queued:%'
                """));
    }

    @Test
    void failedOrOfflineFinalAuthorizationCannotSubmitWithdrawal()
            throws Exception {
        UUID islandId = UUID.fromString("aaaaaaaa-1111-2222-3333-000000000030");
        UUID funderId = UUID.fromString("aaaaaaaa-1111-2222-3333-000000000031");
        UUID recipientId = UUID.fromString("aaaaaaaa-1111-2222-3333-000000000032");
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        assertTrue(ledger.transferToIsland(funderId, islandId, 40L,
                "test:failed-authorization-fund").join().applied());

        IllegalStateException lookupFailure =
                new IllegalStateException("authoritative role lookup failed");
        CompletionException failed = assertThrows(CompletionException.class,
                () -> ledger.transferFromIsland(islandId, recipientId, 10L,
                                "test:failed-authorization",
                                () -> CompletableFuture.failedFuture(lookupFailure))
                        .join());
        assertEquals(lookupFailure, failed.getCause());

        LedgerDao.Transfer offline = ledger.transferFromIsland(
                islandId, recipientId, 10L, "test:offline-authorization",
                () -> CompletableFuture.completedFuture(false)).join();
        assertFalse(offline.applied());
        assertFalse(offline.insufficient());
        assertEquals(40L, offline.sourceBalance());
        assertEquals(0L, offline.targetBalance());
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id LIKE 'test:failed-authorization:%'
                   OR transaction_id LIKE 'test:offline-authorization:%'
                """));
    }

    @Test
    void servicePreservesOriginalMutationFailureWhenAuditRowIsAbsent()
            throws Exception {
        UUID playerId = UUID.fromString("aaaaaaaa-2222-3333-4444-bbbbbbbbbbbb");
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        IllegalStateException original =
                new IllegalStateException("simulated write rejection");
        sql.failBeforeNextConnectionAction(original);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> ledger.depositPlayer(playerId, 25L,
                        "test:absent-mutation", "vault_deposit").join());

        assertEquals(original, failure.getCause());
        assertEquals(0L, ledger.playerBalance(playerId));
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id = 'test:absent-mutation'
                """));
    }

    @Test
    void serviceFailsClosedWhenMutationAuditConflictsWithExpectedWrite()
            throws Exception {
        UUID playerId = UUID.fromString("aaaaaaaa-3333-4444-5555-bbbbbbbbbbbb");
        AccountKey player = AccountKey.player(playerId);
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        sql.update("""
                        INSERT INTO wpme_sb_transactions
                            (transaction_id, account_key, counterparty_key,
                             delta_minor, reason, created_at)
                        VALUES (?, ?, NULL, ?, ?, ?)
                        """,
                "test:conflicting-mutation", player.toString(), 999L,
                "wrong_reason", 1L).join();
        sql.failBeforeNextConnectionAction(
                new IllegalStateException("simulated ambiguous mutation"));

        CompletionException failure = assertThrows(CompletionException.class,
                () -> ledger.depositPlayer(playerId, 25L,
                        "test:conflicting-mutation", "vault_deposit").join());

        SQLException conflict = assertInstanceOf(
                SQLException.class, failure.getCause());
        assertTrue(conflict.getMessage().contains("conflicting ledger transaction"));
        assertEquals(1, conflict.getSuppressed().length);
    }

    @Test
    void serviceResolvesBalancedTransferWhenCommitAcknowledgementWasLost()
            throws Exception {
        UUID playerId = UUID.fromString("aaaaaaaa-4444-5555-6666-bbbbbbbbbbbb");
        UUID islandId = UUID.fromString("cccccccc-4444-5555-6666-dddddddddddd");
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        ledger.ensurePlayer(playerId).join();
        sql.failAfterNextConnectionAction(
                new IllegalStateException("simulated lost transfer acknowledgement"));

        LedgerDao.Transfer resolved = ledger.transferToIsland(
                playerId, islandId, 40L, "test:ambiguous-transfer").join();

        assertTrue(resolved.applied());
        assertFalse(resolved.insufficient());
        assertEquals(60L, resolved.sourceBalance());
        assertEquals(40L, resolved.targetBalance());
        assertEquals(60L, ledger.playerBalance(playerId));
        assertEquals(40L, ledger.islandBalance(islandId));
        assertEquals(2L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id LIKE 'test:ambiguous-transfer:%'
                """));
    }

    @Test
    void serviceTransferAllIgnoresStaleCacheAndResolvesLostAcknowledgement()
            throws Exception {
        UUID playerId = UUID.fromString("aaaaaaaa-4444-5555-6666-000000000001");
        UUID islandId = UUID.fromString("cccccccc-4444-5555-6666-000000000001");
        AccountKey player = AccountKey.player(playerId);
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        ledger.ensurePlayer(playerId).join();
        dao.mutate(player, 100L, 25L, "test:out-of-band-credit", "test").join();
        assertEquals(100L, ledger.playerBalance(playerId));
        sql.failAfterNextConnectionAction(
                new IllegalStateException("simulated lost transfer-all acknowledgement"));

        LedgerDao.Transfer resolved = ledger.transferAllToIsland(
                playerId, islandId, "test:ambiguous-transfer-all").join();

        assertTrue(resolved.applied());
        assertEquals(125L, resolved.transferredAmount());
        assertEquals(0L, resolved.sourceBalance());
        assertEquals(125L, resolved.targetBalance());
        assertEquals(0L, ledger.playerBalance(playerId));
        assertEquals(125L, ledger.islandBalance(islandId));
    }

    @Test
    void serviceResolvesEmptyTransferAllWhenNoOpAcknowledgementWasLost()
            throws Exception {
        UUID playerId = UUID.fromString("aaaaaaaa-4444-5555-6666-000000000002");
        UUID islandId = UUID.fromString("cccccccc-4444-5555-6666-000000000002");
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        sql.failAfterNextConnectionAction(
                new IllegalStateException("simulated lost empty transfer-all acknowledgement"));

        LedgerDao.Transfer resolved = ledger.transferAllFromIsland(
                islandId, playerId, "test:ambiguous-empty-transfer-all",
                () -> CompletableFuture.completedFuture(true)).join();

        assertFalse(resolved.applied());
        assertTrue(resolved.insufficient());
        assertEquals(0L, resolved.transferredAmount());
        assertEquals(0L, resolved.sourceBalance());
        assertEquals(0L, resolved.targetBalance());
        assertEquals(0L, count("""
                SELECT COUNT(*) FROM wpme_sb_transactions
                WHERE transaction_id LIKE 'test:ambiguous-empty-transfer-all%'
                """));
    }

    @Test
    void missingStartingAccountIsNotMisclassifiedAsEmptyAfterRejectedWrite()
            throws Exception {
        UUID playerId = UUID.fromString("aaaaaaaa-4444-5555-6666-000000000003");
        UUID islandId = UUID.fromString("cccccccc-4444-5555-6666-000000000003");
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        IllegalStateException rejected = new IllegalStateException("write never started");
        sql.failBeforeNextConnectionAction(rejected);

        CompletionException failure = assertThrows(CompletionException.class,
                () -> ledger.transferAllToIsland(playerId, islandId,
                        "test:missing-source-transfer-all").join());

        assertEquals(rejected, failure.getCause());
        assertEquals(0L, count("SELECT COUNT(*) FROM wpme_sb_accounts"));
    }

    @Test
    void serviceFailsClosedWhenTransferAuditIsPartial() throws Exception {
        UUID playerId = UUID.fromString("aaaaaaaa-5555-6666-7777-bbbbbbbbbbbb");
        UUID islandId = UUID.fromString("cccccccc-5555-6666-7777-dddddddddddd");
        AccountKey player = AccountKey.player(playerId);
        AccountKey island = AccountKey.island(islandId);
        LedgerService ledger = new LedgerService(dao, 100L);
        ledger.init();
        sql.update("""
                        INSERT INTO wpme_sb_transactions
                            (transaction_id, account_key, counterparty_key,
                             delta_minor, reason, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                "test:partial-transfer:debit", player.toString(),
                island.toString(), -40L, "bank_deposit", 1L).join();
        sql.failBeforeNextConnectionAction(
                new IllegalStateException("simulated ambiguous transfer"));

        CompletionException failure = assertThrows(CompletionException.class,
                () -> ledger.transferToIsland(playerId, islandId, 40L,
                        "test:partial-transfer").join());

        SQLException conflict = assertInstanceOf(
                SQLException.class, failure.getCause());
        assertTrue(conflict.getMessage().contains(
                "partial or conflicting transfer audit"));
        assertEquals(1, conflict.getSuppressed().length);
    }

    private static LedgerDao.InventoryOperation operation(
            String operationId, UUID playerId,
            LedgerDao.InventoryOperationType type, byte[] payload,
            List<LedgerDao.InventoryLine> lines) {
        return new LedgerDao.InventoryOperation(operationId, playerId, type,
                operationId, LedgerDao.InventoryStatus.PENDING, payload,
                null, null, null, lines, 1L, 1L);
    }

    private long count(String query) {
        return scalar(query);
    }

    private long count(String query, Object... params) {
        return sql.query(query, row -> row.getLong(1), params).join().getFirst();
    }

    private long scalar(String query) {
        return sql.query(query, row -> row.getLong(1)).join().getFirst();
    }

    private static final class TestSqlService implements SqlService {
        private final Connection connection;
        private RuntimeException failBeforeConnectionAction;
        private RuntimeException failAfterConnectionAction;
        private RuntimeException failBeforeFollowingConnectionAction;
        private CompletableFuture<Void> delayAfterConnectionAction;

        private TestSqlService() throws SQLException {
            connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        }

        @Override public boolean isEnabled() { return true; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isHealthy() { return true; }
        @Override public @NotNull Map<String, Object> poolStats() { return Map.of(); }

        @Override
        public void shutdown() {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // test cleanup
            }
        }

        @Override
        public @NotNull CompletableFuture<Integer> update(@NotNull String query,
                                                           @NotNull Object... params) {
            return completed(() -> {
                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    bind(statement, params);
                    return statement.executeUpdate();
                }
            });
        }

        @Override
        public <T> @NotNull CompletableFuture<List<T>> query(@NotNull String query,
                                                              @NotNull RowMapper<T> mapper,
                                                              @NotNull Object... params) {
            return completed(() -> {
                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    bind(statement, params);
                    try (ResultSet result = statement.executeQuery()) {
                        List<T> rows = new ArrayList<>();
                        while (result.next()) {
                            rows.add(mapper.map(result));
                        }
                        return rows;
                    }
                }
            });
        }

        @Override
        public <T> @NotNull CompletableFuture<T> withConnection(@NotNull SqlAction<T> action) {
            RuntimeException before = failBeforeConnectionAction;
            failBeforeConnectionAction = null;
            if (before != null) {
                return CompletableFuture.failedFuture(before);
            }
            try {
                T result = action.execute(connection);
                RuntimeException after = failAfterConnectionAction;
                failAfterConnectionAction = null;
                if (after != null && failBeforeFollowingConnectionAction != null) {
                    failBeforeConnectionAction = failBeforeFollowingConnectionAction;
                    failBeforeFollowingConnectionAction = null;
                }
                if (after != null) {
                    return CompletableFuture.failedFuture(after);
                }
                CompletableFuture<Void> delay = delayAfterConnectionAction;
                delayAfterConnectionAction = null;
                return delay == null
                        ? CompletableFuture.completedFuture(result)
                        : delay.thenApply(ignored -> result);
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @Override
        public @NotNull Object dataSource() {
            return connection;
        }

        private void failBeforeNextConnectionAction(RuntimeException failure) {
            failBeforeConnectionAction = failure;
        }

        private void failAfterNextConnectionAction(RuntimeException failure) {
            failAfterConnectionAction = failure;
        }

        private void failAfterNextAndBeforeFollowingConnectionActions(
                RuntimeException after, RuntimeException following) {
            failAfterConnectionAction = after;
            failBeforeFollowingConnectionAction = following;
        }

        private CompletableFuture<Void> delayNextConnectionCompletion() {
            CompletableFuture<Void> delay = new CompletableFuture<>();
            delayAfterConnectionAction = delay;
            return delay;
        }

        private static void bind(PreparedStatement statement, Object[] params) throws SQLException {
            for (int index = 0; index < params.length; index++) {
                statement.setObject(index + 1, params[index]);
            }
        }

        private static <T> CompletableFuture<T> completed(SqlSupplier<T> supplier) {
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @FunctionalInterface
        private interface SqlSupplier<T> {
            T get() throws Exception;
        }
    }
}
