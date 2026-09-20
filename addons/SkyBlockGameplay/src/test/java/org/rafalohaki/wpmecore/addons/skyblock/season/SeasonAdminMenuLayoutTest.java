package org.rafalohaki.wpmecore.addons.skyblock.season;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Layout panelu administratora sezonów (frozen B4): slot CLOCK aktywnej
 * edycji z wewnętrznym identyfikatorem w stopce lore, siatka nadchodzących
 * 19..24 z licznikiem przepełnienia na 25, stopka Historia/Podgląd/Zamknij/
 * Przeładuj/close. Menu budowane przez pakietowe {@code populate} na
 * przechwytującym {@link MenuService} (wzorzec CosmeticPlayerMenuTest).
 */
@Tag("mockbukkit")
class SeasonAdminMenuLayoutTest {

    private ServerMock server;
    private PlayerMock player;
    private CapturingMenuService menus;
    private List<CloseCall> closeCalls;

    /** Jedno wywołanie closera: (force, dryRun) — panel używa wyłącznie (false, false). */
    private record CloseCall(boolean force, boolean dryRun) { }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Ui.init(null); // fail-open: żadnej paczki w testach
        player = server.addPlayer();
        menus = new CapturingMenuService();
        closeCalls = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ------------------------------------------------------------------
    // Konstrukcja menu: okna liczone WZGLĘDEM dzisiaj (UTC), żeby test był
    // deterministyczny niezależnie od daty uruchomienia.
    // ------------------------------------------------------------------

    /** Aktywna edycja [-10 d; +20 d] plus {@code upcomingCount} okien po 30 dni z rządku. */
    private EditionRegistry registryAroundToday(int upcomingCount) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String activeStart = today.minusDays(10).toString();
        String activeEnd = today.plusDays(20).toString();

        StringBuilder yaml = new StringBuilder("""
                enabled: true
                list:
                  - slug: aktywna
                    display-name: "Edycja Aktywna"
                    type: REGULAR
                    start: "%s"
                    end: "%s"
                """.formatted(activeStart, activeEnd));
        LocalDate cursor = LocalDate.parse(activeEnd).plusDays(1);
        for (int k = 1; k <= upcomingCount; k++) {
            String start = cursor.toString();
            String end = cursor.plusDays(29).toString();
            yaml.append("""
                  - slug: nastepna-%d
                    display-name: "Nadchodząca %d"
                    type: REGULAR
                    start: "%s"
                    end: "%s"
                """.formatted(k, k, start, end));
            cursor = cursor.plusDays(30);
        }
        return SeasonEditionFixtures.loadRegistry(yaml.toString());
    }

    private SeasonAdminMenu panel(EditionRegistry registry) {
        org.bukkit.plugin.java.JavaPlugin plugin =
                MockBukkit.createMockPlugin("SeasonAdminMenuLayoutTest");
        SeasonEditionService editionService =
                new SeasonEditionService(SeasonEditionFixtures.NoopDao.INSTANCE, registry);
        BiFunction<Boolean, Boolean, CompletableFuture<SeasonEndService.CloseOutcome>> closer =
                (force, dryRun) -> {
                    closeCalls.add(new CloseCall(force, dryRun));
                    return CompletableFuture.completedFuture(new SeasonEndService.CloseOutcome(
                            false, 5, 6, 0L, 0, 0, 0, "dry-run"));
                };
        return new SeasonAdminMenu(
                plugin,
                menus,
                MiniMessage.miniMessage(),
                () -> 5,
                this::activeEndExclusiveMillis,
                // Jak w produkcji (SkyBlockGameplay): nagłówek to nazwa AKTYWNEJ
                // edycji z rejestru (editionService.publicHeaderLabel → labelAt).
                () -> registry.labelAt(System.currentTimeMillis()),
                () -> "01.06.2026 – 31.08.2026",
                closer,
                () -> registry,
                editionService,
                mock(SqlService.class),
                java.util.function.UnaryOperator.identity());
    }

    /** Północ UTC ~21 dni od teraz — koniec „aktywnego” sezonu dla licznika dni. */
    private long activeEndExclusiveMillis() {
        long dayMillis = 86_400_000L;
        return (System.currentTimeMillis() / dayMillis + 21) * dayMillis;
    }

    /** Złącza tekstowe ikony (nazwa + lore) jednym stringiem — łatwiejsze asercje. */
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
    @DisplayName("layout: nagłówek BOOK, aktywny CLOCK z wewn. id, siatka 19..24 i przepełnienie na 25")
    void layoutPlacesHeaderActiveUpcomingAndOverflow() {
        SeasonAdminMenu menu = panel(registryAroundToday(8)); // 8 nadchodzących → przepełnienie
        menu.populate(player);

        CapturingMenuService.CapturingMenu built = menus.last;
        assertEquals(54, built.size(), "panel ma mieć 6 rzędów");
        assertEquals("Panel Sezonów",
                PlainTextComponentSerializer.plainText().serialize(built.title));

        // Nagłówek: dekoracja BOOK na slocie 4.
        assertEquals(Material.BOOK, built.items.get(4).getType(),
                "nagłówek ma być książką z opisem trybu (WRITABLE_BOOK zostaje Historii na 46)");
        assertNull(built.handlers.get(4), "nagłówek nie może być klikalny");

        // Aktywna edycja: CLOCK na 13, lore z zakresem i wewnętrznym id S5.
        assertNotNull(built.items.get(13), "slot aktywnej edycji ma być obsadzony");
        assertEquals(Material.CLOCK, built.items.get(13).getType(),
                "aktywna edycja ma być zaznaczona zegarem");
        String clockText = textOf(built.items.get(13));
        assertTrue(clockText.contains("Edycja Aktywna"), clockText);
        assertTrue(clockText.contains("01.06.2026 – 31.08.2026"),
                "lore zegara ma pokazywać zakres dat: " + clockText);
        assertTrue(clockText.contains("wewn. id: S5"),
                "wewnętrzny identyfikator sezonu ma być w stopce lore (szary): " + clockText);

        // Siatka nadchodzących: 19..24 pełne, 25 = licznik przepełnienia (+2).
        for (int slot = 19; slot <= 24; slot++) {
            Material material = built.items.get(slot).getType();
            assertTrue(material == Material.PAPER || material == Material.AMETHYST_SHARD,
                    "slot " + slot + " ma być kartą/kryształem edycji, był " + material);
        }
        assertEquals(Material.GRAY_DYE, built.items.get(25).getType(),
                "przy >6 nadchodzących slot 25 ma pokazać licznik przepełnienia");
        String overflow = textOf(built.items.get(25));
        assertTrue(overflow.contains("+2"), overflow);
        assertTrue(overflow.contains("dalszych edycji"), overflow);

        // Pierwsze dwie nadchodzące mają swoje nazwy w siatce.
        assertTrue(textOf(built.items.get(19)).contains("Nadchodząca 1"));
        assertTrue(textOf(built.items.get(20)).contains("Nadchodząca 2"));
    }

    @Test
    @DisplayName("stopka i rama: Historia/Podgląd/Zamknij/Przeładuj/close klikalne, reszto dekoracje")
    void footerButtonsAreClickableAndFrameIsDecorative() {
        SeasonAdminMenu menu = panel(registryAroundToday(2)); // bez przepełnienia
        menu.populate(player);

        CapturingMenuService.CapturingMenu built = menus.last;

        // Dwie karty nadchodzących (19, 20), dalej wypełniacze siatki.
        assertEquals(Material.PAPER, built.items.get(20).getType(),
                "druga nadchodząca ma być na slocie 20");
        assertEquals(Material.GRAY_DYE, built.items.get(21).getType(),
                "za kartami slot 21 ma być wypełniaczem (—)");
        assertEquals(Material.GRAY_DYE, built.items.get(23).getType(),
                "slot 23 ma być wypełniaczem — bez przepełnienia nie ma licznika");
        assertEquals(Material.GRAY_DYE, built.items.get(25).getType(),
                "bez przepełnienia slot 25 ma być wypełniaczem, nie licznikiem");
        assertFalse(textOf(built.items.get(25)).contains("dalszych edycji"),
                "wypełniacz na 25 nie może udawać licznika nadmiaru");

        // Stopka.
        assertEquals(Material.WRITABLE_BOOK, built.items.get(46).getType(), "Historia");
        assertEquals(Material.SPYGLASS, built.items.get(48).getType(), "Podgląd");
        assertEquals(Material.TNT, built.items.get(49).getType(), "Zamknij sezon");
        assertEquals(Material.REPEATING_COMMAND_BLOCK, built.items.get(51).getType(),
                "Przeładuj rejestr");
        assertEquals(Material.BARRIER, built.items.get(53).getType(), "Ui.closeButton");
        for (int slot : new int[]{46, 48, 49, 51, 53}) {
            assertNotNull(built.handlers.get(slot), "przycisk stopki " + slot + " ma być klikalny");
        }

        // Rama akcentowa nie jest klikalna.
        assertEquals(Material.LIGHT_BLUE_STAINED_GLASS_PANE, built.items.get(0).getType(),
                "rama akcentowa ma być jasnoniebieską szybą");
        assertNull(built.handlers.get(0), "rama nie może być klikalna");
    }

    @Test
    @DisplayName("Podgląd (48) odpala closer dokładnie raz jako dry-run (false,false)")
    void previewButtonRunsDryRunOnly() {
        SeasonAdminMenu menu = panel(registryAroundToday(2));
        menu.populate(player);

        menus.last.handlers.get(48).onClick(player, ClickType.LEFT);

        assertEquals(1, closeCalls.size(), "podgląd ma wykonać dokładnie jedno zamknięcie");
        assertEquals(new CloseCall(false, false), closeCalls.getFirst(),
                "podgląd ma wołać closeSeason(false, false) — nigdy force");
    }

    @Test
    @DisplayName("Zamknij (49) otwiera ekran potwierdzenia, nie zamyka sezonu bezpośrednio")
    void closeButtonOpensConfirmationInsteadOfClosing() {
        SeasonAdminMenu menu = panel(registryAroundToday(2));
        menu.populate(player);

        menus.last.handlers.get(49).onClick(player, ClickType.LEFT);
        // Ekran potwierdzenia buduje się na wątku encji (getScheduler().run),
        // a MockBukkit kolejkuje takie zadanie do najbliższego ticku.
        server.getScheduler().performOneTick();

        assertEquals(0, closeCalls.size(),
                "TNT na panelu nie może zamykać sezonu wprost — tylko potwierdzenie");
        assertNotNull(menus.last, "kliknięcie ma otworzyć nowe menu");
        assertEquals("Potwierdź zamknięcie sezonu",
                PlainTextComponentSerializer.plainText().serialize(menus.last.title),
                "docelowym ekranem ma być potwierdzenie");
    }

    /** Serwis menu przechwytujący zawartość do asercji layoutu (wzorzec UiLayoutTest). */
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
