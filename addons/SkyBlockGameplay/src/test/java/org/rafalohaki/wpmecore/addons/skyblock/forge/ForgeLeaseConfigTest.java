package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.season.CosmeticCatalog;
import org.rafalohaki.wpmecore.api.item.CustomItem;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.io.StringReader;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ECO-10: parsowanie receptur dzierżawnych (result-cosmetic + lease-days)
 * jest fail-closed względem cosmetics.yml — jak wszystko w forge.yml.
 */
class ForgeLeaseConfigTest {

    private static final CosmeticCatalog CATALOG = new CosmeticCatalog(
            true,
            Map.of(1, "abyss"),
            Map.of(1, 1),
            Map.of("abyss", new CosmeticCatalog.Collection("abyss", "<name>", "mystical_core_abyss",
                    1, List.of("mystical_abyss_chestplate"))));

    private static final String HEADER = """
            schema-version: 1
            categories:
              wynajem:
                slot: 8
                icon: DIAMOND_CHESTPLATE
                name: "<name>"
                lore:
                  - "lore"
            """;

    private static YamlConfiguration yaml(String content) {
        return YamlConfiguration.loadConfiguration(new StringReader(content));
    }

    private static IllegalArgumentException failureOf(String content) {
        return assertThrows(IllegalArgumentException.class,
                () -> ForgeConfig.parse(yaml(content), STUB_ITEMS, Set.of(), CATALOG));
    }

    /** Stub serwisu customów — rezultat customowy musi być znanym identyfikatorem. */
    private static final CustomItemService STUB_ITEMS = new CustomItemService() {
        @Override
        public @NotNull Collection<CustomItem> all() {
            return List.of();
        }

        @Override
        public @NotNull Optional<CustomItem> byId(@NotNull String id) {
            return Optional.of(new CustomItem(id, Material.PAPER, null, List.of(), false, Map.of()));
        }

        @Override
        public @NotNull Optional<ItemStack> create(@NotNull String id) {
            return Optional.empty();
        }

        @Override
        public String idOf(ItemStack stack) {
            return null;
        }
    };

    @Test
    void parsesCosmeticLeaseRecipeWithDefaultLeaseDays() {
        ForgeConfig config = ForgeConfig.parse(yaml(HEADER + """

                recipes:
                  wynajem_abyss:
                    category: wynajem
                    name: "<white>Wypożycz</white>"
                    result-cosmetic: "abyss/mystical_abyss_chestplate"
                    result-amount: 1
                    cost-money: 300000
                    required-items:
                      - item: "IRON_INGOT"
                        amount: 1
                """), STUB_ITEMS, Set.of(), CATALOG);

        ForgeConfig.ForgeRecipe recipe = config.recipes().get("wynajem_abyss");
        assertEquals("abyss/mystical_abyss_chestplate", recipe.resultCosmetic());
        assertEquals(ForgeConfig.DEFAULT_LEASE_DAYS, recipe.leaseDays(), "domyślnie 7 dni");
        assertNull(recipe.resultCustomItemId());
        assertNull(recipe.resultMaterial());
    }

    @Test
    void parsesCustomItemLeaseForTheCosmeticPet() {
        ForgeConfig config = ForgeConfig.parse(yaml(HEADER + """

                recipes:
                  wynajem_pet:
                    category: wynajem
                    name: "<white>Pet</white>"
                    result-item: "custom:skyblock:pet/diamond"
                    result-amount: 1
                    cost-money: 300000
                    lease-days: 7
                    required-items:
                      - item: "IRON_INGOT"
                        amount: 1
                """), STUB_ITEMS, Set.of(), CATALOG);

        ForgeConfig.ForgeRecipe recipe = config.recipes().get("wynajem_pet");
        assertEquals("skyblock:pet/diamond", recipe.resultCustomItemId());
        assertEquals(7, recipe.leaseDays());
        assertNull(recipe.resultCosmetic());
    }

    @Test
    void unknownCollectionOrPieceStopsTheParse() {
        assertTrue(failureOf(HEADER + """

                recipes:
                  x:
                    category: wynajem
                    name: "<white>X</white>"
                    result-cosmetic: "nieznana/parts"
                    result-amount: 1
                    cost-money: 1
                    required-items:
                      - item: "IRON_INGOT"
                        amount: 1
                """).getMessage().contains("nieznana kolekcja"));

        assertTrue(failureOf(HEADER + """

                recipes:
                  x:
                    category: wynajem
                    name: "<white>X</white>"
                    result-cosmetic: "abyss/mystical_blood_helmet"
                    result-amount: 1
                    cost-money: 1
                    required-items:
                      - item: "IRON_INGOT"
                        amount: 1
                """).getMessage().contains("nie ma części"));
    }

    @Test
    void leaseRecipeWithoutCatalogFailsClosed() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ForgeConfig.parse(yaml(HEADER + """

                        recipes:
                          x:
                            category: wynajem
                            name: "<white>X</white>"
                            result-cosmetic: "abyss/mystical_abyss_chestplate"
                            result-amount: 1
                            cost-money: 1
                            required-items:
                              - item: "IRON_INGOT"
                                amount: 1
                        """), STUB_ITEMS, Set.of(), null));
        assertTrue(failure.getMessage().contains("katalog kosmetyki niedostępny"));
    }

    @Test
    void leaseDaysCannotRideOnMaterialsCommandsOrMinions() {
        assertTrue(failureOf(HEADER + """

                recipes:
                  x:
                    category: wynajem
                    name: "<white>X</white>"
                    result-item: "IRON_BLOCK"
                    result-amount: 1
                    cost-money: 1
                    lease-days: 7
                    required-items:
                      - item: "IRON_INGOT"
                        amount: 1
                """).getMessage().contains("dzierżawa"));
    }

    @Test
    void cosmeticResultConflictsWithResultItemOrCommand() {
        assertTrue(failureOf(HEADER + """

                recipes:
                  x:
                    category: wynajem
                    name: "<white>X</white>"
                    result-cosmetic: "abyss/mystical_abyss_chestplate"
                    result-item: "IRON_BLOCK"
                    result-amount: 1
                    cost-money: 1
                    required-items:
                      - item: "IRON_INGOT"
                        amount: 1
                """).getMessage().contains("result-item"));

        assertTrue(failureOf(HEADER + """

                recipes:
                  x:
                    category: wynajem
                    name: "<white>X</white>"
                    result-cosmetic: "abyss/mystical_abyss_chestplate"
                    result-command: "minionki daj %player% diamond"
                    result-amount: 1
                    cost-money: 1
                    required-items:
                      - item: "IRON_INGOT"
                        amount: 1
                """).getMessage().contains("result-command"));
    }

    @Test
    void leaseAmountMustBeExactlyOne() {
        assertTrue(failureOf(HEADER + """

                recipes:
                  x:
                    category: wynajem
                    name: "<white>X</white>"
                    result-cosmetic: "abyss/mystical_abyss_chestplate"
                    result-amount: 2
                    cost-money: 1
                    required-items:
                      - item: "IRON_INGOT"
                        amount: 1
                """).getMessage().contains("dokładnie 1"));
    }
}
