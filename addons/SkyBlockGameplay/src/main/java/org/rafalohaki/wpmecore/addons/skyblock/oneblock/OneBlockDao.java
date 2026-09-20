package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class OneBlockDao {

    private final SqlService sql;
    private final List<String> phaseIds;

    public OneBlockDao(@NotNull SqlService sql, @NotNull List<String> phaseIds) {
        this.sql = sql;
        this.phaseIds = List.copyOf(phaseIds);
    }

    public @NotNull CompletableFuture<List<OneBlockState>> loadAll() {
        return sql.query("""
                SELECT island_id, world_name, x, y, z, phase_id, progress, total_mined, dry_streak
                FROM wpme_sb_oneblock
                """, OneBlockDao::mapState);
    }

    public @NotNull CompletableFuture<Optional<OneBlockState>> findByIsland(@NotNull UUID islandId) {
        return sql.queryOne("""
                SELECT island_id, world_name, x, y, z, phase_id, progress, total_mined, dry_streak
                FROM wpme_sb_oneblock
                WHERE island_id = ?
                """, OneBlockDao::mapState, islandId.toString());
    }

    private static OneBlockState mapState(ResultSet rs) throws SQLException {
        return new OneBlockState(
                UUID.fromString(rs.getString("island_id")),
                rs.getString("world_name"),
                rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                rs.getString("phase_id"),
                rs.getInt("progress"),
                rs.getInt("total_mined"),
                rs.getInt("dry_streak"));
    }

    public @NotNull CompletableFuture<Void> save(@NotNull OneBlockState state) {
        // The v3 DDL keeps the legacy numeric phase column NOT NULL without a
        // default, so every INSERT must still provide it. Runtime progression
        // itself reads/writes phase_id only (§5.1); the number written here is
        // derived from the chapter order so old plugin versions keep working
        // after a rollback. Unknown ids clamp onto the first chapter.
        int legacyPhase = legacyPhaseNumber(state.phaseId());
        return sql.update("""
                INSERT INTO wpme_sb_oneblock
                    (island_id, world_name, x, y, z, phase_id, progress, total_mined, dry_streak, phase)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(island_id) DO UPDATE SET
                    world_name = excluded.world_name,
                    x = excluded.x, y = excluded.y, z = excluded.z,
                    phase_id = excluded.phase_id,
                    progress = excluded.progress,
                    total_mined = excluded.total_mined,
                    dry_streak = excluded.dry_streak,
                    phase = excluded.phase
                """,
                state.islandId().toString(), state.worldName(),
                state.x(), state.y(), state.z(),
                state.phaseId(), state.progress(), state.totalMined(), state.dryStreak(),
                legacyPhase
        ).thenApply(ignored -> null);
    }

    /** 1-based chapter number for the legacy {@code phase} column, kept in [1, size]. */
    private int legacyPhaseNumber(@NotNull String phaseId) {
        int number = Math.max(1, phaseIds.indexOf(phaseId) + 1);
        return Math.min(number, Math.max(1, phaseIds.size()));
    }

    public @NotNull CompletableFuture<Void> deleteByIsland(@NotNull UUID islandId) {
        return sql.update("""
                DELETE FROM wpme_sb_oneblock WHERE island_id = ?
                """, islandId.toString()).thenApply(ignored -> null);
    }
}
