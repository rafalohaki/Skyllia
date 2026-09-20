package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IslandViewTest {

    private IslandView viewWith(IslandRole role) {
        return new IslandView(
                new IslandSnapshot(UUID.randomUUID(), UUID.randomUUID(), LifecycleState.active(),
                        IslandBounds.of(0, 0, 100), IslandCapabilities.allEnabled(), 1L),
                role);
    }

    @Test
    void canWithdrawForOwnerOrCoOwnerOnly() {
        assertTrue(viewWith(IslandRole.OWNER).canWithdraw());
        assertTrue(viewWith(IslandRole.CO_OWNER).canWithdraw());
        assertFalse(viewWith(IslandRole.MEMBER).canWithdraw());
        assertFalse(viewWith(IslandRole.VISITOR).canWithdraw());
    }

    @Test
    void unknownRoleIsNotKnown() {
        IslandView v = viewWith(IslandRole.UNKNOWN);
        assertFalse(v.roleKnown());
        assertFalse(v.canWithdraw());
    }

    @Test
    void delegatesIdentityToSnapshot() {
        UUID i = UUID.randomUUID(), o = UUID.randomUUID();
        IslandView v = new IslandView(
                new IslandSnapshot(i, o, LifecycleState.active(), IslandBounds.of(0, 0, 100),
                        IslandCapabilities.allEnabled(), 1L), IslandRole.MEMBER);
        assertEquals(i, v.islandId());
        assertEquals(o, v.ownerId());
        assertEquals(IslandRole.MEMBER, v.role());
    }

    // --- scalone z IslandSnapshotTest (snapshot/rola/capabilities to ten sam kontrakt co widok) ---


    @Test
    void snapshotIsImmutableAndCarriesIdentity() {
        UUID island = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        IslandSnapshot s = new IslandSnapshot(island, owner,
                LifecycleState.active(), IslandBounds.of(1, 2, 100.0),
                IslandCapabilities.allEnabled(), 7L);
        assertEquals(island, s.islandId());
        assertEquals(owner, s.ownerId());
        assertTrue(s.lifecycle().confirmsActive());
        assertEquals(7L, s.revision());
    }

    @Test
    void unknownRoleIsDistinctFromMember() {
        assertNotEquals(IslandRole.UNKNOWN, IslandRole.MEMBER);
    }

    @Test
    void capabilityFlagDefaultsNotSupported() {
        IslandCapabilities caps = IslandCapabilities.none();
        assertFalse(caps.supports(IslandCapabilities.Flag.WARP));
        assertFalse(caps.supports(IslandCapabilities.Flag.TRANSFER));
    }
}
