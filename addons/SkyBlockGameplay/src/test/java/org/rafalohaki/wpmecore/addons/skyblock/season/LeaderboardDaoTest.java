package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reguła kolejności hall of fame.
 *
 * <p>Kolejność musi być <b>całkowita i powtarzalna</b>: tablica odświeża się co
 * kilka minut, więc każdy remis rozstrzygnięty losowo objawiłby się graczom jako
 * migotanie miejsc bez żadnej zmiany w grze.
 */
class LeaderboardDaoTest {

    private static final String A = "00000000-0000-0000-0000-0000000000aa";
    private static final String B = "00000000-0000-0000-0000-0000000000bb";
    private static final String C = "00000000-0000-0000-0000-0000000000cc";

    private static LeaderboardDao.Achievement row(String id, int wins, int podiums, int best) {
        return new LeaderboardDao.Achievement(id, wins, podiums, best);
    }

    @Test
    void winsOutweighPodiumCount() {
        List<LeaderboardEntry> ranked = LeaderboardDao.rank(List.of(
                row(A, 1, 1, 1),
                row(B, 0, 9, 2)), 5);

        assertEquals(List.of(UUID.fromString(A), UUID.fromString(B)),
                ranked.stream().map(LeaderboardEntry::playerId).toList(),
                "jedno zwycięstwo waży więcej niż dziewięć drugich miejsc");
        assertEquals(List.of(1, 2), ranked.stream().map(LeaderboardEntry::rank).toList());
    }

    @Test
    void tiesFallBackToPodiumsThenBestPlaceThenIdentifier() {
        List<LeaderboardEntry> byPodiums = LeaderboardDao.rank(List.of(
                row(A, 2, 3, 1), row(B, 2, 5, 1)), 5);
        assertEquals(UUID.fromString(B), byPodiums.get(0).playerId(),
                "przy równych zwycięstwach decyduje liczba podiów");

        List<LeaderboardEntry> byBest = LeaderboardDao.rank(List.of(
                row(A, 0, 2, 3), row(B, 0, 2, 2)), 5);
        assertEquals(UUID.fromString(B), byBest.get(0).playerId(),
                "przy równych podiach decyduje najlepsze miejsce");

        // Identyczny dorobek: kolejność ma być stabilna, a nie przypadkowa.
        List<LeaderboardEntry> first = LeaderboardDao.rank(List.of(
                row(C, 1, 1, 1), row(A, 1, 1, 1), row(B, 1, 1, 1)), 5);
        List<LeaderboardEntry> again = LeaderboardDao.rank(List.of(
                row(B, 1, 1, 1), row(C, 1, 1, 1), row(A, 1, 1, 1)), 5);
        assertEquals(first.stream().map(LeaderboardEntry::playerId).toList(),
                again.stream().map(LeaderboardEntry::playerId).toList(),
                "ta sama treść w innej kolejności wejścia musi dać ten sam ranking");
    }

    @Test
    void limitAndMalformedRowsAreHonoured() {
        List<LeaderboardEntry> ranked = LeaderboardDao.rank(List.of(
                row(A, 5, 5, 1), row(B, 4, 4, 1), row(C, 3, 3, 1)), 2);
        assertEquals(2, ranked.size(), "limit obcina tablicę");

        List<LeaderboardEntry> filtered = LeaderboardDao.rank(List.of(
                row(null, 9, 9, 1),
                row("to-nie-uuid", 9, 9, 1),
                row(A, 0, 0, 0),
                row(B, 1, 1, 1)), 5);
        assertEquals(1, filtered.size(),
                "null, zepsuty identyfikator i zerowy dorobek wypadają: " + filtered);
        assertEquals(UUID.fromString(B), filtered.get(0).playerId());
        assertTrue(filtered.get(0).detail().contains("podium"));
    }
}
