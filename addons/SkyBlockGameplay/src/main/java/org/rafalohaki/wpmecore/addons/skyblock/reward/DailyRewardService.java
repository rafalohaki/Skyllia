package org.rafalohaki.wpmecore.addons.skyblock.reward;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.SkyBlockSettings;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Codzienna nagroda i seria logowań. Dzień = data UTC.
 *
 * <p>Wiersz w {@code wpme_sb_daily_reward} jest jedynym źródłem prawdy o
 * „odebrano dziś”: pierwszy odbiór to INSERT z ochroną przed duplikatem
 * klucza, kolejne to {@code UPDATE … WHERE last_claim_day <> dziś}. Każde z
 * nich wygrywa dokładnie raz, więc dwa równoległe wejścia nie wypłacą dwa razy.
 * Monety idą przez księgę z deterministycznym txId {@code daily:<uuid>:<dzień>}
 * — powtórka po awarii jest cichym no-opem po stronie księgi.
 */
public final class DailyRewardService {

    /** Co tyle dni serii dokłada się lotos (dzień 7, 14, 21…). */
    public static final int LOTUS_EVERY = 7;

    private static final String SELECT_SQL =
            "SELECT last_claim_day, streak, claimed_total FROM wpme_sb_daily_reward WHERE player_uuid = ?";
    private static final String INSERT_SQL = """
            INSERT INTO wpme_sb_daily_reward (player_uuid, last_claim_day, streak, claimed_total, updated_at)
            VALUES (?, ?, ?, 1, ?)
            """;
    private static final String UPDATE_SQL = """
            UPDATE wpme_sb_daily_reward
            SET last_claim_day = ?, streak = ?, claimed_total = claimed_total + 1, updated_at = ?
            WHERE player_uuid = ? AND last_claim_day <> ?
            """;

    public record Row(@NotNull String lastClaimDay, int streak, int claimedTotal) { }

    public record Claim(int streak, long coins, boolean lotus) { }

    private final SqlService sql;
    private final LedgerService ledger;
    private final SkyBlockSettings.DailyRewardSettings settings;

    public DailyRewardService(@NotNull SqlService sql, @NotNull LedgerService ledger,
                              @NotNull SkyBlockSettings.DailyRewardSettings settings) {
        this.sql = sql;
        this.ledger = ledger;
        this.settings = settings;
    }

    public @NotNull CompletableFuture<Optional<Row>> find(@NotNull UUID player) {
        return sql.queryOne(SELECT_SQL,
                rs -> new Row(rs.getString(1), rs.getInt(2), rs.getInt(3)), player.toString());
    }

    /**
     * Odbiór nagrody za {@code today}. Najpierw wiersz (idempotentnie), potem
     * monety — gdy wpłata padnie po zapisie wiersza, txId jest deterministyczny,
     * więc ręczne dosłanie nie podwoi kwoty.
     *
     * @return pusty, gdy dziś już odebrano (także gdy równoległy odbiór wygrał)
     */
    public @NotNull CompletableFuture<Optional<Claim>> claim(@NotNull UUID player,
                                                             @NotNull LocalDate today,
                                                             int bonusPercent) {
        return sql.withConnection(connection ->
                        SqlSupport.inTransaction(connection, () -> claimRow(connection, player, today)))
                .thenCompose(streak -> {
                    if (streak.isEmpty()) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    int reached = streak.getAsInt();
                    long coins = coinsFor(reached, bonusPercent);
                    return ledger.depositPlayer(player, coins, "daily:" + player + ":" + today,
                                    "Codzienna nagroda")
                            .thenApply(ignored -> Optional.of(
                                    new Claim(reached, coins, reached % LOTUS_EVERY == 0)));
                });
    }

    /** Seria po odbiorze w dniu {@code day}: wczoraj odebrane → +1, inaczej od nowa. */
    public static int nextStreak(@Nullable Row row, @NotNull LocalDate day) {
        return row != null && row.lastClaimDay().equals(day.minusDays(1).toString())
                ? row.streak() + 1
                : 1;
    }

    public long coinsFor(int streak, int bonusPercent) {
        long base = settings.baseCoins()
                + settings.perStreakDay() * Math.min(streak, settings.streakCap());
        return base + base * bonusPercent / 100L;
    }

    private OptionalInt claimRow(Connection connection, UUID player, LocalDate today)
            throws SQLException {
        String day = today.toString();
        long now = System.currentTimeMillis();
        Row row = null;
        try (PreparedStatement select = connection.prepareStatement(SELECT_SQL)) {
            select.setString(1, player.toString());
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    row = new Row(rs.getString(1), rs.getInt(2), rs.getInt(3));
                }
            }
        }
        int streak = nextStreak(row, today);
        if (row == null) {
            boolean inserted = SqlSupport.insertIgnoringConstraint(connection, INSERT_SQL,
                    "ON CONFLICT (player_uuid) DO NOTHING", statement -> {
                        statement.setString(1, player.toString());
                        statement.setString(2, day);
                        statement.setInt(3, streak);
                        statement.setLong(4, now);
                    });
            return inserted ? OptionalInt.of(streak) : OptionalInt.empty();
        }
        if (day.equals(row.lastClaimDay())) {
            return OptionalInt.empty();
        }
        try (PreparedStatement update = connection.prepareStatement(UPDATE_SQL)) {
            update.setString(1, day);
            update.setInt(2, streak);
            update.setLong(3, now);
            update.setString(4, player.toString());
            update.setString(5, day);
            return update.executeUpdate() == 1 ? OptionalInt.of(streak) : OptionalInt.empty();
        }
    }
}
