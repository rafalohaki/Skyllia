package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * M2.5: tabela rzadkość → punkty za ryby z EvenMoreFish, wczytana z config.yml
 * (sekcja {@code season.fish-points}). Zastępuje dawną stałą w kodzie, więc
 * balans punktów da się zmienić bez recompilacji.
 *
 * <p>Fail-closed jak {@link CosmeticCatalog} / {@link SeasonSchedule}: sekcja
 * jest obowiązkowa, muszą się w niej znaleźć wszystkie cztery rzadkości EMF
 * (common/rare/epic/legendary), wartości całkowite &gt; 0; nieznane rzadkości
 * i duplikaty nazw (klucze różniące się wyłącznie wielkością liter) są
 * zabronione. Naruszenie rzuca {@link IllegalArgumentException}, a warstwa
 * wyżej kończy start pluginu (SEVERE + disablePlugin).
 */
public record SeasonFishPoints(@NotNull Map<String, Integer> pointsByRarity) {

    /** Rzadkości EMF wymagane w tabeli — klucze znormalizowane do lower-case. */
    public static final Set<String> REQUIRED_RARITIES =
            Set.of("common", "rare", "epic", "legendary");

    /**
     * Parsuje sekcję {@code season.fish-points}. Brak sekcji = błąd startu
     * (brak sensownego fallbacku: punkty za ryby albo są skonfigurowane
     * kompletnie, albo plugin odmawia pracy).
     */
    public static @NotNull SeasonFishPoints load(@Nullable ConfigurationSection section) {
        if (section == null) {
            throw new IllegalArgumentException("Sekcja season.fish-points jest wymagana"
                    + " (rzadkość → punkty za ryby); jej brak zatrzymuje start pluginu");
        }
        Map<String, Integer> parsed = new LinkedHashMap<>();
        for (String rawKey : section.getKeys(false)) {
            String rarity = rawKey.toLowerCase(Locale.ROOT);
            if (!REQUIRED_RARITIES.contains(rarity)) {
                throw new IllegalArgumentException("season.fish-points: nieznana rzadkość '"
                        + rawKey + "'; dozwolone klucze: " + REQUIRED_RARITIES);
            }
            if (parsed.containsKey(rarity)) {
                throw new IllegalArgumentException("season.fish-points: duplikat rzadkości '"
                        + rarity + "' (klucze różniące się tylko wielkością liter liczą się"
                        + " jako jeden)");
            }
            Object rawValue = section.get(rawKey);
            if (!(rawValue instanceof Number number)
                    || Math.floor(number.doubleValue()) != number.doubleValue()) {
                throw new IllegalArgumentException("season.fish-points." + rawKey
                        + ": oczekiwano liczby całkowitej punktów, było '" + rawValue + "'");
            }
            int points = number.intValue();
            if (points <= 0) {
                throw new IllegalArgumentException("season.fish-points." + rawKey
                        + ": punkty muszą być > 0, było " + points);
            }
            parsed.put(rarity, points);
        }
        Set<String> missing = new TreeSet<>(REQUIRED_RARITIES);
        missing.removeAll(parsed.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("season.fish-points: brakuje rzadkości "
                    + missing + "; tabela musi pokrywać wszystkie rzadkości EMF");
        }
        return new SeasonFishPoints(Map.copyOf(parsed));
    }

    /** Punkty dla rzadkości EMF; {@code null} gdy ryba spoza tabeli. */
    public @Nullable Integer pointsFor(@Nullable String rarityId) {
        return rarityId == null ? null : pointsByRarity.get(rarityId.toLowerCase(Locale.ROOT));
    }
}
