package org.rafalohaki.wpmecore.addons.skyblock.automation;

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.bukkit.configuration.file.YamlConfiguration;
import org.rafalohaki.wpmecore.addons.skyblock.automation.GeneratorEconomyBalanceTest.Layer;
import org.rafalohaki.wpmecore.addons.skyblock.automation.GeneratorEconomyBalanceTest.LayerIncome;
import org.rafalohaki.wpmecore.addons.skyblock.shared.FortuneYield;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Przestrzeń generatora: warstwa świata × poziom Fortuny
 * {@code 0..HARD_MAX_LEVEL}. To ta bramka puściła 19 542 coins/h przy suficie
 * 12 500, bo liczyła drop bez Fortuny (F13) — tu sufit wiążę dla KAŻDEGO
 * poziomu, który da się osiągnąć, a nie dla jednego wpisanego w config.
 *
 * <p>Wagi {@code generator.ores.rates} celowo NIE są kwantyfikowane: F8
 * dokumentuje, że to kompozycja wag jest przedmiotem kalibracji, a okna
 * cooldownu trzymają tylko tempo — losowy wektor wag, który wywraca sufit,
 * jest zapowiedzianym zachowaniem (podniesienie wagi drogiej rudy wywróci
 * bramkę wysyłaną), nie regresją.
 *
 * <p>Model to {@link GeneratorEconomyBalanceTest} — te same tabele.
 */
class GeneratorFortuneSpacePropertyTest {

    private static final YamlConfiguration CONFIG = load();

    /** Zapas wyłącznie na arytmetykę zmiennoprzecinkową, nie na balans. */
    private static final double FP_SLACK = 1.0e-6;

    @Property
    void everyLayerAtEveryReachableFortuneStaysUnderTheCeiling(
            @ForAll @IntRange(min = 0, max = FortuneYield.HARD_MAX_LEVEL) int fortune) {
        double ceiling = GeneratorEconomyBalanceTest.generatorCeiling(CONFIG);
        assertTrue(ceiling > 0, "generator.balance.max-coins-per-hour musi być > 0");
        Map<String, LayerIncome> income =
                GeneratorEconomyBalanceTest.perLayerHourlyIncome(CONFIG, fortune);
        for (Map.Entry<String, LayerIncome> layer : income.entrySet()) {
            double total = layer.getValue().total();
            assertTrue(total <= ceiling + FP_SLACK,
                    "warstwa '" + layer.getKey() + "' przy Fortunie " + fortune + " daje "
                            + Math.round(total) + " coins/h (rudy "
                            + Math.round(layer.getValue().ore()) + ", wypełniacz "
                            + Math.round(layer.getValue().filler()) + ", kryształ "
                            + Math.round(layer.getValue().custom()) + ") przy sufitcie "
                            + Math.round(ceiling) + " (generator.balance.max-coins-per-hour)");
        }
    }

    @Property
    void incomeNeverShrinksWithABetterTool(
            @ForAll @IntRange(min = 0, max = FortuneYield.HARD_MAX_LEVEL - 1) int level) {
        Map<String, LayerIncome> lower =
                GeneratorEconomyBalanceTest.perLayerHourlyIncome(CONFIG, level);
        Map<String, LayerIncome> higher =
                GeneratorEconomyBalanceTest.perLayerHourlyIncome(CONFIG, level + 1);
        for (Map.Entry<String, LayerIncome> layer : lower.entrySet()) {
            assertTrue(higher.get(layer.getKey()).total() >= layer.getValue().total() - FP_SLACK,
                    "warstwa '" + layer.getKey() + "' przy Fortunie " + (level + 1) + " daje mniej"
                            + " niż przy " + level + " — przestrzeń nie jest monotoniczna, więc"
                            + " sprawdzanie szczytu nic nie znaczy");
        }
    }

    @Example
    void layersWithoutOreSubstitutionIgnoreTheTool() {
        Map<String, LayerIncome> bare =
                GeneratorEconomyBalanceTest.perLayerHourlyIncome(CONFIG, 0);
        Map<String, LayerIncome> capped = GeneratorEconomyBalanceTest
                .perLayerHourlyIncome(CONFIG, FortuneYield.HARD_MAX_LEVEL);
        for (Layer layer : GeneratorEconomyBalanceTest.LAYERS) {
            if (layer.oreSubstitution()) {
                continue;
            }
            // Nether nie zna Fortuny, bo onBlockForm w ogóle nie podmienia tam
            // na rudę — gdyby wypełniacz albo kryształ nagle zaczął zależeć od
            // narzędzia, to ten przykład świeci na czerwono.
            assertTrue(Math.abs(bare.get(layer.id()).total() - capped.get(layer.id()).total())
                            <= FP_SLACK,
                    "warstwa '" + layer.id() + "' bez podmiany na rudę zmieniła przychód"
                            + " przy Fortunie III — Fortuna nie ma czego mnożyć, gdzie nie ma rudy");
        }
    }

    @Example
    void theModelIsActuallySensitiveToTheToolCap() {
        double bare = 0.0;
        double capped = 0.0;
        for (LayerIncome income : GeneratorEconomyBalanceTest
                .perLayerHourlyIncome(CONFIG, 0).values()) {
            bare = Math.max(bare, income.total());
        }
        for (LayerIncome income : GeneratorEconomyBalanceTest
                .perLayerHourlyIncome(CONFIG, FortuneYield.HARD_MAX_LEVEL).values()) {
            capped = Math.max(capped, income.total());
        }
        assertTrue(capped > bare,
                "żadna warstwa nie zarabia więcej przy Fortunie III (" + Math.round(bare)
                        + " coins/h w obu przypadkach) — model znowu liczy gołe narzędzie"
                        + " (regresja F13)");
    }

    private static YamlConfiguration load() {
        Path path = Path.of("src/main/resources/config.yml");
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            throw new IllegalStateException("brak wysyłanego zasobu " + path.toAbsolutePath(), e);
        }
    }
}
