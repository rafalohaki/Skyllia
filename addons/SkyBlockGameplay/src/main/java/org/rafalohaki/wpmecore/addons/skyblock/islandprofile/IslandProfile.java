package org.rafalohaki.wpmecore.addons.skyblock.islandprofile;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Wpme-owned projection of an island profile. Skyllia remains the source of
 * truth for world membership and roles; this table is the Wpme guard that
 * enforces exactly one ACTIVE island per UUID (R-MEM-01) and carries the
 * mode/status state machine.
 */
public record IslandProfile(
        @NotNull UUID islandId,
        @NotNull UUID ownerUuid,
        @NotNull String mode,
        @NotNull ProfileStatus status,
        long createdAt,
        long updatedAt) {

    public enum ProfileStatus {
        ACTIVE,
        CREATING,
        DELETED,
        ARCHIVED;

        public static @NotNull ProfileStatus parse(@NotNull String raw) {
            try {
                return valueOf(raw.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ACTIVE;
            }
        }
    }
}
