package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Zbijanie 9 sztuk surowca w blok. Klucze custom:* nigdy nie trafiają do mapy
 * mapping, więc przedmioty specjalne są chronione z konstrukcji. Liczba bloków
 * nie przekroczy 7 (slot mieści maks. 64), więc add zawsze zmieści wynik.
 */
public final class MinionCompactor {

    public static final long RATIO = 9L;

    private MinionCompactor() { }

    public static void compact(@NotNull MinionStorage storage,
                               @NotNull Map<Material, Material> mappings) {
        for (Map.Entry<Material, Material> entry : mappings.entrySet()) {
            String source = entry.getKey().name();
            String target = entry.getValue().name();
            if (source.equals(target)) {
                continue;
            }
            long count = storage.count(source);
            if (count < RATIO) {
                continue;
            }
            long blocks = count / RATIO;
            long remainder = count % RATIO;
            // docelowy materiał potrzebuje slotu, gdy go jeszcze nie ma, a slot
            // źródła nie zwolni się w całości (zostaje reszta)
            if (storage.count(target) == 0L && !storage.hasFreeSlot() && remainder > 0L) {
                continue;
            }
            storage.remove(source, count);
            if (remainder > 0L) {
                storage.add(source, remainder);
            }
            storage.add(target, blocks);
        }
    }
}
