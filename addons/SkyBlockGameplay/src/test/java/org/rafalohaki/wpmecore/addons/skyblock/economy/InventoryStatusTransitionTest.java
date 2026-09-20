package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.InventoryStatus.COMPLETE;
import static org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.InventoryStatus.DELIVERED;
import static org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.InventoryStatus.PENDING;
import static org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.InventoryStatus.QRTN_DELIVERED;
import static org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao.InventoryStatus.QRTN_PENDING;

/**
 * Lista zezwoleń zamiast listy zakazów.
 *
 * <p>Poprzednia wersja bramkowała przejścia listą zakazów, przez co dodanie
 * jakiegokolwiek nowego stanu po cichu zabraniało właśnie tego przejścia, które
 * miało być dozwolone — {@code PENDING -> QRTN_PENDING} kończyłoby się
 * {@code IllegalArgumentException} rzuconym synchronicznie z miejsca, z którego
 * dzierżawa ekwipunku nigdy by się nie zwolniła, zostawiając gracza zamrożonego
 * na zawsze. Ta macierz jest wypisana w całości właśnie po to, żeby kolejny
 * dopisany stan wymusił świadomą decyzję.
 */
class InventoryStatusTransitionTest {

    private static final Map<LedgerDao.InventoryStatus, Set<LedgerDao.InventoryStatus>> ALLOWED =
            Map.of(
                    PENDING, EnumSet.of(DELIVERED, COMPLETE, QRTN_PENDING),
                    DELIVERED, EnumSet.of(COMPLETE, QRTN_DELIVERED),
                    QRTN_PENDING, EnumSet.of(PENDING, COMPLETE),
                    QRTN_DELIVERED, EnumSet.of(DELIVERED, COMPLETE),
                    COMPLETE, EnumSet.noneOf(LedgerDao.InventoryStatus.class));

    @Test
    void everyPairInTheMatrixMatchesTheDeclaredContract() {
        for (LedgerDao.InventoryStatus from : LedgerDao.InventoryStatus.values()) {
            Set<LedgerDao.InventoryStatus> allowed = ALLOWED.get(from);
            assertTrue(allowed != null, "macierz nie opisuje stanu " + from);
            for (LedgerDao.InventoryStatus to : LedgerDao.InventoryStatus.values()) {
                assertEquals(allowed.contains(to), from.canTransitionTo(to),
                        from + " -> " + to);
            }
        }
    }

    @Test
    void quarantineIsReachableFromBothLiveStates() {
        assertTrue(PENDING.canTransitionTo(QRTN_PENDING));
        assertTrue(DELIVERED.canTransitionTo(QRTN_DELIVERED));
    }

    /** Kwarantanna zapamiętuje stan, z którego przyszła — krzyżowanie ich gubi tę informację. */
    @Test
    void quarantineDoesNotCrossBetweenTheTwoLiveStates() {
        assertFalse(PENDING.canTransitionTo(QRTN_DELIVERED));
        assertFalse(DELIVERED.canTransitionTo(QRTN_PENDING));
    }

    @Test
    void quarantineReturnsOnlyToTheStateItCameFrom() {
        assertTrue(QRTN_PENDING.canTransitionTo(PENDING));
        assertFalse(QRTN_PENDING.canTransitionTo(DELIVERED));
        assertTrue(QRTN_DELIVERED.canTransitionTo(DELIVERED));
        assertFalse(QRTN_DELIVERED.canTransitionTo(PENDING));
    }

    @Test
    void completeIsTerminal() {
        for (LedgerDao.InventoryStatus to : LedgerDao.InventoryStatus.values()) {
            assertFalse(COMPLETE.canTransitionTo(to), "COMPLETE -> " + to);
        }
    }

    @Test
    void noStatusTransitionsToItself() {
        for (LedgerDao.InventoryStatus status : LedgerDao.InventoryStatus.values()) {
            assertFalse(status.canTransitionTo(status), status + " -> " + status);
        }
    }

    /** Zapisujemy nazwy do VARCHAR(16); dłuższa stała ucięłaby się w bazie. */
    @Test
    void everyStatusNameFitsTheColumn() {
        for (LedgerDao.InventoryStatus status : LedgerDao.InventoryStatus.values()) {
            assertTrue(status.name().length() <= 16,
                    status + " ma " + status.name().length() + " znaków");
        }
    }

    @Test
    void quarantineStatesAreRecognisable() {
        assertTrue(QRTN_PENDING.isQuarantined());
        assertTrue(QRTN_DELIVERED.isQuarantined());
        assertFalse(PENDING.isQuarantined());
        assertFalse(DELIVERED.isQuarantined());
        assertFalse(COMPLETE.isQuarantined());
    }
}
