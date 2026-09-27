package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.rafalohaki.wpmecore.addons.skyblock.forge.LeaseCarryPolicy.ClickVerb;
import org.rafalohaki.wpmecore.addons.skyblock.forge.LeaseCarryPolicy.ViewKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ECO-10: decyzje strażnika wynoszenia dzierżawy — czysta logika bez serwera
 * ({@link LeaseCarryPolicy}). Kliknięcie nie może dopuścić do włożenia
 * znacznika {@code lease_expiry} do obcego inwentarza ani wyrzucenia go na
 * ziemię; zabieranie z kontenerów z powrotem do siebie jest zawsze dozwolone.
 */
class LeaseCarryPolicyTest {

    @Test
    void cursorPlaceIntoTopInventoryIsBlocked() {
        assertTrue(LeaseCarryPolicy.depositsLease(
                ClickVerb.PLACE_FROM_CURSOR, true, ViewKind.CONTAINER, true, false, false));
        assertTrue(LeaseCarryPolicy.depositsLease(
                ClickVerb.PLACE_FROM_CURSOR, true, ViewKind.STOOL, true, false, false));
    }

    @Test
    void cursorPlaceIntoOwnInventoryIsAllowed() {
        assertFalse(LeaseCarryPolicy.depositsLease(
                ClickVerb.PLACE_FROM_CURSOR, false, ViewKind.CONTAINER, true, false, false));
        assertFalse(LeaseCarryPolicy.depositsLease(
                ClickVerb.PLACE_FROM_CURSOR, false, ViewKind.STOOL, true, false, false));
    }

    @Test
    void shiftFromOwnInventoryIntoContainerIsBlocked() {
        assertTrue(LeaseCarryPolicy.depositsLease(
                ClickVerb.SHIFT_MOVE, false, ViewKind.CONTAINER, false, true, false));
    }

    @Test
    void shiftFromOwnInventoryInStoolViewStaysAllowed() {
        assertFalse(LeaseCarryPolicy.depositsLease(
                ClickVerb.SHIFT_MOVE, false, ViewKind.STOOL, false, true, false));
    }

    @Test
    void shiftFromContainerBackToPlayerIsAllowed() {
        assertFalse(LeaseCarryPolicy.depositsLease(
                ClickVerb.SHIFT_MOVE, true, ViewKind.CONTAINER, false, true, false));
    }

    @Test
    void hotbarKeyIntoContainerSlotIsBlocked() {
        assertTrue(LeaseCarryPolicy.depositsLease(
                ClickVerb.HOTBAR_SWAP, true, ViewKind.CONTAINER, false, false, true));
        // Ten sam klawisz nad własnym ekwipunkiem zostaje w środku.
        assertFalse(LeaseCarryPolicy.depositsLease(
                ClickVerb.HOTBAR_SWAP, false, ViewKind.CONTAINER, false, false, true));
    }

    @Test
    void groundDropsAreBlocked() {
        assertTrue(LeaseCarryPolicy.depositsLease(
                ClickVerb.DROP_FROM_CURSOR, false, ViewKind.CONTAINER, true, false, false));
        assertTrue(LeaseCarryPolicy.depositsLease(
                ClickVerb.DROP_FROM_SLOT, true, ViewKind.CONTAINER, false, true, false));
    }

    @Test
    void takingItemsOutIsNeverBlocked() {
        assertFalse(LeaseCarryPolicy.depositsLease(
                ClickVerb.TAKE, true, ViewKind.CONTAINER, false, true, false));
        assertFalse(LeaseCarryPolicy.depositsLease(
                ClickVerb.TAKE, true, ViewKind.CONTAINER, true, false, false));
    }

    @Test
    void itemsWithoutLeaseNeverBlock() {
        for (ClickVerb verb : ClickVerb.values()) {
            assertFalse(LeaseCarryPolicy.depositsLease(
                    verb, true, ViewKind.CONTAINER, false, false, false),
                    "bez dzierżawy nie wolno blokować: " + verb);
        }
    }

    @Test
    void dragReachingTopInventoryIsBlockedOnlyWithLeaseOnCursor() {
        assertTrue(LeaseCarryPolicy.dragDepositsLease(true, List.of(3, 30, 31), 27));
        assertFalse(LeaseCarryPolicy.dragDepositsLease(false, List.of(3, 30, 31), 27));
        assertFalse(LeaseCarryPolicy.dragDepositsLease(true, List.of(27, 28), 27));
    }
}
