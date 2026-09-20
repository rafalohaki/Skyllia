package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Czysta matematyka poziomu: progi, monotoniczność, postęp 0..1, składniki. */
class IslandLevelTest {

    private static final IslandLevel.Settings S = IslandLevel.Settings.DEFAULTS;

    @Test
    void thresholdsFollowBaseTimesLevelToThePowerOfOnePointFive() {
        assertEquals(0L, IslandLevel.xpForLevel(S, 0));
        assertEquals(500L, IslandLevel.xpForLevel(S, 1));
        assertEquals(1414L, IslandLevel.xpForLevel(S, 2)); // 500 × 2^1.5 = 1414.2
        assertEquals(500_000L, IslandLevel.xpForLevel(S, 100));
        for (int level = 1; level <= S.maxLevel(); level++) {
            assertTrue(IslandLevel.xpForLevel(S, level) > IslandLevel.xpForLevel(S, level - 1),
                    "próg musi rosnąć z poziomem: " + level);
        }
    }

    @Test
    void levelForIsMonotonicAndExactAtThresholds() {
        assertEquals(0, IslandLevel.levelFor(S, 0L));
        assertEquals(0, IslandLevel.levelFor(S, 499L));
        assertEquals(1, IslandLevel.levelFor(S, 500L));
        assertEquals(1, IslandLevel.levelFor(S, 1413L));
        assertEquals(2, IslandLevel.levelFor(S, 1414L));
        assertEquals(100, IslandLevel.levelFor(S, 500_000L));
        assertEquals(100, IslandLevel.levelFor(S, Long.MAX_VALUE)); // maks obcina
        int previous = 0;
        for (long xp = 0L; xp <= 600_000L; xp += 997L) {
            int level = IslandLevel.levelFor(S, xp);
            assertTrue(level >= previous, "poziom nie może spaść przy rosnącym xp: " + xp);
            assertTrue(IslandLevel.xpForLevel(S, level) <= xp, "próg poziomu ≤ xp: " + xp);
            previous = level;
        }
    }

    @Test
    void progressStaysWithinZeroAndOne() {
        assertEquals(0.0D, IslandLevel.progress(S, 0, 0L));
        assertEquals(0.0D, IslandLevel.progress(S, 1, 500L));
        assertEquals(1.0D, IslandLevel.progress(S, 100, 0L)); // maks = pełny pasek
        assertEquals(0.0D, IslandLevel.progress(S, 5, 10L)); // zapisany poziom wyżej niż xp → 0, nie ujemne
        for (long xp = 0L; xp <= 520_000L; xp += 1_303L) {
            int level = IslandLevel.levelFor(S, xp);
            double progress = IslandLevel.progress(S, level, xp);
            assertTrue(progress >= 0.0D && progress <= 1.0D, "postęp poza 0..1 przy xp " + xp);
        }
        assertEquals(0L, IslandLevel.xpToNext(S, 100, 0L));
        assertEquals(500L, IslandLevel.xpToNext(S, 0, 0L));
        assertEquals(914L, IslandLevel.xpToNext(S, 1, 500L));
    }

    @Test
    void breakdownUsesConfiguredWeights() {
        IslandLevel.Breakdown b = IslandLevel.breakdown(S, 12_345L, 3, 2, 40L);
        assertEquals(123L, b.fromScore());
        assertEquals(750L, b.fromMinions());
        assertEquals(1_000L, b.fromChapter());
        assertEquals(80L, b.fromSeasonPoints());
        assertEquals(1_953L, b.xp());
        // ujemne wejścia (brak wyspy w rankingu, nieznana faza) nie psują sumy
        assertEquals(0L, IslandLevel.breakdown(S, -5L, -1, -1, -9L).xp());
        assertEquals(600L, S.rewardFor(3));
    }

    @Test
    void settingsRejectNonsense() {
        assertThrows(IllegalArgumentException.class,
                () -> new IslandLevel.Settings(0L, 100, 100L, 250L, 500L, 2L, 200L, 60));
        assertThrows(IllegalArgumentException.class,
                () -> new IslandLevel.Settings(500L, 0, 100L, 250L, 500L, 2L, 200L, 60));
        assertEquals(IslandLevel.Settings.DEFAULTS, IslandLevel.Settings.load(null));
    }
}
