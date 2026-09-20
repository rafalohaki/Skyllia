package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import fr.euphyllia.skyllia.api.event.SkyblockCreateEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.ProfileStateService;

import java.util.logging.Level;

/**
 * Post-create profile guard: after Skyllia creates an island, attempt to
 * insert the Wpme profile row via {@link ProfileStateService}. If the owner
 * already has an ACTIVE membership, the insert fails (one-active guard) and
 * the event is logged. The island still exists in Skyllia but the Wpme guard
 * rejects further Wpme-level operations for that duplicate until admin repair.
 *
 * <p>Staying inside {@code skylliaintegration} keeps {@code fr.euphyllia.skyllia..}
 * imports out of SkyBlockGameplay (R-API-01).
 */
public final class IslandProfileCreationListener implements Listener {

    private final JavaPlugin plugin;
    private final ProfileStateService profiles;

    public IslandProfileCreationListener(@NotNull JavaPlugin plugin, @NotNull ProfileStateService profiles) {
        this.plugin = plugin;
        this.profiles = profiles;
    }

    public static @NotNull IslandProfileCreationListener register(@NotNull JavaPlugin plugin, @NotNull ProfileStateService profiles) {
        IslandProfileCreationListener listener = new IslandProfileCreationListener(plugin, profiles);
        Bukkit.getPluginManager().registerEvent(
                SkyblockCreateEvent.class, listener, EventPriority.MONITOR,
                (l, e) -> ((IslandProfileCreationListener) l).onCreate((SkyblockCreateEvent) e),
                plugin, false);
        return listener;
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    private void onCreate(@NotNull SkyblockCreateEvent event) {
        var island = event.getIsland();
        var owner = island.getOwner();
        if (owner == null || owner.getMojangId() == null) {
            return;
        }
        profiles.tryCreateIsland(owner.getMojangId(), island.getId(), "CLASSIC")
                .whenComplete((ok, failure) -> {
                    if (failure != null) {
                        plugin.getLogger().log(Level.WARNING, "Profile create failed for " + island.getId(), failure);
                    } else if (Boolean.FALSE.equals(ok)) {
                        plugin.getLogger().warning("Profile guard rejected island " + island.getId() + " for " + owner.getMojangId() + " (already has active island)");
                    }
                });
    }
}
