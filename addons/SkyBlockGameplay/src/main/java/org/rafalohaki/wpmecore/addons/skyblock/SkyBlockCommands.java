package org.rafalohaki.wpmecore.addons.skyblock;

import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.economy.IslandBankMenu;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.economy.PlayerOperationCoordinator;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockVaultProvider;
import org.rafalohaki.wpmecore.addons.skyblock.economy.VaultEconomyHook;
import org.rafalohaki.wpmecore.addons.skyblock.hub.HubPedestal;
import org.rafalohaki.wpmecore.addons.skyblock.hub.SkyBlockHub;
import org.rafalohaki.wpmecore.addons.skyblock.hub.SkyBlockPresentationPublisher;
import org.rafalohaki.wpmecore.addons.skyblock.hub.WandService;
import org.rafalohaki.wpmecore.addons.skyblock.season.CosmeticCatalog;
import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonCommand;
import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonCloseReport;
import org.rafalohaki.wpmecore.addons.skyblock.season.CosmeticService;
import org.rafalohaki.wpmecore.addons.skyblock.season.LeaderboardDao;
import org.rafalohaki.wpmecore.addons.skyblock.season.LeaderboardService;
import org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockTopRewardCoordinator;
import org.rafalohaki.wpmecore.addons.skyblock.quests.DailyQuestService;
import org.rafalohaki.wpmecore.addons.skyblock.forge.ForgeConfig;
import org.rafalohaki.wpmecore.addons.skyblock.forge.ForgeMenu;
import org.rafalohaki.wpmecore.addons.skyblock.forge.ForgeService;
import org.rafalohaki.wpmecore.addons.skyblock.automation.ChunkerDao;
import org.rafalohaki.wpmecore.addons.skyblock.automation.ChunkerListener;
import org.rafalohaki.wpmecore.addons.skyblock.automation.ChunkerService;
import org.rafalohaki.wpmecore.addons.skyblock.automation.CobblestoneGeneratorListener;
import org.rafalohaki.wpmecore.addons.skyblock.automation.SellChestDao;
import org.rafalohaki.wpmecore.addons.skyblock.automation.SellChestListener;
import org.rafalohaki.wpmecore.addons.skyblock.automation.SellChestService;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionDao;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionDisplayRenderer;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionItem;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionListener;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionMenu;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionService;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionsConfig;
import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockContent;
import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockContentLoader;
import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockDao;
import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockMilestoneDao;
import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockMilestoneMenu;
import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockService;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandCapabilities;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandFluidInitializer;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandLifecycleGuard;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaBootstrap;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaCommands;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.rafalohaki.wpmecore.addons.skyblock.shop.ShopCatalog;
import org.rafalohaki.wpmecore.addons.skyblock.shared.ItemNames;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.api.item.CustomItem;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.WpmeAPI;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.api.service.SchedulerService;
import org.rafalohaki.wpmecore.api.util.AddonBootstrap;
import org.rafalohaki.wpmecore.api.util.ConfigUpdater;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Powierzchnia komend dodatku.
 *
 * <p>Wyjęta z klasy wtyczki, która pełniła cztery role naraz: korzeń
 * kompozycji, listener zdarzeń, rejestr komend i zestaw akcesorów. Same
 * komendy to było 394 linie z 954.
 *
 * <p>Klasa leży w tym samym pakiecie co wtyczka i sięga po jej pola
 * bezpośrednio. To celowe: alternatywą był konstruktor o szesnastu
 * argumentach albo upublicznienie szesnastu akcesorów. Współpraca po
 * package-private zostaje wewnątrz jednego pakietu — dokładnie tam,
 * gdzie ma zostać.
 */
final class SkyBlockCommands {

    /**
     * Audyt pierwszej godziny 2026-09-05 (P1-1): manifest obiecywał /pomoc, a gracz
     * dostawał „Unknown command”. Kolejność = kolejność celów progresywnych.
     * Ikony U+E0xx = czcionka {@code wpme:icons} z paczki klienta; MiniMessage
     * wymaga tagu {@code <font:>} — paczka NIE nadpisuje fontu default, więc
     * surowy znak PUA renderuje się jako puste miejsce.
     */
    static final String POMOC = String.join("<newline>",
            "<gold><bold>Pomoc SkyBlock</bold></gold> <dark_gray>— najważniejsze komendy</dark_gray>",
            "<font:wpme:icons>\uE001</font> <yellow>/is</yellow> <gray>— wyspa: założenie, teleport, członkowie (<white>/is help</white>)</gray>",
            "<font:wpme:icons>\uE005</font> <yellow>/spawn</yellow> <gray>— powrót na plac</gray>",
            "<font:wpme:icons>\uE018</font> <yellow>/menu</yellow> <gray>— wszystkie okna gry w jednym miejscu</gray>",
            "<font:wpme:icons>\uE002</font> <yellow>/sklep</yellow> <gray>— sprzedaż surowców i zakupy</gray>",
            "<font:wpme:icons>\uE017</font> <yellow>/ah</yellow> <gray>— dom aukcyjny: handel z innymi graczami</gray>",
            "<font:wpme:icons>\uE00D</font> <yellow>/zadania</yellow> <gray>— dzienne zadania wyspy z nagrodami</gray>",
            "<font:wpme:icons>\uE011</font> <yellow>/nagroda</yellow> <gray>— codzienna nagroda i seria logowań</gray>",
            "<font:wpme:icons>\uE00E</font> <yellow>/sezon</yellow> <gray>— punkty sezonowe, questy i TOP-10</gray>",
            "<font:wpme:icons>\uE011</font> <yellow>/przepustka</yellow> <gray>— karnet sezonowy i nagrody</gray>",
            "<font:wpme:icons>\uE00A</font> <yellow>/kuznia</yellow> <gray>— kuźnia: talizmany, ulepszenia i pety za monety</gray>",
            "<font:wpme:icons>\uE00D</font> <yellow>/pets</yellow> <gray>— twoje pety: aktywacja i odbiór surowców</gray>",
            "<font:wpme:icons>\uE010</font> <yellow>/bank</yellow> <gray>— wspólny bank wyspy</gray>",
            "<font:wpme:icons>\uE009</font> <yellow>/narzedzia</yellow> <gray>— sklep magicznych narzędzi</gray>",
            "<font:wpme:icons>\uE00A</font> <yellow>/craft</yellow> <gray>— stół rzemieślniczy; <white>/enderchest</white> — skrzynia kresu</gray>",
            "<font:wpme:icons>\uE004</font> <yellow>/latanie</yellow> <gray>— lot na własnej wyspie (perk rangi)</gray>",
            "<font:wpme:icons>\uE007</font> <yellow>/gierki</yellow> <gray>— centrum gier: ruletka i skrzynki (tytuły, bez strat)</gray>",
            "<font:wpme:icons>\uE01A</font> <yellow>/emf shop</yellow> <gray>— skup ryb; konkurs wędkarski co 2 h, wędka u rybaka Barnaby</gray>",
            "<font:wpme:icons>\uE019</font> <yellow>/wyspa oneblock info</yellow> <gray>— faza i kamienie milowe; <white>/wyspa oneblock nagrody</white> — odbiór</gray>",
            "<dark_gray>Bieżący cel widzisz na tablicy po prawej stronie ekranu.</dark_gray>");

    private final SkyBlockGameplay plugin;

    SkyBlockCommands(@NotNull SkyBlockGameplay plugin) {
        this.plugin = plugin;
    }
    void register() {
        AddonBootstrap.registerCommands(plugin, registrar -> {
            registrar.register(Commands.literal("spawn")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.use"))
                    .executes(context -> execute(context.getSource().getSender(),
                            this::teleportToGlobalSpawn)).build(),
                    "Teleportuje na globalny spawn SkyBlock", List.of());
            registrar.register(Commands.literal("menu")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.use"))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> { try { if (plugin.skyllia() != null && plugin.skyllia().islandOf(player.getUniqueId()).isPresent() && plugin.islandCenter() != null) plugin.islandCenter().open(player); else plugin.mainMenu().open(player); } catch (Exception e) { plugin.mainMenu().open(player); } })).build(),
                    "Otwiera główne menu SkyBlock", List.of("sbmenu", "skyblockmenu", "sb"));
            // Literał `latanie`, nie `fly`: na każdym backendzie z AdminTools
            // (SkyBlock, anarchia) `/fly` należy do narzędzi administracji
            // (węzeł `admintools.fly`) i przesłania rejestrację perką —
            // gracz z `skyblockgameplay.fly` dostawał „Unknown command”.
            // Alias `fly` zostaje dla backendów bez AdminTools.
            if (plugin.islandFly() != null) {
                registrar.register(Commands.literal("latanie")
                        .requires(source -> source.getSender() instanceof Player player
                                && player.hasPermission("skyblockgameplay.use"))
                        .executes(context -> execute(context.getSource().getSender(),
                                player -> plugin.islandFly().toggle(player))).build(),
                        "Lot na własnej wyspie (perk rangi)", List.of("fly", "lotwyspa"));
            }
            registrar.register(Commands.literal("pomoc")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.use"))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> pomocDialog().open(player,
                                    () -> player.sendMessage(plugin.miniMessage().deserialize(POMOC)))))
                    .then(Commands.literal("tekst")
                            .executes(context -> execute(context.getSource().getSender(),
                                    player -> player.sendMessage(plugin.miniMessage()
                                            .deserialize(POMOC))))
                            .build())
                    .build(),
                    "Pomoc SkyBlock: dialog tematów; /pomoc tekst = lista na czacie",
                    List.of("komendy"));
            registrar.register(Commands.literal("nagroda")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.use"))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> {
                                if (plugin.dailyReward() != null) {
                                    plugin.dailyReward().command(player);
                                } else {
                                    player.sendMessage(Component.text(
                                            "Codzienna nagroda jest wyłączona.", NamedTextColor.RED));
                                }
                            })).build(),
                    "Codzienna nagroda i seria logowań", List.of("daily"));
            // QoL: wirtualny stół i enderchest — czysta wygoda, brak perku.
            // Uprawnienia w paper-plugin.yml; łatwe do zawężenia dla rang LP.
            registrar.register(Commands.literal("craft")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.qol.craft"))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> player.openWorkbench(null, true))).build(),
                    "Wirtualny stół rzemieślniczy", List.of("workbench", "warsztat"));
            registrar.register(Commands.literal("enderchest")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.qol.enderchest"))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> player.openInventory(player.getEnderChest()))).build(),
                    "Otwiera skrzynię kresu", List.of("ec", "ender"));
            registrar.register(Commands.literal("sezon")
                    // Gracz: wymaga skyblockgameplay.use; konsola/RCON przechodzi
                    // (ma wszystkie uprawnienia) — dziecko „zamknij” ma własną
                    // bramkę skyblockgameplay.admin, a podkomendy tylko-w-grze
                    // i tak odrzucają nie-gracza w execute(Consumer<Player>).
                    .requires(source -> !(source.getSender() instanceof Player)
                            || ((Player) source.getSender()).hasPermission(
                                    "skyblockgameplay.use"))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> {
                                if (plugin.seasonPoints() != null) {
                                    new SeasonCommand(plugin.seasonPoints(),
                                            plugin.seasonEndMillis(), plugin::seasonLabel,
                                            plugin.miniMessage(),
                                            org.rafalohaki.wpmecore.addons.skyblock.season.LeaderboardService::nameOf,
                                            plugin::currentRangeDetail,
                                            plugin.seasonQuests()).withIslandResolver(id -> plugin.skyllia() == null ? java.util.Optional.empty() : plugin.skyllia().islandOf(id).map(org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView::snapshot)).show(player);
                                } else {
                                    player.sendMessage(net.kyori.adventure.text.Component.text(
                                            "Sezony jeszcze nie aktywne.", NamedTextColor.RED));
                                }
                            }))
                    .then(Commands.literal("zamknij")
                            .requires(source -> source.getSender()
                                    .hasPermission("skyblockgameplay.admin"))
                            .executes(context ->
                                    executeSeasonClose(context.getSource().getSender(), false, false))
                            .then(Commands.literal("--confirm")
                                    .executes(context ->
                                            executeSeasonClose(context.getSource().getSender(), true, false))
                                    .then(Commands.literal("--force")
                                            .executes(context ->
                                                    executeSeasonClose(context.getSource().getSender(), true, true))
                                            .build())
                                    .build())
                            .build())
                    .then(Commands.literal("pass")
                            .executes(context -> execute(context.getSource().getSender(), this::openPass)).build())
                    .then(Commands.literal("admin")
                            .requires(source -> source.getSender()
                                    .hasPermission("skyblockgameplay.admin"))
                            .executes(context -> execute(context.getSource().getSender(),
                                    player -> plugin.seasonAdminMenu().open(player)))
                            // Migracja Eco: EcoQuests przyznaje punkty sezonowe przez run_command
                            // z konsoli: `sezon admin punkty <gracz> <n> <id>`; <id> = klucz
                            // idempotencji SeasonPointService.award (np. eco-quest:<quest>).
                            .then(Commands.literal("punkty")
                                    .then(Commands.argument("gracz",
                                                    com.mojang.brigadier.arguments.StringArgumentType.word())
                                            .then(Commands.argument("punkty",
                                                            com.mojang.brigadier.arguments.LongArgumentType.longArg(1))
                                                    .then(Commands.argument("id",
                                                                    com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                                            .executes(context -> executeSeasonAward(
                                                                    context.getSource().getSender(),
                                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(context, "gracz"),
                                                                    com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "punkty"),
                                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id")))
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            // P1-1: dzienny kanał punktów dla zadań dnia. To samo
                            // wejście co wędka — punkty liczą się do dobowego capu
                            // kanału (season.daily-points-caps.<kanał>), a <id>
                            // dedupuje powtórzony run_command.
                            .then(Commands.literal("punkty-dzien")
                                    .then(Commands.argument("gracz",
                                                    com.mojang.brigadier.arguments.StringArgumentType.word())
                                            .then(Commands.argument("punkty",
                                                            com.mojang.brigadier.arguments.LongArgumentType
                                                                    .longArg(1, 100_000))
                                                    .then(Commands.argument("kanal",
                                                                    com.mojang.brigadier.arguments.StringArgumentType.word())
                                                            .then(Commands.argument("id",
                                                                            com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                                                    .executes(context -> executeSeasonDailyAward(
                                                                            context.getSource().getSender(),
                                                                            com.mojang.brigadier.arguments.StringArgumentType.getString(context, "gracz"),
                                                                            com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "punkty"),
                                                                            com.mojang.brigadier.arguments.StringArgumentType.getString(context, "kanal"),
                                                                            com.mojang.brigadier.arguments.StringArgumentType.getString(context, "id")))
                                                                    .build())
                                                            .build())
                                                    .build())
                                            .build())
                                    .build())
                            .build())
                    .build(),
                    "Pokazuje punkty sezonowe, pozycję i TOP-10", List.of());
            registrar.register(Commands.literal("przepustka")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.use"))
                    .executes(context -> execute(context.getSource().getSender(), this::openPass))
                    // Migracja Eco: karnet w EcoBattlepass, ale premium kupuje się u nas
                    // za Złote Lotosy (ledger + most `ecobattlepass setpremium`).
                    .then(Commands.literal("premium")
                            .executes(context -> execute(context.getSource().getSender(),
                                    player -> {
                                        if (plugin.seasonPassMenu() != null) {
                                            plugin.seasonPassMenu().activatePremium(player);
                                        } else {
                                            player.sendMessage(net.kyori.adventure.text.Component.text(
                                                    "Przepustka jeszcze nie aktywna.", NamedTextColor.RED));
                                        }
                                    })).build())
                    .build(),
                    "Otwiera karnet sezonowy (premium: /przepustka premium)", List.of("sezonpass", "pass", "karnet"));
            registrar.register(Commands.literal("kosmetyka")
                    .requires(source -> source.getSender()
                            .hasPermission("skyblockgameplay.admin"))
                    .then(Commands.argument("gracz",
                                    com.mojang.brigadier.arguments.StringArgumentType.word())
                            .then(Commands.argument("kolekcja",
                                            com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .suggests((context, builder) -> {
                                        plugin.cosmeticCatalog().collections().keySet()
                                                .forEach(builder::suggest);
                                        return builder.buildFuture();
                                    })
                                    .then(Commands.argument("czesc",
                                                    com.mojang.brigadier.arguments.StringArgumentType.word())
                                            .suggests((context, builder) -> {
                                                String id = com.mojang.brigadier.arguments
                                                        .StringArgumentType.getString(context, "kolekcja");
                                                CosmeticCatalog.Collection collection =
                                                        plugin.cosmeticCatalog().collections().get(id);
                                                if (collection != null) {
                                                    collection.pieces().forEach(builder::suggest);
                                                }
                                                return builder.buildFuture();
                                            })
                                            .executes(context -> grantCosmetic(
                                                    context.getSource().getSender(),
                                                    com.mojang.brigadier.arguments.StringArgumentType
                                                            .getString(context, "gracz"),
                                                    com.mojang.brigadier.arguments.StringArgumentType
                                                            .getString(context, "kolekcja"),
                                                    com.mojang.brigadier.arguments.StringArgumentType
                                                            .getString(context, "czesc")))))).build(),
                    "Wydaje część kolekcji kosmetycznej (kanał sklepowy)", List.of());
            registrar.register(Commands.literal("kosmetyki")
                    .requires(source -> source.getSender() instanceof Player
                            && source.getSender().hasPermission("skyblockgameplay.use"))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> {
                                if (plugin.cosmeticPlayerMenu() != null) {
                                    plugin.cosmeticPlayerMenu().open(player);
                                } else {
                                    player.sendMessage(net.kyori.adventure.text.Component.text(
                                            "Kosmetyka sezonowa jest chwilowo niedostępna.",
                                            NamedTextColor.RED));
                                }
                            })).build(),
                    "Otwiera Twoją kolekcję kosmetyczną (zakładanie i zdejmowanie)",
                    List.of());
            registrar.register(Commands.literal("kuznia")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.use"))
                    .then(Commands.argument("kategoria",
                                    com.mojang.brigadier.arguments.StringArgumentType.word())
                            .suggests((context, builder) -> {
                                for (ForgeConfig.ForgeCategory category
                                        : plugin.forgeConfig().visibleCategories()) {
                                    builder.suggest(category.id());
                                }
                                return builder.buildFuture();
                            })
                            .executes(context -> execute(context.getSource().getSender(),
                                    player -> plugin.forgeMenu().open(player,
                                            com.mojang.brigadier.arguments.StringArgumentType
                                                    .getString(context, "kategoria")))))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> plugin.forgeMenu().open(player))).build(),
                    "Otwiera Kuźnię Mistrzowską i Kantor Wymiany",
                    List.of("forge", "wymiana", "kantor", "kowal"));
            registrar.register(Commands.literal("wartosc")
                    .requires(source -> source.getSender() instanceof Player)
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> wartosc(player))).build(),
                    "Sprawdza cenę przedmiotu z ręki w cenniku /sklep",
                    List.of("cena", "wartość", "ilewarto"));
            registrar.register(Commands.literal("bank")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.use"))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> plugin.bank().open(player))).build(),
                    "Otwiera wspólny bank wyspy", List.of("bankwyspy", "isbank", "islandbank"));
            registrar.register(Commands.literal("zadania")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.use"))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> {
                                // Migracja Eco: Księga Zadań EcoQuests. Aliasy quest/quests
                                // usunięte — kolidowały z komendą EcoQuests.
                                if (plugin.ecoMigration()) {
                                    player.performCommand("quests");
                                } else {
                                    plugin.quests().open(player);
                                }
                            })).build(),
                    "Otwiera dzienne zadania wyspy", List.of("questy", "daily", "misje"));
            registrar.register(Commands.literal("narzedzia")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.use"))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> plugin.wands().open(player))).build(),
                    "Otwiera sklep magicznych narzędzi", List.of("rozdzki", "wands", "wand"));
            registrar.register(Commands.literal("automatyzacja")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.use"))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> plugin.mainMenu().openAutomationMenu(player))).build(),
                    "Otwiera menu automatyzacji wyspy (Chunker, Sell Chest)",
                    List.of("automation", "maszyny", "chunker", "sellchest", "chunkery"));
            registrar.register(Commands.literal("stworzwyspe")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.use"))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> plugin.mainMenu().openIslandCreationPicker(player))).build(),
                    "Otwiera menu wyboru trybu wyspy (SkyBlock / OneBlock)", List.of("createisland", "iscreate"));
            registrar.register(Commands.literal("wyspa")
                    .requires(source -> source.getSender() instanceof Player)
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> player.performCommand("is")))
                    .then(Commands.literal("stworz")
                            .executes(context -> execute(context.getSource().getSender(),
                                    player -> plugin.mainMenu().openIslandCreationPicker(player))))
                    .then(Commands.literal("create")
                            .executes(context -> execute(context.getSource().getSender(),
                                    player -> plugin.mainMenu().openIslandCreationPicker(player))))
                    .then(Commands.literal("oneblock")
                            .then(Commands.literal("nagrody")
                                    .requires(source -> source.getSender().hasPermission("skyblockgameplay.use"))
                                    .executes(context -> execute(context.getSource().getSender(),
                                            player -> plugin.oneBlockMilestoneMenu().openFor(player))))
                            .then(Commands.literal("info")
                                    .requires(source -> source.getSender().hasPermission("skyblockgameplay.admin"))
                                    .executes(context -> execute(context.getSource().getSender(),
                                            this::oneblockInfo)))
                            .then(Commands.literal("napraw")
                                    .requires(source -> source.getSender().hasPermission("skyblockgameplay.use"))
                                    .executes(context -> execute(context.getSource().getSender(),
                                            this::oneblockRepair)))
                            .then(Commands.literal("ustaw")
                                    .requires(source -> source.getSender().hasPermission("skyblockgameplay.admin"))
                                    .then(Commands.argument("faza", com.mojang.brigadier.arguments.StringArgumentType.word())
                                            .then(Commands.argument("licznik", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                                    .executes(context -> execute(context.getSource().getSender(),
                                                            player -> oneblockSetPrompt(player,
                                                                    context.getArgument("faza", String.class),
                                                                    context.getArgument("licznik", Integer.class))))
                                                    .then(Commands.literal("potwierdz")
                                                            .executes(context -> execute(context.getSource().getSender(),
                                                                    player -> oneblockSet(player,
                                                                            context.getArgument("faza", String.class),
                                                                            context.getArgument("licznik", Integer.class)))))))))
                    .then(Commands.argument("args", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                            .executes(context -> execute(context.getSource().getSender(),
                                    player -> player.performCommand("is " + context.getArgument("args", String.class))))).build(),
                    "Teleportuje i zarządza wyspą SkyBlock", List.of("island"));
            registrar.register(Commands.literal("nagrody")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.use"))
                    .executes(context -> execute(context.getSource().getSender(),
                            player -> plugin.topRewardCoordinator().showRankingSummary(player)))
                    .then(Commands.literal("claim")
                            .executes(context -> execute(context.getSource().getSender(),
                                    player -> plugin.topRewardCoordinator().claimReward(player))))
                    .then(Commands.literal("odbierz")
                            .executes(context -> execute(context.getSource().getSender(),
                                    player -> plugin.topRewardCoordinator().claimReward(player))))
                    .then(Commands.literal("top")
                            .executes(context -> execute(context.getSource().getSender(),
                                    player -> plugin.topRewardCoordinator().showRankingSummary(player))))
                    .then(Commands.literal("status")
                            .executes(context -> execute(context.getSource().getSender(),
                                    player -> plugin.topRewardCoordinator().showRankingSummary(player))))
                    .then(Commands.literal("admin")
                            .requires(source -> source.getSender().hasPermission("skyblockgameplay.admin"))
                            .then(Commands.literal("season-end")
                                    .executes(context -> executeSeasonEnd(context.getSource().getSender()))))
                    .build(),
                    "Otwiera ranking i odbiór nagród sezonowych TOP 3 wysp",
                    List.of("toprewards", "nagrodasezonowa", "nagrodysezonowe", "topnagrody"));
            registrar.register(Commands.literal("skyblock")
                    .requires(source -> source.getSender().hasPermission("skyblockgameplay.admin"))
                    .then(Commands.literal("admin")
                            .then(Commands.literal("season-end")
                                    .executes(context -> executeSeasonEnd(context.getSource().getSender())))
                            .then(Commands.literal("minion-give")
                                    .then(Commands.argument("typ",
                                                    com.mojang.brigadier.arguments.StringArgumentType.word())
                                            .executes(context -> giveMinion(
                                                    context.getSource().getSender(),
                                                    context.getArgument("typ", String.class), 1))
                                            .then(Commands.argument("poziom",
                                                            com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 5))
                                                    .executes(context -> giveMinion(
                                                            context.getSource().getSender(),
                                                            context.getArgument("typ", String.class),
                                                            context.getArgument("poziom", Integer.class)))))))
                    .build(),
                    "Komendy administracyjne SkyBlock", List.of());
        });
        // /is top — subkomenda Skylli (jej komenda, nie nasza), ten sam blok TOP
        // co ogon /sezon. Rejestracja i typy Skylli siedzą w skylliaintegration
        // (R-API-01) — tu podajemy tylko uprawnienie i akcję po CommandSender.
        SkylliaCommands.registerSubCommand(plugin, "skyblockgameplay.use", sender -> {
            if (plugin.seasonPoints() == null) {
                sender.sendMessage(Component.text("Sezony jeszcze nie aktywne.", NamedTextColor.RED));
                return;
            }
            new SeasonCommand(plugin.seasonPoints(), plugin.seasonEndMillis(), plugin::seasonLabel,
                    plugin.miniMessage(), LeaderboardService::nameOf, plugin::currentRangeDetail).withIslandResolver(id -> plugin.skyllia() == null ? java.util.Optional.empty() : plugin.skyllia().islandOf(id).map(org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView::snapshot))
                    .showTop(sender, 10);
        }, "top");

        // /is prestige — menu prestiżu z modułu; po konsolidacji addonu
        // SkylliaPrestige jedynym systemem prestiżu jest island.prestige.
        SkylliaCommands.registerSubCommand(plugin, "skyblockgameplay.use", sender -> {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Tylko w grze.", NamedTextColor.RED));
                return;
            }
            org.rafalohaki.wpmecore.addons.skyblock.island.IslandPrestigeMenu menu =
                    plugin.islandPrestigeMenu();
            if (menu == null) {
                player.sendMessage(Component.text("Prestiż wyspy jest wyłączony.", NamedTextColor.RED));
                return;
            }
            menu.open(player);
        }, "prestige");
    }

    int giveMinion(CommandSender sender, String typeId, int tier) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Tylko w grze.", NamedTextColor.RED));
            return 0;
        }
        MinionsConfig config = plugin.minionsConfig();
        MinionsConfig.TypeDef type = config == null ? null : config.type(typeId);
        if (type == null) {
            player.sendMessage(Component.text("Nieznany typ minionka: " + typeId
                    + ". Dostępne: " + String.join(", ", config.minions().keySet()),
                    NamedTextColor.RED));
            return 0;
        }
        ItemStack item = MinionItem.create(type, tier, false, null, 0L, "", plugin.miniMessage());
        player.getInventory().addItem(item).values().forEach(
                stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        player.sendMessage(Component.text("Wydano minionka " + typeId + " Tier " + tier + ".",
                NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    int executeSeasonEnd(CommandSender sender) {
        if (!sender.hasPermission("skyblockgameplay.admin")) {
            sender.sendMessage(Component.text("Nie masz uprawnienia do tej komendy.",
                    NamedTextColor.RED));
            return 0;
        }
        SkyBlockTopRewardCoordinator coordinator = plugin.topRewardCoordinator();
        if (coordinator == null) {
            sender.sendMessage(Component.text("Koordynator nagród sezonowych jest niedostępny.",
                    NamedTextColor.RED));
            return 0;
        }
        String oldLabel = plugin.seasonLabel();
        coordinator.endSeason().thenRun(() -> {
                    // Etykiety datowe zamiast numerków (decyzja operatora 2026-08-25).
                    // Nowa etykieta: nazwa kolejnej edycji z editions.yml. Gdy jej
                    // brak (edycje wyłączone albo brak następnego wpisu), etykieta
                    // legacy pokazuje ten sam zakres dat co stara — ogłaszanie
                    // „rozpoczął się sezon X” przy X równym starej etykiecie byłoby
                    // absurdem, więc idzie jedno uczciwe zdanie o końcu sezonu i
                    // kolejnym oknie wg kalendarza, bez fałszywej deklaracji.
                    String nextText = plugin.seasonNextEditionLabel();
                    String oldText = oldLabel != null ? oldLabel : "dotychczasowy";
                    if (nextText == null || nextText.equals(oldLabel)) {
                        sender.sendMessage(Component.text(
                                "Zakończono sezon (" + oldText + "). Kolejne okno "
                                        + "sezonowe rozpocznie się zgodnie z kalendarzem.",
                                NamedTextColor.GREEN));
                        Bukkit.broadcast(Component.text(
                                "[SkyBlock] Sezon (" + oldText + ") dobiegł końca! "
                                        + "Kolejne okno sezonowe rozpocznie się zgodnie "
                                        + "z kalendarzem. Gratulacje dla zwycięzców!",
                                NamedTextColor.GOLD));
                        return;
                    }
                    sender.sendMessage(Component.text(
                            "Zakończono sezon (" + oldText + ")! Nowy aktywny sezon: "
                                    + nextText + ".",
                            NamedTextColor.GREEN));
                    Bukkit.broadcast(Component.text(
                            "[SkyBlock] Sezon " + oldText + " dobiegł końca! Rozpoczął się sezon "
                                    + nextText + "! Gratulacje dla zwycięzców!",
                            NamedTextColor.GOLD));
                }).exceptionally(err -> {
            sender.sendMessage(Component.text(
                    "Błąd podczas kończenia sezonu: " + err.getMessage(),
                    NamedTextColor.RED));
            return null;
        });
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Operatorowe zamknięcie sezonu (decyzja 2026-08-25): ZERO auto-zamykania.
     * Bez {@code --confirm} wołamy {@code closeSeason(false, force)} = dry-run —
     * dostajemy odmowę z powodem i podsumowaniem, nic się nie zmienia.
     * Z {@code --confirm} pełne zamknięcie: snapshot → nagrody raz (outbox)
     * → rollover season_id+1 → czyste punkty. Broadcast po sukcesie robi
     * SkyBlockGameplay; tu tylko raport dla operatora.
     */
    /**
     * {@code /sezon admin punkty <gracz> <n> <id>} — z konsoli (EcoQuests run_command) lub od
     * operatora. Gracz musi być online (bez blokującego lookupu UUID na Folii); id dedupuje
     * ponowne wywołania, więc powtórzony run_command nie płaci dwa razy.
     */
    int executeSeasonAward(CommandSender sender, String name, long points, String rawId) {
        String id = rawId.trim();
        var service = plugin.seasonPoints();
        if (service == null) {
            sender.sendMessage(net.kyori.adventure.text.Component.text("Sezony jeszcze nie aktywne.", NamedTextColor.RED));
            return 0;
        }
        Player target = org.bukkit.Bukkit.getPlayerExact(name);
        if (target == null || id.isEmpty()) {
            sender.sendMessage(net.kyori.adventure.text.Component.text(
                    "Gracz " + name + " nie jest online albo brak id operacji.", NamedTextColor.RED));
            return 0;
        }
        service.award(target.getUniqueId(), points, id + ":" + target.getUniqueId()).whenComplete((applied, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "sezon admin punkty " + name + " " + points + " " + id + " nie powiodło się", failure);
                return;
            }
            sender.sendMessage(net.kyori.adventure.text.Component.text(
                    (applied ? "Przyznano " : "Pominięto (już przyznane) ") + points + " pkt sezonowych dla " + name
                            + " [" + id + "]", applied ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        });
        return 1;
    }

    /**
     * {@code /sezon admin punkty-dzien <gracz> <punkty> <kanał> <id>} — kanał
     * zadania dnia (EcoQuests run_command lub operator). Punkty wchodzą do
     * dobowego capu kanału, nie obok niego; {@code id} dedupuje powtórzenia.
     */
    int executeSeasonDailyAward(CommandSender sender, String name, long points,
                                String channel, String rawId) {
        String id = rawId.trim();
        String channelId = channel.trim().toLowerCase(java.util.Locale.ROOT);
        var service = plugin.seasonDailyPoints();
        if (service == null) {
            sender.sendMessage(Component.text("Sezony jeszcze nie aktywne.", NamedTextColor.RED));
            return 0;
        }
        Player target = org.bukkit.Bukkit.getPlayerExact(name);
        if (target == null || id.isEmpty() || channelId.isEmpty()) {
            sender.sendMessage(Component.text(
                    "Gracz " + name + " nie jest online albo brak kanału/id operacji.",
                    NamedTextColor.RED));
            return 0;
        }
        service.awardDaily(target.getUniqueId(), channelId, points, id).whenComplete((granted, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "sezon admin punkty-dzien " + name + " " + points + " " + channelId
                                + " " + id + " nie powiodło się", failure);
                sender.sendMessage(Component.text(
                        "Nie udało się przyznać punktów dnia: " + failure.getMessage(),
                        NamedTextColor.RED));
                return;
            }
            if (granted == null || granted == 0) {
                sender.sendMessage(Component.text("Limit dnia wyczerpany dla kanału " + channelId
                        + " (albo operacja [" + id + "] już rozliczona) — nic nie przyznano.",
                        NamedTextColor.YELLOW));
                return;
            }
            long cap = plugin.seasonDailyPointsCaps().capFor(channelId);
            service.pointsToday(target.getUniqueId(), channelId).thenAccept(today -> {
                String left = cap == 0
                        ? "bez limitu"
                        : Math.max(0L, cap - today) + " z " + cap;
                sender.sendMessage(Component.text("Przyznano " + granted + " pkt sezonowych dla "
                        + name + " (kanał " + channelId + ", zostało dziś: " + left + ") ["
                        + id + "]", NamedTextColor.GREEN));
            }).exceptionally(readFailure -> {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "sezon admin punkty-dzien: nie udało się odczytać stanu doby dla "
                                + name + " " + channelId, readFailure);
                sender.sendMessage(Component.text("Przyznano " + granted + " pkt sezonowych dla "
                        + name + " (kanał " + channelId + ", stanu doby nie udało się odczytać) ["
                        + id + "]", NamedTextColor.GREEN));
                return null;
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Karnet: przy migracji Eco GUI EcoBattlepass, inaczej nasze menu przepustki. */
    private void openPass(@NotNull Player player) {
        if (plugin.ecoMigration()) {
            player.performCommand("battlepass");
            return;
        }
        if (plugin.seasonPassMenu() != null) {
            plugin.seasonPassMenu().open(player);
        } else {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "Przepustka jeszcze nie aktywna.", NamedTextColor.RED));
        }
    }

    int executeSeasonClose(CommandSender sender, boolean confirm, boolean force) {
        var outcomeFuture = plugin.closeSeason(confirm, force);
        if (outcomeFuture == null) {
            sender.sendMessage(Component.text("Zamykanie sezonu niedostępne.",
                    NamedTextColor.RED));
            return 0;
        }
        outcomeFuture.thenAccept(outcome ->
                // Wspólny raport operatora CLI+GUI (ekstrakcja formatowania
                // do SeasonCloseReport; etykieta następnej edycji gdy znana).
                SeasonCloseReport.send(sender, outcome,
                        plugin.seasonNextEditionLabel(), !confirm)
        ).exceptionally(err -> {
            sender.sendMessage(Component.text(
                    "Błąd podczas zamykania sezonu: " + err.getMessage(),
                    NamedTextColor.RED));
            return null;
        });
        return Command.SINGLE_SUCCESS;
    }

    // ---- /wyspa oneblock handlers (spec §8) ----

    void oneblockInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Tylko w grze.", NamedTextColor.RED));
            return;
        }
        plugin.skyllia().islandOf(player.getUniqueId()).ifPresentOrElse(view ->
                plugin.oneBlockService().info(view.islandId())
                        .thenAccept(info -> player.getScheduler().run(plugin, task ->
                                player.sendMessage(info
                                        .map(data -> Component.text(
                                                "OneBlock: rozdział=" + data.phaseId()
                                                        + " licznik=" + data.progress()
                                                        + " dry_streak=" + data.dryStreak()
                                                        + " nieodebrane=" + data.unclaimedMilestones(),
                                                NamedTextColor.GOLD))
                                        .orElseGet(() -> Component.text(
                                                "To nie jest wyspa OneBlock.", NamedTextColor.RED))), null))
                        .exceptionally(err -> {
                            plugin.getLogger().log(java.util.logging.Level.WARNING,
                                    "OneBlock info failed for island " + view.islandId(), err);
                            player.getScheduler().run(plugin, task -> player.sendMessage(Component.text(
                                    "Nie udało się odczytać stanu OneBlock.", NamedTextColor.RED)), null);
                            return null;
                        }),
                () -> sender.sendMessage(Component.text("Nie masz wyspy.", NamedTextColor.RED)));
    }

    void oneblockRepair(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Tylko w grze.", NamedTextColor.RED));
            return;
        }
        boolean isAdmin = player.hasPermission("skyblockgameplay.admin");
        plugin.skyllia().islandOf(player.getUniqueId()).ifPresentOrElse(view -> {
            OneBlockService.RepairResult result = plugin.oneBlockService().repairOneBlock(view.islandId(), isAdmin);
            switch (result) {
                case SUCCESS -> player.sendMessage(plugin.miniMessage().deserialize(
                        "<green>Blok OneBlock został ponownie postawiony na Twojej wyspie.</green>"));
                case NOT_ONEBLOCK -> player.sendMessage(plugin.miniMessage().deserialize(
                        "<red>Twoja wyspa nie jest wyspą OneBlock.</red>"));
                case NOT_AIR -> player.sendMessage(plugin.miniMessage().deserialize(
                        "<red>Blok OneBlock już istnieje na Twojej wyspie (naprawa jest możliwa tylko, gdy blok zniknął).</red>"));
                case COOLDOWN -> player.sendMessage(plugin.miniMessage().deserialize(
                        "<red>Musisz odczekać chwilę przed ponowną naprawą bloku.</red>"));
                case WORLD_UNLOADED -> player.sendMessage(plugin.miniMessage().deserialize(
                        "<red>Świat Twojej wyspy nie jest obecnie załadowany.</red>"));
            }
        }, () -> sender.sendMessage(Component.text("Nie masz wyspy.", NamedTextColor.RED)));
    }

    void oneblockSetPrompt(Player player, String phaseId, int progress) {
        player.sendMessage(plugin.miniMessage().deserialize(
                "<red>Ustawienie rozdziału '" + phaseId + "' i licznika " + progress
                        + " cofa progres wyspy. Kliknij, aby potwierdzić:</red> <dark_red><underlined>Potwierdzam</underlined></dark_red>"
        ).clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(
                "/wyspa oneblock ustaw " + phaseId + " " + progress + " potwierdz")));
    }

    void oneblockSet(Player player, String phaseId, int progress) {
        plugin.skyllia().islandOf(player.getUniqueId()).ifPresentOrElse(view -> {
            if (plugin.oneBlockService().setProgress(view.islandId(), phaseId, progress)) {
                player.sendMessage(Component.text(
                        "Ustawiono: rozdział=" + phaseId + " licznik=" + progress, NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text(
                        "Nieznany rozdział albo to nie wyspa OneBlock.", NamedTextColor.RED));
            }
        }, () -> player.sendMessage(Component.text("Nie masz wyspy.", NamedTextColor.RED)));
    }

    void teleportToGlobalSpawn(Player player) {
        SkyBlockHub currentHub = plugin.hub();
        if (currentHub == null) {
            player.sendMessage(Component.text(
                    "Spawn SkyBlock jest chwilowo niedostępny.",
                    NamedTextColor.RED));
            return;
        }
        currentHub.teleportWithConfirmation(player);
    }


    /**
     * Kanał sklepowy dla kosmetyki. Egzemplarz jest oznaczany progiem
     * {@code SKLEP}, więc po samym przedmiocie widać, że pochodzi z zakupu, a nie
     * z podium sezonu. Edycja to sezon kolekcji, żeby zachować jej przynależność.
     */
    int grantCosmetic(@NotNull CommandSender sender, @NotNull String playerName,
                              @NotNull String collectionId, @NotNull String pieceId) {
        CosmeticCatalog.Collection collection = plugin.cosmeticCatalog().collections().get(collectionId);
        if (collection == null) {
            sender.sendMessage(Component.text("Nieznana kolekcja: " + collectionId,
                    NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        if (!collection.pieces().contains(pieceId)) {
            sender.sendMessage(Component.text("Kolekcja '" + collectionId
                    + "' nie ma części '" + pieceId + "'", NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(Component.text("Gracz " + playerName + " jest offline",
                    NamedTextColor.RED));
            return Command.SINGLE_SUCCESS;
        }
        String edition = CosmeticCatalog.editionOf(
                plugin.cosmeticCatalog().seasonOf(collectionId).orElse(0));
        target.getScheduler().run(plugin, ignored -> plugin.cosmeticService().grantSingle(
                target, collection, pieceId, "SKLEP", edition, () -> { }), null);
        sender.sendMessage(Component.text("Wydano " + pieceId + " graczowi " + playerName,
                NamedTextColor.GREEN));
        return Command.SINGLE_SUCCESS;
    }

    /**
     * /wartosc — cena przedmiotu z głównej ręki według cennika /sklep, bez
     * otwierania GUI. Uzgędnia customy (PDC), stawki za sztukę i przedmioty
     * wymieniane za lotosy. Komenda czytelnicza: niczego nie sprzedaje.
     */
    private void wartosc(@NotNull Player player) {
        MiniMessage mm = plugin.miniMessage();
        ItemStack stack = player.getInventory().getItemInMainHand();
        if (stack.getType().isAir()) {
            player.sendMessage(mm.deserialize(
                    "<gray>Weź przedmiot do ręki i wpisz ponownie <white>/wartosc</white>.</gray>"));
            return;
        }
        CustomItemService customItems = plugin.shop().getCustomItemService();
        String customId = customItems == null ? null : customItems.idOf(stack);
        ShopCatalog.Product product = customId != null
                ? plugin.shop().catalog().productByCustomItem(customId)
                : plugin.shop().catalog().product(stack.getType());
        if (product == null) {
            player.sendMessage(mm.deserialize("<red>Tego przedmiotu nie ma w cenniku /sklep.</red>"
                    + (customId != null ? "" : " <gray>Przedmioty z komponentami/PDC różdżka pomija.</gray>")));
            return;
        }
        ItemNames names = new ItemNames(customItems);
        String label = customId != null
                ? names.customLabel(customId)
                : names.vanillaLabel(stack.getType());
        int amount = Math.max(1, stack.getAmount());
        if (product.sell() > 0) {
            player.sendMessage(mm.deserialize("<gray>" + label + " ×" + amount + " — skup:</gray> "
                    + Ui.price(product.sell() * amount)
                    + " <dark_gray>(" + Ui.price(product.sell()) + "/szt.)</dark_gray>"));
            return;
        }
        if (product.isCurrencyItemPayment()) {
            String currency = product.currencyItem();
            String currencyName = customItems != null && currency != null
                    ? customItems.byId(currency).map(CustomItem::name).orElse(currency)
                    : String.valueOf(currency);
            player.sendMessage(mm.deserialize("<gray>" + label + " ×" + amount + " — kupno:</gray> <gold>"
                    + product.priceItemCount() * amount + "× " + currencyName
                    + "</gold> <gray>(wymiana, nie skup).</gray>"));
            return;
        }
        player.sendMessage(mm.deserialize("<gray>" + label + " ×" + amount + " — kupno:</gray> "
                + Ui.price(product.buy() * amount) + " <gray>— nie skupowane w /sklep.</gray>"));
    }

    int execute(CommandSender sender, Consumer<Player> action) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Ta komenda jest dostępna tylko w grze.",
                    NamedTextColor.RED));
            return 0;
        }
        if (!player.hasPermission("skyblockgameplay.use")) {
            player.sendMessage(Component.text("Nie masz uprawnienia do tej komendy.",
                    NamedTextColor.RED));
            return 0;
        }
        player.getScheduler().run(plugin, ignored -> action.accept(player), null);
        return Command.SINGLE_SUCCESS;
    }

    /** Dialog pomocy budowany leniwie — potrzebuje schedulera i MiniMessage,
     *  które istnieją dopiero po {@code onEnable}. */
    private PomocDialog pomocDialog() {
        return new PomocDialog(plugin.scheduler(), plugin.miniMessage(),
                plugin.getLogger());
    }

}
