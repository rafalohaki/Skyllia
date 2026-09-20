package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regresja labu 2026-08-25: produkcyjny SQL snapshotu TOP-10 używał
 * {@code INSERT INTO … SELECT … ON CONFLICT (…) DO NOTHING}, a SQLite po
 * FROM (podzapytanie) parsuje „ON” jako operator złączenia — udokumentowana
 * niejednoznaczność UPSERT-a za SELECT-em. Błąd składni leciał w czasie
 * prepare, całe zamknięcie sezonu się wywalało, a żaden test tego nie łapał,
 * bo nikt nie odpalił {@code snapshotTop10} na prawdziwym SQLite.
 *
 * <p>Ten test uruchamia PRAWDZIWĄ, prywatną metodę {@code snapshotTop10}
 * (refleksja — celowo bez zmiany widoczności w produkcji) na :memory: SQLite
 * z prawdziwymi migracjami ({@link SkyBlockSchemaMigrator}). Na STARYM SQL-u
 * zawodzi natychmiast: {@code method.invoke(...).join()} rzuca
 * CompletionException opakowujące SQLiteException („near \"ON\": syntax
 * error”), zanim cokolwiek trafi do historii — żadna asercja poniżej nie ma
 * szansy się wykonać.
 */
class SeasonCloseSnapshotRegressionTest {

    private static final int SEASON = 7;
    private static final long NOW = 1_800_000_000_000L;

    /** Gracze w kolejności seedowania; timestamp rośnie monotonnie. */
    private static final List<String> NAMES = List.of(
            "alice", "bob", "carol", "dave", "erin", "frank",
            "grace", "heidi", "ivan", "judy", "katya", "leo");

    private SingleConnectionSqlService sql;
    private SeasonEndService service;

    @BeforeEach
    void setUp() throws Exception {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        service = new SeasonEndService(
                sql,
                () -> SEASON,
                () -> Long.MAX_VALUE,
                () -> 0L,
                (player, seasonId, rank) -> {
                    throw new AssertionError("snapshot-only test nie może dispatchować nagród");
                },
                ZoneOffset.UTC,
                Logger.getLogger("SeasonCloseSnapshotRegressionTest"));
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    private static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(
                ("season-close-regression:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static String name(UUID player) {
        return NAMES.stream().filter(n -> uuid(n).equals(player)).findFirst().orElseThrow();
    }

    /**
     * Refleksyjne wywołanie prywatnego {@code snapshotTop10(int)} → liczba
     * dopisanych wierszy historii.
     */
    private long snapshotTop10() throws Exception {
        Method method = SeasonEndService.class.getDeclaredMethod("snapshotTop10", int.class);
        method.setAccessible(true);
        CompletableFuture<?> future = (CompletableFuture<?>) method.invoke(service, SEASON);
        return (Long) future.join();
    }

    private void seedPoints(String name, long points, long updatedAt) throws SQLException {
        try (PreparedStatement ps = ((Connection) sql.dataSource()).prepareStatement(
                "INSERT INTO wpme_sb_season_points (season_id, player_uuid, points, quests_done, updated_at)"
                        + " VALUES (?, ?, ?, 0, ?)")) {
            ps.setInt(1, SEASON);
            ps.setString(2, uuid(name).toString());
            ps.setLong(3, points);
            ps.setLong(4, updatedAt);
            assertEquals(1, ps.executeUpdate(), "seed " + name);
        }
    }

    /**
     * 12 graczy; remisy punktowe rozstrzyga {@code ORDER BY points DESC,
     * updated_at ASC} — dave (t=102) wygrywa z carol (t=103) przy tych samych
     * 800 pkt. katya i leo zostają poza TOP-10.
     */
    private void seedTwelvePlayersWithTie() throws SQLException {
        seedPoints("alice", 1000L, NOW + 100); // rank 1
        seedPoints("bob",   900L,  NOW + 101); // rank 2
        seedPoints("carol", 800L,  NOW + 103); // remis — późniejszy timestamp → rank 4
        seedPoints("dave",  800L,  NOW + 102); // remis — wcześniejszy timestamp → rank 3
        seedPoints("erin",  700L,  NOW + 104); // rank 5
        seedPoints("frank", 600L,  NOW + 105); // rank 6
        seedPoints("grace", 500L,  NOW + 106); // rank 7
        seedPoints("heidi", 400L,  NOW + 107); // rank 8
        seedPoints("ivan",  300L,  NOW + 108); // rank 9
        seedPoints("judy",  200L,  NOW + 109); // rank 10
        seedPoints("katya", 150L,  NOW + 110); // poza TOP-10
        seedPoints("leo",   100L,  NOW + 111); // poza TOP-10
    }

    /** Historia sezonu w kolejności rank_position → wiersz [rank, points, rewarded_at]. */
    private Map<UUID, List<Long>> historyByRank() throws SQLException {
        try (PreparedStatement ps = ((Connection) sql.dataSource()).prepareStatement(
                        "SELECT player_uuid, rank_position, points, rewarded_at"
                                + " FROM wpme_sb_season_history WHERE season_id = ?"
                                + " ORDER BY rank_position")) {
            ps.setInt(1, SEASON);
            try (ResultSet rs = ps.executeQuery()) {
                Map<UUID, List<Long>> rows = new LinkedHashMap<>();
                while (rs.next()) {
                    rows.put(UUID.fromString(rs.getString(1)),
                            List.of(rs.getLong(2), rs.getLong(3), rs.getLong(4)));
                }
                return rows;
            }
        }
    }

    @Test
    void archivesExactlyTopTenWithCorrectRanksAndTieBreak() throws Exception {
        seedTwelvePlayersWithTie();

        assertEquals(10L, snapshotTop10(), "pierwsze zamknięcie dopisuje dokładnie TOP-10");

        List<String> expectedOrder = List.of(
                "alice", "bob", "dave", "carol", "erin",
                "frank", "grace", "heidi", "ivan", "judy");

        Map<UUID, List<Long>> history = historyByRank();
        assertEquals(10, history.size(), "historia ma dokładnie 10 wierszy (nie 12)");

        List<String> actualOrder = new ArrayList<>();
        for (Map.Entry<UUID, List<Long>> entry : history.entrySet()) {
            actualOrder.add(name(entry.getKey()));
            assertEquals(actualOrder.size(), entry.getValue().get(0),
                    "rank_position ciągły 1..10 dla " + name(entry.getKey()));
            assertEquals(expectedOrder.get(actualOrder.size() - 1), name(entry.getKey()),
                    "kolejność wg points DESC, updated_at ASC (remis: dave przed carol)");
        }
        assertEquals(expectedOrder, actualOrder);
    }

    @Test
    void frozenPointsMatchSeededValuesAndRewardedAtIsStamped() throws Exception {
        seedTwelvePlayersWithTie();

        assertEquals(10L, snapshotTop10());

        List<Long> alice = historyByRank().get(uuid("alice"));
        assertEquals(1000L, alice.get(1), "punkty zamrożone 1:1 z tabeli punktów");
        assertTrue(alice.get(2) > 0, "rewarded_at zapieczętowany");
        long stamp = alice.get(2);
        for (Map.Entry<UUID, List<Long>> entry : historyByRank().entrySet()) {
            assertEquals(stamp, entry.getValue().get(2),
                    "wszystkie wiersze z tego samego zamknięcia mają jeden rewarded_at ("
                            + name(entry.getKey()) + ")");
        }
    }

    @Test
    void secondInvocationAddsZeroRowsAndKeepsHistoryFrozen() throws Exception {
        seedTwelvePlayersWithTie();
        assertEquals(10L, snapshotTop10());
        Map<UUID, List<Long>> firstPass = historyByRank();

        assertEquals(0L, snapshotTop10(),
                "INSERT OR IGNORE: ponowne zamknięcie dopisuje zero wierszy");
        assertEquals(firstPass, historyByRank(),
                "historia niezmieniona — brak nadpisania ranków i rewarded_at");
    }
}
