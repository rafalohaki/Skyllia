package org.rafalohaki.wpmecore.addons.skyblock.forge;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.rafalohaki.wpmecore.addons.skyblock.forge.ForgeEconomyBalanceTest.Chains;
import org.rafalohaki.wpmecore.addons.skyblock.forge.ForgeEconomyBalanceTest.ShopPrices;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Przestrzeń zlewu: receptura × kształt wyceny składników.
 *
 * <p><b>Jaką klasę błędu zamyka.</b> Do F17 bramka minionków liczyła tylko
 * {@code cost-money}, więc Płyta Wzmocniona (1,7 mln coins-eq) wymagana przez
 * minionka diamentu była dla niej niewidzialna — ten sam kształt co
 * „przychód liczony bez Fortuny" po stronie wpływów: test sprawdzał jeden
 * składnik zamiast całej sumy. F17 napisał bramkę na pełnych łańcuchach; tu
 * pełny łańcuch dostaje dwie wiążące własności dla KAŻDEJ receptury:
 * <ol>
 *   <li>składnik z wartością musi łańcuch podnosić nad gołe {@code cost-money}
 *       — gdyby którykolwiek obieg znowu go zgubił, równość świeci na czerwono;</li>
 *   <li>podniesienie cen skupu w sklepie nie może nigdy zmniejszyć kosztu
 *       łańcucha, a przy składniku wycenionym bezpośrednio ceną skupu musi
 *       go zwiększyć — dokładnie przez ten sam mechanizm, który ignorowała
 *       stara bramka.</li>
 * </ol>
 *
 * <p>Receptury z wynikiem {@code minion:*} zostają wyłączone — rządzi nimi
 * zwrot z {@code minions.yml:balance}, nie cena (dokumentacja F17).
 */
class ForgePriceSpacePropertyTest {

    private static final YamlConfiguration FORGE =
            load(Path.of("src/main/resources/forge.yml"));
    private static final YamlConfiguration CONFIG =
            load(Path.of("src/main/resources/config.yml"));

    private static final ShopPrices PRICES = ForgeEconomyBalanceTest.shopSellPrices(CONFIG);
    private static final Chains CHAINS = new Chains(FORGE, PRICES);

    @Property
    void everyRecipeChainStaysUnderTheCeiling(@ForAll("nonMinionRecipeIds") String id) {
        long ceiling = balance().getLong("max-coins-eq-per-recipe", -1L);
        assertTrue(ceiling > 0, "forge.yml:balance.max-coins-eq-per-recipe musi być > 0");
        long cost = CHAINS.of(id);
        assertTrue(cost <= ceiling, "receptura '" + id + "' kosztuje w pełnym łańcuchu "
                + cost + " coins-eq, sufit z forge.yml:balance to " + ceiling);
    }

    @Property
    void ingredientsAreNeverInvisibleToTheChainCost(@ForAll("nonMinionRecipeIds") String id) {
        long coins = CHAINS.costMoney(id);
        long chain = CHAINS.of(id);
        if (CHAINS.hasValuedIngredient(id)) {
            assertTrue(chain > coins, "receptura '" + id + "' ma składnik z wartością w ekonomii,"
                    + " a jej pełny łańcuch (" + chain + ") nie przewyższa gołego cost-money ("
                    + coins + ") — składnik jest znowu niewidzialny, czyli dokładnie kształt"
                    + " błędu F17 (płyta za 1,7 mln poza polem widzenia bramki)");
        } else {
            assertTrue(chain == coins, "receptura '" + id + "' nie ma żadnego składnika"
                    + " z wartością, więc jej łańcuch (" + chain + ") musi być równy"
                    + " cost-money (" + coins + ") — rozjadź się tu, a znów liczysz coś,"
                    + " czego nie da się wyjaśnić składem receptury");
        }
    }

    @Property
    void chainCostScalesWithShopPrices(@ForAll("nonMinionRecipeIds") String id,
                                       @ForAll @IntRange(min = 2, max = 16) int factor) {
        long base = CHAINS.of(id);
        long scaled = new Chains(FORGE, PRICES.scaled(factor)).of(id);
        assertTrue(scaled >= base, "podniesienie wszystkich cen skupu przez " + factor
                + "× zmniejszyło koszt receptury '" + id + "' (" + base + " -> " + scaled
                + ") — arytmetyka łańcucha nie jest monotoniczna względem cen");
        if (CHAINS.hasPricedLeafIngredient(id)) {
            assertTrue(scaled > base, "receptura '" + id + "' ma składnik wyceniony ceną skupu"
                    + " (bez podreceptury), a jej koszt nie urósł przy " + factor + "× — ten"
                    + " składnik nie wchodzi do sumy, czyli bramka go nie widzi");
        }
    }

    @Provide
    Arbitrary<String> nonMinionRecipeIds() {
        ConfigurationSection recipes = FORGE.getConfigurationSection("recipes");
        assertNotNull(recipes, "forge.yml: brak sekcji recipes");
        List<String> ids = recipes.getKeys(false).stream()
                .filter(id -> ForgeEconomyBalanceTest.minionTypeOf(recipes, id) == null)
                .sorted()
                .toList();
        assertTrue(!ids.isEmpty(), "forge.yml: zero receptur poza minionkami — własność nie ma"
                + " czego kwantyfikować, sprawdź prefix wyniku");
        return Arbitraries.of(ids);
    }

    private static ConfigurationSection balance() {
        ConfigurationSection balance = FORGE.getConfigurationSection("balance");
        assertNotNull(balance, "forge.yml: brak sekcji balance");
        return balance;
    }

    private static YamlConfiguration load(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            throw new IllegalStateException("brak wysyłanego zasobu " + path.toAbsolutePath(), e);
        }
    }
}
