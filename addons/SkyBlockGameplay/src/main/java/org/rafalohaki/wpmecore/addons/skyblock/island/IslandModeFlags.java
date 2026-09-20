package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

/**
 * Feature flags for three island modes. Only CLASSIC active by default.
 */
public final class IslandModeFlags {

    private final Map<IslandMode, Boolean> enabled = new EnumMap<>(IslandMode.class);

    public IslandModeFlags(@NotNull ConfigurationSection config) {
        // Defaults: classic true, others false. Config may override.
        for (IslandMode mode : IslandMode.values()) {
            boolean def = mode == IslandMode.CLASSIC;
            // Support both island.modes.classic and island.modes.classic.enabled
            String path = "island.modes." + mode.id();
            if (config.isBoolean(path)) {
                enabled.put(mode, config.getBoolean(path));
            } else if (config.isConfigurationSection(path)) {
                enabled.put(mode, config.getBoolean(path + ".enabled", def));
            } else if (config.isBoolean("island.modes." + mode.id() + ".enabled")) {
                enabled.put(mode, config.getBoolean("island.modes." + mode.id() + ".enabled", def));
            } else {
                // Also check flat island.modes section
                ConfigurationSection modes = config.getConfigurationSection("island.modes");
                if (modes != null && modes.isBoolean(mode.id())) {
                    enabled.put(mode, modes.getBoolean(mode.id()));
                } else if (modes != null && modes.isConfigurationSection(mode.id())) {
                    enabled.put(mode, modes.getConfigurationSection(mode.id()).getBoolean("enabled", def));
                } else {
                    enabled.put(mode, def);
                }
            }
        }
        // Enforce default: if config section missing entirely, classic true, others false already set.
        // But if modes section exists and classic not set, ensure true.
        if (!config.contains("island.modes")) {
            enabled.put(IslandMode.CLASSIC, true);
            enabled.put(IslandMode.ONEBLOCK, false);
            enabled.put(IslandMode.EXPEDITION, false);
        }
    }

    public IslandModeFlags(boolean classic, boolean oneblock, boolean expedition) {
        enabled.put(IslandMode.CLASSIC, classic);
        enabled.put(IslandMode.ONEBLOCK, oneblock);
        enabled.put(IslandMode.EXPEDITION, expedition);
    }

    public static @NotNull IslandModeFlags defaults() {
        return new IslandModeFlags(true, false, false);
    }

    public boolean isEnabled(@NotNull IslandMode mode) {
        return enabled.getOrDefault(mode, false);
    }

    public @NotNull Map<IslandMode, Boolean> asMap() {
        return Map.copyOf(enabled);
    }
}
