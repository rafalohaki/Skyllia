package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.rafalohaki.wpmecore.addons.skyblock.shared.FortuneYield;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Przestrzeń OneBlocka: rozdział × poziom Fortuny {@code 0..HARD_MAX_LEVEL}.
 *
 * <p>Rozdział terminalny nie awansuje, więc jego EV na rozbicie to trwałe
 * tempo serwera; rozdział skończony rozlicza się sumą. Bramka
 * {@link OneBlockEconomyBalanceTest} pilnuje obu przy jednym wysylanym
 * poziomie — tu oba sufity wiążą dla każdego poziomu, który pozwala
 * osiągnąć gra. Ten sam kształt błędu co w F13 (bramka liczyła drop bez
 * Fortuny) dotyczył i tej bramki: rozdział {@code kres} przekraczał własny
 * sufit o 25 %.
 *
 * <p>Model to {@link OneBlockEconomyBalanceTest#perBreakValues} — te same
 * tabele łupów i ten sam model pity.
 */
class OneBlockFortuneSpacePropertyTest {

    private static final YamlConfiguration ONEBLOCK =
            load(Path.of("src/main/resources/oneblock.yml"));
    private static final YamlConfiguration CONFIG =
            load(Path.of("src/main/resources/config.yml"));

    /** Zapas wyłącznie na arytmetykę zmiennoprzecinkową, nie na balans. */
    private static final double FP_SLACK = 1.0e-6;

    @Property
    void everyChapterAtEveryReachableFortuneRespectsItsCeiling(
            @ForAll @IntRange(min = 0, max = FortuneYield.HARD_MAX_LEVEL) int fortune) {
        ConfigurationSection balance = ONEBLOCK.getConfigurationSection("balance");
        assertNotNull(balance, "oneblock.yml: brak sekcji balance");
        double maxPerBreak = balance.getDouble("terminal-max-value-per-break");
        double maxChapterTotal = balance.getDouble("finite-max-chapter-value");
        assertTrue(maxPerBreak > 0 && maxChapterTotal > 0,
                "oneblock.yml:balance ma niepoprawne wartości");

        List<Map<?, ?>> phases = ONEBLOCK.getMapList("phases");
        String terminalId = String.valueOf(phases.getLast().get("id"));
        Map<String, Double> perBreak =
                OneBlockEconomyBalanceTest.perBreakValues(ONEBLOCK, CONFIG, fortune);

        double terminal = perBreak.get(terminalId);
        assertTrue(terminal <= maxPerBreak + FP_SLACK,
                "rozdział terminalny '" + terminalId + "' przy Fortunie " + fortune + " daje "
                        + round2(terminal) + " coins/rozbicie, sufit"
                        + " terminal-max-value-per-break to " + maxPerBreak);

        for (Map<?, ?> phase : phases) {
            String id = String.valueOf(phase.get("id"));
            if (id.equals(terminalId)) {
                continue;
            }
            int blocksRequired = ((Number) phase.get("blocks-required")).intValue();
            double total = perBreak.get(id) * blocksRequired;
            assertTrue(total <= maxChapterTotal + FP_SLACK,
                    "rozdział skończony '" + id + "' przy Fortunie " + fortune + " wypłaca"
                            + " łącznie " + Math.round(total) + " coins (" + blocksRequired
                            + " bloków), sufit finite-max-chapter-value to "
                            + Math.round(maxChapterTotal));
        }
    }

    @Property
    void chapterValueNeverShrinksWithABetterTool(
            @ForAll @IntRange(min = 0, max = FortuneYield.HARD_MAX_LEVEL - 1) int level) {
        Map<String, Double> lower =
                OneBlockEconomyBalanceTest.perBreakValues(ONEBLOCK, CONFIG, level);
        Map<String, Double> higher =
                OneBlockEconomyBalanceTest.perBreakValues(ONEBLOCK, CONFIG, level + 1);
        for (Map.Entry<String, Double> chapter : lower.entrySet()) {
            assertTrue(higher.get(chapter.getKey()) >= chapter.getValue() - FP_SLACK,
                    "rozdział '" + chapter.getKey() + "' tanieje przy lepszym narzędziu ("
                            + chapter.getValue() + " -> " + higher.get(chapter.getKey())
                            + ") — przestrzeń nie jest monotoniczna, więc szczyt nic nie znaczy");
        }
    }

    @Example
    void atLeastOneChapterIsActuallySensitiveToTheToolCap() {
        Map<String, Double> bare =
                OneBlockEconomyBalanceTest.perBreakValues(ONEBLOCK, CONFIG, 0);
        Map<String, Double> capped =
                OneBlockEconomyBalanceTest.perBreakValues(ONEBLOCK, CONFIG,
                        FortuneYield.HARD_MAX_LEVEL);
        double gain = 0.0;
        for (Map.Entry<String, Double> chapter : bare.entrySet()) {
            gain = Math.max(gain, capped.get(chapter.getKey()) - chapter.getValue());
        }
        assertTrue(gain > FP_SLACK,
                "żaden rozdział nie zarabia więcej przy Fortunie III — model znowu liczy gołe"
                        + " narzędzie (regresja F13)");
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static YamlConfiguration load(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            throw new IllegalStateException("brak wysyłanego zasobu " + path.toAbsolutePath(), e);
        }
    }
}
