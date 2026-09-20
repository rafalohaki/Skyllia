package org.rafalohaki.wpmecore.addons.skyblock.season;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Regresja kalendarza nadchodzących edycji (drift potwierdzony na żywo):
 * łańcuch {@code nextAfter(koniecPoprzedniej - 1)} renderował WYŁĄCZNIE okna
 * REGULAR — edycje EVENT zagnieżdżone wewnątrz dłuższych zakresów REGULAR
 * nigdy nie pasowały do sąsiedztwa (ich start wyprzedza koniec obejmującej
 * edycji) i znikały z siatki panelu. Po poprawce iteracja idzie po liście
 * SORTOWANEJ PO STARCIE względem „teraz”, więc okna zagnieżdżone wychodzą
 * na kartach obok regularnych, a licznik przepełnienia (+N na slocie 25)
 * liczy je wszystkie.
 *
 * <p>Fiksura odtwarza kształt produkcyjnego editions.yml: 8 przyszłych okien
 * = 4 REGULAR + 4 EVENT (każdy EVENT wewnątrz zakresu REGULAR), względem
 * dzisiejszej daty UTC — test deterministyczny niezależnie od dnia uruchomienia.
 * Gracz to atrapa Mockito, menu przechwytuje {@link CapturingMenuService}
 * (wzorzec layout-testów); serwer MockBukkit stawia sobie wyłącznie test
 * renderujący ikony — reszta klasy działa też w profilu domyślnym.
 */
class SeasonAdminMenuNestedWindowsTest {

    private CapturingMenuService menus;

    @BeforeEach
    void setUp() {
        Ui.init(null); // fail-open: żadnej paczki zasobów w testach
        menus = new CapturingMenuService();
    }

    /**
     * Rejestr jak produkcyjny editions.yml: aktywna REGULAR [-10 d; +20 d],
     * potem 8 przyszłych okien (4 REGULAR po kolei; 4 EVENT — każdy startuje
     * w środku obejmującego go okna REGULAR).
     */
    private EditionRegistry productionShapedRegistry() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String yaml = """
                enabled: true
                list:
                  - slug: aktywna
                    display-name: "Edycja Aktywna"
                    type: REGULAR
                    start: "%s"
                    end: "%s"
                  - slug: jesienna
                    display-name: "Jesienna Zmiana"
                    type: REGULAR
                    start: "%s"
                    end: "%s"
                  - slug: szkolna
                    display-name: "Szkolny Czas"
                    type: EVENT
                    start: "%s"
                    end: "%s"
                  - slug: zimowa
                    display-name: "Zimowy Okres"
                    type: REGULAR
                    start: "%s"
                    end: "%s"
                  - slug: swiateczna
                    display-name: "Swiateczne Dni"
                    type: EVENT
                    start: "%s"
                    end: "%s"
                  - slug: feryjna
                    display-name: "Feryjny Zryw"
                    type: EVENT
                    start: "%s"
                    end: "%s"
                  - slug: wiosenna
                    display-name: "Wiosenne Dni"
                    type: REGULAR
                    start: "%s"
                    end: "%s"
                  - slug: wielkanocna
                    display-name: "Wielkanocne Dni"
                    type: EVENT
                    start: "%s"
                    end: "%s"
                  - slug: letnia-27
                    display-name: "Letni Powrot"
                    type: REGULAR
                    start: "%s"
                    end: "%s"
                """.formatted(
                today.minusDays(10), today.plusDays(20),
                // jesienna REGULAR [+21; +110]
                today.plusDays(21), today.plusDays(110),
                // szkolna EVENT — start równy z jesienną, zagnieżdżona
                today.plusDays(21), today.plusDays(80),
                // zimowa REGULAR [+111; +200]
                today.plusDays(111), today.plusDays(200),
                // swiateczna EVENT — w środku zimy
                today.plusDays(150), today.plusDays(180),
                // feryjna EVENT — też w środku zimy
                today.plusDays(185), today.plusDays(199),
                // wiosenna REGULAR [+201; +290]
                today.plusDays(201), today.plusDays(290),
                // wielkanocna EVENT — w środku wiosny
                today.plusDays(220), today.plusDays(260),
                // letnia-27 REGULAR [+291; +380]
                today.plusDays(291), today.plusDays(380));
        return SeasonEditionFixtures.loadRegistry(yaml);
    }

    private SeasonAdminMenu panel(EditionRegistry registry) {
        // Wtyczka tylko do logowania ostrzeżeń panelu — atrapa wystarcza.
        org.bukkit.plugin.java.JavaPlugin plugin =
                mock(org.bukkit.plugin.java.JavaPlugin.class);
        SeasonEditionService editionService =
                new SeasonEditionService(SeasonEditionFixtures.NoopDao.INSTANCE, registry);
        BiFunction<Boolean, Boolean, CompletableFuture<SeasonEndService.CloseOutcome>> closer =
                (force, dryRun) -> CompletableFuture.completedFuture(
                        new SeasonEndService.CloseOutcome(false, 5, 6, 0L, 0, 0, 0, "dry-run"));
        // Koniec sezonu daleko w przyszłości — gałąź „wygasła” nieistotna.
        long farFutureEnd = System.currentTimeMillis() + 86_400_000L * 21;
        return new SeasonAdminMenu(plugin, menus, MiniMessage.miniMessage(),
                () -> 5, () -> farFutureEnd,
                () -> "Sezon Testowy", () -> null,
                closer, () -> registry, editionService,
                mock(SqlService.class), java.util.function.UnaryOperator.identity());
    }

    private static String textOf(org.bukkit.inventory.ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return "";
        }
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        StringBuilder text = new StringBuilder();
        if (stack.getItemMeta().displayName() != null) {
            text.append(plain.serialize(stack.getItemMeta().displayName()));
        }
        if (stack.getItemMeta().lore() != null) {
            for (net.kyori.adventure.text.Component line : stack.getItemMeta().lore()) {
                text.append('\n').append(plain.serialize(line));
            }
        }
        return text.toString();
    }

    @Test
    @DisplayName("upcomingFrom obejmuje okna zagnieżdżone: 8 przyszłych (4 REGULAR + 4 EVENT)")
    void upcomingFromListsNestedWindows() {
        EditionRegistry registry = productionShapedRegistry();
        long now = System.currentTimeMillis();

        List<Edition> upcoming = registry.upcomingFrom(now);

        assertEquals(8, upcoming.size(), "4 REGULAR + 4 EVENT mają być przyszłe");
        assertTrue(upcoming.stream().allMatch(e -> e.startInclusiveMillis() > now),
                "wszystkie okna mają startować po „teraz”");
        for (int i = 1; i < upcoming.size(); i++) {
            assertTrue(upcoming.get(i - 1).startInclusiveMillis()
                            <= upcoming.get(i).startInclusiveMillis(),
                    "kolejność po starcie ma być rosnąca");
        }
        assertEquals(4, upcoming.stream().filter(e -> e.type() == Edition.Type.REGULAR).count());
        assertEquals(4, upcoming.stream().filter(e -> e.type() == Edition.Type.EVENT).count());
    }

    @Test
    @Tag("mockbukkit") // renderuje ItemStack (Ui.item) — wymaga serwera w tle; poza profilem domyślnym
    @DisplayName("siatka: EVENT-y zagnieżdżone wychodzą kartami obok REGULAR, nadmiar +2 na 25")
    void gridShowsNestedEventsAlongsideRegularWithOverflow() {
        // Serwer stawiamy LOKALNIE, nie w @BeforeEach: pole/typ MockBukkit w tej
        // klasie wywróciłby jej ładowanie w profilu domyślnym (mockbukkit jest
        // tam zdjęty z classpath), a nietagowany test rejestru ma tam zostać.
        MockBukkit.mock();
        try {
            assertGridShowsNestedEvents();
        } finally {
            MockBukkit.unmock();
        }
    }

    private void assertGridShowsNestedEvents() {
        SeasonAdminMenu menu = panel(productionShapedRegistry());
        Player player = mock(Player.class);
        menu.populate(player);

        CapturingMenuService.CapturingMenu built = menus.last;

        // Sześć widocznych kart (19..24): jesienna, szkolna, zimowa,
        // swiateczna, feryjna, wiosenna — kolejność po starcie.
        for (int slot = 19; slot <= 24; slot++) {
            Material material = built.items.get(slot).getType();
            assertTrue(material == Material.PAPER || material == Material.AMETHYST_SHARD,
                    "slot " + slot + " ma być kartą edycji, był " + material);
        }
        assertEquals(Material.PAPER, built.items.get(19).getType(), "jesienna (REGULAR) na 19");
        assertEquals(Material.AMETHYST_SHARD, built.items.get(20).getType(),
                "szkolna (EVENT zagnieżdżony przy tym samym starcie) ma być kryształem na 20 "
                        + "— stary łańcuch nextAfter(koniec-1) jej NIE pokazywał");
        assertTrue(textOf(built.items.get(20)).contains("Szkolny Czas"));
        assertEquals(Material.PAPER, built.items.get(21).getType(), "zimowa (REGULAR) na 21");
        assertEquals(Material.AMETHYST_SHARD, built.items.get(22).getType(),
                "swiateczna (EVENT w środku zimy) ma być widoczna");
        assertEquals(Material.AMETHYST_SHARD, built.items.get(23).getType(),
                "feryjna (EVENT w środku zimy) ma być widoczna");

        // Nadmiar: 8 przyszłych − 6 widocznych = +2 (stary algorytm widział tylko
        // 4 okna REGULAR → licznika w ogóle nie było).
        assertEquals(Material.GRAY_DYE, built.items.get(25).getType());
        String overflow = textOf(built.items.get(25));
        assertTrue(overflow.contains("+2"), overflow);
        assertTrue(overflow.contains("dalszych edycji"), overflow);
    }

    /** Serwis menu przechwytujący zawartość do asercji layoutu (wzorzec layout-testów). */
    private static final class CapturingMenuService implements MenuService {

        private CapturingMenu last;

        @Override
        public @NotNull Menu rowsFor(int itemCount, @NotNull net.kyori.adventure.text.Component title) {
            return ofRows((itemCount + 8) / 9, title);
        }

        @Override
        public @NotNull Menu ofRows(int rows, @NotNull net.kyori.adventure.text.Component title) {
            last = new CapturingMenu(rows, title);
            return last;
        }

        private static final class CapturingMenu implements Menu {

            private final int rows;
            private final net.kyori.adventure.text.Component title;
            private final Map<Integer, org.bukkit.inventory.ItemStack> items = new HashMap<>();
            private final Map<Integer, MenuService.ClickHandler> handlers = new HashMap<>();

            private CapturingMenu(int rows, net.kyori.adventure.text.Component title) {
                this.rows = rows;
                this.title = title;
            }

            @Override public int size() {
                return rows * 9;
            }

            @Override public boolean add(@NotNull org.bukkit.inventory.ItemStack icon,
                                         @NotNull MenuService.ClickHandler handler) {
                return false;
            }

            @Override public @NotNull Menu set(int slot, @NotNull org.bukkit.inventory.ItemStack icon,
                                               @NotNull MenuService.ClickHandler handler) {
                items.put(slot, icon);
                handlers.put(slot, handler);
                return this;
            }

            @Override public @NotNull Menu decoration(int slot, @NotNull org.bukkit.inventory.ItemStack icon) {
                items.put(slot, icon);
                handlers.remove(slot);
                return this;
            }

            @Override public void open(@NotNull Player viewer) { }
        }
    }
}
