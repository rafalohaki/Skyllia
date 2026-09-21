package pl.b2t.skylliaminions;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * Bonusowe sloty minionków wyspy z dwóch źródeł współdzielonej bazy, które
 * zapisuje SkyBlockGameplay — addon nie potrzebuje żadnego API między
 * pluginami:
 * <ul>
 *   <li>{@code wpme_sb_island_prestige} — +1 slot co {@code slotsEveryLevels}
 *       poziomów prestiżu,</li>
 *   <li>{@code wpme_sb_island_upgrades} (tor {@code minions}) — +N slotów za
 *       poziom ulepszenia, gdzie N = {@code upgradeSlotsPerLevel}.</li>
 * </ul>
 * Resolver jest synchroniczny (ścieżka stawiania minionka) i nigdy nie robi
 * I/O w wątku: trzyma cache z TTL, a odświeżenie leci asynchronicznie przez
 * SqlService. Brak tabeli ulepszeń (starszy addon) nie wywraca resolvera —
 * jedno zapytanie pada, cache zostaje na starym wpisie.
 */
final class PrestigeSlots {

    private static final long TTL_MILLIS = 60_000L;

    private final SqlService sql;
    private final int slotsEveryLevels;
    private final int upgradeSlotsPerLevel;
    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(int prestigeLevel, int upgradeLevel, long fetchedAt) {
    }

    PrestigeSlots(@NotNull SqlService sql, int slotsEveryLevels, int upgradeSlotsPerLevel) {
        this.sql = sql;
        this.slotsEveryLevels = Math.max(0, slotsEveryLevels);
        this.upgradeSlotsPerLevel = Math.max(0, upgradeSlotsPerLevel);
    }

    /** Resolver dla {@link MinionListener#setIslandExtraSlots}: cache albo 0. */
    @NotNull ToIntFunction<UUID> resolver() {
        return islandId -> {
            if (islandId == null) {
                return 0;
            }
            Entry entry = cache.get(islandId);
            long now = System.currentTimeMillis();
            if (entry == null || now - entry.fetchedAt() > TTL_MILLIS) {
                refresh(islandId);
            }
            if (entry == null) {
                return 0;
            }
            int slots = 0;
            if (slotsEveryLevels > 0) {
                slots += entry.prestigeLevel() / slotsEveryLevels;
            }
            slots += entry.upgradeLevel() * upgradeSlotsPerLevel;
            return slots;
        };
    }

    /**
     * Dwa niezależne odświeżenia: gdy jedno źródło pada (np. stary addon bez
     * tabeli ulepszeń przy mieszanym wdrożeniu), drugie nadal trafia do cache
     * — sloty prestiżu nie znikają przez brak wpme_sb_island_upgrades.
     */
    private void refresh(@NotNull UUID islandId) {
        long fetchedAt = System.currentTimeMillis();
        String island = islandId.toString();
        sql.queryOne("SELECT level FROM wpme_sb_island_prestige WHERE island_id = ?",
                        rs -> rs.getInt(1), island)
                .thenAccept(found -> cache.merge(islandId,
                        new Entry(found.orElse(0), 0, fetchedAt),
                        (old, next) -> new Entry(next.prestigeLevel(),
                                old.upgradeLevel(), fetchedAt)))
                .exceptionally(failure -> null);
        sql.queryOne("SELECT level FROM wpme_sb_island_upgrades WHERE island_id = ? AND track = 'minions'",
                        rs -> rs.getInt(1), island)
                .thenAccept(found -> cache.merge(islandId,
                        new Entry(0, found.orElse(0), fetchedAt),
                        (old, next) -> new Entry(old.prestigeLevel(),
                                next.upgradeLevel(), fetchedAt)))
                .exceptionally(failure -> null);
    }
}
