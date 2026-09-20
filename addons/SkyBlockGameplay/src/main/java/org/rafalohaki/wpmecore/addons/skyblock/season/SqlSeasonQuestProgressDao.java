package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * M2.5b: implementacja postępu questów sezonowych na {@code SqlService}.
 *
 * <p>Tabela {@code wpme_sb_season_quest_progress} (migracja #6). Upsert bez
 * {@code ON CONFLICT DO UPDATE} — ten sam wzorzec update→insert→retry co
 * {@link SqlSeasonPointDao#addPoints}, żeby DDL pozostał przenośny.
 */
public final class SqlSeasonQuestProgressDao implements SeasonQuestProgressDao {

    private final org.rafalohaki.wpmecore.api.service.SqlService sql;

    public SqlSeasonQuestProgressDao(@NotNull org.rafalohaki.wpmecore.api.service.SqlService sql) {
        this.sql = sql;
    }

    private static boolean isConstraint(SQLException e) {
        // sqlite-jdbc nie ustawia SQLState dla naruszeń ograniczeń; rozpoznajemy
        // też kod 19 (SQLITE_CONSTRAINT) — jak w SqlSeasonPointDao.isConstraint.
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
    public @NotNull CompletableFuture<Map<UUID, Map<String, Integer>>> load(int seasonId) {
        return sql.query("""
                        SELECT player_uuid, quest_id, progress
                        FROM wpme_sb_season_quest_progress WHERE season_id = ?
                        """, rs -> Map.entry(UUID.fromString(rs.getString(1)),
                        Map.entry(rs.getString(2), rs.getInt(3))), seasonId)
                .thenApply(rows -> {
                    Map<UUID, Map<String, Integer>> loaded = new HashMap<>();
                    for (var row : rows) {
                        loaded.computeIfAbsent(row.getKey(), k -> new HashMap<>())
                                .put(row.getValue().getKey(), row.getValue().getValue());
                    }
                    return loaded;
                });
    }

    @Override
    public @NotNull CompletableFuture<Void> save(int seasonId, @NotNull UUID playerUuid,
                                                 @NotNull String questId, int progress) {
        long now = System.currentTimeMillis();
        return sql.withConnection(connection -> {
            try (PreparedStatement update = connection.prepareStatement("""
                            UPDATE wpme_sb_season_quest_progress
                            SET progress = ?, updated_at = ?
                            WHERE season_id = ? AND player_uuid = ? AND quest_id = ?
                            """)) {
                update.setInt(1, progress);
                update.setLong(2, now);
                update.setInt(3, seasonId);
                update.setString(4, playerUuid.toString());
                update.setString(5, questId);
                if (update.executeUpdate() > 0) return null;
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO wpme_sb_season_quest_progress
                                (season_id, player_uuid, quest_id, progress, updated_at)
                            VALUES (?, ?, ?, ?, ?)
                            """)) {
                insert.setInt(1, seasonId);
                insert.setString(2, playerUuid.toString());
                insert.setString(3, questId);
                insert.setInt(4, progress);
                insert.setLong(5, now);
                insert.executeUpdate();
            } catch (SQLException e) {
                if (!isConstraint(e)) throw e; // wyścig: wiersz wstawiony równolegle — dołóż update'em
                try (PreparedStatement retry = connection.prepareStatement("""
                                UPDATE wpme_sb_season_quest_progress
                                SET progress = ?, updated_at = ?
                                WHERE season_id = ? AND player_uuid = ? AND quest_id = ?
                                """)) {
                    retry.setInt(1, progress);
                    retry.setLong(2, now);
                    retry.setInt(3, seasonId);
                    retry.setString(4, playerUuid.toString());
                    retry.setString(5, questId);
                    retry.executeUpdate();
                }
            }
            return null;
        });
    }
}
