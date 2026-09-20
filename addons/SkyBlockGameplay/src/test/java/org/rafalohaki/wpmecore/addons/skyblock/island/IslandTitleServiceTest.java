package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Magazyn tytułu wyspy na SQLite w pamięci: nadanie, odczyt z cache,
 * idempotencja po {@code source_id} i progi priorytetu, które nie pozwalają
 * tańszemu tytułowi zjeść droższego.
 *
 * <p>Pusty tytuł jest tu pełnoprawnym stanem: wyspa bez wiersza (i taka,
 * której wiersza jeszcze nie wczytaliśmy) nie może nigdzie pokazać „null”.
 */
class IslandTitleServiceTest {

    private SingleConnectionSqlService sql;
    private IslandTitleService service;
    private final UUID island = UUID.randomUUID();

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        service = new IslandTitleService(new IslandTitleDao.Sql(sql));
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void grantedTitleSurvivesRestartAndReadsBackFromTheStore() {
        assertEquals(IslandTitleService.GrantStatus.GRANTED,
                service.grant(island, "Rybacka", "prestige:1").join());

        assertEquals(Optional.of("Rybacka"), service.titleOf(island).join());
        assertEquals("Rybacka", storedTitle());
        assertEquals("prestige:1", storedSourceId());

        // „Restart”: świeży serwis na tej samej bazie widzi tytuł wyspy.
        IslandTitleService restarted = new IslandTitleService(new IslandTitleDao.Sql(sql));
        assertEquals(Optional.of("Rybacka"), restarted.titleOf(island).join());
    }

    @Test
    void regrantFromTheSameSourceNeverOverwrites() {
        service.grant(island, "Rybacka", "prestige:1").join();

        assertEquals(IslandTitleService.GrantStatus.ALREADY_GRANTED,
                service.grant(island, "Rybacka", "prestige:1").join());

        assertEquals("Rybacka", storedTitle(), "wiersz nie może się ruszyć");
    }

    @Test
    void betterPlaceInSeasonBeatsPrestigeAndPrestigeCannotTakeItBack() {
        service.grant(island, "Rybacka", "prestige:1").join();

        // Sezon (miejsce 1) wygrywa z prestiżem — sezonu nie da się powtórzyć.
        assertEquals(IslandTitleService.GrantStatus.GRANTED,
                service.grant(island, "Mistrz Sezonu", "season:7:rank1").join());
        assertEquals("Mistrz Sezonu", storedTitle());

        // Kolejny poziom prestiżu nie zabiera tytułu sezonowego.
        assertEquals(IslandTitleService.GrantStatus.LOWER_PRIORITY,
                service.grant(island, "Kupiecka", "prestige:2").join());
        assertEquals("Mistrz Sezonu", storedTitle());
        assertEquals("season:7:rank1", storedSourceId());
    }

    @Test
    void earlierPlaceBeatsLaterPlaceInTheSameSeason() {
        service.grant(island, "Wyspiarz", "season:7:rank50").join();

        assertEquals(IslandTitleService.GrantStatus.GRANTED,
                service.grant(island, "Wicemistrz", "season:7:rank10").join());
        assertEquals(IslandTitleService.GrantStatus.LOWER_PRIORITY,
                service.grant(island, "Wyspiarz", "season:7:rank50").join(),
                "gorsze miejsce nie odbiera tytułu lepszemu");
        assertEquals("Wicemistrz", storedTitle());
    }

    @Test
    void oneTimeLotusSinkOutranksEveryRepeatableTitle() {
        service.grant(island, "Mistrz Sezonu", "season:7:rank1").join();

        assertEquals(IslandTitleService.GrantStatus.GRANTED,
                service.grant(island, "<gold>Władca Lotosu</gold>", "lotus:master").join());

        assertEquals("<gold>Władca Lotosu</gold>", storedTitle());
        assertEquals(IslandTitleService.GrantStatus.LOWER_PRIORITY,
                service.grant(island, "Mistrz następnego sezonu", "season:8:rank1").join(),
                "tytuł za jednorazowy zlew nie schodzi za nagrodę powtarzalną");
    }

    @Test
    void revokeOnlyRemovesItsOwnSource() {
        service.grant(island, "Rybacka", "prestige:1").join();

        assertFalse(service.revoke(island, "lotus:master").join(), "obce źródło nie zdejmuje tytułu");
        assertEquals("Rybacka", storedTitle());

        assertTrue(service.revoke(island, "prestige:1").join());
        assertNull(storedTitle(), "po zdjęciu tytułu wyspa nie ma wiersza");
        assertTrue(service.cachedTitle(island).isEmpty());
        assertTrue(service.titleOf(island).join().isEmpty());
    }

    @Test
    void forgettingADeletedIslandDropsTheRowAndTheSnapshot() {
        service.grant(island, "Władca Lotosu", "lotus:master").join();
        assertEquals("Władca Lotosu", service.cachedTitle(island).orElseThrow());

        service.forgetIsland(island).join();

        assertNull(storedTitle(), "wyspa skasowana nie może zostawić po sobie wiersza tytułu");
        assertTrue(service.cachedTitle(island).isEmpty(), "migawka też musi zniknąć");
        assertEquals("", service.placeholderTitle(island));
        assertFalse(service.hasTitleFrom(island, "lotus:master").join(),
                "odtworzona wyspa nie może dziedziczyć zlewu Lotosów po poprzedniej");
        // „Restart”: nowy serwis na tej samej bazie też nie widzi tytułu.
        assertTrue(new IslandTitleService(new IslandTitleDao.Sql(sql)).titleOf(island).join().isEmpty());
    }

    @Test
    void placeholderIsEmptyUntilTheTitleIsKnownAndNeverNull() {
        // Bez wiersza i bez wczytanej migawki: pusty napis, nigdy „null”.
        assertEquals("", service.placeholderTitle(island));
        assertEquals("", service.placeholderTitle(null));
        assertTrue(service.cachedTitle(island).isEmpty(),
                "placeholder nie może udawać, że zna tytuł, którego nie wczytał");

        service.grant(island, "Rybacka", "prestige:1").join();
        assertEquals("Rybacka", service.placeholderTitle(island));

        // Świeży serwis: pierwsze wezwanie placeholdera nie blokuje i nie kłamie,
        // ale w tle dociąga migawkę — drugie już zna tytuł.
        IslandTitleService cold = new IslandTitleService(new IslandTitleDao.Sql(sql));
        assertEquals("", cold.placeholderTitle(island));
        await(() -> !cold.cachedTitle(island).isEmpty());
        assertEquals("Rybacka", cold.placeholderTitle(island));
    }

    @Test
    void unknownSourceNeverOverwritesAKnownTitle() {
        service.grant(island, "Rybacka", "prestige:1").join();

        assertEquals(IslandTitleService.GrantStatus.LOWER_PRIORITY,
                service.grant(island, "Z Tej Samej Półki", "prestige:1-extra").join());
        assertEquals("Rybacka", storedTitle());
    }

    /** Priorytety są jedną funkcją bez bazy — kontrakt progów widoczny wprost. */
    @Test
    void prioritiesOrderLotusAboveSeasonAbovePrestige() {
        assertTrue(IslandTitleService.priorityOf("lotus:master")
                > IslandTitleService.priorityOf("season:7:rank1"));
        assertTrue(IslandTitleService.priorityOf("season:7:rank1")
                > IslandTitleService.priorityOf("season:7:rank50"));
        assertTrue(IslandTitleService.priorityOf("season:7:rank50")
                > IslandTitleService.priorityOf("prestige:10"));
        assertTrue(IslandTitleService.priorityOf("prestige:10")
                > IslandTitleService.priorityOf("prestige:1"));
        assertEquals(IslandTitleService.UNKNOWN_TIER, IslandTitleService.priorityOf("prestige:"));
        assertEquals(IslandTitleService.UNKNOWN_TIER, IslandTitleService.priorityOf("prestige:x"));
        assertEquals(IslandTitleService.UNKNOWN_TIER, IslandTitleService.priorityOf("season:7:rank"));
    }

    /**
     * Zapis warunkowy może przegrać z kimś, kto zdążył zmienić wiersz między
     * odczytem a zapisem. Wtedy decyzja jest powtarzana na świeżym wierszu —
     * jedno przegrane podejście nie może zgubić nadania.
     */
    @Test
    void lostConditionalWriteIsRetriedAgainstTheFreshRow() {
        IslandTitleService racing = new IslandTitleService(
                new RacingDao(new IslandTitleDao.Sql(sql), 1));

        assertEquals(IslandTitleService.GrantStatus.GRANTED,
                racing.grant(island, "Rybacka", "prestige:1").join());

        assertEquals("Rybacka", storedTitle());
    }

    /** Gdy zapis przegrywa zawsze, nic nie zapisujemy i mówimy to wprost. */
    @Test
    void permanentlyLostConditionalWriteReportsNotApplied() {
        IslandTitleService racing = new IslandTitleService(
                new RacingDao(new IslandTitleDao.Sql(sql), Integer.MAX_VALUE));

        assertEquals(IslandTitleService.GrantStatus.NOT_APPLIED,
                racing.grant(island, "Rybacka", "prestige:1").join());

        assertNull(storedTitle());
    }

    private void await(java.util.function.BooleanSupplier condition) {
        for (int attempt = 0; attempt < 200 && !condition.getAsBoolean(); attempt++) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("przerwano oczekiwanie na wczytanie tytułu",
                        interrupted);
            }
        }
    }

    private String storedTitle() {
        return sql.queryOne("SELECT title FROM wpme_sb_island_titles WHERE island_id = ?",
                rs -> rs.getString(1), island.toString()).join().orElse(null);
    }

    private String storedSourceId() {
        return sql.queryOne("SELECT source_id FROM wpme_sb_island_titles WHERE island_id = ?",
                rs -> rs.getString(1), island.toString()).join().orElse(null);
    }

    /**
     * DAO, który pierwsze {@code losses} zapisów warunkowych przegrywa tak, jak
     * przegrałby z równoległym pisarzem — bez tego gałąź ponowienia decyzji
     * nie miałaby żadnego pokrycia.
     */
    private static final class RacingDao implements IslandTitleDao {

        private final IslandTitleDao delegate;
        private int remainingLosses;

        private RacingDao(IslandTitleDao delegate, int losses) {
            this.delegate = delegate;
            this.remainingLosses = losses;
        }

        @Override
        public @NotNull CompletableFuture<Optional<Stored>> find(@NotNull UUID islandId) {
            return delegate.find(islandId);
        }

        @Override
        public @NotNull CompletableFuture<Boolean> writeIfCurrent(@NotNull UUID islandId,
                                                                 @NotNull String title,
                                                                 @NotNull String sourceId,
                                                                 @Nullable String expectedSourceId,
                                                                 long updatedAt) {
            if (remainingLosses > 0) {
                remainingLosses--;
                return CompletableFuture.completedFuture(false);
            }
            return delegate.writeIfCurrent(islandId, title, sourceId, expectedSourceId, updatedAt);
        }

        @Override
        public @NotNull CompletableFuture<Boolean> deleteIfSource(@NotNull UUID islandId,
                                                                  @NotNull String sourceId) {
            return delegate.deleteIfSource(islandId, sourceId);
        }

        @Override
        public @NotNull CompletableFuture<Void> deleteAll(@NotNull UUID islandId) {
            return delegate.deleteAll(islandId);
        }
    }
}
