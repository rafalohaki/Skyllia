package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** M2.5: progi nagród końca sezonu i idempotentne operationId (spec §6). */
class SeasonEndServiceTest {

    @Test
    void top1GetsLargestReward() {
        var r = SeasonEndService.rewardForRank(1);
        assertEquals(50_000L, r.coins());
        assertEquals(10, r.lotus());
    }

    @Test
    void tierBoundariesAreInclusive() {
        assertEquals(30_000L, SeasonEndService.rewardForRank(2).coins(), "rank 2");
        assertEquals(30_000L, SeasonEndService.rewardForRank(3).coins(), "rank 3");
        assertEquals(15_000L, SeasonEndService.rewardForRank(4).coins(), "rank 4");
        assertEquals(15_000L, SeasonEndService.rewardForRank(10).coins(), "rank 10");
        assertEquals(5_000L, SeasonEndService.rewardForRank(11).coins(), "rank 11");
        assertEquals(5_000L, SeasonEndService.rewardForRank(50).coins(), "rank 50");
        assertEquals(2_500L, SeasonEndService.rewardForRank(51).coins(), "rank 51");
        assertEquals(2_500L, SeasonEndService.rewardForRank(100).coins(), "rank 100");
    }

    @Test
    void beyondTop100IsEmpty() {
        assertTrue(SeasonEndService.rewardForRank(101).isEmpty());
        assertTrue(SeasonEndService.rewardForRank(5_000).isEmpty());
    }

    @Test
    void rewardOperationIdIsDeterministicPerPlayerAndSeason() {
        var u = java.util.UUID.randomUUID();
        String a = SeasonEndService.rewardOperationId(4, u);
        String b = SeasonEndService.rewardOperationId(4, u);
        String c = SeasonEndService.rewardOperationId(5, u);
        assertEquals(a, b, "ta sama sezona+gracz = ten sam operationId");
        assertNotEquals(a, c, "inny sezon = inny operationId");
        assertTrue(a.startsWith("season-end:4:"));
    }

    @Test
    void participationRequiresAtLeastOnePoint() {
        assertTrue(SeasonEndService.qualifiesForParticipation(1L));
        assertFalse(SeasonEndService.qualifiesForParticipation(0L));
        assertEquals(500L, SeasonEndService.participationCoins());
    }
}
