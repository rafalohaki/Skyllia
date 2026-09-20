package pl.b2t.skylliaprestige;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import fr.euphyllia.skyllia.api.skyblock.Island;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /is prestige — status i zakup poziomu prestiżu wyspy. Płatność leci przez
 * Vault (konto gracza); przy przejściu na bank wyspy (SkylliaBank) podmiana
 * nastąpi w {@link #charge(Player, long)} bez ruszania stanu wysp.
 */
public final class PrestigeCommand implements SubCommandInterface {

    private final SkylliaPrestige plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private Economy economy;

    PrestigeCommand(SkylliaPrestige plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    @Override
    public void onExecute(@NotNull Plugin plugin, @NotNull CommandSender sender, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(mm.deserialize("<red>Komenda tylko dla graczy."));
            return;
        }
        Island island = SkylliaAPI.getIslandByPlayerId(player.getUniqueId());
        if (island == null) {
            player.sendMessage(mm.deserialize("<red>Nie masz wyspy."));
            return;
        }
        if (!island.getOwner().getMojangId().equals(player.getUniqueId())) {
            player.sendMessage(mm.deserialize("<red>Tylko właściciel wyspy może zarządzać prestiżem."));
            return;
        }

        PrestigeService service = this.plugin.prestige();
        int level = service.levelOf(island);
        PrestigeService.Settings settings = service.settings();

        if (args.length == 0 || !args[0].equalsIgnoreCase("kup")) {
            sendStatus(player, service, level, settings);
            return;
        }

        if (level >= settings.maxLevel()) {
            player.sendMessage(mm.deserialize("<red>Osiągnięto maksymalny poziom prestiżu."));
            return;
        }
        long cost = settings.costFor(level + 1);
        if (!charge(player, cost)) {
            player.sendMessage(mm.deserialize(
                    "<red>Brakuje środków. Koszt poziomu " + (level + 1) + ": <yellow>" + cost + "</yellow> monet."));
            return;
        }
        boolean ok = service.recordLevel(island, level, level + 1, cost);
        if (!ok) {
            refund(player, cost);
            player.sendMessage(mm.deserialize("<red>Nie udało się zapisać poziomu — środki zwrócone."));
            return;
        }
        int newLevel = level + 1;
        String title = settings.titleFor(newLevel);
        player.sendMessage(mm.deserialize("<green>Prestiż wyspy wzrósł do poziomu <yellow>" + newLevel + "</yellow>" +
                (title != null ? " — tytuł: <gold>" + title : "")));
    }

    private void sendStatus(Player player, PrestigeService service, int level,
                            PrestigeService.Settings s) {
        player.sendMessage(mm.deserialize("<gold><bold>Prestiż wyspy"));
        player.sendMessage(mm.deserialize("<gray>Poziom: <yellow>" + level + "</yellow>/<yellow>" + s.maxLevel()));
        String title = s.titleFor(level);
        if (title != null) player.sendMessage(mm.deserialize("<gray>Tytuł: <gold>" + title));
        player.sendMessage(mm.deserialize("<gray>Dodatkowe sloty minionów: <green>+" + s.extraMinionSlots(level)));
        player.sendMessage(mm.deserialize("<gray>Szczęście kryształów: <green>+" + (int) (s.crystalLuckPercentPerLevel() * level) + "%"));
        if (level < s.maxLevel()) {
            long cost = s.costFor(level + 1);
            player.sendMessage(mm.deserialize("<gray>Koszt poziomu " + (level + 1) + ": <yellow>" + cost + " monet"));
            player.sendMessage(mm.deserialize("<dark_gray>Użyj /is prestige kup aby kupić."));
        } else {
            player.sendMessage(mm.deserialize("<green>Maksymalny poziom osiągnięty."));
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
    public @NotNull List<String> onTabComplete(@NotNull Plugin plugin, @NotNull CommandSender sender,
                                               @NotNull String[] args) {
        if (args.length == 1) return List.of("kup");
        return List.of();
    }
}
