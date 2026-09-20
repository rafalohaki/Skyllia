package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.entity.Display;
import org.jetbrains.annotations.NotNull;

public record LeaderboardBoard(@NotNull String id, boolean allTime, @NotNull String world,
                        double x, double y, double z, float yaw, float pitch,
                        @NotNull Display.Billboard billboard,
                        @NotNull String title,
                        @NotNull String metric) {

    public LeaderboardBoard(@NotNull String id, boolean allTime, @NotNull String world,
                            double x, double y, double z,
                            @NotNull String title, @NotNull String metric) {
        this(id, allTime, world, x, y, z, -90.0f, 0.0f, Display.Billboard.FIXED, title, metric);
    }
}
