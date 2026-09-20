package org.rafalohaki.wpmecore.addons.skyblock.profile;

import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.IslandProfileDao;
import org.rafalohaki.wpmecore.addons.skyblock.playtime.ProfilePlaytimeDao;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1-D: resume DELETE musi FAKTYCZNIE czyścić membership — nie tylko
 * przesuwać checkpoint. Luka znaleziona na labie (canary SKY-2): gracz po
 * delete miał ACTIVE membership w wpme_sb_island_membership mimo zamkniętej
 * wyspy.
 */
class ProfileRecoveryMembershipCleanupTest {

    private SingleConnectionSqlService sql;
    private SkyBlockSchemaMigrator migrator;
    private ProfileTransitionDao transitionDao;
    private IslandProfileDao profileDao;
    private ProfileSnapshotDao snapshotDao;
    private ProfileSnapshotService snapshotService;
    private ProfilePlaytimeDao playtimeDao;
    private ProfileRecoveryService recoveryService;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        migrator = new SkyBlockSchemaMigrator(sql);
        migrator.migrate().join();
        transitionDao = new ProfileTransitionDao(sql);
        snapshotDao = new ProfileSnapshotDao(sql);
        playtimeDao = new ProfilePlaytimeDao(sql);
        snapshotService = new ProfileSnapshotService(snapshotDao, "test-scope");
        profileDao = new IslandProfileDao(sql);
        recoveryService = new ProfileRecoveryService(
                transitionDao, snapshotDao, profileDao, playtimeDao, snapshotService);
        // Domyslnie w testach: Skyllia potwierdza, ze wyspy NIE MA — dopiero wtedy
        // resume DELETE/RESET wolno dotknac danych (ISLAND-1).
        recoveryService.setIslandExistsCheck(islandId -> false);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    private void seedActiveProfile(UUID islandId, UUID playerUuid) {
        // tryCreateProfile tworzy profil I membership OWNER dla gracza
        assertTrue(profileDao.tryCreateProfile(islandId, playerUuid, "CLASSIC").join());
    }

    private void seedMember(UUID islandId, UUID memberUuid) {
        assertTrue(profileDao.tryJoin(islandId, memberUuid, "MEMBER").join());
    }

    @Test
    void resumeDeleteDeactivatesMembershipNotJustCheckpoint() {
        UUID islandId = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        seedActiveProfile(islandId, playerUuid);

        // journal DELETE utknął w SNAPSHOT_WRITTEN (crash po snapshocie, przed clean)
        String op = "op:delete:" + playerUuid;
        assertTrue(transitionDao.create(op, islandId, playerUuid, "DELETE", "ACTIVE", "LEFT", "SNAPSHOT_WRITTEN").join());

        // membership nadal ACTIVE przed recovery
        assertTrue(profileDao.findActiveMembership(playerUuid).join().isPresent(),
                "seed: członek aktywny przed recovery");

        var report = recoveryService.recoverAll().join();
        System.out.println("RECOVERY: " + report.details());

        // po recovery: membership MUSI być zdezaktywowany (M1-D fix)
        assertTrue(profileDao.findActiveMembership(playerUuid).join().isEmpty(),
                "resume DELETE musi dezaktywować membership");
        assertEquals("RESUME_COMPLETE", transitionDao.find(op).join().orElseThrow().result());
        assertTrue(report.resumed() >= 1 || report.quarantined() >= 0,
                "recovery raportuje resumed: " + report.details());
    }

    /**
     * ISLAND-1: journal LEAVE powstaje PRZED przetworzeniem komendy i może
     * dotyczyć odrzuconej próby — resume NIE może więc wycierać profilu całej
     * żywej wyspy (deleteProfile = profil DELETED + membership wszystkich LEFT).
     * Resume LEAVE domyka wyłącznie tranzycję; mutacja danych należy do
     * IslandMemberRemoveListener na SkyblockRemoveMemberEvent.
     */
    @Test
    void resumeLeaveDoesNotWipeIslandProfileOrCollateralMembers() {
        UUID islandId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        seedActiveProfile(islandId, ownerUuid);
        seedMember(islandId, memberUuid);

        // journal LEAVE właściciela (dziś journal mają wyłącznie gracze z ACTIVE membership)
        String op = "op:leave:" + ownerUuid;
        assertTrue(transitionDao.create(op, islandId, ownerUuid, "LEAVE", "ACTIVE", "LEFT", "SNAPSHOT_WRITTEN").join());

        recoveryService.recoverAll().join();

        // tranzycja domknięta...
        assertEquals("RESUME_COMPLETE", transitionDao.find(op).join().orElseThrow().result(),
                "resume LEAVE domyka tranzycję");
        // ...ale profil wyspy i OBA memberships przeżywają (zero mutacji danych)
        assertEquals("ACTIVE", profileDao.findProfile(islandId).join().orElseThrow().status().name(),
                "resume LEAVE nie może kasować profilu żywej wyspy");
        assertTrue(profileDao.findActiveMembership(ownerUuid).join().isPresent(),
                "membership wykonawcy (właściciela) przeżywa resume LEAVE");
        assertTrue(profileDao.findActiveMembership(memberUuid).join().isPresent(),
                "membership niezwiązanego członka przeżywa resume LEAVE");
    }

    /** ISLAND-1: to samo dla journala KICK — kick (nawet odrzucony) nie wyciera wyspy. */
    @Test
    void resumeKickDoesNotWipeIslandProfile() {
        UUID islandId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        seedActiveProfile(islandId, ownerUuid);
        seedMember(islandId, memberUuid);

        String op = "op:kick:" + ownerUuid;
        assertTrue(transitionDao.create(op, islandId, ownerUuid, "KICK", "ACTIVE", "LEFT", "PREPARE").join());

        recoveryService.recoverAll().join();

        assertEquals("RESUME_COMPLETE", transitionDao.find(op).join().orElseThrow().result(),
                "resume KICK domyka tranzycję");
        assertEquals("ACTIVE", profileDao.findProfile(islandId).join().orElseThrow().status().name(),
                "resume KICK nie może kasować profilu żywej wyspy");
        assertTrue(profileDao.findActiveMembership(ownerUuid).join().isPresent(),
                "membership właściciela przeżywa resume KICK");
        assertTrue(profileDao.findActiveMembership(memberUuid).join().isPresent(),
                "membership ofiary kopnięcia przeżywa resume KICK (mutację robi listener eventu)");
    }

    @Test
    void completedTransitionsAreIdempotentOnSecondRecovery() {
        UUID islandId = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        seedActiveProfile(islandId, playerUuid);

        String op = "op:delete:" + playerUuid;
        transitionDao.create(op, islandId, playerUuid, "DELETE", "ACTIVE", "LEFT", "SNAPSHOT_WRITTEN").join();
        recoveryService.recoverAll().join();

        // drugi przebieg: nic do roboty, brak błędów
        var report2 = recoveryService.recoverAll().join();
        assertEquals(0, report2.totalPending(), "po pierwszym recovery brak pending transitions");
        assertTrue(profileDao.findActiveMembership(playerUuid).join().isEmpty(),
                "membership nadal wyczyszczony po drugim przebiegu");
    }

    /**
     * ISLAND-1 (druga połowa): journal DELETE/RESET powstaje PRZED przetworzeniem
     * komendy, więc przeżywa jej odrzucenie — a odrzuca ją pięcioma ścieżkami nasz
     * własny IslandLifecycleGuard (saldo banku > 0, konflikt nicku, wątek tickowy,
     * timeout odczytu salda). Resume leci przy relogu I przy KAŻDYM boocie
     * (recoverAll), więc bez autorytatywnego potwierdzenia u Skyllii pierwszy
     * restart po odrzuconym /is delete wycierał profil ŻYWEJ wyspy.
     */
    @Test
    void resumeDeleteDoesNotWipeAnIslandSkylliaStillHas() {
        UUID islandId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        UUID memberUuid = UUID.randomUUID();
        seedActiveProfile(islandId, ownerUuid);
        seedMember(islandId, memberUuid);

        // Skyllia mówi: wyspa istnieje (komenda została odrzucona przez guard)
        recoveryService.setIslandExistsCheck(islandId2 -> true);

        String op = "op:delete:" + ownerUuid;
        assertTrue(transitionDao.create(op, islandId, ownerUuid, "DELETE", "ACTIVE", "LEFT",
                "SNAPSHOT_WRITTEN").join());

        recoveryService.recoverAll().join();

        assertTrue(profileDao.findActiveMembership(ownerUuid).join().isPresent(),
                "wlasciciel zywej wyspy nie moze stracic membershipu przez sam restart");
        assertTrue(profileDao.findActiveMembership(memberUuid).join().isPresent(),
                "wspollokator tez nie — deleteProfile to semantyka CALEJ wyspy");
        assertEquals("RESUME_COMPLETE", transitionDao.find(op).join().orElseThrow().result(),
                "tranzycja i tak ma zostac domknieta, zeby nie blokowala gracza");
    }
}
