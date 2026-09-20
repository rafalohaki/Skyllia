package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * M2.5: domyślna implementacja punktów sezonowych (spec §3, §2.1).
 *
 * <p>Progi odblokowań questów sezonowych — rozwinięte wyspy nie kończą
 * sezonu w dzień. Catch-up +20% w końcówce sezonu dla doganiających.
 */
public final class DefaultSeasonPointService implements SeasonPointService {

    /** Progi punktowe odblokowania kolejnych paczek questów (spec §2.1). */
    static final long[] UNLOCK_THRESHOLDS = {0L, 300L, 800L, 1_600L, 3_000L};
    /** Ile questów otwiera każdy próg. */
    static final int[] SLOTS_PER_TIER = {8, 8, 8, 8, 8};

    private final SeasonTierEngine tierEngine =
            new SeasonTierEngine(UNLOCK_THRESHOLDS, SLOTS_PER_TIER[0]);

    private static final double CATCH_UP_MULTIPLIER = 1.20D;
    private static final int CATCH_UP_LAST_DAYS = 14;

    private final SeasonPointDao dao;
    private final java.util.function.LongSupplier seasonEndEpochMillis;
    private final java.util.function.IntSupplier seasonSupplier;
    private final ZoneId zone;
    /** Mnożnik weekendowy z configu ({@code season.weekend-points-multiplier}); 1.0 = off. */
    private final double weekendMultiplier;

    /** Mnożnik z rotacyjnego eventu punktowego (SkyBlockEventsService); 1.0 = off. */
    private volatile java.util.function.DoubleSupplier eventMultiplier = () -> 1.0;

    /** Dopina event punktowy do mnożenia nagród — woła korzeń po złożeniu serwisów. */
    public void setEventMultiplier(@NotNull java.util.function.DoubleSupplier multiplier) {
        this.eventMultiplier = multiplier;
    }
    /** Seam testowy: skąd bierzemy „dzisiaj” (default: strefa sezonu). */
    private final java.util.function.Supplier<java.time.DayOfWeek> today;

    private final ConcurrentHashMap<UUID, CachedPoints> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5_000L;

    private record CachedPoints(long points, long cachedAt) { }

    public DefaultSeasonPointService(@NotNull SeasonPointDao dao,
                                     @NotNull java.util.function.IntSupplier seasonSupplier,
                                     @NotNull java.util.function.LongSupplier seasonEndEpochMillis,
                                     @NotNull ZoneId zone) {
        this(dao, seasonSupplier, seasonEndEpochMillis, zone, 1.0D, () -> today(zone));
    }

    public DefaultSeasonPointService(@NotNull SeasonPointDao dao,
                                     @NotNull java.util.function.IntSupplier seasonSupplier,
                                     @NotNull java.util.function.LongSupplier seasonEndEpochMillis,
                                     @NotNull ZoneId zone, double weekendMultiplier) {
        this(dao, seasonSupplier, seasonEndEpochMillis, zone, weekendMultiplier, () -> today(zone));
    }

    public DefaultSeasonPointService(@NotNull SeasonPointDao dao,
                                     @NotNull java.util.function.IntSupplier seasonSupplier,
                                     @NotNull java.util.function.LongSupplier seasonEndEpochMillis,
                                     @NotNull ZoneId zone, double weekendMultiplier,
                                     @NotNull java.util.function.Supplier<java.time.DayOfWeek> today) {
        this.dao = dao;
        this.seasonSupplier = seasonSupplier;
        this.seasonEndEpochMillis = seasonEndEpochMillis;
        this.zone = zone;
        this.weekendMultiplier = weekendMultiplier;
        this.today = today;
    }

    private static @NotNull java.time.DayOfWeek today(@NotNull ZoneId zone) {
        return java.time.ZonedDateTime.now(zone).getDayOfWeek();
    }

    @Override
    public int currentSeason() {
        return seasonSupplier.getAsInt();
    }

    @Override
    public @NotNull CompletableFuture<Boolean> award(@NotNull UUID playerUuid, long points,
                                                     @NotNull String operationId) {
        return award(playerUuid, points, operationId, true);
    }

    @Override
    public @NotNull CompletableFuture<Boolean> award(@NotNull UUID playerUuid, long points,
                                                     @NotNull String operationId,
                                                     boolean countAsQuest) {
        if (points <= 0) return CompletableFuture.completedFuture(false);
        long effective = points;
        if (isWeekendBonusActive()) {
            effective = Math.round(effective * weekendMultiplier);
        }
        if (isCatchUpActive()) {
            effective = Math.round(effective * CATCH_UP_MULTIPLIER);
        }
        double eventMult = eventMultiplier.getAsDouble();
        if (eventMult > 1.0D && Double.isFinite(eventMult)) {
            effective = Math.round(effective * eventMult);
        }
        return dao.addPoints(playerUuid, currentSeason(), effective, operationId, countAsQuest)
                .thenApply(ok -> {
                    if (ok) cache.remove(playerUuid);
                    return ok;
                });
    }

    @Override
    public @NotNull CompletableFuture<Long> pointsOf(@NotNull UUID playerUuid) {
        int season = currentSeason();
        CachedPoints cached = cache.get(playerUuid);
        if (cached != null && System.currentTimeMillis() - cached.cachedAt < CACHE_TTL_MS
                && !isCatchUpActive()) { // w catch-up punkty mogą rosnąć szybciej — krótszy TTL i tak jest
            return CompletableFuture.completedFuture(cached.points);
        }
        return dao.points(playerUuid, season).thenApply(p -> {
            cache.put(playerUuid, new CachedPoints(p, System.currentTimeMillis()));
            return p;
        });
    }

    @Override
    public @NotNull CompletableFuture<Optional<Integer>> rankOf(@NotNull UUID playerUuid) {
        return dao.rank(playerUuid, currentSeason());
    }

    /** TOP-N bez nicków — warstwa wyżej dokłada nazwy (jak LeaderboardService). */
    @Override
    public @NotNull CompletableFuture<List<SeasonPointDao.TopRow>> top(int limit) {
        return dao.top(currentSeason(), limit);
    }

    @Override
    public int unlockedQuestSlots(long seasonPoints) {
        return tierEngine.unlockedQuestSlots(seasonPoints);
    }

    @Override
    public boolean isCatchUpActive() {
        long end = seasonEndEpochMillis.getAsLong();
        long daysLeft = Duration.between(Instant.now(),
                Instant.ofEpochMilli(end).atZone(zone).toInstant()).toDays();
        return daysLeft < CATCH_UP_LAST_DAYS && daysLeft >= 0;
    }

    /**
     * Bonus weekendowy mnoży się z catch-up (np. weekend 2.0 × catch-up 1.2 = 2.4).
     * Aktywny sobota–niedziela w strefie sezonu, wyłącznie przy mnożniku > 1.0.
     */
    @Override
    public boolean isWeekendBonusActive() {
        if (weekendMultiplier <= 1.0D) {
            return false;
        }
        java.time.DayOfWeek day = today.get();
        return day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY;
    }

    @Override
    public double weekendMultiplier() {
        return weekendMultiplier;
    }
}
