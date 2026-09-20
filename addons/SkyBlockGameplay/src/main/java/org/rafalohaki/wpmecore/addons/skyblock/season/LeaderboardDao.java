package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Odczyt dorobku wszech czasów.
 *
 * <p>Sezony resetują się, wyspy nie — więc „ogólny" ranking nie może liczyć
 * bieżącego stanu banku, bo ten należy do trwającego sezonu. Liczy się historia:
 * ile razy gracz stanął na podium, zapisana w {@code wpme_sb_seasonal_claims}
 * przy odbiorze nagrody. To jedyna trwała pamięć zasług między sezonami.
 */
public final class LeaderboardDao {

    /** Surowy wiersz przed posortowaniem; sortujemy w Javie, bo reguła jest złożona. */
    record Achievement(String playerId, int wins, int podiums, int best) {
    }

    private final SqlService sql;

    public LeaderboardDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    @NotNull CompletableFuture<List<LeaderboardEntry>> allTime(int limit) {
        return sql.query("""
                SELECT player_id,
                       SUM(CASE WHEN rank_position = 1 THEN 1 ELSE 0 END) AS wins,
                       COUNT(*) AS podiums,
                       MIN(rank_position) AS best
                FROM wpme_sb_seasonal_claims
                GROUP BY player_id
                """, row -> new Achievement(row.getString(1), row.getInt(2),
                row.getInt(3), row.getInt(4))
        ).thenApply(rows -> rank(rows, limit));
    }

    /**
     * Zwycięstwo waży więcej niż liczba podiów, a przy remisie wygrywa lepsze
     * najlepsze miejsce. Ostatnie kryterium to identyfikator, żeby kolejność
     * była powtarzalna — inaczej tablica migotałaby przy każdym odświeżeniu.
     */
    static @NotNull List<LeaderboardEntry> rank(@NotNull List<Achievement> rows, int limit) {
        List<Achievement> valid = new ArrayList<>();
        for (Achievement row : rows) {
            if (row.playerId() != null && !row.playerId().isBlank() && row.podiums() > 0) {
                valid.add(row);
            }
        }
        valid.sort(Comparator.comparingInt(Achievement::wins).reversed()
                .thenComparing(Comparator.comparingInt(Achievement::podiums).reversed())
                .thenComparingInt(Achievement::best)
                .thenComparing(Achievement::playerId));

        List<LeaderboardEntry> out = new ArrayList<>();
        for (Achievement row : valid) {
            if (out.size() >= limit) {
                break;
            }
            UUID playerId;
            try {
                playerId = UUID.fromString(row.playerId());
            } catch (IllegalArgumentException malformed) {
                continue;
            }
            out.add(new LeaderboardEntry(out.size() + 1, playerId, "",
                    row.wins(),
                    row.wins() + "× 1. miejsce <dark_gray>•</dark_gray> "
                            + row.podiums() + "× podium"));
        }
        return List.copyOf(out);
    }
}
