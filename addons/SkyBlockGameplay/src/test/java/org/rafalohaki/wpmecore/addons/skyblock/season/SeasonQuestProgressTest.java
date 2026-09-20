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
 * M2.5: questy sezonowe end-to-end na prawdziwym SQL (SQLite :memory:) —
 * przyznanie punktów za ukończenie questa, idempotencja per gracz per sezon
 * (operationId), brak podwójnej nagrody po przekroczeniu celu oraz integracja
 * z progami odblokowań {@link SeasonTierEngine} (spec §2.1).
 */
class SeasonQuestProgressTest {

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

    /**
     * Lekka atrapa zamiast Mockito: hierarchia {@code Player} jest tak szeroka,
     * że inline-mock-maker nie radzi sobie z nią na JVM bez agenta; handler
     * dotyka tylko kilku metod, więc wystarczy {@link java.lang.reflect.Proxy}.
     */
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

    @Test
    void awardsQuestPointsExactlyOnceAtGoal() {
        UUID player = UUID.randomUUID();
        SeasonQuestService quests = new SeasonQuestService(points, List.of(breakStone(2, 50L)),
                progressDao, java.util.logging.Logger.getLogger("SeasonQuestProgressTest"));

        // D3: handler czyta progi z cache punktów (cold miss = 0 slotów) —
        // podgrzej cache jak w produkcji robi to PlayerJoinEvent.
        quests.refreshPointsCache(player);
        quests.onBreak(breakStoneEvent(player));
        assertEquals(0L, points.pointsOf(player).join(), "poniżej celu — bez punktów");

        quests.onBreak(breakStoneEvent(player));
        assertEquals(50L, points.pointsOf(player).join(), "cel osiągnięty — jednorazowa nagroda");

        quests.onBreak(breakStoneEvent(player));
        assertEquals(50L, points.pointsOf(player).join(),
                "postęp ponad cel nie nagradza ponownie");
    }

    /**
     * F24: rozbicie centrum OneBlocka nie dociera do waniliowego
     * {@code BlockBreakEvent} — {@code OneBlockService} anuluje własne zdarzenie,
     * żeby platforma nie zniknęła spod gracza, a handler questów sezonowych stoi
     * na {@code ignoreCancelled = true}. Efekt przed poprawką: rozdział
     * „Podziemia i Jaskinia” (STONE/COBBLESTONE/COAL_ORE/IRON_ORE, 500 bloków)
     * dawał zero postępu w {@code sq_mine_1}, mimo że jest to CAŁA rozgrywka tego
     * trybu. Ścieżka obserwatora musi liczyć dokładnie tak samo jak waniliowa.
     */
    @Test
    void oneblockCentreBreakCountsThroughTheObserverPath() {
        UUID player = UUID.randomUUID();
        SeasonQuestService quests = new SeasonQuestService(points, List.of(breakStone(2, 50L)),
                progressDao, java.util.logging.Logger.getLogger("SeasonQuestProgressTest"));
        quests.refreshPointsCache(player);

        quests.recordBreak(player, "STONE");
        assertEquals(1, quests.progressOf(player, "sq_stone"),
                "rozbicie z obserwatora OneBlocka musi ruszyć licznik");
        assertEquals(0L, points.pointsOf(player).join(), "poniżej celu — bez punktów");

        quests.recordBreak(player, "STONE");
        assertEquals(50L, points.pointsOf(player).join(),
                "cel osiągnięty tą samą drogą co waniliowa");

        quests.recordBreak(player, "DIRT");
        assertEquals(2, quests.progressOf(player, "sq_stone"),
                "materiał spoza celów questa nie liczy się nawet z obserwatora");
    }

    @Test
    void matureOnlyQuestIgnoresImmatureCrops() {
        UUID player = UUID.randomUUID();
        SeasonQuestService.Definition wheat = new SeasonQuestService.Definition("sq_wheat",
                SeasonQuestService.Definition.Type.BREAK, Set.of("WHEAT"), 2, 50L, true,
                Material.WHEAT, "Zbiory", List.of());
        SeasonQuestService quests = new SeasonQuestService(points, List.of(wheat),
                progressDao, java.util.logging.Logger.getLogger("SeasonQuestProgressTest"));
        quests.refreshPointsCache(player);

        quests.recordBreak(player, "WHEAT", false);
        assertEquals(0, quests.progressOf(player, "sq_wheat"),
                "niedojrzała uprawa nie liczy się przy mature-only");

        quests.recordBreak(player, "WHEAT", true);
        quests.recordBreak(player, "WHEAT");
        assertEquals(2, quests.progressOf(player, "sq_wheat"),
                "dojrzała uprawa i ścieżka OneBlocka (domyślnie dojrzała) liczą się");
    }

    @Test
    void duplicateOperationIdIsRejectedByLedger() {
        UUID player = UUID.randomUUID();

        assertTrue(points.award(player, 50L, "season-quest:" + player + ":" + SEASON
                + ":sq_stone").join());
        assertFalse(points.award(player, 50L, "season-quest:" + player + ":" + SEASON
                        + ":sq_stone").join(),
                "to samo operationId = dedup w wpme_sb_reward_claims");
        assertEquals(50L, points.pointsOf(player).join());
    }

    @Test
    void differentPlayersCompleteTheSameQuestIndependently() {
        // Regresja operationId: przed poprawką ("season-quest:<id>" bez gracza)
        // unikalny indeks operation_id blokował nagrodę wszystkim po pierwszym graczu.
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        SeasonQuestService quests = new SeasonQuestService(points, List.of(breakStone(1, 75L)),
                progressDao, java.util.logging.Logger.getLogger("SeasonQuestProgressTest"));

        // D3: ciepły cache punktów obu graczy (jak po PlayerJoinEvent).
        quests.refreshPointsCache(first);
        quests.refreshPointsCache(second);
        quests.onBreak(breakStoneEvent(first));
        quests.onBreak(breakStoneEvent(second));

        assertEquals(75L, points.pointsOf(first).join());
        assertEquals(75L, points.pointsOf(second).join(),
                "drugi gracz dostaje punkty za ten sam quest");
    }

    @Test
    void progressIsPerSeasonViaOperationId() {
        // Nowy sezon = nowe operationId → ten sam quest można zrobić ponownie.
        UUID player = UUID.randomUUID();
        String op = "season-quest:" + player + ":";

        assertTrue(points.award(player, 50L, op + SEASON + ":sq_stone").join());
        assertTrue(points.award(player, 50L, op + (SEASON + 1) + ":sq_stone").join(),
                "inny sezon = inny claim");
        assertFalse(points.award(player, 50L, op + SEASON + ":sq_stone").join());
        assertEquals(100L, points.pointsOf(player).join());
    }

    @Test
    void questsBeyondUnlockThresholdDoNotCount() {
        // Katalog 9 definicji; przy 0 pkt odblokowane 8 slotów (próg 0) →
        // dziewiąty quest (indeks 8) ignorowany do zdobycia progu 300.
        var locked = new SeasonQuestService.Definition("sq_locked",
                SeasonQuestService.Definition.Type.BREAK, Set.of("STONE"), 1, 40L,
                false, Material.STONE, "Zamek", List.of());
        List<SeasonQuestService.Definition> filler = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            filler.add(new SeasonQuestService.Definition("sq_fill_" + i,
                    SeasonQuestService.Definition.Type.PLACE, Set.of("DIRT"), 999_999, 10L,
                    false, Material.DIRT, "F", List.of()));
        }
        SeasonQuestService quests = new SeasonQuestService(points,
                concat(filler, List.of(locked)), progressDao,
                java.util.logging.Logger.getLogger("SeasonQuestProgressTest"));

        UUID player = UUID.randomUUID();
        assertEquals(8, points.unlockedQuestSlots(0L), "próg 0 otwiera 8 slotów");

        // D3: handler czyta progi z cache punktów — podgrzej przed zdarzeniami.
        quests.refreshPointsCache(player);
        quests.onBreak(breakStoneEvent(player));
        assertEquals(0L, points.pointsOf(player).join(),
                "quest za progiem nie liczy się ani nie nagradza");

        // dogonienie progu 300 odblokowuje sloty 9..16
        assertTrue(points.award(player, 300L, "test-threshold:" + player).join());
        assertEquals(16, points.unlockedQuestSlots(points.pointsOf(player).join()));

        // D3: award poszedł bezpośrednio przez serwis punktów (nie przez quest),
        // więc cache trzeba odświeżyć ręcznie — jak zrobi to kolejny join/award.
        quests.refreshPointsCache(player);
        quests.onBreak(breakStoneEvent(player));
        assertEquals(340L, points.pointsOf(player).join(),
                "po odblokowaniu quest nagradza normalnie");
    }

    @Test
    void eventQuestsCountWithoutUnlockSlotsAndStopWhenEditionEnds() {
        // 8 slotów zajętych wypełniaczami; quest eventu nie ma indeksu w katalogu
        // slotowym, więc liczy się od 0 pkt. Pusta lista = koniec okna edycji.
        List<SeasonQuestService.Definition> filler = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            filler.add(new SeasonQuestService.Definition("sq_fill_" + i,
                    SeasonQuestService.Definition.Type.PLACE, Set.of("DIRT"), 999_999, 10L,
                    false, Material.DIRT, "F", List.of()));
        }
        var event = new java.util.concurrent.atomic.AtomicReference<>(
                List.of(new SeasonQuestService.Definition("sq_event",
                        SeasonQuestService.Definition.Type.BREAK, Set.of("STONE"), 2, 150L,
                        false, Material.PUMPKIN, "Event", List.of())));
        SeasonQuestService quests = new SeasonQuestService(points, filler, event::get,
                progressDao, java.util.logging.Logger.getLogger("SeasonQuestProgressTest"));
        UUID player = UUID.randomUUID();
        quests.refreshPointsCache(player);

        quests.onBreak(breakStoneEvent(player));
        assertEquals(1, quests.progressOf(player, "sq_event"), "event liczy się bez slotu");
        event.set(List.of());
        quests.onBreak(breakStoneEvent(player));
        assertEquals(1, quests.progressOf(player, "sq_event"), "po końcu edycji nie liczy");
        assertEquals(0L, points.pointsOf(player).join());

        event.set(List.of(new SeasonQuestService.Definition("sq_event",
                SeasonQuestService.Definition.Type.BREAK, Set.of("STONE"), 2, 150L,
                false, Material.PUMPKIN, "Event", List.of())));
        quests.onBreak(breakStoneEvent(player));
        assertEquals(150L, points.pointsOf(player).join(), "cel eventu nagradza normalnie");
    }

    private static List<SeasonQuestService.Definition> concat(
            List<SeasonQuestService.Definition> a, List<SeasonQuestService.Definition> b) {
        var all = new java.util.ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
