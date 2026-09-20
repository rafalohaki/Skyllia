package pl.b2t.skylliaminions;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bufor urobku minionka. Slot = jeden klucz (materiał lub custom:&lt;id&gt;);
 * pojemność slotu to stała 64 (wszystkie surowce minionków są stackowalne do 64,
 * a Material.getMaxStackSize() wymaga RegistryAccess serwera). Metody
 * synchronized, bo bufor jest współdzielony przez wątek regionu minionka,
 * ewentualne taski regionu skrzyni linked oraz GUI gracza.
 */
public final class MinionStorage {

    private static final long SLOT_CAPACITY = 64L;

    private int slots;
    private final LinkedHashMap<String, Long> contents = new LinkedHashMap<>();

    private MinionStorage(int slots) {
        this.slots = Math.max(1, slots);
    }

    public static @NotNull MinionStorage empty(int slots) {
        return new MinionStorage(slots);
    }

    public static @NotNull MinionStorage restore(int slots, @NotNull Map<String, Long> contents) {
        MinionStorage storage = new MinionStorage(slots);
        for (Map.Entry<String, Long> entry : contents.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0L) {
                storage.contents.put(entry.getKey(), Math.min(entry.getValue(), SLOT_CAPACITY));
            }
        }
        return storage;
    }

    public synchronized int slots() {
        return slots;
    }

    public synchronized int usedSlots() {
        return contents.size();
    }

    public synchronized boolean hasFreeSlot() {
        return contents.size() < slots;
    }

    public synchronized boolean isFull() {
        return !hasFreeSlot();
    }

    public synchronized long slotCapacity(@NotNull String key) {
        return SLOT_CAPACITY;
    }

    public synchronized long count(@NotNull String key) {
        return contents.getOrDefault(key, 0L);
    }

    public synchronized boolean canAccept(@NotNull String key, long amount) {
        Long current = contents.get(key);
        if (current != null) {
            return current + amount <= SLOT_CAPACITY;
        }
        return hasFreeSlot() && amount <= SLOT_CAPACITY;
    }

    public synchronized long add(@NotNull String key, long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        Long current = contents.get(key);
        if (current == null) {
            if (!hasFreeSlot()) {
                return 0L;
            }
            long stored = Math.min(amount, SLOT_CAPACITY);
            contents.put(key, stored);
            return stored;
        }
        long stored = Math.min(amount, SLOT_CAPACITY - current);
        if (stored > 0L) {
            contents.put(key, current + stored);
        }
        return stored;
    }

    public synchronized long remove(@NotNull String key, long amount) {
        if (amount <= 0L) {
            return 0L;
        }
        Long current = contents.get(key);
        if (current == null) {
            return 0L;
        }
        long removed = Math.min(amount, current);
        if (current - removed <= 0L) {
            contents.remove(key);
        } else {
            contents.put(key, current - removed);
        }
        return removed;
    }

    public synchronized @NotNull Map<String, Long> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(contents));
    }

    public synchronized @NotNull String encode() {
        return MinionRecord.encodeStorage(contents);
    }

    public synchronized void expandSlots(int newSlots) {
        if (newSlots > slots) {
            slots = newSlots;
        }
    }
}
