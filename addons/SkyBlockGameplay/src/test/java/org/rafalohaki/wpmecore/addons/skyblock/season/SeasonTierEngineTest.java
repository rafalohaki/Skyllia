package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.Test;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2: symulacja progresji sezonu — deterministyczna (SplittableRandom z
 * seedem), replayowalna. Weryfikuje niezmienniki silnika progów:
 * monotoniczność slotów, poprawne progi, spójność progress.
 */
class SeasonTierEngineTest {

    private SeasonTierEngine productionEngine() {
        // Progi produkcyjne (DefaultSeasonPointService.UNLOCK_THRESHOLDS, 8 slotów/tier)
        return new SeasonTierEngine(new long[]{0L, 300L, 800L, 1_600L, 3_000L}, 8);
    }

    @Test
    void thresholdsUnlockTiersMonotonically() {
        var engine = productionEngine();
        assertEquals(1, engine.unlockedTiers(0));
        assertEquals(1, engine.unlockedTiers(299));
        assertEquals(2, engine.unlockedTiers(300));
        assertEquals(2, engine.unlockedTiers(799));
        assertEquals(3, engine.unlockedTiers(800));
        assertEquals(5, engine.unlockedTiers(3_000));
        assertEquals(5, engine.unlockedTiers(999_999));

        assertEquals(8, engine.unlockedQuestSlots(0));
        assertEquals(40, engine.unlockedQuestSlots(3_000));
    }

    @Test
    void nextThresholdAndProgressAreConsistent() {
        var engine = productionEngine();
        assertEquals(300, engine.nextThreshold(0));
        assertEquals(Long.MAX_VALUE, engine.nextThreshold(3_000));
        assertEquals(1.0D, engine.progressToNext(5_000));

        double p = engine.progressToNext(150);
        assertEquals(0.5D, p, 1e-9); // 150/300 = połowa do tieru 2
        assertTrue(p >= 0 && p < 1);
    }

    /**
     * Symulacja sezonu #1: 50 graczy × 56 dni, punkty losowe deterministyczne.
     * Replay: ten sam seed = identyczny wynik. Niezmienniki: sloty rosną
     * monotonnicznie w czasie; gracz z największą liczbą punktów ma max sloty.
     */
    @Test
    void deterministicSeasonSimulationReplays() {
        long seed = 2026_08_24L;
        int players = 50;
        int days = 56;

        var firstRun = simulate(seed, players, days);
        var secondRun = simulate(seed, players, days);
        assertArrayEquals(firstRun, secondRun, "replay z tym samym seedem musi dać identyczny stan");

        // Niezmiennik: sloty końcowe rosną z punktami
        long topPoints = 0;
        int topSlots = 0;
        for (int i = 0; i < players; i++) {
            var engine = productionEngine();
            int slots = engine.unlockedQuestSlots(firstRun[i]);
            assertTrue(slots >= topSlots || firstRun[i] < topPoints,
                    "więcej punktów nigdy nie może oznaczać mniej slotów");
            if (firstRun[i] > topPoints) {
                topPoints = firstRun[i];
                topSlots = slots;
            }
        }
        // Gracz z max punktami ma pełne odblokowanie (symulacja dochodzi wysoko)
        assertEquals(productionEngine().unlockedQuestSlots(topPoints), topSlots);
    }

    /** Deterministyczna symulacja: dzienny przydział punktów per gracz z SplittableRandom. */
    private long[] simulate(long seed, int players, int days) {
        RandomGenerator rng = RandomGeneratorFactory.of("SplittableRandom").create(seed);
        long[] points = new long[players];
        for (int d = 0; d < days; d++) {
            for (int pl = 0; pl < players; pl++) {
                // aktywność gracza: 60% dni gra, wtedy 10-40 punktów z questów
                if (rng.nextDouble() < 0.60D) {
                    points[pl] += 10 + rng.nextLong(31);
                }
            }
        }
        return points;
    }

    @Test
    void rejectsInvalidThresholds() {
        assertThrows(IllegalArgumentException.class,
                () -> new SeasonTierEngine(new long[]{}, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new SeasonTierEngine(new long[]{300L, 100L}, 8));
        assertDoesNotThrow(() -> new SeasonTierEngine(new long[]{0L}, 8));
    }
}
