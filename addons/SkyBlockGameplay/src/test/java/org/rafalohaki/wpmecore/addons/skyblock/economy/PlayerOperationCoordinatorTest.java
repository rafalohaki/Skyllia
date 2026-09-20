package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerOperationCoordinatorTest {

    @Test
    void shopWandBankAndVaultShareOnePerPlayerLease() {
        PlayerOperationCoordinator coordinator = new PlayerOperationCoordinator();
        UUID playerId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        PlayerOperationCoordinator.Lease shop =
                coordinator.tryAcquire(playerId, true).orElseThrow();
        assertTrue(coordinator.isBusy(playerId));
        assertTrue(coordinator.owns(shop));
        assertTrue(coordinator.tryAcquire(playerId, false).isEmpty());

        coordinator.release(shop);
        PlayerOperationCoordinator.Lease bank =
                coordinator.tryAcquire(playerId, false).orElseThrow();
        assertTrue(coordinator.owns(bank));
        assertFalse(bank.inventorySensitive());
    }

    @Test
    void staleCompletionCannotReleaseANewerOperation() {
        PlayerOperationCoordinator coordinator = new PlayerOperationCoordinator();
        UUID playerId = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
        PlayerOperationCoordinator.Lease first =
                coordinator.tryAcquire(playerId, true).orElseThrow();
        coordinator.release(first);
        PlayerOperationCoordinator.Lease second =
                coordinator.tryAcquire(playerId, true).orElseThrow();

        coordinator.release(first);

        assertTrue(coordinator.owns(second));
        assertTrue(coordinator.isBusy(playerId));
    }

    @Test
    void startupAndPerPlayerGuardsComposeWithoutAnInventoryMovementGap() {
        PlayerOperationCoordinator coordinator = new PlayerOperationCoordinator();
        UUID playerId = UUID.fromString("cccccccc-dddd-eeee-ffff-aaaaaaaaaaaa");

        assertTrue(coordinator.isInventoryMovementBlocked(playerId));

        coordinator.guardInventory(playerId);
        coordinator.openInventoryGate();
        assertTrue(coordinator.isInventoryMovementBlocked(playerId));

        coordinator.releaseInventoryGuard(playerId);
        assertFalse(coordinator.isInventoryMovementBlocked(playerId));
    }

    @Test
    void stopClosesTheGateRetainsLeasesAndRejectsLateOperations() {
        PlayerOperationCoordinator coordinator = new PlayerOperationCoordinator();
        UUID playerId = UUID.fromString("dddddddd-eeee-ffff-aaaa-bbbbbbbbbbbb");
        coordinator.openInventoryGate();
        PlayerOperationCoordinator.Lease lease =
                coordinator.tryAcquire(playerId, true).orElseThrow();

        coordinator.stop();
        coordinator.release(lease);

        assertTrue(coordinator.owns(lease));
        assertTrue(coordinator.isInventoryMovementBlocked(playerId));
        assertTrue(coordinator.tryAcquire(
                UUID.fromString("eeeeeeee-ffff-aaaa-bbbb-cccccccccccc"),
                false).isEmpty());
    }
}
