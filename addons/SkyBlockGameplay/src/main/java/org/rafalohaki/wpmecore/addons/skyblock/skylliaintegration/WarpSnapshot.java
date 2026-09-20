package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record WarpSnapshot(@NotNull UUID islandId, @NotNull String name, @Nullable Location location) {}
