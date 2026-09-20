package pl.b2t.skylliaminions;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * Bonusowe sloty minionków z prestiżu wyspy. Poziom czytamy z tabeli
 * {@code wpme_sb_island_prestige} współdzielonej bazy — ten sam wiersz, który
 * zapisuje SkyBlockGameplay przy zakupie prestiżu, więc addon nie potrzebuje
 * żadnego API między pluginami. Resolver jest synchroniczny (ścieżka
 * stawiania minionka) i nigdy nie robi I/O w wątku: trzyma cache z TTL,
 * a odświeżenie leci asynchronicznie przez SqlService.
 */
final class PrestigeSlots {

    private static final long TTL_MILLIS = 60_000L;

    private final SqlService sql;
    private final int slotsEveryLevels;
    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(int level, long fetchedAt) {
    }

    PrestigeSlots(@NotNull SqlService sql, int slotsEveryLevels) {
        this.sql = sql;
        this.slotsEveryLevels = Math.max(0, slotsEveryLevels);
    }

    /** Resolver dla {@link MinionListener#setIslandExtraSlots}: cache albo 0. */
    @NotNull ToIntFunction<UUID> resolver() {
        return islandId -> {
            if (slotsEveryLevels <= 0 || islandId == null) {
                return 0;
            }
            Entry entry = cache.get(islandId);
            long now = System.currentTimeMillis();
            if (entry == null || now - entry.fetchedAt() > TTL_MILLIS) {
                refresh(islandId);
            }
            int level = entry == null ? 0 : entry.level();
            return level / slotsEveryLevels;
        };
    }

    private void refresh(@NotNull UUID islandId) {
        long fetchedAt = System.currentTimeMillis();
        sql.queryOne("SELECT level FROM wpme_sb_island_prestige WHERE island_id = ?",
                        rs -> rs.getInt(1), islandId.toString())
                .thenAccept(found -> cache.put(islandId,
                        new Entry(found.orElse(0), fetchedAt)))
                .exceptionally(failure -> null);
    }
}
