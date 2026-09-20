package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockTopRewardCoordinator;
import org.rafalohaki.wpmecore.addons.skyblock.quests.QuestCatalog;
import org.rafalohaki.wpmecore.addons.skyblock.shop.ShopCatalog;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
/**
 * @param scale mnożnik wielkości całej tablicy: skala tekstu, głów i odstępów
 *              między wierszami naraz. Jedno pokrętło zamiast pięciu, bo
 *              rozjechanie ich osobno psuje układ - głowa przestaje pasować
 *              do wiersza, który opisuje.
 *
 * <p>Tytuł tablicy może zawierać literał {@link #EDITION_TOKEN}; jest on
 * podstawiany przy każdym odświeżeniu (patrz {@link #resolveTitle}), więc
 * limit 256 znaków z parsowania konfiguracji obejmuje też postać z tokenem.
 */

public record LeaderboardSettings(boolean enabled, int refreshSeconds, int entries,
                           double scale,
                           @NotNull List<LeaderboardBoard> boards,
                           @NotNull PedestalSettings pedestal) {
    public LeaderboardSettings {
        boards = List.copyOf(boards);
    }

    /**
     * Literał w tytule tablicy, który przy każdym odświeżeniu jest zastępowany
     * nazwą bieżącej edycji sezonu. Tytuł bez tokenu nigdy nie jest ruszany.
     */
    public static final String EDITION_TOKEN = "{edition}";

    /**
     * Podstawia edycję do skonfigurowanego tytułu tablicy.
     *
     * <p>Bez edycji ({@code edition == null} albo pusta) lub gdy tytuł nie
     * zawiera tokenu zwraca tytuł bez zmian — konfiguracje statyczne są wtedy
     * stabilne bajt w bajt, niezależnie od tego, czy dostawca jest podpięty.
     */
    public static @NotNull String resolveTitle(@NotNull String configured,
                                               @Nullable String edition) {
        if (edition == null || edition.isBlank()
                || !configured.contains(EDITION_TOKEN)) {
            return configured;
        }
        return configured.replace(EDITION_TOKEN, edition);
    }

    public static @NotNull LeaderboardSettings disabled() {
        return new LeaderboardSettings(false, 300, 5, 1.0, List.of(),
                PedestalSettings.disabled());
    }
}
