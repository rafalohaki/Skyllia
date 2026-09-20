package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rozbiór tokenu {@code %skyblock_board_<tablica>_<numer>%} i
 * {@code %skyblock_island_title%}.
 *
 * <p>Bez MockBukkita i bez działającego PlaceholderAPI: {@code onRequest} jest
 * czystą funkcją napisu, a wersja wtyczki oraz tytuł wyspy są zwykłymi
 * argumentami konstruktora (gracz to mock interfejsu — wystarczy mu UUID).
 */
class SkyBlockSeasonExpansionTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    /** Tytuł wyspy gracza — kontrakt dostawcy: pusty napis, gdy wyspa go nie ma. */
    private static final java.util.function.Function<UUID, String> TITLES =
            player -> PLAYER.equals(player) ? "Rybacka" : "";

    private static SkyBlockSeasonExpansion expansion() {
        return new SkyBlockSeasonExpansion("1.0-TEST", boards(), TITLES);
    }

    private static LeaderboardPlaceholders boards() {
        LeaderboardBoard season = new LeaderboardBoard("season", false, "world",
                23.5, 99.6, -3.5, "<bold>RANKING</bold>", "punkty");
        LeaderboardBoard fame = new LeaderboardBoard("hall-of-fame", true, "world",
                23.5, 99.6, 6.5, "<bold>HALL</bold>", "wygrane sezony");
        LeaderboardPlaceholders boards = new LeaderboardPlaceholders(
                new LeaderboardSettings(true, 300, 3, 1.0, List.of(season, fame),
                        PedestalSettings.disabled()),
                () -> CompletableFuture.completedFuture(new LeaderboardService.Boards(
                        List.of(new LeaderboardEntry(1, PLAYER, "Steve", 10L, "<gray>bank</gray> 5")),
                        List.of(new LeaderboardEntry(1, PLAYER, "Alex", 2L, "<gray>bank</gray> 9")))));
        boards.refreshNow().join();
        return boards;
    }

    private static org.bukkit.OfflinePlayer offline(UUID playerId) {
        org.bukkit.OfflinePlayer player = mock(org.bukkit.OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(playerId);
        return player;
    }

    @Test
    void identifierAndPersistenceMatchTheContract() {
        SkyBlockSeasonExpansion expansion = expansion();

        assertEquals("skyblock", expansion.getIdentifier());
        assertEquals("rafalohaki", expansion.getAuthor());
        assertEquals("1.0-TEST", expansion.getVersion());
        // Ekspansja jedzie w naszym jarze — /papi reload nie może jej zdjąć.
        assertTrue(expansion.persist());
    }

    /** Identyfikator z myślnikiem: numer odcinany od końca, nie od pierwszego znaku. */
    @Test
    void resolvesRowsOfBothBoards() {
        SkyBlockSeasonExpansion expansion = expansion();

        assertTrue(
                expansion.onRequest(null, "board_season_1").contains("Steve"));
        assertTrue(
                expansion.onRequest(null, "board_hall-of-fame_1").contains("Alex"));
        assertEquals("", expansion.onRequest(null, "board_season_3"),
                "wiersz bez danych jest pusty, nie znika");
    }

    /** Wielkość liter nie ma znaczenia — TAB i hologramy piszą tokeny różnie. */
    @Test
    void tokenIsCaseInsensitive() {
        assertEquals(expansion().onRequest(null, "board_season_1"),
                expansion().onRequest(null, "BOARD_SEASON_1"));
    }

    /** Wszystko, czego nie znamy, zwraca null — PAPI zostawia wtedy token widoczny. */
    @Test
    void unknownTokensAreLeftToPlaceholderApi() {
        SkyBlockSeasonExpansion expansion = expansion();

        assertNull(expansion.onRequest(null, "board_season_0"), "numeracja od 1");
        assertNull(expansion.onRequest(null, "board_season_4"), "poza liczbą wierszy");
        assertNull(expansion.onRequest(null, "board_season_x"), "numer nie jest liczbą");
        assertNull(expansion.onRequest(null, "board_nieznana_1"), "nieznana tablica");
        assertNull(expansion.onRequest(null, "board_1"), "brak identyfikatora tablicy");
        assertNull(expansion.onRequest(null, "board_"), "sam prefiks");
        assertNull(expansion.onRequest(null, "sezon_nazwa"), "cudzy token");
        assertNull(expansion.onRequest(null, ""), "pusty token");
    }

    /**
     * {@code %skyblock_island_title%}: tytuł wyspy gracza, a bez tytułu — pusty
     * napis. „Null” zostawiłoby w hologramie surowy token, więc ten kontrakt
     * sprawdzamy także dla gracza bez wyspy i dla braku gracza.
     */
    @Test
    void islandTitleTokenResolvesThroughTheResolverAndNeverRendersNull() {
        SkyBlockSeasonExpansion expansion = expansion();

        assertEquals("Rybacka", expansion.onRequest(offline(PLAYER), "island_title"));
        assertEquals("Rybacka", expansion.onRequest(offline(PLAYER), "ISLAND_TITLE"),
                "token jest bez wielkości liter");
        assertEquals("", expansion.onRequest(offline(UUID.randomUUID()), "island_title"),
                "wyspa bez tytułu renderuje pustkę");
        assertEquals("", expansion.onRequest(null, "island_title"),
                "brak gracza to też pustka, nie „null”");
    }

    /** Wyjątek dostawcy nie może wyjść z wątku, który rysuje hologram. */
    @Test
    void throwingTitleResolverDegradesToAnEmptyString() {
        SkyBlockSeasonExpansion expansion = new SkyBlockSeasonExpansion("1.0-TEST", boards(),
                player -> {
                    throw new IllegalStateException("magazyn tytułów padł");
                });

        assertEquals("", expansion.onRequest(offline(PLAYER), "island_title"));
        assertTrue(expansion.onRequest(null, "board_season_1").contains("Steve"),
                "awaria tytułu nie może zabrać tablic wyników");
    }
}
