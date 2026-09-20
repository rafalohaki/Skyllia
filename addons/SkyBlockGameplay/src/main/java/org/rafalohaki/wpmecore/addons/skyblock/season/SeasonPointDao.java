package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * M2.5: DAO punktów sezonowych. Uproszczony kontrakt na potrzeby serwisu —
 * SQL w jednym miejscu, transakcje po stronie implementacji.
 */
public interface SeasonPointDao {

    /** Atomowy przyrost punktów; zwraca false gdy operationId już zużyty (idempotencja). */
    @NotNull CompletableFuture<Boolean> addPoints(@NotNull UUID playerUuid, int seasonId,
                                                   long points, @NotNull String operationId,
                                                   boolean questCompletion);

    /** Suma punktów gracza w sezonie (0 gdy brak wiersza). */
    @NotNull CompletableFuture<Long> points(@NotNull UUID playerUuid, int seasonId);

    /** Liczba ukończonych questów sezonowych (do progresji progowej). */
    @NotNull CompletableFuture<Integer> questsDone(@NotNull UUID playerUuid, int seasonId);

    /** Pozycja w rankingu (1-based) przez COUNT punktów wyższych + 1. */
    @NotNull CompletableFuture<java.util.Optional<Integer>> rank(@NotNull UUID playerUuid, int seasonId);

    /** TOP-N: uuid, punkty. Nick dokłada warstwa wyżej. */
    @NotNull CompletableFuture<java.util.List<TopRow>> top(int seasonId, int limit);


    record TopRow(@NotNull UUID playerId, long points) { }
}
