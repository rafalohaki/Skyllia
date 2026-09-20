package org.rafalohaki.wpmecore.addons.skyblock.season;

/**
 * M2: silnik progresji progów sezonu — czysta maszyna stanu (State pattern),
 * deterministyczna i symulowalna (replay z seedem po stronie testów).
 * Zasada działania: gracz zbiera punkty z questów; każdy próg z
 * {@code UNLOCK_THRESHOLDS} odblokowuje kolejną paczkę questów
 * (SLOTS_PER_TIER). Silnik nie wie nic o Bukkit/SQL — ewaluacja oddzielona
 * od efektów ubocznych.
 */
public final class SeasonTierEngine {

    private final long[] thresholds;
    private final int slotsPerTier;

    public SeasonTierEngine(long[] thresholds, int slotsPerTier) {
        if (thresholds == null || thresholds.length == 0) {
            throw new IllegalArgumentException("thresholds nie mogą być puste");
        }
        for (int i = 0; i < thresholds.length; i++) {
            if (i > 0 && thresholds[i] <= thresholds[i - 1]) {
                throw new IllegalArgumentException("progi muszą rosnąć ściśle");
            }
        }
        this.thresholds = thresholds.clone();
        this.slotsPerTier = slotsPerTier;
    }

    /** Ile paczek questów jest odblokowanych przy danej liczbie punktów. */
    public int unlockedTiers(long points) {
        int tiers = 0;
        for (long t : thresholds) {
            if (points >= t) tiers++;
            else break;
        }
        return tiers;
    }

    /** Liczba odblokowanych slotów questów (tiers × slots). */
    public int unlockedQuestSlots(long points) {
        return unlockedTiers(points) * slotsPerTier;
    }

    /**
     * Następny próg do zdobycia; Long.MAX_VALUE gdy wszystko odblokowane.
     */
    public long nextThreshold(long points) {
        for (long t : thresholds) {
            if (points < t) return t;
        }
        return Long.MAX_VALUE;
    }

    /** Postęp do następnego progu w przedziale [0,1); 1.0 gdy max tier. */
    public double progressToNext(long points) {
        long next = nextThreshold(points);
        if (next == Long.MAX_VALUE) return 1.0D;
        long prev = previousThreshold(points);
        return (double) (points - prev) / (next - prev);
    }

    private long previousThreshold(long points) {
        long prev = thresholds[0];
        for (long t : thresholds) {
            if (t <= points) prev = t;
            else break;
        }
        return prev;
    }
}
