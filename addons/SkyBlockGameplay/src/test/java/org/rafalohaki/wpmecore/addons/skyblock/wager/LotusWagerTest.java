package org.rafalohaki.wpmecore.addons.skyblock.wager;

import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ECO-13: limity zakładu, blokada doby w PDC i klucz idempotencji wygranej. */
class LotusWagerTest {

    private final LotusWager wager = new LotusWager(pluginWithName("wager-test"));

    /** NamespacedKey(Plugin) czyta Plugin#namespace() — mock musi to mieć zastubowane. */
    private static org.bukkit.plugin.Plugin pluginWithName(String name) {
        org.bukkit.plugin.Plugin plugin = mock(org.bukkit.plugin.Plugin.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.namespace()).thenReturn(name);
        return plugin;
    }

    @Test
    void stakeAndPayoutMatchTheAuditedOdds() {
        assertEquals(3, LotusWager.STAKE, "wpisowe z audytu ECO-13");
        assertEquals(5, LotusWager.PAYOUT, "wygrana z audytu — EV 0,833 jak Underground");
        assertEquals(20, LotusWager.MAX_SALDO, "capy anty-tilt");
    }

    @Test
    void saldoAboveLimitBlocksTheWagerButExactlyTheLimitDoesNot() {
        assertTrue(LotusWager.saldoBlocksWager(21), "ponad 20 SL — zakaz gry");
        assertFalse(LotusWager.saldoBlocksWager(20), "równo 20 SL — gra dozwolona");
        assertFalse(LotusWager.saldoBlocksWager(3));
    }

    @Test
    void missingStakeRejectsBelowTheEntryPrice() {
        assertTrue(LotusWager.missingStake(2));
        assertFalse(LotusWager.missingStake(3));
    }

    @Test
    void dayMarkerInPlayerContainerBlocksSecondAttemptAndResetLiftsIt() {
        PersistentDataContainer container = mock(PersistentDataContainer.class);
        LocalDate today = LocalDate.of(2026, 9, 27);
        when(container.get(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(org.bukkit.persistence.PersistentDataType.STRING)))
                .thenReturn(null, "2026-09-27", "not-a-date");

        assertNull(wager.lastPlayedDay(container), "świeży gracz — brak wpisu");
        assertTrue(wager.playedOn(container, today), "wpis z dziś blokuje drugą próbę");
        assertFalse(wager.playedOn(container, today), "uszkodzony wpis nie blokuje na zawsze");

        wager.markPlayed(container, today);
        verify(container).set(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(org.bukkit.persistence.PersistentDataType.STRING),
                org.mockito.ArgumentMatchers.eq("2026-09-27"));

        wager.resetDay(container);
        verify(container).remove(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void grantOperationIdIsPerPlayerPerDay() {
        LocalDate day = LocalDate.of(2026, 9, 27);
        java.util.UUID player = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals("zaklad:" + player + ":" + day, LotusWager.grantOperationId(player, day),
                "deterministyczny klucz idempotencji outboxu");
    }
}
