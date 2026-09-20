package org.rafalohaki.wpmecore.addons.skyblock.season;

import com.oheers.fish.api.events.EMFFishCaughtEvent;
import com.oheers.fish.api.fishing.items.IFish;
import com.oheers.fish.api.fishing.items.IRarity;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2.5: SeasonFishListener czyta rzadkość → punkty z configu (SeasonFishPoints),
 * nie ze stałej w kodzie. Event EMF budowany na prawdziwej klasie
 * {@link EMFFishCaughtEvent} z atrapami {@link java.lang.reflect.Proxy}
 * (wzorzec SeasonQuestProgressTest — hierarchia Player jest za szeroka dla
 * inline-mock-makera bez agenta).
 */
class SeasonFishListenerTest {

    /** Rejestruje wywołania dziennego kanału — bez SQL, wystarczy kontrakt interfejsu. */
    private static final class RecordingDailyPoints implements SeasonDailyPointsService {
        record Award(UUID player, String channel, long points, String operationId) { }
        final List<Award> awards = new ArrayList<>();

        @Override
        public @NotNull CompletableFuture<Long> awardDaily(@NotNull UUID playerUuid,
                                                           @NotNull String channel, long points,
                                                           @NotNull String operationId) {
            awards.add(new Award(playerUuid, channel, points, operationId));
            return CompletableFuture.completedFuture(points);
        }

        @Override
        public @NotNull CompletableFuture<Long> pointsToday(@NotNull UUID playerUuid,
                                                            @NotNull String channel) {
            return CompletableFuture.completedFuture(
                    awards.stream().mapToLong(Award::points).sum());
        }
    }

    private RecordingDailyPoints points;
    private SeasonFishListener listener;

    @BeforeEach
    void setUp() {
        points = new RecordingDailyPoints();
        listener = new SeasonFishListener(points, fishPointsFromShippedValues());
    }

    private static SeasonFishPoints fishPointsFromShippedValues() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("common", 5);
        config.set("rare", 15);
        config.set("epic", 40);
        config.set("legendary", 120);
        return SeasonFishPoints.load(config);
    }

    /**
     * Lekka atrapa zamiast Mockito (wzorzec SeasonQuestProgressTest):
     * handler dotyka tylko kilku metod, Proxy wystarcza.
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

    private EMFFishCaughtEvent caughtEvent(String rarityId, UUID playerUuid) {
        IRarity rarity = proxy(IRarity.class, Map.of("getId", rarityId));
        IFish fish = proxy(IFish.class, Map.of("getRarity", rarity));
        Player player = proxy(Player.class, Map.of("getUniqueId", playerUuid));
        return new EMFFishCaughtEvent(fish, player, LocalDateTime.now());
    }

    @Test
    void awardsConfigValuesPerRarity() {
        UUID player = UUID.randomUUID();

        listener.onEmfFishCaught(caughtEvent("common", player));
        listener.onEmfFishCaught(caughtEvent("rare", player));
        listener.onEmfFishCaught(caughtEvent("EPIC", player));
        listener.onEmfFishCaught(caughtEvent("legendary", player));

        assertEquals(List.of(5L, 15L, 40L, 120L),
                points.awards.stream().map(RecordingDailyPoints.Award::points).toList(),
                "punkty pochodzą z tabeli configu, nie ze stałej w kodzie");
        assertEquals(player, points.awards.get(0).player());
        assertEquals(List.of("fish", "fish", "fish", "fish"),
                points.awards.stream().map(RecordingDailyPoints.Award::channel).toList(),
                "ryby idą kanałem dobowego capu 'fish'");
    }

    @Test
    void unknownRarityAwardsNothing() {
        listener.onEmfFishCaught(caughtEvent("mythic", UUID.randomUUID()));
        assertTrue(points.awards.isEmpty());
    }

    @Test
    void missingFishRarityOrPlayerIsIgnored() {
        UUID player = UUID.randomUUID();

        IFish noRarity = proxy(IFish.class,
                java.util.Collections.singletonMap("getRarity", null));
        listener.onEmfFishCaught(new EMFFishCaughtEvent(noRarity,
                proxy(Player.class, Map.of("getUniqueId", player)), LocalDateTime.now()));
        assertTrue(points.awards.isEmpty(), "ryba bez rzadkości = brak punktów");

        IFish fish = proxy(IFish.class, Map.of("getRarity",
                proxy(IRarity.class, Map.of("getId", "common"))));
        listener.onEmfFishCaught(new EMFFishCaughtEvent(fish, null, LocalDateTime.now()));
        assertTrue(points.awards.isEmpty(), "event bez gracza = brak punktów");
    }

    @Test
    void operationIdStaysEmfFishPlayerTimestamp() {
        UUID player = UUID.randomUUID();
        long before = System.currentTimeMillis();

        listener.onEmfFishCaught(caughtEvent("rare", player));

        String operationId = points.awards.get(0).operationId();
        assertTrue(operationId.matches("emf-fish:" + player + ":\\d+"),
                "format operationId bez zmian: " + operationId);
        long stamp = Long.parseLong(operationId.substring(operationId.lastIndexOf(':') + 1));
        assertFalse(stamp < before, "timestamp z chwili zdarzenia");
    }

    @Test
    void customConfigValuesFlowThroughToAward() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("common", 7);
        config.set("rare", 21);
        config.set("epic", 55);
        config.set("legendary", 200);
        SeasonFishListener tuned = new SeasonFishListener(points, SeasonFishPoints.load(config));

        tuned.onEmfFishCaught(caughtEvent("epic", UUID.randomUUID()));

        assertEquals(55L, points.awards.get(0).points(),
                "zmiana wartości w configu zmienia przyznawane punkty");
    }
}
