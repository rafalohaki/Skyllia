package org.rafalohaki.wpmecore.addons.skyblock.reward;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.perks.RankPerks;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Strona Bukkit codziennej nagrody: auto-odbiór 2 s po wejściu i {@code /nagroda}.
 * Stan gracza dotykamy wyłącznie na jego wątku (CanvaSigma/Folia); SQL i księga
 * są asynchroniczne, wynik wraca przez {@code player.getScheduler().run}.
 */
public final class DailyRewardListener implements Listener {

    private static final long JOIN_DELAY_TICKS = 40L;

    private final Plugin plugin;
    private final DailyRewardService service;
    private final InventoryOutbox outbox;
    private final CustomItemService customItems;
    private final MiniMessage miniMessage;
    private final String lotusItem;
    /** ECO-14: identyfikator Złotego Lotosa za wielką serię. */
    private final String greatItem;

    public DailyRewardListener(@NotNull Plugin plugin, @NotNull DailyRewardService service,
                               @NotNull InventoryOutbox outbox, @NotNull CustomItemService customItems,
                               @NotNull MiniMessage miniMessage, @NotNull String lotusItem) {
        this(plugin, service, outbox, customItems, miniMessage, lotusItem,
                "skyblock:token/gold_lotus");
    }

    public DailyRewardListener(@NotNull Plugin plugin, @NotNull DailyRewardService service,
                               @NotNull InventoryOutbox outbox, @NotNull CustomItemService customItems,
                               @NotNull MiniMessage miniMessage, @NotNull String lotusItem,
                               @NotNull String greatItem) {
        this.plugin = plugin;
        this.service = service;
        this.outbox = outbox;
        this.customItems = customItems;
        this.miniMessage = miniMessage;
        this.lotusItem = lotusItem;
        this.greatItem = greatItem;
    }

    /** ECO-14 hook operatorski ({@code /sezon admin seria}) — dostęp do serwisu. */
    public @NotNull DailyRewardService service() {
        return service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        try {
            player.getScheduler().runDelayed(plugin, task -> claim(player, false), null, JOIN_DELAY_TICKS);
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.FINE, "Daily reward task rejected for " + player.getUniqueId(), rejected);
        }
    }

    /** {@code /nagroda}: odbiór, a gdy dziś już odebrano — status. Wołane na wątku gracza. */
    public void command(@NotNull Player player) {
        claim(player, true);
    }

    private void claim(@NotNull Player player, boolean showStatus) {
        if (!player.isOnline() || !plugin.isEnabled()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        int bonus = RankPerks.dailyBonusPercent(player);
        service.claim(uuid, today, bonus).whenComplete((claim, failure) ->
                player.getScheduler().run(plugin, task -> {
                    if (failure != null) {
                        plugin.getLogger().log(Level.WARNING, "Daily reward claim failed for " + uuid, failure);
                        player.sendMessage(Ui.component(miniMessage,
                                "<red>Codzienna nagroda nie została odebrana — spróbuj <white>/nagroda</white> za chwilę.</red>"));
                        return;
                    }
                    if (claim.isPresent()) {
                        deliver(player, claim.get(), today);
                    } else if (showStatus) {
                        status(player, today, bonus);
                    }
                }, null));
    }

    private void deliver(@NotNull Player player, @NotNull DailyRewardService.Claim claim,
                         @NotNull LocalDate today) {
        player.sendMessage(Ui.component(miniMessage, "<green>Codzienna nagroda: <white>+"
                + Ui.money(claim.coins()) + "</white> (seria " + claim.streak() + " dni)</green>"));
        if (claim.greatSeries()) {
            deliverGreatSeries(player, claim, today);
        }
        if (!claim.lotus()) {
            return;
        }
        ItemStack lotus = customItems.create(lotusItem).orElse(null);
        if (lotus == null) {
            plugin.getLogger().warning("Daily reward lotus item missing: " + lotusItem);
            return;
        }
        // Deterministyczny operationId: powtórka po restarcie jest no-opem outboxu.
        outbox.beginGrant(player, 0L, "daily:" + player.getUniqueId() + ":" + today + ":lotus",
                "daily-reward-lotus", lotus, outcome -> {
                    if (outcome == InventoryOutbox.Outcome.SUCCESS
                            || outcome == InventoryOutbox.Outcome.DEFERRED) {
                        player.sendMessage(Ui.component(miniMessage, "<gold>Seria " + claim.streak()
                                + " dni — dostajesz <white>Srebrny Lotos</white>!</gold>"));
                    } else {
                        plugin.getLogger().warning("Daily reward lotus grant " + outcome
                                + " for " + player.getUniqueId());
                    }
                });
    }

    /**
     * ECO-14: wielka seria — co {@code great-every}. dni serii gracz dostaje
     * Złotego Lotosa (outbox, deterministyczny operationId jak lotos) i dodatkowe
     * monety, które wpłacił już serwis (księga). Komunikat ma być NIE do
     * przegapienia: retencja zależy od tego, żeby gracz wiedział, że seria
     * coś znaczy.
     */
    private void deliverGreatSeries(@NotNull Player player,
                                    @NotNull DailyRewardService.Claim claim,
                                    @NotNull LocalDate today) {
        player.sendMessage(Ui.component(miniMessage,
                "<gold><bold>★ WIELKA SERIA: " + claim.streak() + " DNI! ★</bold></gold>"));
        if (claim.greatCoins() > 0L) {
            // Ui.money zwraca już kwotę z odmienioną jednostką („moneta/monety/monet”)
            // — szablon nie dokleja własnej.
            player.sendMessage(Ui.component(miniMessage, "<green>Bonus wielkiej serii: <white>+"
                    + Ui.money(claim.greatCoins()) + "</white>.</green>"));
        }
        ItemStack great = customItems.create(greatItem).orElse(null);
        if (great == null) {
            plugin.getLogger().warning("Daily reward great-series item missing: " + greatItem);
            return;
        }
        outbox.beginGrant(player, 0L,
                "daily:" + player.getUniqueId() + ":" + today + ":great-series",
                "daily-reward-great-series", great, outcome -> {
                    if (outcome == InventoryOutbox.Outcome.SUCCESS
                            || outcome == InventoryOutbox.Outcome.DEFERRED) {
                        player.sendMessage(Ui.component(miniMessage,
                                "<gold>Dostajesz <white>Złoty Lotos</white>!"
                                + " Nie przerywaj serii — następna czeka za "
                                + service.daysToNextGreat(claim.streak()) + " dni.</gold>"));
                    } else {
                        plugin.getLogger().warning("Daily reward great-series grant " + outcome
                                + " for " + player.getUniqueId());
                    }
                });
    }

    private void status(@NotNull Player player, @NotNull LocalDate today, int bonus) {
        service.find(player.getUniqueId()).whenComplete((row, failure) ->
                player.getScheduler().run(plugin, task -> {
                    DailyRewardService.Row current = failure != null || row == null ? null : row.orElse(null);
                    boolean claimedToday = current != null && today.toString().equals(current.lastClaimDay());
                    int next = DailyRewardService.nextStreak(current, claimedToday ? today.plusDays(1) : today);
                    int streak = current == null ? 0 : current.streak();
                    player.sendMessage(Ui.component(miniMessage, "<gold>Codzienna nagroda</gold> <gray>—</gray> seria: <white>"
                            + streak + " dni</white>, następna: <white>+"
                            + Ui.money(service.coinsFor(next, bonus)) + "</white>"
                            + (next % DailyRewardService.LOTUS_EVERY == 0 ? " <white>+ Srebrny Lotos</white>" : "")
                            + (service.isGreatSeries(next)
                                    ? " <gold><bold>+ WIELKA SERIA</bold></gold>"
                                    : (service.daysToNextGreat(streak) > 0
                                            ? " <dark_gray>(wielka seria za " + service.daysToNextGreat(streak) + ")</dark_gray>"
                                            : ""))
                            + (claimedToday ? " <gray>(odebrano dziś)</gray>" : "")));
                }, null));
    }
}
