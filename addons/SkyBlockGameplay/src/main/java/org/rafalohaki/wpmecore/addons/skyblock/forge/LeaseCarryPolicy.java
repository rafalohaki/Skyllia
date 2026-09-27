package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.jetbrains.annotations.NotNull;

/**
 * ECO-10: czysty rdzeń decyzji strażnika wynoszenia dzierżawy
 * ({@link LeaseCarryGuard}) — celowo bez żadnych importów Bukkit, bo statyczne
 * inicjalizatory klas zdarzeniowych (np. {@code InventoryType}) ciągną
 * {@code org.bukkit.Registry} i nie wstają w testach jednostkowych bez
 * serwera. Mapowanie enumów Bukkit na własne wartości robi listener.
 */
public final class LeaseCarryPolicy {

    /** Ruch, jaki wykonuje kliknięcie w ekwipunku. */
    public enum ClickVerb {
        /** Kursor wkłada do klikniętego slotu (PLACE_*, SWAP_WITH_CURSOR). */
        PLACE_FROM_CURSOR,
        /** Kursor wylatuje na ziemię (klik poza okno widoku). */
        DROP_FROM_CURSOR,
        /** Kliknięty slot wylatuje na ziemię (Q). */
        DROP_FROM_SLOT,
        /** Shift: przeniesienie do „drugiego" inwentarza widoku. */
        SHIFT_MOVE,
        /** Klawisz 1-9: podmiana klikniętego slotu z paskiem gorącym. */
        HOTBAR_SWAP,
        /** Zabieranie do siebie (PICKUP*, COLLECT_TO_CURSOR) i akcje obojętne. */
        TAKE
    }

    /**
     * Rodzaj górnego inwentarza widoku: STOÓŁ (własny ekwipunek, stoły
     * rzemieślnicze i robocze — vanilla shift-click nigdy nie zasila ich slotów
     * górnych, a ich sloty wracają do gracza przy zamknięciu) albo KONTENER
     * (skrzynia, piec, enderchest, GUI pluginu — trwałe osadzenie możliwe).
     */
    public enum ViewKind {
        STOOL,
        CONTAINER
    }

    private LeaseCarryPolicy() {
    }

    /**
     * Czy to kliknięcie wynosi dzierżawę poza ekwipunek gracza.
     * {@code clickedTop} — kliknięty slot leży w górnym inwentarzu widoku.
     */
    public static boolean depositsLease(@NotNull ClickVerb verb, boolean clickedTop,
                                        @NotNull ViewKind viewKind, boolean cursorLease,
                                        boolean currentLease, boolean hotbarLease) {
        return switch (verb) {
            // Kursor wkłada coś do klikniętego slotu — blokada tylko nad górą.
            case PLACE_FROM_CURSOR -> clickedTop && cursorLease;
            // Wyrzucanie (klik poza okno albo Q nad slotem) znika z ekwipunku.
            case DROP_FROM_CURSOR -> cursorLease;
            case DROP_FROM_SLOT -> currentLease;
            // Shift z własnego dolnego inwentarza: w kontenerach zasila górę,
            // w widokach stołów przenosi tylko wewnątrz ekwipunku gracza.
            case SHIFT_MOVE -> !clickedTop && currentLease && viewKind == ViewKind.CONTAINER;
            // Klawisz 1-9 nad slotem góry podmienia go z paska gorącego.
            case HOTBAR_SWAP -> clickedTop && hotbarLease;
            // Zabieranie — dozwolone zawsze (ratunek egzemplarzy z kontenerów).
            case TAKE -> false;
        };
    }

    /** Przeciągnięcie trafia do góry, więc kursor (dzierżawa) zostałby włożony. */
    public static boolean dragDepositsLease(boolean cursorLease,
                                            @NotNull Iterable<Integer> rawSlots, int topSize) {
        if (!cursorLease) {
            return false;
        }
        for (int rawSlot : rawSlots) {
            if (rawSlot >= 0 && rawSlot < topSize) {
                return true;
            }
        }
        return false;
    }
}
