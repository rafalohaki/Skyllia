package org.rafalohaki.wpmecore.addons.skyblock.content;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.reward.RewardClaimDao;
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

class ContentAssignmentAndRewardClaimTest {

    private SingleConnectionSqlService sql;
    private ContentAssignmentDao contentDao;
    private RewardClaimDao rewardDao;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        contentDao = new ContentAssignmentDao(sql);
        rewardDao = new RewardClaimDao(sql);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void contentAssignmentIsPerProfileAndDomain() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        contentDao.assign(p1, "classic", "classic-v1").join();
        contentDao.assign(p1, "oneblock", "v2").join();
        contentDao.assign(p2, "classic", "classic-v2").join();

        assertEquals("classic-v1", contentDao.findVersion(p1, "classic").join().orElseThrow());
        assertEquals("v2", contentDao.findVersion(p1, "oneblock").join().orElseThrow());
        assertEquals("classic-v2", contentDao.findVersion(p2, "classic").join().orElseThrow());
        contentDao.assign(p1, "classic", "classic-v3").join();
        assertEquals("classic-v3", contentDao.findVersion(p1, "classic").join().orElseThrow());
    }

    @Test
    void rewardClaimIsIdempotentPerProfileReward() {
        UUID profile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        String reward = "classic:starter";
        String op1 = "op:1";
        String op2 = "op:2";
        assertTrue(rewardDao.tryClaim(profile, player, reward, op1).join());
        assertFalse(rewardDao.tryClaim(profile, player, reward, op2).join());
        assertTrue(rewardDao.isClaimed(profile, player, reward).join());
        assertEquals(op1, rewardDao.findOperation(profile, player, reward).join().orElseThrow());
        UUID otherPlayer = UUID.randomUUID();
        assertTrue(rewardDao.tryClaim(profile, otherPlayer, reward, "op:other").join());
        UUID otherProfile = UUID.randomUUID();
        assertTrue(rewardDao.tryClaim(otherProfile, player, reward, "op:otherProfile").join());
        assertFalse(rewardDao.tryClaim(profile, player, "other:reward", op1).join());
    }

    @Test
    void contentAndRewardDoNotCrossProfiles() {
        UUID classicProfile = UUID.randomUUID();
        UUID expeditionProfile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        contentDao.assign(classicProfile, "classic", "v1").join();
        contentDao.assign(expeditionProfile, "expedition", "v1").join();
        assertTrue(contentDao.findVersion(expeditionProfile, "classic").join().isEmpty());
        assertTrue(contentDao.findVersion(classicProfile, "expedition").join().isEmpty());
        rewardDao.tryClaim(classicProfile, player, "reward:1", "op:classic1").join();
        assertFalse(rewardDao.isClaimed(expeditionProfile, player, "reward:1").join());
        assertTrue(rewardDao.isClaimed(classicProfile, player, "reward:1").join());
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
