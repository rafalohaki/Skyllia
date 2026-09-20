package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import fr.euphyllia.skyllia.api.event.SkyblockChangeOwnerEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.IslandProfileDao;

import java.util.logging.Level;

/**
 * M1-A domknięcie: projekcja ownera po transferze wyspy. Skyllia zmienia
 * ownera u siebie; WpmeCore musi odświeżyć wpme_sb_island_profiles.owner_uuid
 * i role w membership (stary OWNER → MEMBER, nowy → OWNER), inaczej profil
 * wskazuje na byłego właściciela.
 */
public final class IslandOwnerTransferListener implements org.bukkit.event.Listener {

    private final Plugin plugin;
    private final IslandProfileDao profileDao;

    private IslandOwnerTransferListener(@NotNull Plugin plugin, @NotNull IslandProfileDao profileDao) {
        this.plugin = plugin;
        this.profileDao = profileDao;
    }

    public static @NotNull IslandOwnerTransferListener register(@NotNull Plugin plugin,
                                                                @NotNull IslandProfileDao profileDao) {
        var listener = new IslandOwnerTransferListener(plugin, profileDao);
        Bukkit.getPluginManager().registerEvent(
                SkyblockChangeOwnerEvent.class, listener, EventPriority.MONITOR,
                (l, e) -> ((IslandOwnerTransferListener) l).onChange((SkyblockChangeOwnerEvent) e),
                plugin, false);
        return listener;
    }

    public void unregister() {
        HandlerList.unregisterAll((org.bukkit.plugin.java.JavaPlugin) plugin);
    }

    private void onChange(@NotNull SkyblockChangeOwnerEvent event) {
        var island = event.getIsland();
        if (island == null || island.getId() == null) return;
        // Nowy owner znany dopiero po stronie Skyllii — odpytujemy autorytatywnie async.
        var newOwner = island.getOwner() == null ? null : island.getOwner().getMojangId();
        if (newOwner == null) return;
        profileDao.refreshOwnerProjection(island.getId(), newOwner)
                .whenComplete((updated, err) -> {
                    if (err != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Owner projection refresh failed for island " + island.getId(), err);
                    } else if (Boolean.TRUE.equals(updated)) {
                        plugin.getLogger().info("Owner projection refreshed for island " + island.getId());
                    }
                });
    }
}
