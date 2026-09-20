package org.rafalohaki.wpmecore.addons.skyblock.automation;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ChunkerDao {

    private final SqlService sql;

    public ChunkerDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    public @NotNull CompletableFuture<Void> create(@NotNull ChunkerRecord record) {
        return sql.update("""
                INSERT INTO wpme_sb_chunkers
                    (island_id, world, chunk_x, chunk_z, chest_x, chest_y, chest_z, owner_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.islandId().toString(),
                record.world(),
                record.chunkX(),
                record.chunkZ(),
                record.chestX(),
                record.chestY(),
                record.chestZ(),
                record.ownerId().toString()
        ).thenApply(ignored -> null);
    }

    public @NotNull CompletableFuture<Void> update(@NotNull ChunkerRecord record) {
        return sql.update("""
                UPDATE wpme_sb_chunkers SET
                    island_id = ?,
                    chest_x = ?,
                    chest_y = ?,
                    chest_z = ?,
                    owner_id = ?
                WHERE world = ? AND chunk_x = ? AND chunk_z = ?
                """,
                record.islandId().toString(),
                record.chestX(),
                record.chestY(),
                record.chestZ(),
                record.ownerId().toString(),
                record.world(),
                record.chunkX(),
                record.chunkZ()
        ).thenApply(ignored -> null);
    }

    public @NotNull CompletableFuture<Void> save(@NotNull ChunkerRecord record) {
        return sql.update("""
                INSERT INTO wpme_sb_chunkers
                    (island_id, world, chunk_x, chunk_z, chest_x, chest_y, chest_z, owner_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(world, chunk_x, chunk_z) DO UPDATE SET
                    island_id = excluded.island_id,
                    chest_x = excluded.chest_x,
                    chest_y = excluded.chest_y,
                    chest_z = excluded.chest_z,
                    owner_id = excluded.owner_id
                """,
                record.islandId().toString(),
                record.world(),
                record.chunkX(),
                record.chunkZ(),
                record.chestX(),
                record.chestY(),
                record.chestZ(),
                record.ownerId().toString()
        ).thenApply(ignored -> null);
    }

    public @NotNull CompletableFuture<List<ChunkerRecord>> loadAll() {
        return sql.query("""
                SELECT island_id, world, chunk_x, chunk_z, chest_x, chest_y, chest_z, owner_id
                FROM wpme_sb_chunkers
                """, rs -> new ChunkerRecord(
                UUID.fromString(rs.getString("island_id")),
                rs.getString("world"),
                rs.getInt("chunk_x"),
                rs.getInt("chunk_z"),
                rs.getInt("chest_x"),
                rs.getInt("chest_y"),
                rs.getInt("chest_z"),
                UUID.fromString(rs.getString("owner_id"))
        ));
    }

    public @NotNull CompletableFuture<List<ChunkerRecord>> findByIsland(@NotNull UUID islandId) {
        return sql.query("""
                SELECT island_id, world, chunk_x, chunk_z, chest_x, chest_y, chest_z, owner_id
                FROM wpme_sb_chunkers
                WHERE island_id = ?
                """, rs -> new ChunkerRecord(
                UUID.fromString(rs.getString("island_id")),
                rs.getString("world"),
                rs.getInt("chunk_x"),
                rs.getInt("chunk_z"),
                rs.getInt("chest_x"),
                rs.getInt("chest_y"),
                rs.getInt("chest_z"),
                UUID.fromString(rs.getString("owner_id"))
        ), islandId.toString());
    }

    public @NotNull CompletableFuture<Optional<ChunkerRecord>> findByChunk(
            @NotNull String world, int chunkX, int chunkZ) {
        return sql.queryOne("""
                SELECT island_id, world, chunk_x, chunk_z, chest_x, chest_y, chest_z, owner_id
                FROM wpme_sb_chunkers
                WHERE world = ? AND chunk_x = ? AND chunk_z = ?
                """, rs -> new ChunkerRecord(
                UUID.fromString(rs.getString("island_id")),
                rs.getString("world"),
                rs.getInt("chunk_x"),
                rs.getInt("chunk_z"),
                rs.getInt("chest_x"),
                rs.getInt("chest_y"),
                rs.getInt("chest_z"),
                UUID.fromString(rs.getString("owner_id"))
        ), world, chunkX, chunkZ);
    }

    public @NotNull CompletableFuture<Void> deleteByIsland(@NotNull UUID islandId) {
        return sql.update("""
                DELETE FROM wpme_sb_chunkers WHERE island_id = ?
                """, islandId.toString()).thenApply(ignored -> null);
    }

    public @NotNull CompletableFuture<Void> deleteByLocation(
            @NotNull String world, int chestX, int chestY, int chestZ) {
        return sql.update("""
                DELETE FROM wpme_sb_chunkers
                WHERE world = ? AND chest_x = ? AND chest_y = ? AND chest_z = ?
                """, world, chestX, chestY, chestZ).thenApply(ignored -> null);
    }

    public @NotNull CompletableFuture<Void> deleteByChunk(
            @NotNull String world, int chunkX, int chunkZ) {
        return sql.update("""
                DELETE FROM wpme_sb_chunkers
                WHERE world = ? AND chunk_x = ? AND chunk_z = ?
                """, world, chunkX, chunkZ).thenApply(ignored -> null);
    }
}
