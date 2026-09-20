package org.rafalohaki.wpmecore.addons.skyblock.hub;

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
public record SpawnPoint(@NotNull String world, double x, double y, double z,
                  float yaw, float pitch) {
}
