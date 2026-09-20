package pl.b2t.skylliaprestige;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * /skyprestige — status i zakup poziomu prestiżu wyspy. Płatność leci przez
 * Vault (konto gracza); przy przejściu na bank wyspy (SkylliaBank) podmiana
 * nastąpi w {@link #charge(Player, long)} bez ruszania stanu wysp.
 */
public final class PrestigeCommand implements CommandExecutor, TabCompleter {

    private final SkylliaPrestige plugin;
    private Economy economy;

    PrestigeCommand(SkylliaPrestige plugin) {
        this.plugin = plugin;
    }

    void hookEconomy() {
        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
            plugin.getLogger().info("Ekonomia: " + economy.getName());
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cKomenda tylko dla graczy.");
            return true;
        }
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            player.sendMessage("§cNie masz wyspy.");
            return true;
        }
        if (!island.getOwner().getMojangId().equals(player.getUniqueId())) {
            player.sendMessage("§cTylko właściciel wyspy może zarządzać prestiżem.");
            return true;
        }

        PrestigeService service = plugin.prestige();
        int level = service.levelOf(island);
        PrestigeService.Settings settings = service.settings();

        if (args.length == 0 || !args[0].equalsIgnoreCase("kup")) {
            sendStatus(player, island, service, level, settings);
            return true;
        }

        if (level >= settings.maxLevel()) {
            player.sendMessage("§cOsiągnięto maksymalny poziom prestiżu.");
            return true;
        }
        long cost = settings.costFor(level + 1);
        if (!charge(player, cost)) {
            player.sendMessage("§cBrakuje środków. Koszt poziomu " + (level + 1) + ": §e" + cost + "§c monet.");
            return true;
        }
        boolean ok = service.recordLevel(island, level, level + 1, cost);
        if (!ok) {
            refund(player, cost);
            player.sendMessage("§cNie udało się zapisać poziomu — środki zwrócone.");
            return true;
        }
        int newLevel = level + 1;
        String title = settings.titleFor(newLevel);
        player.sendMessage("§aPrestiż wyspy wzrósł do poziomu §e" + newLevel + "§a" +
                (title != null ? " — tytuł: §6" + title : ""));
        return true;
    }

    private void sendStatus(Player player, Island island, PrestigeService service,
                            int level, PrestigeService.Settings s) {
        player.sendMessage("§6§lPrestiż wyspy");
        player.sendMessage("§7Poziom: §e" + level + "§7/§e" + s.maxLevel());
        String title = s.titleFor(level);
        if (title != null) player.sendMessage("§7Tytuł: §6" + title);
        player.sendMessage("§7Dodatkowe sloty minionów: §a+" + s.extraMinionSlots(level));
        player.sendMessage("§7Szczęście kryształów: §a+" + (int) (s.crystalLuckPercentPerLevel() * level) + "%");
        if (level < s.maxLevel()) {
            long cost = s.costFor(level + 1);
            player.sendMessage("§7Koszt poziomu " + (level + 1) + ": §e" + cost + " monet");
            player.sendMessage("§8Użyj /skyprestige kup aby kupić.");
        } else {
            player.sendMessage("§aMaksymalny poziom osiągnięty.");
        }
    }

    private boolean charge(Player player, long cost) {
        if (economy == null) return false;
        return economy.withdrawPlayer(player, cost).transactionSuccess();
    }

    private void refund(Player player, long cost) {
        if (economy != null) economy.depositPlayer(player, cost);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) return List.of("kup");
        return List.of();
    }
}
