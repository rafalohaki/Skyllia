package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinionStorageTest {

    @Test
    void addFillsExistingSlotUpToCapacity() {
        MinionStorage storage = MinionStorage.empty(2);
        assertEquals(63L, storage.add("DIAMOND", 63L));
        assertEquals(1L, storage.add("DIAMOND", 2L)); // 63 + 2 -> tylko 1 mieści się w limicie 64
        assertEquals(64L, storage.count("DIAMOND"));
        assertEquals(1, storage.usedSlots());
    }

    @Test
    void addRejectsNewMaterialWhenSlotsExhausted() {
        MinionStorage storage = MinionStorage.empty(1);
        storage.add("DIAMOND", 5L);
        assertEquals(0L, storage.add("COBBLESTONE", 1L));
        assertEquals(0L, storage.count("COBBLESTONE"));
        assertTrue(storage.isFull());
    }

    @Test
    void canAcceptReflectsCapacityAndSlots() {
        MinionStorage storage = MinionStorage.restore(2, Map.of("DIAMOND", 64L));
        assertFalse(storage.canAccept("DIAMOND", 1L));
        assertTrue(storage.canAccept("DIAMOND_BLOCK", 1L));
        storage.remove("DIAMOND", 64L);
        assertTrue(storage.canAccept("DIAMOND", 1L));
    }

    @Test
    void removeDeletesKeyAtZeroAndRestoreKeepsOrder() {
        MinionStorage storage = MinionStorage.restore(2, MinionRecord.decodeStorage("WHEAT=3;OAK_LOG=5"));
        assertEquals(3L, storage.remove("WHEAT", 3L));
        assertFalse(storage.snapshot().containsKey("WHEAT"));
        assertEquals("OAK_LOG=5", storage.encode());
    }

    @Test
    void expandSlotsOnlyGrows() {
        MinionStorage storage = MinionStorage.empty(2);
        storage.expandSlots(6);
        assertEquals(6, storage.slots());
        storage.expandSlots(3);
        assertEquals(6, storage.slots());
    }
}
