package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Niemutowalny stan jednego minionka; storage to uporządkowana mapa
 * {@code nazwa_materialu -> liczba} albo {@code custom:<id> -> liczba}.
 * Kodek "KEY=count;KEY=count" używa separatora '=', bo klucze custom zawierają ':'.
 */
public record MinionRecord(
        @NotNull UUID minionId,
        @NotNull UUID islandId,
        @NotNull String typeId,
        int tier,
        @NotNull String world,
        double x, double y, double z,
        @NotNull Map<String, Long> storage,
        @Nullable String fuelType,
        long fuelExpiresAt,
        boolean compactorEnabled,
        @Nullable Integer linkedChestX, @Nullable Integer linkedChestY, @Nullable Integer linkedChestZ,
        long totalGenerated,
        long createdAt,
        long updatedAt) {

    public MinionRecord {
        storage = Collections.unmodifiableMap(new LinkedHashMap<>(storage));
    }

    public int blockX() { return (int) Math.floor(x); }
    public int blockY() { return (int) Math.floor(y); }
    public int blockZ() { return (int) Math.floor(z); }

    public @NotNull MinionRecord withStorage(@NotNull Map<String, Long> storageNow) {
        return new MinionRecord(minionId, islandId, typeId, tier, world, x, y, z, storageNow,
                fuelType, fuelExpiresAt, compactorEnabled, linkedChestX, linkedChestY, linkedChestZ,
                totalGenerated, createdAt, System.currentTimeMillis());
    }

    public @NotNull MinionRecord withGeneration(long generated, @NotNull Map<String, Long> storageNow) {
        return new MinionRecord(minionId, islandId, typeId, tier, world, x, y, z, storageNow,
                fuelType, fuelExpiresAt, compactorEnabled, linkedChestX, linkedChestY, linkedChestZ,
                totalGenerated + generated, createdAt, System.currentTimeMillis());
    }

    public @NotNull MinionRecord withTier(int newTier) {
        return new MinionRecord(minionId, islandId, typeId, newTier, world, x, y, z, storage,
                fuelType, fuelExpiresAt, compactorEnabled, linkedChestX, linkedChestY, linkedChestZ,
                totalGenerated, createdAt, System.currentTimeMillis());
    }

    public @NotNull MinionRecord withFuel(@Nullable String newFuelType, long newFuelExpiresAt) {
        return new MinionRecord(minionId, islandId, typeId, tier, world, x, y, z, storage,
                newFuelType, newFuelExpiresAt, compactorEnabled, linkedChestX, linkedChestY, linkedChestZ,
                totalGenerated, createdAt, System.currentTimeMillis());
    }

    public @NotNull MinionRecord withCompactor(boolean enabled) {
        return new MinionRecord(minionId, islandId, typeId, tier, world, x, y, z, storage,
                fuelType, fuelExpiresAt, enabled, linkedChestX, linkedChestY, linkedChestZ,
                totalGenerated, createdAt, System.currentTimeMillis());
    }

    public @NotNull String storageEncoded() {
        return encodeStorage(storage);
    }

    public static @NotNull String encodeStorage(@NotNull Map<String, Long> storage) {
        StringJoiner joiner = new StringJoiner(";");
        for (Map.Entry<String, Long> entry : storage.entrySet()) {
            joiner.add(entry.getKey() + "=" + entry.getValue());
        }
        return joiner.toString();
    }

    public static @NotNull Map<String, Long> decodeStorage(@NotNull String encoded) {
        Map<String, Long> storage = new LinkedHashMap<>();
        if (encoded.isBlank()) {
            return storage;
        }
        for (String segment : encoded.split(";")) {
            int split = segment.lastIndexOf('=');
            if (split <= 0 || split == segment.length() - 1) {
                continue;
            }
            try {
                storage.put(segment.substring(0, split), Long.parseLong(segment.substring(split + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return storage;
    }
}
