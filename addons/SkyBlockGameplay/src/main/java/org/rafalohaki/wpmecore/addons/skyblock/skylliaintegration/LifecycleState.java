package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

/**
 * Powierzchowny stan lifecycle wyspy widoczny w {@link IslandSnapshot}. Po
 * przejściu na publiczne API Skyllii (bez własnego forka) nie śledzimy już
 * tokenu operacji ani blokad — pojedynczą bramą bezpieczeństwa banku jest
 * veto usunięcia w {@link IslandLifecycleGuard} na {@code SkyblockDeleteEvent}.
 * Stąd {@code active()} jako jedyna wartość wystawiana przez adapter.
 */
public record LifecycleState(boolean disabled) {

    public static LifecycleState active() {
        return new LifecycleState(false);
    }

    public boolean confirmsActive() {
        return !disabled;
    }
}
