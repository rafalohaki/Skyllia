package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MinionDao {

    private final SqlService sql;

    public MinionDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    /**
     * Atomowy insert z limitem: licznik i insert w jednym SQL, więc dwa
     * równoległe postawienia nie przekroczą limitu wyspy.
     */
    public @NotNull CompletableFuture<Boolean> tryInsertWithinLimit(
            @NotNull MinionRecord record, int maxPerIsland) {
        return sql.update("""
                INSERT INTO wpme_sb_minions
                    (minion_id, island_id, type_id, tier, world, x, y, z, storage_json,
                     fuel_type, fuel_expires_at, compactor_enabled,
                     linked_chest_x, linked_chest_y, linked_chest_z,
                     total_generated, created_at, updated_at)
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                WHERE (SELECT COUNT(*) FROM wpme_sb_minions WHERE island_id = ?) < ?
                """,
                record.minionId().toString(), record.islandId().toString(), record.typeId(),
                record.tier(), record.world(), record.x(), record.y(), record.z(),
                record.storageEncoded(), record.fuelType(), record.fuelExpiresAt(),
                record.compactorEnabled(), record.linkedChestX(), record.linkedChestY(),
                record.linkedChestZ(), record.totalGenerated(), record.createdAt(),
                record.updatedAt(), record.islandId().toString(), maxPerIsland
        ).thenApply(updated -> updated != null && updated >= 1);
    }

    public @NotNull CompletableFuture<Void> save(@NotNull MinionRecord record) {
        return sql.update("""
                INSERT INTO wpme_sb_minions
                    (minion_id, island_id, type_id, tier, world, x, y, z, storage_json,
                     fuel_type, fuel_expires_at, compactor_enabled,
                     linked_chest_x, linked_chest_y, linked_chest_z,
                     total_generated, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(minion_id) DO UPDATE SET
                    tier = excluded.tier,
                    storage_json = excluded.storage_json,
                    fuel_type = excluded.fuel_type,
                    fuel_expires_at = excluded.fuel_expires_at,
                    compactor_enabled = excluded.compactor_enabled,
                    linked_chest_x = excluded.linked_chest_x,
                    linked_chest_y = excluded.linked_chest_y,
                    linked_chest_z = excluded.linked_chest_z,
                    total_generated = excluded.total_generated,
                    updated_at = excluded.updated_at
                """,
                record.minionId().toString(), record.islandId().toString(), record.typeId(),
                record.tier(), record.world(), record.x(), record.y(), record.z(),
                record.storageEncoded(), record.fuelType(), record.fuelExpiresAt(),
                record.compactorEnabled(), record.linkedChestX(), record.linkedChestY(),
                record.linkedChestZ(), record.totalGenerated(), record.createdAt(),
                record.updatedAt()
        ).thenApply(ignored -> null);
    }

    public @NotNull CompletableFuture<List<MinionRecord>> loadAll() {
        return sql.query("SELECT * FROM wpme_sb_minions", MinionDao::mapRecord);
    }

    public @NotNull CompletableFuture<List<MinionRecord>> findByIsland(@NotNull UUID islandId) {
        return sql.query("SELECT * FROM wpme_sb_minions WHERE island_id = ?",
                MinionDao::mapRecord, islandId.toString());
    }

    public @NotNull CompletableFuture<Optional<MinionRecord>> findById(@NotNull UUID minionId) {
        return sql.queryOne("SELECT * FROM wpme_sb_minions WHERE minion_id = ?",
                MinionDao::mapRecord, minionId.toString());
    }

    public @NotNull CompletableFuture<Void> deleteById(@NotNull UUID minionId) {
        return sql.update("DELETE FROM wpme_sb_minions WHERE minion_id = ?",
                minionId.toString()).thenApply(ignored -> null);
    }

    public @NotNull CompletableFuture<Void> deleteByIsland(@NotNull UUID islandId) {
        return sql.update("DELETE FROM wpme_sb_minions WHERE island_id = ?",
                islandId.toString()).thenApply(ignored -> null);
    }

    private static @NotNull MinionRecord mapRecord(@NotNull ResultSet rs) throws SQLException {
        Map<String, Long> storage = MinionRecord.decodeStorage(rs.getString("storage_json"));
        return new MinionRecord(
                UUID.fromString(rs.getString("minion_id")),
                UUID.fromString(rs.getString("island_id")),
                rs.getString("type_id"),
                rs.getInt("tier"),
                rs.getString("world"),
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                storage,
                rs.getString("fuel_type"),
                rs.getLong("fuel_expires_at"),
                rs.getBoolean("compactor_enabled"),
                (Integer) rs.getObject("linked_chest_x"),
                (Integer) rs.getObject("linked_chest_y"),
                (Integer) rs.getObject("linked_chest_z"),
                rs.getLong("total_generated"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
