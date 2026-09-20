package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Trwałość aktywacji nazwanych edycji (migracja #7: tabela
 * {@code wpme_sb_season_editions}). Wiersze są append-only:
 * PK (season_id, activated_at) czyni powtórny zapis bezbolesnym,
 * a {@code closed_at} znacznikiem domknięcia sezonu jest aktualizowany
 * w istniejącej transakcji rolloveru.
 */
public interface SeasonEditionDao {

    /** Najnowsza aktywacja edycji dla sezonu (snapshot do GUI i cache). */
    record Row(int seasonId, long activatedAt, String slug, String displayName,
               Edition.Type type, String startDate, String endDate,
               long closedAt /*0=open*/) { }

    /**
     * Najnowszy wiersz per sezon ({@code closed_at} może wskazywać 0 = otwarty).
     */
    @NotNull CompletableFuture<Map<Integer, Row>> latestPerSeason();

    /**
     * Idempotentne odnotowanie aktywacji edycji dla sezonu — kolizja PK
     * (ten sam season_id + activated_at) oznacza „już zapisane” i jest ignorowana.
     */
    @NotNull CompletableFuture<Void> recordActivation(int seasonId, @NotNull Edition e,
                                                      long nowMillis);

    /**
     * Domyka otwarte aktywacje sezonu wewnątrz TRANSAKCJI WYWOŁUJĄCEGO
     * (precedens {@code SqlSupport.inTransaction} w rolloverAndWipe).
     * Zwraca liczbę zaktualizowanych wierszy; nie commituje.
     *
     * <p><b>WIĄŻĄCY KONTRAKT WĄTKOWY (H1):</b> implementacja MUSI ukończyć
     * future synchronicznie na wątku wywołującym (completedFuture /
     * failedFuture z wartością policzoną przed zwrotem) — cała praca SQL dzieje
     * się na przekazanym {@code tx}, więc nie ma powodu odkładać jej na lane
     * asynchroniczny. Wywoławca ({@code SeasonEndService.rolloverAndWipe})
     * robi na wyniku blokujące {@code join()} WEWNĄTRZ otwartej transakcji
     * na wątku lane SQL: future, który nie jest zakończony w chwili zwrotu,
     * zdeadlockuje lane na zawsze (join czeka na pracę, której ten sam wątek
     * nigdy nie wykona, bo siedzi w joinie). Obrońca: wywoływca dodatkowo
     * owija join w {@code orTimeout(10s)}, żeby złamanie kontraktu kończyło się
     * głośnym wyjątkiem i rollbackiem, a nie wiszącym wątkiem.
     */
    @NotNull CompletableFuture<Integer> stampClosed(Connection tx, int seasonId,
                                                    long closedAtMillis);
}
