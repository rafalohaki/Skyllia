package org.rafalohaki.wpmecore.addons.skyblock.automation;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record ChunkerRecord(
        @NotNull UUID islandId,
        @NotNull String world,
        int chunkX,
        int chunkZ,
        int chestX,
        int chestY,
        int chestZ,
        @NotNull UUID ownerId
) {
    public record ChunkKey(@NotNull String world, int chunkX, int chunkZ) {}

    public @NotNull ChunkKey chunkKey() {
        return new ChunkKey(world, chunkX, chunkZ);
    }

    public record LocationKey(@NotNull String world, int x, int y, int z) {}

    public @NotNull LocationKey chestLocationKey() {
        return new LocationKey(world, chestX, chestY, chestZ);
    }
}
