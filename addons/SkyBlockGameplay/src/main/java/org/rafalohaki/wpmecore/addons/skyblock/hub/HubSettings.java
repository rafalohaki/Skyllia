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
public record HubSettings(@NotNull SpawnPoint spawn, boolean teleportOnJoin,
                   int onboardingDelayTicks, boolean openMenuOnFirstJoin,
                   boolean openHelpDialogOnFirstJoin,
                   @NotNull List<JumpPadDefinition> jumpPads,
                   boolean protectSpawn, boolean disableHunger,
                   boolean invulnerable, boolean healOnEntry) {
    public HubSettings {
        jumpPads = List.copyOf(jumpPads);
    }
}
