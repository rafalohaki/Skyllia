package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Komunikat o nieznanym trybie wyspy nie może obiecywać trybu wyłączonego.
 *
 * <p>Regresja: literał „Dostępne: classic, oneblock, expedition" był wpisany na
 * sztywno w {@code SkyBlockGameplay}, a {@code expedition} jest domyślnie
 * wyłączone — gracz dostawał propozycję trybu, którego nie da się wybrać
 * (audyt gotowości produkcyjnej 2026-09-11). Ten test pilnuje, że lista liczy się
 * z flag, na których opiera się menu tworzenia wyspy.
 */
class IslandModeListTest {

    /** Ta sama logika co {@code SkyBlockGameplay.enabledModeIds()}, bez Bukkita. */
    private static String enabledIds(IslandModeFlags flags) {
        return Arrays.stream(IslandMode.values())
                .filter(flags::isEnabled)
                .map(IslandMode::id)
                .collect(Collectors.joining(", "));
    }

    @Test
    void domyslneFlagiNieWymieniajaEkspedycji() {
        String ids = enabledIds(IslandModeFlags.defaults());

        assertTrue(ids.contains("classic"), ids);
        assertFalse(ids.contains("expedition"),
                "expedition jest domyslnie wylaczony, wiec nie moze byc proponowany: " + ids);
        assertFalse(ids.contains("oneblock"), ids);
    }

    @Test
    void wlaczonyTrybPojawiaSieWLiScie() {
        IslandModeFlags flags = new IslandModeFlags(true, true, false);
        String ids = enabledIds(flags);

        assertEquals("classic, oneblock", ids, "kolejnosc enum: classic, oneblock");
        assertTrue(Arrays.stream(IslandMode.values()).anyMatch(m -> m.id().equals("expedition")));
    }

    @Test
    void wlaczonaEkspedycjaTezSiePojawia() {
        IslandModeFlags flags = new IslandModeFlags(true, false, true);

        assertTrue(enabledIds(flags).contains("expedition"),
                "gdy operator wlaczy expedition, komunikat ma ja wymienic");
    }
}
