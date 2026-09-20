package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class OneBlockMilestoneDao {

    public record MilestoneRow(@NotNull String milestoneKey, @NotNull String loot, long createdAt) {}

    private final SqlService sql;

    public OneBlockMilestoneDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    /**
     * @return {@code 1} when a fresh reward row was created, {@code 0} when the
     *         milestone already existed. Callers must not announce a reward on
     *         {@code 0}: a counter can reach the same milestone twice after a
     *         crash rewinds progress to the last checkpoint, and the announcement
     *         would promise loot that {@code DO NOTHING} never stored.
     */
    public @NotNull CompletableFuture<Integer> insertIfAbsent(@NotNull UUID islandId,
                                                              @NotNull String milestoneKey,
                                                              @NotNull String loot, long createdAt) {
        return sql.update("""
                INSERT INTO wpme_sb_oneblock_milestones
                    (island_id, milestone_key, loot, created_at, claimed_at)
                VALUES (?, ?, ?, ?, NULL)
                ON CONFLICT(island_id, milestone_key) DO NOTHING
                """,
                islandId.toString(), milestoneKey, loot, createdAt);
    }

    public @NotNull CompletableFuture<List<MilestoneRow>> listUnclaimed(@NotNull UUID islandId) {
        return sql.query("""
                SELECT milestone_key, loot, created_at
                FROM wpme_sb_oneblock_milestones
                WHERE island_id = ? AND claimed_at IS NULL
                ORDER BY created_at
                """, rs -> new MilestoneRow(
                rs.getString("milestone_key"), rs.getString("loot"), rs.getLong("created_at")),
                islandId.toString());
    }

    /** Atomic claim: 1 ⇒ this caller won the row, 0 ⇒ already claimed. */
    public @NotNull CompletableFuture<Integer> claim(@NotNull UUID islandId,
                                                     @NotNull String milestoneKey, long now) {
        return sql.update("""
                UPDATE wpme_sb_oneblock_milestones
                   SET claimed_at = ?
                 WHERE island_id = ? AND milestone_key = ? AND claimed_at IS NULL
                """, now, islandId.toString(), milestoneKey);
    }

    /** Undo a claim that could not be delivered (full inventory) — only our own timestamp. */
    public @NotNull CompletableFuture<Integer> revert(@NotNull UUID islandId,
                                                      @NotNull String milestoneKey, long claimedAt) {
        return sql.update("""
                UPDATE wpme_sb_oneblock_milestones
                   SET claimed_at = NULL
                 WHERE island_id = ? AND milestone_key = ? AND claimed_at = ?
                """, islandId.toString(), milestoneKey, claimedAt);
    }

    public @NotNull CompletableFuture<Void> deleteByIsland(@NotNull UUID islandId) {
        return sql.update("DELETE FROM wpme_sb_oneblock_milestones WHERE island_id = ?",
                islandId.toString()).thenApply(ignored -> null);
    }
}
