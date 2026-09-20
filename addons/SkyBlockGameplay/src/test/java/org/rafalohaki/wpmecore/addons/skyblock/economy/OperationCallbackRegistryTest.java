package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2-25. Rejestr był kluczowany <b>samym</b> {@code operationId}, a dwa
 * identyfikatory są celowo wspólne dla całej wyspy
 * ({@code SkyBlockTopRewardCoordinator} — sezonowy Lotos,
 * {@code DailyQuestService} — milestone tygodniowy). Druga rejestracja
 * nadpisywała wpis pierwszego gracza, więc {@code findDanglingFor(B)} trafiał
 * w klucz A i B odbierał nagrodę A.
 *
 * <p>Test jest czysty (bez MockBukkita) celowo: naprawy pinowane wyłącznie
 * w profilu {@code mockbukkit-compat} potrafiły być martwym kodem przy w pełni
 * zielonym {@code mvn test}.
 */
class OperationCallbackRegistryTest {

    private static final Logger LOGGER =
            Logger.getLogger(OperationCallbackRegistryTest.class.getName());

    @Test
    void twoMembersSharingOneIslandWideOperationIdKeepTheirOwnCallbacks() {
        OperationCallbackRegistry registry = new OperationCallbackRegistry(LOGGER);
        UUID memberA = UUID.randomUUID();
        UUID memberB = UUID.randomUUID();
        String islandWide = "season_reward:lotus:3:" + UUID.randomUUID();
        List<InventoryOutbox.Outcome> deliveredToA = new ArrayList<>();
        List<InventoryOutbox.Outcome> deliveredToB = new ArrayList<>();

        registry.put(islandWide, memberA, deliveredToA::add);
        registry.put(islandWide, memberB, deliveredToB::add);

        assertEquals(2, registry.count(), "wpis A nie ma prawa zostać nadpisany wpisem B");
        assertEquals(islandWide, registry.findDanglingFor(memberA).orElseThrow());
        assertEquals(islandWide, registry.findDanglingFor(memberB).orElseThrow());

        assertTrue(registry.complete(islandWide, memberB, InventoryOutbox.Outcome.SUCCESS));

        assertEquals(List.of(InventoryOutbox.Outcome.SUCCESS), deliveredToB);
        assertTrue(deliveredToA.isEmpty(), "domknięcie B nie domyka operacji A");
        assertTrue(registry.hasPendingFor(memberA));
        assertFalse(registry.hasPendingFor(memberB));

        assertTrue(registry.complete(islandWide, memberA, InventoryOutbox.Outcome.DEFERRED));
        assertEquals(List.of(InventoryOutbox.Outcome.DEFERRED), deliveredToA);
        assertEquals(0, registry.count());
    }

    @Test
    void completingWithTheWrongOwnerDoesNothing() {
        OperationCallbackRegistry registry = new OperationCallbackRegistry(LOGGER);
        UUID owner = UUID.randomUUID();
        List<InventoryOutbox.Outcome> delivered = new ArrayList<>();
        registry.put("milestone:island:2026-W35", owner, delivered::add);

        assertFalse(registry.complete("milestone:island:2026-W35", UUID.randomUUID(),
                InventoryOutbox.Outcome.SUCCESS));

        assertTrue(delivered.isEmpty());
        assertEquals(1, registry.count());
    }

    @Test
    void removeForPlayerLeavesTheOtherOwnerAlone() {
        OperationCallbackRegistry registry = new OperationCallbackRegistry(LOGGER);
        UUID memberA = UUID.randomUUID();
        UUID memberB = UUID.randomUUID();
        String islandWide = "milestone:island:2026-W35";
        registry.put(islandWide, memberA, outcome -> { });
        registry.put(islandWide, memberB, outcome -> { });

        registry.removeForPlayer(memberA);

        assertFalse(registry.hasPendingFor(memberA));
        assertTrue(registry.hasPendingFor(memberB));
        assertEquals(1, registry.count());
    }
}
