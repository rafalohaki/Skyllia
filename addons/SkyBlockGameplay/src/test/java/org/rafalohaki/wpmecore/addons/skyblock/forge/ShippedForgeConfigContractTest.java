package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wysyłany {@code forge.yml} musi parsować się względem wysyłanego
 * {@code models.yml}.
 *
 * <p>Dokładnie ten rodzaj błędu przepuścił bursztyn w {@code minions.yml}:
 * paliwo wskazywało identyfikator CustomItems, którego nikt nie zdefiniował, i
 * wyszło to dopiero przy starcie serwera.
 */
class ShippedForgeConfigContractTest {

    private static final Path FORGE = Path.of("src/main/resources/forge.yml");
    private static final Path MODELS = Path.of("../customitems/src/main/resources/models.yml");
    private static final Path MINIONS = Path.of("src/main/resources/minions.yml");

    private record PresentItems(Set<String> ids) implements CustomItemService {
        @Override
        public @NotNull Collection<CustomItem> all() {
            return List.of();
        }

        @Override
        public @NotNull Optional<CustomItem> byId(@NotNull String id) {
            return ids.contains(id) ? Optional.of(stub(id)) : Optional.empty();
        }

        @Override
        public @NotNull Optional<ItemStack> create(@NotNull String id) {
            return Optional.empty();
        }

        @Override
        public String idOf(ItemStack stack) {
            return null;
        }

        private static CustomItem stub(String id) {
            return new CustomItem(id, org.bukkit.Material.PAPER, null, List.of(), false,
                    java.util.Map.of());
        }
    }

    private static YamlConfiguration load(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "brak wysyłanego zasobu " + path.toAbsolutePath());
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static Set<String> shippedCustomItemIds() throws IOException {
        ConfigurationSection items = load(MODELS).getConfigurationSection("items");
        assertNotNull(items, "models.yml nie ma sekcji items");
        return items.getKeys(false);
    }

    /**
     * Typy minionków z wysyłanego {@code minions.yml}. Receptura na minionka
     * wskazuje typ po identyfikatorze, więc usunięcie typu ma wywalić parsowanie
     * tutaj, a nie przy starcie serwera.
     */
    private static Set<String> shippedMinionTypes() throws IOException {
        ConfigurationSection minions = load(MINIONS).getConfigurationSection("minions");
        assertNotNull(minions, "minions.yml nie ma sekcji minions");
        return minions.getKeys(false);
    }

    private static ForgeConfig shippedConfig() throws IOException {
        return ForgeConfig.parse(load(FORGE), new PresentItems(shippedCustomItemIds()),
                shippedMinionTypes());
    }

    /**
     * Cały system minionków był dotąd nieosiągalny: jedyną drogą do pierwszego
     * egzemplarza była komenda administracyjna. Ten test pilnuje, żeby każdy typ
     * miał recepturę, więc dodanie typu bez drogi zdobycia zatrzyma budowanie.
     */
    private static String petTypeOf(String command) {
        if (command == null || !command.startsWith("minionki daj ")) {
            return null;
        }
        String[] parts = command.trim().split("\\s+");
        return parts[parts.length - 1];
    }

    @Test
    void everyShippedMinionTypeIsObtainableFromTheForge() throws IOException {
        ForgeConfig config = shippedConfig();

        // Typ wychodzi jako przedmiot minionka (`result-item: minion:<typ>`) albo
        // z komendy `minionki daj %player% <typ>` — obie drogi są „zdobywalne w kuźni”.
        Set<String> craftable = config.recipes().values().stream()
                .map(recipe -> recipe.resultMinionType() != null ? recipe.resultMinionType()
                        : petTypeOf(recipe.resultCommand()))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(shippedMinionTypes(), craftable,
                "każdy typ minionka musi mieć recepturę w kuźni — inaczej gracz "
                        + "nie zdobędzie go inaczej niż komendą administracyjną");
    }

    @Test
    void shippedForgeYamlParsesAgainstShippedCustomItems() throws IOException {
        ForgeConfig config = shippedConfig();

        assertFalse(config.categories().isEmpty(), "wysyłany forge.yml nie ma kategorii");
        assertFalse(config.recipes().isEmpty(), "wysyłany forge.yml nie ma receptur");
    }

    @Test
    void everyShippedCategoryIsReachableFromTheTabRow() throws IOException {
        ForgeConfig config = shippedConfig();

        assertEquals(config.categories().size(), config.visibleCategories().size(),
                "wysyłany forge.yml nie powinien mieć pustych kategorii");
    }

    @Test
    void everyCategoryFitsTheRecipeGrid() throws IOException {
        ForgeConfig config = shippedConfig();

        for (ForgeConfig.ForgeCategory category : config.visibleCategories()) {
            assertTrue(config.recipesOf(category.id()).size()
                            <= ForgeConfig.MAX_RECIPES_PER_CATEGORY,
                    "kategoria '" + category.id() + "' przekracza siatkę slotów 9..44");
        }
    }

    /** Wyrób, którego nie da się zdobyć inaczej, jest gorszy niż jego brak. */
    @Test
    void everyRecipeProducesSomethingAndConsumesSomething() throws IOException {
        ForgeConfig config = shippedConfig();

        for (ForgeConfig.ForgeRecipe recipe : config.recipes().values()) {
            assertTrue(recipe.resultAmount() > 0, recipe.id());
            assertFalse(recipe.ingredients().isEmpty(), recipe.id());
            assertTrue(recipe.costMoney() >= 0L, recipe.id());
        }
    }

    /**
     * Bursztyn ma nazwę „Bursztyn Kuźni" i jest paliwem minionków, więc kuźnia
     * musi mieć dla niego recepturę — inaczej nazwa obiecuje coś, czego nie ma.
     */
    @Test
    void theForgeCanActuallyMakeTheAmberItIsNamedAfter() throws IOException {
        ForgeConfig config = shippedConfig();

        assertTrue(config.recipes().values().stream()
                        .anyMatch(recipe -> "skyblock:crystal/amber"
                                .equals(recipe.resultCustomItemId())),
                "brak receptury na skyblock:crystal/amber");
    }
}
