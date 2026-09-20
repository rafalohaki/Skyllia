package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockTopRewardCoordinator.ClaimStatus;
import org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockTopRewardCoordinator.IslandScore;
import org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockTopRewardCoordinator.SeasonEligibility;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Kto ma prawo do nagrody sezonowej.
 *
 * <p>Sam warunek „miejsce w TOP 3" nie wystarcza, bo na starcie serwera każda
 * istniejąca wyspa jest w TOP 3 — z wynikiem zero. Limitowany komplet kosmetyki
 * sezonu wychodziłby wtedy w pierwszej godzinie, zanim ktokolwiek zdąży zagrać,
 * i nigdy już nie byłby limitowany.
 */
class SeasonEligibilityTest {

    private static IslandScore score(long total, int rank) {
        return new IslandScore(UUID.randomUUID(), total, 0, total, rank);
    }

    /** Ranking o zadanej liczbie wysp, gdzie tylko {@code scoring} pierwszych ma punkty. */
    private static List<IslandScore> ranking(int size, int scoring) {
        List<IslandScore> all = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            all.add(score(i <= scoring ? 10_000L - i : 0L, i));
        }
        return all;
    }

    private static final SeasonEligibility OPEN = new SeasonEligibility(true, 1L, 5);

    @Test
    void aRunningSeasonRefusesEveryClaim() {
        List<IslandScore> board = ranking(9, 9);

        assertEquals(ClaimStatus.SEASON_OPEN,
                SkyBlockTopRewardCoordinator.rejectClaim(
                        SeasonEligibility.CLOSED, board, board.getFirst()),
                "dopóki operator nie zamknie sezonu, nagroda nie może wyjść");
    }

    @Test
    void anIslandWithoutPointsIsNeverInTheTop() {
        List<IslandScore> board = ranking(9, 0);

        assertEquals(ClaimStatus.NOT_IN_TOP,
                SkyBlockTopRewardCoordinator.rejectClaim(OPEN, board, board.getFirst()),
                "zerowy dorobek nie jest zwycięstwem, nawet gdy nikt inny nie gra");
    }

    @Test
    void aRankingWithTooFewRealContendersCrownsNobody() {
        // Dziewięć wysp na liście, ale tylko dwie mają jakikolwiek dorobek.
        List<IslandScore> board = ranking(9, 2);

        assertEquals(ClaimStatus.RANKING_TOO_SMALL,
                SkyBlockTopRewardCoordinator.rejectClaim(OPEN, board, board.getFirst()),
                "próg liczy wyspy z punktami, bo puste konta łatwo założyć");
    }

    @Test
    void fourthPlaceStaysOut() {
        List<IslandScore> board = ranking(9, 9);

        assertEquals(ClaimStatus.NOT_IN_TOP,
                SkyBlockTopRewardCoordinator.rejectClaim(OPEN, board, board.get(3)),
                "bez progu (0) poza podium nie ma nagrody");
    }

    /** P2-4: próg sezonu — miejsce #4+ z wynikiem ≥ threshold-score przechodzi, poniżej nie. */
    @Test
    void thresholdLetsMidTableIslandsThrough() {
        SeasonEligibility withThreshold = new SeasonEligibility(true, 1L, 5, 9_990L);
        List<IslandScore> board = ranking(9, 9); // wyniki 9999, 9998, … 9991

        assertNull(SkyBlockTopRewardCoordinator.rejectClaim(withThreshold, board, board.get(3)),
                "#4 z 9996 ≥ 9990 — nagroda progu");
        assertNull(SkyBlockTopRewardCoordinator.rejectClaim(withThreshold, board, board.get(8)),
                "#9 z 9991 ≥ 9990 — próg nie patrzy na miejsce");
        assertEquals(ClaimStatus.NOT_IN_TOP,
                SkyBlockTopRewardCoordinator.rejectClaim(new SeasonEligibility(true, 1L, 5, 9_995L), board, board.get(5)),
                "#6 z 9994 < 9995 — poniżej progu");
        assertNull(SkyBlockTopRewardCoordinator.rejectClaim(withThreshold, board, board.getFirst()),
                "podium dalej przechodzi");
    }

    @Test
    void aRealWinnerOfAClosedSeasonIsLetThrough() {
        List<IslandScore> board = ranking(9, 9);

        assertNull(SkyBlockTopRewardCoordinator.rejectClaim(OPEN, board, board.getFirst()),
                "wyspa z dorobkiem, na podium, w rozstrzygniętym sezonie — nagroda należna");
    }

    @Test
    void anIslandOutsideTheRankingIsRefused() {
        assertEquals(ClaimStatus.NOT_IN_TOP,
                SkyBlockTopRewardCoordinator.rejectClaim(OPEN, ranking(9, 9), null));
    }

    @Test
    void theDefaultIsClosed() {
        assertEquals(false, SeasonEligibility.CLOSED.claimsOpen(),
                "brak sekcji w konfiguracji musi znaczyć 'zamknięte', nie 'otwarte'");
    }
}
