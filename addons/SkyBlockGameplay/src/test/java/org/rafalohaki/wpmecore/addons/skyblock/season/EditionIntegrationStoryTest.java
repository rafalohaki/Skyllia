package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jeden złożony test-opowieść pełnego cyklu sezonu na PRAWDZIWYM SQLite
 * (:memory: {@link SingleConnectionSqlService} + {@link SkyBlockSchemaMigrator},
 * bez MockBuka — wzorzec {@code SeasonCloseSnapshotRegressionTest}), w kolejności
 * produkcyjnej: włączone edycje ({@code editions.yml} przez
 * {@link SeasonEditionFixtures}) → {@code refreshActivation} wiąże bieżący
 * numer sezonu → zasiew 12 graczy → zamknięcie {@code --force} z uzbrojonym
 * guardem wyścigu (10-argumentowy konstruktor, warstwa 1 aktywna) i akcją
 * po COMMIT jak w produkcji → asercje: historia TOP-10, pusty rejestr roszczeń
 * (deferral offline), licznik +1, {@code stampClosed} edycji, ponowne wiązanie
 * NOWEGO sid po syncu cache (jak {@code closeSeason}: setSeason + jawne
 * refreshActivation), nagłówek publiczny rozwiązuje NAZWĘ NASTĘPNEGO okna.
 * Druga odsłona:
 * {@code swapRegistry} na kalendarz z INNĄ edycją obejmującą „teraz” →
 * {@code refreshActivation} DOPISUJE drugi wiersz dla NOWEGO sezonu
 * (dowód append-only: stare wiersze nietknięte).
 */
class EditionIntegrationStoryTest {

    private static final int SEASON = 7;
    /** „Teraz”: 2027-01-15T12:00Z — wewnątrz okna zima-2027. */
    private static final long T_NOW =
            Instant.parse("2027-01-15T12:00:00Z").toEpochMilli();
    /** Druga aktywacja MUSI mieć późniejszy znacznik (PK: season_id+activated_at). */
    private static final long T_ACT2 = T_NOW + 60_000L;
    /** Moment w NASTĘPNYM oknie (wiosna-2027) dla nagłówka publicznego. */
    private static final long T_NEXT_WINDOW =
            Instant.parse("2027-03-01T00:00:00Z").toEpochMilli();

    /** Kalendarz A: okno bieżące + następne (bez nachodzenia typu REGULAR). */
    private static final String EDITIONS_A = """
            editions:
              enabled: true
              list:
                - slug: zima-2027
                  display-name: Zima 2027
                  type: REGULAR
                  start: "2027-01-01"
                  end: "2027-01-31"
                - slug: wiosna-2027
                  display-name: Wiosna 2027
                  type: REGULAR
                  start: "2027-02-01"
                  end: "2027-04-30"
            """;

    /** Kalendarz B: INNA edycja obejmuje „teraz” (podmiana w locie). */
    private static final String EDITIONS_B = """
            editions:
              enabled: true
              list:
                - slug: edycja-b
                  display-name: Edycja B
                  type: REGULAR
                  start: "2027-01-10"
                  end: "2027-02-20"
            """;

    /** Gracze w kolejności seedowania; timestamp rośnie monotonnie. */
    private static final List<String> NAMES = List.of(
            "alice", "bob", "carol", "dave", "erin", "frank",
            "grace", "heidi", "ivan", "judy", "katya", "leo");

    /** Kolejność TOP-10 wg points DESC, updated_at ASC (remis: dave przed carol). */
    private static final List<String> TOP_TEN_ORDER = List.of(
            "alice", "bob", "dave", "carol", "erin",
            "frank", "grace", "heidi", "ivan", "judy");

    private SingleConnectionSqlService sql;
    private SqlSeasonEditionDao editionDao;
    private SeasonEditionService editions;
    private EditionRegistry registryA;
    /** Lustrzany odbicie wpme_sb_season_state — podbijane po zamknięciu jak produkcja. */
    private AtomicInteger currentSid;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        editionDao = new SqlSeasonEditionDao(sql);
        registryA = SeasonEditionFixtures.loadRegistry(EDITIONS_A);
        editions = new SeasonEditionService(editionDao, registryA);
        currentSid = new AtomicInteger(SEASON);
        editions.setSeasonIdSupplier(currentSid::get);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void seasonLifecycleFromActivationThroughForceCloseToNextWindowAndSwap()
            throws Exception {
        // (1) Edycje włączone; refreshActivation wiąże BIEŻĄCY numer sezonu.
        editions.refreshActivation(T_NOW);
        assertEquals(List.of("zima-2027"), activationSlugs(SEASON),
                "pierwszy refresh dopisuje dokładnie jeden wiersz dla S7");
        assertEquals("zima-2027", editions.currentCached(SEASON).slug(),
                "cache aktywacji podąża za wierszem");

        // (2) Zasiew 12 graczy (TOP-10 + 2 poza podium historii).
        seedTwelvePlayersWithTie();

        // (3) Zamknięcie --force; 10-arg ctor = guard wyścigu warstwa 1 uzbrojony,
        //     postCommitAction = refreshActivation jak w produkcji.
        SeasonEndService endService = new SeasonEndService(
                sql,
                currentSid::get,
                () -> Long.MAX_VALUE,
                () -> 0L,
                (player, seasonId, rank) -> CompletableFuture.completedFuture(
                        SeasonEndService.CosmeticDispatcher.DispatchResult.DEFERRED_OFFLINE),
                ZoneOffset.UTC,
                Logger.getLogger("EditionIntegrationStoryTest"),
                editionDao,
                registryA,
                () -> editions.refreshActivation(T_NOW));

        SeasonEndService.CloseOutcome outcome =
                endService.close(T_NOW, true).join();

        // (4a) Wynik zamknięcia: TOP-10 zarchiwizowane, nikt nie dostał nic
        //      teraz — wszyscy trójka z podium offline (deferral).
        assertTrue(outcome.closed(), "--force zamyka pomimo guardu czasu");
        assertEquals(SEASON, outcome.closedSeason());
        assertEquals(SEASON + 1, outcome.newSeason());
        assertEquals(10L, outcome.archivedRows(), "historia przyjmuje TOP-10");
        assertEquals(0, outcome.rewardedNow());
        assertEquals(0, outcome.alreadySettled());
        assertEquals(3, outcome.deferredOffline(),
                "rangi 1..3 offline → wszystkie odroczone");
        assertNull(outcome.refusalReason());

        // (4b) Historia TOP-10: kolejność remisów i jeden znacznik rewarded_at.
        Map<UUID, List<Long>> history = historyByRank(SEASON);
        assertEquals(10, history.size(), "historia ma dokładnie 10 wierszy (nie 12)");
        List<String> actualOrder = new ArrayList<>();
        long rewardedAtStamp = -1L;
        for (Map.Entry<UUID, List<Long>> entry : history.entrySet()) {
            actualOrder.add(name(entry.getKey()));
            assertEquals(actualOrder.size(), entry.getValue().get(0),
                    "rank_position ciągły 1..10 dla " + name(entry.getKey()));
            if (rewardedAtStamp < 0L) {
                rewardedAtStamp = entry.getValue().get(2);
            }
            assertEquals(rewardedAtStamp, entry.getValue().get(2),
                    "jedno zamknięcie = jeden rewarded_at (" + name(entry.getKey()) + ")");
        }
        assertEquals(TOP_TEN_ORDER, actualOrder,
                "kolejność wg points DESC, updated_at ASC (remis: dave przed carol)");

        // (4c) Deferral offline = ZERO zapisanych roszczeń.
        assertEquals(0L, scalar("SELECT COUNT(*) FROM wpme_sb_seasonal_claims"),
                "rejestr roszczeń kosmetycznych pusty (roszczenie bez dostawy "
                        + "spaliłoby nagrodę)");

        // (4d) Rollover: licznik +1, punkty zamkniętego sezonu wytarte.
        assertEquals((long) SEASON + 1L,
                scalar("SELECT current_season FROM wpme_sb_season_state WHERE id = 1"),
                "licznik sezonu podbity 7 → 8");
        assertEquals(0L, scalar(
                "SELECT COUNT(*) FROM wpme_sb_season_points WHERE season_id = ?", SEASON),
                "punkty zamkniętego sezonu wyczyszczone w transakcji rolloveru");

        // (4e) Edycje: stampClosed domknął wiersze S7 w tej SAMEJ transakcji.
        //      Wewnętrzny postCommit (refreshActivation) działa jeszcze przy
        //      STARYM sid z cache dostawcy — produkcja (SKYBLOCK-1-9) aktualizuje
        //      cache koordynatora dopiero PO zwrocie close(), więc NOWY sezon
        //      nie może tu jeszcze dostać wiersza.
        assertEquals(0L, scalar(
                "SELECT COUNT(*) FROM wpme_sb_season_editions"
                        + " WHERE season_id = ? AND closed_at IS NULL", SEASON),
                "wszystkie wiersze S7 mają closed_at (stampClosed)");
        assertEquals(List.of("zima-2027"), activationSlugs(SEASON),
                "stampClosed nie dopisuje ani nie usuwa wierszy");
        assertTrue(activationSlugs(SEASON + 1).isEmpty(),
                "postCommit przy starym cache sid jeszcze NIE wiąże S8");

        // Produkcja (SkyBlockGameplay.closeSeason): po potwierdzonym rolloverze
        // setSeason wpisuje NOWY sid do cache koordynatora (= nasz supplier),
        // a dopiero POTEM jawne refreshActivation wiąże nowy numer z aktywną
        // edycją przed broadcastem (idempotentne append-only).
        currentSid.set(outcome.newSeason());
        editions.refreshActivation(T_NOW);

        assertEquals(List.of("zima-2027"), activationSlugs(SEASON + 1),
                "refreshActivation po syncu cache wiąże NOWY sezon S8 z aktywną edycją");

        // (4f) Nagłówek publiczny: dalej „Zima 2027”, a w następnym oknie —
        //      nazwa NASTĘPNEJ edycji (kalendarz łańcuchuje się po zamknięciu).
        assertEquals("Zima 2027", editions.publicHeaderLabel(T_NOW));
        assertEquals("Wiosna 2027", editions.publicHeaderLabel(T_NEXT_WINDOW),
                "nagłówek rozwiązuje nazwę NASTĘPNEGO okna");

        // (5) DRUGA ODSŁONA: swapRegistry na kalendarz z inną edycją obejmującą
        //     „teraz”; refreshActivation dopisuje NOWE wiersze — append-only.
        editions.swapRegistry(SeasonEditionFixtures.loadRegistry(EDITIONS_B));
        editions.refreshActivation(T_ACT2);

        assertEquals(List.of("zima-2027", "edycja-b"), activationSlugs(SEASON),
                "S7: stary wiersz nietknięty na czele, nowy dopisany na ogonie");
        assertEquals(List.of("zima-2027", "edycja-b"), activationSlugs(SEASON + 1),
                "NOWY sezon S8: drugi wiersz DOPISANY (append-only), nie nadpisany");
        assertEquals("Edycja B", editions.publicHeaderLabel(T_NOW),
                "po swapie nagłówek rozwiązuje nową edycję obejmującą „teraz”");
        assertEquals("edycja-b", editions.currentCached(SEASON + 1).slug(),
                "cache wskazuje najnowszą aktywację nowego sezonu");
    }

    // ------------------------------------------------------------------
    // Narzędzia (wzorce z SeasonCloseSnapshotRegressionTest / Fixtures).
    // ------------------------------------------------------------------

    private static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(
                ("edition-integration-story:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static String name(UUID player) {
        return NAMES.stream().filter(n -> uuid(n).equals(player)).findFirst().orElseThrow();
    }

    private Connection connection() {
        return (Connection) sql.dataSource();
    }

    private void seedTwelvePlayersWithTie() throws SQLException {
        seedPoints("alice", 1000L, T_NOW + 100); // rank 1
        seedPoints("bob", 900L, T_NOW + 101);    // rank 2
        seedPoints("carol", 800L, T_NOW + 103);  // remis — późniejszy timestamp → rank 4
        seedPoints("dave", 800L, T_NOW + 102);   // remis — wcześniejszy timestamp → rank 3
        seedPoints("erin", 700L, T_NOW + 104);   // rank 5
        seedPoints("frank", 600L, T_NOW + 105);  // rank 6
        seedPoints("grace", 500L, T_NOW + 106);  // rank 7
        seedPoints("heidi", 400L, T_NOW + 107);  // rank 8
        seedPoints("ivan", 300L, T_NOW + 108);   // rank 9
        seedPoints("judy", 200L, T_NOW + 109);   // rank 10
        seedPoints("katya", 150L, T_NOW + 110);  // poza TOP-10
        seedPoints("leo", 100L, T_NOW + 111);    // poza TOP-10
    }

    private void seedPoints(String name, long points, long updatedAt) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "INSERT INTO wpme_sb_season_points (season_id, player_uuid, points,"
                        + " quests_done, updated_at) VALUES (?, ?, ?, 0, ?)")) {
            ps.setInt(1, SEASON);
            ps.setString(2, uuid(name).toString());
            ps.setLong(3, points);
            ps.setLong(4, updatedAt);
            assertEquals(1, ps.executeUpdate(), "seed " + name);
        }
    }

    /** Historia sezonu w kolejności rank_position → wiersz [rank, points, rewarded_at]. */
    private Map<UUID, List<Long>> historyByRank(int seasonId) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT player_uuid, rank_position, points, rewarded_at"
                        + " FROM wpme_sb_season_history WHERE season_id = ?"
                        + " ORDER BY rank_position")) {
            ps.setInt(1, seasonId);
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

    /** Slugi aktywacji sezonu w kolejności activated_at (starsze → nowsze). */
    private List<String> activationSlugs(int seasonId) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(
                "SELECT slug FROM wpme_sb_season_editions WHERE season_id = ?"
                        + " ORDER BY activated_at")) {
            ps.setInt(1, seasonId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> slugs = new ArrayList<>();
                while (rs.next()) {
                    slugs.add(rs.getString(1));
                }
                return slugs;
            }
        }
    }

    private long scalar(String query, Object... params) throws SQLException {
        try (PreparedStatement ps = connection().prepareStatement(query)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
