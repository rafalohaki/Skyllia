package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wiersze tablic wyników wystawione jako gotowe napisy MiniMessage.
 *
 * <p>Powstało pod migrację hologramów na FancyHolograms: placeholder jest wołany
 * synchronicznie z wątku, który rysuje hologram, więc <b>nie wolno</b> w nim
 * dotknąć SQL-a ani czekać na przyszłość. Ta klasa trzyma gotową migawkę
 * napisów; liczy ją w tle asynchronicznie — jednym {@code service.refresh(entries)} na cykl.
 * Odczyt to {@code Map.get} na polu {@code volatile}, bez blokad.
 */
public final class LeaderboardPlaceholders {

    /** Napis dla tablicy bez wyników. */
    static final String EMPTY_BOARD =
            "<gray><italic>Jeszcze nikt się tu nie zapisał.</italic></gray>";

    /** Dolna granica odświeżania w sekundach. */
    static final long MIN_REFRESH_SECONDS = 30L;

    private static final Logger LOG = Logger.getLogger("wpmecore");

    private final LeaderboardSettings settings;
    private final Supplier<CompletableFuture<LeaderboardService.Boards>> source;

    /**
     * Identyfikator tablicy → dokładnie {@code settings.entries()} wierszy.
     * Mapa jest niezmienna, podmieniana w całości: czytelnik widzi albo starą,
     * albo nową tablicę, nigdy jej połowy.
     */
    private volatile Map<String, List<String>> rows;

    LeaderboardPlaceholders(@NotNull LeaderboardSettings settings,
                            @NotNull Supplier<CompletableFuture<LeaderboardService.Boards>> source) {
        this.settings = settings;
        this.source = source;
        // Przed pierwszym odświeżeniem tablice są puste, a nie nieznane: hologram
        // pokaże pustkę zamiast surowego %skyblock_board_…%. Literówka w id nadal
        // zostaje widoczna, bo takiego klucza w mapie nie ma.
        Map<String, List<String>> empty = new LinkedHashMap<>();
        for (LeaderboardBoard board : settings.boards()) {
            empty.put(board.id(), filler(settings.entries()));
        }
        this.rows = Map.copyOf(empty);
    }

    /** Czy jest co publikować — przy {@code leaderboards.enabled: false} nie ma ani jednej tablicy. */
    boolean hasBoards() {
        return !rows.isEmpty();
    }

    /** Ile wierszy ma każda tablica — tyle, ile dziś rysuje renderer. */
    public int entries() {
        return settings.entries();
    }

    /** Okres odświeżania migawki; ta sama podłoga 30 s, co w rendererze. */
    public long refreshSeconds() {
        return Math.max(MIN_REFRESH_SECONDS, settings.refreshSeconds());
    }

    /**
     * Wiersz {@code index} (licząc od 1) tablicy {@code boardId} albo {@code null},
     * gdy tablicy nie ma w konfiguracji lub numer jest poza zakresem — PAPI
     * zostawia wtedy token nietknięty, więc pomyłka w komendzie jest widoczna.
     *
     * <p>Wyłącznie odczyt z pamięci. Wołane z wątku renderującego hologram.
     */
    public @Nullable String row(@NotNull String boardId, int index) {
        List<String> board = rows.get(boardId);
        if (board == null || index < 1 || index > board.size()) {
            return null;
        }
        return board.get(index - 1);
    }

    /**
     * Przelicza migawkę. Wołane z harmonogramu asynchronicznego, nigdy z wątku
     * regionu. Awaria zostawia poprzednią migawkę — tablica zamarza na starych
     * danych zamiast zniknąć.
     */
    @NotNull CompletableFuture<Void> refreshNow() {
        return source.get().handle((boards, failure) -> {
            if (failure != null) {
                LOG.log(Level.WARNING,
                        "Nie policzono tablic wyników dla placeholderów", failure);
                return null;
            }
            Map<String, List<String>> next = new LinkedHashMap<>();
            for (LeaderboardBoard board : settings.boards()) {
                next.put(board.id(), render(board,
                        board.allTime() ? boards.allTime() : boards.season(),
                        settings.entries()));
            }
            rows = Map.copyOf(next);
            return null;
        });
    }

    /** Stała liczba wierszy: dane, potem pusty wypełniacz do {@code size}. */
    static @NotNull List<String> render(@NotNull LeaderboardBoard board,
                                        @NotNull List<LeaderboardEntry> entries, int size) {
        List<String> out = new ArrayList<>(size);
        if (entries.isEmpty()) {
            out.add(EMPTY_BOARD);
        } else {
            for (LeaderboardEntry entry : entries) {
                if (out.size() >= size) {
                    break;
                }
                out.add(row(board, entry));
            }
        }
        while (out.size() < size) {
            out.add("");
        }
        return List.copyOf(out);
    }

    /**
     * Jeden wiersz — dwie linie: miejsce z nickiem, pod spodem wcięte
     * rozbicie wyniku. Wąskie (pasuje w szczelinę muru), a wpis czyta się
     * jako jeden blok. Bullet {@code •} jest w waniliowej czcionce.
     */
    static @NotNull String row(@NotNull LeaderboardBoard board, @NotNull LeaderboardEntry entry) {
        return medal(entry.rank()) + '#' + entry.rank()
                + " <white><bold>" + entry.name() + "</bold></white><newline>"
                + "  <gray>" + board.metric() + "</gray> "
                + "<yellow><bold>"
                + (board.allTime() ? String.valueOf(entry.score()) : Ui.money(entry.score()))
                + "</bold></yellow>"
                + " <dark_gray>•</dark_gray> " + entry.detail();
    }

    /** Kolor miejsca na podium (złoto, srebro, brąz, neutralny). */
    static @NotNull String medal(int rank) {
        return switch (rank) {
            case 1 -> "<gold>";
            case 2 -> "<white>";
            case 3 -> "<#cd7f32>";
            default -> "<gray>";
        };
    }

    private static @NotNull List<String> filler(int size) {
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add("");
        }
        return List.copyOf(out);
    }
}
