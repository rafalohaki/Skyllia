package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeRequirementsTest {

    private static final ForgeConfig.ForgeIngredient RAW_TUNGSTEN =
            new ForgeConfig.ForgeIngredient("skyblock:metal/raw_tungsten", null, 4);
    private static final ForgeConfig.ForgeIngredient COAL =
            new ForgeConfig.ForgeIngredient(null, Material.COAL, 8);

    private static ForgeConfig.ForgeRecipe recipe(ForgeConfig.ForgeIngredient... ingredients) {
        return new ForgeConfig.ForgeRecipe("refined", "metals", "<white>R</white>",
                "skyblock:metal/refined_tungsten", null, null, 1, 25000L, null, List.of(ingredients));
    }

    @Test
    void buildsDistinctKeysForCustomAndVanilla() {
        assertEquals("custom:skyblock:metal/raw_tungsten", ForgeRequirements.key(RAW_TUNGSTEN));
        assertEquals("COAL", ForgeRequirements.key(COAL));
    }

    @Test
    void reportsNothingWhenEverythingIsOwned() {
        Map<String, Integer> owned = Map.of(
                "custom:skyblock:metal/raw_tungsten", 4, "COAL", 8);

        assertTrue(ForgeRequirements.shortfalls(recipe(RAW_TUNGSTEN, COAL), owned).isEmpty());
        assertTrue(ForgeRequirements.satisfied(recipe(RAW_TUNGSTEN, COAL), owned));
    }

    @Test
    void reportsOnlyTheIngredientsThatAreShort() {
        Map<String, Integer> owned = Map.of(
                "custom:skyblock:metal/raw_tungsten", 4, "COAL", 3);

        List<ForgeRequirements.Shortfall> shortfalls =
                ForgeRequirements.shortfalls(recipe(RAW_TUNGSTEN, COAL), owned);

        assertEquals(1, shortfalls.size());
        assertEquals(Material.COAL, shortfalls.getFirst().ingredient().material());
        assertEquals(3, shortfalls.getFirst().owned());
        assertEquals(5, shortfalls.getFirst().missing());
        assertFalse(ForgeRequirements.satisfied(recipe(RAW_TUNGSTEN, COAL), owned));
    }

    @Test
    void treatsAMissingKeyAsZeroOwned() {
        List<ForgeRequirements.Shortfall> shortfalls =
                ForgeRequirements.shortfalls(recipe(RAW_TUNGSTEN, COAL), Map.of());

        assertEquals(2, shortfalls.size());
        assertEquals(0, shortfalls.getFirst().owned());
    }

    @Test
    void surplusIsNotAShortfall() {
        assertTrue(ForgeRequirements.satisfied(recipe(RAW_TUNGSTEN, COAL),
                Map.of("custom:skyblock:metal/raw_tungsten", 400, "COAL", 800)));
    }

    @Test
    void splitsIngredientsIntoTheTwoMapsTheOutboxExpects() {
        ForgeConfig.ForgeRecipe recipe = recipe(RAW_TUNGSTEN, COAL);

        assertEquals(Map.of(Material.COAL, 8), ForgeRequirements.plainRemovals(recipe));
        assertEquals(Map.of("skyblock:metal/raw_tungsten", 4),
                ForgeRequirements.customRemovals(recipe));
    }

    /** Mapa po cichu zgubiłaby drugą pozycję, gdyby ilości się nie sumowały. */
    @Test
    void sumsAnIngredientThatAppearsTwiceInOneRecipe() {
        ForgeConfig.ForgeRecipe recipe = recipe(COAL,
                new ForgeConfig.ForgeIngredient(null, Material.COAL, 5),
                RAW_TUNGSTEN,
                new ForgeConfig.ForgeIngredient("skyblock:metal/raw_tungsten", null, 2));

        assertEquals(Map.of(Material.COAL, 13), ForgeRequirements.plainRemovals(recipe));
        assertEquals(Map.of("skyblock:metal/raw_tungsten", 6),
                ForgeRequirements.customRemovals(recipe));
    }

    /**
     * Kuźnia wydaje wyrób przed zabraniem składników, żeby awaria między krokami
     * wypadła na korzyść gracza. Wyrób będący własnym składnikiem łamie tę
     * kolejność — świeży egzemplarz podniósłby baseline i zostałby zabrany.
     */
    @Test
    void detectsARecipeThatWouldEatItsOwnResult() {
        assertTrue(ForgeRequirements.consumesItsOwnResult(recipe(
                new ForgeConfig.ForgeIngredient("skyblock:metal/refined_tungsten", null, 1))));

        assertTrue(ForgeRequirements.consumesItsOwnResult(new ForgeConfig.ForgeRecipe(
                "plate", "metals", "<white>P</white>", null, Material.IRON_BLOCK, null, 1, 0L, null,
                List.of(new ForgeConfig.ForgeIngredient(null, Material.IRON_BLOCK, 1)))));

        assertFalse(ForgeRequirements.consumesItsOwnResult(recipe(RAW_TUNGSTEN, COAL)));
    }
}
