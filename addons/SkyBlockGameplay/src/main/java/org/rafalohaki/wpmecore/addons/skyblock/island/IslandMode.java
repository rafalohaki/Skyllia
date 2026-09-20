package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * M1-C: three island modes. Only CLASSIC is enabled by default.
 * OneBlock and Expedition are behind feature flags and show maintenance/disabled.
 */
public enum IslandMode {
    CLASSIC("classic", Material.GRASS_BLOCK, "<green><bold>Klasyczny SkyBlock</bold></green>",
            new String[]{
                    "<gray>Tradycyjna lewitująca wyspa przetrwania.</gray>",
                    "<gray>Drzewo, uprawy, skrzynka startowa i generatory.</gray>"
            }),
    ONEBLOCK("oneblock", Material.BEACON, "<aqua><bold>OneBlock</bold></aqua>",
            new String[]{
                    "<gray>Ekscytująca rozgrywka na pojedynczym bloku!</gray>",
                    "<gray>Wykopuj OneBlock, odblokowuj kolejne fazy i surowce.</gray>"
            }),
    EXPEDITION("expedition", Material.FILLED_MAP, "<gold><bold>Ekspedycja</bold></gold>",
            new String[]{
                    "<gray>Rozwijaj placówkę i odblokowuj nowe sektory.</gray>",
                    "<gray>Las, kopalnia i ruiny — wybierz swoją ścieżkę.</gray>"
            });

    private final String id;
    private final Material icon;
    private final String displayName;
    private final String[] description;

    IslandMode(String id, Material icon, String displayName, String[] description) {
        this.id = id;
        this.icon = icon;
        this.displayName = displayName;
        this.description = description;
    }

    public @NotNull String id() { return id; }
    public @NotNull Material icon() { return icon; }
    public @NotNull String displayName() { return displayName; }
    public @NotNull String[] description() { return description.clone(); }

    private static final Map<String, IslandMode> BY_ID = Map.of(
            "classic", CLASSIC,
            "oneblock", ONEBLOCK,
            "expedition", EXPEDITION
    );

    public static @Nullable IslandMode parse(@Nullable String raw) {
        if (raw == null) return null;
        return BY_ID.get(raw.toLowerCase(Locale.ROOT).trim());
    }

    /** Skyllia template name mapping. Classic uses "starter", OneBlock uses "oneblock". Expedition placeholder. */
    public @NotNull String skylliaTemplate() {
        return switch (this) {
            case CLASSIC -> "starter";
            case ONEBLOCK -> "oneblock";
            case EXPEDITION -> "expedition";
        };
    }
}
