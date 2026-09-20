package org.rafalohaki.wpmecore.addons.skyblock.season;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Ekspansja PlaceholderAPI: {@code %skyblock_board_<tablica>_<numer>%} oraz
 * {@code %skyblock_island_title%}.
 *
 * <p>Jeden token tablicy to jeden wiersz (wg {@code leaderboards.entries}). Numeracja od 1;
 * wiersze ponad liczbę wyników są puste, a tablica bez wyników ma w pierwszym
 * wierszu komunikat zastępczy.
 *
 * <p><b>Kontrakt bez blokowania.</b> {@link #onRequest} tylko czyta migawkę
 * z {@link LeaderboardPlaceholders} i migawkę tytułów wyspy — żadnego SQL-a,
 * żadnego {@code join()}, żadnego sięgania po stan świata. Migawki liczy w tle
 * harmonogram asynchroniczny; ta metoda biegnie na wątku, który rysuje hologram.
 *
 * <p>Rozwiązywanie tytułu dostarcza konstruktor jako {@link Function} po
 * identyfikatorze gracza (JDK-owa klasa, więc izolowany classloader widzi tę
 * samą definicję co wtyczka), a ekspansja nie dotyka klas SkyBlocka. Gracz bez
 * wyspy albo wyspa bez wczytanego tytułu dostaje <b>pusty napis</b> — nigdy
 * „null”, bo PAPI zostawiłoby wtedy surowy token w hologramie.
 *
 * <p>Klasa jest ładowana odizolowanym classloaderem (patrz
 * {@link SeasonPlaceholderModule}), więc wolno jej sięgać wyłącznie po
 * <b>publiczne</b> składowe klas SkyBlocka — pakietowe byłyby w innym pakiecie
 * czasu wykonania i skończyłyby się {@code IllegalAccessError}.
 */
public final class SkyBlockSeasonExpansion extends PlaceholderExpansion {

    private static final String BOARD_PREFIX = "board_";
    /** Token tytułu wyspy: {@code %skyblock_island_title%}. */
    static final String TITLE_TOKEN = "island_title";

    private final String version;
    private final LeaderboardPlaceholders boards;
    private final Function<UUID, String> islandTitles;

    /** Konstruktor mostu — jedyny publiczny, bo tylko po nim sięga refleksja. */
    public SkyBlockSeasonExpansion(@NotNull JavaPlugin plugin,
                                   @NotNull LeaderboardPlaceholders boards,
                                   @NotNull Function<UUID, String> islandTitles) {
        // Wersja jest zdejmowana raz, przy budowie: nie zmienia się w trakcie
        // pracy serwera, a ścieżka wołana przez PAPI ma być możliwie krótka.
        this(Objects.requireNonNull(plugin, "plugin").getPluginMeta().getVersion(), boards,
                islandTitles);
    }

    SkyBlockSeasonExpansion(@NotNull String version, @NotNull LeaderboardPlaceholders boards,
                           @NotNull Function<UUID, String> islandTitles) {
        this.version = Objects.requireNonNull(version, "version");
        this.boards = Objects.requireNonNull(boards, "boards");
        this.islandTitles = Objects.requireNonNull(islandTitles, "islandTitles");
    }

    @Override
    public @NotNull String getIdentifier() {
        return "skyblock";
    }

    @Override
    public @NotNull String getAuthor() {
        return "rafalohaki";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        // Ekspansja jedzie w naszym jarze, więc ma przeżyć /papi reload.
        return true;
    }

    /**
     * {@code island_title} → tytuł wyspy gracza (pusty, gdy brak);
     * {@code board_<id>_<numer>} → wiersz tablicy; cokolwiek innego to
     * {@code null}, czyli „nie znam tego tokenu” — PAPI zostawia go wtedy
     * nietkniętego.
     */
    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, @NotNull String params) {
        String token = params.toLowerCase(Locale.ROOT);
        if (token.equals(TITLE_TOKEN)) {
            return player == null ? "" : titleOf(player);
        }
        if (!token.startsWith(BOARD_PREFIX)) {
            return null;
        }
        // Identyfikator tablicy sam może zawierać podkreślenie (dozwolone w
        // konfiguracji), więc numer odcinamy od końca, nie od pierwszego znaku.
        int split = token.lastIndexOf('_');
        if (split < BOARD_PREFIX.length()) {
            return null;
        }
        int index;
        try {
            index = Integer.parseInt(token.substring(split + 1));
        } catch (NumberFormatException notANumber) {
            return null;
        }
        return boards.row(token.substring(BOARD_PREFIX.length(), split), index);
    }

    /**
     * Tytuł wyspy gracza. Rozwiązywanie jest kontraktem dostawcy (odczyt
     * migawki), ale biegnie na wątku, który rysuje hologram — wyjątek wyszedłby
     * z PAPI i wywalał render, dlatego degraduje się do pustego napisu.
     */
    private @NotNull String titleOf(@NotNull OfflinePlayer player) {
        try {
            String title = islandTitles.apply(player.getUniqueId());
            return title == null ? "" : title;
        } catch (RuntimeException failure) {
            return "";
        }
    }
}
