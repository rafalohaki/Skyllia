package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * M2.5: osobiste punkty sezonowe gracza (spec `docs/spec/sezony-rankingi.md` §3).
 *
 * <p>Punkty pochodzą wyłącznie z questów sezonowych i zdarzeń aktywności —
 * handel, aukcje i grind bloków nie dają punktów (anti-laundering). Punkty
 * są per gracz, nie per wyspa; resetują się co sezon przez snapshot do
 * {@code wpme_sb_season_history}.
 */
public interface SeasonPointService {

    /** Aktualny sezon (z wpme_sb_season_state przez coordinatora). */
    int currentSeason();

    /**
     * Przyznaje punkty za ukończony quest sezonowy. Idempotentne po
     * operationId — ponowne wywołanie z tym samym ID nie zmienia stanu.
     *
     * @return true gdy punkty zostały przyznane (pierwsze wykonanie)
     */
    @NotNull CompletableFuture<Boolean> award(@NotNull UUID playerUuid, long points,
                                              @NotNull String operationId);

    /**
     * Wariant z flagą {@code countAsQuest}: czy przyznanie podbija
     * {@code quests_done} (składnik rankingu wysp „zadania × 500”). Tylko
     * realne ukończenia zadań powinny go liczyć — aktywności typu wędka
     * dają punkty, ale nie są „ukończonym zadaniem”. Domyślnie zachowanie
     * sprzed flagi: każde przyznanie liczy jako quest.
     */
    default @NotNull CompletableFuture<Boolean> award(@NotNull UUID playerUuid, long points,
                                                      @NotNull String operationId,
                                                      boolean countAsQuest) {
        return award(playerUuid, points, operationId);
    }

    /** Suma punktów gracza w bieżącym sezonie. */
    @NotNull CompletableFuture<Long> pointsOf(@NotNull UUID playerUuid);

    /** Pozycja w rankingu sezonowym (1-based); pusta gdy brak punktów. */
    @NotNull CompletableFuture<java.util.Optional<Integer>> rankOf(@NotNull UUID playerUuid);

    /** TOP-N rankingu sezonowego (uuid, punkty); nick dokłada warstwa wyżej. */
    @NotNull CompletableFuture<java.util.List<SeasonPointDao.TopRow>> top(int limit);

    /**
     * Progresja odblokowań: ile questów sezonowych gracz ma otwartych.
     * Progi wg spec §2.1 — rozwinięte wyspy nie kończą sezonu w dzień.
     */
    int unlockedQuestSlots(long seasonPoints);

    /** Czy catch-up mnożnik (+20% końcówki sezonu) jest aktywny. */
    boolean isCatchUpActive();

    /** Czy bonus weekendowy (sob+nie) jest aktywny przy mnożniku > 1.0. */
    default boolean isWeekendBonusActive() {
        return false;
    }

    /** Skonfigurowany mnożnik weekendowy (1.0 = off); do komunikatów. */
    default double weekendMultiplier() {
        return 1.0D;
    }
}
