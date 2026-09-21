package org.rafalohaki.wpmecore.addons.skyblock;

import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.economy.IslandBankMenu;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;

import org.rafalohaki.wpmecore.addons.skyblock.hub.WandService;

import org.rafalohaki.wpmecore.addons.skyblock.quests.DailyQuestService;

import org.rafalohaki.wpmecore.addons.skyblock.automation.ChunkerService;
import org.rafalohaki.wpmecore.addons.skyblock.automation.SellChestService;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandCenterMenu;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandCreationMenus;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandGuideMenu;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandCreationCoordinator;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockMilestoneMenu;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;

/** One predictable entry point for the whole SkyBlock loop. */
final class SkyBlockMenus {

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage miniMessage;
    private final LedgerService ledger;
    private final SkyBlockSettings settings;
    private final DailyQuestService quests;
    private final IslandBankMenu bank;
    private final WandService wands;
    private final InventoryOutbox outbox;
    private final ChunkerService chunkerService;
    private final SellChestService sellChestService;
    private final OneBlockMilestoneMenu oneBlockMilestones;
    private final IslandCenterMenu islandCenter;
    private final IslandCreationMenus creationMenus;
    private final IslandGuideMenu guideMenu;
    private final SkylliaIntegration skyllia;
    private final IslandCreationCoordinator coordinator;

    SkyBlockMenus(@NotNull JavaPlugin plugin, @NotNull MenuService menus,
                  @NotNull MiniMessage miniMessage, @NotNull LedgerService ledger,
                  @NotNull SkyBlockSettings settings,
                  @NotNull DailyQuestService quests, @NotNull IslandBankMenu bank,
                  @NotNull WandService wands,
                  @NotNull InventoryOutbox outbox,
                  @NotNull ChunkerService chunkerService,
                  @NotNull SellChestService sellChestService,
                  @NotNull OneBlockMilestoneMenu oneBlockMilestones,
                  @NotNull IslandCenterMenu islandCenter,
                  @NotNull IslandCreationMenus creationMenus,
                  @NotNull IslandGuideMenu guideMenu,
                  @NotNull SkylliaIntegration skyllia,
                  @NotNull IslandCreationCoordinator coordinator) {
        this.plugin = plugin;
        this.menus = menus;
        this.miniMessage = miniMessage;
        this.ledger = ledger;
        this.settings = settings;
        this.quests = quests;
        this.bank = bank;
        this.wands = wands;
        this.outbox = outbox;
        this.chunkerService = chunkerService;
        this.sellChestService = sellChestService;
        this.oneBlockMilestones = oneBlockMilestones;
        this.islandCenter = islandCenter;
        this.creationMenus = creationMenus;
        this.guideMenu = guideMenu;
        this.skyllia = skyllia;
        this.coordinator = coordinator;
    }

    void open(@NotNull Player player) {
        MenuService.Menu menu = menus.ofRows(5,
                Ui.component(miniMessage, settings.menuTitle()));
        Ui.frame(menu, miniMessage, Material.CYAN_STAINED_GLASS_PANE);
        menu.set(11, Ui.item(Material.EMERALD, miniMessage,
                        "<green><bold>Sklep serwerowy</bold></green>",
                        List.of("<gray>Tu zamieniasz wykopane surowce na monety</gray>",
                                "<gray>i kupujesz sprzęt. Ceny są dla wszystkich takie same.</gray>",
                                "<dark_gray> </dark_gray>",
                                "<yellow>Kliknij, aby otworzyć.</yellow>"), true),
                (viewer, click) -> {
                    // Uczciwy stan: sklep dostarcza zewnętrzna wtyczka (zShop).
                    // Gdy komenda nie jest zarejestrowana, klik nie może zniknąć
                    // w ciszy — gracz dostaje to samo zdanie co kafle external-actions.
                    if (!viewer.performCommand("sklep")) {
                        viewer.sendMessage(Ui.component(miniMessage,
                                "<red>Ta część gry jest chwilowo wyłączona.</red>"));
                    }
                });
        menu.set(13, Ui.item(Material.WRITABLE_BOOK, miniMessage,
                        "<aqua><bold>Dzienne zadania wyspy</bold></aqua>",
                        List.of("<gray>Trzy cele na dziś, wspólne dla całej wyspy.</gray>",
                                "<gray>Nagroda trafia do banku wyspy.</gray>",
                                "<gray>Dzień przerwy niczego nie kasuje.</gray>",
                                // F24: 15 zadań w tygodniu = Złoty Lotos = tor
                                // premium karnetu. Ten łańcuch istniał w kodzie
                                // (DailyQuestService.WEEKLY_MILESTONE_GOAL), ale
                                // nigdzie nie było o nim słowa przed kliknięciem.
                                "<gray>15 zadań w tygodniu to Złoty Lotos —</gray>",
                                "<gray>klucz do toru premium karnetu sezonowego.</gray>",
                                "<dark_gray> </dark_gray>",
                                "<yellow>Kliknij, aby otworzyć.</yellow>"), true),
                (viewer, click) -> quests.open(viewer));
        menu.set(15, Ui.item(Material.ENDER_CHEST, miniMessage,
                        "<gold><bold>Bank wyspy</bold></gold>",
                        List.of("<gray>Wspólna kasa całej wyspy.</gray>",
                                "<gray>Przekładasz tu monety ze swojej kieszeni.</gray>",
                                "<dark_gray> </dark_gray>",
                                "<yellow>Kliknij, aby otworzyć.</yellow>"), true),
                (viewer, click) -> bank.open(viewer));
        menu.set(22, Ui.item(Material.HOPPER, miniMessage,
                        "<gold><bold>Automatyzacja wyspy</bold></gold>",
                        List.of("<gray>Maszyny, które zbierają i sprzedają za Ciebie.</gray>",
                                "<gray>Przydają się, gdy masz już z czego kupić.</gray>",
                                "<dark_gray> </dark_gray>",
                                "<yellow>Kliknij, aby otworzyć.</yellow>"), true),
                (viewer, click) -> openAutomationMenu(viewer));
        menu.set(31, Ui.item(Material.BLAZE_ROD, miniMessage,
                        "<light_purple><bold>Magiczne narzędzia</bold></light_purple>",
                        List.of("<gray>Różdżka, która sprzedaje zawartość skrzyni,</gray>",
                                "<gray>i taka, która sama obsiewa pole.</gray>",
                                "<gray>Każda ma z góry podaną liczbę użyć.</gray>",
                                "<dark_gray> </dark_gray>",
                                "<yellow>Kliknij, aby otworzyć.</yellow>"), true),
                (viewer, click) -> wands.open(viewer));
        menu.set(33, Ui.item(Material.AMETHYST_SHARD, miniMessage,
                        "<light_purple><bold>Nagrody OneBlock</bold></light_purple>",
                        List.of("<gray>Skrzynie odkładane co któreś rozbicie bloku.</gray>",
                                "<gray>Tylko dla wysp w trybie OneBlock.</gray>",
                                "<dark_gray> </dark_gray>",
                                "<yellow>Kliknij, aby otworzyć.</yellow>"), true),
                (viewer, click) -> oneBlockMilestones.openFor(viewer));
        // F24: lista urywała się na „weź zadanie dnia”, czyli dokładnie tam, gdzie
        // zaczyna się reszta gry. Cztery kroki domykają łańcuch aż do nagrody, bo
        // to jest odpowiedź na „nie wiedział o co chodzi”: kopanie ma cel, cel ma
        // nagrodę, a nagroda ma następny cel.
        menu.decoration(35, Ui.item(Material.COMPASS, miniMessage,
                "<yellow><bold>Co robić dalej?</bold></yellow>",
                List.of("<white>1.</white> <gray>Załóż wyspę — komenda <white>/is</white>.</gray>",
                        "<white>2.</white> <gray>Zrób generator bruku z lawy i wody</gray>",
                        "<gray>   i wykop trochę kamienia.</gray>",
                        "<white>3.</white> <gray>Sprzedaj go w sklepie i weź zadanie dnia.</gray>",
                        "<white>4.</white> <gray>Zadania <white>sezonowe</white> zrobisz raz — potem</gray>",
                        "<gray>   punkty sezonu daje <white>łowienie ryb</white>.</gray>",
                        "<dark_gray> </dark_gray>",
                        "<dark_gray>To okno wraca komendą /menu.</dark_gray>"), false));
        menu.decoration(4, Ui.item(Material.GOLD_INGOT, miniMessage,
                "<gold>Portfel: </gold>" + Ui.price(ledger.playerBalance(player.getUniqueId())),
                List.of("<gray>Saldo jest zapisane w transakcyjnym ledgerze.</gray>"), true));

        for (SkyBlockSettings.ExternalAction action : settings.externalActions()) {
            addExternalAction(menu, action);
        }
        menu.set(30, Ui.item(Material.GRASS_BLOCK, miniMessage,
                        "<green><bold>Centrum Wyspy</bold></green>",
                        List.of("<gray>Ustawienia Twojej wyspy: kogo zapraszasz,</gray>",
                                "<gray>kto może wejść, wygląd wyspy, punkty podróży.</gray>",
                                "<yellow>Kliknij, aby otworzyć.</yellow>"), true),
                (viewer, click) -> {
                    if (skyllia.islandOf(viewer.getUniqueId()).isPresent()) islandCenter.open(viewer);
                    else creationMenus.openPicker(viewer);
                });
        menu.close(40, Ui.closeButton(miniMessage));
        menu.open(player);
    }

    /**
     * F19: pierwsze wejście. Zakładamy zerową wiedzę o Minecrafcie, więc
     * powiedziane jest DOKŁADNIE JEDNO zadanie („załóż wyspę”) i sposób, w jaki
     * się je wykonuje (klawisz T, ukośnik, Enter). Wcześniejsza wersja odsyłała
     * do „pięciu przewodników na placu” — przewodników jest siedem, stoją
     * kilkadziesiąt kratek od miejsca wejścia i nic nie mówiło, że rozmawia się
     * z nimi prawym przyciskiem myszy.
     */
    void onboarding(@NotNull Player player, boolean openMenu) {
        player.showTitle(Title.title(
                Ui.component(miniMessage, "<gold><bold>SKYBLOCK</bold></gold>"),
                Ui.component(miniMessage,
                        "<gray>Zacznij od komendy <yellow>/is</yellow></gray>"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4),
                        Duration.ofMillis(700))));
        player.sendMessage(Ui.component(miniMessage,
                "<gold><bold>Witaj na SkyBlocku!</bold></gold> "
                        + "<gray>Grasz na własnej wyspie wiszącej w powietrzu — "
                        + "najpierw trzeba ją założyć.</gray>"));
        player.sendMessage(Ui.component(miniMessage,
                "<white>Zrób to tak:</white> <gray>naciśnij klawisz <white>T</white>, "
                        + "wpisz <yellow>/is</yellow> i naciśnij <white>Enter</white>. "
                        + "Otworzy się okno wyboru — kliknij tryb, potem zielony przycisk.</gray>"));
        player.sendMessage(Ui.component(miniMessage,
                "<gray>Postacie stojące na placu robią to samo co komendy — "
                        + "podejdź i kliknij taką postać prawym przyciskiem myszy.</gray>"));
        player.sendMessage(Ui.component(miniMessage,
                "<dark_gray>Zgubisz się: <yellow>/is help</yellow>. Wszystkie okna gry: "
                        + "<yellow>/menu</yellow>.</dark_gray>"));
        if (openMenu) {
            open(player);
        }
    }

    private void addExternalAction(MenuService.Menu menu,
                                   SkyBlockSettings.ExternalAction action) {
        boolean available = action.requiredPlugin().isEmpty();
        if (!available) {
            Plugin required = plugin.getServer().getPluginManager()
                    .getPlugin(action.requiredPlugin());
            available = required != null && required.isEnabled();
        }
        List<String> lore = new ArrayList<>(action.lore());
        if (!available) {
            lore.add("<dark_gray> </dark_gray>");
            lore.add("<red>Ta część gry jest chwilowo wyłączona.</red>");
            menu.decoration(action.slot(), Ui.item(Material.GRAY_DYE, miniMessage,
                    action.name(), lore, false));
            return;
        }
        lore.add("<dark_gray> </dark_gray>");
        lore.add("<yellow>Kliknij, aby przejść.</yellow>");
        menu.set(action.slot(), Ui.item(action.material(), miniMessage,
                        action.name(), lore, false),
                (viewer, click) -> {
                    viewer.closeInventory();
                    if (!viewer.performCommand(action.command())) {
                        viewer.sendMessage(Ui.component(miniMessage,
                                "<red>Ta funkcja jest chwilowo niedostępna.</red>"));
                    }
                });
    }

        void openIslandCreationPicker(@NotNull Player player) {
        creationMenus.openPicker(player);
    }

    void openIslandCenter(@NotNull Player player) {
        islandCenter.open(player);
    }

    void openIslandGuide(@NotNull Player player) {
        guideMenu.open(player);
    }

    void openAutomationMenu(@NotNull Player player) {
        MenuService.Menu menu = menus.ofRows(3,
                Ui.component(miniMessage, "<gold><bold>Automatyzacja wyspy</bold></gold>"));
        Ui.frame(menu, miniMessage, Material.ORANGE_STAINED_GLASS_PANE);
        menu.decoration(4, Ui.item(Material.GOLD_INGOT, miniMessage,
                "<gold>Portfel: </gold>" + Ui.price(ledger.playerBalance(player.getUniqueId())),
                List.of(), true));

        List<String> chunkerLore = List.of(
                "<gray>Zbiera przedmioty leżące na ziemi w okolicy</gray>",
                "<gray>i wkłada je do skrzyni obok.</gray>",
                "<dark_gray>Postaw go na skrzyni albo tuż obok niej.</dark_gray>",
                "<dark_gray> </dark_gray>",
                "<gray>Cena: </gray>" + Ui.price(settings.automation().chunkerPrice()),
                "<yellow>Kliknij, aby kupić.</yellow>"
        );
        menu.set(11, Ui.item(Material.HOPPER, miniMessage,
                        "<gold><bold>Lej Chunkowy (Chunker)</bold></gold>",
                        chunkerLore, true),
                (viewer, click) -> buyAutomationItem(viewer, "chunker",
                        settings.automation().chunkerPrice(),
                        chunkerService.createChunkerItem(1),
                        "<gold>Lej Chunkowy</gold>"));

        List<String> guideLore = List.of(
                "<white>1.</white> <gray>Lej zbiera z ziemi wszystko, co wypadło w pobliżu.</gray>",
                "<white>2.</white> <gray>Skrzynia co 20 sekund sprzedaje to, co w niej leży.</gray>",
                "<white>3.</white> <gray>Monety idą prosto do banku Twojej wyspy.</gray>",
                "<dark_gray> </dark_gray>",
                "<dark_gray>Razem: zbierasz i zarabiasz, nie stojąc przy tym.</dark_gray>"
        );
        menu.decoration(13, Ui.item(Material.REDSTONE_TORCH, miniMessage,
                "<yellow><bold>Jak działa automatyzacja?</bold></yellow>",
                guideLore, false));

        List<String> sellChestLore = List.of(
                "<gray>Co 20 sekund sprzedaje to, co w niej leży,</gray>",
                "<gray>po cenach ze sklepu <white>/sklep</white>.</gray>",
                "<gray>Monety trafiają do banku wyspy.</gray>",
                "<dark_gray>Postaw ją gdziekolwiek na swojej wyspie.</dark_gray>",
                "<dark_gray> </dark_gray>",
                "<gray>Cena: </gray>" + Ui.price(settings.automation().sellChestPrice()),
                "<yellow>Kliknij, aby kupić.</yellow>"
        );
        menu.set(15, Ui.item(Material.CHEST, miniMessage,
                        "<gold><bold>Skrzynia Autosprzedaży (Sell Chest)</bold></gold>",
                        sellChestLore, true),
                (viewer, click) -> buyAutomationItem(viewer, "sell_chest",
                        settings.automation().sellChestPrice(),
                        sellChestService.createSellChestItem(1),
                        "<gold>Skrzynia Autosprzedaży</gold>"));

        menu.set(22, Ui.backButton(miniMessage),
                (viewer, click) -> open(viewer));
        menu.open(player);
    }

    private void buyAutomationItem(@NotNull Player player,
                                   @NotNull String type,
                                   long price,
                                   @NotNull org.bukkit.inventory.ItemStack item,
                                   @NotNull String displayName) {
        if (player.getInventory().firstEmpty() < 0) {
            player.sendActionBar(Ui.component(miniMessage,
                    "<red>Zwolnij jedno miejsce w ekwipunku.</red>"));
            return;
        }
        String transaction = "automation:buy:" + type + ":" + java.util.UUID.randomUUID();
        outbox.beginGrant(player, -price, transaction, "automation_buy", item, outcome -> {
            switch (outcome) {
                case SUCCESS -> player.sendActionBar(Ui.component(miniMessage,
                        "<green>Kupiono <item> za <gold><price></gold>.</green>",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.component("item",
                                Ui.component(miniMessage, displayName)),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("price",
                                Ui.money(price))));
                case INSUFFICIENT -> player.sendActionBar(Ui.component(miniMessage,
                        "<red>Nie masz wystarczającej liczby monet w portfelu.</red>"));
                case BUSY -> player.sendActionBar(Ui.component(miniMessage,
                        "<gray>Poprzednia operacja jeszcze trwa.</gray>"));
                case DEFERRED -> player.sendMessage(Ui.component(miniMessage,
                        "<yellow>Zakup zapisano. Zwolnij miejsce i wejdź ponownie, aby odebrać przedmiot.</yellow>"));
                case REJECTED, ERROR -> player.sendMessage(Ui.component(miniMessage,
                        "<red>Zakup nie został rozpoczęty. Nic nie pobrano.</red>"));
            }
            if (player.isOnline()) {
                openAutomationMenu(player);
            }
        });
    }
}
