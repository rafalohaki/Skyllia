package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class IslandBankMenu {

    private static final int[] DEPOSIT_SLOTS = {19, 20, 21, 22, 23};
    private static final int[] WITHDRAW_SLOTS = {28, 29, 30, 31, 32};
    private static final String WITHDRAW_PERMISSION =
            "skyblockgameplay.bank.withdraw";
    private static final long AUTHORIZATION_TIMEOUT_SECONDS = 5L;

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage miniMessage;
    private final LedgerService ledger;
    private final SkylliaIntegration skyllia;
    private final PlayerOperationCoordinator coordinator;
    private final InventoryOutbox outbox;
    private final List<Long> amounts;
    /**
     * Najniższa kwota z configu ({@code bank.amounts}) — tyle wie „Wpłać wszystko”,
     * więc lore nie może zaszywać na sztywno liczby, której w configu nie ma.
     */
    private final long smallestAmount;
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();

    public IslandBankMenu(@NotNull JavaPlugin plugin, @NotNull MenuService menus,
                   @NotNull MiniMessage miniMessage, @NotNull LedgerService ledger,
                   @NotNull SkylliaIntegration skyllia,
                   @NotNull PlayerOperationCoordinator coordinator,
                   @NotNull InventoryOutbox outbox,
                   @NotNull List<Long> amounts) {
        this.plugin = plugin;
        this.menus = menus;
        this.miniMessage = miniMessage;
        this.ledger = ledger;
        this.skyllia = skyllia;
        this.coordinator = coordinator;
        this.outbox = outbox;
        this.amounts = amounts;
        this.smallestAmount = amounts.stream()
                .mapToLong(Long::longValue)
                .min()
                .orElse(0L);
    }

    public void open(@NotNull Player player) {
        IslandView context = skyllia.islandOf(player.getUniqueId()).orElse(null);
        if (context == null) {
            player.sendMessage(Ui.component(miniMessage,
                    "<red>Najpierw utwórz wyspę komendą <yellow>/is</yellow>. "
                            + "Jeśli właśnie dołączyłeś, spróbuj ponownie za chwilę.</red>"));
            return;
        }
        if (!opening.add(player.getUniqueId())) {
            player.sendActionBar(Ui.component(miniMessage,
                    "<gray>Odświeżam salda banku…</gray>"));
            return;
        }
        ledger.authoritativePlayerBalance(player.getUniqueId())
                .thenCombine(ledger.authoritativeIslandBalance(context.islandId()),
                        BankBalances::new)
                .whenComplete((balances, failure) -> scheduleOpen(
                        player, context.islandId(), balances, failure));
    }

    private void scheduleOpen(Player player, UUID expectedIslandId,
                              BankBalances balances, Throwable failure) {
        try {
            var scheduled = player.getScheduler().run(plugin, ignored -> {
                opening.remove(player.getUniqueId());
                if (failure != null || balances == null) {
                    if (failure != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Island bank balance refresh failed", failure);
                    }
                    player.sendMessage(Ui.component(miniMessage,
                            "<red>Nie udało się odświeżyć sald banku. Spróbuj ponownie.</red>"));
                    return;
                }
                IslandView current = skyllia
                        .islandOf(player.getUniqueId()).orElse(null);
                if (current == null || !current.islandId().equals(expectedIslandId)) {
                    player.sendMessage(Ui.component(miniMessage,
                            "<red>Twoje członkostwo wyspy właśnie się zmieniło.</red>"));
                    return;
                }
                openNow(player, current, balances);
            }, () -> opening.remove(player.getUniqueId()));
            if (scheduled == null) {
                opening.remove(player.getUniqueId());
            }
        } catch (RuntimeException rejected) {
            opening.remove(player.getUniqueId());
            plugin.getLogger().log(java.util.logging.Level.WARNING, // SKYBLOCK-2-10: FINE czynił degradację niewidoczną
                    "Island bank menu scheduling was rejected", rejected);
        }
    }

    private void openNow(Player player, IslandView context,
                         BankBalances balances) {
        MenuService.Menu menu = menus.ofRows(5, Ui.panelTitle(5,
                Ui.component(miniMessage, "<gold><bold>Wspólny bank wyspy</bold></gold>")));
        Ui.frame(menu, miniMessage, Material.YELLOW_STAINED_GLASS_PANE);
        menu.decoration(11, Ui.item(Material.GOLD_INGOT, miniMessage,
                "<gold>Twój portfel</gold>",
                List.of(Ui.price(balances.player())), true));
        menu.decoration(15, Ui.item(Material.ENDER_CHEST, miniMessage,
                "<aqua>Bank wyspy</aqua>",
                List.of(Ui.price(balances.island()),
                        "<gray>Wspólny dla wszystkich członków.</gray>"), true));
        for (int index = 0; index < amounts.size(); index++) {
            long amount = amounts.get(index);
            menu.set(DEPOSIT_SLOTS[index], Ui.item(Material.LIME_DYE, miniMessage,
                            "<green>Wpłać " + Ui.money(amount) + "</green>",
                            List.of("<gray>Portfel → bank wyspy</gray>",
                                    "<yellow>Kliknij, aby wpłacić.</yellow>"), false),
                    (viewer, click) -> transfer(viewer, amount, true));
            boolean allowed = context.canWithdraw()
                    && player.hasPermission(WITHDRAW_PERMISSION);
            menu.set(WITHDRAW_SLOTS[index], Ui.item(
                            allowed ? Material.ORANGE_DYE : Material.GRAY_DYE, miniMessage,
                            allowed ? "<gold>Wypłać " + Ui.money(amount) + "</gold>"
                                    : context.roleKnown()
                                    ? "<gray>Wypłata tylko dla OWNER/CO_OWNER</gray>"
                                    : "<gray>Trwa weryfikacja roli…</gray>",
                            context.roleKnown()
                                    ? (allowed
                                            ? List.of("<gray>Bank wyspy → portfel</gray>",
                                                    "<yellow>Kliknij, aby wypłacić.</yellow>")
                                            : List.of("<gray>Bank wyspy → portfel</gray>"))
                                    : List.of("<gray>Zamknij menu i spróbuj ponownie za chwilę.</gray>"),
                            false),
                    (viewer, click) -> transfer(viewer, amount, false));
        }
        menu.set(23, Ui.item(Material.LIME_CONCRETE, miniMessage,
                        "<green><bold>Wpłać wszystko</bold></green>",
                        List.of("<gray>Cały portfel → bank wyspy</gray>",
                                "<dark_gray>Przelew obejmie także kwoty mniejsze niż "
                                        + Ui.money(smallestAmount) + ".</dark_gray>",
                                "<yellow>Kliknij, aby wpłacić.</yellow>"),
                        false),
                (viewer, click) -> transferAll(viewer, true));
        boolean canWithdrawAll = context.canWithdraw()
                && player.hasPermission(WITHDRAW_PERMISSION);
        menu.set(32, Ui.item(canWithdrawAll ? Material.ORANGE_CONCRETE : Material.GRAY_CONCRETE,
                        miniMessage,
                        canWithdrawAll ? "<gold><bold>Wypłać wszystko</bold></gold>"
                                : "<gray>Wypłata tylko dla OWNER/CO_OWNER</gray>",
                        List.of("<gray>Cały bank wyspy → portfel</gray>",
                                "<dark_gray>Umożliwia bezpieczne wyzerowanie banku.</dark_gray>",
                                canWithdrawAll
                                        ? "<yellow>Kliknij, aby wypłacić.</yellow>"
                                        : "<red>Nie masz uprawnień do wypłaty.</red>"),
                        false),
                (viewer, click) -> transferAll(viewer, false));
        menu.close(40, Ui.closeButton(miniMessage));
        menu.open(player);
    }

    private void transferAll(Player player, boolean deposit) {
        IslandView context = skyllia.islandOf(player.getUniqueId()).orElse(null);
        if (context == null) {
            player.closeInventory();
            player.sendMessage(Ui.component(miniMessage, "<red>Nie należysz już do wyspy.</red>"));
            return;
        }
        if (!deposit && (!context.canWithdraw()
                || !player.hasPermission(WITHDRAW_PERMISSION))) {
            player.sendActionBar(Ui.component(miniMessage,
                    "<red>Wypłacać może tylko właściciel lub współwłaściciel.</red>"));
            return;
        }
        transfer(player, 0L, deposit, true);
    }

    private void transfer(Player player, long amount, boolean deposit) {
        transfer(player, amount, deposit, false);
    }

    private void transfer(Player player, long amount, boolean deposit, boolean all) {
        IslandView context = skyllia.islandOf(player.getUniqueId()).orElse(null);
        if (context == null) {
            player.closeInventory();
            player.sendMessage(Ui.component(miniMessage, "<red>Nie należysz już do wyspy.</red>"));
            return;
        }
        if (!deposit && (!context.canWithdraw()
                || !player.hasPermission(WITHDRAW_PERMISSION))) {
            player.sendActionBar(Ui.component(miniMessage,
                    "<red>Wypłacać może tylko właściciel lub współwłaściciel.</red>"));
            return;
        }
        if (!outbox.isEconomyReady(player.getUniqueId())) {
            player.sendActionBar(Ui.component(miniMessage,
                    "<gray>Ekonomia odzyskuje poprzednią operację. Spróbuj za chwilę.</gray>"));
            return;
        }
        var acquired = coordinator.tryAcquire(player.getUniqueId(), false);
        if (acquired.isEmpty()) {
            player.sendActionBar(Ui.component(miniMessage, "<gray>Poprzednia operacja jeszcze trwa.</gray>"));
            return;
        }
        PlayerOperationCoordinator.Lease lease = acquired.get();
        IslandView verified = skyllia
                .islandOf(player.getUniqueId()).orElse(null);
        if (verified == null || !verified.islandId().equals(context.islandId())
                || (!deposit && (!verified.canWithdraw()
                || !player.hasPermission(WITHDRAW_PERMISSION)))) {
            coordinator.release(lease);
            player.sendActionBar(Ui.component(miniMessage,
                    "<red>Twoje członkostwo lub rola na wyspie właśnie się zmieniły.</red>"));
            return;
        }
        String id = "bank:" + (deposit ? "deposit:" : "withdraw:")
                + (all ? "all:" : "fixed:") + UUID.randomUUID();
        UUID playerId = player.getUniqueId();
        UUID islandId = verified.islandId();
        var future = all
                ? deposit
                ? ledger.transferAllToIsland(playerId, islandId, id)
                : ledger.transferAllFromIsland(islandId, playerId, id,
                        () -> authorizeWithdrawal(player, playerId, islandId))
                : deposit
                ? ledger.transferToIsland(playerId, islandId, amount, id)
                : ledger.transferFromIsland(islandId, playerId, amount, id,
                        () -> authorizeWithdrawal(player, playerId, islandId));
        future.whenComplete((result, failure) -> {
            try {
                var scheduled = player.getScheduler().run(plugin, task -> {
                    coordinator.release(lease);
                    if (failure != null || result == null) {
                        if (failure != null) {
                            plugin.getLogger().log(Level.WARNING,
                                    "Island bank transfer failed", failure);
                        }
                        player.sendMessage(Ui.component(miniMessage,
                                "<red>Nie udało się potwierdzić operacji bankowej. "
                                        + "Sprawdź saldo przed ponowieniem; historia transakcji "
                                        + "pozostaje źródłem prawdy.</red>"));
                    } else if (!result.applied()) {
                        player.sendActionBar(Ui.component(miniMessage,
                                all && result.insufficient()
                                        ? deposit
                                        ? "<gray>Twój portfel jest pusty.</gray>"
                                        : "<gray>Bank wyspy jest pusty.</gray>"
                                        : result.insufficient()
                                        ? "<red>Na koncie źródłowym brakuje monet.</red>"
                                        : "<red>Operacja została odrzucona.</red>"));
                    } else {
                        player.sendActionBar(Ui.component(miniMessage,
                                deposit
                                        ? "<green>Wpłacono <gold><amount></gold> "
                                                + "do banku wyspy.</green>"
                                        : "<green>Wypłacono <gold><amount></gold> "
                                                + "do portfela.</green>",
                                Placeholder.unparsed("amount",
                                        Ui.money(result.transferredAmount()))));
                    }
                    open(player);
                }, () -> coordinator.release(lease));
                if (scheduled == null) {
                    coordinator.release(lease);
                }
            } catch (RuntimeException rejected) {
                coordinator.release(lease);
                plugin.getLogger().log(java.util.logging.Level.WARNING, // SKYBLOCK-2-10: FINE czynił degradację niewidoczną
                        "Island bank result scheduling was rejected", rejected);
            }
        });
    }

    /**
     * Invoked only after the ledger operation reaches the head of both account
     * queues. Durable Skyllia role state is read off every tick thread, then
     * Bukkit permission state is read on this player's entity scheduler.
     */
    private @NotNull CompletableFuture<Boolean> authorizeWithdrawal(
            Player player, UUID playerId, UUID islandId) {
        CompletableFuture<Boolean> roleDecision = new CompletableFuture<>();
        try {
            var scheduled = plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> {
                try {
                    roleDecision.complete(
                            skyllia.authoritativeCanWithdraw(playerId, islandId));
                } catch (RuntimeException failure) {
                    plugin.getLogger().log(Level.WARNING,
                            "Authoritative island bank role check failed", failure);
                    roleDecision.complete(false);
                }
            });
            if (scheduled == null) {
                roleDecision.complete(false);
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, // SKYBLOCK-2-10: FINE czynił degradację niewidoczną
                    "Authoritative island bank role check was rejected", rejected);
            roleDecision.complete(false);
        }
        return roleDecision
                .completeOnTimeout(false, AUTHORIZATION_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS)
                .thenCompose(allowed -> allowed
                        ? permissionOnPlayerThread(player, playerId)
                        : CompletableFuture.completedFuture(false));
    }

    private @NotNull CompletableFuture<Boolean> permissionOnPlayerThread(
            Player player, UUID expectedPlayerId) {
        CompletableFuture<Boolean> permissionDecision = new CompletableFuture<>();
        try {
            var scheduled = player.getScheduler().run(plugin, ignored -> {
                try {
                    permissionDecision.complete(
                            player.isOnline()
                                    && expectedPlayerId.equals(player.getUniqueId())
                                    && player.hasPermission(WITHDRAW_PERMISSION));
                } catch (RuntimeException failure) {
                    plugin.getLogger().log(Level.WARNING,
                            "Final island bank permission check failed", failure);
                    permissionDecision.complete(false);
                }
            }, () -> permissionDecision.complete(false));
            if (scheduled == null) {
                permissionDecision.complete(false);
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, // SKYBLOCK-2-10: FINE czynił degradację niewidoczną
                    "Final island bank permission check was rejected", rejected);
            permissionDecision.complete(false);
        }
        return permissionDecision.completeOnTimeout(
                false, AUTHORIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private record BankBalances(long player, long island) { }
}
