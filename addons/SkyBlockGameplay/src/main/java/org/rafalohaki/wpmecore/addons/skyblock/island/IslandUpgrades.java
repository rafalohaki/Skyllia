package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Ulepszenia wyspy za monety — średniotorowa progresja między startem
 * a prestiżem. Trzy tory kupowane poziomami z BANKU WYSPY:
 * <ul>
 *   <li>{@link Track#SIZE} — rozmiar wyspy ({@code island.setSize} × procent
 *       z poziomu, kumulatywnie jak w prestiżu);</li>
 *   <li>{@link Track#MEMBERS} — dodatkowe miejsca w zespole
 *       ({@code island.setMaxMembers});</li>
 *   <li>{@link Track#MINIONS} — dodatkowe sloty minionków; stan trzyma
 *       tabela {@code wpme_sb_island_upgrades}, którą równolegle czyta
 *       SkylliaMinions (ta sama współdzielona baza co prestiż).</li>
 * </ul>
 *
 * <p>Koszt poziomu {@code n+1} = {@code base-cost × cost-multiplier^n}
 * — ta sama formuła co prestiż, tylko tańsza o rząd wielkości (audyt:
 * jedyną ścieżką wzrostu wyspy był prestiż od 250 000 monet, czyli mebel
 * dla weterana; gracz w połowie gry nie miał nic do kupienia dla wyspy).
 *
 * <p><b>Fail-closed:</b> brak sekcji {@code island.upgrades},
 * {@code enabled: false} albo żaden tor z policzalnym kosztem =
 * {@code null} z {@link Settings#load(ConfigurationSection)} — wtyczka
 * wstaje jak dziś, a kafelek i menu ulepszeń się nie pokazują.
 */
public final class IslandUpgrades {

    /** Tor ulepszenia; {@code dbId} trafia do {@code wpme_sb_island_upgrades.track}. */
    public enum Track {
        SIZE("size"),
        MEMBERS("members"),
        MINIONS("minions");

        private final String dbId;

        Track(@NotNull String dbId) {
            this.dbId = dbId;
        }

        /** Klucz wiersza w bazie i w configu. */
        public @NotNull String dbId() {
            return dbId;
        }

        /** Sekcja configu dla toru ({@code island.upgrades.<dbId>}). */
        public @NotNull String sectionName() {
            return dbId;
        }
    }

    private IslandUpgrades() {
    }

    /**
     * Ustawienia jednego toru.
     *
     * @param maxLevel        najwyższy osiągalny poziom toru
     * @param baseCost        koszt poziomu 1 (z banku wyspy)
     * @param costMultiplier  mnożnik geometryczny; {@code > 1} = rosnący koszt
     * @param percentPerLevel przyrost procentowy rozmiaru za poziom
     *                        (tylko tor SIZE; reszta ignoruje)
     * @param slotsPerLevel   ile jednostek daje poziom (członek/slot minionka)
     */
    public record TrackSettings(int maxLevel, long baseCost, double costMultiplier,
                                double percentPerLevel, int slotsPerLevel) {

        /**
         * Koszt poziomu {@code level + 1} — tego, który podnosi tor z poziomu
         * {@code level}. Ta sama matematyka co
         * {@link IslandPrestige.Settings#costFor(int)}: rosnąco i z nasyceniem
         * na {@link Long#MAX_VALUE} przy przepełnieniu.
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
    }

    /** Sekcja {@code island.upgrades} → mapa tor→ustawienia (tylko włączone i policzalne). */
    public record Settings(@NotNull Map<Track, TrackSettings> tracks) {

        public Settings {
            tracks = Map.copyOf(tracks);
        }

        public @Nullable TrackSettings track(@NotNull Track track) {
            return tracks.get(track);
        }

        /**
         * {@code null} znaczy „ulepszenia wyłączone”: brak sekcji,
         * {@code enabled: false} albo żaden tor nie ma policzalnych wartości
         * ({@code max-level >= 1}, {@code base-cost > 0},
         * {@code cost-multiplier > 1}).
         */
        public static @Nullable Settings load(@Nullable ConfigurationSection section) {
            if (section == null || !section.getBoolean("enabled", false)) {
                return null;
            }
            EnumMap<Track, TrackSettings> tracks = new EnumMap<>(Track.class);
            for (Track track : Track.values()) {
                ConfigurationSection child = section.getConfigurationSection(track.sectionName());
                if (child == null || !child.getBoolean("enabled", true)) {
                    continue;
                }
                int maxLevel = child.getInt("max-level", 0);
                long baseCost = child.getLong("base-cost", 0L);
                double multiplier = child.getDouble("cost-multiplier", 0.0D);
                if (maxLevel < 1 || baseCost <= 0L
                        || !Double.isFinite(multiplier) || multiplier <= 1.0D) {
                    continue;
                }
                tracks.put(track, new TrackSettings(maxLevel, baseCost, multiplier,
                        Math.max(0.0D, child.getDouble("percent-per-level", 0.0D)),
                        Math.max(1, child.getInt("slots-per-level", 1))));
            }
            return tracks.isEmpty() ? null : new Settings(tracks);
        }
    }
}
