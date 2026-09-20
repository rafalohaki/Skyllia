package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class LedgerService {

    private static final System.Logger LOGGER =
            System.getLogger(LedgerService.class.getName());

    private final LedgerDao dao;
    private final long startingBalance;
    private final Map<String, Long> balances = new ConcurrentHashMap<>();
    private final Object accountOperationMutex = new Object();
    private final Map<String, CompletableFuture<Void>> accountOperations = new HashMap<>();
    // M1-D: leave/kick/delete/reset blocks new outbox/ledger mutations
    private volatile org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileMutationGuard mutationGuard;

    public void setMutationGuard(org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileMutationGuard guard) {
        this.mutationGuard = guard;
    }

    /**
     * Bramka strażnika mutacji przed zapisem do księgi — bez blokowania wątku.
     *
     * <p>Dawniej było tu {@code get(600 ms)}. Odpowiedź strażnika to zapytanie
     * do tej samej puli SQL, na której siedzi wołający (np. {@code …-Sql-1}
     * przy rozliczaniu zadań), więc czekanie na nią wątkiem puli zagładzało
     * pulę: przy {@code maximum-pool-size: 2} dwa takie oczekiwania naraz
     * blokowały wykonanie samego zapytania. Fail-closed zamieniał to w utratę
     * nagrody, choć żadna blokada nie była aktywna.
     *
     * <p>Polityka bez zmian: gdy naprawdę nie da się zweryfikować, mutacja nie
     * idzie. Zmienia się sposób czekania i to, że powód mówi prawdę.
     */
    private <T> @NotNull CompletableFuture<T> guarded(
            @NotNull UUID playerId, @NotNull Supplier<CompletableFuture<T>> mutation) {
        var guard = this.mutationGuard;
        if (guard == null) {
            return mutation.get();
        }
        return guard.isBlockedForPlayer(playerId)
                .handle((blocked, failure) -> {
                    if (failure != null) {
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Nie udało się zweryfikować strażnika mutacji dla " + playerId
                                        + " — wstrzymuję zapis do księgi", failure);
                        throw new org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileMutationGuard.BlockedMutationException(
                                "Nie udało się zweryfikować strażnika mutacji dla " + playerId
                                        + " — wstrzymuję fail-closed: " + rootCause(failure));
                    }
                    if (Boolean.TRUE.equals(blocked)) {
                        throw new org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileMutationGuard.BlockedMutationException(
                                "Mutacje księgi wstrzymane dla " + playerId
                                        + " — trwa opuszczanie/wyrzucenie/usunięcie/reset wyspy");
                    }
                    return (Void) null;
                })
                .thenCompose(ignored -> mutation.get());
    }

    /** Powód po ludzku: {@code CompletionException} owija właściwy wyjątek. */
    private static @NotNull String rootCause(@NotNull Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    public LedgerService(@NotNull LedgerDao dao, long startingBalance) {
        if (startingBalance < 0L || startingBalance > LedgerDao.MAX_BALANCE) {
            throw new IllegalArgumentException("starting balance outside ledger range");
        }
        this.dao = dao;
        this.startingBalance = startingBalance;
    }

    public void init() throws Exception {
        init(null);
    }

    public void init(String questPeriodCutoff) throws Exception {
        init(questPeriodCutoff, 0L);
    }

    /**
     * @param outboxCutoffMillis LEDGER-1: wiersze outboxu ekwipunku w stanie
     *                           {@code COMPLETE} starsze niż ten znacznik znikają.
     *                           {@code 0} wyłącza retencję.
     */
    public void init(String questPeriodCutoff, long outboxCutoffMillis) throws Exception {
        dao.createSchema().get(15L, TimeUnit.SECONDS);
        if (questPeriodCutoff != null) {
            validateLabel(questPeriodCutoff, 16, "quest retention cutoff");
            dao.pruneQuestHistory(questPeriodCutoff).get(15L, TimeUnit.SECONDS);
        }
        if (outboxCutoffMillis > 0L) {
            // ponytail: retencja biegnie przy starcie, tak jak questowa. Indeks
            // (player_id, status) zdejmuje koszt zapytania, więc przyrost między
            // restartami boli już tylko dyskiem — zadanie cykliczne dopiero, gdy
            // serwer zacznie chodzić tygodniami bez restartu.
            int removed = dao.pruneSettledInventoryOperations(outboxCutoffMillis)
                    .get(30L, TimeUnit.SECONDS);
            if (removed > 0) {
                LOGGER.log(System.Logger.Level.INFO,
                        "Inventory outbox retention removed " + removed + " settled row(s)");
            }
        }
        balances.putAll(dao.loadBalances().get(15L, TimeUnit.SECONDS));
    }

    public long playerBalance(@NotNull UUID playerId) {
        return balance(AccountKey.player(playerId));
    }

    long islandBalance(@NotNull UUID islandId) {
        return balance(AccountKey.island(islandId));
    }

    @NotNull CompletableFuture<Long> authoritativePlayerBalance(@NotNull UUID playerId) {
        AccountKey account = AccountKey.player(playerId);
        return ensurePlayer(playerId).thenCompose(ignored -> dao.balance(account));
    }

    public @NotNull CompletableFuture<Long> authoritativeIslandBalance(@NotNull UUID islandId) {
        AccountKey account = AccountKey.island(islandId);
        return dao.balance(account);
    }

    boolean hasPlayerAccount(@NotNull UUID playerId) {
        return balances.containsKey(AccountKey.player(playerId).toString());
    }

    public @NotNull CompletableFuture<LedgerDao.Mutation> ensurePlayer(@NotNull UUID playerId) {
        AccountKey account = AccountKey.player(playerId);
        return serializeAccounts(List.of(account), () ->
                dao.ensureAccount(account, startingBalance, "account:start:" + account)
                        .thenApply(result -> cache(account, result)));
    }

    public @NotNull CompletableFuture<LedgerDao.Mutation> depositPlayer(
            @NotNull UUID playerId, long amount, @NotNull String transactionId,
            @NotNull String reason) {
        return guarded(playerId, () ->
                mutate(AccountKey.player(playerId), startingBalance, amount, transactionId, reason));
    }

    public @NotNull CompletableFuture<LedgerDao.Mutation> deposit(
            @NotNull AccountKey account, long amount, @NotNull String transactionId,
            @NotNull String reason) {
        long initial = account.type() == AccountKey.Type.PLAYER ? startingBalance : 0L;
        return mutate(account, initial, amount, transactionId, reason);
    }

    public @NotNull CompletableFuture<LedgerDao.Mutation> deposit(
            @NotNull AccountKey account, long amount, @NotNull String reason) {
        return deposit(account, amount, "deposit:" + UUID.randomUUID(), reason);
    }

    @NotNull CompletableFuture<LedgerDao.Mutation> depositIsland(
            @NotNull UUID islandId, long amount, @NotNull String transactionId,
            @NotNull String reason) {
        return mutate(AccountKey.island(islandId), 0L, amount, transactionId, reason);
    }

    @NotNull CompletableFuture<LedgerDao.Mutation> depositIsland(
            @NotNull UUID islandId, long amount, @NotNull String reason) {
        return depositIsland(islandId, amount, "island_deposit:" + UUID.randomUUID(), reason);
    }

    @NotNull CompletableFuture<LedgerDao.Mutation> withdrawPlayer(
            @NotNull UUID playerId, long amount, @NotNull String transactionId,
            @NotNull String reason) {
        return mutate(AccountKey.player(playerId), startingBalance,
                Math.negateExact(amount), transactionId, reason);
    }

    public @NotNull CompletableFuture<LedgerDao.Mutation> withdrawIsland(
            @NotNull UUID islandId, long amount, @NotNull String transactionId,
            @NotNull String reason) {
        return mutate(AccountKey.island(islandId), 0L,
                Math.negateExact(amount), transactionId, reason);
    }

    @NotNull CompletableFuture<LedgerDao.InventoryMutation> mutatePlayerWithInventoryOperation(
            @NotNull UUID playerId, long delta, @NotNull String transactionId,
            @NotNull String reason, @NotNull LedgerDao.InventoryOperation operation) {
        /*
         * Bez własnego sprawdzenia strażnika. Wszystkie trzy wejścia do tej
         * metody (InventoryOutbox: beginGrant, beginRemoval, beginOfflineGrant)
         * przepuszczają operację przez tego samego strażnika chwilę wcześniej,
         * więc drugie pytanie o tę samą odpowiedź tylko podwajało ruch do
         * SQLite'a i — dopóki było blokujące — czas zajęcia wątku puli.
         */
        validateAmount(delta == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(delta));
        validateLabel(transactionId, 128, "transaction id");
        validateLabel(reason, 64, "reason");
        validateInventoryOperation(playerId, delta, transactionId, operation);
        AccountKey account = AccountKey.player(playerId);
        return serializeAccounts(List.of(account), () ->
                dao.mutateWithInventoryOperation(account, startingBalance, delta,
                                transactionId, reason, operation)
                        .thenApply(result -> {
                            cache(account, result.mutation());
                            return result;
                        }));
    }

    @NotNull CompletableFuture<Optional<LedgerDao.InventoryOperation>> inventoryOperation(
            @NotNull String operationId) {
        validateLabel(operationId, 128, "inventory operation id");
        return dao.inventoryOperation(operationId);
    }

    @NotNull CompletableFuture<List<LedgerDao.InventoryOperation>> pendingInventoryOperations(
            @NotNull UUID playerId) {
        return dao.pendingInventoryOperations(playerId);
    }

    @NotNull CompletableFuture<List<UUID>> pendingInventoryPlayers() {
        return dao.pendingInventoryPlayers();
    }

    @NotNull CompletableFuture<Boolean> transitionInventoryOperation(
            @NotNull String operationId, @NotNull LedgerDao.InventoryStatus expected,
            @NotNull LedgerDao.InventoryStatus target) {
        validateLabel(operationId, 128, "inventory operation id");
        if (!expected.canTransitionTo(target)) {
            throw new IllegalArgumentException(
                    "invalid inventory outbox transition " + expected + " -> " + target);
        }
        return dao.transitionInventoryOperation(operationId, expected, target);
    }

    @NotNull CompletableFuture<LedgerDao.Transfer> transferToIsland(
            @NotNull UUID playerId, @NotNull UUID islandId, long amount,
            @NotNull String transactionId) {
        return transfer(AccountKey.player(playerId), AccountKey.island(islandId),
                startingBalance, amount, transactionId, "bank_deposit");
    }

    @NotNull CompletableFuture<LedgerDao.Transfer> transferFromIsland(
            @NotNull UUID islandId, @NotNull UUID playerId, long amount,
            @NotNull String transactionId,
            @NotNull AsyncAuthorization authorization) {
        return transfer(AccountKey.island(islandId), AccountKey.player(playerId),
                0L, amount, transactionId, "bank_withdraw", authorization);
    }

    @NotNull CompletableFuture<LedgerDao.Transfer> transferAllToIsland(
            @NotNull UUID playerId, @NotNull UUID islandId,
            @NotNull String transactionId) {
        return transferAll(AccountKey.player(playerId), AccountKey.island(islandId),
                startingBalance, transactionId, "bank_deposit_all");
    }

    @NotNull CompletableFuture<LedgerDao.Transfer> transferAllFromIsland(
            @NotNull UUID islandId, @NotNull UUID playerId,
            @NotNull String transactionId,
            @NotNull AsyncAuthorization authorization) {
        return transferAll(AccountKey.island(islandId), AccountKey.player(playerId),
                0L, transactionId, "bank_withdraw_all", authorization);
    }

    public @NotNull CompletableFuture<Map<String, LedgerDao.QuestStatus>> questStatuses(
            @NotNull UUID islandId, @NotNull String period) {
        return dao.questStatuses(AccountKey.island(islandId), period);
    }

    public @NotNull CompletableFuture<Integer> countCompletedQuestsInPeriod(
            @NotNull UUID islandId, @NotNull String startPeriod, @NotNull String endPeriod) {
        validateLabel(startPeriod, 16, "start quest period");
        validateLabel(endPeriod, 16, "end quest period");
        return dao.countCompletedQuestsInPeriod(AccountKey.island(islandId), startPeriod, endPeriod);
    }

    public @NotNull CompletableFuture<Boolean> isWeeklyMilestoneClaimed(
            @NotNull UUID islandId, @NotNull String weekKey) {
        validateLabel(weekKey, 16, "week key");
        return dao.isWeeklyMilestoneClaimed(AccountKey.island(islandId), weekKey);
    }

    public @NotNull CompletableFuture<Boolean> claimWeeklyMilestone(
            @NotNull UUID islandId, @NotNull String weekKey) {
        validateLabel(weekKey, 16, "week key");
        return dao.claimWeeklyMilestone(AccountKey.island(islandId), weekKey);
    }

    /**
     * Skład wyspy (konta ACTIVE) — odbiorcy powiadomienia o tygodniowym kamieniu
     * milowym. Źródłem jest baza, nie cache Skyllii: po skasowaniu i odtworzeniu
     * wyspy o tym samym {@code island_id} migawka cache bywa pusta i komunikat
     * nie docierał do nikogo (E2E 2026-09-10).
     */
    public @NotNull CompletableFuture<Set<UUID>> activeIslandMemberIds(@NotNull UUID islandId) {
        return dao.activeIslandMemberIds(islandId);
    }

    public @NotNull CompletableFuture<LedgerDao.QuestMutation> incrementQuest(
            @NotNull UUID islandId, @NotNull UUID ownerId,
            @NotNull String period, @NotNull String questId,
            long increment, long target, long reward,
            @NotNull String batchTransactionId) {
        if (increment <= 0L || target <= 0L) {
            throw new IllegalArgumentException("quest increment and target must be positive");
        }
        validateAmount(increment);
        validateAmount(target);
        validateAmount(reward);
        validateLabel(period, 16, "quest period");
        validateLabel(questId, 48, "quest id");
        validateLabel(batchTransactionId, 128, "quest batch transaction id");
        return incrementQuestNow(islandId, ownerId, period, questId,
                increment, target, reward, batchTransactionId);
    }

    private @NotNull CompletableFuture<LedgerDao.QuestMutation> incrementQuestNow(
            UUID islandId, UUID ownerId, String period, String questId,
            long increment, long target, long reward, String batchTransactionId) {
        AccountKey account = AccountKey.island(islandId);
        String transactionId = "quest:" + period + ':' + questId + ':' + ownerId;
        return serializeAccounts(List.of(account), () -> {
            CompletableFuture<LedgerDao.QuestMutation> write = dao.incrementQuest(
                    account, ownerId, period, questId, increment, target, reward,
                    transactionId, batchTransactionId);
            return resolveExceptionalWrite(write, () ->
                    dao.resolveQuestIncrementCommit(account, ownerId, period, questId,
                            increment, target, reward, batchTransactionId))
                    .thenCompose(result -> dao.balance(account).handle((current, failure) -> {
                        long authoritative = current == null
                                ? result.accountBalance() : current;
                        if (failure != null) {
                            LOGGER.log(System.Logger.Level.WARNING,
                                    "Could not refresh quest account cache after commit", failure);
                        }
                        balances.put(account.toString(), authoritative);
                        return new LedgerDao.QuestMutation(result.progress(), result.rewarded(),
                                result.rewardedNow(), authoritative);
                    }));
        });
    }

    private long balance(AccountKey account) {
        return balances.getOrDefault(account.toString(), 0L);
    }

    private @NotNull CompletableFuture<LedgerDao.Mutation> mutate(
            AccountKey account, long initial, long delta, String transactionId, String reason) {
        validateAmount(delta == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(delta));
        validateLabel(transactionId, 128, "transaction id");
        validateLabel(reason, 64, "reason");
        return serializeAccounts(List.of(account), () -> {
            CompletableFuture<LedgerDao.Mutation> write =
                    dao.mutate(account, initial, delta, transactionId, reason);
            return resolveExceptionalWrite(write, () ->
                    dao.resolveMutationCommit(account, delta, transactionId, reason))
                    .thenApply(result -> cache(account, result));
        });
    }

    private @NotNull CompletableFuture<LedgerDao.Transfer> transfer(
            AccountKey source, AccountKey target, long sourceInitial, long amount,
            String transactionId, String reason) {
        return transfer(source, target, sourceInitial, amount,
                transactionId, reason,
                () -> CompletableFuture.completedFuture(true));
    }

    private @NotNull CompletableFuture<LedgerDao.Transfer> transfer(
            AccountKey source, AccountKey target, long sourceInitial, long amount,
            String transactionId, String reason,
            AsyncAuthorization authorization) {
        validateAmount(amount);
        validateLabel(transactionId, 120, "transaction id");
        validateLabel(reason, 64, "reason");
        java.util.Objects.requireNonNull(authorization, "authorization");
        return serializeAccounts(List.of(source, target), () ->
                authorize(authorization).thenCompose(authorized -> {
                    if (!authorized) {
                        return authoritativeNoOpTransfer(source, target);
                    }
                    CompletableFuture<LedgerDao.Transfer> write =
                            dao.transfer(source, target, sourceInitial, amount,
                                    transactionId, reason);
                    return resolveExceptionalWrite(write, () ->
                            dao.resolveTransferCommit(source, target, amount,
                                    transactionId, reason))
                            .thenCompose(result ->
                                    refreshTransferBalances(source, target, result));
                }));
    }

    private @NotNull CompletableFuture<LedgerDao.Transfer> transferAll(
            AccountKey source, AccountKey target, long sourceInitial,
            String transactionId, String reason) {
        return transferAll(source, target, sourceInitial,
                transactionId, reason,
                () -> CompletableFuture.completedFuture(true));
    }

    private @NotNull CompletableFuture<LedgerDao.Transfer> transferAll(
            AccountKey source, AccountKey target, long sourceInitial,
            String transactionId, String reason,
            AsyncAuthorization authorization) {
        validateLabel(transactionId, 120, "transaction id");
        validateLabel(reason, 64, "reason");
        java.util.Objects.requireNonNull(authorization, "authorization");
        return serializeAccounts(List.of(source, target), () ->
                authorize(authorization).thenCompose(authorized -> {
                    if (!authorized) {
                        return authoritativeNoOpTransfer(source, target);
                    }
                    CompletableFuture<LedgerDao.Transfer> write =
                            dao.transferAll(source, target, sourceInitial,
                                    transactionId, reason);
                    return resolveExceptionalWrite(write, () ->
                            dao.resolveTransferAllCommit(source, target, sourceInitial,
                                    transactionId, reason))
                            .thenCompose(result ->
                                    refreshTransferBalances(source, target, result));
                }));
    }

    /**
     * Starts the final authorization only after this operation reaches the
     * head of every affected account queue. An exceptional, null, or
     * null-valued decision can never reach the mutation path.
     */
    private static @NotNull CompletableFuture<Boolean> authorize(
            AsyncAuthorization authorization) {
        try {
            CompletableFuture<Boolean> decision = authorization.authorize();
            if (decision == null) {
                return CompletableFuture.failedFuture(
                        new NullPointerException("ledger authorization returned null"));
            }
            return decision.thenApply(Boolean.TRUE::equals);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<LedgerDao.Transfer> authoritativeNoOpTransfer(
            AccountKey source, AccountKey target) {
        return dao.currentBalancesOrZero(source, target).thenApply(current -> {
            balances.put(source.toString(), current.sourceBalance());
            balances.put(target.toString(), current.targetBalance());
            return new LedgerDao.Transfer(false, false,
                    current.sourceBalance(), current.targetBalance(), 0L);
        });
    }

    private CompletableFuture<LedgerDao.Transfer> refreshTransferBalances(
            AccountKey source, AccountKey target, LedgerDao.Transfer committed) {
        return dao.currentBalances(source, target).handle((current, failure) -> {
            long sourceBalance = current == null
                    ? committed.sourceBalance() : current.sourceBalance();
            long targetBalance = current == null
                    ? committed.targetBalance() : current.targetBalance();
            if (failure != null) {
                LOGGER.log(System.Logger.Level.WARNING,
                        "Could not refresh transfer account caches after commit", failure);
            }
            balances.put(source.toString(), sourceBalance);
            balances.put(target.toString(), targetBalance);
            return new LedgerDao.Transfer(committed.applied(), committed.insufficient(),
                    sourceBalance, targetBalance, committed.transferredAmount());
        });
    }

    /**
     * Single-instance account sequencer. It preserves commit/cache completion
     * order for operations touching the same player or shared island account,
     * while unrelated islands can still use the SQL pool concurrently.
     */
    private <T> CompletableFuture<T> serializeAccounts(
            List<AccountKey> accounts, Supplier<CompletableFuture<T>> action) {
        List<String> keys = accounts.stream()
                .map(AccountKey::toString)
                .distinct()
                .sorted()
                .toList();
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("at least one ledger account is required");
        }
        CompletableFuture<Void> start = new CompletableFuture<>();
        CompletableFuture<T> operation;
        CompletableFuture<Void> tail;
        synchronized (accountOperationMutex) {
            CompletableFuture<?>[] predecessors = keys.stream()
                    .map(accountOperations::get)
                    .filter(java.util.Objects::nonNull)
                    .toArray(CompletableFuture[]::new);
            CompletableFuture<Void> predecessor = CompletableFuture.allOf(predecessors)
                    .handle((ignored, failure) -> null);
            operation = start.thenCompose(ignored -> predecessor)
                    .thenCompose(ignored -> {
                        try {
                            CompletableFuture<T> submitted = action.get();
                            return submitted == null
                                    ? CompletableFuture.failedFuture(
                                    new NullPointerException("ledger action returned null"))
                                    : submitted;
                        } catch (Throwable failure) {
                            return CompletableFuture.failedFuture(failure);
                        }
                    });
            tail = operation.handle((ignored, failure) -> null);
            for (String key : keys) {
                accountOperations.put(key, tail);
            }
        }
        CompletableFuture<Void> registeredTail = tail;
        tail.whenComplete((ignored, failure) -> {
            synchronized (accountOperationMutex) {
                for (String key : keys) {
                    accountOperations.remove(key, registeredTail);
                }
            }
        });
        CompletableFuture<T> exposed = new CompletableFuture<>();
        operation.whenComplete((value, failure) -> {
            if (failure == null) {
                exposed.complete(value);
            } else {
                exposed.completeExceptionally(failure);
            }
        });
        start.complete(null);
        return exposed;
    }

    /**
     * JDBC can durably commit and still lose the completion acknowledgement
     * while returning/closing the pooled connection. Resolve only by reading
     * the immutable audit row under the same transaction id; never replay or
     * compensate an ambiguous write.
     */
    private static <T> CompletableFuture<T> resolveExceptionalWrite(
            CompletableFuture<T> write,
            Supplier<CompletableFuture<Optional<T>>> resolver) {
        CompletableFuture<T> outcome = new CompletableFuture<>();
        write.whenComplete((value, failure) -> {
            if (failure == null) {
                outcome.complete(value);
                return;
            }

            CompletableFuture<Optional<T>> lookup;
            try {
                lookup = resolver.get();
            } catch (Throwable lookupStartFailure) {
                completeResolutionFailure(outcome, lookupStartFailure, failure);
                return;
            }
            lookup.whenComplete((resolved, lookupFailure) -> {
                if (lookupFailure != null) {
                    completeResolutionFailure(outcome, lookupFailure, failure);
                } else if (resolved.isPresent()) {
                    outcome.complete(resolved.get());
                } else {
                    outcome.completeExceptionally(unwrap(failure));
                }
            });
        });
        return outcome;
    }

    private static void completeResolutionFailure(
            CompletableFuture<?> outcome, Throwable resolutionFailure,
            Throwable originalFailure) {
        Throwable resolved = unwrap(resolutionFailure);
        Throwable original = unwrap(originalFailure);
        if (resolved != original) {
            resolved.addSuppressed(original);
        }
        outcome.completeExceptionally(resolved);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private LedgerDao.Mutation cache(AccountKey account, LedgerDao.Mutation mutation) {
        balances.put(account.toString(), mutation.balance());
        return mutation;
    }

    private static void validateInventoryOperation(
            UUID playerId, long delta, String transactionId,
            LedgerDao.InventoryOperation operation) {
        validateLabel(operation.operationId(), 128, "inventory operation id");
        if (!playerId.equals(operation.playerId())
                || !transactionId.equals(operation.transactionId())
                || operation.status() != LedgerDao.InventoryStatus.PENDING) {
            throw new IllegalArgumentException("inventory operation identity mismatch");
        }
        if (operation.type() == LedgerDao.InventoryOperationType.GRANT) {
            byte[] payload = operation.itemPayload();
            /*
             * Nadanie nie może uznawać konta — od tego jest usunięcie — ale wolno
             * mu nic nie kosztować. Nagroda sezonowa i kosmetyka wydawana przez
             * sklep nie mają ceny, a bez tego musiałyby omijać outbox i dodawać
             * przedmiot wprost do ekwipunku, czyli nietrwale.
             */
            if (delta > 0L || payload == null || payload.length == 0
                    || payload.length > 262_144
                    || !operation.lines().isEmpty()
                    || operation.auxiliaryType() != null
                    || operation.auxiliaryKey() != null
                    || operation.auxiliaryValue() != null) {
                throw new IllegalArgumentException("invalid grant inventory operation");
            }
            return;
        }
        /*
         * Usunięcie nie może obciążać gracza — od tego jest nadanie — ale wolno mu
         * nie płacić nic. Kuźnia przy aktualnej kolejności sagi (składniki i
         * zapłata najpierw, wyrób w mutacji pobocznej tej samej operacji) zleca
         * usunięcie z ujemną deltą: to zapłata za wyrób, który zostanie wydany
         * przez handler FORGE_PRODUCT. Wymóg delta >= 0 unieruchamiał całą kuźnię
         * — każda płatna receptura kończyła się ERROR przed jakimkolwiek zapisem.
         * Ujemna delta jest więc legalna wyłącznie z uzasadnioną mutacją
         * poboczną; bez niej zostaje dotychczasowa reguła.
         */
        boolean paidForgeRemoval = delta < 0L
                && InventoryOutbox.AUXILIARY_FORGE_PRODUCT.equals(operation.auxiliaryType());
        if ((delta < 0L && !paidForgeRemoval) || operation.itemPayload() != null
                || operation.lines().isEmpty() || operation.lines().size() > 128) {
            throw new IllegalArgumentException("invalid removal inventory operation");
        }
        for (LedgerDao.InventoryLine line : operation.lines()) {
            validateLabel(line.materialKey(), 64, "inventory material");
            if (line.removeAmount() <= 0 || line.baselineCount() < line.removeAmount()) {
                throw new IllegalArgumentException("invalid inventory removal line");
            }
        }
        if ((operation.auxiliaryType() == null) != (operation.auxiliaryKey() == null)
                || (operation.auxiliaryType() == null) != (operation.auxiliaryValue() == null)) {
            throw new IllegalArgumentException("incomplete auxiliary inventory operation");
        }
        if (operation.auxiliaryType() != null) {
            validateLabel(operation.auxiliaryType(), 24, "auxiliary operation type");
            validateLabel(operation.auxiliaryKey(), 128, "auxiliary operation key");
        }
    }

    private static void validateAmount(long amount) {
        if (amount < 0L || amount > LedgerDao.MAX_BALANCE) {
            throw new IllegalArgumentException("amount outside ledger range");
        }
    }

    private static void validateLabel(String value, int maxLength, String label) {
        if (value.isBlank() || value.length() > maxLength || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
    }

    @FunctionalInterface
    interface AsyncAuthorization {
        @NotNull CompletableFuture<Boolean> authorize();
    }
}
