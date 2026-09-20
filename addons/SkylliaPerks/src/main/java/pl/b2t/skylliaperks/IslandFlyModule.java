package pl.b2t.skylliaperks;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Perk rangi {@code skyblockgameplay.fly}: /latanie działa wyłącznie na wyspie,
 * której gracz jest członkiem. Wyjście poza wyspę (ruch, zmiana świata) gasi
 * lot. Moduł dotyka tylko lotu, który sam włączył — /gamemode i lot admina
 * zostają w spokoju.
 */
public final class IslandFlyModule implements Listener {

    private static final Set<RoleType> MEMBER_ROLES =
            Set.of(RoleType.OWNER, RoleType.CO_OWNER, RoleType.MODERATOR, RoleType.MEMBER);

    private final MiniMessage miniMessage;
    private final Set<UUID> enabledByModule = ConcurrentHashMap.newKeySet();

    public IslandFlyModule(@NotNull MiniMessage miniMessage) {
        this.miniMessage = miniMessage;
    }

    private boolean onOwnIsland(@NotNull Player player, @NotNull Location at) {
        Island island = SkylliaAPI.getIslandByChunk(at.getBlockX() >> 4, at.getBlockZ() >> 4);
        if (island == null) {
            return false;
        }
        for (Players member : island.getMembers()) {
            if (member.getMojangId().equals(player.getUniqueId())) {
                return MEMBER_ROLES.contains(member.getRoleType());
            }
        }
        return false;
    }

    /** /latanie — przełącznik; wołane na wątku regionu gracza (komenda Brigadier). */
    public void toggle(@NotNull Player player) {
        if (!player.hasPermission(SkylliaPerks.FLY_PERMISSION)) {
            player.sendMessage(miniMessage.deserialize(
                    "<red>Lot na wyspie to perk rangi — zobacz <yellow>/sklep</yellow> na stronie 2b2t.pl.</red>"));
            return;
        }
        if (enabledByModule.remove(player.getUniqueId())) {
            player.setFlying(false);
            player.setAllowFlight(false);
            player.sendMessage(Component.text("Lot wyłączony."));
            return;
        }
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
            player.sendMessage(Component.text("W tym trybie gry lot masz już z automatu."));
            return;
        }
        if (!onOwnIsland(player, player.getLocation())) {
            player.sendMessage(Component.text("Latać możesz tylko na własnej wyspie."));
            return;
        }
        enabledByModule.add(player.getUniqueId());
        player.setAllowFlight(true);
        player.sendMessage(miniMessage.deserialize(
                "<green>Lot włączony</green> <gray>— działa tylko na Twojej wyspie, poza nią gaśnie.</gray>"));
    }

    private void groundIfLeftIsland(@NotNull Player player, @NotNull Location to) {
        if (!enabledByModule.contains(player.getUniqueId()) || onOwnIsland(player, to)) {
            return;
        }
        enabledByModule.remove(player.getUniqueId());
        player.setFlying(false);
        player.setAllowFlight(false);
        player.sendMessage(Component.text("Opuszczasz wyspę — lot wyłączony."));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld()) {
            return;
        }
        groundIfLeftIsland(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        groundIfLeftIsland(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        enabledByModule.remove(event.getPlayer().getUniqueId());
    }
}
