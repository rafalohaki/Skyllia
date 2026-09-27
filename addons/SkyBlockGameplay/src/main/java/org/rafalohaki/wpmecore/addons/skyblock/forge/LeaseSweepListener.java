package org.rafalohaki.wpmecore.addons.skyblock.forge;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import java.util.logging.Level;

/**
 * ECO-10: kasuje wygasłe dzierżawy z ekwipunku — raz przy wejściu i co
 * 5 minut dla obecnych graczy. Czyszczenie dotyka stanu gracza, więc zawsze
 * biegnie na wątku jego encji (CanvaSigma/Folia); pętla po graczach startuje
 * z globalnego schedulera i każdemu zleca pracę na jego własnym wątku.
 */
public final class LeaseSweepListener implements Listener {

    static final long JOIN_DELAY_TICKS = 40L;
    static final long PERIOD_TICKS = 5L * 60L * 20L;

    private final JavaPlugin plugin;
    private final LeaseExpiry leaseExpiry;
    private final MiniMessage miniMessage;

    public LeaseSweepListener(@NotNull JavaPlugin plugin, @NotNull LeaseExpiry leaseExpiry,
                              @NotNull MiniMessage miniMessage) {
        this.plugin = plugin;
        this.leaseExpiry = leaseExpiry;
        this.miniMessage = miniMessage;
    }

    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        scheduleOnce(event.getPlayer(), JOIN_DELAY_TICKS);
    }

    /** Startuje cykliczne sprzątanie dla graczy obecnych na serwerze. */
    public void start() {
        try {
            plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    scheduleOnce(player, 1L);
                }
            }, PERIOD_TICKS, PERIOD_TICKS);
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.WARNING,
                    "Cykliczne czyszczenie dzierżaw nie wystartowało; wygasłe wypożyczenia "
                            + "będą zdejmowane przy wejściu gracza", rejected);
        }
    }

    private void scheduleOnce(@NotNull Player player, long delayTicks) {
        try {
            player.getScheduler().runDelayed(plugin, task -> sweep(player), null, delayTicks);
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.FINE,
                    "Lease sweep task rejected for " + player.getUniqueId(), rejected);
        }
    }

    private void sweep(@NotNull Player player) {
        if (!player.isOnline() || !plugin.isEnabled()) {
            return;
        }
        int removed = leaseExpiry.sweep(player);
        if (removed > 0) {
            player.sendMessage(Ui.component(miniMessage,
                    "<gray>Wypożyczenie z kuźni wygasło"
                            + (removed > 1 ? "y (" + removed + " szt.)" : "")
                            + " — przedmiot wrócił do właściciela.</gray>"));
        }
    }
}
