package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.rafalohaki.wpmecore.addons.skyblock.shared.ConfigSchema;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Kolekcje kosmetyczne wczytane z {@code cosmetics.yml}.
 *
 * <p>Sezony są eventowe — resetują się, wyspy nie — więc każdy sezon ma
 * przypisaną jedną kolekcję, a wydany egzemplarz nosi znacznik sezonu i miejsca.
 * Dzięki temu „LIMITOWANA EDYCJA" z opisu przedmiotu znaczy coś sprawdzalnego.
 *
 * <p>Parsowanie jest fail-closed, jak w {@link ForgeConfig}: kolekcja
 * wskazująca identyfikator, którego nikt nie zdefiniował, zatrzymuje start
 * pluginu zamiast obiecywać przedmiot, którego nie da się wydać.
 */
public record CosmeticCatalog(boolean enabled,
                              @NotNull Map<Integer, String> seasonCollections,
                              @NotNull Map<Integer, Integer> topRewards,
                              @NotNull Map<String, Collection> collections) {

    private static final ConfigSchema SCHEMA = new ConfigSchema("cosmetics.yml");

    public record Collection(@NotNull String id, @NotNull String name, @NotNull String core,
                             int contentVersion, @NotNull List<String> pieces) { }

    /** Pusty katalog — używany, gdy kosmetyka jest wyłączona konfiguracją. */
    static @NotNull CosmeticCatalog disabled() {
        return new CosmeticCatalog(false, Map.of(), Map.of(), Map.of());
    }

    public @NotNull Optional<Collection> forSeason(int seasonId) {
        String id = seasonCollections.get(seasonId);
        return id == null ? Optional.empty() : Optional.ofNullable(collections.get(id));
    }

    /**
     * Które części kolekcji należą się za dane miejsce w sezonie.
     *
     * <p>Kolejność jest taka jak w konfiguracji, więc niższe miejsce dostaje
     * podzbiór tego, co wyższe — hełm i napierśnik są cenniejsze niż buty.
     */
    public @NotNull List<String> piecesFor(int seasonId, int rank) {
        Collection collection = forSeason(seasonId).orElse(null);
        int count = topRewards.getOrDefault(rank, 0);
        if (!enabled || collection == null || count <= 0) {
            return List.of();
        }
        return List.copyOf(collection.pieces()
                .subList(0, Math.min(count, collection.pieces().size())));
    }

    /** Sezon, do którego przypisana jest kolekcja — pusty, gdy do żadnego. */
    public @NotNull Optional<Integer> seasonOf(@NotNull String collectionId) {
        return seasonCollections.entrySet().stream()
                .filter(entry -> entry.getValue().equals(collectionId))
                .map(Map.Entry::getKey)
                .findFirst();
    }

    /**
     * Znacznik edycji dla {@code ItemPolicyMarkers}. Musi pasować do wzorca
     * {@code [A-Z0-9][A-Z0-9_.-]{0,31}}, inaczej oznaczenie rzuca wyjątkiem.
     */
    public static @NotNull String editionOf(int seasonId) {
        return "S" + seasonId;
    }

    /** Znacznik progu; ten sam wzorzec co edycja. */
    public static @NotNull String tierOf(int rank) {
        return "TOP" + rank;
    }

    public static @NotNull CosmeticCatalog load(@NotNull JavaPlugin plugin,
                                                @Nullable CustomItemService customItems) {
        File file = new File(plugin.getDataFolder(), "cosmetics.yml");
        if (!file.isFile()) {
            plugin.saveResource("cosmetics.yml", false);
        }
        return parse(YamlConfiguration.loadConfiguration(file), customItems);
    }

    static @NotNull CosmeticCatalog parse(@NotNull ConfigurationSection root,
                                          @Nullable CustomItemService customItems) {
        int schema = root.getInt("schema-version", -1);
        if (schema != 1) {
            throw SCHEMA.fail("schema-version", "oczekiwano 1, było " + schema);
        }
        if (!root.getBoolean("enabled", false)) {
            return disabled();
        }

        Map<String, Collection> collections = new LinkedHashMap<>();
        ConfigurationSection collectionsSection = SCHEMA.section(root, "collections");
        for (String id : collectionsSection.getKeys(false)) {
            collections.put(id, parseCollection(root, id, customItems));
        }

        Map<Integer, String> seasons = new LinkedHashMap<>();
        ConfigurationSection seasonSection = SCHEMA.section(root, "season-collections");
        for (String key : seasonSection.getKeys(false)) {
            int seasonId = positiveInt(key, "season-collections");
            String collectionId = seasonSection.getString(key, "");
            if (!collections.containsKey(collectionId)) {
                throw SCHEMA.fail("season-collections." + key,
                        "nieznana kolekcja '" + collectionId + "'");
            }
            seasons.put(seasonId, collectionId);
        }

        int longestCollection = collections.values().stream()
                .mapToInt(collection -> collection.pieces().size()).max().orElse(0);
        Map<Integer, Integer> rewards = new LinkedHashMap<>();
        ConfigurationSection rewardSection = SCHEMA.section(root, "top-rewards");
        for (String key : rewardSection.getKeys(false)) {
            int rank = positiveInt(key, "top-rewards");
            int count = rewardSection.getInt(key, 0);
            if (count <= 0) {
                throw SCHEMA.fail("top-rewards." + key, "musi być > 0");
            }
            if (count > longestCollection) {
                throw SCHEMA.fail("top-rewards." + key, "żadna kolekcja nie ma tylu części ("
                        + longestCollection + ")");
            }
            rewards.put(rank, count);
        }

        return new CosmeticCatalog(true, Map.copyOf(seasons), Map.copyOf(rewards),
                Map.copyOf(collections));
    }

    private static Collection parseCollection(ConfigurationSection root, String id,
                                              @Nullable CustomItemService customItems) {
        String path = "collections." + id;
        ConfigurationSection section = SCHEMA.section(root, path);

        String core = SCHEMA.string(section, path, "core");
        SCHEMA.requireKnownCustomItem(customItems, core, path + ".core");

        int contentVersion = section.getInt("content-version", 0);
        if (contentVersion < 1) {
            throw SCHEMA.fail(path + ".content-version", "musi być >= 1");
        }

        List<String> pieces = section.getStringList("pieces");
        if (pieces.isEmpty()) {
            throw SCHEMA.fail(path + ".pieces", "kolekcja bez części");
        }
        Set<String> unique = new LinkedHashSet<>();
        List<String> checked = new ArrayList<>();
        for (int index = 0; index < pieces.size(); index++) {
            String piece = pieces.get(index);
            if (!unique.add(piece)) {
                throw SCHEMA.fail(path + ".pieces[" + index + "]",
                        "część '" + piece + "' powtarza się w kolekcji");
            }
            SCHEMA.requireKnownCustomItem(customItems, piece, path + ".pieces[" + index + "]");
            checked.add(piece);
        }

        return new Collection(id, SCHEMA.string(section, path, "name"), core,
                contentVersion, List.copyOf(checked));
    }

    private static int positiveInt(String rawKey, String path) {
        int value;
        try {
            value = Integer.parseInt(rawKey);
        } catch (NumberFormatException notANumber) {
            throw SCHEMA.fail(path + "." + rawKey, "klucz musi być liczbą");
        }
        if (value <= 0) {
            throw SCHEMA.fail(path + "." + rawKey, "musi być > 0");
        }
        return value;
    }
}
