package org.rafalohaki.wpmecore.addons.skyblock.playtime;

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

class ProfilePlaytimeTest {

    private SingleConnectionSqlService sql;
    private ProfilePlaytimeDao dao;
    private ProfilePlaytimeService service;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        dao = new ProfilePlaytimeDao(sql);
        service = new ProfilePlaytimeService(dao);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void activeAndAfkAreDistinguished() {
        UUID profile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        long t0 = 1_000_000L;
        assertTrue(service.onJoin(profile, player, t0).join());
        assertTrue(service.onHeartbeat(profile, player, t0 + 10_000L, false).join());
        assertTrue(service.onHeartbeat(profile, player, t0 + 15_000L, true).join());
        assertTrue(service.onHeartbeat(profile, player, t0 + 25_000L, false).join());
        var row = service.playtime(profile, player).join();
        assertEquals(20_000L, row.totalActiveMs());
        assertEquals(5_000L, row.totalAfkMs());
    }

    @Test
    void idempotentCloseDoesNotDoubleCount() {
        UUID profile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        long t0 = 2_000_000L;
        service.onJoin(profile, player, t0).join();
        service.onHeartbeat(profile, player, t0 + 5_000L, false).join();
        assertTrue(service.closeSessionIdempotent(profile, player, t0 + 8_000L).join());
        var afterFirst = service.playtime(profile, player).join();
        long activeAfterFirst = afterFirst.totalActiveMs();
        assertFalse(service.closeSessionIdempotent(profile, player, t0 + 10_000L).join());
        var afterSecond = service.playtime(profile, player).join();
        assertEquals(activeAfterFirst, afterSecond.totalActiveMs());
        assertNull(afterSecond.sessionStart());
    }

    @Test
    void crashReconciliationDoesNotInflateOfflineTime() {
        UUID profile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        long t0 = 3_000_000L;
        service.onJoin(profile, player, t0).join();
        service.onHeartbeat(profile, player, t0 + 2_000L, false).join();
        long crashNow = t0 + 3_600_000L;
        int reconciled = service.reconcileCrashedSessions(crashNow).join();
        assertEquals(1, reconciled);
        var row = service.playtime(profile, player).join();
        assertEquals(2_000L, row.totalActiveMs());
        assertEquals(0L, row.totalAfkMs());
        assertNull(row.sessionStart());
        assertNull(row.lastHeartbeat());
    }

    @Test
    void reconnectAfterCrashStartsNewSessionWithoutLeak() {
        UUID profile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        long t0 = 4_000_000L;
        service.onJoin(profile, player, t0).join();
        service.onHeartbeat(profile, player, t0 + 1_000L, false).join();
        service.reconcileCrashedSessions(t0 + 10 * 60 * 1000L).join();
        long t1 = t0 + 20 * 60 * 1000L;
        assertTrue(service.onJoin(profile, player, t1).join());
        service.onHeartbeat(profile, player, t1 + 3_000L, false).join();
        var row = service.playtime(profile, player).join();
        assertEquals(4_000L, row.totalActiveMs());
    }

    @Test
    void afkDoesNotInflateBeyondToleranceAfterReconnectCrash() {
        UUID profile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        long t0 = 5_000_000L;
        service.onJoin(profile, player, t0).join();
        service.onHeartbeat(profile, player, t0 + 1_000L, false).join();
        service.onHeartbeat(profile, player, t0 + 2_000L, true).join();
        service.onQuit(profile, player, t0 + 3_000L).join();
        var row = service.playtime(profile, player).join();
        assertEquals(1_000L, row.totalActiveMs());
        assertEquals(1_000L, row.totalAfkMs());
        long later = t0 + 100_000L;
        service.reconcileCrashedSessions(later).join();
        var row2 = service.playtime(profile, player).join();
        assertEquals(row.totalActiveMs(), row2.totalActiveMs());
        assertEquals(row.totalAfkMs(), row2.totalAfkMs());
    }

    @Test
    void heartbeatAfterCloseIsNoop() {
        UUID profile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        long t0 = 6_000_000L;
        service.onJoin(profile, player, t0).join();
        service.onQuit(profile, player, t0 + 1_000L).join();
        assertFalse(service.onHeartbeat(profile, player, t0 + 2_000L, false).join());
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
