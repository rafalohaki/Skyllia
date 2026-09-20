package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.IslandProfileDao;
import org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileTransitionDao;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ISLAND-2: SkyblockRemoveMemberEvent musi deaktywować membership OFIARY
 * (nie wykonawcy — journal zapisuje UUID wykonawcy kicka) oraz domykać
 * oczekujące tranzycje LEAVE/KICK tej wyspy, żeby wykonawca nie siedział
 * zablokowany (hasBlockingTransitionForPlayer) do relogu.
 */
class IslandMemberRemoveListenerTest {

    private SingleConnectionSqlService sql;
    private IslandProfileDao profileDao;
    private ProfileTransitionDao transitionDao;
    private IslandMemberRemoveListener listener;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        profileDao = new IslandProfileDao(sql);
        transitionDao = new ProfileTransitionDao(sql);
        var plugin = mock(org.bukkit.plugin.java.JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("test"));
        listener = new IslandMemberRemoveListener(plugin, profileDao, transitionDao);
        listener.setCloseRetryDelaysMs(1L, 1L);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void removeMemberDeactivatesVictimMembershipAndClosesPendingKickJournal() {
        UUID islandId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();   // wykonawca kicka
        UUID victimUuid = UUID.randomUUID();  // ofiara kicka
        assertTrue(profileDao.tryCreateProfile(islandId, ownerUuid, "CLASSIC").join());
        assertTrue(profileDao.tryJoin(islandId, victimUuid, "MEMBER").join());

        // journal KICK wykonawcy (player_uuid = wykonawca, nie ofiara) — utknął pending
        String op = "op:kick:" + ownerUuid;
        assertTrue(transitionDao.create(op, islandId, ownerUuid, "KICK", "ACTIVE", "CLEANING", "SNAPSHOT_WRITTEN").join());
        assertTrue(transitionDao.hasBlockingTransitionForPlayer(ownerUuid).join(),
                "seed: wykonawca zablokowany przez pending KICK");

        listener.onMemberRemoved(islandId, victimUuid).join();

        // ofiara wyleciała z membership...
        assertTrue(profileDao.findActiveMembership(victimUuid).join().isEmpty(),
                "membership ofiary musi być zdezaktywowany");
        // ...wykonawca i profil wyspy nietknięci...
        assertTrue(profileDao.findActiveMembership(ownerUuid).join().isPresent(),
                "membership wykonawcy przeżywa kick ofiary");
        assertEquals("ACTIVE", profileDao.findProfile(islandId).join().orElseThrow().status().name(),
                "profil wyspy przeżywa kick");
        // ...a journal (i blokada ekonomii wykonawcy) domknięty
        assertNotNull(transitionDao.find(op).join().orElseThrow().result(),
                "tranzycja KICK wykonawcy domknięta");
        assertFalse(transitionDao.hasBlockingTransitionForPlayer(ownerUuid).join(),
                "wykonawca odblokowany po domknięciu journala");
    }

    @Test
    void removeMemberClosesPendingLeaveJournalOfTheLeaver() {
        UUID islandId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        UUID leaverUuid = UUID.randomUUID();
        assertTrue(profileDao.tryCreateProfile(islandId, ownerUuid, "CLASSIC").join());
        assertTrue(profileDao.tryJoin(islandId, leaverUuid, "MEMBER").join());

        // journal LEAVE — tu wykonawca == opuszczający
        String op = "op:leave:" + leaverUuid;
        assertTrue(transitionDao.create(op, islandId, leaverUuid, "LEAVE", "ACTIVE", "CLEANING", "SNAPSHOT_WRITTEN").join());

        listener.onMemberRemoved(islandId, leaverUuid).join();

        assertTrue(profileDao.findActiveMembership(leaverUuid).join().isEmpty(),
                "membership opuszczającego zdezaktywowany");
        assertNotNull(transitionDao.find(op).join().orElseThrow().result(),
                "tranzycja LEAVE opuszczającego domknięta");
    }

    @Test
    void removeMemberDoesNotTouchPendingDeleteJournal() {
        UUID islandId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        assertTrue(profileDao.tryCreateProfile(islandId, ownerUuid, "CLASSIC").join());
        assertTrue(profileDao.tryJoin(islandId, memberUuid, "MEMBER").join());

        // pending DELETE ma własny obieg domknięcia (IslandLifecycleGuard.finishPendingDelete)
        String op = "op:delete:" + ownerUuid;
        assertTrue(transitionDao.create(op, islandId, ownerUuid, "DELETE", "ACTIVE", "CLEANING", "SNAPSHOT_WRITTEN").join());

        listener.onMemberRemoved(islandId, memberUuid).join();

        assertNull(transitionDao.find(op).join().orElseThrow().result(),
                "tranzycja DELETE nie należy do listenera member-remove");
    }

    @Test
    void removeMemberOfForeignIslandOrWithoutRowIsIdempotent() {
        UUID islandId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        assertTrue(profileDao.tryCreateProfile(islandId, ownerUuid, "CLASSIC").join());

        // ofiara nie ma wiersza membership (np. już LEFT) — wywołanie nie wybucha
        CompletableFuture<Void> done = listener.onMemberRemoved(islandId, UUID.randomUUID());
        assertDoesNotThrow(() -> done.join());
        assertTrue(profileDao.findActiveMembership(ownerUuid).join().isPresent(),
                "membership właściciela nietknięty przy pustym deactivate");
    }

    @Test
    void journalInsertedAfterTheFirstScanStillGetsClosed() throws Exception {
        // Wyścig ISLAND-2/A1-05: Skyllia odpala SkyblockRemoveMemberEvent na AsyncScheduler
        // równolegle z zapisem journala (activeMembership → snapshotBeforeClean →
        // transitionDao.create, SQLite pool=1). Pierwszy skan trafia w pustkę; bez ponowienia
        // wiersz zostaje z result IS NULL i wykonawca kicka siedzi zablokowany na ekonomii
        // i komendach destrukcyjnych aż do relogu — czyli dokładnie objaw, który ISLAND-2
        // miał usunąć.
        listener.setCloseRetryDelaysMs(800L, 800L);
        UUID islandId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        UUID victimUuid = UUID.randomUUID();
        assertTrue(profileDao.tryCreateProfile(islandId, ownerUuid, "CLASSIC").join());
        assertTrue(profileDao.tryJoin(islandId, victimUuid, "MEMBER").join());

        CompletableFuture<Void> projection = listener.onMemberRemoved(islandId, victimUuid);

        Thread.sleep(200L); // pierwszy skan zdążył zobaczyć pustkę
        String op = "op:kick:" + ownerUuid;
        assertTrue(transitionDao.create(op, islandId, ownerUuid, "KICK", "ACTIVE", "CLEANING",
                "SNAPSHOT_WRITTEN").join());

        projection.join();

        assertFalse(transitionDao.hasBlockingTransitionForPlayer(ownerUuid).join(),
                "spozniony journal tez musi zostac domkniety — inaczej wykonawca kicka siedzi "
                        + "zablokowany do relogu");
    }
}
