package org.rafalohaki.wpmecore.addons.skyblock.automation;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record SellChestRecord(
        @NotNull UUID islandId,
        @NotNull String world,
        int x,
        int y,
        int z,
        @NotNull UUID ownerId,
        long createdAt
) {
    public record LocationKey(@NotNull String world, int x, int y, int z) {}

    public @NotNull LocationKey locationKey() {
        return new LocationKey(world, x, y, z);
    }
}
