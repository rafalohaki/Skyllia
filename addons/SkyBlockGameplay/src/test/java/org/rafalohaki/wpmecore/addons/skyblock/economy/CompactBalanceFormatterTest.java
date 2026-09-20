package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompactBalanceFormatterTest {

    @Test
    void formatsExactPolishValuesWithoutFractions() {
        assertEquals("0", CompactBalanceFormatter.exact(0));
        assertEquals("12 345", CompactBalanceFormatter.exact(12_345));
    }

    @Test
    void compactsBoundariesPredictably() {
        assertEquals("999", CompactBalanceFormatter.compact(999));
        assertEquals("1 tys.", CompactBalanceFormatter.compact(1_000));
        assertEquals("1,3 tys.", CompactBalanceFormatter.compact(1_250));
        assertEquals("1 mln", CompactBalanceFormatter.compact(1_000_000));
        assertEquals("-2,5 mld", CompactBalanceFormatter.compact(-2_500_000_000L));
    }
}
