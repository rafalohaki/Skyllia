package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.jetbrains.annotations.NotNull;

/**
 * Decyduje, co zrobić z operacją ekwipunku, która nie doszła do skutku.
 *
 * <p>Klasa jest celowo czysta — bez Bukkita, bez stanu, bez zegara — bo
 * {@code InventoryOutboxTest} działa wyłącznie pod profilem {@code mockbukkit},
 * a ta reguła musi być pilnowana przez domyślną bramkę testową.
 *
 * <p><b>Rozróżnienie ograniczonej i nieograniczonej gałęzi jest tu najważniejsze.</b>
 * Awaria bazy sama się goi, więc porzucenie przy niej trwałej operacji rozjechałoby
 * saldo z ekwipunkiem bez śladu — gałąź nieograniczona nigdy nie zwraca
 * {@link Decision#QUARANTINE}. Niezbieżność logiki nie zagoi się nigdy, a dopóki
 * trwa, dzierżawa jest {@code inventorySensitive} i gracz zostaje zamrożony oraz
 * nietykalny — dlatego gałąź ograniczona musi się kiedyś poddać.
 *
 * <p>Licznik prób należy do wywołującego. Ta klasa go nie przechowuje i nie zwraca,
 * żeby ta sama liczba nie żyła w dwóch miejscach.
 */
public final class ConvergenceRetryPolicy {

    /** Próby w trybie szybkim: 40 × 20 ticków ≈ 40 s. */
    public static final int FAST_ATTEMPTS = 40;

    /** Łączny budżet gałęzi ograniczonej: powyżej ≈ 5 min w trybie wolnym. */
    static final int TOTAL_ATTEMPTS = 100;

    public static final long FAST_DELAY_TICKS = 20L;
    static final long SLOW_DELAY_TICKS = 100L;

    private ConvergenceRetryPolicy() {
    }

    enum Decision {
        RETRY_FAST,
        RETRY_SLOW,
        /** Odstawienie do dead letter queue — patrz {@code InventoryOutbox#quarantine}. */
        QUARANTINE
    }

    /**
     * @param crossedIntoSlowMode prawdziwe dokładnie na jednej próbie w całej
     *                            sekwencji, żeby ostrzeżenie w logu poszło raz,
     *                            a nie sześćdziesiąt razy
     */
    record Step(@NotNull Decision decision, long delayTicks, boolean crossedIntoSlowMode) { }

    private static final Step FAST =
            new Step(Decision.RETRY_FAST, FAST_DELAY_TICKS, false);
    private static final Step SLOW_FIRST =
            new Step(Decision.RETRY_SLOW, SLOW_DELAY_TICKS, true);
    private static final Step SLOW =
            new Step(Decision.RETRY_SLOW, SLOW_DELAY_TICKS, false);
    private static final Step QUARANTINE =
            new Step(Decision.QUARANTINE, 0L, false);

    /**
     * @param attempt ile prób już zawiodło, licząc od zera
     * @param bounded {@code true} dla niezbieżności logiki (wolno się poddać),
     *                {@code false} dla awarii bazy (nie wolno)
     */
    static @NotNull Step next(int attempt, boolean bounded) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative: " + attempt);
        }
        if (attempt < FAST_ATTEMPTS) {
            return FAST;
        }
        if (attempt == FAST_ATTEMPTS) {
            return SLOW_FIRST;
        }
        if (bounded && attempt >= TOTAL_ATTEMPTS) {
            return QUARANTINE;
        }
        return SLOW;
    }
}
