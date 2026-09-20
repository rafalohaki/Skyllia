package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class OneBlockState {

    private final UUID islandId;
    private final String worldName;
    private final int x;
    private final int y;
    private final int z;
    private final AtomicReference<String> phaseId;
    private final AtomicInteger progress;
    private final AtomicInteger totalMined;
    private final AtomicInteger dryStreak;
    private volatile boolean dirty;

    public OneBlockState(@NotNull UUID islandId, @NotNull String worldName,
                         int x, int y, int z, @NotNull String phaseId,
                         int progress, int totalMined, int dryStreak) {
        this.islandId = islandId;
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.phaseId = new AtomicReference<>(phaseId);
        this.progress = new AtomicInteger(progress);
        this.totalMined = new AtomicInteger(totalMined);
        this.dryStreak = new AtomicInteger(dryStreak);
    }

    public @NotNull UUID islandId() { return islandId; }
    public @NotNull String worldName() { return worldName; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }

    public @NotNull String phaseId() { return phaseId.get(); }
    public void setPhaseId(@NotNull String newPhaseId) { phaseId.set(newPhaseId); }

    public int progress() { return progress.get(); }
    public int incrementProgress() { return progress.incrementAndGet(); }
    public void resetProgress() { progress.set(0); }

    public int totalMined() { return totalMined.get(); }
    public int incrementTotalMined() { return totalMined.incrementAndGet(); }

    public int dryStreak() { return dryStreak.get(); }
    public int incrementDryStreak() { return dryStreak.incrementAndGet(); }
    public void resetDryStreak() { dryStreak.set(0); }

    public void markDirty() { dirty = true; }
    public void clearDirty() { dirty = false; }
    public boolean isDirty() { return dirty; }

    public record LocationKey(@NotNull String world, int x, int y, int z) {}

    public @NotNull LocationKey locationKey() {
        return new LocationKey(worldName, x, y, z);
    }
}
