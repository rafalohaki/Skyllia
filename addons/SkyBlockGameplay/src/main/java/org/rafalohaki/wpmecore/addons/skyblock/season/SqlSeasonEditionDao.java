package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Implementacja {@link SeasonEditionDao} na {@code SqlService} (migracja #7).
 * Append-only: kolizja PK (season_id, activated_at) = „już zapisane”,
 * klasyfikacja naruszenia jak w {@link SqlSeasonPointDao}.
 */
public final class SqlSeasonEditionDao implements SeasonEditionDao {

    private final SqlService sql;

    public SqlSeasonEditionDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    @Override
    public @NotNull CompletableFuture<Map<Integer, Row>> latestPerSeason() {
        return sql.withConnection(connection -> {
            // Sort ASC + fold: ostatni wiersz per sezon = najnowsza aktywacja.
            Map<Integer, Row> latest = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT season_id, activated_at, slug, display_name,
                           edition_type, start_date, end_date, closed_at
                    FROM wpme_sb_season_editions
                    ORDER BY season_id, activated_at
                    """);
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Row row = readRow(rs);
                    latest.put(row.seasonId(), row);
                }
            }
            return latest;
        });
    }

    @Override
    public @NotNull CompletableFuture<Void> recordActivation(int seasonId, @NotNull Edition e,
                                                             long nowMillis) {
        return sql.withConnection(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO wpme_sb_season_editions
                        (season_id, activated_at, slug, display_name, edition_type,
                         start_date, end_date, closed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
                    """)) {
                insert.setInt(1, seasonId);
                insert.setLong(2, nowMillis);
                insert.setString(3, e.slug());
                insert.setString(4, e.displayName());
                insert.setString(5, e.type().name());
                insert.setString(6, startDate(e));
                insert.setString(7, endDate(e));
                insert.executeUpdate();
            } catch (SQLException failure) {
                if (!SqlSupport.isConstraintViolation(failure)) {
                    throw failure;
                }
                // PK (season_id, activated_at) — ta sama aktywacja już odnotowana.
            }
            return null;
        });
    }

    @Override
    public @NotNull CompletableFuture<Integer> stampClosed(Connection tx, int seasonId,
                                                           long closedAtMillis) {
        // Wykonanie natychmiast na wątku wywołującego: UPDATE musi trafić do
        // TRANSAKCJI wywołującego zanim zrobi commit; wynik opakowujemy tylko
        // w CompletableFuture dla zgodności z interfejsem.
        try (PreparedStatement update = tx.prepareStatement("""
                UPDATE wpme_sb_season_editions
                SET closed_at = ?
                WHERE season_id = ? AND closed_at IS NULL
                """)) {
            update.setLong(1, closedAtMillis);
            update.setInt(2, seasonId);
            return CompletableFuture.completedFuture(update.executeUpdate());
        } catch (SQLException failure) {
            CompletableFuture<Integer> failed = new CompletableFuture<>();
            failed.completeExceptionally(failure);
            return failed;
        }
    }

    /** Dzień startowy edycji jako ISO YYYY-MM-DD (UTC — granica jest północna). */
    private static @NotNull String startDate(@NotNull Edition e) {
        return java.time.Instant.ofEpochMilli(e.startInclusiveMillis())
                .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
    }

    /** Dzień końcowy WŁĄCZNIE: granica półotwarta minus jeden dzień. */
    private static @NotNull String endDate(@NotNull Edition e) {
        return java.time.Instant.ofEpochMilli(e.endExclusiveMillis())
                .atZone(java.time.ZoneOffset.UTC).toLocalDate().minusDays(1).toString();
    }

    private static @NotNull Row readRow(@NotNull ResultSet rs) throws SQLException {
        long closedAt = rs.getLong(8);
        if (rs.wasNull()) {
            closedAt = 0L; // 0 = otwarta aktywacja
        }
        String rawType = rs.getString(5);
        final Edition.Type type;
        try {
            type = Edition.Type.valueOf(rawType.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException corruptRow) {
            // Fail-closed: wiersz z nieznanym typem nie może zasilać UI.
            throw new SQLException("wpme_sb_season_editions: nieznany edition_type '"
                    + rawType + "'", corruptRow);
        }
        return new Row(rs.getInt(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                type, rs.getString(6), rs.getString(7), closedAt);
    }
}
