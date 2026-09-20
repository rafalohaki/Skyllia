package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kontrakt wierszy tablic wystawianych jako placeholdery.
 *
 * <p>Bez MockBukkita: formatowanie to czysta składanka napisów, a źródło danych
 * jest atrapą zwracającą gotową {@code CompletableFuture}. Dzięki temu test
 * trafia do domyślnej bramki, a nie do profilu {@code mockbukkit-compat}.
 */
class LeaderboardPlaceholdersTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static LeaderboardBoard board(String id, boolean allTime, String metric) {
        return new LeaderboardBoard(id, allTime, "world", 23.5, 99.6, -3.5, "<bold>T</bold>", metric);
    }

    private static LeaderboardEntry entry(int rank, String name, long score) {
        return new LeaderboardEntry(rank, PLAYER, name, score, "<gray>bank</gray> 500 monet");
    }

    private static LeaderboardSettings settings(int entries, int refreshSeconds,
                                                LeaderboardBoard... boards) {
        return new LeaderboardSettings(true, refreshSeconds, entries, 1.0,
                List.of(boards), PedestalSettings.disabled());
    }

    /**
     * Wiersz sezonowy w formacie dwuliniowym: miejsce z nickiem, pod spodem
     * wcięte rozbicie wyniku — bez zbędnych znaczników zamykających.
     */
    @Test
    void seasonRowMatchesRendererMinusStrayClosingTag() {
        String row = LeaderboardPlaceholders.row(board("season", false, "punkty"),
                entry(1, "Steve", 1234L));

        assertEquals("<gold>#1 <white><bold>Steve</bold></white><newline>"
                + "  <gray>punkty</gray> "
                + "<yellow><bold>" + org.rafalohaki.wpmecore.addons.skyblock.shared.Ui.money(1234L) + "</bold></yellow>"
                + " <dark_gray>•</dark_gray> <gray>bank</gray> 500 monet", row);
        assertFalse(row.contains("</medal>"), "literał </medal> to błąd renderera, nie format");
        assertFalse(row.contains("</gold>"), "kolor miejsca ma zostać otwarty, jak w rendererze");
    }

    /** Hall of fame pokazuje surową liczbę wygranych sezonów, nie kwotę w coins. */
    @Test
    void allTimeRowUsesRawScoreNotMoney() {
        String row = LeaderboardPlaceholders.row(board("hall-of-fame", true, "wygrane sezony"),
                entry(2, "Alex", 3L));

        assertEquals("<white>#2 <white><bold>Alex</bold></white><newline>"
                + "  <gray>wygrane sezony</gray> "
                + "<yellow><bold>3</bold></yellow>"
                + " <dark_gray>•</dark_gray> <gray>bank</gray> 500 monet", row);
    }

    /** Kolory podium i neutral poza nim — kopia tabeli z renderera. */
    @Test
    void medalColoursMatchRenderer() {
        assertEquals("<gold>", LeaderboardPlaceholders.medal(1));
        assertEquals("<white>", LeaderboardPlaceholders.medal(2));
        assertEquals("<#cd7f32>", LeaderboardPlaceholders.medal(3));
        assertEquals("<gray>", LeaderboardPlaceholders.medal(4));
        assertEquals("<gray>", LeaderboardPlaceholders.medal(10));
    }

    /** Stała liczba wierszy: brakujące miejsca są puste, nie znikają. */
    @Test
    void boardIsPaddedToConfiguredRowCount() {
        List<String> rows = LeaderboardPlaceholders.render(board("season", false, "punkty"),
                List.of(entry(1, "Steve", 10L), entry(2, "Alex", 5L)), 5);

        assertEquals(5, rows.size());
        assertTrue(rows.get(0).startsWith("<gold>#1 "));
        assertTrue(rows.get(1).startsWith("<white>#2 "));
        assertEquals(List.of("", "", ""), rows.subList(2, 5));
    }

    /** Pusta tablica: komunikat zastępczy w pierwszym wierszu, reszta pusta. */
    @Test
    void emptyBoardShowsPlaceholderMessage() {
        List<String> rows = LeaderboardPlaceholders.render(board("season", false, "punkty"),
                List.of(), 3);

        assertEquals(List.of(
                "<gray><italic>Jeszcze nikt się tu nie zapisał.</italic></gray>", "", ""), rows);
    }

    /** Nadmiar wyników jest ucinany do liczby wierszy z konfiguracji. */
    @Test
    void extraEntriesAreTruncated() {
        List<String> rows = LeaderboardPlaceholders.render(board("season", false, "punkty"),
                List.of(entry(1, "A", 3L), entry(2, "B", 2L), entry(3, "C", 1L)), 2);

        assertEquals(2, rows.size());
    }

    /** Przed pierwszym odświeżeniem tablica jest pusta, ale znana — bez surowych tokenów. */
    @Test
    void rowsAreEmptyButKnownBeforeFirstRefresh() {
        LeaderboardPlaceholders cache = new LeaderboardPlaceholders(
                settings(5, 300, board("season", false, "punkty")),
                () -> CompletableFuture.completedFuture(
                        new LeaderboardService.Boards(List.of(), List.of())));

        assertEquals("", cache.row("season", 1));
        assertNull(cache.row("season", 0), "numeracja od 1");
        assertNull(cache.row("season", 6), "poza zakresem");
        assertNull(cache.row("nie-ma-takiej", 1), "nieznana tablica zostawia token PAPI");
    }

    /** Odczyt nie dotyka źródła danych — to jest cały sens cache'u. */
    @Test
    void readingRowsNeverTouchesTheSource() {
        AtomicInteger calls = new AtomicInteger();
        LeaderboardPlaceholders cache = new LeaderboardPlaceholders(
                settings(3, 300, board("season", false, "punkty")),
                () -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new LeaderboardService.Boards(List.of(entry(1, "Steve", 7L)), List.of()));
                });

        for (int i = 1; i <= 3; i++) {
            cache.row("season", i);
        }
        assertEquals(0, calls.get(), "placeholder nie może wołać źródła danych");

        cache.refreshNow().join();
        assertEquals(1, calls.get());

        for (int i = 1; i <= 3; i++) {
            cache.row("season", i);
        }
        assertEquals(1, calls.get(), "po odświeżeniu odczyt nadal jest tylko odczytem");
    }

    /** Odświeżenie rozdziela źródła: sezon do tablicy sezonowej, all-time do hall of fame. */
    @Test
    void refreshRoutesEachBoardToItsOwnSource() {
        LeaderboardPlaceholders cache = new LeaderboardPlaceholders(
                settings(2, 300, board("season", false, "punkty"),
                        board("hall-of-fame", true, "wygrane sezony")),
                () -> CompletableFuture.completedFuture(new LeaderboardService.Boards(
                        List.of(entry(1, "Sezonowy", 10L)),
                        List.of(entry(1, "Wieczny", 4L)))));

        cache.refreshNow().join();

        assertTrue(cache.row("season", 1).contains("Sezonowy"));
        assertTrue(cache.row("hall-of-fame", 1).contains("Wieczny"));
        assertEquals("", cache.row("season", 2));
    }

    /** Awaria źródła zostawia poprzednią migawkę zamiast czyścić tablicę. */
    @Test
    void failedRefreshKeepsLastGoodSnapshot() {
        AtomicReference<CompletableFuture<LeaderboardService.Boards>> next = new AtomicReference<>(
                CompletableFuture.completedFuture(new LeaderboardService.Boards(
                        List.of(entry(1, "Steve", 7L)), List.of())));
        LeaderboardPlaceholders cache = new LeaderboardPlaceholders(
                settings(2, 300, board("season", false, "punkty")), next::get);

        cache.refreshNow().join();
        String good = cache.row("season", 1);
        assertTrue(good.contains("Steve"));

        next.set(CompletableFuture.failedFuture(new IllegalStateException("baza padła")));
        // Awaria jest celowa, a logger sypie wtedy pełnym stosem — wyciszamy go
        // na czas tego jednego wywołania, żeby wyjście bramki zostało czytelne.
        java.util.logging.Logger log = java.util.logging.Logger.getLogger("wpmecore");
        java.util.logging.Level before = log.getLevel();
        log.setLevel(java.util.logging.Level.OFF);
        try {
            cache.refreshNow().join();
        } finally {
            log.setLevel(before);
        }

        assertEquals(good, cache.row("season", 1), "awaria nie może wyczyścić tablicy");
    }

    /** Okres odświeżania: ten sam config co renderer, z tą samą podłogą 30 s. */
    @Test
    void refreshPeriodHonoursConfigWithSameFloorAsRenderer() {
        LeaderboardBoard board = board("season", false, "punkty");
        assertEquals(300L, new LeaderboardPlaceholders(settings(5, 300, board),
                LeaderboardPlaceholdersTest::none).refreshSeconds());
        assertEquals(LeaderboardPlaceholders.MIN_REFRESH_SECONDS,
                new LeaderboardPlaceholders(settings(5, 1, board),
                        LeaderboardPlaceholdersTest::none).refreshSeconds());
    }

    /** Wyłączone tablice nie mają czego wystawiać — most PAPI ma się wtedy nie budzić. */
    @Test
    void disabledLeaderboardsPublishNothing() {
        LeaderboardPlaceholders cache = new LeaderboardPlaceholders(
                LeaderboardSettings.disabled(), LeaderboardPlaceholdersTest::none);

        assertFalse(cache.hasBoards());
        assertNull(cache.row("season", 1));
    }

    /** Liczba wierszy pochodzi z konfiguracji renderera, nie z sufitu. */
    @Test
    void rowCountComesFromSettingsEntries() {
        LeaderboardPlaceholders cache = new LeaderboardPlaceholders(
                settings(10, 300, board("season", false, "punkty")),
                LeaderboardPlaceholdersTest::none);

        assertEquals(10, cache.entries());
        assertEquals("", cache.row("season", 10));
        assertNull(cache.row("season", 11));
    }

    /** Migawka jest niezmienna i podmieniana w całości — czytelnik nigdy nie widzi połowy. */
    @Test
    void snapshotIsImmutable() {
        LeaderboardPlaceholders cache = new LeaderboardPlaceholders(
                settings(2, 300, board("season", false, "punkty")),
                () -> CompletableFuture.completedFuture(new LeaderboardService.Boards(
                        List.of(entry(1, "Steve", 7L)), List.of())));
        cache.refreshNow().join();

        String before = cache.row("season", 1);
        assertSame(before, cache.row("season", 1));
    }

    private static CompletableFuture<LeaderboardService.Boards> none() {
        return CompletableFuture.completedFuture(
                new LeaderboardService.Boards(List.of(), List.of()));
    }
}
