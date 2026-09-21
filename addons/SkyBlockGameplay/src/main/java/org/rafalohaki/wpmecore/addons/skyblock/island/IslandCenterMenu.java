package org.rafalohaki.wpmecore.addons.skyblock.island;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.ProfileStateService;
import org.rafalohaki.wpmecore.addons.skyblock.playtime.ProfilePlaytimeDao;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandMemberSnapshot;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.WarpSnapshot;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.api.util.CompactDurationFormatter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class IslandCenterMenu {

    /** Slot pierwszego kafla w rzędzie treści (kolumny 1..7). */
    private static final int FIRST_CONTENT_SLOT = 10;
    /** Ile kafli mieści jeden rząd treści. */
    private static final int ROW_CAPACITY = 7;
    /** Strona biomów = cztery pełne rzędy treści (10..16, 19..25, 28..34, 37..43). */
    private static final int BIOME_PAGE_SIZE = ROW_CAPACITY * 4;
    /** Stopka biomów w rzędzie 5 — treść kończy się w rzędzie 4, nic się nie nakłada. */
    private static final int SLOT_PAGE_PREV = 45;
    private static final int SLOT_PAGE_NEXT = 53;

    /**
     * Polskie nazwy biomów; klucz bez namespace, małymi literami. Skyllia zwraca
     * surowe klucze ({@code minecraft:old_growth_pine_taiga}) — gracz ma zobaczyć
     * nazwę, nie identyfikator. Nieznany klucz spada do czytelnej etykiety.
     */
    private static final Map<String, String> BIOME_LABELS = Map.ofEntries(
            Map.entry("plains", "Równiny"),
            Map.entry("sunflower_plains", "Słonecznikowe równiny"),
            Map.entry("snowy_plains", "Śnieżne równiny"),
            Map.entry("desert", "Pustynia"),
            Map.entry("forest", "Las"),
            Map.entry("flower_forest", "Kwietny las"),
            Map.entry("birch_forest", "Las brzozowy"),
            Map.entry("old_growth_birch_forest", "Stary las brzozowy"),
            Map.entry("dark_forest", "Ciemny las"),
            Map.entry("taiga", "Tajga"),
            Map.entry("snowy_taiga", "Śnieżna tajga"),
            Map.entry("old_growth_pine_taiga", "Stara tajga sosnowa"),
            Map.entry("old_growth_spruce_taiga", "Stara tajga świerkowa"),
            Map.entry("jungle", "Dżungla"),
            Map.entry("sparse_jungle", "Rzadka dżungla"),
            Map.entry("bamboo_jungle", "Bambusowa dżungla"),
            Map.entry("swamp", "Bagno"),
            Map.entry("mangrove_swamp", "Bagno namorzynowe"),
            Map.entry("savanna", "Sawanna"),
            Map.entry("savanna_plateau", "Płaskowyż sawanny"),
            Map.entry("windswept_savanna", "Wietrzna sawanna"),
            Map.entry("badlands", "Bezkresne wyżyny"),
            Map.entry("eroded_badlands", "Zerodowane wyżyny"),
            Map.entry("wooded_badlands", "Zalesione wyżyny"),
            Map.entry("beach", "Plaża"),
            Map.entry("snowy_beach", "Śnieżna plaża"),
            Map.entry("stony_shore", "Kamieniste wybrzeże"),
            Map.entry("ocean", "Ocean"),
            Map.entry("deep_ocean", "Głęboki ocean"),
            Map.entry("warm_ocean", "Ciepły ocean"),
            Map.entry("lukewarm_ocean", "Letni ocean"),
            Map.entry("cold_ocean", "Zimny ocean"),
            Map.entry("frozen_ocean", "Zamarznięty ocean"),
            Map.entry("deep_cold_ocean", "Głęboki zimny ocean"),
            Map.entry("deep_frozen_ocean", "Głęboki zamarznięty ocean"),
            Map.entry("deep_lukewarm_ocean", "Głęboki letni ocean"),
            Map.entry("river", "Rzeka"),
            Map.entry("frozen_river", "Zamarznięta rzeka"),
            Map.entry("mushroom_fields", "Wyspa grzybowa"),
            Map.entry("ice_spikes", "Lodowe iglice"),
            Map.entry("cherry_grove", "Wiśniowy gaj"),
            Map.entry("meadow", "Łąka"),
            Map.entry("grove", "Śnieżny gaj"),
            Map.entry("snowy_slopes", "Śnieżne zbocza"),
            Map.entry("jagged_peaks", "Postrzępione szczyty"),
            Map.entry("frozen_peaks", "Zamarznięte szczyty"),
            Map.entry("stony_peaks", "Kamienne szczyty"),
            Map.entry("windswept_hills", "Wietrzne wzgórza"),
            Map.entry("windswept_gravelly_hills", "Wietrzne żwirowe wzgórza"),
            Map.entry("windswept_forest", "Wietrzny las"),
            Map.entry("lush_caves", "Bujne jaskinie"),
            Map.entry("dripstone_caves", "Jaskinie naciekowe"),
            Map.entry("deep_dark", "Mroczna głębia"),
            Map.entry("nether_wastes", "Pustkowia Netheru"),
            Map.entry("crimson_forest", "Karmazynowy las"),
            Map.entry("warped_forest", "Spaczony las"),
            Map.entry("basalt_deltas", "Delty bazaltu"),
            Map.entry("soul_sand_valley", "Dolina piasku dusz"),
            Map.entry("the_end", "Kres"),
            Map.entry("end_barrens", "Pustkowia Kresu"),
            Map.entry("end_highlands", "Wyżyny Kresu"),
            Map.entry("end_midlands", "Środkowe Kresu"),
            Map.entry("small_end_islands", "Małe wyspy Kresu"),
            Map.entry("the_void", "Pustka"));

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage mm;
    private final SkylliaIntegration skyllia;
    private final ProfileStateService profiles;
    private final ProfilePlaytimeDao playtimeDao;
    private final IslandModeFlags flags;
    /**
     * F19: przejście z Centrum Wyspy do Centrum gry (sklep, zadania, bank).
     * Wstrzykiwane setterem, bo SkyBlockMenus powstaje PO tym menu, a jest
     * package-private w korzeniu dodatku — Consumer omija oba problemy naraz.
     * Brak wstrzyknięcia = kafelek pokazuje samą podpowiedź komendy.
     */
    private volatile Consumer<Player> gameCenterOpener;
    /** Poziom wyspy (setter, bo serwis powstaje po tym menu); brak = kafelek bez liczb. */
    private volatile IslandLevelService levels;
    /**
     * Prestiż Wyspy (setter, jak poziom wyspy). Brak wstrzyknięcia = prestiż
     * wyłączony w configu (fail-closed) i kafelek w ogóle się nie renderuje.
     */
    private volatile IslandPrestigeMenu prestige;
    /**
     * Ulepszenia Wyspy (setter, jak prestiż). Brak wstrzyknięcia = ulepszenia
     * wyłączone w configu (fail-closed) i kafelek w ogóle się nie renderuje.
     */
    private volatile IslandUpgradesMenu upgrades;
    /**
     * Trwały tytuł wyspy (migracja #13). Setter, bo serwis powstaje po tym
     * menu; brak wstrzyknięcia = kafel przeglądu bez linii tytułu.
     */
    private volatile IslandTitleService titles;

    public IslandCenterMenu(@NotNull JavaPlugin plugin, @NotNull MenuService menus, @NotNull MiniMessage mm,
                            @NotNull SkylliaIntegration skyllia, @NotNull ProfileStateService profiles,
                            @NotNull ProfilePlaytimeDao playtimeDao, @NotNull IslandModeFlags flags) {
        this.plugin = plugin;
        this.menus = menus;
        this.mm = mm;
        this.skyllia = skyllia;
        this.profiles = profiles;
        this.playtimeDao = playtimeDao;
        this.flags = flags;
    }

    /** F19: wstrzyknięcie akcji „otwórz Centrum gry” po zbudowaniu SkyBlockMenus. */
    public void setGameCenterOpener(@NotNull Consumer<Player> opener) {
        this.gameCenterOpener = opener;
    }

    public void setLevelService(@NotNull IslandLevelService levels) {
        this.levels = levels;
    }

    /**
     * Wstrzyknięcie menu prestiżu po jego zbudowaniu (jak poziom wyspy).
     * Bez niego kafelek prestiżu nie istnieje — tak wygląda config bez
     * sekcji {@code island.prestige}.
     */
    public void setPrestigeMenu(@NotNull IslandPrestigeMenu prestige) {
        this.prestige = prestige;
    }

    /**
     * Wstrzyknięcie menu ulepszeń po jego zbudowaniu (jak prestiż).
     * Bez niego kafelek ulepszeń nie istnieje — tak wygląda config bez
     * sekcji {@code island.upgrades}.
     */
    public void setUpgradesMenu(@NotNull IslandUpgradesMenu upgrades) {
        this.upgrades = upgrades;
    }

    /**
     * Wstrzyknięcie magazynu tytułów wyspy (jak prestiż). Bez niego kafel
     * przeglądu nie pokazuje linii tytułu — dokładnie tak, jak przed migracją #13.
     */
    public void setTitleService(@NotNull IslandTitleService titles) {
        this.titles = titles;
    }

    public void open(@NotNull Player player) {
        var viewOpt = skyllia.islandOf(player.getUniqueId());
        if (viewOpt.isEmpty()) {
            player.sendMessage(Ui.component(mm, "<red>Nie masz wyspy. Utwórz ją komendą <yellow>/is</yellow>.</red>"));
            openCreationFallback(player);
            return;
        }
        var view = viewOpt.get();
        UUID islandId = view.islandId();
        IslandRole cachedRole = view.role();

        MenuService.Menu menu = menus.ofRows(6, panelTitle("Centrum Wyspy"));
        Ui.frame(menu, mm, Material.LIGHT_BLUE_STAINED_GLASS_PANE);

        // A2: tryb gry czytamy z profilu asynchronicznie — kafel startuje z napisem
        // „sprawdzam…”, a wartość wchodzi na wątek encji (fillCenterModeAsync).
        // Tytuł wyspy (migracja #13) idzie tą samą drogą: z cache od razu, z bazy
        // w tle — na wątku regionu nie ma ani jednego zapytania SQL.
        IslandTitleService titleService = this.titles;
        menu.set(10, centerOverviewIcon(islandId, cachedRole, null,
                        titleService == null ? null : titleService.cachedTitle(islandId).orElse(null)),
                (v, c) -> openOverview(v, islandId));

        menu.set(11, Ui.item(Material.PLAYER_HEAD, mm, "<yellow><bold>Członkowie i role</bold></yellow>",
                List.of("<gray>Kto gra na tej wyspie i co mu wolno.</gray>",
                        "<gray>Właściciel, zastępca, moderator, członek.</gray>",
                        "<yellow>Kliknij, aby otworzyć.</yellow>"), true),
                (v, c) -> openMembers(v, islandId));

        menu.set(12, Ui.item(Material.PAPER, mm, "<aqua><bold>Zaproszenia</bold></aqua>",
                List.of("<gray>Dopisz znajomego do swojej wyspy.</gray>",
                        "<gray>Napisz na czacie: <white>/is invite nick</white></gray>"), true),
                (v, c) -> openInvites(v, islandId));

        menu.set(13, Ui.item(Material.IRON_DOOR, mm, "<white><bold>Dostęp</bold></white>",
                List.of("<gray>Ustawienia prywatności wyspy.</gray>",
                        "<gray>Prywatna / publiczna</gray>"), true),
                (v, c) -> openAccess(v, islandId));

        menu.set(14, Ui.item(Material.BARRIER, mm, "<red><bold>Bany</bold></red>",
                List.of("<gray>Zbanowani gracze i odbanowywanie.</gray>"), true),
                (v, c) -> openBans(v, islandId));

        // A9: Skyllia ma tylko odczyt punktu odwiedzin (getVisit) — kafel nie może
        // obiecywać zarządzania, którego nie ma. Pokazuje prawdziwy stan wyspy
        // i komendę, która ten punkt faktycznie ustawia.
        boolean visitSet = skyllia.hasVisit(islandId);
        menu.decoration(15, Ui.item(Material.ENDER_PEARL, mm, "<light_purple><bold>Odwiedziny</bold></light_purple>",
                List.of("<gray>Stan: " + (visitSet
                                ? "<green>punkt odwiedzin ustawiony</green>"
                                : "<red>brak punktu odwiedzin</red>") + "</gray>",
                        "<dark_gray> </dark_gray>",
                        "<gray>Stań w wybranym miejscu na wyspie i napisz</gray>",
                        "<white>/is setvisit</white> <gray>— wtedy goście mogą wejść.</gray>"), false));

        int warpCount = skyllia.warpsOf(islandId).size();
        menu.set(16, Ui.item(Material.ENDER_EYE, mm, "<gold><bold>Warpy</bold></gold>",
                List.of("<gray>Liczba warpów: <white>" + warpCount + "</white></gray>",
                        "<gray>/is warp, /is setwarp</gray>"), true),
                (v, c) -> openWarps(v, islandId));

        // Ulepszenia wyspy (migracja #14): średniotorowa progresja za monety
        // z banku wyspy — rozmiar, członkowie, sloty minionków. Kafelek
        // istnieje tylko wtedy, gdy sekcja `island.upgrades` jest włączona
        // (fail-closed jak prestiż). Slot 20 siada w rzędzie progresji wyspy,
        // obok biomu (19) i poziomu wyspy (21).
        IslandUpgradesMenu upgradesMenu = this.upgrades;
        if (upgradesMenu != null) {
            upgradesMenu.renderTile(player, menu, 20, islandId);
        }
        menu.set(19, Ui.item(Material.OAK_LEAVES, mm, "<green><bold>Biom</bold></green>",
                List.of("<gray>Aktualny biom: <white>" + biomeLabel(skyllia.biomeOf(islandId)) + "</white></gray>",
                        "<yellow>Kliknij, aby zmienić.</yellow>"), true),
                (v, c) -> openBiome(v, islandId, 0));

        // Poziom wyspy: liczby z cache serwisu (bez bazy na wątku gracza); klik = przelicz teraz.
        menu.set(21, Ui.item(Material.EXPERIENCE_BOTTLE, mm, "<light_purple><bold>Poziom wyspy</bold></light_purple>",
                levelLore(islandId), true),
                (v, c) -> refreshLevel(v, islandId));

        // A5: kafel czasu gry dostaje prawdziwą wartość z bazy po powrocie na wątek
        // encji; klik powtarza odczyt, więc kafel nigdy nie zostaje na „Ładowanie…”.
        menu.set(22, playtimeIcon("<gray>Sprawdzam zapis czasu gry…</gray>"), (v, c) -> fetchPlaytime(v, islandId, menu));
        fetchPlaytime(player, islandId, menu);

        menu.set(25, Ui.item(Material.ANVIL, mm, "<red><bold>Operacje właściciela</bold></red>",
                List.of("<gray>Skasowanie wyspy, start od zera,</gray>",
                        "<gray>oddanie wyspy innemu graczowi.</gray>",
                        "<red>Każda z tych rzeczy jest nieodwracalna.</red>"), true),
                (v, c) -> openOwnerOps(v, islandId));

        menu.set(30, Ui.item(Material.BOOK, mm, "<aqua><bold>Przewodnik Wyspy</bold></aqua>",
                List.of("<gray>Pierwsze kroki i wskazówki.</gray>"), true),
                (v, c) -> new IslandGuideMenu(menus, mm).open(v));

        // Slot 34: Prestiż Wyspy — powtarzalny zlew monet z konta wyspy na nagrodę
        // czysto kosmetyczną. Kafelek istnieje tylko wtedy, gdy prestiż jest
        // włączony w configu; poziom czyta cache serwisu (bez bazy na wątku
        // regionu), a świeżą wartość dociąga w tle i podmienia kafel w miejscu.
        IslandPrestigeMenu prestigeMenu = this.prestige;
        if (prestigeMenu != null) {
            prestigeMenu.renderTile(player, menu, 34, islandId);
        }

        // F19: slot 32 wskazywał na /is help, którego ta Skyllia w ogóle nie ma
        // (brak HelpSubCommand w jarze) — klik kończył się komunikatem „taka
        // podkomenda nie istnieje”. Zamiast martwego linku: jedyne wyjście
        // z Centrum Wyspy do Centrum gry, bo /menu z wyspą otwiera już TO menu
        // i sklep, zadania oraz bank stawały się nieosiągalne z menu.
        menu.set(32, Ui.item(Material.EMERALD, mm, "<green><bold>Centrum gry</bold></green>",
                List.of("<gray>Sklep, dzisiejsze zadania, bank wyspy,</gray>",
                        "<gray>automatyzacja i magiczne narzędzia.</gray>",
                        "<yellow>Kliknij, aby otworzyć.</yellow>"), true),
                (v, c) -> {
                    Consumer<Player> opener = gameCenterOpener;
                    if (opener != null) {
                        opener.accept(v);
                    } else {
                        v.closeInventory();
                        v.sendMessage(Ui.component(mm,
                                "<gray>Sklep otworzysz komendą <white>/sklep</white>, zadania komendą <white>/zadania</white>.</gray>"));
                    }
                });

        menu.close(49, Ui.closeButton(mm));
        // „Powrót” wraca do Centrum gry (stamtąd wchodzi się tu z /menu);
        // wcześniej zamykał GUI wbrew własnemu podpisowi „Wróć do poprzedniego
        // ekranu”. Bez podpiętego otwieracza (testy) zostaje zamknięcie.
        menu.set(48, Ui.backButton(mm), (v, c) -> {
            Consumer<Player> back = gameCenterOpener;
            if (back != null) {
                back.accept(v);
            } else {
                v.closeInventory();
            }
        });
        menu.open(player);
        fillCenterModeAsync(player, islandId, cachedRole, menu);
    }

    /** Nagłówek ekranu gracza — jednolity złoty tytuł we wszystkich menu tego pliku. */
    private @NotNull Component panelTitle(@NotNull String name) {
        return Ui.component(mm, "<gold><bold>" + name + "</bold></gold>");
    }

    /**
     * A2: tryb gry siedzi w profilu wyspy (baza), więc czytamy go asynchronicznie
     * i dopisujemy do kafla po powrocie na wątek encji. Na wątku encji nie ma ani
     * jednego blokującego {@code get(...)} — menu otwiera się od razu, a odpowiedź
     * podmienia kafel w miejscu.
     *
     * <p>Tytuł wyspy jedzie tym samym kursem: oba odczyty są niezależne, ale
     * podmieniają <b>jeden</b> kafel, więc czekamy na oba naraz — inaczej drugi
     * z nich skasowałby linię pierwszego. Awaria magazynu tytułów nie może
     * zabrać linii trybu gry, dlatego tytuł degraduje się do braku linii.
     */
    private void fillCenterModeAsync(@NotNull Player player, @NotNull UUID islandId, @NotNull IslandRole role,
                                     @NotNull MenuService.Menu menu) {
        IslandTitleService titleService = this.titles;
        CompletableFuture<String> titleFuture = titleService == null
                ? CompletableFuture.completedFuture(null)
                : titleService.titleOf(islandId).handle((title, failure) ->
                        failure == null && title != null ? title.orElse(null) : null);
        profiles.profile(islandId)
                .handle((profile, ex) -> ex == null && profile != null && profile.isPresent()
                        ? profile.get().mode()
                        : IslandMode.CLASSIC.id())
                .thenCombine(titleFuture, Overview::new)
                .whenComplete((tile, failure) -> {
                    if (failure != null || tile == null) {
                        return;
                    }
                    runOnEntity(player, () -> menu.set(10,
                            centerOverviewIcon(islandId, role, tile.mode(), tile.title()),
                            (v, c) -> openOverview(v, islandId)));
                });
    }

    /** Jedno odświeżenie kafla przeglądu: tryb gry i tytuł wyspy wracają razem. */
    private record Overview(@NotNull String mode, @Nullable String title) { }

    /** Tryb gry dla kafla w ekranie „Przegląd wyspy” — ten sam odczyt, inna dekoracja. */
    private void fillOverviewModeAsync(@NotNull Player player, @NotNull UUID islandId,
                                       @NotNull List<IslandMemberSnapshot> members, @NotNull MenuService.Menu menu) {
        profiles.profile(islandId).whenComplete((profile, ex) -> {
            String mode = ex == null && profile != null && profile.isPresent()
                    ? profile.get().mode()
                    : IslandMode.CLASSIC.id();
            runOnEntity(player, () -> menu.decoration(13, overviewIcon(islandId, mode, members)));
        });
    }

    /**
     * FOLIA-THREADING: każdy odczyt z bazy kończy się powrotem na wątek encji
     * gracza — dopiero tam wolno otwierać menu, pisać wiadomości i odpalać
     * komendy. Gracz, który zdążył wyjść, nie dostaje żadnego zadania.
     */
    private void runOnEntity(@NotNull Player player, @NotNull Runnable action) {
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) {
                action.run();
            }
        }, null);
    }

    /** Wiersz lore z trybem gry; {@code null} = odczyt jeszcze trwa. */
    private @NotNull String modeLine(@Nullable String mode) {
        if (mode == null) {
            return "<gray>Tryb gry: <white>sprawdzam…</white></gray>";
        }
        IslandMode parsed = IslandMode.parse(mode);
        return parsed != null
                ? "<gray>Tryb gry: " + parsed.displayName() + "</gray>"
                : "<gray>Tryb gry: <white>" + readableId(mode) + "</white></gray>";
    }

    private @NotNull ItemStack centerOverviewIcon(@NotNull UUID islandId, @NotNull IslandRole role,
                                                  @Nullable String mode, @Nullable String title) {
        List<String> lore = new ArrayList<>(5);
        lore.add(modeLine(mode));
        if (title != null && !title.isBlank()) {
            lore.add("<gray>Tytuł wyspy: <white>" + title + "</white></gray>");
        }
        lore.add("<gray>Twoja rola: <yellow>" + roleName(role) + "</yellow></gray>");
        lore.add("<gray>Wyspa działa normalnie.</gray>");
        lore.add("<dark_gray>Numer wyspy: " + shortId(islandId) + "</dark_gray>");
        return Ui.item(Material.GRASS_BLOCK, mm, "<green><bold>Przegląd wyspy</bold></green>",
                List.copyOf(lore), true);
    }

    private @NotNull ItemStack overviewIcon(@NotNull UUID islandId, @Nullable String mode,
                                            @NotNull List<IslandMemberSnapshot> members) {
        String ownerName = members.stream()
                .filter(m -> m.role() == IslandRole.OWNER)
                .map(IslandMemberSnapshot::name)
                .findFirst()
                .orElse("?");
        return Ui.item(Material.GRASS_BLOCK, mm, "<green><bold>Wyspa " + shortId(islandId) + "</bold></green>",
                List.of(modeLine(mode),
                        "<gray>Właściciel: <white>" + ownerName + "</white></gray>",
                        "<gray>Osób na wyspie: <white>" + members.size() + "</white></gray>",
                        "<gray>Wyspa gotowa do gry.</gray>"), false);
    }

    private @NotNull ItemStack playtimeIcon(@NotNull String valueLine) {
        return Ui.item(Material.CLOCK, mm, "<yellow><bold>Czas gry</bold></yellow>",
                List.of(valueLine,
                        "<dark_gray> </dark_gray>",
                        "<yellow>Kliknij, aby odświeżyć.</yellow>"), false);
    }

    private static @NotNull String shortId(@NotNull UUID islandId) {
        return islandId.toString().substring(0, 8);
    }

    /** Poziom, xp, ile do następnego, cztery składniki i następna nagroda — wszystko z cache. */
    private List<String> levelLore(UUID islandId) {
        IslandLevelService service = levels;
        var snapshot = service == null ? java.util.Optional.<IslandLevelService.Snapshot>empty()
                : service.cached(islandId);
        if (service == null || snapshot.isEmpty()) {
            return List.of("<gray>Jeszcze nie policzony.</gray>",
                    "<yellow>Kliknij, aby przeliczyć.</yellow>");
        }
        IslandLevel.Settings settings = service.settings();
        IslandLevelService.Snapshot s = snapshot.get();
        boolean max = s.level() >= settings.maxLevel();
        String next = max ? "<gold>maksymalny poziom</gold>"
                : "<white>" + IslandLevel.xpToNext(settings, s.level(), s.xp()) + " xp</white>"
                + " <dark_gray>(" + Math.round(IslandLevel.progress(settings, s.level(), s.xp()) * 100.0D) + "%)</dark_gray>";
        String reward = max ? "<gray>brak — maks</gray>"
                : "<gold>" + Ui.money(settings.rewardFor(s.level() + 1)) + "</gold> <gray>do banku</gray>";
        return List.of(
                "<gray>Poziom: <light_purple><bold>" + s.level() + "</bold></light_purple></gray>",
                "<gray>Doświadczenie: <white>" + s.xp() + " xp</white></gray>",
                "<gray>Do następnego: " + next + "</gray>",
                // Pusta linia lore to ZAWSZE spacja — goły pusty napis psuje renderowanie
                // klientów starszych niż serwer (ViaVersion).
                "<dark_gray> </dark_gray>",
                "<gray>Z czego się składa:</gray>",
                "<dark_gray>• <gray>Dorobek (bank + zadania): <white>" + s.breakdown().fromScore() + "</white></gray>",
                "<dark_gray>• <gray>Minionki: <white>" + s.breakdown().fromMinions() + "</white></gray>",
                "<dark_gray>• <gray>Rozdział OneBlocka: <white>" + s.breakdown().fromChapter() + "</white></gray>",
                "<dark_gray>• <gray>Punkty sezonowe członków: <white>" + s.breakdown().fromSeasonPoints() + "</white></gray>",
                "<dark_gray> </dark_gray>",
                "<gray>Następna nagroda: " + reward + "</gray>",
                "<yellow>Kliknij, aby przeliczyć teraz.</yellow>");
    }

    private void refreshLevel(Player player, UUID islandId) {
        IslandLevelService service = levels;
        if (service == null) {
            return;
        }
        player.sendMessage(Ui.component(mm, "<gray>Przeliczam poziom wyspy…</gray>"));
        service.refresh(islandId).thenRun(() -> runOnEntity(player, () -> open(player)));
    }

    private void openCreationFallback(Player player) {
        new IslandCreationMenus(plugin, menus, mm, skyllia, profiles, flags, null).openPicker(player);
    }

    /**
     * A5: jedno zapytanie, wynik dopiero na wątku encji. Wcześniej kafel zostawał
     * na „Ładowanie…”, a odczyt leciał dwa razy (raz w pustym zadaniu).
     */
    private void fetchPlaytime(@NotNull Player player, @NotNull UUID islandId, @NotNull MenuService.Menu menu) {
        playtimeDao.find(islandId, player.getUniqueId()).whenComplete((row, ex) -> {
            String value;
            if (ex != null) {
                value = "<red>Nie udało się odczytać czasu gry.</red>";
            } else if (row == null || row.isEmpty()) {
                value = "<gray>Brak zapisu czasu gry dla tej wyspy.</gray>";
            } else {
                value = "<gray>Łączny czas na tej wyspie: <white>"
                        + CompactDurationFormatter.format(Duration.ofMillis(row.get().totalActiveMs()))
                        + "</white></gray>";
            }
            runOnEntity(player, () -> menu.set(22, playtimeIcon(value), (v, c) -> fetchPlaytime(v, islandId, menu)));
        });
    }

    private void openOverview(Player player, UUID islandId) {
        MenuService.Menu menu = menus.ofRows(3, panelTitle("Przegląd wyspy"));
        Ui.frame(menu, mm, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        var members = skyllia.membersOf(islandId);
        menu.decoration(13, overviewIcon(islandId, null, members));
        menu.set(22, Ui.backButton(mm), (v, c) -> open(v));
        menu.open(player);
        fillOverviewModeAsync(player, islandId, members, menu);
    }

    private void openMembers(Player player, UUID islandId) {
        MenuService.Menu menu = menus.ofRows(4, panelTitle("Członkowie i role"));
        Ui.frame(menu, mm, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        var members = skyllia.membersOf(islandId);
        if (members.isEmpty()) {
            menu.decoration(13, Ui.item(Material.BARRIER, mm, "<gray>Brak członków</gray>",
                    List.of("<gray>Zaproś kogoś komendą <white>/is invite nick</white>.</gray>"), false));
        } else {
            // A8: Skyllia nie wystawia API wyrzucania ani awansu członka, więc kafel
            // jest dekoracją z prawdziwym opisem roli — a nie przyciskiem, który po
            // kliknięciu tylko wypisuje podpowiedź. Zarządzanie idzie komendami.
            boolean overflow = members.size() > ROW_CAPACITY;
            int shown = overflow ? ROW_CAPACITY - 1 : members.size();
            for (int i = 0; i < shown; i++) {
                menu.decoration(FIRST_CONTENT_SLOT + i, memberIcon(members.get(i)));
            }
            if (overflow) {
                menu.decoration(FIRST_CONTENT_SLOT + shown, overflowIcon(
                        "+" + (members.size() - shown) + " dalszych członków…",
                        "<gray>Zmiany robisz komendami wyspy:</gray>",
                        "<white>/is promote nick</white> <gray>awansuje,</gray>",
                        "<white>/is kick nick</white> <gray>wyrzuca.</gray>"));
            }
        }
        menu.set(31, Ui.backButton(mm), (v, c) -> open(v));
        menu.open(player);
    }

    private @NotNull ItemStack memberIcon(@NotNull IslandMemberSnapshot member) {
        Material material = switch (member.role()) {
            case OWNER -> Material.DIAMOND_BLOCK;
            case CO_OWNER -> Material.GOLD_BLOCK;
            case MODERATOR -> Material.IRON_BLOCK;
            default -> Material.PLAYER_HEAD;
        };
        return Ui.item(material, mm, "<yellow>" + member.name() + "</yellow>",
                List.of("<gray>Rola: <white>" + roleName(member.role()) + "</white></gray>",
                        "<dark_gray> </dark_gray>",
                        "<gray>Zmiany robisz komendami wyspy:</gray>",
                        "<white>/is promote nick</white> <gray>awansuje,</gray>",
                        "<white>/is kick nick</white> <gray>wyrzuca z wyspy.</gray>"), false);
    }

    /** F19: rola po polsku — gracz nie musi wiedzieć, co znaczy CO_OWNER. */
    static @NotNull String roleName(@NotNull IslandRole role) {
        return switch (role) {
            case OWNER -> "właściciel";
            case CO_OWNER -> "zastępca właściciela";
            case MODERATOR -> "moderator";
            case MEMBER -> "członek wyspy";
            case VISITOR -> "gość";
            case BAN -> "zbanowany";
            case UNKNOWN -> "nieznana";
        };
    }

    private void openInvites(Player player, UUID islandId) {
        MenuService.Menu menu = menus.ofRows(3, panelTitle("Zaproszenia"));
        Ui.frame(menu, mm, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        menu.decoration(13, Ui.item(Material.PAPER, mm, "<aqua><bold>Zaproszenia</bold></aqua>",
                List.of("<gray>Zaproś kogoś: napisz na czacie</gray>",
                        "<white>/is invite nick</white>",
                        "<gray>Zaproszony wpisuje u siebie <white>/is accept</white>.</gray>",
                        "<dark_gray>Lista zaproszeń nie jest tu pokazywana.</dark_gray>"), false));
        menu.set(22, Ui.backButton(mm), (v, c) -> open(v));
        menu.open(player);
    }

    private void openAccess(Player player, UUID islandId) {
        MenuService.Menu menu = menus.ofRows(3, panelTitle("Dostęp"));
        Ui.frame(menu, mm, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        boolean isPrivate = skyllia.isPrivateIsland(islandId);
        menu.set(11, Ui.item(isPrivate ? Material.IRON_DOOR : Material.OAK_DOOR, mm,
                isPrivate ? "<red><bold>Wyspa prywatna</bold></red>" : "<green><bold>Wyspa publiczna</bold></green>",
                List.of("<gray>Prywatna: wchodzi tylko zespół wyspy.</gray>",
                        "<gray>Publiczna: może wpaść każdy.</gray>",
                        "<yellow>Kliknij, aby przełączyć (tylko właściciel).</yellow>"), true),
                (v, c) -> toggleAccess(v, islandId));
        // A10: Skyllia nie zna zbiorczego „resetu uprawnień” (PermissionSubCommand
        // umie tylko list/set/toggle) — kafel jest dekoracją z prawdziwą instrukcją,
        // a nie przyciskiem, który po kliknięciu nic nie zmienia.
        menu.decoration(15, Ui.item(Material.BARRIER, mm, "<gray><bold>Uprawnienia wyspy</bold></gray>",
                List.of("<gray>Skyllia nie ma zbiorczego resetu uprawnień.</gray>",
                        "<gray>Ustawiasz je pojedynczo:</gray>",
                        "<white>/is permission set &lt;ranga&gt; &lt;uprawnienie&gt; &lt;on|off&gt;</white>",
                        "<dark_gray>Podgląd: <white>/is permission list</white>.</dark_gray>"), false));
        menu.set(22, Ui.backButton(mm), (v, c) -> open(v));
        menu.open(player);
    }

    private void toggleAccess(Player player, UUID islandId) {
        CompletableFuture.supplyAsync(() -> skyllia.authoritativeRole(player.getUniqueId(), islandId))
                .thenAccept(role -> runOnEntity(player, () -> {
                    if (role != IslandRole.OWNER) {
                        player.sendMessage(Ui.component(mm, "<red>Tylko właściciel może zmienić dostęp.</red>"));
                        return;
                    }
                    boolean nowPrivate = !skyllia.isPrivateIsland(islandId);
                    if (!skyllia.setPrivateIsland(islandId, nowPrivate)) {
                        player.sendMessage(Ui.component(mm, "<red>Nie udało się zmienić dostępu do wyspy.</red>"));
                        return;
                    }
                    player.sendMessage(Ui.component(mm, "<green>Ustawiono wyspę jako "
                            + (nowPrivate ? "prywatną" : "publiczną") + ".</green>"));
                    // A12: zostajemy na ekranie „Dostęp”, żeby od razu widzieć nowy stan.
                    openAccess(player, islandId);
                }));
    }

    private void openBans(Player player, UUID islandId) {
        MenuService.Menu menu = menus.ofRows(4, panelTitle("Bany"));
        Ui.frame(menu, mm, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        var banned = skyllia.bannedMembersOf(islandId);
        if (banned.isEmpty()) {
            menu.decoration(13, Ui.item(Material.BARRIER, mm, "<gray>Brak banów</gray>",
                    List.of("<gray>Na tej wyspie nikt nie jest zbanowany.</gray>"), false));
        } else {
            boolean overflow = banned.size() > ROW_CAPACITY;
            int shown = overflow ? ROW_CAPACITY - 1 : banned.size();
            for (int i = 0; i < shown; i++) {
                var ban = banned.get(i);
                menu.set(FIRST_CONTENT_SLOT + i, Ui.item(Material.SKELETON_SKULL, mm, "<red>" + ban.name() + "</red>",
                        List.of("<gray>Kliknij, aby wpuścić go z powrotem.</gray>",
                                "<dark_gray>Może to zrobić właściciel lub zastępca.</dark_gray>"), true),
                        (v, c) -> handleUnban(v, islandId, ban));
            }
            if (overflow) {
                menu.decoration(FIRST_CONTENT_SLOT + shown, overflowIcon(
                        "+" + (banned.size() - shown) + " dalszych banów…",
                        "<gray>Zdjęcie bana nie jest tu już pokazane,</gray>",
                        "<gray>ale działa komenda <white>/is unban nick</white>.</gray>"));
            }
        }
        menu.set(31, Ui.backButton(mm), (v, c) -> open(v));
        menu.open(player);
    }

    private void handleUnban(@NotNull Player viewer, @NotNull UUID islandId, @NotNull IslandMemberSnapshot banned) {
        CompletableFuture.supplyAsync(() -> skyllia.authoritativeRole(viewer.getUniqueId(), islandId))
                .thenAccept(role -> runOnEntity(viewer, () -> {
                    if (role != IslandRole.OWNER && role != IslandRole.CO_OWNER) {
                        viewer.sendMessage(Ui.component(mm, "<red>Brak uprawnień.</red>"));
                        return;
                    }
                    // A1: performCommand wyłącznie na wątku encji gracza.
                    // Skyllia szuka bana po NICKU (Island#getMember(String)) — surowy
                    // UUID kończył się „player-not-banned” i ban zostawał na wyspie.
                    viewer.sendMessage(Ui.component(mm,
                            "<gray>Zdejmuję bana z <white>" + banned.name() + "</white>…</gray>"));
                    viewer.performCommand("is unban " + banned.name());
                    // A10: po zapisie odświeżamy ekran banów (opóźnienie na commit Skyllii).
                    viewer.getScheduler().runDelayed(plugin, task -> {
                        if (viewer.isOnline()) {
                            openBans(viewer, islandId);
                        }
                    }, null, 10);
                }));
    }

    private void openWarps(Player player, UUID islandId) {
        MenuService.Menu menu = menus.ofRows(4, panelTitle("Warpy"));
        Ui.frame(menu, mm, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        var warps = skyllia.warpsOf(islandId);
        if (warps.isEmpty()) {
            menu.decoration(13, Ui.item(Material.BARRIER, mm, "<gray>Nie masz jeszcze punktów podróży</gray>",
                    List.of("<gray>Punkt podróży to zapamiętane miejsce na wyspie.</gray>",
                            "<gray>Stań tam i napisz na czacie: <white>/is setwarp nazwa</white></gray>"), false));
        } else {
            boolean overflow = warps.size() > ROW_CAPACITY;
            int shown = overflow ? ROW_CAPACITY - 1 : warps.size();
            for (int i = 0; i < shown; i++) {
                WarpSnapshot warp = warps.get(i);
                if (warp.location() == null) {
                    // Kafel nie może obiecywać teleportu, którego Skyllia nie wykona.
                    menu.decoration(FIRST_CONTENT_SLOT + i, Ui.item(Material.ENDER_EYE, mm,
                            "<yellow>" + warp.name() + "</yellow>",
                            List.of("<gray>Ten punkt nie ma zapisanej lokalizacji.</gray>",
                                    "<gray>Ustaw go ponownie: <white>/is setwarp " + warp.name() + "</white></gray>"), false));
                } else {
                    menu.set(FIRST_CONTENT_SLOT + i, Ui.item(Material.ENDER_EYE, mm, "<yellow>" + warp.name() + "</yellow>",
                            List.of("<gray>X: <white>" + warp.location().getBlockX() + "</white>"
                                            + " Z: <white>" + warp.location().getBlockZ() + "</white></gray>",
                                    "<yellow>Kliknij, aby się tam przenieść.</yellow>"), true),
                            (v, c) -> handleWarpTeleport(v, islandId, warp.name()));
                }
            }
            if (overflow) {
                menu.decoration(FIRST_CONTENT_SLOT + shown, overflowIcon(
                        "+" + (warps.size() - shown) + " dalszych warpów…",
                        "<gray>Pozostałe punkty podróży znajdziesz</gray>",
                        "<gray>komendą <white>/is warp nazwa</white>.</gray>"));
            }
        }
        menu.set(31, Ui.backButton(mm), (v, c) -> open(v));
        menu.open(player);
    }

    private void handleWarpTeleport(Player player, UUID islandId, String warp) {
        CompletableFuture.supplyAsync(() -> skyllia.authoritativeRole(player.getUniqueId(), islandId))
                .thenAccept(role -> runOnEntity(player, () -> {
                    if (role == IslandRole.UNKNOWN || role == IslandRole.BAN) {
                        player.sendMessage(Ui.component(mm, "<red>Nie masz dostępu do tej wyspy.</red>"));
                        return;
                    }
                    // A1: performCommand wyłącznie na wątku encji gracza.
                    player.performCommand("is warp " + warp);
                }));
    }

    /**
     * A4/A11: biomów jest kilkadziesiąt, więc treść idzie w cztery rzędy
     * (10..43), a stopka — powrót, zamknięcie i stronicowanie — ląduje w rzędzie
     * 5, wolnym od kafli. Koniec cichego ucinania listy i koniec nadpisywania
     * 24./25. biomu przyciskami.
     */
    private void openBiome(Player player, UUID islandId, int page) {
        MenuService.Menu menu = menus.ofRows(6, panelTitle("Biom wyspy"));
        Ui.frame(menu, mm, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        List<String> biomes = skyllia.biomeNames();
        if (biomes.isEmpty()) {
            biomes = List.of("minecraft:plains", "minecraft:desert", "minecraft:forest", "minecraft:snowy_plains");
        }
        int pages = Math.max(1, (biomes.size() + BIOME_PAGE_SIZE - 1) / BIOME_PAGE_SIZE);
        int current = Math.min(Math.max(page, 0), pages - 1);
        int first = current * BIOME_PAGE_SIZE;
        int last = Math.min(first + BIOME_PAGE_SIZE, biomes.size());

        menu.decoration(4, Ui.item(Material.OAK_SAPLING, mm,
                "<green>Aktualny biom: <white>" + biomeLabel(skyllia.biomeOf(islandId)) + "</white></green>",
                List.of("<gray>Strona <white>" + (current + 1) + "</white> z <white>" + pages + "</white>.</gray>",
                        "<gray>Dostępnych biomów: <white>" + biomes.size() + "</white>.</gray>"), false));

        for (int i = first; i < last; i++) {
            String raw = biomes.get(i);
            menu.set(contentSlot(i - first), Ui.item(biomeIcon(raw), mm,
                    "<green>" + biomeLabel(raw) + "</green>",
                    List.of("<gray>Biom decyduje o wyglądzie i pogodzie wyspy.</gray>",
                            "<yellow>Kliknij, aby ustawić (tylko właściciel).</yellow>"), false),
                    (v, c) -> handleBiomeChange(v, islandId, raw, current));
        }

        if (current > 0) {
            menu.set(SLOT_PAGE_PREV, Ui.item(Material.ARROW, mm, "<yellow><bold>Poprzednia strona</bold></yellow>",
                    List.of("<gray>Wracasz do biomów <white>" + (first - BIOME_PAGE_SIZE + 1)
                            + "–" + first + "</white>.</gray>"), true),
                    (v, c) -> openBiome(v, islandId, current - 1));
        } else {
            menu.decoration(SLOT_PAGE_PREV, Ui.item(Material.GRAY_DYE, mm, "<gray>Pierwsza strona</gray>",
                    List.of("<gray>Nie ma wcześniejszych biomów.</gray>"), false));
        }
        menu.set(48, Ui.backButton(mm), (v, c) -> open(v));
        menu.close(49, Ui.closeButton(mm));
        if (current < pages - 1) {
            menu.set(SLOT_PAGE_NEXT, Ui.item(Material.SPECTRAL_ARROW, mm, "<yellow><bold>Następna strona</bold></yellow>",
                    List.of("<gray>Dalej biomy <white>" + (last + 1) + "–"
                            + Math.min(last + BIOME_PAGE_SIZE, biomes.size()) + "</white>.</gray>"), true),
                    (v, c) -> openBiome(v, islandId, current + 1));
        } else {
            menu.decoration(SLOT_PAGE_NEXT, Ui.item(Material.GRAY_DYE, mm, "<gray>Ostatnia strona</gray>",
                    List.of("<gray>To już wszystkie biomy.</gray>"), false));
        }
        menu.open(player);
    }

    /** Slot i-tego kafla treści w rzędach 1..4 (10..16, 19..25, 28..34, 37..43). */
    private static int contentSlot(int index) {
        return (index / ROW_CAPACITY + 1) * 9 + 1 + index % ROW_CAPACITY;
    }

    /** A11: licznik nadmiaru zamiast cichego ucięcia listy. */
    private @NotNull ItemStack overflowIcon(@NotNull String name, @NotNull String... lore) {
        return Ui.item(Material.GRAY_DYE, mm, "<gray>" + name + "</gray>", List.of(lore), false);
    }

    private void handleBiomeChange(Player player, UUID islandId, String biomeName, int page) {
        CompletableFuture.supplyAsync(() -> skyllia.authoritativeRole(player.getUniqueId(), islandId))
                .thenAccept(role -> runOnEntity(player, () -> {
                    if (role != IslandRole.OWNER) {
                        player.sendMessage(Ui.component(mm, "<red>Tylko właściciel może zmienić biom.</red>"));
                        return;
                    }
                    boolean ok = skyllia.setBiome(islandId, biomeName, player.getWorld());
                    player.sendMessage(Ui.component(mm, ok
                            ? "<green>Biom zmieniony na <white>" + biomeLabel(biomeName) + "</white>.</green>"
                            : "<red>Zmiana biomu nie powiodła się.</red>"));
                    if (ok) {
                        // Świeży odczyt pokazuje stan świata po zmianie, bez zamykania menu.
                        openBiome(player, islandId, page);
                    }
                }));
    }

    /** Etykieta biomu: polska nazwa, a dla nieznanych kluczy czytelny opis bez namespace. */
    private static @NotNull String biomeLabel(@NotNull String raw) {
        String key = stripNamespace(raw).toLowerCase(Locale.ROOT);
        String label = BIOME_LABELS.get(key);
        return label != null ? label : readableId(key);
    }

    /** Ikona biomu wg rodziny — kilkadziesiąt biomów nie może wyglądać identycznie. */
    private static @NotNull Material biomeIcon(@NotNull String raw) {
        String key = stripNamespace(raw).toLowerCase(Locale.ROOT);
        if (key.contains("ocean") || key.contains("river")) {
            return Material.WATER_BUCKET;
        }
        if (key.contains("snow") || key.contains("frozen") || key.contains("ice") || key.contains("grove")) {
            return Material.SNOW_BLOCK;
        }
        if (key.contains("desert") || key.contains("badlands") || key.contains("beach") || key.contains("savanna")) {
            return Material.SAND;
        }
        if (key.contains("jungle") || key.contains("forest") || key.contains("taiga")
                || key.contains("meadow") || key.contains("cherry")) {
            return Material.OAK_LEAVES;
        }
        if (key.contains("swamp") || key.contains("mangrove")) {
            return Material.MUD;
        }
        if (key.contains("mushroom")) {
            return Material.RED_MUSHROOM_BLOCK;
        }
        if (key.contains("nether") || key.contains("crimson") || key.contains("warped")
                || key.contains("basalt") || key.contains("soul")) {
            return Material.NETHERRACK;
        }
        if (key.contains("end")) {
            return Material.END_STONE;
        }
        if (key.contains("cave") || key.contains("lush") || key.contains("deep_dark")) {
            return Material.MOSS_BLOCK;
        }
        if (key.contains("peak") || key.contains("slope") || key.contains("hill")
                || key.contains("stony") || key.contains("void")) {
            return Material.STONE;
        }
        return Material.GRASS_BLOCK;
    }

    private static @NotNull String stripNamespace(@NotNull String raw) {
        int separator = raw.indexOf(':');
        return separator >= 0 ? raw.substring(separator + 1) : raw;
    }

    /** Surowy klucz na czytelną etykietę: {@code old_growth_pine_taiga} → {@code Old Growth Pine Taiga}. */
    private static @NotNull String readableId(@NotNull String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean capitalize = true;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '_') {
                out.append(' ');
                capitalize = true;
                continue;
            }
            out.append(capitalize ? Character.toUpperCase(c) : c);
            capitalize = false;
        }
        return out.toString();
    }

    private void openOwnerOps(Player player, UUID islandId) {
        new IslandOwnerOperationsMenu(plugin, menus, mm, skyllia, profiles, this).open(player, islandId);
    }
}
