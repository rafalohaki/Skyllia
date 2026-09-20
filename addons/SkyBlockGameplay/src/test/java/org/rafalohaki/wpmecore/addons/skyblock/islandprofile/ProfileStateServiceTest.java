package org.rafalohaki.wpmecore.addons.skyblock.islandprofile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ProfileStateServiceTest {

    private SingleConnectionSqlService sql;
    private IslandProfileDao dao;
    private ProfileStateService service;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        dao = new IslandProfileDao(sql);
        service = new ProfileStateService(dao);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void singleCreateSucceeds() {
        UUID owner = UUID.randomUUID();
        UUID island = UUID.randomUUID();
        boolean ok = service.tryCreateIsland(owner, island, "CLASSIC").join();
        assertTrue(ok);
        assertTrue(service.activeMembership(owner).join().isPresent());
    }

    @Test
    void duplicateCreateForSameUuidOnlyOneWins() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID island1 = UUID.randomUUID();
        UUID island2 = UUID.randomUUID();

        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Boolean> f1 = CompletableFuture.supplyAsync(() -> service.tryCreateIsland(owner, island1, "CLASSIC").join(), exec);
            CompletableFuture<Boolean> f2 = CompletableFuture.supplyAsync(() -> service.tryCreateIsland(owner, island2, "CLASSIC").join(), exec);
            List<Boolean> results = List.of(f1.join(), f2.join());
            long successes = results.stream().filter(b -> b).count();
            assertEquals(1, successes, "exactly one create should succeed, got " + results);
            // Active membership should point to the winner
            var active = service.activeMembership(owner).join();
            assertTrue(active.isPresent());
            assertTrue(active.get().islandId().equals(island1) || active.get().islandId().equals(island2));
        } finally {
            exec.shutdown();
            exec.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentAcceptRaceOnlyOneActivePerPlayer() throws Exception {
        UUID islandA = UUID.randomUUID();
        UUID islandB = UUID.randomUUID();
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        UUID joiner = UUID.randomUUID();

        assertTrue(service.tryCreateIsland(ownerA, islandA, "CLASSIC").join());
        assertTrue(service.tryCreateIsland(ownerB, islandB, "CLASSIC").join());

        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Boolean> f1 = CompletableFuture.supplyAsync(() -> service.tryAcceptInvite(joiner, islandA, "MEMBER").join(), exec);
            CompletableFuture<Boolean> f2 = CompletableFuture.supplyAsync(() -> service.tryAcceptInvite(joiner, islandB, "MEMBER").join(), exec);
            boolean r1 = f1.join();
            boolean r2 = f2.join();
            long wins = (r1 ? 1 : 0) + (r2 ? 1 : 0);
            assertEquals(1, wins, "exactly one accept should succeed, got " + r1 + "," + r2);
            var active = service.activeMembership(joiner).join();
            assertTrue(active.isPresent());
            // Ensure one active membership row in DB
            long count = sql.query("SELECT COUNT(*) AS c FROM wpme_sb_island_membership WHERE player_uuid = ? AND status='ACTIVE'",
                    rs -> rs.getLong(1), joiner.toString()).join().get(0);
            assertEquals(1L, count);
        } finally {
            exec.shutdown();
            exec.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void nickConflictBlocksAndResolveAllowsCreate() {
        // This test documents integration: Profile creation itself does not check Identity,
        // but the command guard does. Here we just verify profile limit independent of identity.
        UUID owner = UUID.randomUUID();
        UUID i1 = UUID.randomUUID();
        UUID i2 = UUID.randomUUID();
        assertTrue(service.tryCreateIsland(owner, i1, "CLASSIC").join());
        assertFalse(service.tryCreateIsland(owner, i2, "CLASSIC").join());
        // After leaving, can create again
        assertTrue(service.leaveIsland(owner, i1).join());
        UUID i3 = UUID.randomUUID();
        assertTrue(service.tryCreateIsland(owner, i3, "CLASSIC").join());
    }

    @Test
    void fiftyFiveConcurrentCreatesForSameUuid() throws Exception {
        UUID owner = UUID.randomUUID();
        List<UUID> islands = new ArrayList<>();
        for (int i = 0; i < 55; i++) islands.add(UUID.randomUUID());

        ExecutorService exec = Executors.newFixedThreadPool(16);
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (UUID is : islands) {
            futures.add(CompletableFuture.supplyAsync(() -> service.tryCreateIsland(owner, is, "CLASSIC").join(), exec));
        }
        List<Boolean> results = futures.stream().map(CompletableFuture::join).toList();
        long wins = results.stream().filter(b -> b).count();
        assertEquals(1, wins, "55 concurrent creates: exactly one should win, got " + wins);
        exec.shutdown();
        exec.awaitTermination(3, TimeUnit.SECONDS);
    }
}
