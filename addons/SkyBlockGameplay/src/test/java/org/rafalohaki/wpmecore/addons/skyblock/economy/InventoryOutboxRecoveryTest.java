package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Inventories;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileMutationGuard;
import org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileTransitionDao;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("mockbukkit")
class InventoryOutboxRecoveryTest {

    /** Canonical OneBlock chapter ids (oneblock.yml order), used for migration 7. */
    private static final List<String> PHASE_IDS = List.of(
            "poczatek", "podziemia", "zima", "pustynia", "dzungla", "pieklo", "kres");

    private ServerMock server;
    private PluginMock plugin;
    private FaultSqlService sql;
    private LedgerService ledger;
    private PlayerOperationCoordinator coordinator;
    private InventoryOutbox outbox;
    private TestEntityScheduler scheduler;
    private Player player;
    private UUID playerId;
    private AtomicInteger checkpointCalls;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("SkyBlockOutboxRecoveryTest");
        plugin.getLogger().setLevel(Level.OFF);
        sql = new FaultSqlService();
        ledger = new LedgerService(new LedgerDao(sql), 100L);
        ledger.init();
        coordinator = new PlayerOperationCoordinator();
        checkpointCalls = new AtomicInteger();
        outbox = new InventoryOutbox(plugin, ledger, coordinator, 1L,
                ignored -> checkpointCalls.incrementAndGet());
        scheduler = new TestEntityScheduler();
        playerId = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        player = player(playerId, scheduler, server.addPlayer().getInventory());
        // SKYBLOCK-2-5: operacja z mutacją poboczną wymaga zarejestrowanego
        // handlera. Rejestrujemy odrzucający — dokładnie ten trwały rodzaj
        // awarii, który te testy mają odwzorować (brak zbieżności).
        outbox.registerAuxiliaryHandler(InventoryOutbox.AUXILIARY_WAND_USE,
                (p, operation) -> false);
        outbox.start();
        assertTrue(outbox.isEconomyReady(playerId));
    }

    @AfterEach
    void tearDown() {
        outbox.stop();
        sql.shutdown();
        MockBukkit.unmock();
    }

    @Test
    void grantKeepsTwoDurabilityBoundariesAndRemovalKeepsOne() {
        List<InventoryOutbox.Outcome> grantOutcomes = new ArrayList<>();
        outbox.beginGrant(player, -10L, "shop:buy:checkpoint-count",
                "server_shop_buy", new ItemStack(Material.COBBLESTONE, 1),
                grantOutcomes::add);

        assertEquals(List.of(InventoryOutbox.Outcome.SUCCESS), grantOutcomes);
        assertEquals(2, checkpointCalls.get());
        assertEquals(1, Inventories.countPlain(
                player.getInventory(), Material.COBBLESTONE));

        List<InventoryOutbox.Outcome> removalOutcomes = new ArrayList<>();
        outbox.beginRemoval(player, 5L, "shop:sell:checkpoint-count",
                "server_shop_sell", Map.of(Material.COBBLESTONE, 1), null,
                removalOutcomes::add);

        assertEquals(List.of(InventoryOutbox.Outcome.SUCCESS), removalOutcomes);
        assertEquals(3, checkpointCalls.get());
        assertEquals(0, Inventories.countPlain(
                player.getInventory(), Material.COBBLESTONE));
        InventoryOutbox.ShutdownState shutdown = outbox.stop();
        assertEquals(3L, shutdown.checkpointCount());
        assertFalse(shutdown.checkpointInProgress());
    }

    /**
     * Nagroda nic nie kosztuje. Bez tego kosmetyka sezonowa i sklepowa musiałaby
     * omijać outbox i dodawać przedmiot wprost do ekwipunku — czyli nietrwale,
     * tak jak dotąd wydawane były Lotosy za miejsce w sezonie.
     */
    @Test
    void aGrantThatCostsNothingIsStillDurable() {
        List<InventoryOutbox.Outcome> outcomes = new ArrayList<>();
        String operationId = "cosmetic:free-grant";

        outbox.beginGrant(player, 0L, operationId, "cosmetic_grant",
                new ItemStack(Material.NETHERITE_CHESTPLATE, 1), outcomes::add);

        assertEquals(List.of(InventoryOutbox.Outcome.SUCCESS), outcomes);
        assertEquals(LedgerDao.InventoryStatus.COMPLETE, statusOf(operationId));
        assertEquals(1, Inventories.countPlain(
                player.getInventory(), Material.NETHERITE_CHESTPLATE));
        assertFalse(coordinator.isBusy(playerId));
    }

    /**
     * Deterministyczny identyfikator operacji jest drugim zamkiem na podwójne
     * wydanie. Nagroda sezonowa używa {@code season_reward:lotus:<sezon>:<uuid>},
     * więc nawet gdyby tabela roszczeń zawiodła, outbox nie wyda jej dwa razy.
     *
     * <p>Powtórka <b>zgłasza</b> SUCCESS, a nie błąd — to kontrakt idempotentnego
     * konsumenta: wołający nie musi odróżniać „wydano teraz" od „było już wydane",
     * bo w obu wypadkach gracz ma swoją nagrodę i dokładnie jedną.
     */
    @Test
    void aRepeatedGrantUnderTheSameOperationIdIsANoOpThatStillReportsSuccess() {
        String operationId = "season_reward:lotus:3:" + playerId;
        List<InventoryOutbox.Outcome> first = new ArrayList<>();
        List<InventoryOutbox.Outcome> second = new ArrayList<>();

        outbox.beginGrant(player, 0L, operationId, "seasonal_lotus",
                new ItemStack(Material.COBBLESTONE, 3), first::add);
        outbox.beginGrant(player, 0L, operationId, "seasonal_lotus",
                new ItemStack(Material.COBBLESTONE, 3), second::add);

        assertEquals(List.of(InventoryOutbox.Outcome.SUCCESS), first);
        assertEquals(List.of(InventoryOutbox.Outcome.SUCCESS), second);
        assertEquals(3, Inventories.countPlain(
                        player.getInventory(), Material.COBBLESTONE),
                "powtórka pod tym samym identyfikatorem nie może dołożyć przedmiotów");
    }

    /**
     * Nadanie nadal nie może uznać konta — od tego jest usunięcie. Odrzucenie
     * walidatora nie leci wyjątkiem do wywołującego: {@code beginMutation} łapie
     * je i zwraca {@code ERROR}, więc dzierżawa zawsze zostaje zwolniona.
     */
    @Test
    void aGrantMayNotCreditThePlayer() {
        List<InventoryOutbox.Outcome> outcomes = new ArrayList<>();

        outbox.beginGrant(player, 5L, "cosmetic:credits", "cosmetic_grant",
                new ItemStack(Material.NETHERITE_CHESTPLATE, 1), outcomes::add);

        assertEquals(List.of(InventoryOutbox.Outcome.ERROR), outcomes);
        assertEquals(0, Inventories.countPlain(
                player.getInventory(), Material.NETHERITE_CHESTPLATE));
        assertFalse(coordinator.isBusy(playerId), "dzierżawa musi zostać zwolniona");
    }

    @Test
    void untouchedFullInventoryDeferralDoesNotForceRedundantPlayerdataWrite() {
        ItemStack[] full = player.getInventory().getStorageContents();
        for (int slot = 0; slot < full.length; slot++) {
            full[slot] = new ItemStack(Material.DIRT, 64);
        }
        player.getInventory().setStorageContents(full);
        List<InventoryOutbox.Outcome> outcomes = new ArrayList<>();

        outbox.beginGrant(player, -10L, "shop:buy:full-no-checkpoint",
                "server_shop_buy", new ItemStack(Material.COBBLESTONE, 1),
                outcomes::add);

        assertEquals(List.of(InventoryOutbox.Outcome.DEFERRED), outcomes);
        assertEquals(0, checkpointCalls.get());
        assertEquals(1, ledger.pendingInventoryOperations(playerId).join().size());
        assertFalse(outbox.isEconomyReady(playerId));
    }

    @Test
    void concurrentRegionOperationsNeverOverlapPlayerdataCheckpoints() throws Exception {
        outbox.stop();
        coordinator = new PlayerOperationCoordinator();
        TestEntityScheduler secondScheduler = new TestEntityScheduler();
        UUID secondPlayerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        Player secondPlayer = player(secondPlayerId, secondScheduler,
                server.addPlayer().getInventory());
        CountDownLatch firstCheckpointEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCheckpoint = new CountDownLatch(1);
        AtomicBoolean blockFirstCheckpoint = new AtomicBoolean(true);
        AtomicInteger activeCheckpoints = new AtomicInteger();
        AtomicInteger maximumConcurrentCheckpoints = new AtomicInteger();
        checkpointCalls = new AtomicInteger();
        outbox = new InventoryOutbox(plugin, ledger, coordinator, 1L, checkedPlayer -> {
            int active = activeCheckpoints.incrementAndGet();
            maximumConcurrentCheckpoints.accumulateAndGet(active, Math::max);
            try {
                if (blockFirstCheckpoint.compareAndSet(true, false)) {
                    firstCheckpointEntered.countDown();
                    if (!releaseFirstCheckpoint.await(2L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release checkpoint");
                    }
                }
                checkpointCalls.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("checkpoint test interrupted", interrupted);
            } finally {
                activeCheckpoints.decrementAndGet();
            }
        });
        outbox.start();
        List<InventoryOutbox.Outcome> firstOutcomes = new ArrayList<>();
        List<InventoryOutbox.Outcome> secondOutcomes = new ArrayList<>();

        CompletableFuture<Void> first = CompletableFuture.runAsync(() ->
                outbox.beginGrant(player, -10L, "shop:buy:region-one",
                        "server_shop_buy", new ItemStack(Material.COBBLESTONE, 1),
                        firstOutcomes::add));
        assertTrue(firstCheckpointEntered.await(2L, TimeUnit.SECONDS));

        outbox.beginGrant(secondPlayer, -10L, "shop:buy:region-two",
                "server_shop_buy", new ItemStack(Material.COBBLESTONE, 1),
                secondOutcomes::add);
        assertEquals(0, secondScheduler.delayedCount());
        assertTrue(secondOutcomes.isEmpty());

        releaseFirstCheckpoint.countDown();
        first.join();

        assertEquals(List.of(InventoryOutbox.Outcome.SUCCESS), firstOutcomes);
        assertEquals(List.of(InventoryOutbox.Outcome.SUCCESS), secondOutcomes);
        assertEquals(4, checkpointCalls.get());
        assertEquals(1, maximumConcurrentCheckpoints.get());
        assertEquals(2L, outbox.stop().checkpointMaxQueueDepth());
        assertFalse(coordinator.isBusy(playerId));
        assertFalse(coordinator.isBusy(secondPlayerId));
    }

    @Test
    void contendedPartialReceiptRollbackCannotSkipItsRequiredCheckpoint() throws Exception {
        outbox.stop();
        coordinator = new PlayerOperationCoordinator();
        TestEntityScheduler secondScheduler = new TestEntityScheduler();
        UUID secondPlayerId = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
        PlayerInventory secondInventory = server.addPlayer().getInventory();
        ItemStack[] full = secondInventory.getStorageContents();
        for (int slot = 0; slot < full.length; slot++) {
            full[slot] = new ItemStack(Material.DIRT, 64);
        }
        secondInventory.setStorageContents(full);
        String secondOperationId = "shop:buy:contended-rollback";
        ItemStack partialReceipt = new ItemStack(Material.COBBLESTONE, 1);
        NamespacedKey receiptKey = new NamespacedKey(plugin, "inventory_receipt");
        partialReceipt.editMeta(meta -> meta.getPersistentDataContainer().set(
                receiptKey, PersistentDataType.STRING, secondOperationId));
        secondInventory.setItemInOffHand(partialReceipt);
        Player secondPlayer = player(secondPlayerId, secondScheduler, secondInventory);
        CountDownLatch firstCheckpointEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCheckpoint = new CountDownLatch(1);
        AtomicBoolean blockFirstCheckpoint = new AtomicBoolean(true);
        AtomicInteger activeCheckpoints = new AtomicInteger();
        AtomicInteger maximumConcurrentCheckpoints = new AtomicInteger();
        checkpointCalls = new AtomicInteger();
        outbox = new InventoryOutbox(plugin, ledger, coordinator, 1L, checkedPlayer -> {
            int active = activeCheckpoints.incrementAndGet();
            maximumConcurrentCheckpoints.accumulateAndGet(active, Math::max);
            try {
                if (blockFirstCheckpoint.compareAndSet(true, false)) {
                    firstCheckpointEntered.countDown();
                    if (!releaseFirstCheckpoint.await(2L, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release checkpoint");
                    }
                }
                checkpointCalls.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("checkpoint test interrupted", interrupted);
            } finally {
                activeCheckpoints.decrementAndGet();
            }
        });
        outbox.start();
        List<InventoryOutbox.Outcome> firstOutcomes = new ArrayList<>();
        List<InventoryOutbox.Outcome> secondOutcomes = new ArrayList<>();

        CompletableFuture<Void> first = CompletableFuture.runAsync(() ->
                outbox.beginGrant(player, -10L, "shop:buy:rollback-blocker",
                        "server_shop_buy", new ItemStack(Material.COBBLESTONE, 1),
                        firstOutcomes::add));
        assertTrue(firstCheckpointEntered.await(2L, TimeUnit.SECONDS));
        outbox.beginGrant(secondPlayer, -10L, secondOperationId,
                "server_shop_buy", new ItemStack(Material.COBBLESTONE, 16),
                secondOutcomes::add);

        assertEquals(0, secondScheduler.delayedCount());
        assertTrue(secondOutcomes.isEmpty());
        assertTrue(secondInventory.getItemInOffHand().getType().isAir());

        releaseFirstCheckpoint.countDown();
        first.join();

        assertEquals(List.of(InventoryOutbox.Outcome.SUCCESS), firstOutcomes);
        assertEquals(List.of(InventoryOutbox.Outcome.DEFERRED), secondOutcomes);
        assertEquals(3, checkpointCalls.get());
        assertEquals(1, maximumConcurrentCheckpoints.get());
        assertEquals(1, ledger.pendingInventoryOperations(secondPlayerId).join().size());
        assertFalse(coordinator.isBusy(secondPlayerId));
    }

    @Test
    void delayedStartupScanKeepsInventoryBlockedUntilItsAuthoritativeResult() {
        replaceOutboxWithDelayedStartupScan();

        assertFalse(outbox.isEconomyReady(playerId));
        assertTrue(coordinator.isInventoryMovementBlocked(playerId));

        sql.releaseDelayedQuery();

        assertTrue(outbox.isEconomyReady(playerId));
        assertFalse(coordinator.isInventoryMovementBlocked(playerId));
    }

    @Test
    void startupPublishesPendingPlayerGuardBeforeOpeningTheGlobalGate() {
        String operationId = "shop:sell:startup-pending";
        long now = 1L;
        LedgerDao.InventoryOperation operation = new LedgerDao.InventoryOperation(
                operationId, playerId, LedgerDao.InventoryOperationType.REMOVE,
                operationId, LedgerDao.InventoryStatus.PENDING, null,
                null, null, null,
                List.of(new LedgerDao.InventoryLine(
                        "minecraft:cobblestone", "", 1, 1)),
                now, now);
        ledger.mutatePlayerWithInventoryOperation(playerId, 1L, operationId,
                "server_shop_sell", operation).join();
        replaceOutboxWithDelayedStartupScan();

        sql.releaseDelayedQuery();

        assertFalse(outbox.isEconomyReady(playerId));
        assertTrue(coordinator.isInventoryMovementBlocked(playerId));
        assertFalse(coordinator.isInventoryMovementBlocked(
                UUID.fromString("ffffffff-aaaa-bbbb-cccc-dddddddddddd")));
    }

    @Test
    void stopDuringStartupScanCannotPublishReadinessOrReopenTheGate() {
        replaceOutboxWithDelayedStartupScan();

        InventoryOutbox.ShutdownState shutdown = outbox.stop();
        sql.releaseDelayedQuery();

        assertFalse(shutdown.startupInspectionComplete());
        assertTrue(shutdown.hasUnsettledWork());
        assertFalse(outbox.isEconomyReady(playerId));
        assertTrue(coordinator.isInventoryMovementBlocked(playerId));
        assertTrue(coordinator.tryAcquire(playerId, false).isEmpty());
    }

    @Test
    void stopDuringInFlightMutationRetainsItsLeaseAndDurableRecoveryRow() {
        sql.delayNextWithConnection();
        List<InventoryOutbox.Outcome> outcomes = new ArrayList<>();
        outbox.beginGrant(player, -10L, "shop:buy:shutdown-in-flight",
                "server_shop_buy", new ItemStack(Material.COBBLESTONE, 1),
                outcomes::add);
        assertTrue(coordinator.isBusy(playerId));

        InventoryOutbox.ShutdownState shutdown = outbox.stop();
        sql.releaseDelayedConnection();

        assertTrue(shutdown.startupInspectionComplete());
        assertTrue(shutdown.hasUnsettledWork());
        assertFalse(outbox.isEconomyReady(playerId));
        assertTrue(coordinator.isBusy(playerId));
        assertTrue(coordinator.isInventoryMovementBlocked(playerId));
        assertTrue(outcomes.isEmpty());
        assertEquals(90L, ledger.playerBalance(playerId));
        assertEquals(1, ledger.pendingInventoryOperations(playerId).join().size());
        assertEquals(0, Inventories.countPlain(
                player.getInventory(), Material.COBBLESTONE));
    }

    @Test
    void occupiedLeasePastFastRetryLimitKeepsOneRetryAndEventuallyRecovers() {
        PlayerOperationCoordinator.Lease activeLease =
                coordinator.tryAcquire(playerId, false).orElseThrow();

        outbox.reconcile(player);
        for (int attempt = 0; attempt < InventoryOutbox.LEASE_RETRY_LIMIT; attempt++) {
            scheduler.runNextDelayed();
        }

        assertEquals(InventoryOutbox.LEASE_RETRY_LIMIT,
                scheduler.executedDelayed());
        assertEquals(1, scheduler.delayedCount());
        assertEquals(InventoryOutbox.LEASE_RETRY_SLOW_DELAY_TICKS,
                scheduler.lastDelayTicks());
        assertFalse(outbox.isEconomyReady(playerId));

        coordinator.release(activeLease);
        scheduler.runNextDelayed();

        assertEquals(0, scheduler.delayedCount());
        assertTrue(outbox.isEconomyReady(playerId));
        assertFalse(coordinator.isBusy(playerId));
    }

    @Test
    void stalePreQuitRetirementCannotRemoveTheRejoinedRetryToken() {
        PlayerOperationCoordinator.Lease activeLease =
                coordinator.tryAcquire(playerId, false).orElseThrow();
        outbox.reconcile(player);
        assertEquals(1, scheduler.delayedCount());

        outbox.onQuit(new PlayerQuitEvent(
                player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        TestEntityScheduler rejoinScheduler = new TestEntityScheduler();
        Player rejoined = player(playerId, rejoinScheduler, player.getInventory());
        outbox.reconcile(rejoined);
        assertEquals(1, rejoinScheduler.delayedCount());

        scheduler.retireNextDelayed();
        outbox.reconcile(rejoined);

        assertEquals(1, rejoinScheduler.delayedCount());
        assertFalse(outbox.isEconomyReady(playerId));

        coordinator.release(activeLease);
        rejoinScheduler.runNextDelayed();

        assertTrue(outbox.isEconomyReady(playerId));
        assertFalse(coordinator.isBusy(playerId));
    }

    @ParameterizedTest
    @EnumSource(value = DelayedFailure.class, names = {"RETURN_NULL", "THROW"})
    void rejectedEntityRetryFallsBackAndDoesNotLeaveOnlineRecoveryStuck(DelayedFailure failure) {
        assertRejectedEntityRetryFallsBack(failure);
    }

    @ParameterizedTest
    @EnumSource(value = DelayedFailure.class, names = {"RETURN_NULL", "THROW"})
    void rejectedDurableContinuationStaysFailClosedAndRecoversOnJoin(DelayedFailure failure) {
        assertRejectedDurableContinuationRecovers(failure);
    }

    @Test
    void failedCheckpointRetriesWithoutSqlPollingAndKeepsTheLease() {
        outbox.stop();
        coordinator = new PlayerOperationCoordinator();
        AtomicInteger attempts = new AtomicInteger();
        outbox = new InventoryOutbox(plugin, ledger, coordinator, 1L, ignored -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("injected playerdata failure");
            }
        });
        outbox.start();
        List<InventoryOutbox.Outcome> outcomes = new ArrayList<>();

        outbox.beginGrant(player, -10L, "shop:buy:checkpoint-retry",
                "server_shop_buy", new ItemStack(Material.COBBLESTONE, 1),
                outcomes::add);

        assertEquals(1, attempts.get());
        assertEquals(1, scheduler.delayedCount());
        assertTrue(outcomes.isEmpty());
        assertTrue(coordinator.isBusy(playerId));
        assertTrue(coordinator.isInventoryMovementBlocked(playerId));
        assertEquals(1, ledger.pendingInventoryOperations(playerId).join().size());

        scheduler.runNextDelayed();

        assertEquals(3, attempts.get());
        assertEquals(List.of(InventoryOutbox.Outcome.SUCCESS), outcomes);
        assertTrue(ledger.pendingInventoryOperations(playerId).join().isEmpty());
        assertFalse(coordinator.isBusy(playerId));
        assertFalse(coordinator.isInventoryMovementBlocked(playerId));
    }

    @Test
    void ambiguityLookupFailureThenDefinitiveMissingLoadCompletesCallbackWithError() {
        sql.failNextWithConnections(2);
        List<InventoryOutbox.Outcome> outcomes = new ArrayList<>();

        outbox.beginGrant(player, -10L, "shop:buy:ambiguity-test",
                "server_shop_buy", new ItemStack(Material.COBBLESTONE, 1),
                outcomes::add);

        assertTrue(outcomes.isEmpty());
        assertEquals(1, scheduler.delayedCount());
        scheduler.runNextDelayed();

        assertEquals(List.of(InventoryOutbox.Outcome.ERROR), outcomes);
        assertTrue(outbox.isEconomyReady(playerId));
        assertFalse(coordinator.isBusy(playerId));
    }

    @Test
    void delayedCommitAcrossQuitAndRejoinRemainsFailClosedUntilRecoveryCompletes() {
        sql.delayNextWithConnection();
        outbox.beginGrant(player, -10L, "shop:buy:quit-rejoin",
                "server_shop_buy", new ItemStack(Material.COBBLESTONE, 1),
                ignored -> { });
        assertTrue(coordinator.isBusy(playerId));

        outbox.onQuit(new PlayerQuitEvent(
                player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        scheduler.retire();
        TestEntityScheduler rejoinScheduler = new TestEntityScheduler();
        Player rejoined = player(playerId, rejoinScheduler, player.getInventory());
        outbox.reconcile(rejoined);

        assertFalse(outbox.isEconomyReady(playerId));
        assertTrue(coordinator.isBusy(playerId));
        assertEquals(1, rejoinScheduler.delayedCount());

        sql.releaseDelayedConnection();

        assertFalse(outbox.isEconomyReady(playerId));
        assertFalse(coordinator.isBusy(playerId));
        assertEquals(1, rejoinScheduler.delayedCount());

        rejoinScheduler.runNextDelayed();

        assertTrue(outbox.isEconomyReady(playerId));
        assertFalse(coordinator.isBusy(playerId));
        assertEquals(90L, ledger.playerBalance(playerId));
        assertEquals(1, Inventories.countPlain(
                rejoined.getInventory(), Material.COBBLESTONE));
        assertTrue(ledger.pendingInventoryOperations(playerId).join().isEmpty());
    }

    @Test
    void quitDuringNonInventoryLeaseDoesNotBlockLaterOfflineEconomyMutation() {
        PlayerOperationCoordinator.Lease vaultLease =
                coordinator.tryAcquire(playerId, false).orElseThrow();

        outbox.onQuit(new PlayerQuitEvent(
                player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertTrue(outbox.isEconomyReady(playerId));
        coordinator.release(vaultLease);
        SkyBlockVaultProvider provider =
                new SkyBlockVaultProvider(ledger, coordinator, outbox);
        SkyBlockVaultProvider.Response response = CompletableFuture.supplyAsync(
                () -> provider.depositPlayer(player, 1.0D)).join();

        assertEquals(SkyBlockVaultProvider.ResponseType.SUCCESS, response.type());
        assertEquals(101.0D, response.balance());
        assertFalse(coordinator.isBusy(playerId));
    }

    /**
     * Domyślny {@code auxiliaryHandler} odrzuca każdą operację z mutacją
     * pomocniczą, więc wystarczy ją podać, żeby zbieżność nie nastąpiła nigdy —
     * dokładnie ten trwały rodzaj awarii, przez który gracz był dotąd zamrożony
     * i nietykalny bez końca.
     */
    private String beginNonConvergentRemoval() {
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 10));
        String operationId = "shop:sell:never-converges";
        outbox.beginRemoval(player, 5L, operationId, "server_shop_sell",
                Map.of(Material.COBBLESTONE, 1),
                new InventoryOutbox.AuxiliaryMutation(
                        InventoryOutbox.AUXILIARY_WAND_USE, "wand-instance", 1L),
                quarantineOutcomes::add);
        return operationId;
    }

    private final List<InventoryOutbox.Outcome> quarantineOutcomes = new ArrayList<>();

    private LedgerDao.InventoryStatus statusOf(String operationId) {
        return ledger.inventoryOperation(operationId).join().orElseThrow().status();
    }

    private int drainUntilSettled() {
        int drained = 0;
        while (scheduler.delayedCount() > 0 && drained < 500) {
            scheduler.runNextDelayed();
            drained++;
        }
        return drained;
    }

    /**
     * Gracz ma mniej, niż wynosi stan po usunięciu — czyli nie ma czego zabierać.
     *
     * <p>Dopóki trwa budżet prób warto czekać, bo brak może być przejściowy. Po
     * jego wyczerpaniu dalsze odmawianie niczego nie ratuje: przedmiotów i tak
     * nie ma, a zostawia tylko wiersz, którego nikt nie rozstrzygnie bez ręcznego
     * SQL-a. Operacja ma się wtedy domknąć, a nie osiąść w kwarantannie.
     */
    @Test
    void aRemovalWithNothingLeftToTakeSettlesInsteadOfWaitingForAnOperator() {
        String operationId = "shop:sell:nothing-left";
        // Baseline 10, zabrać 4 → cel 6. Gracz ma 2, czyli poniżej celu.
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 2));
        LedgerDao.InventoryOperation operation = new LedgerDao.InventoryOperation(
                operationId, playerId, LedgerDao.InventoryOperationType.REMOVE,
                operationId, LedgerDao.InventoryStatus.PENDING, null,
                null, null, null,
                List.of(new LedgerDao.InventoryLine("minecraft:cobblestone", "", 4, 10)),
                1L, 1L);
        ledger.mutatePlayerWithInventoryOperation(playerId, 5L, operationId,
                "server_shop_sell", operation).join();

        outbox.reconcile(player);
        int drained = drainUntilSettled();

        assertTrue(drained >= ConvergenceRetryPolicy.TOTAL_ATTEMPTS,
                "budżet prób ma zostać wykorzystany — brak może być przejściowy; wykonano "
                        + drained);
        assertEquals(LedgerDao.InventoryStatus.COMPLETE, statusOf(operationId),
                "operacja ma się domknąć, a nie trafić do kwarantanny");
        assertEquals(2, Inventories.countPlain(
                player.getInventory(), Material.COBBLESTONE),
                "domknięcie nie może niczego zabrać — nie było czego");
        assertFalse(coordinator.isBusy(playerId));
    }

    @Test
    void nonConvergentRemovalIsQuarantinedInsteadOfLoopingForever() {
        String operationId = beginNonConvergentRemoval();

        assertEquals(LedgerDao.InventoryStatus.PENDING, statusOf(operationId));
        assertTrue(scheduler.delayedCount() > 0, "pierwsza porażka planuje ponowienie");

        int drained = drainUntilSettled();

        assertTrue(drained < 500, "ponawianie musi się skończyć, a nie kręcić w kółko");
        assertTrue(drained >= ConvergenceRetryPolicy.TOTAL_ATTEMPTS,
                "budżet ponowień ma zostać wykorzystany, a nie pominięty — przedwczesne "
                        + "odstawienie porzuciłoby operacje, które jeszcze by zbiegły; "
                        + "wykonano " + drained);
        assertEquals(LedgerDao.InventoryStatus.QRTN_PENDING, statusOf(operationId));
        assertEquals(List.of(InventoryOutbox.Outcome.ERROR), quarantineOutcomes,
                "zlecający musi dostać rozstrzygnięcie, inaczej wisi na zawsze");
        assertFalse(coordinator.isBusy(playerId), "gracz odzyskuje sterowność");
        assertTrue(outbox.isEconomyReady(playerId));
    }

    /**
     * Najgroźniejszy błąd, jaki ten projekt mógł popełnić.
     *
     * <p>Zbieżność celuje w wartość bezwzględną wyliczoną ze starego baseline'u,
     * więc ponowne wejście w odstawioną operację kasuje graczowi wszystko, co
     * zdobył od tamtej pory. Filtr na liście oczekujących tego nie łapie:
     * {@code selectInventoryOperation} czyta po samym identyfikatorze, bez
     * predykatu statusu, a {@code resolveDanglingCallback} i
     * {@code reloadAfterTransition} wchodzą tędy prosto do {@code apply}.
     *
     * <p>Dlatego test podaje operację bezpośrednio, zamiast iść przez
     * {@code reconcile} — droga przez listę oczekujących przechodzi także bez
     * bramki i niczego by nie udowodniła.
     */
    @Test
    void applyRefusesToTouchTheInventoryForAQuarantinedOperation() {
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 64));
        PlayerOperationCoordinator.Lease lease =
                coordinator.tryAcquire(playerId, true).orElseThrow();
        List<InventoryOutbox.Outcome> outcomes = new ArrayList<>();
        String operationId = "shop:sell:stale-baseline";
        LedgerDao.InventoryOperation quarantined = new LedgerDao.InventoryOperation(
                operationId, playerId, LedgerDao.InventoryOperationType.REMOVE,
                operationId, LedgerDao.InventoryStatus.QRTN_PENDING, null,
                null, null, null,
                List.of(new LedgerDao.InventoryLine("minecraft:cobblestone", "", 4, 10)),
                1L, 1L);

        outbox.apply(player, lease, quarantined);

        assertEquals(64, Inventories.countPlain(
                player.getInventory(), Material.COBBLESTONE),
                "bez bramki applyRemoval zbiegłoby do baseline 10 - 4 = 6 "
                        + "i skasowałoby graczowi 58 sztuk");
        assertTrue(outcomes.isEmpty() || outcomes.equals(
                List.of(InventoryOutbox.Outcome.ERROR)));
    }

    /**
     * Siódma nieskończona pętla, w innej klasie: {@code retryFailedCheckpointLater}
     * ponawiał zapis playerdaty co 20 ticków bez końca, trzymając dzierżawę. Ta
     * droga omija politykę zbieżności, bo operacja już <i>zbiegła</i> — awaria jest
     * po stronie dysku. Poddanie się jest bezpieczne, bo Paper podmienia plik
     * atomowo, więc nieudany zapis nie psuje poprzednich danych.
     */
    @Test
    void repeatedPlayerdataCheckpointFailureQuarantinesInsteadOfLooping() {
        outbox.stop();
        coordinator = new PlayerOperationCoordinator();
        outbox = new InventoryOutbox(plugin, ledger, coordinator, 1L, checked -> {
            throw new IllegalStateException("injected playerdata write failure");
        });
        outbox.start();
        List<InventoryOutbox.Outcome> outcomes = new ArrayList<>();
        String operationId = "shop:buy:checkpoint-dies";

        outbox.beginGrant(player, -10L, operationId, "server_shop_buy",
                new ItemStack(Material.COBBLESTONE, 1), outcomes::add);
        int drained = drainUntilSettled();

        assertTrue(drained < 500, "zapis playerdaty nie może być ponawiany bez końca");
        assertTrue(drained >= ConvergenceRetryPolicy.TOTAL_ATTEMPTS,
                "budżet ma zostać wykorzystany zanim się poddamy; wykonano " + drained);
        assertEquals(LedgerDao.InventoryStatus.QRTN_PENDING, statusOf(operationId));
        assertFalse(coordinator.isBusy(playerId), "gracz odzyskuje sterowność");
    }

    @Test
    void recoveryScanSkipsAQuarantinedRemoval() {
        String operationId = beginNonConvergentRemoval();
        drainUntilSettled();
        assertEquals(LedgerDao.InventoryStatus.QRTN_PENDING, statusOf(operationId));

        player.getInventory().clear();
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 64));

        outbox.reconcile(player);
        drainUntilSettled();

        assertEquals(64, Inventories.countPlain(
                player.getInventory(), Material.COBBLESTONE),
                "odstawiona operacja nie może już nic zabrać");
        assertEquals(LedgerDao.InventoryStatus.QRTN_PENDING, statusOf(operationId),
                "i nie może po cichu dojść do skutku");
        assertFalse(coordinator.isBusy(playerId));
    }

    @Test
    void quarantinedOperationIsInvisibleToRecoveryScans() {
        String operationId = beginNonConvergentRemoval();
        drainUntilSettled();
        assertEquals(LedgerDao.InventoryStatus.QRTN_PENDING, statusOf(operationId));

        assertTrue(ledger.pendingInventoryOperations(playerId).join().isEmpty(),
                "inaczej start() zamroziłby gracza po każdym restarcie");
        assertFalse(ledger.pendingInventoryPlayers().join().contains(playerId));
    }

    private void replaceOutboxWithDelayedStartupScan() {
        outbox.stop();
        sql.delayNextQuery();
        coordinator = new PlayerOperationCoordinator();
        outbox = new InventoryOutbox(plugin, ledger, coordinator, 1L,
                ignored -> checkpointCalls.incrementAndGet());
        outbox.start();
    }

    private void assertRejectedEntityRetryFallsBack(DelayedFailure failure) {
        PlayerOperationCoordinator.Lease activeLease =
                coordinator.tryAcquire(playerId, false).orElseThrow();
        scheduler.failNextDelayed(failure);

        outbox.reconcile(player);
        outbox.reconcile(player);

        assertEquals(0, scheduler.delayedCount());
        assertFalse(outbox.isEconomyReady(playerId));

        coordinator.release(activeLease);
        server.getScheduler().performTicks(2L);

        assertTrue(outbox.isEconomyReady(playerId));
        assertFalse(coordinator.isBusy(playerId));
    }

    private void assertRejectedDurableContinuationRecovers(DelayedFailure failure) {
        scheduler.failNextRun(failure);
        List<InventoryOutbox.Outcome> outcomes = new ArrayList<>();

        outbox.beginGrant(player, -10L, "shop:buy:rejected-continuation-" + failure,
                "server_shop_buy", new ItemStack(Material.COBBLESTONE, 1),
                outcomes::add);

        assertTrue(outcomes.isEmpty());
        assertFalse(outbox.isEconomyReady(playerId));
        assertTrue(coordinator.isInventoryMovementBlocked(playerId));
        assertFalse(coordinator.isBusy(playerId));
        assertEquals(1, ledger.pendingInventoryOperations(playerId).join().size());

        outbox.reconcile(player);

        assertEquals(List.of(InventoryOutbox.Outcome.SUCCESS), outcomes);
        assertTrue(outbox.isEconomyReady(playerId));
        assertFalse(coordinator.isInventoryMovementBlocked(playerId));
        assertFalse(coordinator.isBusy(playerId));
        assertTrue(ledger.pendingInventoryOperations(playerId).join().isEmpty());
        assertEquals(2, checkpointCalls.get());
    }

    /**
     * F16. Strażnik mutacji odpowiadający wolniej niż dawny limit 600 ms nie
     * może kosztować gracza nagrody.
     *
     * <p>Stary kod robił {@code isBlockedForPlayer(id).get(600, MILLISECONDS)}
     * na wątku wywołującym; spóźniona odpowiedź kończyła się
     * {@code TimeoutException}, a fail-closed oddawał {@code REJECTED} —
     * dokładnie to, co produkcja zapisała 2026-09-01 przy zadaniu „toolsmith".
     * Żadna blokada nie była wtedy aktywna, co odwzorowuje pusty wynik zapytania.
     */
    @Test
    @DisplayName("Wolny strażnik mutacji nie zabiera graczowi nagrody")
    void slowMutationGuardStillDeliversTheReward() throws Exception {
        SlowGuardSqlService guardSql = new SlowGuardSqlService(900L);
        outbox.setMutationGuard(new ProfileMutationGuard(new ProfileTransitionDao(guardSql)));

        CompletableFuture<InventoryOutbox.Outcome> outcome = new CompletableFuture<>();
        outbox.beginGrant(player, 0L,
                "quest:2026-09-01:toolsmith:items:skyblock:token/silver_lotus",
                "quest_reward_items", new ItemStack(Material.DIAMOND, 1), outcome::complete);

        assertEquals(InventoryOutbox.Outcome.SUCCESS, outcome.get(10L, TimeUnit.SECONDS));
        assertEquals(1, Inventories.countPlain(player.getInventory(), Material.DIAMOND));
        assertEquals(1, guardSql.queries(), "strażnik ma być pytany dokładnie raz na nadanie");
    }

    /**
     * Strażnik odpowiadający po czasie — jak SQLite czekający na zwolnienie
     * pliku ({@code busy_timeout=5000}). Nie blokuje niczyjego wątku: obietnica
     * domyka się sama po zadanym opóźnieniu.
     */
    private static final class SlowGuardSqlService implements SqlService {
        private final long delayMillis;
        private final AtomicInteger queries = new AtomicInteger();

        private SlowGuardSqlService(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        int queries() {
            return queries.get();
        }

        @Override public boolean isEnabled() { return true; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isHealthy() { return true; }
        @Override public @NotNull Map<String, Object> poolStats() { return Map.of(); }

        @Override
        public @NotNull CompletableFuture<Integer> update(
                @NotNull String query, @NotNull Object... params) {
            throw new UnsupportedOperationException("strażnik tylko czyta");
        }

        @Override
        public <T> @NotNull CompletableFuture<List<T>> query(
                @NotNull String query, @NotNull RowMapper<T> mapper,
                @NotNull Object... params) {
            queries.incrementAndGet();
            // Pusty wynik = brak blokującego przejścia. Liczy się wyłącznie to,
            // że odpowiedź przychodzi później niż dawny limit 600 ms.
            return CompletableFuture.supplyAsync(List::<T>of,
                    CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS));
        }

        @Override
        public <T> @NotNull CompletableFuture<T> withConnection(@NotNull SqlAction<T> action) {
            throw new UnsupportedOperationException("strażnik tylko czyta");
        }

        @Override
        public @NotNull Object dataSource() {
            throw new UnsupportedOperationException("strażnik tylko czyta");
        }
    }

    private static Player player(UUID playerId, EntityScheduler scheduler,
                                 PlayerInventory inventory) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "getScheduler" -> scheduler;
                    case "getInventory" -> inventory;
                    case "isOnline" -> true;
                    case "getName" -> "OutboxTestPlayer";
                    case "toString" -> "OutboxTestPlayer[" + playerId + ']';
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0F;
        }
        return 0.0D;
    }

    private static final class TestEntityScheduler implements EntityScheduler {
        private final ArrayDeque<Runnable> delayed = new ArrayDeque<>();
        private final ScheduledTask task = (ScheduledTask) Proxy.newProxyInstance(
                ScheduledTask.class.getClassLoader(), new Class<?>[]{ScheduledTask.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        private int executedDelayed;
        private long lastDelayTicks;
        private Runnable lastDelayedRetired;
        private DelayedFailure nextDelayedFailure = DelayedFailure.NONE;
        private DelayedFailure nextRunFailure = DelayedFailure.NONE;
        private boolean retired;

        @Override
        public boolean execute(org.bukkit.plugin.Plugin plugin, Runnable run,
                               Runnable retired, long delay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledTask run(org.bukkit.plugin.Plugin plugin,
                                 Consumer<ScheduledTask> run, Runnable retired) {
            if (this.retired) {
                retired.run();
                return null;
            }
            DelayedFailure failure = nextRunFailure;
            nextRunFailure = DelayedFailure.NONE;
            if (failure == DelayedFailure.RETURN_NULL) {
                return null;
            }
            if (failure == DelayedFailure.THROW) {
                throw new IllegalStateException("injected entity scheduler rejection");
            }
            run.accept(task);
            return task;
        }

        @Override
        public ScheduledTask runDelayed(org.bukkit.plugin.Plugin plugin,
                                        Consumer<ScheduledTask> run,
                                        Runnable retired, long delayTicks) {
            if (this.retired) {
                retired.run();
                return null;
            }
            DelayedFailure failure = nextDelayedFailure;
            nextDelayedFailure = DelayedFailure.NONE;
            if (failure == DelayedFailure.RETURN_NULL) {
                return null;
            }
            if (failure == DelayedFailure.THROW) {
                throw new IllegalStateException("injected entity scheduler rejection");
            }
            lastDelayTicks = delayTicks;
            lastDelayedRetired = retired;
            delayed.add(() -> run.accept(task));
            return task;
        }

        @Override
        public ScheduledTask runAtFixedRate(org.bukkit.plugin.Plugin plugin,
                                            Consumer<ScheduledTask> run,
                                            Runnable retired, long initialDelayTicks,
                                            long periodTicks) {
            throw new UnsupportedOperationException();
        }

        int delayedCount() {
            return delayed.size();
        }

        int executedDelayed() {
            return executedDelayed;
        }

        long lastDelayTicks() {
            return lastDelayTicks;
        }

        void retire() {
            retired = true;
        }

        void failNextDelayed(DelayedFailure failure) {
            if (failure == DelayedFailure.NONE) {
                throw new IllegalArgumentException("failure mode is required");
            }
            nextDelayedFailure = failure;
        }

        void failNextRun(DelayedFailure failure) {
            if (failure == DelayedFailure.NONE) {
                throw new IllegalArgumentException("failure mode is required");
            }
            nextRunFailure = failure;
        }

        void retireNextDelayed() {
            Runnable callback = lastDelayedRetired;
            if (callback == null || delayed.isEmpty()) {
                throw new AssertionError("no delayed task is available for retirement");
            }
            delayed.removeFirst();
            lastDelayedRetired = null;
            callback.run();
        }

        void runNextDelayed() {
            Runnable next = delayed.removeFirst();
            lastDelayedRetired = null;
            executedDelayed++;
            next.run();
        }

    }

    private enum DelayedFailure {
        NONE,
        RETURN_NULL,
        THROW
    }

    private static final class FaultSqlService implements SqlService {
        private final Connection connection;
        private final AtomicInteger failWithConnections = new AtomicInteger();
        private CompletableFuture<Void> delayedConnection;
        private CompletableFuture<Void> delayedQuery;
        private boolean delayNextWithConnection;
        private boolean delayNextQuery;

        private FaultSqlService() throws SQLException {
            connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        }

        void failNextWithConnections(int count) {
            failWithConnections.set(count);
        }

        void delayNextWithConnection() {
            if (delayNextWithConnection || delayedConnection != null) {
                throw new IllegalStateException("a delayed connection is already configured");
            }
            delayNextWithConnection = true;
            delayedConnection = new CompletableFuture<>();
        }

        void releaseDelayedConnection() {
            CompletableFuture<Void> delayed = delayedConnection;
            if (delayed == null || !delayed.complete(null)) {
                throw new IllegalStateException("no delayed connection is pending");
            }
        }

        void delayNextQuery() {
            if (delayNextQuery || delayedQuery != null) {
                throw new IllegalStateException("a delayed query is already configured");
            }
            delayNextQuery = true;
            delayedQuery = new CompletableFuture<>();
        }

        void releaseDelayedQuery() {
            CompletableFuture<Void> delayed = delayedQuery;
            if (delayed == null || !delayed.complete(null)) {
                throw new IllegalStateException("no delayed query is pending");
            }
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
                // Test cleanup.
            }
        }

        @Override
        public @NotNull CompletableFuture<Integer> update(
                @NotNull String query, @NotNull Object... params) {
            return completed(() -> {
                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    bind(statement, params);
                    return statement.executeUpdate();
                }
            });
        }

        @Override
        public <T> @NotNull CompletableFuture<List<T>> query(
                @NotNull String query, @NotNull RowMapper<T> mapper,
                @NotNull Object... params) {
            SqlSupplier<List<T>> action = () -> {
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
            };
            if (delayNextQuery) {
                delayNextQuery = false;
                CompletableFuture<Void> delayed = delayedQuery;
                return delayed.thenCompose(ignored -> completed(action));
            }
            return completed(action);
        }

        @Override
        public <T> @NotNull CompletableFuture<T> withConnection(
                @NotNull SqlAction<T> action) {
            if (failWithConnections.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                return CompletableFuture.failedFuture(
                        new SQLException("injected ambiguous connection failure"));
            }
            if (delayNextWithConnection) {
                delayNextWithConnection = false;
                CompletableFuture<Void> delayed = delayedConnection;
                return delayed.thenCompose(ignored ->
                        completed(() -> action.execute(connection)));
            }
            return completed(() -> action.execute(connection));
        }

        @Override
        public @NotNull Object dataSource() {
            return connection;
        }

        private static void bind(PreparedStatement statement, Object[] params)
                throws SQLException {
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
