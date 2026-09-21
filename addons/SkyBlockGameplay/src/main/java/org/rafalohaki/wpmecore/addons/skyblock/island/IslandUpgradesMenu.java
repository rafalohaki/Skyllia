package org.rafalohaki.wpmecore.addons.skyblock.island;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Menu Ulepszeń Wyspy i kafelek w Centrum Wyspy — średniotorowa progresja
 * za monety z BANKU WYSPY (rozmiar, miejsca w zespole, sloty minionków).
 *
 * <p>FOLIA-THREADING: menu otwiera się na wątku encji gracza, a każdy odczyt
 * z bazy (stany torów, saldo banku wyspy) leci asynchronicznie i wraca na
 * wątek encji — na wątku regionu nie ma ani jednego blokującego zapytania.
 * Rolę właściciela potwierdzamy autorytatywnie przez async scheduler przed
 * debetem, tak jak bank wyspy przy wypłacie i menu prestiżu.
 *
 * <p>Kafelek renderuje sumę poziomów z cache serwisu (bez bazy na wątku
 * regionu) i dociąga świeże wartości w tle; brak wstrzyknięcia = kafelek się
 * nie pokazuje — dokładnie jak kafelek prestiżu bez sekcji w configu.
 */
public final class IslandUpgradesMenu {

    private static final long AUTHORIZATION_TIMEOUT_SECONDS = 5L;
    private static final int SLOT_BANK = 13;
    private static final int SLOT_BACK = 36;
    private static final int SLOT_CLOSE = 40;
    /** Tory na ekranie — kolejność stabilna: rozmiar, zespół, minionki. */
    private static final Map<IslandUpgrades.Track, Integer> TRACK_SLOTS = Map.of(
            IslandUpgrades.Track.SIZE, 20,
            IslandUpgrades.Track.MEMBERS, 22,
            IslandUpgrades.Track.MINIONS, 24);
    private static final int CONFIRM_INFO = 13;
    private static final int CONFIRM_YES = 11;
    private static final int CONFIRM_NO = 15;

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage mm;
    private final SkylliaIntegration skyllia;
    private final LedgerService ledger;
    private final IslandUpgradesService service;
    private final IslandCenterMenu center;
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();

    public IslandUpgradesMenu(@NotNull JavaPlugin plugin, @NotNull MenuService menus,
                              @NotNull MiniMessage mm, @NotNull SkylliaIntegration skyllia,
                              @NotNull LedgerService ledger, @NotNull IslandUpgradesService service,
                              @NotNull IslandCenterMenu center) {
        this.plugin = plugin;
        this.menus = menus;
        this.mm = mm;
        this.skyllia = skyllia;
        this.ledger = ledger;
        this.service = service;
        this.center = center;
    }

    /** Otwiera menu ulepszeń graczowi, który ma wyspę; saldo i poziomy czytane są w tle. */
    public void open(@NotNull Player player) {
        IslandView context = skyllia.islandOf(player.getUniqueId()).orElse(null);
        if (context == null) {
            player.closeInventory();
            player.sendMessage(Ui.component(mm,
                    "<red>Najpierw utwórz wyspę komendą <yellow>/is</yellow>.</red>"));
            return;
        }
        if (!opening.add(player.getUniqueId())) {
            player.sendActionBar(Ui.component(mm, "<gray>Odświeżam stan ulepszeń…</gray>"));
            return;
        }
        UUID expectedIslandId = context.islandId();
        Runnable releaseOpening = () -> this.opening.remove(player.getUniqueId());
        service.states(expectedIslandId)
                .thenCombine(ledger.authoritativeIslandBalance(expectedIslandId), Opening::new)
                .whenComplete((opening, failure) -> runOnEntity(player, () -> {
                    releaseOpening.run();
                    if (failure != null || opening == null) {
                        if (failure != null) {
                            plugin.getLogger().log(Level.WARNING,
                                    "Nie udało się odczytać stanu ulepszeń wyspy " + expectedIslandId,
                                    failure);
                        }
                        player.sendMessage(Ui.component(mm,
                                "<red>Nie udało się odczytać stanu ulepszeń. Spróbuj ponownie.</red>"));
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

    private record Opening(@NotNull Map<IslandUpgrades.Track, IslandUpgradesService.TrackState> states,
                           long balance) {
    }

    private void openNow(@NotNull Player player, @NotNull IslandView context,
                         @NotNull Opening opening) {
        MenuService.Menu menu = menus.ofRows(5, Ui.panelTitle(5, Ui.component(mm,
                "<gold><bold>Ulepszenia wyspy</bold></gold>")));
        Ui.frame(menu, mm, Material.CYAN_STAINED_GLASS_PANE);
        menu.decoration(SLOT_BANK, Ui.item(Material.GOLD_BLOCK, mm, "<gold>Bank wyspy</gold>",
                List.of(Ui.price(opening.balance()),
                        "<gray>Ulepszenia płacisz z konta wyspy —</gray>",
                        "<gray>nie z własnego portfela.</gray>",
                        "<gray>Wpłacają członkowie, kupuje właściciel.</gray>"), false));
        boolean owner = context.role() == IslandRole.OWNER;
        for (Map.Entry<IslandUpgrades.Track, Integer> entry : TRACK_SLOTS.entrySet()) {
            IslandUpgradesService.TrackState state = opening.states().get(entry.getKey());
            if (state == null) {
                continue; // tor wyłączony w configu — kafelka nie ma
            }
            renderTrackTile(menu, context, state, opening.balance(), owner, entry.getValue());
        }
        menu.set(SLOT_BACK, Ui.backButton(mm), (viewer, click) -> center.open(viewer));
        menu.close(SLOT_CLOSE, Ui.closeButton(mm));
        menu.open(player);
    }

    /**
     * Kafel jednego toru. Nie-właściciel widzi stan wyspy, ale zakup jest
     * zarezerwowany dla właściciela — kafel wtedy jest dekoracją, a nie
     * pułapką na cudze monety.
     */
    private void renderTrackTile(@NotNull MenuService.Menu menu, @NotNull IslandView context,
                                 @NotNull IslandUpgradesService.TrackState state,
                                 long balance, boolean owner, int slot) {
        IslandUpgrades.Track track = state.track();
        IslandUpgrades.TrackSettings settings = service.settings().track(track);
        if (settings == null) {
            return;
        }
        String label = trackLabel(track);
        if (!owner) {
            menu.decoration(slot, Ui.item(Material.GRAY_CONCRETE, mm,
                    "<gray><bold>" + label + ": poziom " + state.level() + "</bold></gray>",
                    List.of("<gray>Widzisz stan wyspy, ale zakupu może</gray>",
                            "<gray>dokonać wyłącznie właściciel.</gray>",
                            effectLine(track, settings)), false));
            return;
        }
        if (state.maxed()) {
            menu.decoration(slot, Ui.item(Material.NETHER_STAR, mm,
                    "<gold><bold>" + label + "</bold></gold>",
                    List.of("<gray>Poziom: <white>" + state.level() + "/" + settings.maxLevel()
                                    + "</white></gray>",
                            effectLine(track, settings),
                            "<gold>Ten tor osiągnął maksimum.</gold>"), true));
            return;
        }
        if (balance < state.nextCost()) {
            long missing = state.nextCost() - balance;
            menu.set(slot, Ui.item(Material.RED_CONCRETE, mm,
                            "<red><bold>" + label + ": za mało monet</bold></red>",
                            List.of("<gray>Poziom: <white>" + state.level() + "/"
                                            + settings.maxLevel() + "</white></gray>",
                                    "<gray>Następny: " + Ui.price(state.nextCost()) + "</gray>",
                                    "<gray>Brakuje: <red>" + Ui.money(missing) + "</red></gray>",
                                    effectLine(track, settings),
                                    "<gray>Wpłać monety do banku wyspy i wróć.</gray>"), false),
                    (viewer, click) -> viewer.sendMessage(Ui.component(mm,
                            "<red>Bank wyspy nie ma dość monet na to ulepszenie. "
                                    + "Brakuje <gold><amount></gold>.</red>",
                            Placeholder.unparsed("amount", Ui.money(missing)))));
            return;
        }
        menu.set(slot, Ui.item(trackIcon(track), mm,
                        "<green><bold>" + label + "</bold></green>",
                        List.of("<gray>Poziom: <white>" + state.level() + "/"
                                        + settings.maxLevel() + "</white></gray>",
                                "<gray>Koszt awansu: " + Ui.price(state.nextCost()) + "</gray>",
                                "<gray>Zapłata: bank wyspy.</gray>",
                                effectLine(track, settings),
                                "<yellow>Kliknij, aby potwierdzić.</yellow>"), false),
                (viewer, click) -> confirm(viewer, context, state));
    }

    /**
     * Ekran potwierdzenia (3 rzędy, jak w prestiżu): informacja w środku,
     * TAK po lewej, ANULUJ po prawej. Bez tego kroku kliknięcie w kafel
     * toru od razu zdejmowałoby monety z banku wyspy.
     */
    private void confirm(@NotNull Player player, @NotNull IslandView context,
                         @NotNull IslandUpgradesService.TrackState state) {
        IslandUpgrades.TrackSettings settings = service.settings().track(state.track());
        MenuService.Menu menu = menus.ofRows(3, Ui.panelTitle(3, Ui.component(mm,
                "<gold><bold>Potwierdź ulepszenie</bold></gold>")));
        Ui.frame(menu, mm, Material.CYAN_STAINED_GLASS_PANE);
        menu.decoration(CONFIRM_INFO, Ui.item(trackIcon(state.track()), mm,
                "<white><bold>" + trackLabel(state.track()) + " "
                        + state.level() + " → " + (state.level() + 1) + "</bold></white>",
                List.of("<gray>Koszt: " + Ui.price(state.nextCost()) + "</gray>",
                        "<gray>Zapłata: bank wyspy.</gray>",
                        settings == null ? "<dark_gray>—</dark_gray>" : effectLine(state.track(), settings)),
                false));
        menu.set(CONFIRM_YES, Ui.item(Material.LIME_CONCRETE, mm,
                        "<green><bold>Tak, kup ulepszenie</bold></green>",
                        List.of("<gray>Monety zejdą z banku wyspy.</gray>"), false),
                (viewer, click) -> attemptBuy(viewer, context.islandId(), state.track(),
                        state.level()));
        menu.set(CONFIRM_NO, Ui.item(Material.RED_CONCRETE, mm,
                        "<red><bold>Anuluj</bold></red>",
                        List.of("<gray>Wróć do listy ulepszeń.</gray>"), false),
                (viewer, click) -> open(viewer));
        menu.open(player);
    }

    /**
     * Zakup: najpierw autorytatywna rola właściciela (async), potem świeży
     * poziom toru (gracz mógł patrzeć na nieaktualny ekran) i dopiero wtedy
     * {@code service.purchase} — serwis i tak deduplikuje transakcję ledgerem,
     * ale świeży poziom daje uczciwy komunikat zamiast ślepego retry.
     */
    private void attemptBuy(@NotNull Player player, @NotNull UUID islandId,
                            @NotNull IslandUpgrades.Track track, int expectedLevel) {
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
                                "<red>Ulepszenia wyspy kupuje tylko właściciel.</red>"));
                        return;
                    }
                    service.state(islandId, track).whenComplete((state, stateFailure) ->
                            runOnEntity(player, () -> {
                                if (stateFailure != null || state == null) {
                                    if (stateFailure != null) {
                                        plugin.getLogger().log(Level.WARNING,
                                                "Nie udało się odczytać toru " + track.dbId()
                                                        + " wyspy " + islandId + " przed zakupem",
                                                stateFailure);
                                    }
                                    player.sendMessage(Ui.component(mm,
                                            "<red>Nie udało się odczytać stanu ulepszenia. Spróbuj ponownie.</red>"));
                                    return;
                                }
                                if (state.level() != expectedLevel) {
                                    player.sendMessage(Ui.component(mm,
                                            "<gray>Stan ulepszenia zmienił się w międzyczasie — odświeżam ekran.</gray>"));
                                    open(player);
                                    return;
                                }
                                service.purchase(islandId, track).whenComplete((result, failure) ->
                                        runOnEntity(player, () -> {
                                            if (failure != null || result == null) {
                                                if (failure != null) {
                                                    plugin.getLogger().log(Level.WARNING,
                                                            "Zakup ulepszenia " + track.dbId()
                                                                    + " wyspy " + islandId
                                                                    + " nie doszedł do skutku",
                                                            failure);
                                                }
                                                player.sendMessage(Ui.component(mm,
                                                        "<red>Nie udało się rozliczyć ulepszenia. "
                                                                + "Sprawdź bank wyspy przed powtórzeniem.</red>"));
                                                return;
                                            }
                                            player.sendMessage(Ui.component(mm,
                                                    message(result),
                                                    Placeholder.unparsed("track",
                                                            trackLabel(result.track())),
                                                    Placeholder.unparsed("level",
                                                            Integer.toString(result.level())),
                                                    Placeholder.unparsed("cost",
                                                            Ui.money(result.cost()))));
                                            open(player);
                                        }));
                            }));
                }));
    }

    /**
     * Komunikat po polsku dla wyniku zakupu — każdy status ma dokładnie jeden
     * tekst, żeby dał się sprawdzić bez GUI.
     */
    static @NotNull String message(@NotNull IslandUpgradesService.PurchaseResult result) {
        return switch (result.status()) {
            case PURCHASED -> "<green><track>: poziom <gold><level></gold>! "
                    + "Z banku wyspy zeszło <gold><cost></gold>.</green>";
            case ALREADY_PURCHASED -> "<gray>To ulepszenie było już opłacone — "
                    + "bank wyspy nie stracił drugi raz.</gray>";
            case NO_FUNDS -> "<red>Bank wyspy nie ma dość monet na to ulepszenie. "
                    + "Potrzebujesz <gold><cost></gold>.</red>";
            case MAX_LEVEL -> "<gold><track> ma już maksymalny poziom (<level>).</gold>";
        };
    }

    /**
     * Kafelek ulepszeń w Centrum Wyspy: renderuje sumę poziomów z cache (bez
     * bazy na wątku regionu) i dociąga świeże wartości w tle, podmieniając
     * kafel w miejscu — ten sam kontrakt co kafelek prestiżu.
     */
    public void renderTile(@NotNull Player viewer, @NotNull MenuService.Menu menu, int slot,
                           @NotNull UUID islandId) {
        setTile(menu, slot, islandId);
        service.states(islandId).whenComplete((states, failure) ->
                runOnEntity(viewer, () -> {
                    if (failure != null) {
                        plugin.getLogger().log(Level.FINE,
                                "Nie udało się odświeżyć kafelka ulepszeń wyspy " + islandId, failure);
                        return;
                    }
                    setTile(menu, slot, islandId);
                }));
    }

    private void setTile(@NotNull MenuService.Menu menu, int slot, @NotNull UUID islandId) {
        int total = 0;
        int maxed = 0;
        boolean anyKnown = false;
        for (IslandUpgrades.Track track : TRACK_SLOTS.keySet()) {
            IslandUpgrades.TrackSettings settings = service.settings().track(track);
            if (settings == null) {
                continue;
            }
            int level = service.cachedLevel(islandId, track).orElse(-1);
            if (level >= 0) {
                anyKnown = true;
                total += level;
                if (level >= settings.maxLevel()) {
                    maxed++;
                }
            }
        }
        List<String> lore = new ArrayList<>(5);
        lore.add(anyKnown
                ? "<gray>Kupione poziomy: <white>" + total + "</white></gray>"
                : "<gray>Kupione poziomy: <white>sprawdzam…</white></gray>");
        lore.add("<gray>Rozmiar wyspy, miejsca w zespole,</gray>");
        lore.add("<gray>sloty minionków — za monety z banku.</gray>");
        lore.add(maxed > 0 && maxed == TRACK_SLOTS.size()
                ? "<gold>Wszystkie tory na maksimum.</gold>"
                : "<dark_gray>Tańsza alternatywa dla prestiżu.</dark_gray>");
        lore.add("<yellow>Kliknij, aby otworzyć.</yellow>");
        menu.set(slot, Ui.item(Material.EMERALD, mm,
                        "<aqua><bold>Ulepszenia wyspy</bold></aqua>", List.copyOf(lore), false),
                (clicker, click) -> open(clicker));
    }

    /** Ikona i efekt toru — jedno miejsce, żeby kafel i potwierdzenie mówiły to samo. */
    private static @NotNull Material trackIcon(@NotNull IslandUpgrades.Track track) {
        return switch (track) {
            case SIZE -> Material.OAK_FENCE;
            case MEMBERS -> Material.PLAYER_HEAD;
            case MINIONS -> Material.HOPPER;
        };
    }

    private static @NotNull String trackLabel(@NotNull IslandUpgrades.Track track) {
        return switch (track) {
            case SIZE -> "Rozmiar wyspy";
            case MEMBERS -> "Miejsca w zespole";
            case MINIONS -> "Sloty minionków";
        };
    }

    /** Linia efektu poziomu — tylko to, co naprawdę urośnie przy awansie. */
    private static @NotNull String effectLine(@NotNull IslandUpgrades.Track track,
                                              @NotNull IslandUpgrades.TrackSettings settings) {
        return switch (track) {
            case SIZE -> "<gray>Efekt: <white>+" + String.format(java.util.Locale.ROOT, "%.0f%%",
                    settings.percentPerLevel()) + " rozmiaru wyspy</white> za poziom.</gray>";
            case MEMBERS -> "<gray>Efekt: <white>+" + settings.slotsPerLevel()
                    + " miejsce w zespole</white> za poziom.</gray>";
            case MINIONS -> "<gray>Efekt: <white>+" + settings.slotsPerLevel()
                    + " slot minionków</white> za poziom.</gray>";
        };
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
                            "Autorytatywne sprawdzenie roli dla ulepszeń nie powiodło się", failure);
                    decision.complete(false);
                }
            });
            if (scheduled == null) {
                decision.complete(false);
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.WARNING,
                    "Zaplanowanie sprawdzenia roli dla ulepszeń zostało odrzucone", rejected);
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
     *                  zadanie — bez tego blokada „menu się otwiera" zostałaby
     *                  u gracza na zawsze i każde kolejne otwarcie kończyłoby
     *                  się samym „Odświeżam stan ulepszeń…".
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
                        "Pominięto akcję ulepszeń — gracz " + player.getUniqueId() + " wyszedł");
                if (abandoned != null) {
                    abandoned.run();
                }
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.WARNING,
                    "Zaplanowanie akcji ulepszeń dla " + player.getUniqueId() + " zostało odrzucone",
                    rejected);
            if (abandoned != null) {
                abandoned.run();
            }
        }
    }
}
