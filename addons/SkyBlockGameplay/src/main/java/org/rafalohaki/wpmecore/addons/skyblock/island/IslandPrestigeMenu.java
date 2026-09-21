package org.rafalohaki.wpmecore.addons.skyblock.island;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockTopRewardCoordinator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Inventories;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Menu Prestiżu Wyspy i kafelek w Centrum Wyspy.
 *
 * <p>FOLIA-THREADING: menu otwiera się na wątku encji gracza, a każdy odczyt
 * z bazy (poziom prestiżu, saldo banku wyspy) leci asynchronicznie i wraca na
 * wątek encji — na wątku regionu nie ma ani jednego blokującego zapytania.
 * Rolę właściciela potwierdzamy autorytatywnie przez async scheduler przed
 * debetem, tak jak bank wyspy przy wypłacie.
 *
 * <p>Kafelek renderuje poziom z cache serwisu (bez bazy na wątku regionu)
 * i dociąga świeżą wartość w tle; brak wstrzyknięcia = kafelek się nie pokazuje.
 */
public final class IslandPrestigeMenu {

    private static final long AUTHORIZATION_TIMEOUT_SECONDS = 5L;
    private static final int SLOT_SWITCHER = 36;
    private static final int SLOT_STATE = 13;
    private static final int SLOT_BANK = 20;
    private static final int SLOT_LOTUS = 22;
    private static final int SLOT_BUY = 24;
    private static final int SLOT_CLOSE = 40;

    /** Źródło tytułu zlewu — jedno na wyspę, więc powtórka nie płaci drugi raz. */
    static final String LOTUS_SOURCE = "lotus:master";
    /** Ile Diamentowych Lotosów kosztuje tytuł Władcy Lotosu. */
    static final int LOTUS_COST = 3;
    static final String LOTUS_TITLE = "<gold>Władca Lotosu</gold>";

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage mm;
    private final SkylliaIntegration skyllia;
    private final LedgerService ledger;
    private final IslandPrestigeService service;
    private final IslandCenterMenu center;
    private final IslandTitleService titles;
    private final @Nullable CustomItemService customItems;
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();
    /** Jedno kupno tytułu za Lotosy na gracza naraz — dwa kliknięcia to nie dwa zlewy. */
    private final Set<UUID> buyingLotus = ConcurrentHashMap.newKeySet();

    public IslandPrestigeMenu(@NotNull JavaPlugin plugin, @NotNull MenuService menus,
                              @NotNull MiniMessage mm, @NotNull SkylliaIntegration skyllia,
                              @NotNull LedgerService ledger, @NotNull IslandPrestigeService service,
                              @NotNull IslandCenterMenu center, @NotNull IslandTitleService titles,
                              @Nullable CustomItemService customItems) {
        this.plugin = plugin;
        this.menus = menus;
        this.mm = mm;
        this.skyllia = skyllia;
        this.ledger = ledger;
        this.service = service;
        this.center = center;
        this.titles = titles;
        this.customItems = customItems;
    }

    /** Ile Diamentowych Lotosów ma gracz w ekwipunku (bez blokowania, wątek encji). */
    private int lotusCount(@NotNull Player player) {
        return Inventories.countCustom(player.getInventory(),
                SkyBlockTopRewardCoordinator.DIAMOND_LOTUS_ID, customItems);
    }

    /** Otwiera menu prestiżu graczowi, który ma wyspę; salda i poziom czytane są w tle. */
    public void open(@NotNull Player player) {
        IslandView context = skyllia.islandOf(player.getUniqueId()).orElse(null);
        if (context == null) {
            player.closeInventory();
            player.sendMessage(Ui.component(mm,
                    "<red>Najpierw utwórz wyspę komendą <yellow>/is</yellow>.</red>"));
            return;
        }
        if (!opening.add(player.getUniqueId())) {
            player.sendActionBar(Ui.component(mm, "<gray>Odświeżam stan prestiżu…</gray>"));
            return;
        }
        UUID expectedIslandId = context.islandId();
        Runnable releaseOpening = () -> this.opening.remove(player.getUniqueId());
        service.state(expectedIslandId)
                .thenCombine(ledger.authoritativeIslandBalance(expectedIslandId), Opening::new)
                /*
                 * Kafel zlewu musi wiedzieć od pierwszego renderu, czy wyspa ma
                 * ten tytuł — inaczej po restarcie oferowałby zakup, który
                 * wyspa już posiada. Odczyt jest autorytatywny (magazyn tytułów),
                 * więc jeden wiersz więcej niż dotąd.
                 */
                .thenCombine(titles.hasTitleFrom(expectedIslandId, LOTUS_SOURCE),
                        (opening, lotusOwned) -> opening.withLotus(lotusOwned))
                .whenComplete((opening, failure) -> runOnEntity(player, () -> {
                    releaseOpening.run();
                    if (failure != null || opening == null) {
                        if (failure != null) {
                            plugin.getLogger().log(Level.WARNING,
                                    "Nie udało się odczytać stanu prestiżu wyspy " + expectedIslandId,
                                    failure);
                        }
                        player.sendMessage(Ui.component(mm,
                                "<red>Nie udało się odczytać stanu prestiżu. Spróbuj ponownie.</red>"));
                        return;
                    }
                    IslandView current = skyllia.islandOf(player.getUniqueId()).orElse(null);
                    if (current == null || !current.islandId().equals(expectedIslandId)) {
                        player.sendMessage(Ui.component(mm,
                                "<red>Twoje członkostwo wyspy właśnie się zmieniło.</red>"));
                        return;
                    }
                    openNow(player, current, opening);
                }, releaseOpening));
    }

    private record Opening(IslandPrestigeService.State state, long balance, boolean lotusOwned) {

        private Opening(@NotNull IslandPrestigeService.State state, long balance) {
            this(state, balance, false);
        }

        private @NotNull Opening withLotus(boolean owned) {
            return new Opening(state, balance, owned);
        }
    }

    private void openNow(@NotNull Player player, @NotNull IslandView context,
                         @NotNull Opening opening) {
        IslandPrestigeService.State state = opening.state();
        long balance = opening.balance();
        MenuService.Menu menu = menus.ofRows(5, Ui.panelTitle(5, Ui.component(mm,
                "<gold><bold>Prestiż wyspy</bold></gold>")));
        Ui.frame(menu, mm, Material.PURPLE_STAINED_GLASS_PANE);
        menu.decoration(SLOT_STATE, stateIcon(state, context.islandId()));
        menu.decoration(SLOT_BANK, Ui.item(Material.GOLD_BLOCK, mm, "<gold>Bank wyspy</gold>",
                List.of(Ui.price(balance),
                        "<gray>Prestiż płacisz z konta wyspy —</gray>",
                        "<gray>nie z własnego portfela.</gray>"), false));
        renderLotusTile(menu, context, opening.lotusOwned());
        boolean owner = context.role() == IslandRole.OWNER;
        if (!owner) {
            menu.decoration(SLOT_BUY, Ui.item(Material.GRAY_CONCRETE, mm,
                    "<gray><bold>Prestiż podnosi tylko właściciel</bold></gray>",
                    List.of("<gray>Widzisz stan wyspy, ale zakupu może</gray>",
                            "<gray>dokonać wyłącznie właściciel.</gray>"), false));
        } else if (state.maxed()) {
            menu.decoration(SLOT_BUY, Ui.item(Material.NETHER_STAR, mm,
                    "<gold><bold>Maksymalny prestiż</bold></gold>",
                    List.of("<gray>Wyspa osiągnęła poziom <white>" + state.level()
                                    + "</white> z <white>" + service.settings().maxLevel() + "</white>.</gray>",
                            "<gray>Nie ma już czego kupować.</gray>"), false));
        } else if (balance < state.nextCost()) {
            long missing = state.nextCost() - balance;
            menu.set(SLOT_BUY, Ui.item(Material.RED_CONCRETE, mm,
                            "<red><bold>Za mało monet w banku wyspy</bold></red>",
                            List.of("<gray>Następny prestiż: " + Ui.price(state.nextCost()) + "</gray>",
                                    "<gray>Brakuje: <red>" + Ui.money(missing) + "</red></gray>",
                                    "<gray>Wpłać monety do banku wyspy i wróć.</gray>"), false),
                    (viewer, click) -> viewer.sendMessage(Ui.component(mm,
                            "<red>Bank wyspy nie ma dość monet na ten prestiż. "
                                    + "Brakuje <gold><amount></gold>.</red>",
                            Placeholder.unparsed("amount", Ui.money(missing)))));
        } else {
            menu.set(SLOT_BUY, Ui.item(Material.LIME_CONCRETE, mm,
                            "<green><bold>Podnieś prestiż na <white>" + (state.level() + 1)
                                    + "</white></bold></green>",
                            List.of("<gray>Koszt: " + Ui.price(state.nextCost()) + "</gray>",
                                    "<gray>Zapłata: bank wyspy.</gray>",
                                    cosmeticLine(state.level() + 1),
                                    perkLine(state.level(), state.level() + 1),
                                    "<yellow>Kliknij, aby potwierdzić.</yellow>"), false),
                    (viewer, click) -> confirm(viewer, context, state, balance));
        }
        menu.set(SLOT_SWITCHER, Ui.backButton(mm), (viewer, click) -> center.open(viewer));
        menu.close(SLOT_CLOSE, Ui.closeButton(mm));
        menu.open(player);
    }

    /**
     * Kafelek zlewu Diamentowego Lotosu: jednorazowy tytuł Władcy Lotosu za
     * 3 Lotosy z ekwipunku klikającego. Stan „posiadany” nie jest klikalny —
     * wyspa ma ten tytuł na zawsze, a zlew nie ma drugiego progu.
     */
    private void renderLotusTile(@NotNull MenuService.Menu menu, @NotNull IslandView context,
                                 boolean owned) {
        if (owned) {
            menu.decoration(SLOT_LOTUS, Ui.item(Material.LILY_PAD, mm,
                    "<gold><bold>Władca Lotosu</bold></gold>",
                    List.of("<gray>Ta wyspa już nosi ten tytuł.</gray>",
                            "<dark_gray>Jednorazowy zlew — nie kupisz go drugi raz.</dark_gray>"), true));
            return;
        }
        List<String> lore = List.of(
                "<gray>Koszt: <white>" + LOTUS_COST + "</white> × Diamentowy Lotos</gray>",
                "<gray>z Twojego ekwipunku.</gray>",
                "<gray>Tytuł nosi cała wyspa i widać go</gray>",
                "<gray>w Centrum Wyspy oraz wszędzie,</gray>",
                "<gray>gdzie serwer pokazuje tytuł wyspy.</gray>",
                context.role() == IslandRole.OWNER
                        ? "<yellow>Kliknij, aby nadać tytuł.</yellow>"
                        : "<red>Zlewkę robi tylko właściciel wyspy.</red>");
        menu.set(SLOT_LOTUS, Ui.item(Material.LILY_PAD, mm,
                "<aqua><bold>Tytuł Władcy Lotosu</bold></aqua>", lore, true),
                (viewer, click) -> buyLotusTitle(viewer, context));
    }

    /**
     * Zlew: najpierw autorytatywna rola właściciela i stan tytułu (async), potem
     * ekwipunek. Tytuł zapisujemy <b>przed</b> zdjęciem Lotosów — gdyby wyspa
     * dostała tytuł bez pobrania przedmiotów, kafel pokaże „posiadany”, a gracz
     * nie oddał nic; odwrotna kolejność mogłaby zjeść Lotosy bez tytułu.
     */
    private void buyLotusTitle(@NotNull Player player, @NotNull IslandView context) {
        UUID islandId = context.islandId();
        UUID playerId = player.getUniqueId();
        if (!buyingLotus.add(playerId)) {
            player.sendActionBar(Ui.component(mm, "<gray>Sprawdzam stan tytułu…</gray>"));
            return;
        }
        Runnable release = () -> this.buyingLotus.remove(playerId);
        player.sendMessage(Ui.component(mm, "<gray>Sprawdzam uprawnienia i ekwipunek…</gray>"));
        player.closeInventory();
        authoritativeOwnerAsync(playerId, islandId)
                .thenCombine(titles.hasTitleFrom(islandId, LOTUS_SOURCE), LotusBuy::new)
                .whenComplete((decision, failure) -> runOnEntity(player, () -> {
                    release.run();
                    if (failure != null || decision == null) {
                        if (failure != null) {
                            plugin.getLogger().log(Level.WARNING,
                                    "Nie udało się odczytać tytułu wyspy " + islandId, failure);
                        }
                        player.sendMessage(Ui.component(mm,
                                "<red>Nie udało się odczytać stanu tytułu. Spróbuj ponownie.</red>"));
                        return;
                    }
                    if (!decision.owner()) {
                        player.sendMessage(Ui.component(mm,
                                "<red>Tytuł Władcy Lotosu nadaje tylko właściciel wyspy.</red>"));
                        return;
                    }
                    if (decision.owned()) {
                        player.sendMessage(Ui.component(mm, LOTUS_ALREADY_OWNED));
                        return;
                    }
                    int held = lotusCount(player);
                    if (held < LOTUS_COST) {
                        player.sendMessage(Ui.component(mm, lotusShortfallMessage(held)));
                        return;
                    }
                    grantLotusTitle(player, islandId);
                }, release));
    }

    /** Rola właściciela i posiadanie tytułu — jedno wejście na wątek encji. */
    private record LotusBuy(boolean owner, boolean owned) { }

    private void grantLotusTitle(@NotNull Player player, @NotNull UUID islandId) {
        titles.grant(islandId, LOTUS_TITLE, LOTUS_SOURCE).whenComplete((status, failure) ->
                runOnEntity(player, () -> {
                    if (failure != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Nie udało się nadać tytułu Władcy Lotosu wyspie " + islandId,
                                failure);
                        player.sendMessage(Ui.component(mm,
                                "<red>Nie udało się nadać tytułu — Lotosy zostały w ekwipunku. "
                                        + "Spróbuj ponownie.</red>"));
                        return;
                    }
                    if (status == IslandTitleService.GrantStatus.ALREADY_GRANTED) {
                        player.sendMessage(Ui.component(mm, LOTUS_ALREADY_OWNED));
                        return;
                    }
                    if (status != IslandTitleService.GrantStatus.GRANTED) {
                        plugin.getLogger().warning("Zlew Lotosów wyspy " + islandId
                                + " nie przyjął się (status " + status + ")");
                        player.sendMessage(Ui.component(mm,
                                "<red>Nie udało się nadać tytułu. Spróbuj ponownie.</red>"));
                        return;
                    }
                    int held = lotusCount(player);
                    if (held < LOTUS_COST) {
                        // Ekwipunek zmienił się, gdy zapis szedł w tle: tytuł cofamy,
                        // żeby nikt nie nosił go bez zapłaty.
                        titles.revoke(islandId, LOTUS_SOURCE).exceptionally(rollbackFailure -> {
                            plugin.getLogger().log(Level.SEVERE, "Tytuł Władcy Lotosu dla wyspy "
                                    + islandId + " został nadany, ale cofnięcie po zmianie "
                                    + "ekwipunku nie powiodło się — zdejmij go ręcznie",
                                    rollbackFailure);
                            return null;
                        });
                        player.sendMessage(Ui.component(mm,
                                "<red>Zabrakło Diamentowych Lotosów w ekwipunku — "
                                        + "tytuł nie został nadany.</red>"));
                        return;
                    }
                    Inventories.removeCustom(player.getInventory(),
                            SkyBlockTopRewardCoordinator.DIAMOND_LOTUS_ID, LOTUS_COST, customItems);
                    player.sendMessage(Ui.component(mm,
                            "<gold><bold>Wyspa otrzymała tytuł Władca Lotosu!</bold></gold> "
                                    + "<gray>Widać go w Centrum Wyspy.</gray>"));
                    /*
                     * Klucz dźwięku podany tekstem, nie stałą `Sound.*`: stałe
                     * `org.bukkit.Sound` inicjalizują rejestr Bukkit przy
                     * rozwiązaniu argumentu, więc każde dotknięcie tej ścieżki
                     * w teście bez MockBukkit (a takie są w tym module) kończy
                     * się NoClassDefFoundError. Zachowanie na serwerze jest to
                     * samo — `ui.toast.challenge_complete` to klucz waniliowy.
                     */
                    player.playSound(player.getLocation(), "ui.toast.challenge_complete", 1.0f, 1.2f);
                    open(player);
                }));
    }

    /** Komunikat o brakujących Lotosach — zawsze z liczbą, ile brakuje. */
    static @NotNull String lotusShortfallMessage(int held) {
        int missing = Math.max(0, LOTUS_COST - Math.max(0, held));
        return "<red>Potrzebujesz <white>" + LOTUS_COST
                + "</white> Diamentowych Lotosów w ekwipunku — masz <white>"
                + Math.max(0, held) + "</white>, brakuje <white>" + missing + "</white>.</red>";
    }

    static final String LOTUS_ALREADY_OWNED =
            "<gold>Ta wyspa ma już tytuł Władcy Lotosu — zlew jest jednorazowy.</gold>";

    /** Ekran potwierdzenia — prestiż jest nieodwracalnym wydatkiem z banku wyspy. */
    private void confirm(@NotNull Player player, @NotNull IslandView context,
                         @NotNull IslandPrestigeService.State state, long balance) {
        int target = state.level() + 1;
        MenuService.Menu menu = menus.ofRows(3, Ui.panelTitle(3, Ui.component(mm,
                "<gold><bold>Na pewno? Prestiż " + target + "</bold></gold>")));
        Ui.frame(menu, mm, Material.PURPLE_STAINED_GLASS_PANE);
        menu.decoration(SLOT_STATE, Ui.item(Material.PAPER, mm, "<yellow>Co się stanie</yellow>",
                List.of("<gray>• Z banku wyspy zniknie " + Ui.price(state.nextCost()) + "</gray>",
                        "<gray>• Bank po zakupie: <white>"
                                + Ui.money(Math.max(0L, balance - state.nextCost())) + "</white></gray>",
                        "<gray>• Wyspa pokaże prestiż <white>" + target + "</white>.</gray>",
                        cosmeticLine(target),
                        perkLine(state.level(), target),
                        "<gray>Prestiż nie daje monet ani punktów</gray>",
                        "<gray>sezonowych — to wydatkowy status.</gray>"), false));
        menu.set(11, Ui.item(Material.LIME_CONCRETE, mm, "<green><bold>TAK, PODNIEŚ PRESTIŻ</bold></green>",
                List.of("<gray>Kliknij, aby zapłacić z banku wyspy.</gray>",
                        "<red>Nie da się tego cofnąć.</red>"), true),
                (viewer, click) -> purchase(viewer, context, state.level()));
        menu.set(15, Ui.item(Material.RED_CONCRETE, mm, "<red><bold>ANULUJ</bold></red>",
                List.of("<gray>Nic nie zapłacisz — wracasz do menu prestiżu.</gray>"), true),
                (viewer, click) -> open(viewer));
        menu.open(player);
    }

    /**
     * Zakup: najpierw autorytatywna rola (async), potem serwis. Stan poziomu jest
     * porównywany z tym, który gracz widział na ekranie potwierdzenia — jeśli
     * ktoś zdążył kupić prestiż w międzyczasie, nic nie płacimy i odświeżamy ekran.
     */
    private void purchase(@NotNull Player player, @NotNull IslandView context, int expectedLevel) {
        UUID islandId = context.islandId();
        player.sendMessage(Ui.component(mm, "<gray>Sprawdzam uprawnienia i stan banku wyspy…</gray>"));
        player.closeInventory();
        authoritativeOwnerAsync(player.getUniqueId(), islandId).whenComplete((owner, roleFailure) ->
                runOnEntity(player, () -> {
                    if (roleFailure != null || !Boolean.TRUE.equals(owner)) {
                        if (roleFailure != null) {
                            plugin.getLogger().log(Level.WARNING,
                                    "Nie udało się potwierdzić roli właściciela wyspy " + islandId,
                                    roleFailure);
                        }
                        player.sendMessage(Ui.component(mm,
                                "<red>Prestiż podnosi tylko właściciel wyspy.</red>"));
                        return;
                    }
                    service.state(islandId).whenComplete((state, stateFailure) ->
                            runOnEntity(player, () -> {
                                if (stateFailure != null || state == null) {
                                    if (stateFailure != null) {
                                        plugin.getLogger().log(Level.WARNING,
                                                "Nie udało się odczytać stanu prestiżu przed zakupem",
                                                stateFailure);
                                    }
                                    player.sendMessage(Ui.component(mm,
                                            "<red>Nie udało się odczytać stanu prestiżu. Spróbuj ponownie.</red>"));
                                    return;
                                }
                                if (state.level() != expectedLevel) {
                                    player.sendMessage(Ui.component(mm,
                                            "<gray>Stan prestiżu zmienił się w międzyczasie — odświeżam ekran.</gray>"));
                                    open(player);
                                    return;
                                }
                                service.purchase(islandId).whenComplete((result, failure) ->
                                        runOnEntity(player, () -> {
                                            if (failure != null || result == null) {
                                                if (failure != null) {
                                                    plugin.getLogger().log(Level.WARNING,
                                                            "Zakup prestiżu wyspy " + islandId + " nie doszedł do skutku",
                                                            failure);
                                                }
                                                player.sendMessage(Ui.component(mm,
                                                        "<red>Nie udało się rozliczyć prestiżu. "
                                                                + "Sprawdź bank wyspy przed powtórzeniem.</red>"));
                                                return;
                                            }
                                            player.sendMessage(Ui.component(mm,
                                                    message(result),
                                                    Placeholder.unparsed("level", Integer.toString(result.level())),
                                                    Placeholder.unparsed("cost", Ui.money(result.cost()))));
                                            open(player);
                                        }));
                            }));
                }));
    }

    /**
     * Komunikat po polsku dla wyniku zakupu. Trzymany jako jedna funkcja, żeby
     * każdy status miał dokładnie jeden tekst — i żeby dal się sprawdzić bez GUI.
     */
    static @NotNull String message(@NotNull IslandPrestigeService.PurchaseResult result) {
        return switch (result.status()) {
            case PURCHASED -> "<green>Prestiż wyspy podniesiony na poziom <gold><level></gold>! "
                    + "Z banku wyspy zeszło <gold><cost></gold>.</green>";
            case ALREADY_PURCHASED -> "<gray>Ten prestiż był już opłacony — "
                    + "bank wyspy nie stracił drugi raz.</gray>";
            case NO_FUNDS -> "<red>Bank wyspy nie ma dość monet na ten prestiż. "
                    + "Potrzebujesz <gold><cost></gold>.</red>";
            case MAX_LEVEL -> "<gold>Wyspa ma już maksymalny prestiż (poziom <level>).</gold>";
        };
    }

    /**
     * Kafelek prestiżu w Centrum Wyspy: renderuje poziom z cache (bez bazy na
     * wątku regionu) i dociąga świeże wartości w tle, podmieniając kafel
     * w miejscu. Tytuł bierzemy z trwałego magazynu (migracja #13) — dzięki
     * temu widać go też na wyspach, którym sezon nadpisał tytuł poziomu.
     */
    public void renderTile(@NotNull Player viewer, @NotNull MenuService.Menu menu, int slot,
                           @NotNull UUID islandId) {
        setTile(menu, slot, islandId);
        service.state(islandId).whenComplete((state, failure) ->
                runOnEntity(viewer, () -> {
                    if (failure != null) {
                        plugin.getLogger().log(Level.FINE,
                                "Nie udało się odświeżyć kafelka prestiżu wyspy " + islandId, failure);
                        return;
                    }
                    setTile(menu, slot, islandId);
                }));
        titles.titleOf(islandId).whenComplete((title, failure) ->
                runOnEntity(viewer, () -> {
                    if (failure != null) {
                        plugin.getLogger().log(Level.FINE,
                                "Nie udało się odświeżyć tytułu wyspy " + islandId, failure);
                        return;
                    }
                    setTile(menu, slot, islandId);
                }));
    }

    private void setTile(@NotNull MenuService.Menu menu, int slot, @NotNull UUID islandId) {
        int level = service.cachedLevel(islandId).orElse(-1);
        boolean known = level >= 0;
        boolean maxed = known && level >= service.settings().maxLevel();
        String configTitle = known ? service.settings().titleFor(level) : null;
        String storedTitle = titles.cachedTitle(islandId).orElse(null);
        String title = storedTitle != null && !storedTitle.isBlank() ? storedTitle : configTitle;
        List<String> lore = new ArrayList<>(5);
        lore.add(known
                ? "<gray>Poziom prestiżu: <white>" + level + "</white></gray>"
                : "<gray>Poziom prestiżu: <white>sprawdzam…</white></gray>");
        if (title != null && !title.isBlank()) {
            lore.add("<gray>Tytuł wyspy: <white>" + title + "</white></gray>");
        }
        lore.add(maxed
                ? "<gold>Wyspa ma maksymalny prestiż.</gold>"
                : "<gray>Wydaj monety z banku wyspy na kosmetyczny tytuł.</gray>");
        lore.add(maxed
                ? "<dark_gray>Nic już nie zostało do kupienia.</dark_gray>"
                : "<dark_gray>Bez bonusów do zarobku.</dark_gray>");
        lore.add("<yellow>Kliknij, aby otworzyć.</yellow>");
        menu.set(slot, Ui.item(maxed ? Material.NETHER_STAR : Material.AMETHYST_CLUSTER, mm,
                        "<gold><bold>Prestiż wyspy</bold></gold>", List.copyOf(lore), known && maxed),
                (clicker, click) -> open(clicker));
    }

    /** Tytuł kosmetyczny dla docelowego poziomu (albo uczciwa informacja, że go nie ma). */
    private @NotNull String cosmeticLine(int level) {
        String title = service.settings().titleFor(level);
        return title == null || title.isBlank()
                ? "<gray>Nagroda: wpis prestiżu w Centrum Wyspy.</gray>"
                : "<gray>Nagroda: tytuł wyspy <white>" + title + "</white>.</gray>";
    }

    /**
     * Linia mechanicznych perków docelowego poziomu: pokazuje tylko to, co
     * naprawdę urośnie (slot minionka przy progu, % szczęścia na kryształy).
     */
    private @NotNull String perkLine(int from, int target) {
        IslandPrestige.Settings s = service.settings();
        StringBuilder sb = new StringBuilder("<gray>Perki:");
        boolean any = false;
        int newSlots = s.extraMinionSlots(target) - s.extraMinionSlots(from);
        if (newSlots > 0) {
            sb.append(" <white>+").append(newSlots)
                    .append(newSlots == 1 ? " slot minionka" : " slotów minionków").append("</white>,");
            any = true;
        }
        double luckDelta = s.crystalLuckPercentPerLevel();
        if (luckDelta > 0) {
            sb.append(" <white>+").append(String.format(java.util.Locale.ROOT, "%.0f%%", luckDelta))
                    .append(" szczęścia na kryształy</white>");
            any = true;
        }
        double sizeDelta = s.sizeStepPercentPerLevel();
        if (sizeDelta > 0) {
            sb.append(" <white>+").append(String.format(java.util.Locale.ROOT, "%.0f%%", sizeDelta))
                    .append(" rozmiaru wyspy</white>");
            any = true;
        }
        if (s.grantsMemberSlot(target)) {
            sb.append(" <white>+1 slot członka wyspy</white>");
            any = true;
        }
        return any ? sb.toString() : "<gray>Perki: bez nowych bonusów na tym poziomie.</gray>";
    }

    /**
     * Ikona stanu prestiżu. Linia tytułu pokazuje tytuł, który wyspa
     * <b>naprawdę</b> nosi (magazyn z migracji #13); dla wyspy sprzed migracji
     * (bez wiersza) spada do tytułu przypisanego temu poziomowi w configu.
     */
    private @NotNull org.bukkit.inventory.ItemStack stateIcon(
            @NotNull IslandPrestigeService.State state, @NotNull UUID islandId) {
        String configTitle = state.title();
        String storedTitle = titles.cachedTitle(islandId).orElse(null);
        String title = storedTitle != null && !storedTitle.isBlank() ? storedTitle : configTitle;
        return Ui.item(Material.BEACON, mm, "<gold>Poziom prestiżu: <white>"
                        + state.level() + "</white></gold>",
                List.of("<gray>Wydane z banku wyspy: " + Ui.price(state.spentMinor()) + "</gray>",
                        state.maxed()
                                ? "<gold>To najwyższy poziom.</gold>"
                                : "<gray>Następny poziom: <white>" + (state.level() + 1)
                                + "</white> za " + Ui.price(state.nextCost()) + "</gray>",
                        title == null || title.isBlank()
                                ? "<dark_gray>Brak tytułu dla tego poziomu.</dark_gray>"
                                : "<gray>Tytuł wyspy: <white>" + title + "</white></gray>"), false);
    }

    /** AUTHORITATIVE_ASYNC: rola właściciela poza wątkiem regionu, fail-closed przy braku odpowiedzi. */
    private @NotNull CompletableFuture<Boolean> authoritativeOwnerAsync(
            @NotNull UUID playerId, @NotNull UUID islandId) {
        CompletableFuture<Boolean> decision = new CompletableFuture<>();
        try {
            var scheduled = plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> {
                try {
                    decision.complete(skyllia.authoritativeRole(playerId, islandId) == IslandRole.OWNER);
                } catch (RuntimeException failure) {
                    plugin.getLogger().log(Level.WARNING,
                            "Autorytatywne sprawdzenie roli dla prestiżu nie powiodło się", failure);
                    decision.complete(false);
                }
            });
            if (scheduled == null) {
                decision.complete(false);
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.WARNING,
                    "Zaplanowanie sprawdzenia roli dla prestiżu zostało odrzucone", rejected);
            decision.complete(false);
        }
        return decision.completeOnTimeout(false, AUTHORIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** FOLIA-THREADING: wiadomości i otwieranie menu tylko na wątku encji gracza. */
    private void runOnEntity(@NotNull Player player, @NotNull Runnable action) {
        runOnEntity(player, action, null);
    }

    /**
     * @param abandoned sprzątanie, gdy gracz wyszedł albo scheduler odrzucił
     *                  zadanie — bez tego blokada „menu się otwiera” zostałaby
     *                  u gracza na zawsze i każde kolejne otwarcie kończyłoby
     *                  się samym „Odświeżam stan prestiżu…”.
     */
    private void runOnEntity(@NotNull Player player, @NotNull Runnable action,
                             @Nullable Runnable abandoned) {
        try {
            var scheduled = player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) {
                    action.run();
                }
            }, abandoned);
            if (scheduled == null) {
                plugin.getLogger().log(Level.FINE,
                        "Pominięto akcję prestiżu — gracz " + player.getUniqueId() + " wyszedł");
                if (abandoned != null) {
                    abandoned.run();
                }
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.WARNING,
                    "Zaplanowanie akcji prestiżu dla " + player.getUniqueId() + " zostało odrzucone",
                    rejected);
            if (abandoned != null) {
                abandoned.run();
            }
        }
    }
}
