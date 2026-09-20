package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * M2.5: implementacja DAO punktów sezonowych na {@code SqlService}.
 * Idempotencja przez tabelę wpme_sb_reward_claims (operation_id unique) —
 * ten sam wzorzec co nagrody profilowe.
 */
public final class SqlSeasonPointDao implements SeasonPointDao {

    private final org.rafalohaki.wpmecore.api.service.SqlService sql;
    private final java.util.function.Supplier<Integer> seasonSupplier;

    public SqlSeasonPointDao(@NotNull org.rafalohaki.wpmecore.api.service.SqlService sql,
                             @NotNull java.util.function.Supplier<Integer> seasonSupplier) {
        this.sql = sql;
        this.seasonSupplier = seasonSupplier;
    }

    private static boolean isConstraint(SQLException e) {
        // sqlite-jdbc 3.53.x nie ustawia SQLState dla naruszeń ograniczeń
        // (getSQLState() == null); rozpoznajemy też kod 19 (SQLITE_CONSTRAINT)
        // i komunikat, żeby dedup działał na obu sterownikach (SQLite i PG).
        for (SQLException current = e; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if ((state != null && state.startsWith("23"))
                    || current.getErrorCode() == 19
                    || (current.getMessage() != null
                        && current.getMessage().contains("constraint failed"))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public @NotNull CompletableFuture<Boolean> addPoints(@NotNull UUID playerUuid, int seasonId,
                                                         long points, @NotNull String operationId,
                                                         boolean questCompletion) {
        return sql.withConnection(connection ->
                org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport.inTransaction(connection, () -> {
                    // 1. Idempotencja: claim operation_id w reward_claims
                    try (PreparedStatement claim = connection.prepareStatement("""
                            INSERT INTO wpme_sb_reward_claims (profile_id, player_uuid, reward_id, operation_id, claimed_at)
                            VALUES ('season-points', ?, ?, ?, ?)
                            """)) {
                        claim.setString(1, playerUuid.toString());
                        claim.setString(2, "season:" + seasonId + ":" + operationId);
                        claim.setString(3, operationId);
                        claim.setLong(4, System.currentTimeMillis());
                        try {
                            claim.executeUpdate();
                        } catch (SQLException e) {
                            if (isConstraint(e)) return false; // już rozliczone
                            throw e;
                        }
                    }
                    // 2. Upsert punktów
                    long now = System.currentTimeMillis();
                    try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE wpme_sb_season_points
                            SET points = points + ?, quests_done = quests_done + ?, updated_at = ?
                            WHERE season_id = ? AND player_uuid = ?
                            """)) {
                        update.setLong(1, points);
                        update.setInt(2, questCompletion ? 1 : 0);
                        update.setLong(3, now);
                        update.setInt(4, seasonId);
                        update.setString(5, playerUuid.toString());
                        if (update.executeUpdate() > 0) return true;
                    }
                    // 3. Brak wiersza → insert (konkurencja: constraint PK chroni)
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO wpme_sb_season_points (season_id, player_uuid, points, quests_done, updated_at)
                            VALUES (?, ?, ?, ?, ?)
                            """)) {
                        insert.setInt(1, seasonId);
                        insert.setString(2, playerUuid.toString());
                        insert.setLong(3, points);
                        insert.setInt(4, questCompletion ? 1 : 0);
                        insert.setLong(5, now);
                        insert.executeUpdate();
                    } catch (SQLException e) {
                        if (isConstraint(e)) { // wyścig: ktoś wstawił przed nami — dołóż punkty update'em
                            try (PreparedStatement retry = connection.prepareStatement("""
                                    UPDATE wpme_sb_season_points
                                    SET points = points + ?, quests_done = quests_done + ?, updated_at = ?
                                    WHERE season_id = ? AND player_uuid = ?
                                    """)) {
                                retry.setLong(1, points);
                                retry.setInt(2, questCompletion ? 1 : 0);
                                retry.setLong(3, System.currentTimeMillis());
                                retry.setInt(4, seasonId);
                                retry.setString(5, playerUuid.toString());
                                retry.executeUpdate();
                            }
                            return true;
                        }
                        throw e;
                    }
                    return true;
                }));
    }

    @Override
    public @NotNull CompletableFuture<Long> points(@NotNull UUID playerUuid, int seasonId) {
        return sql.queryOne("""
                        SELECT points FROM wpme_sb_season_points WHERE season_id = ? AND player_uuid = ?
                        """, rs -> rs.getLong(1), seasonId, playerUuid.toString())
                .thenApply(opt -> opt.orElse(0L));
    }

    @Override
    public @NotNull CompletableFuture<Integer> questsDone(@NotNull UUID playerUuid, int seasonId) {
        return sql.queryOne("""
                        SELECT quests_done FROM wpme_sb_season_points WHERE season_id = ? AND player_uuid = ?
                        """, rs -> rs.getInt(1), seasonId, playerUuid.toString())
                .thenApply(opt -> opt.orElse(0));
    }

    @Override
    public @NotNull CompletableFuture<Optional<Integer>> rank(@NotNull UUID playerUuid, int seasonId) {
        return sql.query("""
                        SELECT COUNT(*) FROM wpme_sb_season_points
                        WHERE season_id = ? AND points > (
                            SELECT COALESCE(MAX(points), 0) FROM wpme_sb_season_points
                            WHERE season_id = ? AND player_uuid = ?)
                        """, rs -> rs.getInt(1), seasonId, seasonId, playerUuid.toString())
                .thenCompose(rows -> {
                    int above = rows.isEmpty() ? 0 : rows.get(0);
                    if (above != 0) {
                        return CompletableFuture.completedFuture(Optional.of(above + 1));
                    }
                    // Brak punktów wyżej → dopiero tu rozstrzyga własny stan gracza.
                    // Blokujące points(...).join() w thenApply zakleszczało jednowątkowy
                    // egzekutor SQLite (Hikari pool=1 → 1 wątek Sql-*): zadanie points()
                    // czekało w kolejce za zadaniem, które samo trzymało wątek.
                    return points(playerUuid, seasonId).thenApply(ownPoints ->
                            ownPoints == 0
                                    ? Optional.<Integer>empty()
                                    : Optional.of(1));
                });
    }

    @Override
    public @NotNull CompletableFuture<List<TopRow>> top(int seasonId, int limit) {
        return sql.query("""
                        SELECT player_uuid, points FROM wpme_sb_season_points
                        WHERE season_id = ? ORDER BY points DESC, updated_at ASC LIMIT ?
                        """, rs -> new TopRow(UUID.fromString(rs.getString(1)), rs.getLong(2)),
                seasonId, limit);
    }

}
