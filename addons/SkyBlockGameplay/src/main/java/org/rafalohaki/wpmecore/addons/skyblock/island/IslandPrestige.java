package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Prestiż Wyspy — czysta matematyka i konfiguracja, bez Bukkita i bez bazy.
 *
 * <p>Powtarzalny zlew monet dla wyspy, która przeszła już wszystko: poziom
 * {@code n+1} kosztuje {@code base-cost × cost-multiplier^n} płacone z konta
 * wyspy. Nagroda to tytuł kosmetyczny plus mechaniczne perki z konfiguracji
 * (sloty minionków, szczęście na kryształy) — bez punktów sezonowych i bez
 * zwrotu monet, żeby prestiż nie był samopodtrzymującą się drukarką.
 *
 * <p><b>Fail-closed:</b> brak sekcji {@code island.prestige}, {@code enabled: false}
 * albo wartości, z których nie da się policzyć rosnącego kosztu, znaczą
 * {@code null} z {@link #load(ConfigurationSection)} — wtyczka wstaje jak dziś,
 * a kafelek i menu prestiżu w ogóle się nie pokazują.
 */
public final class IslandPrestige {

    /** Domyślny poziom maksymalny — używany, gdy config nie poda {@code max-level}. */
    public static final int DEFAULT_MAX_LEVEL = 10;

    private IslandPrestige() {
    }

    /**
     * Wartości z sekcji {@code island.prestige} w {@code config.yml}.
     *
     * @param maxLevel       najwyższy osiągalny poziom prestiżu
     * @param baseCost       koszt pierwszego prestiżu (z banku wyspy)
     * @param costMultiplier mnożnik geometryczny; {@code > 1} gwarantuje rosnący koszt
     * @param titles         tytuł kosmetyczny per poziom ({@code level − 1}); może być krótsza
     * @param minionSlotsEveryLevels co ile poziomów prestiżu wyspa dostaje +1 slot
     *                               minionka ({@code 0} = bez bonusu)
     * @param crystalLuckPercentPerLevel bonus do szans na kryształy z rozgrywki
     *                                   (gameplay-drops) za poziom, w procentach
     */
    public record Settings(int maxLevel, long baseCost, double costMultiplier,
                           @NotNull List<String> titles,
                           int minionSlotsEveryLevels,
                           double crystalLuckPercentPerLevel) {

        public Settings {
            titles = List.copyOf(titles);
        }

        /** Prestiż czysto kosmetyczny: wygodny konstruktor bez perków (testy, stare miejsca). */
        public Settings(int maxLevel, long baseCost, double costMultiplier,
                        @NotNull List<String> titles) {
            this(maxLevel, baseCost, costMultiplier, titles, 0, 0.0D);
        }

        /**
         * Sekcja configu → ustawienia. {@code null} znaczy „prestiż wyłączony”:
         * brak sekcji, {@code enabled: false} albo wartości niepoliczalne
         * (koszt niedodatni, mnożnik {@code <= 1}, limit poziomów {@code < 1}).
         */
        public static @Nullable Settings load(@Nullable ConfigurationSection section) {
            if (section == null || !section.getBoolean("enabled", false)) {
                return null;
            }
            int maxLevel = section.getInt("max-level", DEFAULT_MAX_LEVEL);
            long baseCost = section.getLong("base-cost", 0L);
            double multiplier = section.getDouble("cost-multiplier", 0.0D);
            if (maxLevel < 1 || baseCost <= 0L || !Double.isFinite(multiplier) || multiplier <= 1.0D) {
                return null;
            }
            return new Settings(maxLevel, baseCost, multiplier,
                    section.getStringList("title-per-level"),
                    Math.max(0, section.getInt("minion-slots-every-levels", 0)),
                    Math.max(0.0D, section.getDouble("crystal-luck-percent-per-level", 0.0D)));
        }

        /**
         * Koszt prestiżu numer {@code level + 1} — czyli tego, który podnosi
         * wyspę z poziomu {@code level}. Rosnący geometrycznie i nigdy poniżej
         * {@code baseCost}; przy przepełnieniu {@code long} nasyca się, więc
         * koszt nie staje się nagle ujemny i nie „zarabia” dla wyspy.
         */
        public long costFor(int level) {
            if (level <= 0) {
                return baseCost;
            }
            double raw = baseCost * Math.pow(costMultiplier, level);
            if (!Double.isFinite(raw) || raw >= Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            return Math.max(baseCost, Math.round(raw));
        }

        /** Tytuł kosmetyczny przyznany za poziom {@code level} (1-based); brak = pusty. */
        public @Nullable String titleFor(int level) {
            return level >= 1 && level <= titles.size() ? titles.get(level - 1) : null;
        }

        /** Bonusowe sloty minionków z prestiżu: +1 co {@code minionSlotsEveryLevels} poziomów. */
        public int extraMinionSlots(int level) {
            return minionSlotsEveryLevels > 0 ? Math.max(0, level) / minionSlotsEveryLevels : 0;
        }

        /** Mnożnik szans na kryształy z rozgrywki dla poziomu (1.0 = bez bonusu). */
        public double crystalLuckMultiplier(int level) {
            return 1.0 + Math.max(0, level) * (crystalLuckPercentPerLevel / 100.0);
        }
    }
}
