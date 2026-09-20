package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

/**
 * Per-player widok wyspy: island-snapshot + rola tego gracza. Typowany niezmiennik.
 * {@link #canWithdraw()} jest CACHE-only (do bramki UI); autorytatywna decyzja przed
 * commitem przez {@link SkylliaIntegration#authoritativeCanWithdraw}.
 */
public record IslandView(IslandSnapshot snapshot, IslandRole role) {

    public boolean canWithdraw() {
        return role == IslandRole.OWNER || role == IslandRole.CO_OWNER;
    }

    public boolean roleKnown() {
        return role != IslandRole.UNKNOWN;
    }

    public java.util.UUID islandId() {
        return snapshot.islandId();
    }

    public java.util.UUID ownerId() {
        return snapshot.ownerId();
    }
}
