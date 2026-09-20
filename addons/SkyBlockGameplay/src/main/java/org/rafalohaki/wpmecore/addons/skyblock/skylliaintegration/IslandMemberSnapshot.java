package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import java.util.UUID;

/** Niezmiennik członkostwa/roli; owner/role do odczytu poza integracją. */
public record IslandMemberSnapshot(UUID playerUuid, String name, IslandRole role) {
    public IslandMemberSnapshot {
        if (name == null || name.isBlank()) name = "?";
    }
}
