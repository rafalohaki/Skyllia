package org.rafalohaki.wpmecore.addons.skyblock.perks;

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
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Perk rangi {@code skyblockgameplay.fly}: /latanie działa wyłącznie na wyspie, której
 * gracz jest członkiem. Wyjście poza wyspę (ruch, zmiana świata) gasi lot.
 * Moduł dotyka tylko lotu, który sam włączył — /gamemode i lot admina zostają w spokoju.
 */
public final class IslandFlyModule implements Listener {

    private static final Set<IslandRole> MEMBER_ROLES =
            Set.of(IslandRole.OWNER, IslandRole.CO_OWNER, IslandRole.MODERATOR, IslandRole.MEMBER);

    private final SkylliaIntegration skyllia;
    private final MiniMessage miniMessage;
    private final Set<UUID> enabledByModule = ConcurrentHashMap.newKeySet();

    public IslandFlyModule(@NotNull SkylliaIntegration skyllia, @NotNull MiniMessage miniMessage) {
        this.skyllia = skyllia;
        this.miniMessage = miniMessage;
    }

    static boolean isIslandMember(@NotNull IslandRole role) {
        return MEMBER_ROLES.contains(role);
    }

    private boolean onOwnIsland(@NotNull Player player, @NotNull Location at) {
        return skyllia.islandAt(player.getUniqueId(), at)
                .map(view -> isIslandMember(view.role()))
                .orElse(false);
    }

    /** /latanie — przełącznik; wołane na wątku regionu gracza (komenda Brigadier). */
    public void toggle(@NotNull Player player) {
        if (!RankPerks.canFly(player)) {
            player.sendMessage(Ui.component(miniMessage,
                    "<red>Lot na wyspie to perk rangi — zobacz <yellow>/sklep</yellow> na stronie 2b2t.pl.</red>"));
            return;
        }
        if (enabledByModule.remove(player.getUniqueId())) {
            player.setFlying(false);
            player.setAllowFlight(false);
            player.sendMessage(Ui.component(miniMessage, "<gray>Lot wyłączony.</gray>"));
            return;
        }
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
            player.sendMessage(Ui.component(miniMessage, "<gray>W tym trybie gry lot masz już z automatu.</gray>"));
            return;
        }
        if (!onOwnIsland(player, player.getLocation())) {
            player.sendMessage(Ui.component(miniMessage, "<red>Latać możesz tylko na własnej wyspie.</red>"));
            return;
        }
        enabledByModule.add(player.getUniqueId());
        player.setAllowFlight(true);
        player.sendMessage(Ui.component(miniMessage,
                "<green>Lot włączony</green> <gray>— działa tylko na Twojej wyspie, poza nią gaśnie.</gray>"));
    }

    private void groundIfLeftIsland(@NotNull Player player, @NotNull Location to) {
        if (!enabledByModule.contains(player.getUniqueId()) || onOwnIsland(player, to)) {
            return;
        }
        enabledByModule.remove(player.getUniqueId());
        player.setFlying(false);
        player.setAllowFlight(false);
        player.sendMessage(Ui.component(miniMessage, "<gray>Opuszczasz wyspę — lot wyłączony.</gray>"));
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
