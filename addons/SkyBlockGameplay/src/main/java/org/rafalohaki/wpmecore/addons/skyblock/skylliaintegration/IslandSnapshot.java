package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import java.util.UUID;

/**
 * Niezmiennik odczytu wyspy — jedyny typ island-context widoczny poza
 * {@code skylliaintegration}. Moduły ekonomii/progresji/GUI zależą od tego typu,
 * a nie od typów Skyllii (R-API-01).
 */
public record IslandSnapshot(UUID islandId, UUID ownerId, LifecycleState lifecycle,
                             IslandBounds bounds, IslandCapabilities capabilities,
                             long revision) {
}
