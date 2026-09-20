package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2.5b: dokończenie questów sezonowych — (1) quest CRAFT osiągalny
 * end-to-end na prawdziwym SQL (CraftItemEvent → award, idempotentny
 * operationId), (2) persystencja postępu: save przy każdym przyroście,
 * load po "restarcie" (nowa instancja serwisu nad tym samym SQL) przywraca
 * liczniki, a dedup nagród przez reward_claims nie dubluje punktów.
 */
class SeasonQuestPersistenceTest {

    private static final int SEASON = 3;

    private SingleConnectionSqlService sql;
    private DefaultSeasonPointService points;
    private SqlSeasonQuestProgressDao progressDao;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
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

    /** Odpowiednik sq_craft_1 z config.yml (CRAFT, cel 3 dla szybkości testu). */
    private static SeasonQuestService.Definition craftBread(int goal) {
        return new SeasonQuestService.Definition("sq_craft_1",
                SeasonQuestService.Definition.Type.CRAFT, Set.of("BREAD"), goal, 50L,
                false, Material.CRAFTING_TABLE, "Rzemieślnik I", List.of());
    }

    /**
     * Lekka atrapa jak w {@code SeasonQuestProgressTest}: hierarchia Player jest
     * tak szeroka, że inline-mock-maker nie radzi sobie z nią bez agenta.
     */
    private static <T> T proxy(Class<T> type, Map<String, Object> returns) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, (unused, method, args) -> {
                    Object value = returns.get(method.getName());
                    if (value != null || returns.containsKey(method.getName())) return value;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType().isPrimitive()) return 0;
                    return null;
                }));
    }

    private static CraftItemEvent craftEvent(UUID playerUuid, Material resultMaterial) {
        Player player = proxy(Player.class, Map.of("getUniqueId", playerUuid));
        InventoryView view = proxy(InventoryView.class,
                Map.of("getPlayer", player));
        // Realny ItemStack wymaga rejestru Bukkita (jak w OneBlockServiceTest);
        // mock przez Mockito to ustawiony wzorzec repo (ChunkerServiceTest).
        ItemStack result = org.mockito.Mockito.mock(ItemStack.class);
        org.mockito.Mockito.when(result.getType()).thenReturn(resultMaterial);
        org.mockito.Mockito.when(result.clone()).thenReturn(result); // CraftingRecipe.getResult robi clone()
        CraftingRecipe recipe = new CraftingRecipe(
                new NamespacedKey("wpmecore", "test-" + resultMaterial.name().toLowerCase()),
                result) { };
        return new CraftItemEvent(recipe, view, InventoryType.SlotType.RESULT,
                0, ClickType.LEFT, InventoryAction.PICKUP_ONE);
    }

    private SeasonQuestService quests(int goal) {
        return new SeasonQuestService(points, List.of(craftBread(goal)), progressDao,
                java.util.logging.Logger.getLogger("SeasonQuestPersistenceTest"));
    }

    @Test
    void craftQuestIsReachableEndToEndAndRewardsExactlyOnceAtGoal() {
        UUID player = UUID.randomUUID();
        SeasonQuestService service = quests(3);

        // D3: handler czyta progi z cache punktów (cold miss = 0 slotów) —
        // podgrzej cache jak w produkcji robi to PlayerJoinEvent.
        service.refreshPointsCache(player);
        service.onCraft(craftEvent(player, Material.BREAD));
        assertEquals(0L, points.pointsOf(player).join(), "poniżej celu — bez punktów");

        service.onCraft(craftEvent(player, Material.BREAD));
        assertEquals(0L, points.pointsOf(player).join(), "wciąż poniżej celu");

        service.onCraft(craftEvent(player, Material.BREAD));
        assertEquals(50L, points.pointsOf(player).join(), "cel osiągnięty — jednorazowa nagroda");

        // ponad cel: licznik rośnie, award idempotentny przez operationId
        service.onCraft(craftEvent(player, Material.BREAD));
        assertEquals(50L, points.pointsOf(player).join(),
                "postęp ponad cel nie nagradza ponownie");
    }

    @Test
    void nonMatchingCraftResultDoesNotCount() {
        UUID player = UUID.randomUUID();
        SeasonQuestService service = quests(2);

        service.onCraft(craftEvent(player, Material.TORCH));

        assertEquals(0, service.progressOf(player, "sq_craft_1"),
                "wynik poza targets questa się nie liczy");
    }

    @Test
    void progressSurvivesRestartThroughNewServiceInstanceOverSameSql() {
        UUID player = UUID.randomUUID();
        SeasonQuestService before = quests(3);

        // D3: ciepły cache punktów (jak po PlayerJoinEvent) — cold miss zwraca
        // 0 slotów i celowo odrzuca zdarzenie.
        before.refreshPointsCache(player);
        before.onCraft(craftEvent(player, Material.BREAD));
        before.onCraft(craftEvent(player, Material.BREAD));
        assertEquals(2, before.progressOf(player, "sq_craft_1"));

        // "restart": świeża instancja serwisu nad tym samym SQL + load per sezon
        // (loadProgress podgrzewa też cache punktów graczy z postępem).
        SeasonQuestService after = quests(3);
        after.loadProgress(SEASON).join();

        assertEquals(2, after.progressOf(player, "sq_craft_1"),
                "postęp wraca po restarcie");

        // dokończenie questa po restarcie: jedna nagroda, mimo że award mógł
        // teoretycznie już wisieć — dedup przez reward_claims (trwały)
        after.onCraft(craftEvent(player, Material.BREAD));
        assertEquals(50L, points.pointsOf(player).join(),
                "nagroda dokładnie raz także w ścieżce restart-recovery");
        assertEquals(3, after.progressOf(player, "sq_craft_1"));
    }

    @Test
    void loadReplacesStaleInMemoryState() {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();

        SeasonQuestService first = quests(5);
        // D3: ciepły cache punktów obu graczy (cold miss = 0 slotów odrzuca zdarzenie).
        first.refreshPointsCache(playerA);
        first.refreshPointsCache(playerB);
        first.onCraft(craftEvent(playerA, Material.BREAD));
        first.onCraft(craftEvent(playerB, Material.BREAD));
        first.onCraft(craftEvent(playerB, Material.BREAD));

        SeasonQuestService second = quests(5);
        second.loadProgress(SEASON).join();

        assertEquals(1, second.progressOf(playerA, "sq_craft_1"));
        assertEquals(2, second.progressOf(playerB, "sq_craft_1"),
                "load per sezon wczytuje pełny zapis, nie tylko jednego gracza");
    }
}
