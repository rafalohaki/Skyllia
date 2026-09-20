package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.api.item.CustomItem;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped {@code minions.yml} names CustomItems ids that live in another
 * module. Nothing used to compare the two files, so a fuel or bonus drop could
 * reference an id that CustomItems never defines. {@code MinionsConfig.load}
 * then throws at startup and {@code SkyBlockGameplay} disables itself
 * fail-closed, which is only discovered on a production boot.
 *
 * <p>These tests parse both shipped resources and assert that every id the
 * minion registry depends on is actually shipped by CustomItems.
 */
class ShippedMinionsConfigContractTest {

    private static final Path MINIONS = Path.of("src/main/resources/minions.yml");
    private static final Path MODELS = Path.of("../customitems/src/main/resources/models.yml");

    /** Only {@link #byId} is exercised by {@link MinionsConfig#parse}. */
    private record ShippedItems(Set<String> ids) implements CustomItemService {

        @Override
        public @NotNull Collection<CustomItem> all() {
            return ids.stream().map(this::definition).toList();
        }

        private CustomItem definition(String id) {
            return new CustomItem(id, Material.PAPER, null, List.of(), false, Map.of());
        }

        @Override
        public @NotNull Optional<CustomItem> byId(@NotNull String id) {
            return ids.contains(id)
                    ? Optional.of(new CustomItem(id, Material.PAPER, null, List.of(), false, Map.of()))
                    : Optional.empty();
        }

        @Override
        public @NotNull Optional<ItemStack> create(@NotNull String id) {
            return Optional.empty();
        }

        @Override
        public @Nullable String idOf(@Nullable ItemStack stack) {
            return null;
        }
    }

    private static YamlConfiguration load(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "missing shipped resource " + path.toAbsolutePath());
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static Set<String> shippedCustomItemIds() throws IOException {
        ConfigurationSection items = load(MODELS).getConfigurationSection("items");
        assertNotNull(items, "models.yml is missing the items section");
        return items.getKeys(false);
    }

    @Test
    void everyCustomItemIdInShippedMinionsYamlIsDefinedByCustomItems() throws IOException {
        YamlConfiguration minions = load(MINIONS);
        Set<String> shipped = shippedCustomItemIds();

        ConfigurationSection fuels = minions.getConfigurationSection("fuels");
        assertNotNull(fuels, "minions.yml is missing the fuels section");
        for (String fuel : fuels.getKeys(false)) {
            String id = fuels.getString(fuel + ".custom-item");
            if (id != null) {
                assertTrue(shipped.contains(id),
                        "fuel '" + fuel + "' needs CustomItems id '" + id + "'");
            }
        }

        ConfigurationSection registry = minions.getConfigurationSection("minions");
        assertNotNull(registry, "minions.yml is missing the minions section");
        for (String minion : registry.getKeys(false)) {
            ConfigurationSection tiers = registry.getConfigurationSection(minion + ".tiers");
            assertNotNull(tiers, "minion '" + minion + "' is missing tiers");
            for (String tier : tiers.getKeys(false)) {
                String id = tiers.getString(tier + ".bonus-drop");
                if (id != null) {
                    assertTrue(shipped.contains(id),
                            "minion '" + minion + "' tier " + tier
                                    + " needs CustomItems id '" + id + "'");
                }
            }
        }
    }

    @Test
    void shippedMinionsYamlParsesAgainstShippedCustomItems() throws IOException {
        MinionsConfig config =
                MinionsConfig.parse(load(MINIONS), new ShippedItems(shippedCustomItemIds()));

        assertFalse(config.fuels().isEmpty(), "shipped minions.yml defines no fuels");
        assertFalse(config.minions().isEmpty(), "shipped minions.yml defines no minions");
    }
}
