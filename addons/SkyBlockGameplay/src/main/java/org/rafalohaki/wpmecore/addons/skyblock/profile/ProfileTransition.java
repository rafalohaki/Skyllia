package org.rafalohaki.wpmecore.addons.skyblock.profile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record ProfileTransition(
        @NotNull String operationId,
        @NotNull UUID profileId,
        @NotNull UUID playerUuid,
        @NotNull String transitionType,
        @NotNull String fromStatus,
        @NotNull String toStatus,
        @Nullable String checkpoint,
        @Nullable String result,
        long createdAt,
        long updatedAt) {
}
