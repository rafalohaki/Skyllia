package org.rafalohaki.wpmecore.addons.skyblock.migration;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.IslandProfileDao;
import org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileAccountDao;
import org.rafalohaki.wpmecore.api.service.SqlDialect;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class BackfillServiceTest {

    private SingleConnectionSqlService sql;
    private LedgerDao ledgerDao;
    private IslandProfileDao profileDao;
    private ProfileAccountDao profileAccountDao;
    private BackfillService backfill;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        ledgerDao = new LedgerDao(sql);
        profileDao = new IslandProfileDao(sql);
        profileAccountDao = new ProfileAccountDao(sql);
        backfill = new BackfillService(sql);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void dryRunReportsChecksumsAndIssues() {
        UUID island = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        assertTrue(profileDao.tryCreateProfile(island, owner, "CLASSIC").join());
        insertLegacyBalance(owner, 500L, "tx:owner");
        insertLegacyBalance(stranger, 300L, "tx:stranger");
        BackfillReport report = backfill.dryRun().join();
        assertEquals(800L, report.checksumBefore());
        assertTrue(report.accountsWithoutProfile().contains("player:" + stranger));
        assertFalse(report.accountsWithoutProfile().contains("player:" + owner));
    }

    @Test
    void backfillMigratesClearAccountsAndPreservesChecksum() {
        UUID islandA = UUID.randomUUID();
        UUID ownerA = UUID.randomUUID();
        UUID islandB = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        assertTrue(profileDao.tryCreateProfile(islandA, ownerA, "CLASSIC").join());
        assertTrue(profileDao.tryCreateProfile(islandB, ownerB, "CLASSIC").join());
        insertLegacyBalance(ownerA, 1000L, "tx:a");
        insertLegacyBalance(ownerB, 2000L, "tx:b");
        BackfillReport before = backfill.dryRun().join();
        assertEquals(3000L, before.checksumBefore());
        assertTrue(before.accountsWithoutProfile().isEmpty());
        BackfillReport after = backfill.backfill(false).join();
        assertEquals(3000L, after.checksumBefore());
        assertEquals(3000L, after.checksumAfter());
        assertTrue(after.checksumMatches());
        assertEquals(2, after.accountsMigrated());
        assertEquals(0, after.ambiguousAccounts());
        assertEquals(1000L, profileAccountDao.balance(islandA, ownerA).join());
        assertEquals(2000L, profileAccountDao.balance(islandB, ownerB).join());
    }

    @Test
    void ambiguousAccountsAreQuarantinedNotRandomlyAssigned() {
        UUID islandA = UUID.randomUUID();
        UUID islandB = UUID.randomUUID();
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        UUID multiPlayer = UUID.randomUUID();
        assertTrue(profileDao.tryCreateProfile(islandA, ownerA, "CLASSIC").join());
        assertTrue(profileDao.tryCreateProfile(islandB, ownerB, "CLASSIC").join());
        // Simulate legacy duplicate ACTIVE memberships (before M1-A guard) by temporarily dropping the partial unique index
        sql.update("DROP INDEX IF EXISTS ux_wpme_sb_active_membership").join();
        sql.update("INSERT INTO wpme_sb_island_membership (island_id, player_uuid, role, status, joined_at, updated_at) VALUES (?, ?, 'MEMBER','ACTIVE', ?,?)",
                islandA.toString(), multiPlayer.toString(), System.currentTimeMillis(), System.currentTimeMillis()).join();
        sql.update("INSERT INTO wpme_sb_island_membership (island_id, player_uuid, role, status, joined_at, updated_at) VALUES (?, ?, 'MEMBER','ACTIVE', ?,?)",
                islandB.toString(), multiPlayer.toString(), System.currentTimeMillis(), System.currentTimeMillis()).join();
        insertLegacyBalance(multiPlayer, 999L, "tx:multi");
        insertLegacyBalance(ownerA, 100L, "tx:ownerA");
        BackfillReport report = backfill.backfill(false).join();
        assertTrue(report.accountsWithoutProfile().contains("player:" + multiPlayer));
        assertFalse(report.accountsWithoutProfile().contains("player:" + ownerA));
        assertEquals(1099L, report.checksumBefore());
        assertEquals(100L, report.checksumAfter());
        assertTrue(report.checksumMatches());
        assertEquals(1, report.accountsMigrated());
        assertEquals(1, report.ambiguousAccounts());
        assertTrue(report.multipleMembershipPlayers().contains(multiPlayer));
        assertEquals(0L, profileAccountDao.balance(islandA, multiPlayer).join());
        assertEquals(0L, profileAccountDao.balance(islandB, multiPlayer).join());
    }

    @Test
    void reportsDisabledLockedMissingOwnersAndMultipleMemberships() {
        UUID islandActive = UUID.randomUUID();
        UUID islandDeleted = UUID.randomUUID();
        UUID ownerActive = UUID.randomUUID();
        UUID ownerDeleted = UUID.randomUUID();
        assertTrue(profileDao.tryCreateProfile(islandActive, ownerActive, "CLASSIC").join());
        assertTrue(profileDao.tryCreateProfile(islandDeleted, ownerDeleted, "CLASSIC").join());
        sql.update("UPDATE wpme_sb_island_profiles SET status = 'DELETED' WHERE island_id = ?", islandDeleted.toString()).join();
        UUID islandOrphan = UUID.randomUUID();
        UUID orphanOwner = UUID.randomUUID();
        sql.update("INSERT INTO wpme_sb_island_profiles (island_id, owner_uuid, mode, status, created_at, updated_at) VALUES (?, ?, 'CLASSIC','ACTIVE', ?,?)",
                islandOrphan.toString(), orphanOwner.toString(), System.currentTimeMillis(), System.currentTimeMillis()).join();
        BackfillReport report = backfill.dryRun().join();
        assertTrue(report.disabledOrLockedIslands().contains(islandDeleted));
        assertTrue(report.missingOwners().contains(islandOrphan));
        assertFalse(report.missingOwners().contains(islandActive));
    }

    @Test
    void backfillIsIdempotent() {
        UUID island = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        assertTrue(profileDao.tryCreateProfile(island, owner, "CLASSIC").join());
        insertLegacyBalance(owner, 500L, "tx:idem");
        BackfillReport first = backfill.backfill(false).join();
        BackfillReport second = backfill.backfill(false).join();
        assertEquals(first.checksumAfter(), second.checksumAfter());
        assertEquals(500L, profileAccountDao.balance(island, owner).join());
        assertEquals(1, first.accountsMigrated());
        assertTrue(second.checksumMatches());
    }

    @Test
    void contentAssignmentFoundationCreatedForMigratedProfiles() {
        UUID island = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        assertTrue(profileDao.tryCreateProfile(island, owner, "CLASSIC").join());
        insertLegacyBalance(owner, 100L, "tx:content");
        backfill.backfill(false).join();
        var count = sql.query("SELECT COUNT(*) FROM wpme_sb_content_assignments WHERE profile_id = ? AND domain = 'classic'",
                rs -> rs.getLong(1), island.toString()).join().get(0);
        assertEquals(1L, count);
    }

    private void insertLegacyBalance(UUID player, long amount, String txId) {
        String key = "player:" + player;
        sql.update("INSERT OR REPLACE INTO wpme_sb_accounts (account_key, balance_minor, updated_at) VALUES (?, ?, ?)",
                key, amount, System.currentTimeMillis()).join();
        sql.update("INSERT OR IGNORE INTO wpme_sb_transactions (transaction_id, account_key, counterparty_key, delta_minor, reason, created_at) VALUES (?, ?, NULL, ?, ?, ?)",
                txId, key, amount, "test", System.currentTimeMillis()).join();
    }

    private static final class SingleConnectionSqlService implements SqlService {
        private final Connection connection;
        SingleConnectionSqlService() throws SQLException { connection = DriverManager.getConnection("jdbc:sqlite::memory:"); }
        @Override public @NotNull SqlDialect dialect() { return SqlDialect.SQLITE; }
        @Override public boolean isEnabled() { return true; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isHealthy() { return true; }
        @Override public @NotNull Map<String, Object> poolStats() { return Map.of(); }
        @Override public void shutdown() { try { connection.close(); } catch (SQLException ignored) {} }
        @Override public @NotNull CompletableFuture<Integer> update(@NotNull String q, @NotNull Object... p) { return completed(() -> { try (PreparedStatement s = connection.prepareStatement(q)) { bind(s,p); return s.executeUpdate(); }}); }
        @Override public <T> @NotNull CompletableFuture<List<T>> query(@NotNull String q, @NotNull RowMapper<T> m, @NotNull Object... p) { return completed(() -> { try (PreparedStatement s = connection.prepareStatement(q)) { bind(s,p); try (ResultSet r=s.executeQuery()){ List<T> out=new ArrayList<>(); while(r.next()) out.add(m.map(r)); return out; } } }); }
        @Override public <T> @NotNull CompletableFuture<T> withConnection(@NotNull SqlAction<T> a) { return completed(() -> a.execute(connection)); }
        @Override public @NotNull Object dataSource() { return connection; }
        private static void bind(PreparedStatement s, Object... p) throws SQLException { for(int i=0;i<p.length;i++) s.setObject(i+1,p[i]); }
        private static <T> CompletableFuture<T> completed(SqlSupplier<T> sup){ try{return CompletableFuture.completedFuture(sup.get());}catch(Throwable e){return CompletableFuture.failedFuture(e);}}
        @FunctionalInterface private interface SqlSupplier<T>{ T get() throws Exception; }
    }
}
