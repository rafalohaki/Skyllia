package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.rafalohaki.wpmecore.addons.skyblock.shared.ConfigSchema;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Materials;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Ładuje minions.yml fail-closed (wzorzec D8 z OneBlockContentLoader): każdy błąd
 * przerywa startup z precyzyjną ścieżką zamiast cichej naprawy w runtime.
 */
public record MinionsConfig(
        @NotNull Settings settings,
        @NotNull Map<String, FuelDef> fuels,
        @NotNull Map<Material, Material> compactMappings,
        @NotNull Map<String, TypeDef> minions) {

    private static final ConfigSchema SCHEMA = new ConfigSchema("minions.yml");

    public record Settings(int maxMinionsPerIsland, int tickIntervalSeconds,
                           int adjacentChestScanRadius, int defaultStorageSlots,
                           int slotsPerTierIncrease) { }

    /** Dokładnie jedno z item/customItemId jest nie-null. */
    public record FuelDef(@NotNull String id, @Nullable Material item,
                          @Nullable String customItemId, long durationSeconds,
                          double speedMultiplier) { }

    /**
     * Wygląd minionka: dokładnie jedno z {@code headTexture} / {@code headMaterial}.
     *
     * <p>Tekstura daje głowę-postać, ale wymaga prawdziwego hasza skina.
     * {@code headMaterial} to droga bez żadnego zewnętrznego źródła — minionek
     * wygląda wtedy jak unoszący się blok swojego surowca. Przejście z jednego
     * na drugie to zmiana w konfiguracji, bez przebudowy pluginu.
     */
    public record TypeDef(@NotNull String id, @NotNull String name,
                          @Nullable String headTexture, @Nullable Material headMaterial,
                          @NotNull Material primaryItem,
                          int primaryAmount, @NotNull Map<Integer, TierDef> tiers) {

        /** Materiał, z którego zbudować przedmiot minionka. */
        public @NotNull Material displayMaterial() {
            return headTexture != null ? Material.PLAYER_HEAD : Objects.requireNonNull(headMaterial);
        }
    }

    public record TierDef(int tier, double intervalSeconds, int storageSlots,
                          long upgradeCostMoney, int upgradeCostItems,
                          @Nullable String bonusDrop, double bonusChancePercent) { }

    public @Nullable TypeDef type(@NotNull String id) {
        return minions.get(id);
    }

    public @Nullable TierDef tier(@NotNull String typeId, int tier) {
        TypeDef def = minions.get(typeId);
        return def == null ? null : def.tiers().get(tier);
    }

    public static @NotNull MinionsConfig load(@NotNull JavaPlugin plugin,
                                              @Nullable CustomItemService customItems) {
        File file = new File(plugin.getDataFolder(), "minions.yml");
        if (!file.isFile()) {
            plugin.saveResource("minions.yml", false);
        }
        return parse(YamlConfiguration.loadConfiguration(file), customItems);
    }

    static @NotNull MinionsConfig parse(@NotNull ConfigurationSection root,
                                        @Nullable CustomItemService customItems) {
        int schema = root.getInt("schema-version", -1);
        if (schema != 1) {
            throw SCHEMA.fail("schema-version", "oczekiwano 1, było " + schema);
        }
        ConfigurationSection settingsSection = SCHEMA.section(root, "settings");
        Settings settings = new Settings(
                positiveInt(settingsSection, "max-minions-per-island"),
                positiveInt(settingsSection, "tick-interval-seconds"),
                positiveInt(settingsSection, "adjacent-chest-scan-radius"),
                positiveInt(settingsSection, "default-storage-slots"),
                Math.max(0, settingsSection.getInt("slots-per-tier-increase", 0)));

        Map<String, FuelDef> fuels = new LinkedHashMap<>();
        ConfigurationSection fuelsSection = SCHEMA.section(root, "fuels");
        for (String id : fuelsSection.getKeys(false)) {
            String path = "fuels." + id;
            ConfigurationSection fuel = SCHEMA.section(root, path);
            String vanillaItem = fuel.getString("item");
            String customItemId = fuel.getString("custom-item");
            if ((vanillaItem == null) == (customItemId == null)) {
                throw SCHEMA.fail(path, "paliwo musi mieć dokładnie jedno z: item / custom-item");
            }
            if (customItemId != null) {
                if (customItems == null || customItems.byId(customItemId).isEmpty()) {
                    throw SCHEMA.fail(path + ".custom-item", "nieznany identyfikator CustomItems '" + customItemId + "'");
                }
            }
            Material item = null;
            if (vanillaItem != null) {
                item = Material.matchMaterial(vanillaItem);
                if (item == null) {
                    throw SCHEMA.fail(path + ".item", "nieznany materiał '" + vanillaItem + "'");
                }
            }
            long duration = fuel.getLong("duration-seconds", 0L);
            if (duration <= 0L) {
                throw SCHEMA.fail(path + ".duration-seconds", "musi być > 0");
            }
            double multiplier = fuel.getDouble("speed-multiplier", 0.0);
            if (multiplier < 1.0) {
                throw SCHEMA.fail(path + ".speed-multiplier", "musi być >= 1.0");
            }
            fuels.put(id, new FuelDef(id, item, customItemId, duration, multiplier));
        }

        Map<Material, Material> compact = new LinkedHashMap<>();
        ConfigurationSection compactSection = SCHEMA.section(root, "compact-mappings");
        for (String from : compactSection.getKeys(false)) {
            Material source = Material.matchMaterial(from);
            if (source == null) {
                throw SCHEMA.fail("compact-mappings." + from, "nieznany materiał '" + from + "'");
            }
            Material target = Material.matchMaterial(String.valueOf(compactSection.get(from)));
            if (target == null) {
                throw SCHEMA.fail("compact-mappings." + from, "nieznany materiał docelowy");
            }
            compact.put(source, target);
        }

        Map<String, TypeDef> types = new LinkedHashMap<>();
        ConfigurationSection minionsSection = SCHEMA.section(root, "minions");
        for (String id : minionsSection.getKeys(false)) {
            String path = "minions." + id;
            ConfigurationSection type = SCHEMA.section(root, path);
            String name = SCHEMA.string(type, path, "name");
            String headTexture = type.getString("head-texture");
            String headMaterialKey = type.getString("head-material");
            boolean hasTexture = headTexture != null && !headTexture.isBlank();
            boolean hasMaterial = headMaterialKey != null && !headMaterialKey.isBlank();
            if (hasTexture == hasMaterial) {
                throw SCHEMA.fail(path,
                        "wygląd musi mieć dokładnie jedno z: head-texture / head-material");
            }
            Material headMaterial = null;
            if (hasMaterial) {
                headMaterial = SCHEMA.material(headMaterialKey, path + ".head-material");
            }
            Material primary = Material.matchMaterial(type.getString("primary-item", ""));
            if (primary == null || Materials.isAir(primary)) {
                throw SCHEMA.fail(path + ".primary-item", "nieznany materiał '" + type.getString("primary-item") + "'");
            }
            int primaryAmount = type.getInt("primary-amount", 0);
            if (primaryAmount <= 0) {
                throw SCHEMA.fail(path + ".primary-amount", "musi być > 0");
            }
            Map<Integer, TierDef> tiers = new LinkedHashMap<>();
            for (int tier = 1; tier <= 5; tier++) {
                ConfigurationSection tierSection = type.getConfigurationSection("tiers." + tier);
                if (tierSection == null) {
                    throw SCHEMA.fail(path + ".tiers." + tier, "brak definicji tieru (wymagane 1..5)");
                }
                double interval = tierSection.getDouble("interval", 0.0);
                if (interval <= 0.0) {
                    throw SCHEMA.fail(path + ".tiers." + tier + ".interval", "musi być > 0");
                }
                int storageSlots = tierSection.contains("storage-slots")
                        ? tierSection.getInt("storage-slots")
                        : settings.defaultStorageSlots() + settings.slotsPerTierIncrease() * (tier - 1);
                if (storageSlots <= 0) {
                    throw SCHEMA.fail(path + ".tiers." + tier + ".storage-slots", "musi być > 0");
                }
                ConfigurationSection cost = tierSection.getConfigurationSection("upgrade-cost");
                if (cost == null) {
                    throw SCHEMA.fail(path + ".tiers." + tier + ".upgrade-cost", "brak sekcji kosztu");
                }
                long money = Math.max(0L, cost.getLong("money", 0L));
                int items = Math.max(0, cost.getInt("items", 0));
                String bonusDrop = tierSection.getString("bonus-drop");
                double bonusChance = tierSection.getDouble("bonus-chance", 0.0);
                if (bonusDrop != null) {
                    if (customItems == null || customItems.byId(bonusDrop).isEmpty()) {
                        throw SCHEMA.fail(path + ".tiers." + tier + ".bonus-drop",
                                "nieznany identyfikator CustomItems '" + bonusDrop + "'");
                    }
                    if (bonusChance <= 0.0 || bonusChance > 100.0) {
                        throw SCHEMA.fail(path + ".tiers." + tier + ".bonus-chance", "poza zakresem (0, 100]");
                    }
                }
                tiers.put(tier, new TierDef(tier, interval, storageSlots, money, items, bonusDrop, bonusChance));
            }
            types.put(id, new TypeDef(id, name, hasTexture ? headTexture : null, headMaterial,
                    primary, primaryAmount, tiers));
        }
        return new MinionsConfig(settings, fuels, compact, types);
    }

    private static int positiveInt(@NotNull ConfigurationSection section, @NotNull String key) {
        int value = section.getInt(key, 0);
        if (value <= 0) {
            throw SCHEMA.fail(section.getCurrentPath() + "." + key, "musi być > 0");
        }
        return value;
    }
}
