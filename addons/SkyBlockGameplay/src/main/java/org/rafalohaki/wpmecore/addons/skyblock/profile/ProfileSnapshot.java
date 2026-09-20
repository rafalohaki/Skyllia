package org.rafalohaki.wpmecore.addons.skyblock.profile;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record ProfileSnapshot(
        @NotNull String snapshotId,
        @NotNull UUID profileId,
        @NotNull UUID playerUuid,
        @NotNull String serverScope,
        @NotNull String snapshotType,
        @Nullable byte[] payload,
        @NotNull String payloadSha256,
        @NotNull String contentSha256,
        long createdAt) {

    public ProfileSnapshot {
        payload = payload == null ? null : payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload == null ? null : payload.clone();
    }
}
