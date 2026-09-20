package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * M2.5b: trwały postęp questów sezonowych — liczniki per gracz per sezon.
 *
 * <p>Punkty i dedup nagród mają swoje trwałe tabele ({@code wpme_sb_season_points},
 * {@code wpme_sb_reward_claims}); ta warstwa przenosi wyłącznie liczniki postępu,
 * żeby restart serwera nie zerował drążków questowych.
 */
public interface SeasonQuestProgressDao {

    /**
     * Cały zapis postępu dla danego sezonu (load on enable).
     * Brak wierszy = pusta mapa, nie błąd.
     */
    @NotNull CompletableFuture<Map<UUID, Map<String, Integer>>> load(int seasonId);

    /** Zapis pojedynczego licznika po przyroście (upsert, async jak reszta DAO). */
    @NotNull CompletableFuture<Void> save(int seasonId, @NotNull UUID playerUuid,
                                          @NotNull String questId, int progress);
}
