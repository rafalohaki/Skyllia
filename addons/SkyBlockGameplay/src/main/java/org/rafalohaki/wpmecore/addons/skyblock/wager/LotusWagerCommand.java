package org.rafalohaki.wpmecore.addons.skyblock.wager;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Inventories;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * ECO-13: Zakład Lotosowy ({@code /zaklad}) — „podwój albo nic" na Srebrne
 * Lotosy. Komenda czatowa z jednoklikowym potwierdzeniem ({@code /zaklad
 * potwierdz} przez clickEvent), bo akcja jest bezzwrotna.
 *
 * <p>Flow stawki idzie przez outbox (ten sam kontrakt co składniki kuźni):
 * deterministyczny operationId na dobę, więc powtórka po awarii nie zabiera
 * SL drugi raz. Dzień gry zapisujemy w PDC gracza dopiero w callbacku
 * udanego zabrania stawki — awaria przed rzutem kosztuje dom, nie gracza.
 * Rzut leci 2 ticki po zabraniu stawki, bo callback outboxu domyka się jeszcze
 * w leasingu operacji — natychmiastowe zagnieżdżone nadanie wygranej dostałoby
 * BUSY (patrz saga w ForgeService). Każde wyjście ze stanu „stawka zabrana"
 * kończy się rzutem: gdy gracz zniknie w oknie albo scheduler odrzuci zadanie,
 * wynik rozstrzyga się offline — wygrana trafia do trwałego nadania
 * ({@code beginOfflineGrant}, ten sam deterministyczny operationId jak normalna
 * wygrana), a przegrana czysto wygasa. Stawka nigdy nie przepada bez losowania.
 */
public final class LotusWagerCommand {

    /** Próby doręczenia offline wygranej: pierwsza + ponowienia co 5 s. */
    private static final int OFFLINE_RETRY_LIMIT = 4;
    private static final long OFFLINE_RETRY_DELAY_TICKS = 100L;

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage;
    private final InventoryOutbox outbox;
    private final LotusWager wager;
    private final CustomItemService customItems;

    public LotusWagerCommand(@NotNull JavaPlugin plugin, @NotNull MiniMessage miniMessage,
                             @NotNull InventoryOutbox outbox, @NotNull LotusWager wager,
                             @NotNull CustomItemService customItems) {
        this.plugin = plugin;
        this.miniMessage = miniMessage;
        this.outbox = outbox;
        this.wager = wager;
        this.customItems = customItems;
    }

    public @NotNull LotusWager wager() {
        return wager;
    }

    /** {@code /zaklad} — zasady, stan gracza i klik do potwierdzenia. */
    public void info(@NotNull Player player) {
        int saldo = countSilverLotus(player);
        LocalDate today = LotusWager.today();
        player.sendMessage(Ui.component(miniMessage,
                "<gold><bold>Zakład Lotosowy</bold></gold> <gray>— postaw <white>" + LotusWager.STAKE
                        + " Srebrne Lotosy</white>: połowa szans na <white>" + LotusWager.PAYOUT
                        + "</white>, połowa na stratę.</gray>"));
        player.sendMessage(Ui.component(miniMessage,
                "<gray>Limit: <white>1 próba na dobę</white>, gra zamknięta powyżej <white>"
                        + LotusWager.MAX_SALDO + " SL</white> przy sobie. Masz: <white>" + saldo
                        + " SL</white>.</gray>"));
        String verdict;
        if (wager.playedOn(player.getPersistentDataContainer(), today)) {
            verdict = "<red>Zagrałeś już dziś — wróć jutro.</red>";
        } else if (LotusWager.saldoBlocksWager(saldo)) {
            verdict = "<red>Masz przy sobie za dużo SL (limit " + LotusWager.MAX_SALDO
                    + ") — dom nie gra z tym, kto nie potrzebuje.</red>";
        } else if (LotusWager.missingStake(saldo)) {
            verdict = "<red>Za mało Srebrnych Lotosów na wpisowe (potrzeba " + LotusWager.STAKE + ").</red>";
        } else {
            verdict = "<green>Kliknij, aby zagrać:</green> <yellow><bold><click:run_command:'/zaklad potwierdz'>"
                    + "[POSTAW " + LotusWager.STAKE + " SL]</click></bold></yellow>";
        }
        player.sendMessage(Ui.component(miniMessage, verdict));
    }

    /** {@code /zaklad potwierdz} — ostateczne sprawdzenie i zabranie stawki. */
    public void confirm(@NotNull Player player) {
        int saldo = countSilverLotus(player);
        LocalDate today = LotusWager.today();
        if (wager.playedOn(player.getPersistentDataContainer(), today)) {
            send(player, "<red>Zagrałeś już dziś — wróć jutro.</red>");
            return;
        }
        if (LotusWager.saldoBlocksWager(saldo)) {
            send(player, "<red>Za dużo SL przy sobie (limit " + LotusWager.MAX_SALDO + ").</red>");
            return;
        }
        if (LotusWager.missingStake(saldo)) {
            send(player, "<red>Za mało Srebrnych Lotosów — potrzeba " + LotusWager.STAKE + ".</red>");
            return;
        }
        if (!outbox.isEconomyReady(player.getUniqueId())) {
            send(player, "<gray>Poprzednia operacja jeszcze trwa.</gray>");
            return;
        }
        String operationId = "zaklad:stake:" + player.getUniqueId() + ":" + today;
        outbox.beginRemoval(player, 0L, operationId, "zaklad_stake",
                Map.of(), Map.of(LotusWager.SILVER_LOTUS_ID, LotusWager.STAKE),
                null, outcome -> onStakeTaken(player, today, outcome));
    }

    private void onStakeTaken(@NotNull Player player, @NotNull LocalDate today,
                              @NotNull InventoryOutbox.Outcome outcome) {
        switch (outcome) {
            case SUCCESS, DEFERRED -> {
                // Dzień blokujemy dopiero po trwałym zabraniu stawki.
                wager.markPlayed(player.getPersistentDataContainer(), today);
                send(player, "<gold>Lotosy postawione — losowanie...</gold>");
                scheduleRoll(player, today);
            }
            case INSUFFICIENT -> send(player, "<red>Za mało Srebrnych Lotosów.</red>");
            case BUSY -> send(player, "<gray>Poprzednia operacja jeszcze trwa.</gray>");
            case REJECTED, ERROR -> send(player,
                    "<red>Zakład nie doszedł do skutku — nic nie zostało pobrane.</red>");
        }
    }

    /**
     * Rzut 2 ticki po zabraniu stawki: callback outboxu domyka się jeszcze
     * w leasingu operacji (beginMutation woła complete przed release), więc
     * natychmiastowe zagnieżdżone nadanie wygranej dostałoby BUSY. Okno jest
     * zabezpieczone: wycofanie encji albo odrzucenie schedulera rozstrzyga
     * rzut offline — stawka nie może przepaść bez losowania.
     */
    private void scheduleRoll(@NotNull Player player, @NotNull LocalDate today) {
        try {
            var scheduled = player.getScheduler().runDelayed(plugin,
                    task -> roll(player, today),
                    () -> resolveOffline(player.getUniqueId(), today), 2L);
            if (scheduled == null) {
                resolveOffline(player.getUniqueId(), today);
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.WARNING,
                    "Zakład: scheduler odrzucił losowanie dla " + player.getUniqueId()
                            + " — rozstrzygam offline", rejected);
            resolveOffline(player.getUniqueId(), today);
        }
    }

    private void roll(@NotNull Player player, @NotNull LocalDate today) {
        if (!player.isOnline() || !plugin.isEnabled()) {
            resolveOffline(player.getUniqueId(), today);
            return;
        }
        boolean win = ThreadLocalRandom.current().nextBoolean();
        if (!win) {
            send(player, "<red>Przegrałeś " + LotusWager.STAKE
                    + " Srebrne Lotosy. Szczęścia jutro — na drugi rzut masz jedno podejście.</red>");
            return;
        }
        ItemStack payout = customItems.create(LotusWager.SILVER_LOTUS_ID).orElse(null);
        if (payout == null) {
            plugin.getLogger().severe("Zakład: brak przedmiotu " + LotusWager.SILVER_LOTUS_ID
                    + " — wygrana dla " + player.getUniqueId() + " niewydana");
            return;
        }
        payout.setAmount(LotusWager.PAYOUT);
        outbox.beginGrant(player, 0L, LotusWager.grantOperationId(player.getUniqueId(), today),
                "zaklad_win", payout, grantOutcome -> {
                    if (grantOutcome == InventoryOutbox.Outcome.SUCCESS
                            || grantOutcome == InventoryOutbox.Outcome.DEFERRED) {
                        send(player, "<gold><bold>WYGRANA!</bold></gold> <green>Dostajesz <white>"
                                + LotusWager.PAYOUT + " Srebrnych Lotosów</white>.</green>");
                    } else {
                        plugin.getLogger().warning("Zakład: wygrana " + grantOutcome + " dla "
                                + player.getUniqueId() + " — do ręcznego wydania (operationId "
                                + LotusWager.grantOperationId(player.getUniqueId(), today) + ")");
                        send(player, "<red>Wygrana jest zapisana — wejdź ponownie, aby ją odebrać.</red>");
                    }
                });
    }

    /**
     * Rzut dla gracza, który zniknął między zabraniem stawki a losowaniem.
     * Losowanie dzieje się dokładnie raz; przy wygranej dostawa idzie trwałym
     * {@code beginOfflineGrant} z tym samym deterministycznym operationId co
     * normalna wygrana — odbiór przy najbliższym wejściu, idempotentnie.
     * Strażnik mutacji potrafi odrzucić nadanie w trakcie przenosin, więc
     * doręczenie ponawia kilka razy (wynik już ustalony — ponawiane jest
     * wyłącznie wydanie, nie rzut).
     */
    private void resolveOffline(@NotNull UUID playerId, @NotNull LocalDate today) {
        boolean win = ThreadLocalRandom.current().nextBoolean();
        plugin.getLogger().info("Zakład: gracz " + playerId + " zniknął po zabraniu stawki — "
                + "losowanie offline (dzień " + today + "): " + (win ? "wygrana" : "przegrana"));
        if (win) {
            deliverOfflineWin(playerId, today, 0);
        }
    }

    private void deliverOfflineWin(@NotNull UUID playerId, @NotNull LocalDate today, int attempt) {
        ItemStack payout = customItems.create(LotusWager.SILVER_LOTUS_ID).orElse(null);
        if (payout == null) {
            plugin.getLogger().severe("Zakład: brak przedmiotu " + LotusWager.SILVER_LOTUS_ID
                    + " — offline wygrana dla " + playerId + " niewydana");
            return;
        }
        payout.setAmount(LotusWager.PAYOUT);
        String operationId = LotusWager.grantOperationId(playerId, today);
        outbox.beginOfflineGrant(playerId, 0L, operationId, "zaklad_win", payout, outcome -> {
            if (outcome == InventoryOutbox.Outcome.SUCCESS
                    || outcome == InventoryOutbox.Outcome.DEFERRED) {
                plugin.getLogger().info("Zakład: offline wygrana dla " + playerId
                        + " zakolejkowana (operationId " + operationId + ")");
                return;
            }
            if (attempt + 1 < OFFLINE_RETRY_LIMIT) {
                try {
                    plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin,
                            task -> deliverOfflineWin(playerId, today, attempt + 1),
                            OFFLINE_RETRY_DELAY_TICKS);
                    return;
                } catch (RuntimeException rejected) {
                    plugin.getLogger().log(Level.WARNING,
                            "Zakład: ponowienie offline wygranej odrzucone dla " + playerId,
                            rejected);
                }
            }
            plugin.getLogger().warning("Zakład: offline wygrana " + outcome + " dla "
                    + playerId + " — do ręcznego wydania (operationId " + operationId + ")");
        });
    }

    private int countSilverLotus(@NotNull Player player) {
        return Inventories.countCustom(player.getInventory(),
                LotusWager.SILVER_LOTUS_ID, customItems);
    }

    private void send(@NotNull Player player, @NotNull String message) {
        player.sendMessage(Ui.component(miniMessage, message));
    }
}
