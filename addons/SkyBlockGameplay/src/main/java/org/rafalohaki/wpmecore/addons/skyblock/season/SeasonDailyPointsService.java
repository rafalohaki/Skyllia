package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * P1-1: dzienny kanał punktów sezonowych z dobowym capem.
 *
 * <p>Bez tego limitu ranking sezonowy rozstrzygała wyłącznie wędka (jedno
 * źródło bez końca). Każde źródło oddaje punkty tym kanałem, a cap kanału
 * ({@link SeasonDailyPointsCaps}) liczy się per gracz i dzień UTC: nadwyżka
 * ponad limit przepada, więc grind nie da się dalej opłacać w nieskończoność.
 * Brak sekcji capów = kanał bez limitu (zachowanie sprzed P1-1).
 *
 * <p>Punkty trafiają do puli sezonowej przez {@link SeasonPointService#award}
 * (anti-laundering: handel i aukcje nie mają tu wejścia) z identyfikatorem
 * {@code <kanał>:<dzień>:<operationId>} — powtórka po awarii jest cichym no-opem.
 */
public interface SeasonDailyPointsService {

    /** Punkty przyznane w tym dniu dla kanału (UTC). */
    @NotNull CompletableFuture<Long> pointsToday(@NotNull UUID playerUuid, @NotNull String channel);

    /**
     * Przyznaje min(prośba, reszta do capu) punktów i dopisuje je do puli sezonowej
     * przez SeasonPointService. Idempotentne po (uuid, dzień, kanał, operationId).
     *
     * @return ile realnie przyznano (0 = cap wyczerpany albo powtórka)
     */
    @NotNull CompletableFuture<Long> awardDaily(@NotNull UUID playerUuid, @NotNull String channel,
                                                long points, @NotNull String operationId);

    /** Domyślna implementacja: dzień UTC liczony z zegara systemowego. */
    static @NotNull SeasonDailyPointsService create(@NotNull SeasonDailyPointsDao dao,
                                                    @NotNull SeasonPointService seasonPoints,
                                                    @NotNull SeasonDailyPointsCaps caps) {
        return create(dao, seasonPoints, caps, Clock.systemUTC());
    }

    /** Wariant z wstrzykniętym zegarem — dzień rozliczeniowy to data UTC tego zegara. */
    static @NotNull SeasonDailyPointsService create(@NotNull SeasonDailyPointsDao dao,
                                                    @NotNull SeasonPointService seasonPoints,
                                                    @NotNull SeasonDailyPointsCaps caps,
                                                    @NotNull Clock clock) {
        return new Impl(dao, seasonPoints, caps, clock);
    }

    final class Impl implements SeasonDailyPointsService {

        /** Log kompensacji — klasa nie ma dostępu do pluginu (interfejs czysty). */
        private static final java.util.logging.Logger LOG =
                java.util.logging.Logger.getLogger(SeasonDailyPointsService.class.getName());

        private final SeasonDailyPointsDao dao;
        private final SeasonPointService seasonPoints;
        private final SeasonDailyPointsCaps caps;
        private final Clock clock;

        Impl(@NotNull SeasonDailyPointsDao dao, @NotNull SeasonPointService seasonPoints,
             @NotNull SeasonDailyPointsCaps caps, @NotNull Clock clock) {
            this.dao = dao;
            this.seasonPoints = seasonPoints;
            this.caps = caps;
            this.clock = clock;
        }

        @Override
        public @NotNull CompletableFuture<Long> pointsToday(@NotNull UUID playerUuid,
                                                            @NotNull String channel) {
            return dao.sumToday(playerUuid, today(), channel);
        }

        @Override
        public @NotNull CompletableFuture<Long> awardDaily(@NotNull UUID playerUuid,
                                                           @NotNull String channel,
                                                           long points,
                                                           @NotNull String operationId) {
            if (points <= 0) {
                return CompletableFuture.completedFuture(0L);
            }
            String day = today();
            return dao.sumToday(playerUuid, day, channel).thenCompose(today -> {
                long cap = caps.capFor(channel);
                long remaining = cap == 0 ? Long.MAX_VALUE : Math.max(0L, cap - today);
                long granted = Math.min(points, remaining);
                if (granted <= 0) {
                    return CompletableFuture.completedFuture(0L);
                }
                return dao.insertIfAbsent(playerUuid, day, channel, operationId, granted)
                        .thenCompose(inserted -> {
                            if (!inserted) {
                                return CompletableFuture.completedFuture(0L);
                            }
                            // quests_done liczy ukończone ZADANIA, nie każdą aktywność:
                            // kanał „quest" = ukończone zadanie dnia; „fish" i inne
                            // kanały dobowe dają punkty, ale nie pompują składnika
                            // rankingu wysp (zadania × 500). Bez tego każda ryba
                            // liczyła się jak quest i ranking de facto mierzył łowienie.
                            boolean countAsQuest = "quest".equals(channel);
                            return seasonPoints.award(playerUuid, granted,
                            // UWAGA: id operacji dla puli sezonowej MUSI nieść UUID
                            // gracza. `wpme_sb_reward_claims` ma GLOBALNY unikalny
                            // indeks na `operation_id` (ux_..._operation), więc id
                            // typu „kanał:dzień:zadanie" zajmowałby jeden wiersz dla
                            // całej sieci i drugi gracz nie dostałby nic (E2E
                            // 2026-09-10: pierwszy gracz 323 pkt, kolejni 0). Ta sama
                            // konwencja co w questach EcoQuests (`…:<uuid>`).
                            channel + ":" + day + ":" + playerUuid + ":" + operationId,
                            countAsQuest)
                                    .thenApply(applied -> {
                                        if (applied) {
                                            return granted;
                                        }
                                        /*
                                         * `addPoints` zwraca false TYLKO gdy operationId
                                         * jest już zużyty, czyli punkty są w puli —
                                         * wiersz dnia zostaje (limit policzony zgodnie
                                         * ze stanem puli). Wyjątek to inna sytuacja:
                                         * wiersz dnia już jest w bazie, a punktów nie ma,
                                         * więc bez kompensacji cap dobowy byłby zjedzony
                                         * na zawsze (audyt 2026-09-11, D-1).
                                         */
                                        return 0L;
                                    })
                                    .exceptionally(failure -> {
                                        dao.delete(playerUuid, day, channel, operationId)
                                                .exceptionally(cleanupFailure -> {
                                                    LOG.log(java.util.logging.Level.WARNING,
                                                            "Nie udalo sie cofnac wiersza dnia po nieudanym "
                                                                    + "dopisaniu punktow (" + channel + ", " + day + ")",
                                                            cleanupFailure);
                                                    return null;
                                                });
                                        return 0L;
                                    });
                        });
            });
        }

        /** Dzień rozliczeniowy kanału — data UTC. */
        private @NotNull String today() {
            return LocalDate.now(clock).toString();
        }
    }
}
