package org.rafalohaki.wpmecore.addons.skyblock.shared;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Niezmienne wzorów Fortuny, liczone na losowych parametrach zamiast na
 * wybranych punktach (F14: bramki mierzyły jeden punkt i właśnie przez to
 * przepuściły generator 19 542 vs sufit 12 500).
 *
 * <p>Te właściwości są podłożem dla trzech klas właściwości przychodu
 * ({@code GameplayDropsFortuneSpacePropertyTest},
 * {@code GeneratorFortuneSpacePropertyTest},
 * {@code OneBlockFortuneSpacePropertyTest}): tamte kwantyfikują po poziomie
 * {@code 0..HARD_MAX_LEVEL}, a tu leży dowód, że taki zakres wystarcza —
 * mnożnik rośnie monotonicznie, więc szczyt przestrzeni wypada w {@code III}.
 */
class FortuneYieldProperties {

    private static final double EPS = 1.0e-9;

    @Property
    void yieldIsNonDecreasingInFortuneLevel(@ForAll("formula") FortuneYield.Formula formula,
                                            @ForAll @IntRange(min = 0, max = 10_000) int baseTenths,
                                            @ForAll @IntRange(min = 0, max = 32) int bonusMultiplier,
                                            @ForAll @IntRange(min = 0, max = 64) int level) {
        double base = baseTenths / 10.0;
        double atLevel = FortuneYield.amount(formula, base, bonusMultiplier, level);
        double atPrevious = level == 0
                ? atLevel
                : FortuneYield.amount(formula, base, bonusMultiplier, level - 1);
        assertTrue(atLevel >= atPrevious - EPS,
                formula + ": poziom " + level + " dał " + atLevel
                        + ", a niższy poziom " + (level - 1) + " dał " + atPrevious
                        + " (base=" + base + ", mnożnik=" + bonusMultiplier + ")");
    }

    @Property
    void oreMultiplierNeverExceedsTheHardToolCap(@ForAll @IntRange(min = 0, max = 64) int level) {
        double cap = FortuneYield.oreMultiplier(FortuneYield.HARD_MAX_LEVEL);
        assertTrue(FortuneYield.oreMultiplier(Math.min(level, FortuneYield.HARD_MAX_LEVEL)) <= cap + EPS,
                "mnożnik dla poziomów <= " + FortuneYield.HARD_MAX_LEVEL + " przekroczył sufit " + cap);
        assertEquals(2.2, cap, EPS, "sufit ore_drops liczony inaczej niż (1+1+2+3+4)/5");
    }

    @Property
    void formulaNoneIgnoresToolAndLevel(@ForAll @IntRange(min = 0, max = 10_000) int baseTenths,
                                        @ForAll @IntRange(min = 0, max = 32) int bonusMultiplier,
                                        @ForAll @IntRange(min = 0, max = 64) int level) {
        double base = baseTenths / 10.0;
        assertEquals(base, FortuneYield.amount(FortuneYield.Formula.NONE, base, bonusMultiplier, level),
                EPS, "NONE nie może zależeć od narzędzia ani poziomu");
    }

    @Property
    void bareToolKeepsVanillaAverageExceptBinomial(@ForAll @IntRange(min = 0, max = 10_000) int baseTenths,
                                                   @ForAll @IntRange(min = 0, max = 32) int bonusMultiplier) {
        double base = baseTenths / 10.0;
        assertEquals(1.0, FortuneYield.oreMultiplier(0), EPS, "poziom 0 = gołe narzędzie");
        assertEquals(base, FortuneYield.amount(FortuneYield.Formula.ORE_DROPS, base, bonusMultiplier, 0), EPS);
        assertEquals(base, FortuneYield.amount(FortuneYield.Formula.UNIFORM_BONUS, base, bonusMultiplier, 0), EPS);
        // DWUMIAN: base jest liczbą prób, nie średnią — przy poziomie 0 oczekiwana
        // liczba sztuk to base*p, i to jest walidowany poniżej kształt.
        assertEquals(base * FortuneYield.CROP_BINOMIAL_PROBABILITY,
                FortuneYield.amount(FortuneYield.Formula.BINOMIAL_BONUS, base, bonusMultiplier, 0), EPS);
    }

    @Property
    void binomialGrowsLinearlyInLevels(@ForAll @IntRange(min = 0, max = 10_000) int baseTenths,
                                       @ForAll @IntRange(min = 1, max = 64) int level) {
        double expectedStep = level * FortuneYield.CROP_BINOMIAL_PROBABILITY;
        assertEquals(expectedStep,
                FortuneYield.amount(FortuneYield.Formula.BINOMIAL_BONUS, baseTenths / 10.0, 1, level)
                        - FortuneYield.amount(FortuneYield.Formula.BINOMIAL_BONUS, baseTenths / 10.0, 1, 0),
                EPS, "każdy poziom dwumianu dokłada dokładnie p sztuk oczekiwanych");
    }

    @Example
    void negativeLevelIsRejected() {
        for (FortuneYield.Formula formula : FortuneYield.Formula.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> FortuneYield.amount(formula, 1.0, 1, -1),
                    formula + ": poziom ujemny musi być odrzucony, nie zwracać wartości");
        }
    }

    @Provide
    Arbitrary<FortuneYield.Formula> formula() {
        return Arbitraries.of(FortuneYield.Formula.values());
    }
}
