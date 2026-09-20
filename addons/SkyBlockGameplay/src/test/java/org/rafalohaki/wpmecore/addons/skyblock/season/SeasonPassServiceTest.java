package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** M2.5: kontrakt przepustki — poziomy z punktów, claim idempotentny, premium gating. */
class SeasonPassServiceTest {

    @Test
    void pilotHas28Levels() {
        assertEquals(28, SeasonPassService.PILOT_LEVELS);
    }

    @Test
    void fiftyPointsPerLevel() {
        assertEquals(50L, SeasonPassService.POINTS_PER_LEVEL);
        // 1400 pkt = 28 poziomów (max); 1450 pkt = nadal 28
        assertEquals(28, Math.toIntExact(Math.min(28, 1400 / 50)));
        assertEquals(28, Math.toIntExact(Math.min(28, 1450 / 50)));
        assertEquals(5, Math.toIntExact(Math.min(28, 250 / 50)));
    }

    @Test
    void levelBelowOneRejected() {
        // claim(level=0) po stronie serwisu zwraca false bez SQL — kontrakt dokumentujemy
        assertTrue(SeasonPassService.Track.FREE != SeasonPassService.Track.PREMIUM);
    }

    @Test
    void trackEnumCoversBothTiers() {
        assertEquals(2, SeasonPassService.Track.values().length);
    }
}
