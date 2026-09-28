package org.rafalohaki.wpmecore.addons.skyblock.reward;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R50-LOTUS: granty codziennej nagrody nie mogą ścigać się o lease outboxu —
 * lotos startuje dopiero po rozliczeniu wielkiej serii, a BUSY ponowi grant
 * z tym samym deterministycznym operationId (28.09: „Daily reward lotus grant
 * BUSY" przy serii 28 = wielka seria i wielokrotność 7 naraz, item przepadł).
 */
class DailyRewardListenerTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    private Plugin plugin;
    private Player player;
    private DailyRewardService service;
    private InventoryOutbox outbox;
    private DailyRewardListener listener;
    private UUID playerId;

    private final List<Consumer<ScheduledTask>> immediate = new ArrayList<>();
    private final List<Consumer<ScheduledTask>> delayed = new ArrayList<>();
    private final List<String> grants = new ArrayList<>();
    private final List<String> messages = new ArrayList<>();
    private final java.util.Map<String, Consumer<InventoryOutbox.Outcome>> callbacks =
            new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("DailyRewardListenerTest"));

        playerId = UUID.randomUUID();
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        doAnswer(inv -> {
            messages.add(inv.getArgument(0, Component.class).toString());
            return null;
        }).when(player).sendMessage(any(Component.class));
        EntityScheduler scheduler = mock(EntityScheduler.class);
        doAnswer(inv -> {
            immediate.add(inv.getArgument(1));
            return null;
        }).when(scheduler).run(any(), any(), any());
        doAnswer(inv -> {
            delayed.add(inv.getArgument(1));
            return null;
        }).when(scheduler).runDelayed(any(), any(), any(), anyLong());
        when(player.getScheduler()).thenReturn(scheduler);

        service = mock(DailyRewardService.class);
        when(service.claim(any(UUID.class), any(LocalDate.class), anyInt()))
                .thenAnswer(inv -> CompletableFuture.completedFuture(Optional.of(
                        new DailyRewardService.Claim(28, 300L, true, true, 10_000L))));
        when(service.daysToNextGreat(anyInt())).thenReturn(28);

        outbox = mock(InventoryOutbox.class);
        doAnswer(inv -> {
            String operationId = inv.getArgument(2);
            grants.add(operationId);
            callbacks.put(operationId, inv.getArgument(5));
            return null;
        }).when(outbox).beginGrant(any(), anyLong(), anyString(), anyString(), any(), any());

        CustomItemService customItems = mock(CustomItemService.class);
        when(customItems.create(anyString()))
                .thenAnswer(inv -> Optional.of(mock(ItemStack.class)));

        listener = new DailyRewardListener(plugin, service, outbox, customItems,
                MiniMessage.miniMessage(), "skyblock:token/silver_lotus", "skyblock:token/gold_lotus");
    }

    private String opId(String suffix) {
        return "daily:" + playerId + ":" + TODAY + ":" + suffix;
    }

    /** Odpala zakolejkowane zadania aż do wygaśnięcia (hop może zakolejkować następne). */
    private void drain(List<Consumer<ScheduledTask>> queue) {
        while (!queue.isEmpty()) {
            List<Consumer<ScheduledTask>> batch = new ArrayList<>(queue);
            queue.clear();
            batch.forEach(task -> task.accept(null));
        }
    }

    private int countGrants(String operationId) {
        return (int) grants.stream().filter(operationId::equals).count();
    }

    @Test
    void lotusWaitsUntilGreatSeriesSettles() {
        listener.command(player);
        drain(immediate);

        assertEquals(List.of(opId("great-series")), grants,
                "wielka seria startuje pierwsza");
        assertFalse(grants.contains(opId("lotus")),
                "lotos nie może wyścigać się z grantem wielkiej serii (R50-LOTUS)");

        callbacks.get(opId("great-series")).accept(InventoryOutbox.Outcome.SUCCESS);
        drain(immediate);

        assertEquals(List.of(opId("great-series"), opId("lotus")), grants,
                "lotos startuje dopiero po rozliczeniu wielkiej serii");

        callbacks.get(opId("lotus")).accept(InventoryOutbox.Outcome.SUCCESS);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Srebrny Lotos")),
                "komunikat o Srebrnym Lotosie musi dojść (objaw buga: brak komunikatu)");
    }

    @Test
    void busyLotusRetriesWithSameDeterministicOperationId() {
        listener.command(player);
        drain(immediate);
        callbacks.get(opId("great-series")).accept(InventoryOutbox.Outcome.SUCCESS);
        drain(immediate);

        assertEquals(1, countGrants(opId("lotus")));
        callbacks.get(opId("lotus")).accept(InventoryOutbox.Outcome.BUSY);

        drain(delayed);
        assertEquals(2, countGrants(opId("lotus")),
                "ponowienie używa tego samego operationId (idempotencja outboxu)");

        callbacks.get(opId("lotus")).accept(InventoryOutbox.Outcome.DEFERRED);
        assertTrue(delayed.isEmpty(), "po doręczeniu nie ma dalszych ponowień");
        assertTrue(messages.stream().anyMatch(m -> m.contains("Srebrny Lotos")));
    }

    @Test
    void lotusStillAttemptedWhenGreatSeriesFailsTerminally() {
        listener.command(player);
        drain(immediate);
        callbacks.get(opId("great-series")).accept(InventoryOutbox.Outcome.REJECTED);
        drain(immediate);

        assertTrue(grants.contains(opId("lotus")),
                "porażka wielkiej serii nie może blokować lotosa (osobny operationId)");
    }

    @Test
    void exhaustedBusyRetriesSettleTheChain() {
        listener.command(player);
        drain(immediate);
        callbacks.get(opId("great-series")).accept(InventoryOutbox.Outcome.BUSY);
        for (int attempt = 1; attempt <= 5; attempt++) {
            drain(delayed);
            assertEquals(1 + attempt, countGrants(opId("great-series")),
                    "próba " + attempt + " ponowienia wielkiej serii");
            callbacks.get(opId("great-series")).accept(InventoryOutbox.Outcome.BUSY);
        }
        drain(immediate);
        assertTrue(grants.contains(opId("lotus")),
                "po wyczerpaniu ponowień łańcuch się rozlicza — lotos i tak startuje");
        assertEquals(6, countGrants(opId("great-series")));
    }

    @Test
    void plainDayGrantsLotusWithoutGreatSeries() {
        when(service.claim(any(UUID.class), any(LocalDate.class), anyInt()))
                .thenAnswer(inv -> CompletableFuture.completedFuture(Optional.of(
                        new DailyRewardService.Claim(7, 250L, true, false, 0L))));

        listener.command(player);
        drain(immediate);

        assertEquals(List.of(opId("lotus")), grants);
        callbacks.get(opId("lotus")).accept(InventoryOutbox.Outcome.SUCCESS);
        assertTrue(messages.stream().anyMatch(m -> m.contains("Srebrny Lotos")));
    }
}
