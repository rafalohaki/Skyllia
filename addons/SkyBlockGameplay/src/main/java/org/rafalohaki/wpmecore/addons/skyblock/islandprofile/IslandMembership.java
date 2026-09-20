package org.rafalohaki.wpmecore.addons.skyblock.islandprofile;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record IslandMembership(
        @NotNull UUID islandId,
        @NotNull UUID playerUuid,
        @NotNull String role,
        @NotNull MembershipStatus status,
        long joinedAt,
        long updatedAt) {

    public enum MembershipStatus {
        ACTIVE,
        LEFT,
        KICKED,
        EXPIRED;

        public static @NotNull MembershipStatus parse(@NotNull String raw) {
            try {
                return valueOf(raw.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ACTIVE;
            }
        }
    }
}
