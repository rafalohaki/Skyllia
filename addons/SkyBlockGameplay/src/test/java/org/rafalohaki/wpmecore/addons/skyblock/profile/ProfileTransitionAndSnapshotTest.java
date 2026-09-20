package org.rafalohaki.wpmecore.addons.skyblock.profile;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
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

class ProfileTransitionAndSnapshotTest {

    private SingleConnectionSqlService sql;
    private ProfileTransitionDao transitionDao;
    private ProfileSnapshotDao snapshotDao;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        transitionDao = new ProfileTransitionDao(sql);
        snapshotDao = new ProfileSnapshotDao(sql);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void transitionJournalIsDurableAndCheckpointed() {
        String op = "op:leave:" + UUID.randomUUID();
        UUID profile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        assertTrue(transitionDao.create(op, profile, player, "LEAVE", "ACTIVE", "LEFT", "PREPARE").join());
        assertFalse(transitionDao.create(op, profile, player, "LEAVE", "ACTIVE", "LEFT", "PREPARE").join());
        assertTrue(transitionDao.updateCheckpoint(op, "SNAPSHOT_WRITTEN").join());
        assertTrue(transitionDao.complete(op, "SUCCESS").join());
        var found = transitionDao.find(op).join();
        assertTrue(found.isPresent());
        assertEquals("SUCCESS", found.get().result());
        assertEquals("COMPLETE", found.get().checkpoint());
        assertFalse(transitionDao.complete(op, "SUCCESS").join());
    }

    @Test
    void snapshotHasServerScopeAndChecksum() {
        UUID profile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        String snapId = "snap:" + UUID.randomUUID();
        byte[] payload = "inventory".getBytes();
        String sha = snapshotDao.create(snapId, profile, player, "skyblock", "INVENTORY", payload).join();
        assertNotNull(sha);
        assertTrue(sha.matches("[0-9a-f]{64}"));
        var snap = snapshotDao.find(snapId).join().orElseThrow();
        assertEquals(profile, snap.profileId());
        assertEquals(player, snap.playerUuid());
        assertEquals("skyblock", snap.serverScope());
        assertEquals("INVENTORY", snap.snapshotType());
        assertArrayEquals(payload, snap.payload());
        assertTrue(snapshotDao.verifyChecksum(snapId).join());
        assertEquals(sha, snap.payloadSha256());
        assertEquals(sha, snap.contentSha256());
    }

    @Test
    void snapshotCorruptionIsDetected() {
        UUID profile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        String snapId = "snap:corrupt:" + UUID.randomUUID();
        snapshotDao.create(snapId, profile, player, "skyblock", "ENDER", "data".getBytes()).join();
        sql.update("UPDATE wpme_sb_profile_snapshots SET payload_sha256 = ? WHERE snapshot_id = ?", "0".repeat(64), snapId).join();
        assertFalse(snapshotDao.verifyChecksum(snapId).join());
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
