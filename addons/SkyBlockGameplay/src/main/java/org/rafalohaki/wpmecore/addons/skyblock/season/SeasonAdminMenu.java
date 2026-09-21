package org.rafalohaki.wpmecore.addons.skyblock.season;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.api.service.SqlService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Panel operatora sezonów ({@code /sezon admin}) — ekran A.
 *
 * <p>Zero logiki mutacji: zamknięcie idzie wyłącznie przez wstrzyknięty
 * {@code closer} (ta sama ścieżka co {@code /sezon zamknij --confirm}),
 * przeładowanie edycji to ponowny odczyt przez {@code editionsSupplier},
 * podgląd to dry-run na istniejącym serwisie. Liczby wewnętrzne (S&lt;n&gt;)
 * pokazywane tylko jako stopka dark_gray — ta powierzchnia jest operatorska.
 */
public final class SeasonAdminMenu {

    /** Legenda panelu. */
    static final int SLOT_HEADER = 4;
    /** Aktywna edycja (CLOCK). */
    static final int SLOT_ACTIVE = 13;
    /** Nadchodzące edycje: siatka 19..25 (≤6 kart + licznik nadmiaru na 25). */
    static final int FIRST_UPCOMING_SLOT = 19;
    static final int LAST_UPCOMING_SLOT = 25;
    /** Rząd separatorek między treścią a stopką. */
    static final int SEPARATOR_ROW = 4;
    static final int SLOT_HISTORY = 46;
    static final int SLOT_DRY_RUN = 48;
    static final int SLOT_CLOSE_SEASON = 49;
    static final int SLOT_RELOAD = 51;
    static final int SLOT_CLOSE_UI = 53;

    private static final String TITLE = "<gold><bold>Panel Sezonów</bold></gold>";
    private static final int MAX_SHOWN_UPCOMING = 6;

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage miniMessage;
    private final IntSupplier currentSeason;
    private final LongSupplier seasonEndMillis;
    private final Supplier<String> headerLabelSupplier;
    private final Supplier<String> rangeDetailSupplier;
    private final BiFunction<Boolean, Boolean,
            java.util.concurrent.CompletableFuture<SeasonEndService.CloseOutcome>> closer;
    private final Supplier<EditionRegistry> editionsSupplier;
    @SuppressWarnings("unused") /* rezerwa na wzbogacenie kart historii; SQL idzie przez pager. */
    private final SeasonEditionService editionService;
    @SuppressWarnings("unused")
    private final SqlService sql;
    @SuppressWarnings("unused")
    private final UnaryOperator<String> nickResolver;

    /** Wywoływany przed re-renderem slotu 51: przeładowanie edycji z konfiguracji. */
    private @Nullable Runnable reloadHook;

    /** Ochrona przed podwójnym otwarciem (wzorzec {@code SeasonPassMenu}). */
    private final Map<UUID, Boolean> opening = new ConcurrentHashMap<>();

    @Nullable
    private SeasonCloseConfirmMenu confirmMenu;
    @Nullable
    private SeasonHistoryPagerMenu historyMenu;

    public SeasonAdminMenu(@NotNull JavaPlugin plugin,
                           @NotNull MenuService menus,
                           @NotNull MiniMessage miniMessage,
                           @NotNull IntSupplier currentSeason,
                           @NotNull LongSupplier seasonEndMillis,
                           @NotNull Supplier<String> headerLabelSupplier,
                           @NotNull Supplier<String> rangeDetailSupplier,
                           @NotNull BiFunction<Boolean, Boolean,
                                   java.util.concurrent.CompletableFuture<SeasonEndService.CloseOutcome>> closer,
                           @NotNull Supplier<EditionRegistry> editionsSupplier,
                           @NotNull SeasonEditionService editionService,
                           @NotNull SqlService sql,
                           @NotNull UnaryOperator<String> nickResolver) {
        this.plugin = plugin;
        this.menus = menus;
        this.miniMessage = miniMessage;
        this.currentSeason = currentSeason;
        this.seasonEndMillis = seasonEndMillis;
        this.headerLabelSupplier = headerLabelSupplier;
        this.rangeDetailSupplier = rangeDetailSupplier;
        this.closer = closer;
        this.editionsSupplier = editionsSupplier;
        this.editionService = editionService;
        this.sql = sql;
        this.nickResolver = nickResolver;
    }

    /**
     * Rejestruje hak wywoływany PRZED re-renderem po kliknięciu slotu 51
     * („Przeładuj edycje”). Semantyka (okablowanie w SkyBlockGameplay):
     * ponowny odczyt editions.yml → {@code SeasonEditionService.swapRegistry}
     * → {@code refreshActivation}. Null = brak haka (panel nadal działa).
     */
    public void setReloadHook(@Nullable Runnable hook) {
        this.reloadHook = hook;
    }

    /** Otwiera panel na wątku encji gracza; podwójne otwarcie ignorowane. */
    public void open(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        if (opening.putIfAbsent(uuid, true) != null) {
            return;
        }
        try {
            var scheduled = player.getScheduler().run(plugin, ignored -> {
                opening.remove(uuid);
                buildAndOpen(player);
            }, () -> opening.remove(uuid));
            if (scheduled == null) {
                opening.remove(uuid);
            }
        } catch (RuntimeException rejected) {
            opening.remove(uuid);
            plugin.getLogger().fine("Sezony admin: harmonogram otwarcia odrzucony: " + rejected);
            buildAndOpen(player);
        }
    }

    void buildAndOpen(@NotNull Player player) {
        MenuService.Menu menu = newPanelMenu();
        populate(menu, player);
        menu.open(player);
    }

    /**
     * Buduje i wypełnia panel BEZ otwarcia — package-private dla testów
     * layoutu (menu przechwytuje wstrzyknięty {@link MenuService}).
     */
    void populate(@NotNull Player player) {
        MenuService.Menu menu = newPanelMenu();
        populate(menu, player);
    }

    private @NotNull MenuService.Menu newPanelMenu() {
        return menus.ofRows(6, Ui.component(miniMessage, TITLE));
    }

    /** Wypełnia sloty panelu; package-private dla testów layoutu. */
    void populate(@NotNull MenuService.Menu menu, @NotNull Player player) {
        Ui.frame(menu, miniMessage, Material.LIGHT_BLUE_STAINED_GLASS_PANE);

        menu.decoration(SLOT_HEADER, Ui.item(Material.BOOK, miniMessage,
                "<aqua><bold>Panel sezonów</bold></aqua>",
                List.of(
                        "<gray>Aktywna edycja i operacje operatorskie.</gray>",
                        "<gray>Zamykanie jest wyłącznie ręczne.</gray>"),
                false));

        populateActive(menu);
        populateUpcoming(menu);
        populateFooter(menu);

        menu.close(SLOT_CLOSE_UI, Ui.closeButton(miniMessage));
    }

    /** Slot 13: aktywna edycja — nazwa + okno + dni do końca + wewn. id. */
    private void populateActive(@NotNull MenuService.Menu menu) {
        long now = System.currentTimeMillis();
        long end = seasonEndMillis.getAsLong();
        boolean expired = now >= end;
        long daysLeft = SeasonSchedule.daysUntil(end, now);

        List<String> lore = new ArrayList<>();
        // Każdy element lore budujemy jako jawny String — żadnego przepływu
        // obiektów/komponentów do Ui.item (artefakt „[object object]” w lore
        // slotu AKTYWNA pochodził z pustej linii "" deserializowanej przez
        // MiniMessage do komponentu bez tekstu; tu gwarantujemy same napisy).
        // Dodatkowo: każdy ogon po stylowanym segmencie dostaje WŁASNY tag
        // (<gray> dni.</gray>) — goły literał jako ostatni sibling trafia do
        // protokołu 1.21.5+ jako goły string i ViaVersion zwraca go klientom
        // 1.21.4 jako zdeformowany komponent {"" : "..."} (render: pusto /
        // „[object Object]” u parserów).
        String range = blankToNull(String.valueOf(rangeDetailSupplier.get()));
        if (range != null) {
            lore.add("<gray>Okno: <white>" + range + "</white></gray>");
        }
        lore.add(expired
                ? "<yellow>⚠ wygasła — czeka na zamknięcie</yellow>"
                : "<gray>Koniec za <white>" + daysLeft + "</white><gray> dni.</gray>");
        // Pusty wiersz wizualnej przerwy jako spacja — nigdy goły "".
        lore.add(expired ? "<dark_gray> </dark_gray>" : "<green>AKTYWNA</green>");
        lore.add("<dark_gray>wewn. id: S" + currentSeason.getAsInt() + "</dark_gray>");

        String header = blankToNull(String.valueOf(headerLabelSupplier.get()));

        menu.decoration(SLOT_ACTIVE, Ui.item(Material.CLOCK, miniMessage,
                "<gold><bold>" + (header != null ? header : "Sezon bieżący") + "</bold></gold>",
                List.copyOf(lore), true));
    }

    /**
     * Sloty 19..25: nadchodzące edycje z rejestru — iteracja po liście
     * SORTOWANEJ PO STARCIE względem „teraz” (a nie łańcuch
     * {@code nextAfter(koniec-1)}), dzięki czemu okna ZAGNIEŻDŻONE wewnątrz
     * dłuższych zakresów (EVENT w REGULAR) też wychodzą na kartach.
     * REGULAR jako PAPER, EVENT jako AMETHYST_SHARD. Więcej niż sześć kart —
     * ostatni slot pokazuje licznik nadmiaru.
     */
    private void populateUpcoming(@NotNull MenuService.Menu menu) {
        EditionRegistry registry = registryOrNull();
        if (registry == null || !registry.isEnabled()) {
            for (int slot = FIRST_UPCOMING_SLOT; slot <= LAST_UPCOMING_SLOT; slot++) {
                menu.decoration(slot, Ui.item(Material.GRAY_DYE, miniMessage,
                        "<dark_gray>Kalendarz edycji wyłączony</dark_gray>",
                        List.of("<gray>Włącz edycje w editions.yml.</gray>"), false));
            }
            return;
        }
        long now = System.currentTimeMillis();
        // Wszystkie przyszłe okna naraz; zagnieżdżone EVENT-y nie giną.
        List<Edition> allUpcoming = registry.upcomingFrom(now);
        boolean overflow = allUpcoming.size() > MAX_SHOWN_UPCOMING;
        List<Edition> upcoming = overflow
                ? allUpcoming.subList(0, MAX_SHOWN_UPCOMING)
                : allUpcoming;
        int slot = FIRST_UPCOMING_SLOT;
        for (Edition edition : upcoming) {
            menu.decoration(slot++, upcomingIcon(edition, registry));
        }
        while (slot <= LAST_UPCOMING_SLOT) {
            menu.decoration(slot++, fillerIcon());
        }
        if (overflow) {
            // Reszta bez trzymania jej na slotach — licznik liczony z tej
            // samej listy, więc zagnieżdżone okna się w nim liczą.
            int hidden = allUpcoming.size() - MAX_SHOWN_UPCOMING;
            menu.decoration(LAST_UPCOMING_SLOT, Ui.item(Material.GRAY_DYE, miniMessage,
                    "<gray>+" + hidden + " dalszych edycji…</gray>",
                    List.of(
                            "<gray>Pozostałe okna zobaczysz w historii,</gray>",
                            "<gray>gdy zostaną zamknięte.</gray>"),
                    false));
        }
    }

    private @NotNull ItemStack upcomingIcon(@NotNull Edition edition,
                                            @NotNull EditionRegistry registry) {
        boolean event = edition.type() == Edition.Type.EVENT;
        Material material = event ? Material.AMETHYST_SHARD : Material.PAPER;
        String color = event ? "light_purple" : "yellow";
        long now = System.currentTimeMillis();
        boolean notStarted = edition.startInclusiveMillis() > now;
        long daysUntilStart = Math.max(0L, SeasonSchedule.daysUntil(
                edition.startInclusiveMillis(), now));
        String detail = registry.rangeDetail(edition);
        List<String> lore = new ArrayList<>();
        // Jawne napisy — żadnych surowych obiektów w lore.
        lore.add("<gray>Rodzaj: <white>" + edition.type().name() + "</white></gray>");
        lore.add("<gray>Okno: <white>" + String.valueOf(detail) + "</white></gray>");
        if (notStarted) {
            lore.add("<gray>Start za <white>" + daysUntilStart + "</white><gray> dni.</gray>");
        }
        return Ui.item(material, miniMessage,
                "<" + color + "><bold>" + String.valueOf(edition.displayName())
                        + "</bold></" + color + ">",
                List.copyOf(lore), false);
    }

    private @NotNull ItemStack fillerIcon() {
        return Ui.item(Material.GRAY_DYE, miniMessage,
                "<dark_gray>—</dark_gray>", List.of(), false);
    }

    /** Stopka: historia, podgląd dry-run, wejście w zamknięcie, reload. */
    private void populateFooter(@NotNull MenuService.Menu menu) {
        Ui.separatorRow(menu, miniMessage, SEPARATOR_ROW);

        menu.set(SLOT_HISTORY, Ui.item(Material.WRITABLE_BOOK, miniMessage,
                "<aqua><bold>Historia edycji</bold></aqua>",
                List.of(
                        "<gray>Ostatnie zamknięte edycje.</gray>",
                        "<yellow>Kliknij, aby przeglądać.</yellow>"),
                false), (who, click) -> historyPager().open(who));

        menu.set(SLOT_DRY_RUN, Ui.item(Material.SPYGLASS, miniMessage,
                "<gold><bold>Podgląd zamknięcia</bold></gold>",
                List.of(
                        "<gray>Dry-run: co się stanie po zamknięciu.</gray>",
                        "<gray>Nic nie zmienia — raport na czacie.</gray>"),
                false), (who, click) -> {
            // A7: raport liczy się na wątku DB, więc komunikat na czacie MUSI
            // wrócić na wątek encji gracza. Etykieta „następnej” liczona PRZED
            // strzałem — rejestr edycji to RAM, nie DB.
            String nextLabel = nextLabel();
            closer.apply(false, false)
                    .thenAccept(outcome -> runOnEntityThread(who,
                            () -> SeasonCloseReport.send(who, outcome, nextLabel, true)))
                    .exceptionally(error -> {
                        plugin.getLogger().warning(
                                "Panel sezonów: dry-run nieudany: " + error);
                        runOnEntityThread(who, () -> who.sendMessage(
                                net.kyori.adventure.text.Component.text(
                                        "Podgląd zamknięcia nieudany — sprawdź log serwera.",
                                        net.kyori.adventure.text.format.NamedTextColor.RED)));
                        return null;
                    });
        });

        menu.set(SLOT_CLOSE_SEASON, Ui.item(Material.TNT, miniMessage,
                "<red><bold>Zamknij sezon</bold></red>",
                List.of(
                        "<gray>Otwiera ekran potwierdzenia.</gray>",
                        "<red>Bez potwierdzenia nic się nie dzieje.</red>"),
                true), (who, click) -> confirmMenu().open(who));

        menu.set(SLOT_RELOAD, Ui.item(Material.REPEATING_COMMAND_BLOCK, miniMessage,
                "<green><bold>Przeładuj edycje</bold></green>",
                List.of("<gray>Odświeża rejestr edycji z konfiguracji.</gray>"),
                false), (who, click) -> {
            // Hak (okablowany w SkyBlockGameplay): świeży odczyt editions.yml →
            // swapRegistry → refreshActivation.
            Runnable hook = this.reloadHook;
            if (hook == null) {
                send(who, "<green>Przeładowano rejestr edycji.</green>");
                buildAndOpen(who);
                return;
            }
            // A7: odczyt editions.yml to I/O — nie może biec na wątku encji
            // gracza (blokuje region). Reload na wątku asynchronicznym, a
            // komunikat i re-render wracają na wątek encji.
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                boolean loaded = true;
                try {
                    hook.run();
                } catch (RuntimeException failure) {
                    loaded = false;
                    plugin.getLogger().warning(
                            "Panel sezonów: przeładowanie edycji nieudane: " + failure);
                }
                boolean ok = loaded;
                runOnEntityThread(who, () -> {
                    send(who, ok
                            ? "<green>Przeładowano rejestr edycji.</green>"
                            : "<red>Nie udało się przeładować edycji — sprawdź log serwera.</red>");
                    buildAndOpen(who);
                });
            });
        });
    }

    /** Etykieta „następnej” rzeczy dla raportu GUI: nazwa edycji → zakres → null. */
    private @Nullable String nextLabel() {
        EditionRegistry registry = registryOrNull();
        if (registry != null && registry.isEnabled()) {
            Edition next = registry.nextAfter(System.currentTimeMillis());
            if (next != null) {
                return next.displayName();
            }
        }
        return blankToNull(rangeDetailSupplier.get());
    }

    private @Nullable EditionRegistry registryOrNull() {
        try {
            return editionsSupplier.get();
        } catch (RuntimeException brokenConfig) {
            plugin.getLogger().warning("Sezony admin: rejestr edycji niedostępny: "
                    + brokenConfig.getMessage());
            return null;
        }
    }

    private @NotNull SeasonCloseConfirmMenu confirmMenu() {
        SeasonCloseConfirmMenu menu = confirmMenu;
        if (menu == null) {
            menu = new SeasonCloseConfirmMenu(plugin, menus, miniMessage, currentSeason,
                    seasonEndMillis, headerLabelSupplier, this::nextLabel,
                    closer, this::open);
            confirmMenu = menu;
        }
        return menu;
    }

    private @NotNull SeasonHistoryPagerMenu historyPager() {
        SeasonHistoryPagerMenu menu = historyMenu;
        if (menu == null) {
            menu = new SeasonHistoryPagerMenu(plugin, menus, miniMessage, sql, nickResolver,
                    this::open);
            historyMenu = menu;
        }
        return menu;
    }

    private void send(@NotNull Player player, @NotNull String message) {
        player.sendMessage(Ui.component(miniMessage, message));
    }

    /**
     * A7 (Folia): raport z zakończenia sezonu biegnie na wątku DB, a
     * {@code sendMessage}/{@code openInventory} wolno wołać wyłącznie na wątku
     * encji gracza. Gdy scheduler odrzuci albo zwróci null (testy bez serwera),
     * zadanie leci inline — wzorzec {@code SeasonCloseConfirmMenu}.
     */
    private void runOnEntityThread(@NotNull Player player, @NotNull Runnable task) {
        try {
            var scheduled = player.getScheduler().run(plugin, ignored -> task.run(), null);
            if (scheduled == null) {
                task.run();
            }
        } catch (RuntimeException rejected) {
            task.run();
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
