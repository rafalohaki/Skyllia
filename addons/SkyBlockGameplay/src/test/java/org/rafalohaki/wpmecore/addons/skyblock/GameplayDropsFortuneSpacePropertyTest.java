package org.rafalohaki.wpmecore.addons.skyblock;

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.bukkit.configuration.file.YamlConfiguration;
import org.rafalohaki.wpmecore.addons.skyblock.shared.FortuneYield;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cała przestrzeń przychodu rolnictwa — uprawa × poziom Fortuny
 * {@code 0..HARD_MAX_LEVEL} — a nie punkt {@code balance.fortune-level}.
 *
 * <p><b>Jaką klasę błędu zamyka.</b> Do F13 bramka balansowa liczyła drop
 * <b>bez Fortuny</b>: generator dawał realnie 19 542 monety/h przy własnym
 * suficie 12 500 i świecił na zielono, bo test sprawdzał jeden przypadek
 * zamiast całej przestrzeni. Ten plik nie ufa, że {@code config.yml} zawsze
 * będzie trzymał {@code fortune-level: 3} — kwantyfikuje po wszystkich
 * poziomach, które pozwala osiągnąć gra, więc obniżenie punktu w configu
 * nie ścisza sprawdzenia.
 *
 * <p>Model to {@link GameplayDropsEconomyBalanceTest} — ta sama tabela upraw
 * i ta sama arytmetyka perydotu, bo rozjazd między bramką a właściwością
 * byłby dokładnie tym samym grzechem co rozjazd między trzema bramkami F13.
 */
class GameplayDropsFortuneSpacePropertyTest {

    private static final YamlConfiguration CONFIG = load();

    /** Zapas wyłącznie na arytmetykę zmiennoprzecinkową, nie na balans. */
    private static final double FP_SLACK = 1.0e-6;

    @Property
    void everyCropAtEveryReachableFortuneStaysUnderTheCeiling(
            @ForAll @IntRange(min = 0, max = FortuneYield.HARD_MAX_LEVEL) int fortune) {
        double ceiling = GameplayDropsEconomyBalanceTest.farmingCeiling(CONFIG);
        assertTrue(ceiling > 0, "gameplay-drops.balance.max-coins-per-hour musi być > 0");
        Map<String, Double> income =
                GameplayDropsEconomyBalanceTest.perCropHourlyIncome(CONFIG, fortune);
        for (Map.Entry<String, Double> crop : income.entrySet()) {
            assertTrue(crop.getValue() <= ceiling + FP_SLACK,
                    "uprawa '" + crop.getKey() + "' przy Fortunie " + fortune + " daje "
                            + Math.round(crop.getValue()) + " coins/h przy sufitcie "
                            + Math.round(ceiling)
                            + " (gameplay-drops.balance.max-coins-per-hour). Wszystkie uprawy: "
                            + income);
        }
    }

    @Property
    void incomeNeverShrinksWithABetterTool(
            @ForAll @IntRange(min = 0, max = FortuneYield.HARD_MAX_LEVEL - 1) int level) {
        Map<String, Double> lower =
                GameplayDropsEconomyBalanceTest.perCropHourlyIncome(CONFIG, level);
        Map<String, Double> higher =
                GameplayDropsEconomyBalanceTest.perCropHourlyIncome(CONFIG, level + 1);
        for (Map.Entry<String, Double> crop : lower.entrySet()) {
            assertTrue(higher.get(crop.getKey()) >= crop.getValue() - FP_SLACK,
                    "uprawa '" + crop.getKey() + "' przy Fortunie " + (level + 1) + " daje mniej"
                            + " (" + higher.get(crop.getKey()) + ") niż przy " + level + " ("
                            + crop.getValue() + ") — model nie jest monotoniczny względem narzędzia,"
                            + " więc sufit liczony od szczytu przestrzeni nie znaczy nic");
        }
    }

    /**
     * Szczyt pasma (kakao) jest FORTUNĄ NIESKALOWANY z projektu — jedyna
     * uprawa bez {@code apply_bonus}, płaci za przewidywalność (komentarz przy
     * {@code cocoa_beans} w cenniku). Więc próg czułości liczy się nie od
     * {@code best()}, a od upraw, których bonus jest wyceniony w sklepie:
     * jeśli model przestanie mnożyć je Fortuną (regresja F13), świeci czerwień.
     */
    @Example
    void fortuneIsWiredThroughEveryPricedBonusYield() {
        Map<String, Double> bare =
                GameplayDropsEconomyBalanceTest.perCropHourlyIncome(CONFIG, 0);
        Map<String, Double> capped = GameplayDropsEconomyBalanceTest
                .perCropHourlyIncome(CONFIG, FortuneYield.HARD_MAX_LEVEL);
        assertTrue(capped.get("carrots") > bare.get("carrots") + FP_SLACK,
                "marchew (bonus dwumianowy, sell 1) nie zarabia więcej przy Fortunie III ("
                        + bare.get("carrots") + " -> " + capped.get("carrots") + ") — model"
                        + " znowu mnoży zero zamiast łupu, czyli regresja F13");
        assertTrue(capped.get("nether_wart") > bare.get("nether_wart") + FP_SLACK,
                "brodawka (bonus jednostajny, sell 1) nie zarabia więcej przy Fortunie III ("
                        + bare.get("nether_wart") + " -> " + capped.get("nether_wart")
                        + ") — ta sama regresja co w F13");
        assertEquals(bare.get("cocoa").doubleValue(), capped.get("cocoa").doubleValue(), FP_SLACK,
                "kakao zmieniło przychód od narzędzia, a jego tabela łupów NIE MA"
                        + " apply_bonus — model mnoży coś, czego Fortuna mnożyć nie powinna");
    }

    @Example
    void shippedGateIsCalibratedAtTheHardToolCap() {
        assertEquals(FortuneYield.HARD_MAX_LEVEL, CONFIG.getInt("balance.fortune-level"),
                "balance.fortune-level to SUFIT NARZĘDZIA, nie przypadek bazowy (komentarz przy"
                        + " kluczu w config.yml). Obniżenie go ścisza bramki punktowe, a"
                        + " właściwości i tak liczą przestrzeń 0.." + FortuneYield.HARD_MAX_LEVEL
                        + " — sufit płynie z max_level w enchantment/fortune.json");
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
