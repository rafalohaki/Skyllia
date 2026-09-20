package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.rafalohaki.wpmecore.api.service.SqlService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kontrakt C4 (runda C): dwuwarstwowy zabezpieczenie przed podwójnym
 * zamknięciem sezonu (CLI --confirm/--force vs GUI confirm).
 *
 * <p>Warstwa 1 (pre-check): świeży SELECT z {@code wpme_sb_season_state};
 * rozjazd z dostawcą sezonu → odmowa BEZ ŻADNEGO zapisu i bez dispatchu,
 * komunikat dokładnie „Sezon został już zamknięty (wyścig z innym
 * operatorem).”.
 *
 * <p>Warstwa 2 (twarda, wewnątrz transakcji rollovera): guarded UPDATE
 * {@code … WHERE id = 1 AND current_season = ?} (expectedOld = zamknięty
 * sezon); rowcount 0 → {@code SQLException("concurrent season close
 * detected")} → pełny ROLLBACK transakcji.
 *
 * <p>Szczera granica rollbacka (zweryfikowana tu asercjami): w transakcji są
 * seed/guard/wipe/stampClosed/licznik — to się cofa. Snapshot TOP-10
 * (INSERT OR IGNORE) oraz dystrybucja nagród kosmetycznych lecą PRZED
 * transakcją poza nią — zostają; ich idempotencja (INSERT OR IGNORE +
 * deterministyczny operationId outboxa) czyni to bezpiecznym przy ponownej
 * próbie zamknięcia.
 *
 * <p>Harness: prawdziwe :memory: SQLite ({@link SingleConnectionSqlService} +
 * {@link SkyBlockSchemaMigrator}), jak {@code SeasonCloseSnapshotRegressionTest};
 * MockBukkit zbędny. Wyścig symulujemy dwoma sposobami: naturalnie
 * (nieaktualny licznik w bazie vs dostawca — legacy ctor pomija warstwę 1,
 * więc guard łapie to w transakcji) oraz przez Proxy na Połączeniu wymuszające
 * rowcount 0 dla guarded UPDATE mimo zgodnych wartości (prawdziwy wyścig
 * między pre-checkiem a transakcją). Typ wrappera wyjątku celowo NIE jest
 * piny — asercja chodzi po łańcuchu przyczyn do SQLException z wiadomością.
 */
class CloseRaceGuardTest {

    private static final int SEASON = 7;
    private static final long NOW = 1_800_000_000_000L;
    private static final String RACE_REFUSAL =
            "Sezon został już zamknięty (wyścig z innym operatorem).";
    private static final String GUARD_SQL_PREFIX = "UPDATE wpme_sb_season_state";
    private static final String CONCURRENT_MESSAGE = "concurrent season close detected";

    private SingleConnectionSqlService sql;
    private final AtomicInteger dispatched = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        dispatched.set(0);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    /** Rozszerzony ctor (10-arg) — włącza warstwę 1; edycje null (poza zakresem C4). */
    private SeasonEndService extended(SqlService svc) {
        return new SeasonEndService(svc,
                () -> SEASON,
                () -> Long.MAX_VALUE,
                () -> 0L,
                dispatcher(),
                ZoneOffset.UTC,
                Logger.getLogger("CloseRaceGuardTest"),
                null, null, null);
    }

    /** Legacy ctor (7-arg) — warstwa 1 POMINIĘTA, twardy guard w tx zostaje. */
    private SeasonEndService legacy(SqlService svc) {
        return new SeasonEndService(svc,
                () -> SEASON,
                () -> Long.MAX_VALUE,
                () -> 0L,
                dispatcher(),
                ZoneOffset.UTC,
                Logger.getLogger("CloseRaceGuardTest"));
    }

    private SeasonEndService.CosmeticDispatcher dispatcher() {
        return (player, seasonId, rank) -> {
            dispatched.incrementAndGet();
            return CompletableFuture.completedFuture(
                    SeasonEndService.CosmeticDispatcher.DispatchResult.DELIVERED);
        };
    }

    /**
     * SqlService delegujący do prawdziwego :memory: SQLite, ale w
     * withConnection owija Connection Proxy-em, którego PreparedStatement
     * zgłasza rowcount 0 dla guarded UPDATE (symulacja: inny operator
     * zdążył podbić licznik między pre-checkiem a naszą transakcją).
     */
    private static final class GuardTrippingSqlService implements SqlService {
        private final SingleConnectionSqlService delegate;

        GuardTrippingSqlService(SingleConnectionSqlService delegate) {
            this.delegate = delegate;
        }

        @Override public boolean isEnabled() { return delegate.isEnabled(); }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public boolean isHealthy() { return delegate.isHealthy(); }
        @Override public Map<String, Object> poolStats() { return delegate.poolStats(); }
        @Override public void shutdown() { /* właścicielem połączenia jest delegate */ }
        @Override public Object dataSource() { return delegate.dataSource(); }
        @Override public CompletableFuture<Integer> update(String query, Object... params) {
            return delegate.update(query, params);
        }
        @Override public <T> CompletableFuture<List<T>> query(
                String query, RowMapper<T> mapper, Object... params) {
            return delegate.query(query, mapper, params);
        }
        @Override public <T> CompletableFuture<T> withConnection(SqlAction<T> action) {
            return delegate.withConnection(connection -> action.execute(guardTrips(connection)));
        }

        private static Connection guardTrips(Connection real) {
            ClassLoader loader = CloseRaceGuardTest.class.getClassLoader();
            return (Connection) Proxy.newProxyInstance(loader,
                    new Class<?>[] { Connection.class },
                    (Object proxy, Method method, Object[] args) -> {
                        if ("prepareStatement".equals(method.getName())
                                && args != null && args.length > 0
                                && String.valueOf(args[0]).startsWith(GUARD_SQL_PREFIX)) {
                            String sqlText = String.valueOf(args[0]);
                            PreparedStatement realStatement =
                                    (PreparedStatement) method.invoke(real, args);
                            return Proxy.newProxyInstance(loader,
                                    new Class<?>[] { PreparedStatement.class },
                                    (Object p2, Method m2, Object[] a2) -> {
                                        if ("executeUpdate".equals(m2.getName())
                                                && m2.getParameterCount() == 0) {
                                            // wyścig: guarded UPDATE nie trafia żadnego wiersza
                                            return 0;
                                        }
                                        return unchecked(m2, realStatement, a2);
                                    });
                        }
                        return unchecked(method, real, args);
                    });
        }

        private static Object unchecked(Method method, Object target, Object[] args)
                throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    // ------------------------------------------------------------------
    // JDBC helpery (bezpośrednio na pojedynczym połączeniu, jak w regresji snapshotu)
    // ------------------------------------------------------------------

    private void seedState(int season) throws SQLException {
        try (PreparedStatement ps = ((Connection) sql.dataSource()).prepareStatement(
                "INSERT INTO wpme_sb_season_state (id, current_season, updated_at)"
                        + " VALUES (1, ?, ?)")) {
            ps.setInt(1, season);
            ps.setLong(2, NOW);
            assertEquals(1, ps.executeUpdate(), "seed wpme_sb_season_state");
        }
    }

    private int readState() throws SQLException {
        try (PreparedStatement ps = ((Connection) sql.dataSource()).prepareStatement(
                "SELECT current_season FROM wpme_sb_season_state WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "wiersz stanu istnieje");
            return rs.getInt(1);
        }
    }

    private void seedPoints(String name, long points) throws SQLException {
        try (PreparedStatement ps = ((Connection) sql.dataSource()).prepareStatement(
                "INSERT INTO wpme_sb_season_points"
                        + " (season_id, player_uuid, points, quests_done, updated_at)"
                        + " VALUES (?, ?, ?, 0, ?)")) {
            ps.setInt(1, SEASON);
            ps.setString(2, uuid(name).toString());
            ps.setLong(3, points);
            ps.setLong(4, NOW);
            assertEquals(1, ps.executeUpdate(), "seed punkty " + name);
        }
    }

    private int countPoints() throws SQLException {
        try (PreparedStatement ps = ((Connection) sql.dataSource()).prepareStatement(
                "SELECT COUNT(*) FROM wpme_sb_season_points WHERE season_id = " + SEASON);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private int countHistory() throws SQLException {
        try (PreparedStatement ps = ((Connection) sql.dataSource()).prepareStatement(
                "SELECT COUNT(*) FROM wpme_sb_season_history WHERE season_id = " + SEASON);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(("close-race-guard:" + name)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Idzie po łańcuchu przyczyn do SQLException o dokładnej wiadomości. */
    private static boolean chainContains(Throwable failure, String message) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException && message.equals(current.getMessage())) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Warstwa 1: pre-check
    // ------------------------------------------------------------------

    @Test
    @DisplayName("pre-check: licznik w DB != dostawca → odmowa, ZERO zapisów, ZERO dispatchy")
    void staleSupplierRefusedByPreCheckWithoutAnyWrite() throws Exception {
        seedState(8); // inny operator zamknął już sezon 7 → baza stoi na 8
        seedPoints("alice", 1000L);
        seedPoints("bob", 900L);

        var outcome = extended(sql).close(NOW, true).join();

        assertTrue(outcome.refused(), "warstwa 1 odmawia");
        assertEquals(RACE_REFUSAL, outcome.refusalReason(),
                "komunikat wyścigu ma być dokładny (kontrakt C4)");
        assertEquals(SEASON, outcome.closedSeason(), "odmowa nie zmienia numeracji");
        assertEquals(SEASON, outcome.newSeason());
        assertEquals(0L, outcome.archivedRows());
        assertEquals(0, outcome.rewardedNow());
        assertEquals(0, outcome.alreadySettled());
        assertEquals(0, outcome.deferredOffline());

        assertEquals(0, dispatched.get(), "przy odmowie nic nie jest dispatchowane");
        assertEquals(8, readState(), "licznik nienaruszony");
        assertEquals(2, countPoints(), "punkty nienaruszone (brak wipe)");
        assertEquals(0, countHistory(), "historia pusta (brak snapshotu)");
    }

    @Test
    @DisplayName("świeża baza (brak wiersza stanu): pre-check przechodzi, rollover 7→8")
    void freshDatabasePassesPreCheckAndClosesNormally() throws Exception {
        var outcome = extended(sql).close(NOW, true).join();

        assertTrue(!outcome.refused(), "zamknięcie udane");
        assertNull(outcome.refusalReason());
        assertEquals(SEASON, outcome.closedSeason());
        assertEquals(SEASON + 1, outcome.newSeason());
        assertEquals(SEASON + 1, readState(),
                "guarded UPDATE przeszedł: seed wpisał 7, bump na 8");
    }

    // ------------------------------------------------------------------
    // Warstwa 2: guarded UPDATE → rowcount 0 → SQLException + rollback tx
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rowcount 0 guarded UPDATE → SQLException('concurrent…'), tx cofnięta")
    void trippedGuardSurfacesSqlExceptionAndRollsBackTransaction() throws Exception {
        seedState(SEASON); // pre-check przechodzi: baza zgadza się z dostawcą
        seedPoints("alice", 1000L);
        seedPoints("bob", 900L);

        SeasonEndService service = extended(new GuardTrippingSqlService(sql));

        CompletionException failure =
                assertThrows(CompletionException.class,
                        () -> service.close(NOW, true).join(),
                        "wyścig w transakcji ma wysadzić future wyjątkiem");

        assertTrue(chainContains(failure, CONCURRENT_MESSAGE),
                "łańcuch przyczyn zawiera SQLException('" + CONCURRENT_MESSAGE
                        + "'); było: " + failure);

        // ROLLBACK transakcji: licznik i punkty nietknięte…
        assertEquals(SEASON, readState(), "bump licznika cofnięty przez rollback");
        assertEquals(2, countPoints(), "wipe punktów cofnięty przez rollback");

        // …ale snapshot i nagrody leciały PRZED transakcją (dokumentacja C4:
        // idempotencja INSERT OR IGNORE + operationId czyni to bezpiecznym).
        assertEquals(2, dispatched.get(),
                "dispatch rang 1..3 odbywa się przed rolloverem (kolejność etapów)");
        assertEquals(2, countHistory(), "snapshot (INSERT OR IGNORE) przetrwa poza tx");
    }

    @Test
    @DisplayName("legacy ctor: warstwa 1 pominięta, twardy guard w tx NATURALNIE łapie rozjazd")
    void legacyConstructorSkipsSoftCheckButHardGuardStillFires() throws Exception {
        seedState(8); // rozjazd: dostawca mówi 7, baza stoi na 8
        seedPoints("alice", 1000L);

        CompletionException failure =
                assertThrows(CompletionException.class,
                        () -> legacy(sql).close(NOW, true).join(),
                        "legacy bez warstwy 1 dochodzi do tx, gdzie guard łapie rozjazd");

        assertTrue(chainContains(failure, CONCURRENT_MESSAGE),
                "twardy guard aktywny także w legacy: " + failure);

        // Guard złapany naturalnie: UPDATE … WHERE current_season=7 nie trafiło
        // wiersza z wartością 8 → rowcount 0 → pełny rollback.
        assertEquals(8, readState(), "licznik nietknięty");
        assertEquals(1, countPoints(), "punkty niewymazane");
        assertEquals(1, dispatched.get(), "dispatch przed tx miał miejsce (idempotentny)");
        assertEquals(1, countHistory(), "snapshot poza tx przetrwał");
    }

    @Test
    @DisplayName("zgodne wartości bez proxy: guarded UPDATE przechodzi (guard nie blokuje normalnego flow)")
    void matchingValuesPassTheGuard() throws Exception {
        seedState(SEASON);
        seedPoints("alice", 500L);

        var outcome = extended(sql).close(NOW, true).join();

        assertTrue(!outcome.refused());
        assertEquals(SEASON + 1, outcome.newSeason());
        assertEquals(SEASON + 1, readState());
        assertEquals(0, countPoints(), "normalne zamknięcie czyści punkty sezonu");
        assertNotNull(outcome);
    }
}
