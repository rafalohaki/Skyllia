package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rozróżnienie ograniczonej i nieograniczonej gałęzi jest najważniejszą regułą
 * całego silnika ponawiania: awaria bazy sama się goi i porzucenie przy niej
 * trwałej operacji rozjeżdża pieniądze z przedmiotami, a niezbieżność logiki
 * nie zagoi się nigdy i trzyma gracza zamrożonego.
 */
class ConvergenceRetryPolicyTest {

    @Test
    void startsFastFromTheFirstFailure() {
        ConvergenceRetryPolicy.Step step = ConvergenceRetryPolicy.next(0, true);

        assertEquals(ConvergenceRetryPolicy.Decision.RETRY_FAST, step.decision());
        assertEquals(ConvergenceRetryPolicy.FAST_DELAY_TICKS, step.delayTicks());
        assertFalse(step.crossedIntoSlowMode());
    }

    @Test
    void staysFastUntilTheFastBudgetIsSpent() {
        ConvergenceRetryPolicy.Step last = ConvergenceRetryPolicy.next(
                ConvergenceRetryPolicy.FAST_ATTEMPTS - 1, true);

        assertEquals(ConvergenceRetryPolicy.Decision.RETRY_FAST, last.decision());
        assertFalse(last.crossedIntoSlowMode());
    }

    @Test
    void switchesToSlowExactlyOnceAtTheBoundary() {
        ConvergenceRetryPolicy.Step boundary = ConvergenceRetryPolicy.next(
                ConvergenceRetryPolicy.FAST_ATTEMPTS, true);

        assertEquals(ConvergenceRetryPolicy.Decision.RETRY_SLOW, boundary.decision());
        assertEquals(ConvergenceRetryPolicy.SLOW_DELAY_TICKS, boundary.delayTicks());
        assertTrue(boundary.crossedIntoSlowMode(), "ostrzeżenie ma pójść dokładnie raz");

        ConvergenceRetryPolicy.Step after = ConvergenceRetryPolicy.next(
                ConvergenceRetryPolicy.FAST_ATTEMPTS + 1, true);

        assertEquals(ConvergenceRetryPolicy.Decision.RETRY_SLOW, after.decision());
        assertFalse(after.crossedIntoSlowMode());
    }

    @Test
    void crossesIntoSlowModeOnlyOnceOverTheWholeSequence() {
        int crossings = 0;
        for (int attempt = 0; attempt <= ConvergenceRetryPolicy.TOTAL_ATTEMPTS; attempt++) {
            if (ConvergenceRetryPolicy.next(attempt, true).crossedIntoSlowMode()) {
                crossings++;
            }
        }

        assertEquals(1, crossings);
    }

    @Test
    void quarantinesTheBoundedBranchExactlyAtTheTotalBudget() {
        assertEquals(ConvergenceRetryPolicy.Decision.RETRY_SLOW,
                ConvergenceRetryPolicy.next(
                        ConvergenceRetryPolicy.TOTAL_ATTEMPTS - 1, true).decision());

        ConvergenceRetryPolicy.Step exhausted = ConvergenceRetryPolicy.next(
                ConvergenceRetryPolicy.TOTAL_ATTEMPTS, true);

        assertEquals(ConvergenceRetryPolicy.Decision.QUARANTINE, exhausted.decision());
        assertEquals(0L, exhausted.delayTicks(), "kwarantanna nic nie planuje");
    }

    /**
     * Awaria bazy nigdy nie porzuca operacji. Gdyby ta gałąź kiedykolwiek
     * zaczęła zwracać QUARANTINE, chwilowy problem z SQL-em rozjechałby saldo
     * z ekwipunkiem bez śladu.
     */
    @Test
    void neverQuarantinesTheUnboundedBranch() {
        for (int attempt : new int[]{0, ConvergenceRetryPolicy.FAST_ATTEMPTS,
                ConvergenceRetryPolicy.TOTAL_ATTEMPTS,
                ConvergenceRetryPolicy.TOTAL_ATTEMPTS * 100, Integer.MAX_VALUE}) {
            ConvergenceRetryPolicy.Step step = ConvergenceRetryPolicy.next(attempt, false);

            assertTrue(step.decision() == ConvergenceRetryPolicy.Decision.RETRY_FAST
                            || step.decision() == ConvergenceRetryPolicy.Decision.RETRY_SLOW,
                    "próba " + attempt + " dała " + step.decision());
        }
    }

    @Test
    void unboundedBranchKeepsBackingOffInsteadOfHammering() {
        ConvergenceRetryPolicy.Step far = ConvergenceRetryPolicy.next(Integer.MAX_VALUE, false);

        assertEquals(ConvergenceRetryPolicy.Decision.RETRY_SLOW, far.decision());
        assertEquals(ConvergenceRetryPolicy.SLOW_DELAY_TICKS, far.delayTicks());
    }

    @Test
    void rejectsANegativeAttemptCount() {
        assertThrows(IllegalArgumentException.class,
                () -> ConvergenceRetryPolicy.next(-1, true));
    }

    @Test
    void fastBudgetIsSmallerThanTheTotalBudget() {
        assertTrue(ConvergenceRetryPolicy.FAST_ATTEMPTS < ConvergenceRetryPolicy.TOTAL_ATTEMPTS,
                "inaczej tryb wolny nigdy by nie wystąpił");
        assertTrue(ConvergenceRetryPolicy.FAST_DELAY_TICKS
                        < ConvergenceRetryPolicy.SLOW_DELAY_TICKS,
                "tryb wolny ma odciążać, nie dociążać");
    }
}
