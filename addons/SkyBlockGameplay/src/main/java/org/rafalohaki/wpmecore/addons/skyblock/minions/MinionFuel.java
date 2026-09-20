package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.jetbrains.annotations.Nullable;

/** Czysta matematyka paliwa — wall-clock epoch-millis, niezależna od chunków. */
public final class MinionFuel {

    private static final long MIN_INTERVAL_MILLIS = 250L;

    private MinionFuel() { }

    public static boolean isActive(long fuelExpiresAt, long nowMillis) {
        return fuelExpiresAt > nowMillis;
    }

    public static double multiplier(@Nullable MinionsConfig.FuelDef fuel,
                                    long fuelExpiresAt, long nowMillis) {
        return isActive(fuelExpiresAt, nowMillis) && fuel != null
                ? fuel.speedMultiplier()
                : 1.0;
    }

    public static long remainingSeconds(long fuelExpiresAt, long nowMillis) {
        return Math.max(0L, (fuelExpiresAt - nowMillis) / 1000L);
    }

    public static long effectiveIntervalMillis(double intervalSeconds, double multiplier) {
        double safe = multiplier <= 0.0 ? 1.0 : multiplier;
        return Math.max(MIN_INTERVAL_MILLIS, Math.round(intervalSeconds * 1000.0 / safe));
    }
}
