package org.rafalohaki.wpmecore.addons.skyblock.hub;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class SkyBlockHubJoinSpawnTest {

    @Test
    void replacesLoginPlacementWithConfiguredHub() {
        Location original = new Location(null, 10.0D, 70.0D, 10.0D);
        Location hub = new Location(null, 8.5D, 80.0D, -4.5D, 90.0F, 0.0F);
        AtomicReference<Location> selected = new AtomicReference<>(original);

        SkyBlockHub.applyJoinSpawn(true, hub, selected::set);

        assertNotSame(hub, selected.get());
        assertEquals(hub, selected.get());
    }

    @Test
    void preservesSavedLocationWhenJoinTeleportIsDisabled() {
        Location original = new Location(null, 10.0D, 70.0D, 10.0D);
        Location hub = new Location(null, 8.5D, 80.0D, -4.5D);
        AtomicReference<Location> selected = new AtomicReference<>(original);

        SkyBlockHub.applyJoinSpawn(false, hub, selected::set);

        assertSame(original, selected.get());
    }

    @Test
    void preservesSavedLocationWhenHubWorldIsUnavailable() {
        Location original = new Location(null, 10.0D, 70.0D, 10.0D);
        AtomicReference<Location> selected = new AtomicReference<>(original);

        SkyBlockHub.applyJoinSpawn(true, null, selected::set);

        assertSame(original, selected.get());
    }
}
