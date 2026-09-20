package org.rafalohaki.wpmecore.addons.skyblock.automation;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SellChestDao {

    private final SqlService sql;

    public SellChestDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    public @NotNull CompletableFuture<Void> create(@NotNull SellChestRecord record) {
        return sql.update("""
                INSERT INTO wpme_sb_sell_chests
                    (island_id, world, x, y, z, owner_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                record.islandId().toString(),
                record.world(),
                record.x(),
                record.y(),
                record.z(),
                record.ownerId().toString(),
                record.createdAt()
        ).thenApply(ignored -> null);
    }

    public @NotNull CompletableFuture<Void> update(@NotNull SellChestRecord record) {
        return sql.update("""
                UPDATE wpme_sb_sell_chests SET
                    island_id = ?,
                    owner_id = ?,
                    created_at = ?
                WHERE world = ? AND x = ? AND y = ? AND z = ?
                """,
                record.islandId().toString(),
                record.ownerId().toString(),
                record.createdAt(),
                record.world(),
                record.x(),
                record.y(),
                record.z()
        ).thenApply(ignored -> null);
    }

    public @NotNull CompletableFuture<Void> save(@NotNull SellChestRecord record) {
        return sql.update("""
                INSERT INTO wpme_sb_sell_chests
                    (island_id, world, x, y, z, owner_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(world, x, y, z) DO UPDATE SET
                    island_id = excluded.island_id,
                    owner_id = excluded.owner_id,
                    created_at = excluded.created_at
                """,
                record.islandId().toString(),
                record.world(),
                record.x(),
                record.y(),
                record.z(),
                record.ownerId().toString(),
                record.createdAt()
        ).thenApply(ignored -> null);
    }

    public @NotNull CompletableFuture<List<SellChestRecord>> loadAll() {
        return sql.query("""
                SELECT island_id, world, x, y, z, owner_id, created_at
                FROM wpme_sb_sell_chests
                """, rs -> new SellChestRecord(
                UUID.fromString(rs.getString("island_id")),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                UUID.fromString(rs.getString("owner_id")),
                rs.getLong("created_at")
        ));
    }

    public @NotNull CompletableFuture<List<SellChestRecord>> findByIsland(@NotNull UUID islandId) {
        return sql.query("""
                SELECT island_id, world, x, y, z, owner_id, created_at
                FROM wpme_sb_sell_chests
                WHERE island_id = ?
                """, rs -> new SellChestRecord(
                UUID.fromString(rs.getString("island_id")),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                UUID.fromString(rs.getString("owner_id")),
                rs.getLong("created_at")
        ), islandId.toString());
    }

    public @NotNull CompletableFuture<Optional<SellChestRecord>> findByLocation(
            @NotNull String world, int x, int y, int z) {
        return sql.queryOne("""
                SELECT island_id, world, x, y, z, owner_id, created_at
                FROM wpme_sb_sell_chests
                WHERE world = ? AND x = ? AND y = ? AND z = ?
                """, rs -> new SellChestRecord(
                UUID.fromString(rs.getString("island_id")),
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                UUID.fromString(rs.getString("owner_id")),
                rs.getLong("created_at")
        ), world, x, y, z);
    }

    public @NotNull CompletableFuture<Void> deleteByIsland(@NotNull UUID islandId) {
        return sql.update("""
                DELETE FROM wpme_sb_sell_chests WHERE island_id = ?
                """, islandId.toString()).thenApply(ignored -> null);
    }

    public @NotNull CompletableFuture<Void> deleteByLocation(
            @NotNull String world, int x, int y, int z) {
        return sql.update("""
                DELETE FROM wpme_sb_sell_chests
                WHERE world = ? AND x = ? AND y = ? AND z = ?
                """, world, x, y, z).thenApply(ignored -> null);
    }
}
