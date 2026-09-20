package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Inventories;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItem;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Transactional outbox joining the SQLite ledger to Minecraft playerdata.
 *
 * <p>Grant operations use a PDC receipt and two forced playerdata checkpoints:
 * PENDING -> DELIVERED proves that the tagged item is on disk, DELIVERED ->
 * COMPLETE proves that the temporary receipt was removed. Removal operations
 * persist a material baseline and converge inventory to baseline-minus-sale
 * before their single COMPLETE checkpoint.
 */
public final class InventoryOutbox implements Listener {

    public static final String AUXILIARY_WAND_USE = "WAND_USE";
    /** Wyrób kuźni należny za składniki pobrane w tej samej operacji. */
    public static final String AUXILIARY_FORGE_PRODUCT = "FORGE_PRODUCT";
    /** Towar sklepu wydany za walutę przedmiotową pobraną w tej samej operacji. */
    public static final String AUXILIARY_SHOP_PRODUCT = "SHOP_PRODUCT";
    static final int LEASE_RETRY_LIMIT = LeaseRetryScheduler.LEASE_RETRY_LIMIT;
    static final long LEASE_RETRY_SLOW_DELAY_TICKS = LeaseRetryScheduler.LEASE_RETRY_SLOW_DELAY_TICKS;

    private final JavaPlugin plugin;
    private final LedgerService ledger;
    private final PlayerOperationCoordinator coordinator;
    private final NamespacedKey receiptKey;
    private final Object lifecycleLock = new Object();
    private final OperationCallbackRegistry callbackRegistry;
    private final CheckpointCoordinator checkpoints;
    private final LeaseRetryScheduler leaseRetries;
    private final java.util.Set<UUID> recoveryPlayers = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> checkpointRequiredOperations =
            ConcurrentHashMap.newKeySet();
    /**
     * Backoff ścieżek bazodanowych. Nieograniczony — awaria bazy sama się goi,
     * a porzucenie przy niej trwałej operacji rozjechałoby saldo z ekwipunkiem.
     * Dotykany także z wątku puli SQL, bo {@code retryLater} bywa wołane z
     * {@code whenComplete}; służy wyłącznie doborowi opóźnienia, więc zgubiona
     * inkrementacja jest bez znaczenia.
     */
    private final Map<UUID, Integer> transientAttempts = new ConcurrentHashMap<>();
    /**
     * Budżet zbieżności, ograniczony. Klucz zewnętrzny to gracz, bo ścieżki, które
     * naprawdę porzucają operację ({@code preserveRecoveryForJoin}, {@code onQuit}),
     * znają tylko jego — identyfikatora operacji nie mają w zasięgu.
     */
    private final Map<UUID, Map<String, Integer>> convergenceAttempts =
            new ConcurrentHashMap<>();
    private final Map<String, AuxiliaryHandler> auxiliaryHandlers = new ConcurrentHashMap<>();
    /**
     * Rejestr customowych przedmiotów. Potrzebny nie tylko przy zleceniu, ale i
     * przy odtwarzaniu po restarcie, gdzie nie ma żadnego wywołującego, który
     * mógłby go podać. Wstrzykiwany setterem, tak jak {@code auxiliaryHandler}.
     */
    private volatile CustomItemService customItems;
    private volatile boolean accepting;
    private volatile boolean stopped;
    private boolean startupInspectionComplete;

    public InventoryOutbox(@NotNull JavaPlugin plugin, @NotNull LedgerService ledger,
                    @NotNull PlayerOperationCoordinator coordinator) {
        this(plugin, ledger, coordinator, LEASE_RETRY_SLOW_DELAY_TICKS);
    }

    public InventoryOutbox(@NotNull JavaPlugin plugin, @NotNull LedgerService ledger,
                    @NotNull PlayerOperationCoordinator coordinator,
                    long rejectedRetryDelayTicks) {
        this(plugin, ledger, coordinator, rejectedRetryDelayTicks, Player::saveData);
    }

    public InventoryOutbox(@NotNull JavaPlugin plugin, @NotNull LedgerService ledger,
                    @NotNull PlayerOperationCoordinator coordinator,
                    long rejectedRetryDelayTicks,
                    @NotNull PlayerDataCheckpoint checkpointWriter) {
        if (rejectedRetryDelayTicks < 1L) {
            throw new IllegalArgumentException("rejected retry delay must be positive");
        }
        this.plugin = plugin;
        this.ledger = ledger;
        this.coordinator = coordinator;
        this.receiptKey = new NamespacedKey(plugin, "inventory_receipt");
        this.callbackRegistry = new OperationCallbackRegistry(plugin.getLogger());
        this.checkpoints = new CheckpointCoordinator(plugin, checkpointWriter, coordinator,
                checkpointRequiredOperations, () -> accepting && plugin.isEnabled(),
                this::preserveRecoveryForJoin, this::quarantineAfterCheckpointFailure);
        this.leaseRetries = new LeaseRetryScheduler(plugin, coordinator, rejectedRetryDelayTicks,
                () -> accepting && plugin.isEnabled(), this::preserveRecoveryForJoin,
                this::attemptReconcile);
    }

    /**
     * Rejestruje obsługę jednego typu mutacji pobocznej.
     *
     * <p>Wcześniej był tu pojedynczy slot i jeden właściciel. Drugi konsument po cichu
     * odbierał obsługę pierwszemu, a handler różdżek dodatkowo odrzucał każdy typ poza
     * własnym — więc dopięcie czegokolwiek nowego wywracało cudzą operację, bez śladu
     * przy kompilacji. Klucz to {@code type} z {@link AuxiliaryMutation}.
     */
    public void registerAuxiliaryHandler(@NotNull String type, @NotNull AuxiliaryHandler handler) {
        AuxiliaryHandler previous = auxiliaryHandlers.put(type, handler);
        if (previous != null) {
            plugin.getLogger().warning("Podmieniono obsługę mutacji pobocznej '" + type
                    + "' — dwa moduły zgłosiły się do tego samego typu.");
        }
    }

    /** Czy dla tego typu mutacji ktoś się zgłosił. Brak zgłoszenia musi wstrzymać operację. */
    static boolean hasHandlerFor(@NotNull Set<String> registered, @Nullable String type) {
        return type == null || registered.contains(type);
    }

    private boolean applyAuxiliary(Player player, LedgerDao.InventoryOperation operation) {
        String type = operation.auxiliaryType();
        if (type == null) {
            return true;
        }
        AuxiliaryHandler handler = auxiliaryHandlers.get(type);
        if (handler == null) {
            plugin.getLogger().severe("Operacja " + operation.operationId()
                    + " niesie mutację poboczną '" + type
                    + "', do której nikt się nie zgłosił — zostaje nierozliczona.");
            return false;
        }
        return handler.apply(player, operation);
    }

    /**
     * Idempotentne wydanie przedmiotu należnego z mutacji pobocznej operacji
     * REMOVE (wyrób kuźni, towar sklepu za walutę).
     *
     * <p>Mutacja poboczna biegnie przy każdym przejściu operacji w statusie
     * PENDING (ponowienie po awarii przejścia statusu, odtwarzanie po crashu
     * między checkpointem a COMPLETE, budżet zbieżności), więc handler nie może
     * gołe {@code addItem} — dołożyłby wyrób drugi i kolejny raz. Tu obowiązuje
     * ten sam mechanizm co przy nadaniach: stosy znaczone są paragonem PDC z
     * identyfikatorem operacji, liczy się już obecne i dokłada tylko brakującą
     * różnicę. Znacznik jest tymczasowy jak w nadaniach: znika, gdy gracz
     * odbierze przedmiot z ekwipunku.
     *
     * @return {@code true}, gdy ekwipunek zawiera dokładnie należną liczbę
     *         sztuk; {@code false} zostawia operację oczekującą (pełny ekwipunek
     *         nie gubi wyrobu — dołoży się go, gdy zrobi się miejsce)
     */
    public boolean deliverTaggedProduct(@NotNull Player player, @NotNull String operationId,
                                        @NotNull ItemStack product) {
        boolean converged = InventoryReceiptOps.applyTaggedDelivery(
                player.getInventory(), operationId, receiptKey, product);
        if (converged) {
            /*
             * Znacznik musi trafić na dysk przed przejściem do COMPLETE —
             * bez tego crash tuż po dodaniu wyrobu skończyłby się jego dupem
             * (po powrocie paragonu nie byłoby, a operacja wciąż PENDING).
             */
            checkpointRequiredOperations.add(operationId);
        }
        return converged;
    }

    public void setCustomItemService(CustomItemService customItems) {
        this.customItems = customItems;
    }

    // M1-D: leave/kick/delete/reset blocks new outbox/ledger mutations
    private volatile org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileMutationGuard mutationGuard;

    public void setMutationGuard(org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileMutationGuard guard) {
        this.mutationGuard = guard;
    }

    /**
     * Bramka strażnika mutacji — bez blokowania wątku wywołującego.
     *
     * <p>Dawniej było tu {@code get(600 ms)}. Odpowiedź strażnika to zapytanie
     * do tej samej puli SQL, na której siedzi wołający (rozliczanie zadań biega
     * na {@code …-Sql-N}), więc czekanie na nią wątkiem puli zagładzało pulę:
     * przy {@code maximum-pool-size: 2} dwa takie oczekiwania naraz blokowały
     * wykonanie samego zapytania i limit czasu mijał zawsze. Fail-closed
     * zamieniał to w utratę nagrody, choć żadna blokada nie była aktywna.
     *
     * <p>Polityka bez zmian: gdy naprawdę nie da się zweryfikować, mutacja nie
     * idzie. Zmienia się sposób czekania i to, że powód mówi prawdę —
     * {@link Outcome#REJECTED} znaczy „trwa przenosiny/kick/usunięcie",
     * {@link Outcome#BUSY} znaczy „nie udało się zweryfikować, spróbuj później".
     *
     * @param player gracz, na którego wątek regionu ma wrócić dalszy ciąg;
     *               {@code null} dla nadania offline, które nie dotyka świata
     */
    private void whenMutationAllowed(@Nullable Player player, @NotNull UUID playerId,
                                     @NotNull String operationId, @NotNull String what,
                                     @NotNull Consumer<Outcome> callback,
                                     @NotNull Runnable allowed) {
        var guard = this.mutationGuard;
        if (guard == null) {
            allowed.run();
            return;
        }
        guard.isBlockedForPlayer(playerId).whenComplete((blocked, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING, "Nie udało się zweryfikować strażnika mutacji dla "
                        + playerId + " (" + what + ' ' + operationId
                        + ") — wstrzymuję fail-closed, do ponowienia", failure);
                callback.accept(Outcome.BUSY);
                return;
            }
            if (Boolean.TRUE.equals(blocked)) {
                plugin.getLogger().warning("Wstrzymano " + what + ' ' + operationId + " dla " + playerId
                        + " — trwa opuszczanie/wyrzucenie/usunięcie/reset wyspy");
                callback.accept(Outcome.REJECTED);
                return;
            }
            resumeOnOwnerThread(player, callback, allowed);
        });
    }

    /**
     * Async-to-region handoff: dalszy ciąg czyta i zmienia ekwipunek gracza,
     * a strażnik domyka obietnicę na wątku puli SQL, więc ciąg musi wrócić na
     * wątek regionu gracza. Gdy gracz zdążył zniknąć, operacja jeszcze się nie
     * zaczęła — nic nie przepada, wołający dostaje {@link Outcome#BUSY}.
     */
    private void resumeOnOwnerThread(@Nullable Player player, @NotNull Consumer<Outcome> callback,
                                     @NotNull Runnable allowed) {
        if (player == null) {
            allowed.run();
            return;
        }
        // Folia potrafi wywołać „retired" i oddać null naraz — doręczamy raz.
        java.util.concurrent.atomic.AtomicBoolean delivered =
                new java.util.concurrent.atomic.AtomicBoolean();
        Runnable giveUp = () -> {
            if (delivered.compareAndSet(false, true)) {
                callback.accept(Outcome.BUSY);
            }
        };
        try {
            var scheduled = player.getScheduler().run(plugin, ignored -> {
                if (delivered.compareAndSet(false, true)) {
                    allowed.run();
                }
            }, giveUp);
            if (scheduled == null) {
                giveUp.run();
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.WARNING,
                    "Nie udało się wrócić na wątek regionu gracza " + player.getUniqueId()
                            + " po sprawdzeniu strażnika mutacji", rejected);
            giveUp.run();
        }
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (stopped) {
                throw new IllegalStateException("inventory outbox cannot restart after stop");
            }
            accepting = false;
            startupInspectionComplete = false;
            coordinator.closeInventoryGate();
        }
        ledger.pendingInventoryPlayers().whenComplete((players, failure) -> {
            if (failure != null) {
                if (stopped) {
                    return;
                }
                plugin.getLogger().log(Level.SEVERE,
                        "Failed to inspect the inventory outbox at startup; "
                                + "SkyBlockGameplay is fail-closed", failure);
                disablePluginFailClosed();
                return;
            }
            synchronized (lifecycleLock) {
                if (stopped) {
                    return;
                }
                for (UUID playerId : players) {
                    recoveryPlayers.add(playerId);
                    coordinator.guardInventory(playerId);
                }
                startupInspectionComplete = true;
                accepting = true;
                coordinator.openInventoryGate();
            }
            if (!players.isEmpty()) {
                plugin.getLogger().warning("Recovering " + players.size()
                        + " player inventory outbox entr"
                        + (players.size() == 1 ? "y" : "ies"));
            }
            try {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                    for (UUID playerId : players) {
                        Player player = plugin.getServer().getPlayer(playerId);
                        if (player != null && player.isOnline()) {
                            reconcile(player);
                        }
                    }
                });
            } catch (RuntimeException rejected) {
                if (!players.isEmpty()) {
                    plugin.getLogger().log(Level.WARNING,
                            "Startup outbox reconciliation scheduling was rejected; "
                                    + "pending players remain protected on next join",
                            rejected);
                }
            }
        });
    }

    public @NotNull ShutdownState stop() {
        synchronized (lifecycleLock) {
            /*
             * Closing the coordinator first makes every item event fail-closed
             * and prevents late completions from releasing their leases. SQL
             * work is not awaited here: its atomic outbox row is the restart
             * recovery boundary, and blocking on it would stall the lifecycle
             * thread.
             */
            coordinator.stop();
            stopped = true;
            accepting = false;
            leaseRetries.clear();
            convergenceAttempts.clear();
            transientAttempts.clear();
            CheckpointCoordinator.Snapshot checkpointSnapshot = this.checkpoints.snapshot();
            return new ShutdownState(startupInspectionComplete,
                    recoveryPlayers.size(), callbackRegistry.count(),
                    coordinator.hasOutstandingInventoryProtection(),
                    checkpointSnapshot.checkpointInProgress(), checkpointSnapshot.queuedCount(),
                    checkpointSnapshot.maxQueueDepth(), checkpointSnapshot.count(),
                    checkpointSnapshot.totalMillis(), checkpointSnapshot.maxMillis());
        }
    }

    /**
     * Fail-closed readiness contract for other economy adapters in this
     * package. A player cannot mutate through them until startup inspection
     * succeeded and every recovered inventory operation has been reconciled.
     */
    public boolean isEconomyReady(@NotNull UUID playerId) {
        return accepting && !recoveryPlayers.contains(playerId);
    }

    public void beginGrant(@NotNull Player player, long delta, @NotNull String operationId,
                    @NotNull String reason, @NotNull ItemStack grantedItem,
                    @NotNull Consumer<Outcome> callback) {
        if (!accepting || !plugin.isEnabled()) {
            callback.accept(Outcome.REJECTED);
            return;
        }
        if (recoveryPlayers.contains(player.getUniqueId())) {
            callback.accept(Outcome.BUSY);
            return;
        }
        whenMutationAllowed(player, player.getUniqueId(), operationId, "nadanie", callback,
                () -> continueGrant(player, delta, operationId, reason, grantedItem, callback));
    }

    private void continueGrant(@NotNull Player player, long delta, @NotNull String operationId,
                    @NotNull String reason, @NotNull ItemStack grantedItem,
                    @NotNull Consumer<Outcome> callback) {
        if (!accepting || !plugin.isEnabled()) {
            callback.accept(Outcome.REJECTED);
            return;
        }
        if (grantedItem.getType().isAir() || grantedItem.getAmount() <= 0) {
            callback.accept(Outcome.REJECTED);
            return;
        }
        byte[] payload;
        try {
            ItemStack tagged = grantedItem.clone();
            tagged.editMeta(meta -> meta.getPersistentDataContainer().set(
                    receiptKey, PersistentDataType.STRING, operationId));
            payload = tagged.serializeAsBytes();
        } catch (RuntimeException serializationFailure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not serialize inventory grant " + operationId,
                    serializationFailure);
            callback.accept(Outcome.ERROR);
            return;
        }
        Optional<PlayerOperationCoordinator.Lease> acquired =
                coordinator.tryAcquire(player.getUniqueId(), true);
        if (acquired.isEmpty()) {
            callback.accept(Outcome.BUSY);
            return;
        }
        PlayerOperationCoordinator.Lease lease = acquired.get();
        long now = System.currentTimeMillis();
        LedgerDao.InventoryOperation operation = new LedgerDao.InventoryOperation(
                operationId, player.getUniqueId(), LedgerDao.InventoryOperationType.GRANT,
                operationId, LedgerDao.InventoryStatus.PENDING, payload,
                null, null, null, List.of(), now, now);
        callbackRegistry.put(operationId, player.getUniqueId(), callback);
        beginMutation(player, lease, delta, operationId, reason, operation);
    }

    public void beginRemoval(@NotNull Player player, long credit, @NotNull String operationId,
                      @NotNull String reason, @NotNull Map<Material, Integer> removals,
                      AuxiliaryMutation auxiliary, @NotNull Consumer<Outcome> callback) {
        beginRemoval(player, credit, operationId, reason, removals, Map.of(),
                auxiliary, callback);
    }

    /**
     * Trwałe usunięcie mieszanki przedmiotów zwykłych i customowych w jednej operacji.
     *
     * <p>Receptura kuźni potrzebuje obu naraz — rafinacja wolframu zabiera cztery
     * surowe wolframy i osiem węgla — a rozbicie tego na dwie operacje zostawiłoby
     * okno, w którym połowa składników już zniknęła, a druga jeszcze nie.
     *
     * @param customRemovals identyfikator CustomItems → sztuki
     */
    public void beginRemoval(@NotNull Player player, long credit, @NotNull String operationId,
                      @NotNull String reason, @NotNull Map<Material, Integer> removals,
                      @NotNull Map<String, Integer> customRemovals,
                      AuxiliaryMutation auxiliary, @NotNull Consumer<Outcome> callback) {
        if (!accepting || !plugin.isEnabled()) {
            callback.accept(Outcome.REJECTED);
            return;
        }
        if (recoveryPlayers.contains(player.getUniqueId())) {
            callback.accept(Outcome.BUSY);
            return;
        }
        whenMutationAllowed(player, player.getUniqueId(), operationId, "pobranie", callback,
                () -> continueRemoval(player, credit, operationId, reason, removals,
                        customRemovals, auxiliary, callback));
    }

    private void continueRemoval(@NotNull Player player, long credit, @NotNull String operationId,
                      @NotNull String reason, @NotNull Map<Material, Integer> removals,
                      @NotNull Map<String, Integer> customRemovals,
                      AuxiliaryMutation auxiliary, @NotNull Consumer<Outcome> callback) {
        if (!accepting || !plugin.isEnabled()) {
            callback.accept(Outcome.REJECTED);
            return;
        }
        // SKYBLOCK-2-5: strażnik przeniesiony z testu do produkcji — operacja z
        // mutacją poboczną bez zarejestrowanego handlera to natychmiastowy
        // REJECTED (nic nie pobrane), nie 5 minut retry i kwarantanna.
        if (!hasHandlerFor(auxiliaryHandlers.keySet(), auxiliary == null ? null : auxiliary.type())) {
            plugin.getLogger().severe("Removal " + operationId + " niesie mutację poboczną '"
                    + auxiliary.type() + "' bez handlera — odrzucam przed pobraniem czegokolwiek.");
            callback.accept(Outcome.REJECTED);
            return;
        }
        Optional<PlayerOperationCoordinator.Lease> acquired =
                coordinator.tryAcquire(player.getUniqueId(), true);
        if (acquired.isEmpty()) {
            callback.accept(Outcome.BUSY);
            return;
        }
        PlayerOperationCoordinator.Lease lease = acquired.get();
        List<LedgerDao.InventoryLine> lines = new ArrayList<>();
        for (Map.Entry<Material, Integer> entry : removals.entrySet()) {
            int amount = entry.getValue();
            int baseline = Inventories.countPlain(player.getInventory(), entry.getKey());
            if (amount <= 0 || baseline < amount) {
                coordinator.release(lease);
                callback.accept(Outcome.REJECTED);
                return;
            }
            // Pusty identyfikator customowy = zwykły przedmiot liczony przez countPlain.
            lines.add(new LedgerDao.InventoryLine(entry.getKey().getKey().toString(),
                    "", amount, baseline));
        }
        for (Map.Entry<String, Integer> entry : customRemovals.entrySet()) {
            String customItemId = entry.getKey();
            int amount = entry.getValue();
            /*
             * Fail-closed przy braku rejestru: bez niego nie umiemy ani policzyć
             * baseline'u, ani później zbiec do celu, a operacja zapisana „na pusto"
             * utknęłaby w ponawianiu, dopóki nie trafi do kwarantanny.
             */
            if (customItems == null) {
                coordinator.release(lease);
                callback.accept(Outcome.REJECTED);
                return;
            }
            Material material = customItems.byId(customItemId)
                    .map(CustomItem::material)
                    .orElse(null);
            int baseline = Inventories.countCustom(
                    player.getInventory(), customItemId, customItems);
            if (material == null || amount <= 0 || baseline < amount) {
                coordinator.release(lease);
                callback.accept(Outcome.REJECTED);
                return;
            }
            lines.add(new LedgerDao.InventoryLine(material.getKey().toString(),
                    customItemId, amount, baseline));
        }
        if (lines.isEmpty()) {
            coordinator.release(lease);
            callback.accept(Outcome.REJECTED);
            return;
        }
        long now = System.currentTimeMillis();
        LedgerDao.InventoryOperation operation = new LedgerDao.InventoryOperation(
                operationId, player.getUniqueId(), LedgerDao.InventoryOperationType.REMOVE,
                operationId, LedgerDao.InventoryStatus.PENDING, null,
                auxiliary == null ? null : auxiliary.type(),
                auxiliary == null ? null : auxiliary.key(),
                auxiliary == null ? null : auxiliary.value(),
                lines, now, now);
        callbackRegistry.put(operationId, player.getUniqueId(), callback);
        beginMutation(player, lease, credit, operationId, reason, operation);
    }

    void reconcile(@NotNull Player player) {
        if (!accepting || !plugin.isEnabled()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        coordinator.guardInventory(playerId);
        if (!accepting || !plugin.isEnabled()) {
            return;
        }
        recoveryPlayers.add(playerId);
        attemptReconcile(player, 0);
    }

    /**
     * Trwały dług GRANT dla gracza, który może być offline w chwili zlecenia:
     * wiersz PENDING powstaje natychmiast, a dostawa następuje przy najbliższym
     * wejściu gracza — reconcile onJoin czyta wiersze autorytatywnie. Operacja
     * idempotentna po {@code operationId} jak każda inna w outboxie.
     */
    public void beginOfflineGrant(@NotNull UUID playerId, long delta, @NotNull String operationId,
                                   @NotNull String reason, @NotNull ItemStack grantedItem,
                                   @NotNull Consumer<Outcome> callback) {
        if (!accepting || !plugin.isEnabled()) {
            callback.accept(Outcome.REJECTED);
            return;
        }
        whenMutationAllowed(null, playerId, operationId, "nadanie offline", callback,
                () -> continueOfflineGrant(playerId, delta, operationId, reason, grantedItem, callback));
    }

    private void continueOfflineGrant(@NotNull UUID playerId, long delta, @NotNull String operationId,
                                   @NotNull String reason, @NotNull ItemStack grantedItem,
                                   @NotNull Consumer<Outcome> callback) {
        if (!accepting || !plugin.isEnabled()) {
            callback.accept(Outcome.REJECTED);
            return;
        }
        if (grantedItem.getType().isAir() || grantedItem.getAmount() <= 0) {
            callback.accept(Outcome.REJECTED);
            return;
        }
        byte[] payload;
        try {
            ItemStack tagged = grantedItem.clone();
            tagged.editMeta(meta -> meta.getPersistentDataContainer().set(
                    receiptKey, PersistentDataType.STRING, operationId));
            payload = tagged.serializeAsBytes();
        } catch (RuntimeException serializationFailure) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not serialize offline inventory grant " + operationId,
                    serializationFailure);
            callback.accept(Outcome.ERROR);
            return;
        }
        long now = System.currentTimeMillis();
        LedgerDao.InventoryOperation operation = new LedgerDao.InventoryOperation(
                operationId, playerId, LedgerDao.InventoryOperationType.GRANT,
                operationId, LedgerDao.InventoryStatus.PENDING, payload,
                null, null, null, List.of(), now, now);
        try {
            ledger.mutatePlayerWithInventoryOperation(playerId, delta, operationId, reason, operation)
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            // Niejednoznaczne zakończenie zapisu: rozstrzyga odczyt wiersza.
                            ledger.inventoryOperation(operationId).whenComplete((stored, lookupFailure) -> {
                                if (stored.isPresent()) {
                                    callback.accept(Outcome.DEFERRED);
                                    reconcileIfOnline(playerId);
                                } else {
                                    plugin.getLogger().log(Level.WARNING,
                                            "Offline inventory operation did not commit " + operationId,
                                            failure);
                                    callback.accept(Outcome.ERROR);
                                }
                            });
                            return;
                        }
                        if (result.operation() == null) {
                            callback.accept(Outcome.REJECTED);
                            return;
                        }
                        if (result.operation().status() == LedgerDao.InventoryStatus.COMPLETE) {
                            callback.accept(Outcome.SUCCESS);
                            return;
                        }
                        callback.accept(Outcome.DEFERRED);
                        // Gracz mógł wejść między zleceniem a zapisem — rozlicz od razu.
                        reconcileIfOnline(playerId);
                    });
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.WARNING,
                    "Offline inventory operation was rejected before submission " + operationId,
                    rejected);
            callback.accept(Outcome.ERROR);
        }
    }

    private void reconcileIfOnline(@NotNull UUID playerId) {
        if (plugin.getServer() == null) {
            return;
        }
        Player online = plugin.getServer().getPlayer(playerId);
        if (online != null && online.isOnline()) {
            reconcile(online);
        }
    }

    private void attemptReconcile(Player player, int attempt) {
        if (!accepting || !plugin.isEnabled() || !player.isOnline()) {
            return;
        }
        if (leaseRetries.hasRetryToken(player.getUniqueId())) {
            return;
        }
        Optional<PlayerOperationCoordinator.Lease> acquired =
                coordinator.tryAcquire(player.getUniqueId(), true);
        if (acquired.isPresent()) {
            loadAndApply(player, acquired.get());
            return;
        }
        leaseRetries.scheduleLeaseRetry(player, attempt);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        reconcile(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        if (coordinator.isInventorySensitiveBusy(playerId) || callbackRegistry.hasPendingFor(playerId)) {
            /*
             * Keep both readiness and the existing lease fail-closed until a
             * rejoin performs an authoritative pending-row read. Releasing
             * here races an in-flight commit and can strand a paid operation.
             */
            coordinator.guardInventory(playerId);
            recoveryPlayers.add(playerId);
        }
        leaseRetries.cancelRetry(playerId);
        convergenceAttempts.remove(playerId);
        transientAttempts.remove(playerId);
        callbackRegistry.removeForPlayer(playerId);
    }

    private void beginMutation(Player player, PlayerOperationCoordinator.Lease lease,
                               long delta, String operationId, String reason,
                               LedgerDao.InventoryOperation operation) {
        try {
            ledger.mutatePlayerWithInventoryOperation(player.getUniqueId(), delta,
                            operationId, reason, operation)
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            resolveAmbiguousBegin(player, lease, operationId, failure);
                            return;
                        }
                        if (result.operation() == null) {
                            schedulePlayer(player, lease, () -> {
                                coordinator.release(lease);
                                callbackRegistry.complete(operationId, player.getUniqueId(),
                                        result.mutation().insufficient()
                                                ? Outcome.INSUFFICIENT : Outcome.REJECTED);
                            });
                            return;
                        }
                        if (result.operation().status()
                                == LedgerDao.InventoryStatus.COMPLETE) {
                            schedulePlayer(player, lease, () -> {
                                callbackRegistry.complete(operationId, player.getUniqueId(),
                                        Outcome.SUCCESS);
                                coordinator.release(lease);
                            });
                        } else {
                            scheduleLoad(player, lease);
                        }
                    });
        } catch (RuntimeException rejected) {
            coordinator.release(lease);
            callbackRegistry.complete(operationId, player.getUniqueId(), Outcome.ERROR);
            plugin.getLogger().log(Level.WARNING,
                    "Inventory operation was rejected before submission " + operationId,
                    rejected);
        }
    }

    private void resolveAmbiguousBegin(Player player, PlayerOperationCoordinator.Lease lease,
                                       String operationId, Throwable originalFailure) {
        ledger.inventoryOperation(operationId).whenComplete((operation, lookupFailure) -> {
            if (lookupFailure != null) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not resolve inventory operation " + operationId
                                + " after an ambiguous database completion",
                        originalFailure);
                retryLater(player, lease);
            } else if (operation.isPresent()) {
                scheduleLoad(player, lease);
            } else {
                plugin.getLogger().log(Level.WARNING,
                        "Inventory operation did not commit " + operationId, originalFailure);
                schedulePlayer(player, lease, () -> {
                    coordinator.release(lease);
                    callbackRegistry.complete(operationId, player.getUniqueId(), Outcome.ERROR);
                });
            }
        });
    }

    private void loadAndApply(Player player, PlayerOperationCoordinator.Lease lease) {
        if (!coordinator.owns(lease)) {
            return;
        }
        ledger.pendingInventoryOperations(player.getUniqueId())
                .whenComplete((operations, failure) -> {
                    if (failure != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Failed to load pending inventory operations for "
                                        + player.getUniqueId(), failure);
                        retryLater(player, lease);
                        return;
                    }
                    // Odczyt się udał, więc baza wróciła: budżet backoffu od nowa.
                    transientAttempts.remove(lease.playerId());
                    schedulePlayer(player, lease, () -> {
                        if (operations.isEmpty()) {
                            Optional<String> dangling = callbackRegistry.findDanglingFor(player.getUniqueId());
                            if (dangling.isPresent()) {
                                resolveDanglingCallback(player, lease, dangling.get());
                            } else {
                                recoveryPlayers.remove(player.getUniqueId());
                                coordinator.releaseInventoryGuard(player.getUniqueId());
                                coordinator.release(lease);
                            }
                            return;
                        }
                        apply(player, lease, operations.getFirst());
                    });
                });
    }

    /**
     * Widoczne w pakiecie wyłącznie dla testu bramki stanu końcowego: obie drogi,
     * które potrafią podać tu odstawioną operację ({@code resolveDanglingCallback}
     * i {@code reloadAfterTransition}), czytają wiersz po samym identyfikatorze i
     * są osiągalne tylko przez wyścig, którego z testu nie da się ustawić.
     */
    void apply(Player player, PlayerOperationCoordinator.Lease lease,
               LedgerDao.InventoryOperation operation) {
        if (!coordinator.owns(lease)) {
            return;
        }
        if (!player.isOnline()) {
            preserveRecoveryForJoin(lease);
            return;
        }
        /*
         * Jedyny punkt dławiący dla operacji odstawionych. Filtr na liście
         * oczekujących tu nie wystarcza: selectInventoryOperation czyta po samym
         * identyfikatorze, bez predykatu statusu, więc resolveDanglingCallback i
         * reloadAfterTransition wchodzą tędy z pominięciem tamtego filtra. Bez tej
         * bramki applyRemovalOperation zbiegłoby nieaktualny baseline i skasowało
         * graczowi wszystko, co zdobył od czasu odstawienia.
         */
        /*
         * A2-25: właściciel operacji. Wiersz jest tu czytany po samym
         * identyfikatorze (resolveDanglingCallback, reloadAfterTransition), a dwa
         * identyfikatory są celowo wspólne dla całej wyspy — bez tej bramki
         * apply() stosował graczowi B operację zleconą przez gracza A: GRANT
         * wydawał cudzą nagrodę, a REMOVE zbiegał do cudzego baselineu i kasował
         * B ekwipunek. Domknięcie jak przy kwarantannie: ERROR i dalej łańcuchem.
         */
        if (!operation.playerId().equals(player.getUniqueId())) {
            plugin.getLogger().warning("Refusing inventory operation "
                    + operation.operationId() + " owned by " + operation.playerId()
                    + " for " + player.getUniqueId());
            callbackRegistry.complete(operation.operationId(), player.getUniqueId(),
                    Outcome.ERROR);
            loadAndApply(player, lease);
            return;
        }
        if (operation.status().isQuarantined()) {
            skipQuarantined(player, lease, operation);
            return;
        }
        try {
            switch (operation.type()) {
                case GRANT -> applyGrantOperation(player, lease, operation);
                case REMOVE -> applyRemovalOperation(player, lease, operation);
            }
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.SEVERE,
                    "Inventory outbox reconciliation failed for "
                            + operation.operationId(), failure);
            retryConvergence(player, lease, operation, "reconciliation threw " + failure);
        }
    }

    /**
     * Operacja jest już odstawiona: domykamy zlecającego i idziemy dalej po
     * łańcuchu. Callback musi zostać domknięty <b>przed</b> {@code loadAndApply},
     * inaczej {@code findDanglingFor} znajdzie tę samą operację i wejdzie w nią
     * jeszcze raz.
     */
    private void skipQuarantined(Player player, PlayerOperationCoordinator.Lease lease,
                                 LedgerDao.InventoryOperation operation) {
        plugin.getLogger().warning("Skipping quarantined inventory operation "
                + operation.operationId() + " (" + operation.status() + ") for "
                + player.getUniqueId());
        callbackRegistry.complete(operation.operationId(), player.getUniqueId(), Outcome.ERROR);
        loadAndApply(player, lease);
    }

    private void applyGrantOperation(Player player, PlayerOperationCoordinator.Lease lease,
                                     LedgerDao.InventoryOperation operation) {
        if (operation.status() == LedgerDao.InventoryStatus.PENDING) {
            int receiptBefore = InventoryReceiptOps.receiptAmount(
                    player.getInventory(), operation.operationId(), receiptKey);
            if (!InventoryReceiptOps.applyGrant(player.getInventory(), operation, receiptKey)) {
                int receiptAfter = InventoryReceiptOps.receiptAmount(
                        player.getInventory(), operation.operationId(), receiptKey);
                if (receiptBefore != receiptAfter) {
                    checkpointRequiredOperations.add(operation.operationId());
                }
                if (!checkpointRequiredOperations.contains(operation.operationId())) {
                    deferGrant(player, lease, operation.operationId());
                    return;
                }
                checkpoints.enqueue(player, lease, operation.operationId(),
                        () -> deferGrant(player, lease, operation.operationId()));
                return;
            }
            checkpointRequiredOperations.add(operation.operationId());
            checkpoints.enqueue(player, lease, operation.operationId(),
                    () -> transitionAndReload(player, lease, operation,
                            LedgerDao.InventoryStatus.PENDING,
                            LedgerDao.InventoryStatus.DELIVERED));
            return;
        }
        if (operation.status() == LedgerDao.InventoryStatus.DELIVERED) {
            InventoryReceiptOps.stripReceipt(player.getInventory(), operation.operationId(), receiptKey);
            checkpointRequiredOperations.add(operation.operationId());
            checkpoints.enqueue(player, lease, operation.operationId(),
                    () -> transitionAndReload(player, lease, operation,
                            LedgerDao.InventoryStatus.DELIVERED,
                            LedgerDao.InventoryStatus.COMPLETE));
            return;
        }
        if (operation.status() == LedgerDao.InventoryStatus.COMPLETE) {
            operationCompleted(player, lease, operation);
            return;
        }
        /*
         * Jawnie zamiast fallthrough: wcześniej każdy nierozpoznany status —
         * w tym odstawiony — kończył się operationCompleted, czyli wynikiem
         * SUCCESS dla operacji, która nigdy nie doszła do skutku.
         */
        throw new IllegalStateException("unexpected grant status " + operation.status()
                + " for " + operation.operationId());
    }

    private void applyRemovalOperation(Player player, PlayerOperationCoordinator.Lease lease,
                                       LedgerDao.InventoryOperation operation) {
        if (operation.status() == LedgerDao.InventoryStatus.COMPLETE) {
            // Paragon mutacji pobocznej jest tymczasowy jak w nadaniach: po
            // COMPLETE zostaje zwykły przedmiot (no-op dla operacji bez paragonu).
            InventoryReceiptOps.stripReceipt(
                    player.getInventory(), operation.operationId(), receiptKey);
            operationCompleted(player, lease, operation);
            return;
        }
        InventoryReceiptOps.RemovalResult result = InventoryReceiptOps.applyRemoval(
                player.getInventory(), operation, customItems);
        boolean auxiliaryApplied = applyAuxiliary(player, operation);
        if (result != InventoryReceiptOps.RemovalResult.CONVERGED || !auxiliaryApplied) {
            plugin.getLogger().severe("Inventory removal cannot yet converge for "
                    + operation.operationId() + " (" + result
                    + (auxiliaryApplied ? "" : ", auxiliary refused")
                    + "); keeping the durable operation pending");
            /*
             * BELOW_TARGET znaczy, że gracz ma mniej niż stan po usunięciu — nie
             * ma czego zabierać. Dopóki trwa budżet prób, warto poczekać: brak
             * może być przejściowy. Po jego wyczerpaniu dalsze odmawianie już nic
             * nie ratuje, bo przedmiotów i tak nie ma, a zostawia tylko wiersz do
             * ręcznego rozstrzygnięcia. Wtedy domykamy operację i zapisujemy to
             * w logu. INEFFECTIVE domknąć się nie da — tam przedmioty nadal są u
             * gracza, więc oddalibyśmy mu i towar, i zapłatę.
             */
            boolean settleWhenExhausted = auxiliaryApplied
                    && result == InventoryReceiptOps.RemovalResult.BELOW_TARGET;
            retryConvergence(player, lease, operation,
                    auxiliaryApplied ? "inventory " + result : "auxiliary mutation refused",
                    settleWhenExhausted);
            return;
        }
        settleRemoval(player, lease, operation);
    }

    /** Wspólne domknięcie usunięcia: checkpoint trwałości, potem przejście do COMPLETE. */
    private void settleRemoval(Player player, PlayerOperationCoordinator.Lease lease,
                               LedgerDao.InventoryOperation operation) {
        checkpointRequiredOperations.add(operation.operationId());
        checkpoints.enqueue(player, lease, operation.operationId(),
                () -> transitionAndReload(player, lease, operation,
                        LedgerDao.InventoryStatus.PENDING,
                        LedgerDao.InventoryStatus.COMPLETE));
    }

    private void deferGrant(Player player, PlayerOperationCoordinator.Lease lease,
                            String operationId) {
        recoveryPlayers.add(player.getUniqueId());
        coordinator.releaseInventoryGuard(player.getUniqueId());
        coordinator.release(lease);
        if (!callbackRegistry.complete(operationId, player.getUniqueId(), Outcome.DEFERRED)) {
            player.sendMessage(Component.text(
                    "Masz oczekujący zakup. Zwolnij miejsce w ekwipunku "
                            + "i wejdź ponownie, aby go odebrać.",
                    NamedTextColor.YELLOW));
        }
    }


    private void preserveRecoveryForJoin(PlayerOperationCoordinator.Lease lease) {
        if (!coordinator.owns(lease)) {
            return;
        }
        // Wejście gracza z powrotem to świeża szansa, tak samo jak restart.
        convergenceAttempts.remove(lease.playerId());
        recoveryPlayers.add(lease.playerId());
        coordinator.guardInventory(lease.playerId());
        coordinator.release(lease);
    }


    private void transitionAndReload(Player player, PlayerOperationCoordinator.Lease lease,
                                     LedgerDao.InventoryOperation operation,
                                     LedgerDao.InventoryStatus expected,
                                     LedgerDao.InventoryStatus target) {
        ledger.transitionInventoryOperation(operation.operationId(), expected, target)
                .whenComplete((changed, failure) -> {
                    if (failure != null) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Failed to checkpoint inventory operation "
                                        + operation.operationId(), failure);
                    }
                    reloadAfterTransition(player, lease, operation.operationId());
                });
    }

    private void operationCompleted(Player player, PlayerOperationCoordinator.Lease lease,
                                    LedgerDao.InventoryOperation operation) {
        checkpointRequiredOperations.remove(operation.operationId());
        Map<String, Integer> budget = convergenceAttempts.get(lease.playerId());
        if (budget != null) {
            budget.remove(operation.operationId());
        }
        if (!callbackRegistry.complete(operation.operationId(), player.getUniqueId(),
                Outcome.SUCCESS)) {
            player.sendActionBar(Component.text(
                    "Bezpiecznie dokończono oczekującą operację ekwipunku.",
                    NamedTextColor.GREEN));
        }
        loadAndApply(player, lease);
    }

    private void reloadAfterTransition(Player player,
                                       PlayerOperationCoordinator.Lease lease,
                                       String operationId) {
        /*
         * Always re-read. It resolves both a duplicate retry and the commit-ack
         * ambiguity where UPDATE committed but its future completed
         * exceptionally during shutdown.
         */
        ledger.inventoryOperation(operationId).whenComplete((resolved, failure) -> {
            if (failure != null || resolved.isEmpty()) {
                if (failure != null) {
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to resolve inventory checkpoint " + operationId,
                            failure);
                }
                retryLater(player, lease);
                return;
            }
            schedulePlayer(player, lease, () -> {
                LedgerDao.InventoryOperation current = resolved.get();
                if (current.status() == LedgerDao.InventoryStatus.COMPLETE) {
                    operationCompleted(player, lease, current);
                } else {
                    apply(player, lease, current);
                }
            });
        });
    }

    private void scheduleLoad(Player player, PlayerOperationCoordinator.Lease lease) {
        schedulePlayer(player, lease, () -> loadAndApply(player, lease));
    }

    /**
     * Ponowienie po awarii bazy. <b>Nigdy się nie poddaje</b> — baza sama się goi,
     * a porzucenie trwałej operacji rozjechałoby saldo z ekwipunkiem bez śladu.
     * Dostaje wyłącznie backoff, żeby przestać wypisywać SEVERE co sekundę.
     */
    private void retryLater(Player player, PlayerOperationCoordinator.Lease lease) {
        if (!coordinator.owns(lease) || !accepting || !plugin.isEnabled()) {
            coordinator.release(lease);
            return;
        }
        int attempts = transientAttempts.merge(lease.playerId(), 1, Integer::sum);
        ConvergenceRetryPolicy.Step step = ConvergenceRetryPolicy.next(attempts - 1, false);
        if (step.crossedIntoSlowMode()) {
            plugin.getLogger().warning("Inventory outbox for " + lease.playerId()
                    + " has failed " + attempts + " database attempts; backing off to "
                    + step.delayTicks() + " ticks between retries");
        }
        rescheduleLoad(player, lease, step.delayTicks());
    }

    /**
     * Ponowienie po niezbieżności logiki. <b>Poddaje się</b> po wyczerpaniu budżetu,
     * bo taka awaria sama się nie zagoi, a dopóki trwa, dzierżawa jest
     * {@code inventorySensitive} i gracz pozostaje zamrożony oraz nietykalny.
     */
    private void retryConvergence(Player player, PlayerOperationCoordinator.Lease lease,
                                  LedgerDao.InventoryOperation operation,
                                  String reason) {
        retryConvergence(player, lease, operation, reason, false);
    }

    private void retryConvergence(Player player, PlayerOperationCoordinator.Lease lease,
                                  LedgerDao.InventoryOperation operation,
                                  String reason, boolean settleWhenExhausted) {
        if (!coordinator.owns(lease) || !accepting || !plugin.isEnabled()) {
            coordinator.release(lease);
            return;
        }
        int attempts = convergenceAttempts
                .computeIfAbsent(lease.playerId(), ignored -> new ConcurrentHashMap<>())
                .merge(operation.operationId(), 1, Integer::sum);
        ConvergenceRetryPolicy.Step step = ConvergenceRetryPolicy.next(attempts - 1, true);
        if (step.crossedIntoSlowMode()) {
            plugin.getLogger().warning("Inventory operation " + operation.operationId()
                    + " has not converged after " + attempts + " attempts (" + reason
                    + "); backing off before quarantine");
        }
        if (step.decision() == ConvergenceRetryPolicy.Decision.QUARANTINE) {
            if (settleWhenExhausted) {
                plugin.getLogger().severe("Closing inventory operation "
                        + operation.operationId() + " after " + attempts
                        + " attempts: " + reason + ". Nothing was left to remove, so"
                        + " parking it would leave a row nobody can resolve.");
                convergenceAttempts.getOrDefault(lease.playerId(), Map.of())
                        .remove(operation.operationId());
                settleRemoval(player, lease, operation);
                return;
            }
            quarantine(player, lease, operation, reason);
            return;
        }
        rescheduleLoad(player, lease, step.delayTicks());
    }

    /**
     * Wspólne planowanie obu ponowień.
     *
     * <p><b>Nie upraszczać tej potrójnej obudowy.</b> {@code runDelayed} zwraca
     * {@code null}, gdy encja jest już wycofana, <i>i w tym samym przypadku
     * wywołuje callback wycofania</i> — więc {@code preserveRecoveryForJoin} bywa
     * wołane dwa razy dla jednego zaplanowania. Jest to nieszkodliwe wyłącznie
     * dlatego, że ta metoda jest idempotentna: {@code coordinator.owns} porównuje
     * tożsamość dzierżawy, a po {@code release} wpisu już nie ma.
     */
    private void rescheduleLoad(Player player, PlayerOperationCoordinator.Lease lease,
                                long delayTicks) {
        try {
            var scheduled = player.getScheduler().runDelayed(plugin,
                    ignored -> loadAndApply(player, lease),
                    () -> preserveRecoveryForJoin(lease), delayTicks);
            if (scheduled == null) {
                preserveRecoveryForJoin(lease);
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not schedule inventory outbox retry for " + lease.playerId()
                            + "; recovery remains fail-closed until join",
                    rejected);
            preserveRecoveryForJoin(lease);
        }
    }

    /**
     * Odstawia operację do dead letter queue: przestaje ją odtwarzać, żeby gracz
     * odzyskał sterowność, ale nie kasuje żadnych danych.
     *
     * <p>Kolejność jest nienaruszalna: <b>zrzut dowodowy, potem trwałe przejście,
     * i dopiero po jego potwierdzeniu cokolwiek innego.</b> Dopóki wiersz jest
     * odtwarzalny, zdjęcie strażnika ekwipunku pozwoliłoby późniejszemu
     * odtworzeniu zbiec nieaktualny baseline i skasować graczowi wszystko, co
     * w międzyczasie zdobył.
     */
    private void quarantine(Player player, PlayerOperationCoordinator.Lease lease,
                            LedgerDao.InventoryOperation operation, String reason) {
        LedgerDao.InventoryStatus from = operation.status();
        LedgerDao.InventoryStatus target = switch (from) {
            case PENDING -> LedgerDao.InventoryStatus.QRTN_PENDING;
            case DELIVERED -> LedgerDao.InventoryStatus.QRTN_DELIVERED;
            case COMPLETE, QRTN_PENDING, QRTN_DELIVERED -> null;
        };
        if (target == null) {
            plugin.getLogger().severe("Refusing to quarantine " + operation.operationId()
                    + " from status " + from + "; retrying instead");
            retryLater(player, lease);
            return;
        }
        plugin.getLogger().severe(quarantineReport(player, operation, reason, target));
        try {
            ledger.transitionInventoryOperation(operation.operationId(), from, target)
                    .whenComplete((changed, failure) -> {
                        if (failure != null || !Boolean.TRUE.equals(changed)) {
                            plugin.getLogger().log(Level.SEVERE,
                                    "Could not durably quarantine "
                                            + operation.operationId()
                                            + "; the player stays frozen until it sticks",
                                    failure);
                            retryLater(player, lease);
                            return;
                        }
                        schedulePlayer(player, lease,
                                () -> completeQuarantine(player, lease, operation));
                    });
        } catch (RuntimeException rejected) {
            // Walidator przejść rzuca synchronicznie, zanim powstanie future.
            plugin.getLogger().log(Level.SEVERE, "Quarantine transition was rejected for "
                    + operation.operationId() + "; the player stays frozen", rejected);
            retryLater(player, lease);
        }
    }

    /**
     * Wejście dla {@link CheckpointCoordinator}, który zna tylko identyfikator
     * operacji — dociągamy wiersz i odstawiamy go tą samą drogą co zwykle.
     */
    private void quarantineAfterCheckpointFailure(Player player,
                                                  PlayerOperationCoordinator.Lease lease,
                                                  String operationId, String reason) {
        ledger.inventoryOperation(operationId).whenComplete((operation, failure) -> {
            if (failure != null || operation.isEmpty()) {
                plugin.getLogger().log(Level.SEVERE, "Could not load " + operationId
                        + " to quarantine it after repeated checkpoint failures", failure);
                retryLater(player, lease);
                return;
            }
            // quarantine czyta ekwipunek do zrzutu, więc musi biec na wątku gracza.
            schedulePlayer(player, lease,
                    () -> quarantine(player, lease, operation.get(), reason));
        });
    }

    /** Biegnie na wątku encji gracza, wyłącznie po potwierdzonym przejściu. */
    private void completeQuarantine(Player player, PlayerOperationCoordinator.Lease lease,
                                    LedgerDao.InventoryOperation operation) {
        String operationId = operation.operationId();
        checkpointRequiredOperations.remove(operationId);
        Map<String, Integer> budget = convergenceAttempts.get(lease.playerId());
        if (budget != null) {
            budget.remove(operationId);
        }
        // Przed loadAndApply: inaczej findDanglingFor wejdzie w tę samą operację.
        callbackRegistry.complete(operationId, player.getUniqueId(), Outcome.ERROR);
        player.sendMessage(Component.text(
                "Operacja ekwipunku wymaga interwencji administratora. "
                        + "Twoje przedmioty i saldo są zapisane.",
                NamedTextColor.RED));
        loadAndApply(player, lease);
    }

    /**
     * Wszystko, czego operator potrzebuje, żeby rozstrzygnąć operację po fakcie.
     * Musi powstać przed przejściem — po przejściu do COMPLETE linie i payload
     * są kasowane bezwarunkowo i nie da się ich odtworzyć.
     */
    private String quarantineReport(Player player, LedgerDao.InventoryOperation operation,
                                    String reason, LedgerDao.InventoryStatus target) {
        StringBuilder report = new StringBuilder()
                .append("Quarantining inventory operation ").append(operation.operationId())
                .append(" (").append(operation.type()).append(' ')
                .append(operation.status()).append(" -> ").append(target).append(')')
                .append(" for ").append(player.getName())
                .append(' ').append(player.getUniqueId())
                .append(": ").append(reason);
        for (LedgerDao.InventoryLine line : operation.lines()) {
            Material material = Material.matchMaterial(line.materialKey());
            report.append("\n  line ").append(line.materialKey())
                    .append(" remove=").append(line.removeAmount())
                    .append(" baseline=").append(line.baselineCount())
                    .append(" target=").append(line.baselineCount() - line.removeAmount())
                    .append(" current=")
                    .append(material == null
                            ? "<unknown material>"
                            : Inventories.countPlain(player.getInventory(), material));
        }
        byte[] payload = operation.itemPayload();
        if (payload != null) {
            report.append("\n  payload base64=")
                    .append(java.util.Base64.getEncoder().encodeToString(payload))
                    .append("\n  receiptsInInventory=")
                    .append(InventoryReceiptOps.receiptAmount(
                            player.getInventory(), operation.operationId(), receiptKey));
        }
        return report.toString();
    }

    private void schedulePlayer(Player player, PlayerOperationCoordinator.Lease lease,
                                Runnable action) {
        if (!accepting || !plugin.isEnabled()) {
            coordinator.release(lease);
            return;
        }
        try {
            var scheduled = player.getScheduler().run(plugin, ignored -> {
                if (player.isOnline() && coordinator.owns(lease)) {
                    action.run();
                } else {
                    preserveRecoveryForJoin(lease);
                }
            }, () -> preserveRecoveryForJoin(lease));
            if (scheduled == null) {
                preserveRecoveryForJoin(lease);
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.WARNING,
                    "Could not schedule inventory outbox continuation for "
                            + lease.playerId()
                            + "; recovery remains fail-closed until join",
                    rejected);
            preserveRecoveryForJoin(lease);
        }
    }

    private void resolveDanglingCallback(Player player,
                                         PlayerOperationCoordinator.Lease lease,
                                         String operationId) {
        ledger.inventoryOperation(operationId).whenComplete((operation, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.SEVERE,
                        "Failed final resolution of inventory operation " + operationId,
                        failure);
                retryLater(player, lease);
                return;
            }
            schedulePlayer(player, lease, () -> {
                if (operation.isEmpty()) {
                    callbackRegistry.complete(operationId, player.getUniqueId(), Outcome.ERROR);
                    loadAndApply(player, lease);
                } else if (operation.get().status() == LedgerDao.InventoryStatus.COMPLETE) {
                    callbackRegistry.complete(operationId, player.getUniqueId(), Outcome.SUCCESS);
                    loadAndApply(player, lease);
                } else {
                    apply(player, lease, operation.get());
                }
            });
        });
    }

    private void disablePluginFailClosed() {
        try {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                if (plugin.isEnabled()) {
                    plugin.getServer().getPluginManager().disablePlugin(plugin);
                }
            });
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not schedule fail-closed plugin disable; "
                            + "all new inventory operations remain disabled",
                    rejected);
        }
    }

    public enum Outcome {
        SUCCESS,
        BUSY,
        INSUFFICIENT,
        REJECTED,
        DEFERRED,
        ERROR
    }

    public record AuxiliaryMutation(@NotNull String type, @NotNull String key, long value) { }

    @FunctionalInterface
    public interface AuxiliaryHandler {
        boolean apply(@NotNull Player player,
                      @NotNull LedgerDao.InventoryOperation operation);
    }

    @FunctionalInterface
    interface PlayerDataCheckpoint {
        void save(@NotNull Player player);
    }

    /**
     * Zapis playerdaty zawiódł tyle razy, że nie ma sensu próbować dalej.
     *
     * <p>Poddanie się jest bezpieczne dla danych gracza — Paper zapisuje do pliku
     * tymczasowego i podmienia właściwy atomowo — ale operacja musi zostać
     * odstawiona, bo status w bazie nie ruszy bez potwierdzonej trwałości.
     */
    @FunctionalInterface
    interface CheckpointExhausted {
        void onExhausted(@NotNull Player player,
                         @NotNull PlayerOperationCoordinator.Lease lease,
                         @NotNull String operationId, @NotNull String reason);
    }


    public record ShutdownState(boolean startupInspectionComplete,
                         int recoveryPlayerCount,
                         int callbackCount,
                         boolean inventoryProtectionHeld,
                         boolean checkpointInProgress,
                         int checkpointQueuedCount,
                         long checkpointMaxQueueDepth,
                         long checkpointCount,
                         long checkpointTotalMillis,
                         long checkpointMaxMillis) {

        public boolean hasUnsettledWork() {
            return !startupInspectionComplete || recoveryPlayerCount > 0
                    || callbackCount > 0 || inventoryProtectionHeld
                    || checkpointInProgress || checkpointQueuedCount > 0;
        }
    }
}
