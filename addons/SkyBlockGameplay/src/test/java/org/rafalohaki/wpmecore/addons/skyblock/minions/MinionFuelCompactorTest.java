package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinionFuelCompactorTest {

    private static final long NOW = 1_000_000L;

    @Test
    void fuelMultiplierAppliesOnlyWhileActive() {
        MinionsConfig.FuelDef coal = new MinionsConfig.FuelDef("coal", Material.COAL, null, 3600L, 2.0);
        assertTrue(MinionFuel.isActive(NOW + 1000L, NOW));
        assertFalse(MinionFuel.isActive(NOW - 1L, NOW));
        assertEquals(2.0, MinionFuel.multiplier(coal, NOW + 1000L, NOW), 1e-9);
        assertEquals(1.0, MinionFuel.multiplier(coal, NOW - 1L, NOW), 1e-9);   // wygasłe
        assertEquals(1.0, MinionFuel.multiplier(null, NOW + 1000L, NOW), 1e-9); // brak definicji
    }

    @Test
    void effectiveIntervalHalvesWithDoubleSpeedAndHasFloor() {
        assertEquals(5000L, MinionFuel.effectiveIntervalMillis(10.0, 2.0));
        assertEquals(10000L, MinionFuel.effectiveIntervalMillis(10.0, 1.0));
        assertEquals(250L, MinionFuel.effectiveIntervalMillis(1.0, 100.0)); // podłoga 250 ms
    }

    @Test
    void remainingSecondsClampsToZero() {
        assertEquals(0L, MinionFuel.remainingSeconds(NOW - 5000L, NOW));
        assertEquals(90L, MinionFuel.remainingSeconds(NOW + 90_500L, NOW));
    }

    @Test
    void compactsNineIntoOneBlockWithRemainder() {
        MinionStorage storage = MinionStorage.empty(2);
        storage.add("DIAMOND", 20L); // 2 bloki + 2 reszty
        MinionCompactor.compact(storage, Map.of(Material.DIAMOND, Material.DIAMOND_BLOCK));
        assertEquals(2L, storage.count("DIAMOND"));
        assertEquals(2L, storage.count("DIAMOND_BLOCK"));
        assertEquals(2, storage.usedSlots());
    }

    @Test
    void compactorLeavesCountsBelowNineAndCustomItemsUntouched() {
        MinionStorage storage = MinionStorage.restore(3,
                Map.of("DIAMOND", 8L, "custom:skyblock:crystal/citrine", 64L));
        MinionCompactor.compact(storage, Map.of(Material.DIAMOND, Material.DIAMOND_BLOCK));
        assertEquals(8L, storage.count("DIAMOND"));
        assertEquals(0L, storage.count("DIAMOND_BLOCK"));
        assertEquals(64L, storage.count("custom:skyblock:crystal/citrine"));
    }

    @Test
    void compactorSkipsConversionWhenTargetNeedsSlotAndNoneFree() {
        MinionStorage storage = MinionStorage.empty(2);
        storage.add("DIAMOND", 19L); // 2 bloki + reszta 1; slot 2 zajmie custom item
        storage.add("custom:skyblock:crystal/citrine", 1L);
        MinionCompactor.compact(storage, Map.of(Material.DIAMOND, Material.DIAMOND_BLOCK));
        assertEquals(19L, storage.count("DIAMOND")); // bez zmian: brak wolnego slotu na blok
        assertEquals(0L, storage.count("DIAMOND_BLOCK"));
    }
}
