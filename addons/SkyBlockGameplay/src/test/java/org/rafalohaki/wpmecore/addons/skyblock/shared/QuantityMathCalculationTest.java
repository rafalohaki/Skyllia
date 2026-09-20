package org.rafalohaki.wpmecore.addons.skyblock.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuantityMathCalculationTest {

    @Nested
    @DisplayName("requiredSlots calculations")
    class RequiredSlotsTests {
        @Test
        void returnsZeroForZeroOrNegativeQuantity() {
            assertEquals(0, QuantityMath.requiredSlots(64, 0));
            assertEquals(0, QuantityMath.requiredSlots(64, -1));
            assertEquals(0, QuantityMath.requiredSlots(64, -100));
            assertEquals(0, QuantityMath.requiredSlots(16, 0));
            assertEquals(0, QuantityMath.requiredSlots(1, 0));
        }

        @Test
        void throwsOnZeroOrNegativeMaxStackSize() {
            assertThrows(IllegalArgumentException.class, () -> QuantityMath.requiredSlots(0, 10));
            assertThrows(IllegalArgumentException.class, () -> QuantityMath.requiredSlots(-1, 10));
        }

        @Test
        void calculatesSlotsForStandardStack64() {
            assertEquals(1, QuantityMath.requiredSlots(64, 1));
            assertEquals(1, QuantityMath.requiredSlots(64, 64));
            assertEquals(2, QuantityMath.requiredSlots(64, 65));
            assertEquals(2, QuantityMath.requiredSlots(64, 128));
            assertEquals(3, QuantityMath.requiredSlots(64, 129));
            assertEquals(36, QuantityMath.requiredSlots(64, 64 * 36));
            assertEquals(37, QuantityMath.requiredSlots(64, 64 * 36 + 1));
        }

        @Test
        void calculatesSlotsForSmallStack16() {
            assertEquals(1, QuantityMath.requiredSlots(16, 1));
            assertEquals(1, QuantityMath.requiredSlots(16, 16));
            assertEquals(2, QuantityMath.requiredSlots(16, 17));
            assertEquals(2, QuantityMath.requiredSlots(16, 32));
            assertEquals(3, QuantityMath.requiredSlots(16, 33));
        }

        @Test
        void calculatesSlotsForUnstackable1() {
            assertEquals(1, QuantityMath.requiredSlots(1, 1));
            assertEquals(2, QuantityMath.requiredSlots(1, 2));
            assertEquals(16, QuantityMath.requiredSlots(1, 16));
            assertEquals(36, QuantityMath.requiredSlots(1, 36));
        }

        @Test
        void handlesLargeQuantitiesWithoutOverflow() {
            assertEquals((int) ((Integer.MAX_VALUE + 63L) / 64), QuantityMath.requiredSlots(64, Integer.MAX_VALUE));
        }
    }

    @Nested
    @DisplayName("maxAffordable calculations")
    class MaxAffordableTests {
        @Test
        void returnsZeroForZeroOrNegativePrice() {
            assertEquals(0, QuantityMath.maxAffordable(0, 1000));
            assertEquals(0, QuantityMath.maxAffordable(-10, 1000));
        }

        @Test
        void returnsZeroForZeroOrNegativeBalance() {
            assertEquals(0, QuantityMath.maxAffordable(100, 0));
            assertEquals(0, QuantityMath.maxAffordable(100, -500));
        }

        @Test
        void calculatesAffordabilityCorrectly() {
            assertEquals(10, QuantityMath.maxAffordable(10, 100));
            assertEquals(10, QuantityMath.maxAffordable(10, 109));
            assertEquals(0, QuantityMath.maxAffordable(10, 9));
            assertEquals(1, QuantityMath.maxAffordable(100, 100));
            assertEquals(1, QuantityMath.maxAffordable(100, 199));
        }

        @Test
        void handlesLargeBalanceAndCapsAtIntegerMax() {
            assertEquals(Integer.MAX_VALUE, QuantityMath.maxAffordable(1, Long.MAX_VALUE));
            assertEquals(Integer.MAX_VALUE, QuantityMath.maxAffordable(1, (long) Integer.MAX_VALUE + 100L));
            assertEquals(1, QuantityMath.maxAffordable(Long.MAX_VALUE, Long.MAX_VALUE));
            assertEquals(0, QuantityMath.maxAffordable(Long.MAX_VALUE, Long.MAX_VALUE - 1));
        }
    }

    @Nested
    @DisplayName("totalPrice calculations")
    class TotalPriceTests {
        @Test
        void returnsZeroWhenPriceOrQuantityIsZero() {
            assertEquals(0L, QuantityMath.totalPrice(0L, 100));
            assertEquals(0L, QuantityMath.totalPrice(100L, 0));
            assertEquals(0L, QuantityMath.totalPrice(0L, 0));
        }

        @Test
        void calculatesCorrectTotalPrice() {
            assertEquals(500L, QuantityMath.totalPrice(100L, 5));
            assertEquals(6400L, QuantityMath.totalPrice(100L, 64));
            assertEquals(100000000000L, QuantityMath.totalPrice(1000000000L, 100));
        }

        @Test
        void throwsOnNegativeArguments() {
            assertThrows(IllegalArgumentException.class, () -> QuantityMath.totalPrice(-1L, 10));
            assertThrows(IllegalArgumentException.class, () -> QuantityMath.totalPrice(10L, -1));
        }

        @Test
        void throwsArithmeticExceptionOnOverflow() {
            assertThrows(ArithmeticException.class, () -> QuantityMath.totalPrice(Long.MAX_VALUE, 2));
            assertThrows(ArithmeticException.class, () -> QuantityMath.totalPrice(Long.MAX_VALUE / 2 + 1, 2));
        }
    }

    @Nested
    @DisplayName("clamp calculations")
    class ClampTests {
        @Test
        void clampsValuesWithinBounds() {
            assertEquals(5, QuantityMath.clamp(5, 1, 10));
            assertEquals(1, QuantityMath.clamp(0, 1, 10));
            assertEquals(1, QuantityMath.clamp(-5, 1, 10));
            assertEquals(10, QuantityMath.clamp(10, 1, 10));
            assertEquals(10, QuantityMath.clamp(15, 1, 10));
        }

        @Test
        void throwsWhenMinGreaterThanMax() {
            assertThrows(IllegalArgumentException.class, () -> QuantityMath.clamp(5, 10, 1));
        }
    }

    @Nested
    @DisplayName("maxBuyable calculations")
    class MaxBuyableTests {
        @Test
        void limitsByCapacityWhenAffordableIsHigher() {
            assertEquals(10, QuantityMath.maxBuyable(10, 1000L, 10L));
        }

        @Test
        void limitsByAffordabilityWhenCapacityIsHigher() {
            assertEquals(5, QuantityMath.maxBuyable(100, 50L, 10L));
        }

        @Test
        void returnsZeroWhenCapacityOrBalanceOrPriceIsZero() {
            assertEquals(0, QuantityMath.maxBuyable(0, 1000L, 10L));
            assertEquals(0, QuantityMath.maxBuyable(10, 0L, 10L));
            assertEquals(0, QuantityMath.maxBuyable(10, 1000L, 0L));
            assertEquals(0, QuantityMath.maxBuyable(-5, 1000L, 10L));
        }
    }
}
