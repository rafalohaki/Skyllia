package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D3 (runda 3 odporności): cache punktów sezonowych w {@link SeasonQuestService}.
 * Handler zdarzeń działa na wątkach regionu Folia, więc {@code unlockedFor}
 * nie wolno blokująco czytać SQL (dawne {@code pointsOf(...).join()}).
 * Semantyka: cold miss → 0 otwartych slotów + zaplanowane asynchroniczne
 * odświeżenie; refresh podmienia wartość w cache; award za ukończenie questa
 * sam odświeża cache (nowy próg widoczny od razu). Na atrapie
 * {@link SingleConnectionSqlService} future kończą się synchronicznie na
 * wątku wywołującym, więc „zaplanowane” odświeżenie wypełnia cache natychmiast.
 */
class SeasonQuestPointsCacheTest {

    private static final int SEASON = 3;

    private SingleConnectionSqlService sql;
    private DefaultSeasonPointService points;
    private SqlSeasonQuestProgressDao progressDao;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        // koniec sezonu daleko w przyszłości → catch-up nieaktywny, punkty 1:1
        points = new DefaultSeasonPointService(
                new SqlSeasonPointDao(sql, () -> SEASON),
                () -> SEASON,
                () -> System.currentTimeMillis() + Duration.ofDays(400).toMillis(),
                ZoneId.of("UTC"));
        progressDao = new SqlSeasonQuestProgressDao(sql);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    private static SeasonQuestService.Definition breakStone(int goal, long pts) {
        return new SeasonQuestService.Definition("sq_stone", SeasonQuestService.Definition.Type.BREAK,
                Set.of("STONE"), goal, pts, false, Material.STONE_PICKAXE, "Kopacz", List.of());
    }

    private static BlockBreakEvent breakStoneEvent(UUID playerUuid) {
        Player player = proxy(Player.class, java.util.Map.of("getUniqueId", playerUuid));
        Block block = proxy(Block.class, java.util.Map.of("getType", Material.STONE));
        return new BlockBreakEvent(block, player);
    }

    /** Lekka atrapa jak w {@code SeasonQuestProgressTest} (szeroka hierarchia Player). */
    private static <T> T proxy(Class<T> type, java.util.Map<String, Object> returns) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, (unused, method, args) -> {
                    Object value = returns.get(method.getName());
                    if (value != null || returns.containsKey(method.getName())) return value;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType().isPrimitive()) return 0;
                    return null;
                }));
    }

    private SeasonQuestService quests() {
        return new SeasonQuestService(points, List.of(breakStone(2, 50L)), progressDao,
                java.util.logging.Logger.getLogger("SeasonQuestPointsCacheTest"));
    }

    @Test
    void coldMissReturnsZeroSlotsButSchedulesRefreshThatFillsCache() {
        UUID player = UUID.randomUUID();
        SeasonQuestService service = quests();

        assertEquals(0, service.unlockedFor(player),
                "cold miss: zero slotów zamiast blokującego joina");

        // Miss zaplanował odświeżenie — na synchronicznym SQL cache jest już
        // pełny, więc kolejne odczyty liczą progi normalnie (próg 0 → 8 slotów).
        assertEquals(8, service.unlockedFor(player),
                "po odświeżeniu cache progi wracają do normy");
    }

    @Test
    void refreshPointsCacheFillsCacheWithLatestSqlValue() {
        UUID player = UUID.randomUUID();
        SeasonQuestService service = quests();

        assertTrue(points.award(player, 300L, "cache-test:" + player).join());
        // Cache jest jeszcze zimny — award poza serwisem go nie podgrzał.

        service.refreshPointsCache(player);
        assertEquals(16, service.unlockedFor(player),
                "refresh wczytuje aktualne punkty z SQL (300 pkt → 16 slotów)");

        assertTrue(points.award(player, 500L, "cache-test-2:" + player).join());
        service.refreshPointsCache(player);
        assertEquals(24, service.unlockedFor(player), "800 pkt → 24 sloty");
    }

    @Test
    void awardThroughQuestCompletionRefreshesCacheItself() {
        UUID player = UUID.randomUUID();
        // Quest daje dokładnie 300 pkt — przekracza pierwszy próg (8 → 16 slotów).
        SeasonQuestService service = new SeasonQuestService(points,
                List.of(breakStone(2, 300L)), progressDao,
                java.util.logging.Logger.getLogger("SeasonQuestPointsCacheTest"));

        service.refreshPointsCache(player); // ciepły cache: 0 pkt
        assertEquals(8, service.unlockedFor(player));

        service.onBreak(breakStoneEvent(player));
        service.onBreak(breakStoneEvent(player)); // cel osiągnięty → award + auto-refresh

        assertEquals(16, service.unlockedFor(player),
                "award za questa sam odświeża cache (nowy próg bez ręcznego refreshu)");
    }

    @Test
    void loadProgressWarmsCacheForPlayersWithProgressRows() {
        UUID player = UUID.randomUUID();
        assertTrue(points.award(player, 800L, "load-warm:" + player).join());
        progressDao.save(SEASON, player, "sq_stone", 2).join();

        SeasonQuestService fresh = quests(); // bez żadnego zdarzenia
        fresh.loadProgress(SEASON).join();

        assertEquals(24, fresh.unlockedFor(player),
                "loadProgress podgrzewa cache punktów graczy z postępem (800 pkt → 24 sloty)");
    }
}
