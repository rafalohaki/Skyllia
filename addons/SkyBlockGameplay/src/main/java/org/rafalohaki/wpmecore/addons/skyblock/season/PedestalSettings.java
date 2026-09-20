package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockTopRewardCoordinator;
import org.rafalohaki.wpmecore.addons.skyblock.quests.QuestCatalog;
import org.rafalohaki.wpmecore.addons.skyblock.shop.ShopCatalog;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
public record PedestalSettings(boolean enabled, @NotNull Material floor, @NotNull Material wall,
                        @NotNull Material accent, @NotNull Material light, int approach) {
    public static @NotNull PedestalSettings disabled() {
        return new PedestalSettings(false, Material.POLISHED_ANDESITE,
                Material.STONE_BRICKS, Material.CHISELED_STONE_BRICKS,
                Material.SEA_LANTERN, 1);
    }
}
