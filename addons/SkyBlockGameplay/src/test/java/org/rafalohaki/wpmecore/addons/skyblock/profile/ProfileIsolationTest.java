package org.rafalohaki.wpmecore.addons.skyblock.profile;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.IslandProfileDao;
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

class ProfileIsolationTest {

    private SingleConnectionSqlService sql;
    private ProfileAccountDao accountDao;
    private ProfileAccountService service;
    private IslandProfileDao profileDao;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        accountDao = new ProfileAccountDao(sql);
        service = new ProfileAccountService(accountDao, 100L);
        profileDao = new IslandProfileDao(sql);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void samePlayerAcrossTwoProfilesHasIsolatedBalances() {
        UUID player = UUID.randomUUID();
        UUID profileA = UUID.randomUUID();
        UUID profileB = UUID.randomUUID();
        UUID ownerA = player;
        UUID ownerB = UUID.randomUUID();

        assertTrue(profileDao.tryCreateProfile(profileA, ownerA, "CLASSIC").join());
        assertTrue(profileDao.tryCreateProfile(profileB, ownerB, "CLASSIC").join());
        service.deposit(profileA, player, 500L, "tx:a1", "test").join();
        service.deposit(profileB, player, 300L, "tx:b1", "test").join();

        assertEquals(600L, service.balance(profileA, player).join());
        assertEquals(400L, service.balance(profileB, player).join());

        service.withdraw(profileA, player, 200L, "tx:a2", "test").join();
        assertEquals(400L, service.balance(profileA, player).join());
        assertEquals(400L, service.balance(profileB, player).join());
    }

    @Test
    void profileAccountNotSharedAcrossProfiles() {
        UUID player = UUID.randomUUID();
        UUID profile1 = UUID.randomUUID();
        UUID profile2 = UUID.randomUUID();

        service.ensure(profile1, player).join();
        service.ensure(profile2, player).join();

        service.deposit(profile1, player, 1000L, "tx:iso1", "test").join();

        assertEquals(1100L, service.balance(profile1, player).join());
        assertEquals(100L, service.balance(profile2, player).join());
    }

    @Test
    void transactionIdempotencyPreventsDoubleCredit() {
        UUID profile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        service.ensure(profile, player).join();
        var r1 = service.deposit(profile, player, 50L, "tx:idem", "test").join();
        var r2 = service.deposit(profile, player, 50L, "tx:idem", "test").join();
        assertTrue(r1.applied());
        assertFalse(r2.applied());
        assertEquals(150L, service.balance(profile, player).join());
    }

    @Test
    void insufficientWithdrawalDoesNotMutate() {
        UUID profile = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        service.ensure(profile, player).join();
        var r = service.withdraw(profile, player, 200L, "tx:big", "test").join();
        assertTrue(r.insufficient());
        assertFalse(r.applied());
        assertEquals(100L, service.balance(profile, player).join());
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
