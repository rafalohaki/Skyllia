package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileTransitionDao;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ISLAND-5: przy /is delete confirm journal-insert jest asynchroniczny —
 * finishPendingDelete może przegrać wyścig z insertem (pierwszy lookup pusty)
 * i zostawić tranzycję NULL, blokując gracza do relogu. Fix: bounded retry.
 */
class IslandLifecycleGuardFinishDeleteRetryTest {

    private SingleConnectionSqlService sql;
    private ProfileTransitionDao transitionDao;
    private IslandLifecycleGuard guard;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        transitionDao = new ProfileTransitionDao(sql);
        var plugin = mock(org.bukkit.plugin.java.JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        guard = new IslandLifecycleGuard(
                plugin, mock(SkylliaIntegration.class),
                islandId -> CompletableFuture.completedFuture(0L),
                MiniMessage.miniMessage(), null);
        guard.setTransitionDao(transitionDao);
        // krótkie, ale niezerowe opóźnienia: test wstawia wiersz PO pierwszym
        // (synchronicznym) lookupie, a PRZED retrym
        guard.setFinishPendingDeleteRetryDelaysMs(200L, 200L);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void finishPendingDeleteRetriesWhenJournalInsertLandsLate() throws Exception {
        UUID islandId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        String op = "op:delete:" + ownerUuid;

        // pierwszy lookup jest synchroniczny i pusty (insert jeszcze w locie)...
        CompletableFuture<Boolean> finished = guard.finishPendingDelete(ownerUuid, "DELETED");

        // ...dopiero teraz ląduje wiersz journala (wyścig z async insertem)
        assertTrue(transitionDao.create(op, islandId, ownerUuid, "DELETE", "ACTIVE", "CLEANING", "SNAPSHOT_WRITTEN").join());

        assertTrue(finished.get(5, TimeUnit.SECONDS), "retry musi znaleźć i domknąć spóźniony wiersz");
        assertEquals("DELETED", transitionDao.find(op).join().orElseThrow().result(),
                "tranzycja domknięta wynikiem DELETED");
    }

    @Test
    void finishPendingDeleteCompletesImmediatelyWhenRowPresent() throws Exception {
        UUID islandId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        String op = "op:delete:" + ownerUuid;
        assertTrue(transitionDao.create(op, islandId, ownerUuid, "DELETE", "ACTIVE", "CLEANING", "SNAPSHOT_WRITTEN").join());

        CompletableFuture<Boolean> finished = guard.finishPendingDelete(ownerUuid, "DELETED");

        assertTrue(finished.get(5, TimeUnit.SECONDS));
        assertEquals("DELETED", transitionDao.find(op).join().orElseThrow().result());
    }

    @Test
    void finishPendingDeleteGivesUpAfterBoundedRetries() throws Exception {
        UUID ownerUuid = UUID.randomUUID(); // nigdy nie ma wiersza

        CompletableFuture<Boolean> finished = guard.finishPendingDelete(ownerUuid, "DELETED");

        assertFalse(finished.get(5, TimeUnit.SECONDS),
                "po wyczerpaniu retry zwraca false zamiast wisieć");
    }

    @Test
    void finishPendingDeleteWithoutDaoOrOwnerIsNoOp() throws Exception {
        assertFalse(guard.finishPendingDelete(null, "DELETED").get(5, TimeUnit.SECONDS));
        IslandLifecycleGuard bare = new IslandLifecycleGuard(
                mock(org.bukkit.plugin.java.JavaPlugin.class), mock(SkylliaIntegration.class),
                islandId -> CompletableFuture.completedFuture(0L),
                MiniMessage.miniMessage(), null);
        assertFalse(bare.finishPendingDelete(UUID.randomUUID(), "DELETED").get(5, TimeUnit.SECONDS));
    }
}
