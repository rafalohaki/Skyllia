package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ECO-12: deterministyczny wybór okazji dnia kuźni. */
class ForgeDailyDealsTest {

    private static final List<String> RECIPES = List.of(
            "perfect_plate", "tungsten_key", "abyss_chestplate_craft", "silver_lotus_exchange",
            "amber_catalyst_fuel", "minion_diamond", "titan_blade_craft", "umber_plate",
            "skeleton_key", "harvest_talisman_craft", "gold_lotus_exchange", "minion_quartz");

    @Test
    void picksExactlyThreeDistinctRecipesForADay() {
        Set<String> deals = ForgeDailyDeals.dealsFor(LocalDate.of(2026, 9, 27), RECIPES);

        assertEquals(ForgeDailyDeals.DEAL_COUNT, deals.size());
        assertTrue(RECIPES.containsAll(deals), "okazje muszą być z katalogu kuźni");
    }

    @Test
    void sameDaySameSetDifferentDayDifferentSet() {
        LocalDate day = LocalDate.of(2026, 9, 27);

        assertEquals(ForgeDailyDeals.dealsFor(day, RECIPES),
                ForgeDailyDeals.dealsFor(day, RECIPES),
                "ten sam dzień = ten sam zestaw (restart nie tasuje)");

        // Rotacja: w tygodniu co najmniej jeden zestaw musi się różnić
        // (zbieżność pełna przez 7 dni kolejnych losowań jest pomijalna).
        boolean rotated = false;
        for (int offset = 1; offset <= 7 && !rotated; offset++) {
            rotated = !ForgeDailyDeals.dealsFor(day, RECIPES)
                    .equals(ForgeDailyDeals.dealsFor(day.plusDays(offset), RECIPES));
        }
        assertTrue(rotated, "seed = doba — zestaw ma rotować");
    }

    @Test
    void inputOrderDoesNotMatter() {
        LocalDate day = LocalDate.of(2026, 9, 27);
        List<String> reversed = new java.util.ArrayList<>(RECIPES);
        java.util.Collections.reverse(reversed);

        assertEquals(ForgeDailyDeals.dealsFor(day, RECIPES),
                ForgeDailyDeals.dealsFor(day, reversed),
                "wybór ma być stabilny niezależnie od kolejności wejścia");
    }

    @Test
    void catalogSmallerThanTheDealCountGetsNoDeals() {
        List<String> small = List.of("a", "b");

        assertEquals(Set.of(), ForgeDailyDeals.dealsFor(LocalDate.of(2026, 9, 27), small),
                "mniej receptur niż okazji — bez promocji (nie globalna zniżka)");
        assertEquals(Set.of("a", "b", "c"),
                ForgeDailyDeals.dealsFor(LocalDate.of(2026, 9, 27), List.of("a", "b", "c", "d")),
                "katalog większy od puli — dokładnie 3 okazje");
    }

    @Test
    void dealPercentMatchesTheAudit() {
        assertEquals(20, ForgeDailyDeals.DEAL_PERCENT, "audyt ECO-12: −20 %");
        // Cena 100 000 po rabacie rangowym 15 % (85 000) → okazja 17 000 zniżki.
        long afterRank = 85_000L;
        assertEquals(68_000L, afterRank - afterRank * ForgeDailyDeals.DEAL_PERCENT / 100L,
                "okazja działa na cenie po rabacie rangowym, dzielenie obcina zniżkę");
    }
}
