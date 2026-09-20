package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permissible;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Parser kuźni jest fail-closed: każdy błąd w {@code forge.yml} ma zatrzymać
 * start pluginu z komunikatem wskazującym ścieżkę, a nie ujawnić się dopiero
 * przy kliknięciu gracza.
 */
class ForgeConfigTest {

    private static YamlConfiguration yaml(String content) {
        return YamlConfiguration.loadConfiguration(new StringReader(content));
    }

    private static final String MINIMAL = """
            schema-version: 1
            categories:
              metals:
                slot: 1
                icon: IRON_INGOT
                name: "<white>Metale</white>"
                lore:
                  - "<gray>Opis.</gray>"
            recipes:
              plate:
                category: metals
                name: "<white>Płyta</white>"
                result-item: "IRON_BLOCK"
                result-amount: 2
                cost-money: 1500
                required-items:
                  - item: "IRON_INGOT"
                    amount: 9
            """;

    private static IllegalArgumentException failureOf(String content) {
        return assertThrows(IllegalArgumentException.class,
                () -> ForgeConfig.parse(yaml(content), null));
    }

    @Test
    void parsesAVanillaOnlyRecipe() {
        ForgeConfig config = ForgeConfig.parse(yaml(MINIMAL), null);

        ForgeConfig.ForgeRecipe recipe = config.recipes().get("plate");
        assertEquals("metals", recipe.category());
        assertEquals(Material.IRON_BLOCK, recipe.resultMaterial());
        assertNull(recipe.resultCustomItemId());
        assertEquals(2, recipe.resultAmount());
        assertEquals(1500L, recipe.costMoney());
        assertEquals(1, recipe.ingredients().size());
        assertEquals(Material.IRON_INGOT, recipe.ingredients().getFirst().material());
        assertEquals(9, recipe.ingredients().getFirst().amount());
    }

    @Test
    void hidesCategoriesThatHaveNoRecipes() {
        ForgeConfig config = ForgeConfig.parse(yaml("""
                schema-version: 1
                categories:
                  metals:
                    slot: 1
                    icon: IRON_INGOT
                    name: "<white>Metale</white>"
                    lore: []
                  pusta:
                    slot: 5
                    icon: BLAZE_POWDER
                    name: "<gray>Pusta</gray>"
                    lore: []
                recipes:
                  plate:
                    category: metals
                    name: "<white>Płyta</white>"
                    result-item: "IRON_BLOCK"
                    result-amount: 1
                    cost-money: 10
                    required-items:
                      - item: "IRON_INGOT"
                        amount: 9
                """), null);

        assertEquals(2, config.categories().size());
        List<ForgeConfig.ForgeCategory> visible = config.visibleCategories();
        assertEquals(1, visible.size());
        assertEquals("metals", visible.getFirst().id());
    }

    @Test
    void visibleCategoriesFollowTheTabSlotOrderNotTheFileOrder() {
        ForgeConfig config = ForgeConfig.parse(yaml("""
                schema-version: 1
                categories:
                  druga:
                    slot: 5
                    icon: IRON_INGOT
                    name: "<white>B</white>"
                    lore: []
                  pierwsza:
                    slot: 1
                    icon: IRON_INGOT
                    name: "<white>A</white>"
                    lore: []
                recipes:
                  a:
                    category: pierwsza
                    name: "<white>A</white>"
                    result-item: "IRON_BLOCK"
                    result-amount: 1
                    cost-money: 1
                    required-items:
                      - item: "IRON_INGOT"
                        amount: 1
                  b:
                    category: druga
                    name: "<white>B</white>"
                    result-item: "GOLD_BLOCK"
                    result-amount: 1
                    cost-money: 1
                    required-items:
                      - item: "GOLD_INGOT"
                        amount: 1
                """), null);

        assertEquals(List.of("pierwsza", "druga"),
                config.visibleCategories().stream()
                        .map(ForgeConfig.ForgeCategory::id).toList());
    }

    /** Brak rejestru traktujemy jak nieznany identyfikator — kuźnia nie obiecuje na pusto. */
    @Test
    void rejectsACustomItemThatNoRegistryKnows() {
        IllegalArgumentException failure = failureOf(MINIMAL.replace(
                "result-item: \"IRON_BLOCK\"",
                "result-item: \"custom:skyblock:metal/nie_istnieje\""));

        assertTrue(failure.getMessage().contains("nieznany identyfikator CustomItems"),
                failure.getMessage());
        assertTrue(failure.getMessage().startsWith("forge.yml:recipes.plate.result-item"),
                failure.getMessage());
    }

    @Test
    void rejectsARecipePointingAtAnUnknownCategory() {
        assertTrue(failureOf(MINIMAL.replace("category: metals", "category: nieistniejaca"))
                .getMessage().contains("nieznana kategoria"));
    }

    @Test
    void rejectsTwoCategoriesSharingATabSlot() {
        assertTrue(failureOf("""
                schema-version: 1
                categories:
                  metals:
                    slot: 1
                    icon: IRON_INGOT
                    name: "<white>A</white>"
                    lore: []
                  keys:
                    slot: 1
                    icon: TRIPWIRE_HOOK
                    name: "<white>B</white>"
                    lore: []
                recipes: {}
                """).getMessage().contains("slot jest już zajęty"));
    }

    @Test
    void rejectsACategorySlotOutsideTheTabRow() {
        assertTrue(failureOf(MINIMAL.replace("slot: 1", "slot: 9"))
                .getMessage().contains("poza zakresem [0, 8]"));
    }

    /** Sloty 9..44 mieszczą dokładnie 36 receptur; paginacji nie ma. */
    @Test
    void rejectsMoreRecipesThanTheGridHolds() {
        StringBuilder content = new StringBuilder("""
                schema-version: 1
                categories:
                  metals:
                    slot: 1
                    icon: IRON_INGOT
                    name: "<white>Metale</white>"
                    lore: []
                recipes:
                """);
        for (int index = 0; index <= ForgeConfig.MAX_RECIPES_PER_CATEGORY; index++) {
            content.append("""
                      recipe%d:
                        category: metals
                        name: "<white>R</white>"
                        result-item: "IRON_BLOCK"
                        result-amount: 1
                        cost-money: 1
                        required-items:
                          - item: "IRON_INGOT"
                            amount: 1
                    """.formatted(index));
        }

        assertTrue(failureOf(content.toString()).getMessage()
                .contains("najwyżej " + ForgeConfig.MAX_RECIPES_PER_CATEGORY + " receptur"));
    }

    /**
     * {@code setAmount} powyżej maksimum stosu nie przycina ani nie rzuca —
     * tworzy stos ponadwymiarowy. Lepiej odmówić w konfiguracji.
     */
    @Test
    void rejectsAResultLargerThanAVanillaStack() {
        assertTrue(failureOf(MINIMAL.replace("result-amount: 2", "result-amount: 65"))
                .getMessage().contains("nie może przekraczać 64"));
    }

    @Test
    void acceptsAFullStackAsAResult() {
        ForgeConfig config = ForgeConfig.parse(
                yaml(MINIMAL.replace("result-amount: 2", "result-amount: 64")), null);

        assertEquals(64, config.recipes().get("plate").resultAmount());
    }

    @Test
    void rejectsANegativeCost() {
        assertTrue(failureOf(MINIMAL.replace("cost-money: 1500", "cost-money: -1"))
                .getMessage().contains("musi być >= 0"));
    }

    @Test
    void acceptsAFreeRecipe() {
        ForgeConfig config = ForgeConfig.parse(
                yaml(MINIMAL.replace("cost-money: 1500", "cost-money: 0")), null);

        assertEquals(0L, config.recipes().get("plate").costMoney());
    }

    @Test
    void rejectsARecipeThatConsumesNothing() {
        assertTrue(failureOf("""
                schema-version: 1
                categories:
                  metals:
                    slot: 1
                    icon: IRON_INGOT
                    name: "<white>Metale</white>"
                    lore: []
                recipes:
                  plate:
                    category: metals
                    name: "<white>Płyta</white>"
                    result-item: "IRON_BLOCK"
                    result-amount: 1
                    cost-money: 10
                    required-items: []
                """).getMessage().contains("brak wymaganych składników"));
    }

    @Test
    void rejectsANonPositiveIngredientAmount() {
        assertTrue(failureOf(MINIMAL.replace("amount: 9", "amount: 0"))
                .getMessage().contains("musi być > 0"));
    }

    @Test
    void rejectsAnUnknownMaterial() {
        assertTrue(failureOf(MINIMAL.replace("result-item: \"IRON_BLOCK\"",
                "result-item: \"NIE_MA_TAKIEGO\"")).getMessage()
                .contains("nieznany materiał"));
    }

    @Test
    void rejectsAWrongSchemaVersion() {
        assertTrue(failureOf(MINIMAL.replace("schema-version: 1", "schema-version: 2"))
                .getMessage().contains("oczekiwano 1, było 2"));
    }

    @Test
    void rejectsAMissingCategoriesSection() {
        assertTrue(failureOf("schema-version: 1\nrecipes: {}\n")
                .getMessage().contains("brak wymaganej sekcji"));
    }

    /** Gracz noszący wskazane węzły uprawnień — do testów zniżki rangowej (F17). */
    private static Permissible holder(Set<String> nodes) {
        Permissible permissible = mock(Permissible.class);
        when(permissible.hasPermission(anyString()))
                .thenAnswer(invocation -> nodes.contains(invocation.getArgument(0, String.class)));
        return permissible;
    }

    private static final String WITH_DISCOUNTS = MINIMAL + """
            discounts:
              - permission: "skyblockgameplay.forge.discount.sponsor"
                percent: 10
              - permission: "skyblockgameplay.forge.discount.legend"
                percent: 15
            """;

    @Test
    void chargesTheListPriceWithoutAnyRankNode() {
        ForgeConfig config = ForgeConfig.parse(yaml(WITH_DISCOUNTS), null);
        ForgeConfig.ForgeRecipe recipe = config.recipes().get("plate");

        assertEquals(0, config.discountPercent(holder(Set.of())));
        assertEquals(1500L, config.costFor(holder(Set.of()), recipe));
    }

    @Test
    void appliesTheDeclaredPercentToTheRecipeCost() {
        ForgeConfig config = ForgeConfig.parse(yaml(WITH_DISCOUNTS), null);
        ForgeConfig.ForgeRecipe recipe = config.recipes().get("plate");

        assertEquals(1350L, config.costFor(
                holder(Set.of("skyblockgameplay.forge.discount.sponsor")), recipe));
        assertEquals(1275L, config.costFor(
                holder(Set.of("skyblockgameplay.forge.discount.legend")), recipe));
    }

    /**
     * Legenda dziedziczy po sponsorze w LuckPermsie, więc nosi OBA węzły.
     * Bez sortowania malejąco płaciłaby stawkę sponsora — dokładnie ta usterka
     * jest powodem, dla którego lista jest listą, a nie mapą.
     */
    @Test
    void theBiggestMatchingDiscountWins() {
        ForgeConfig config = ForgeConfig.parse(yaml(WITH_DISCOUNTS), null);

        assertEquals(15, config.discountPercent(holder(Set.of(
                "skyblockgameplay.forge.discount.sponsor",
                "skyblockgameplay.forge.discount.legend"))));
    }

    @Test
    void rejectsADiscountOutsideTheAllowedRange() {
        assertTrue(failureOf(MINIMAL + """
                discounts:
                  - permission: "skyblockgameplay.forge.discount.legend"
                    percent: 100
                """).getMessage().contains("discounts[0].percent"));
        assertTrue(failureOf(MINIMAL + """
                discounts:
                  - permission: "skyblockgameplay.forge.discount.legend"
                    percent: 0
                """).getMessage().contains("discounts[0].percent"));
    }

    /**
     * Kropka w kluczu YAML-a jest dla {@code ConfigurationSection} separatorem
     * ścieżki, więc mapa {@code <węzeł>: <procent>} rozsypałaby się na sekcje
     * i żaden węzeł nie zostałby rozpoznany. Ten test pilnuje, że pełny węzeł
     * z kropkami przeżywa parsowanie w całości.
     */
    @Test
    void keepsTheWholeDottedPermissionNode() {
        ForgeConfig config = ForgeConfig.parse(yaml(WITH_DISCOUNTS), null);

        assertEquals(List.of("skyblockgameplay.forge.discount.legend",
                        "skyblockgameplay.forge.discount.sponsor"),
                config.discounts().stream()
                        .map(ForgeConfig.ForgeDiscount::permission).toList());
    }

    @Test
    void aConfigWithoutDiscountsChargesEveryoneTheSame() {
        ForgeConfig config = ForgeConfig.parse(yaml(MINIMAL), null);

        assertTrue(config.discounts().isEmpty());
        assertEquals(0, config.discountPercent(
                holder(Set.of("skyblockgameplay.forge.discount.legend"))));
    }
}
