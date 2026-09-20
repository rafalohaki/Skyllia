package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * P1-1: dzienny licznik punktów sezonowych per (gracz, dzień UTC, kanał).
 *
 * <p>Wiersz jest dowodem, że dana porcja punktów weszła do dobowej puli
 * kanału: PK {@code (player_uuid, day, channel, operation_id)} czyni powtórkę
 * tego samego zdarzenia w tym samym dniu no-opem, a nowy dzień to nowy wiersz.
 * Suma wierszy dnia jest podstawą capu w {@link SeasonDailyPointsService}.
 */
public interface SeasonDailyPointsDao {

    /** Suma punktów przyznanych w dniu {@code day} na kanale (0 gdy brak wierszy). */
    @NotNull CompletableFuture<Long> sumToday(@NotNull UUID playerUuid, @NotNull String day,
                                              @NotNull String channel);

    /**
     * Dopisuje wiersz, jeśli w tym dniu nie ma jeszcze wiersza o tym
     * {@code operationId} na tym kanale.
     *
     * @return false, gdy takie zdarzenie już zostało rozliczone (idempotencja)
     */
    @NotNull CompletableFuture<Boolean> insertIfAbsent(@NotNull UUID playerUuid, @NotNull String day,
                                                       @NotNull String channel,
                                                       @NotNull String operationId, long points);

    /**
     * Cofa wiersz dnia wstawiony przez {@link #insertIfAbsent} — kompensacja po
     * nieudanym dopisaniu punktów do puli sezonowej.
     *
     * <p>Bez tego wiersz dnia zostawał i liczył się do capu dobowego
     * ({@code sumToday}), mimo że punkty nigdy nie trafiły do puli: gracz tracił
     * fragment dziennego limitu do końca dnia, a powtórka tego samego zadania
     * dostawała {@code false} z {@code insertIfAbsent} (wiersz już jest).
     * Usuwamy tylko wiersz o dokładnie tym {@code operationId} — cudzych nie ruszamy.
     */
    @NotNull CompletableFuture<Void> delete(@NotNull UUID playerUuid, @NotNull String day,
                                            @NotNull String channel, @NotNull String operationId);

    /**
     * Implementacja na {@link SqlService} — mieszka w pliku kontraktu, bo DAO
     * ma dokładnie dwa zapytania na jednej tabeli. Transakcja i
     * {@code INSERT OR IGNORE} (na PostgreSQL {@code ON CONFLICT DO NOTHING})
     * idą wspólnym {@link SqlSupport}, więc powtórka nie dubluje wiersza.
     */
    final class Sql implements SeasonDailyPointsDao {

        private final SqlService sql;
        private final Supplier<Integer> seasonSupplier;

        public Sql(@NotNull SqlService sql, @NotNull Supplier<Integer> seasonSupplier) {
            this.sql = sql;
            this.seasonSupplier = seasonSupplier;
        }

        @Override
        public @NotNull CompletableFuture<Long> sumToday(@NotNull UUID playerUuid,
                                                         @NotNull String day,
                                                         @NotNull String channel) {
            return sql.queryOne("""
                            SELECT COALESCE(SUM(points), 0) FROM wpme_sb_season_daily_points
                            WHERE player_uuid = ? AND day = ? AND channel = ?
                            """, rs -> rs.getLong(1), playerUuid.toString(), day, channel)
                    .thenApply(total -> total.orElse(0L));
        }

        @Override
        public @NotNull CompletableFuture<Boolean> insertIfAbsent(@NotNull UUID playerUuid,
                                                                  @NotNull String day,
                                                                  @NotNull String channel,
                                                                  @NotNull String operationId,
                                                                  long points) {
            int seasonId = seasonSupplier.get();
            long now = System.currentTimeMillis();
            return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () ->
                    SqlSupport.insertIgnoringConstraint(connection, """
                            INSERT INTO wpme_sb_season_daily_points
                                (season_id, player_uuid, day, channel, operation_id, points, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """, "ON CONFLICT (player_uuid, day, channel, operation_id) DO NOTHING",
                            statement -> {
                                statement.setInt(1, seasonId);
                                statement.setString(2, playerUuid.toString());
                                statement.setString(3, day);
                                statement.setString(4, channel);
                                statement.setString(5, operationId);
                                statement.setLong(6, points);
                                statement.setLong(7, now);
                            })));
        }

        @Override
        public @NotNull CompletableFuture<Void> delete(@NotNull UUID playerUuid, @NotNull String day,
                                                       @NotNull String channel,
                                                       @NotNull String operationId) {
            return sql.update("""
                            DELETE FROM wpme_sb_season_daily_points
                            WHERE player_uuid = ? AND day = ? AND channel = ? AND operation_id = ?
                            """,
                    playerUuid.toString(), day, channel, operationId)
                    .thenApply(ignored -> null);
        }
    }
}
