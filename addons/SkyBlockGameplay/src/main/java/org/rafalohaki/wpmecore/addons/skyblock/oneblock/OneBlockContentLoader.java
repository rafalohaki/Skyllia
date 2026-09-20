package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Loads oneblock.yml fail-closed (D8): every content error aborts startup with a
 * precise path instead of being silently patched at runtime.
 *
 * <p>Phase list entries are read as plain {@link Map}s from
 * {@link ConfigurationSection#getMapList(String)} and built directly —
 * round-tripping them through {@code createSection} would collapse all entries
 * onto a single overwritten path.
 */
public final class OneBlockContentLoader {

    private OneBlockContentLoader() {}

    public static @NotNull OneBlockContent load(@NotNull JavaPlugin plugin,
                                                @Nullable CustomItemService customItems) {
        File file = new File(plugin.getDataFolder(), "oneblock.yml");
        if (!file.isFile()) {
            plugin.saveResource("oneblock.yml", false);
        }
        return parse(YamlConfiguration.loadConfiguration(file), customItems);
    }

    static @NotNull OneBlockContent parse(@NotNull ConfigurationSection root,
                                          @Nullable CustomItemService customItems) {
        int schema = root.getInt("schema", -1);
        if (schema != 1) {
            throw fail("schema", "oczekiwano 1, było " + schema);
        }
        ConfigurationSection settingsSection = requiredSection(root, "settings");
        int checkpointEvery = positiveInt(settingsSection, "checkpoint-every", "settings.checkpoint-every");
        int mobWarningTicks = positiveInt(settingsSection, "mob-warning-ticks", "settings.mob-warning-ticks");
        // Domyślnie włączone: wejście w nowy rozdział to wydarzenie warte pochwalenia
        // się na serwerze — napędza społeczną pętlę postępu (jak broadcasty żył Kopacza).
        boolean broadcastPhases = settingsSection.getBoolean("broadcast-phases", true);

        List<Map<?, ?>> phases = root.getMapList("phases");
        if (phases.isEmpty()) {
            throw fail("phases", "lista rozdziałów nie może być pusta");
        }

        Set<String> seenIds = new HashSet<>();
        List<OneBlockContent.Chapter> chapters = new ArrayList<>(phases.size());
        int index = 0;
        for (Map<?, ?> phase : phases) {
            String path = "phases[" + index++ + "]";
            String id = requiredString(phase.get("id"), path + ".id");
            if (!seenIds.add(id)) {
                throw fail(path + ".id", "powtórzony identyfikator rozdziału '" + id + "'");
            }
            chapters.add(chapter(phase, path, id, customItems));
        }
        return new OneBlockContent(
                new OneBlockContent.Settings(checkpointEvery, mobWarningTicks, broadcastPhases), chapters);
    }

    private static OneBlockContent.Chapter chapter(@NotNull Map<?, ?> section, @NotNull String path,
                                                   @NotNull String id, @Nullable CustomItemService customItems) {
        String name = requiredString(section.get("name"), path + ".name");
        String subtitle = requiredString(section.get("subtitle"), path + ".subtitle");
        Material icon = blockMaterial(requiredString(section.get("icon"), path + ".icon"), path + ".icon");
        int blocksRequired = positiveInt(section.get("blocks-required"), path + ".blocks-required");

        Map<?, ?> blocksRaw = optionalMap(section, "blocks", path + ".blocks");
        if (blocksRaw.isEmpty()) {
            throw fail(path + ".blocks", "tabela bloków nie może być pusta");
        }
        List<OneBlockContent.WeightedMaterial> blocks = new ArrayList<>(blocksRaw.size());
        double totalWeight = 0.0;
        for (Map.Entry<?, ?> entry : blocksRaw.entrySet()) {
            String blockPath = path + ".blocks." + entry.getKey();
            Material material = blockMaterial(String.valueOf(entry.getKey()), blockPath);
            double weight = weight(entry.getValue(), blockPath);
            blocks.add(new OneBlockContent.WeightedMaterial(material, weight));
            totalWeight += weight;
        }
        if (totalWeight <= 0.0) {
            throw fail(path + ".blocks", "suma wag musi być większa od zera");
        }

        Map<Integer, Material> guaranteed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : optionalMap(section, "guaranteed", path + ".guaranteed").entrySet()) {
            String entryPath = path + ".guaranteed." + entry.getKey();
            guaranteed.put(positiveCounter(entry.getKey(), entryPath),
                    blockMaterial(stringValue(entry.getValue(), entryPath), entryPath));
        }

        Map<?, ?> bonusRaw = requiredMap(section, "bonus", path + ".bonus");
        String bonusItem = requiredString(bonusRaw.get("item"), path + ".bonus.item");
        if (customItems == null) {
            throw fail(path + ".bonus.item",
                    "usługa CustomItems nie jest dostępna (sprawdź kolejność ładowania pluginów)");
        }
        if (customItems.byId(bonusItem).isEmpty()) {
            throw fail(path + ".bonus.item", "nieznany identyfikator CustomItems '" + bonusItem + "'");
        }
        double chance = number(bonusRaw.get("chance"), path + ".bonus.chance");
        if (chance <= 0.0 || chance > 100.0) {
            throw fail(path + ".bonus.chance", "poza zakresem (0, 100]: " + chance);
        }
        int pity = positiveInt(bonusRaw.get("pity"), path + ".bonus.pity");

        Map<?, ?> mobsRaw = requiredMap(section, "mobs", path + ".mobs");
        double spawnChance = number(mobsRaw.get("spawn-chance"), path + ".mobs.spawn-chance");
        if (spawnChance < 0.0 || spawnChance > 100.0) {
            throw fail(path + ".mobs.spawn-chance", "poza zakresem [0, 100]: " + spawnChance);
        }
        List<OneBlockContent.WeightedEntity> table = new ArrayList<>();
        for (Map.Entry<?, ?> entry : optionalMap(mobsRaw, "table", path + ".mobs.table").entrySet()) {
            String entryPath = path + ".mobs.table." + entry.getKey();
            EntityType type = entityType(String.valueOf(entry.getKey()), entryPath);
            if (!type.isSpawnable()) {
                throw fail(entryPath, "encja nie jest spawnowalna: " + entry.getKey());
            }
            table.add(new OneBlockContent.WeightedEntity(type, weight(entry.getValue(), entryPath)));
        }

        Map<Integer, OneBlockContent.Milestone> milestones = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : optionalMap(section, "milestones", path + ".milestones").entrySet()) {
            String entryPath = path + ".milestones." + entry.getKey();
            int counter = positiveCounter(entry.getKey(), entryPath);
            if (!(entry.getValue() instanceof Map<?, ?> milestoneRaw)) {
                throw fail(entryPath, "oczekiwano sekcji");
            }
            milestones.put(counter, milestone(milestoneRaw, entryPath, customItems));
        }

        // Opcjonalny biom tła nieba/mgły rozdziału; pusty = nie ruszamy biomu
        // wyspy (jak było przed wprowadzeniem tej osłony). Walidacja tu, nie w runtime:
        // zły string musi wywrzeć start, nie sypać wyjątkiem przy każdej zmianie fazy.
        String biome = optionalBiome(section.get("biome"), path + ".biome");

        return new OneBlockContent.Chapter(id, name, subtitle, icon, blocksRequired, blocks, guaranteed,
                new OneBlockContent.Bonus(bonusItem, chance, pity),
                new OneBlockContent.Mobs(spawnChance, table), milestones, biome);
    }

    static @NotNull String optionalBiome(@Nullable Object raw, @NotNull String path) {
        if (raw == null) {
            return "";
        }
        String value = stringValue(raw, path).toUpperCase(Locale.ROOT);
        try {
            Biome.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw fail(path, "nieznany biom '" + value + "' (oczekiwano nazwy org.bukkit.block.Biome)");
        }
        return value;
    }

    private static OneBlockContent.Milestone milestone(@NotNull Map<?, ?> section, @NotNull String path,
                                                       @Nullable CustomItemService customItems) {
        String name = requiredString(section.get("name"), path + ".name");
        int rolls = section.get("rolls") == null ? 1
                : positiveInt(section.get("rolls"), path + ".rolls");
        Object rewardsRaw = section.get("rewards");
        if (!(rewardsRaw instanceof List<?> rewardList) || rewardList.isEmpty()) {
            throw fail(path + ".rewards", "lista nagród nie może być pusta");
        }
        List<OneBlockContent.MilestoneReward> rewards = new ArrayList<>(rewardList.size());
        int rewardIndex = 0;
        for (Object raw : rewardList) {
            String rewardPath = path + ".rewards[" + rewardIndex++ + "]";
            if (!(raw instanceof Map<?, ?> rewardMap)) {
                throw fail(rewardPath, "oczekiwano mapy {item|material, amount, weight}");
            }
            String item = optionalString(rewardMap.get("item"), rewardPath);
            String materialName = optionalString(rewardMap.get("material"), rewardPath);
            if (item != null && materialName != null) {
                throw fail(rewardPath, "nagroda ma jednocześnie item i material");
            }
            if (item == null && materialName == null) {
                throw fail(rewardPath, "nagroda nie ma ani item, ani material");
            }
            Material material = null;
            if (materialName != null) {
                material = Material.matchMaterial(materialName);
                if (material == null) {
                    throw fail(rewardPath, "nieznany materiał '" + materialName + "'");
                }
            } else if (customItems == null) {
                throw fail(rewardPath,
                        "usługa CustomItems nie jest dostępna (sprawdź kolejność ładowania pluginów)");
            } else if (customItems.byId(item).isEmpty()) {
                throw fail(rewardPath, "nieznany identyfikator CustomItems '" + item + "'");
            }
            int amount = rewardMap.get("amount") == null ? 1
                    : positiveInt(rewardMap.get("amount"), rewardPath);
            double weight = weight(rewardMap.get("weight"), rewardPath);
            rewards.add(new OneBlockContent.MilestoneReward(item, material, amount, weight));
        }
        return new OneBlockContent.Milestone(name, rolls, rewards);
    }

    private static @NotNull ConfigurationSection requiredSection(@NotNull ConfigurationSection parent,
                                                                @NotNull String key) {
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) {
            throw fail(key, "brak wymaganej sekcji");
        }
        return section;
    }

    private static int positiveInt(@NotNull ConfigurationSection section, @NotNull String key,
                                   @NotNull String path) {
        int value = section.getInt(key, -1);
        if (value <= 0) {
            throw fail(path, "musi być dodatnią liczbą całkowitą, było " + value);
        }
        return value;
    }

    private static int positiveInt(@Nullable Object value, @NotNull String path) {
        int parsed = (int) number(value, path);
        if (parsed <= 0) {
            throw fail(path, "musi być dodatnią liczbą całkowitą, było " + value);
        }
        return parsed;
    }

    private static int positiveCounter(@Nullable Object key, @NotNull String path) {
        if (key instanceof Number number) {
            int value = number.intValue();
            if (value > 0) {
                return value;
            }
        } else if (key instanceof String text) {
            try {
                int value = Integer.parseInt(text.trim());
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // fall through to fail
            }
        }
        throw fail(path, "klucz musi być dodatnią liczbą całkowitą, był '" + key + "'");
    }

    private static double number(@Nullable Object value, @NotNull String path) {
        if (!(value instanceof Number parsed)) {
            throw fail(path, "oczekiwano liczby, było '" + value + "'");
        }
        double parsedValue = parsed.doubleValue();
        if (!Double.isFinite(parsedValue)) {
            // NaN/Infinity mijają porównania zakresów (NaN <= x jest zawsze false).
            throw fail(path, "musi być liczbą skończoną, było " + parsedValue);
        }
        return parsedValue;
    }

    private static double weight(@Nullable Object value, @NotNull String path) {
        double parsed = number(value, path);
        if (parsed <= 0.0) {
            throw fail(path, "waga musi być liczbą > 0");
        }
        return parsed;
    }

    private static @NotNull Map<?, ?> requiredMap(@NotNull Map<?, ?> parent, @NotNull String key,
                                                  @NotNull String path) {
        Map<?, ?> section = optionalMap(parent, key, path);
        if (section.isEmpty()) {
            throw fail(path, "brak wymaganej sekcji");
        }
        return section;
    }

    private static @NotNull Map<?, ?> optionalMap(@NotNull Map<?, ?> parent, @NotNull String key,
                                                  @NotNull String path) {
        Object value = parent.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> section)) {
            throw fail(path, "oczekiwano sekcji");
        }
        return section;
    }

    private static @NotNull String requiredString(@Nullable Object value, @NotNull String path) {
        return stringValue(value, path);
    }

    private static @NotNull String stringValue(@Nullable Object value, @NotNull String path) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw fail(path, "brak wartości tekstowej, było '" + value + "'");
        }
        return text;
    }

    private static @Nullable String optionalString(@Nullable Object value, @NotNull String path) {
        if (value == null) {
            return null;
        }
        return stringValue(value, path);
    }

    private static @NotNull Material blockMaterial(@Nullable String name, @NotNull String path) {
        if (name == null) {
            throw fail(path, "brak materiału");
        }
        Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
        if (material == null || !material.isBlock()) {
            throw fail(path, "oczekiwano materiału-bloku, było '" + name + "'");
        }
        return material;
    }

    private static @NotNull EntityType entityType(@NotNull String name, @NotNull String path) {
        try {
            return EntityType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw fail(path, "nieznany typ encji '" + name + "'");
        }
    }

    private static IllegalArgumentException fail(@NotNull String path, @NotNull String reason) {
        return new IllegalArgumentException("oneblock.yml: " + path + ": " + reason);
    }
}
