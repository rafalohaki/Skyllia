package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Poziom wyspy — czysta matematyka, bez Bukkita i bez bazy.
 *
 * <p>Skyllia nie zna pojęcia poziomu (potwierdzone {@code javap} na jarze,
 * patrz Javadoc {@code SkyBlockPresentationPublisher}). To nasza liczba, liczona
 * z trwałego dorobku wyspy: {@code xp = dorobek/scoreDivisor + minionki×minionXp
 * + rozdział OneBlocka×chapterXp + punkty sezonowe członków×seasonPointXp}.
 * Poziom {@code n} wymaga {@code base × n^1.5} xp; poziom 0 to „jeszcze nic”.
 */
public final class IslandLevel {

    private IslandLevel() {
    }

    /** Wagi i progi z sekcji {@code island-level:} w {@code config.yml}. */
    public record Settings(long base, int maxLevel, long scoreDivisor, long minionXp,
                           long chapterXp, long seasonPointXp, long rewardPerLevel,
                           int refreshSeconds) {

        public static final Settings DEFAULTS =
                new Settings(500L, 100, 100L, 250L, 500L, 2L, 200L, 60);

        public Settings {
            if (base <= 0L || maxLevel < 1 || maxLevel > 1000 || scoreDivisor <= 0L
                    || minionXp < 0L || chapterXp < 0L || seasonPointXp < 0L
                    || rewardPerLevel < 0L || rewardPerLevel > 1_000_000L
                    || refreshSeconds < 5) {
                throw new IllegalArgumentException("island-level: nieprawidłowe wagi lub progi");
            }
        }

        /** Brak sekcji = wartości domyślne (feature nie wymaga ręcznej migracji configu). */
        public static @NotNull Settings load(@Nullable ConfigurationSection section) {
            if (section == null) {
                return DEFAULTS;
            }
            Settings d = DEFAULTS;
            return new Settings(
                    section.getLong("base", d.base()),
                    section.getInt("max-level", d.maxLevel()),
                    section.getLong("score-divisor", d.scoreDivisor()),
                    section.getLong("minion-xp", d.minionXp()),
                    section.getLong("chapter-xp", d.chapterXp()),
                    section.getLong("season-point-xp", d.seasonPointXp()),
                    section.getLong("reward-per-level", d.rewardPerLevel()),
                    section.getInt("refresh-seconds", d.refreshSeconds()));
        }

        /** Nagroda za awans NA poziom {@code level}: {@code rewardPerLevel × level}. */
        public long rewardFor(int level) {
            return rewardPerLevel * level;
        }
    }

    /** Cztery składniki xp — to, co gracz widzi w menu „z czego się składa”. */
    public record Breakdown(long fromScore, long fromMinions, long fromChapter,
                            long fromSeasonPoints) {
        public long xp() {
            return fromScore + fromMinions + fromChapter + fromSeasonPoints;
        }
    }

    public static @NotNull Breakdown breakdown(@NotNull Settings settings, long totalScore,
                                               int minions, int chapterIndex, long seasonPoints) {
        return new Breakdown(
                Math.max(0L, totalScore) / settings.scoreDivisor(),
                Math.max(0, minions) * settings.minionXp(),
                Math.max(0, chapterIndex) * settings.chapterXp(),
                Math.max(0L, seasonPoints) * settings.seasonPointXp());
    }

    /** Ile xp wymaga poziom {@code level}; poziom 0 to 0 xp. */
    public static long xpForLevel(@NotNull Settings settings, int level) {
        if (level <= 0) {
            return 0L;
        }
        return Math.round(settings.base() * Math.pow(level, 1.5D));
    }

    /** Najwyższy poziom, którego próg mieści się w {@code xp}; obcięty do {@code maxLevel}. */
    public static int levelFor(@NotNull Settings settings, long xp) {
        int level = 0;
        while (level < settings.maxLevel() && xpForLevel(settings, level + 1) <= xp) {
            level++;
        }
        return level;
    }

    /**
     * Postęp 0..1 wewnątrz poziomu {@code level}. Poziom może być wyższy niż wynika
     * z xp (zapisany poziom nie spada), wtedy 0; na maksie zawsze 1.
     */
    public static double progress(@NotNull Settings settings, int level, long xp) {
        if (level >= settings.maxLevel()) {
            return 1.0D;
        }
        long floor = xpForLevel(settings, level);
        long ceiling = xpForLevel(settings, level + 1);
        if (xp <= floor) {
            return 0.0D;
        }
        if (xp >= ceiling) {
            return 1.0D;
        }
        return (double) (xp - floor) / (double) (ceiling - floor);
    }

    /** Brakujące xp do następnego poziomu; 0 na maksie albo gdy próg już osiągnięty. */
    public static long xpToNext(@NotNull Settings settings, int level, long xp) {
        if (level >= settings.maxLevel()) {
            return 0L;
        }
        return Math.max(0L, xpForLevel(settings, level + 1) - xp);
    }
}
