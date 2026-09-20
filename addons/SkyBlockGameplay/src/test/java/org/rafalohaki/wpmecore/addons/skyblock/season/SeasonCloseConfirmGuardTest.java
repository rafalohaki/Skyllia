package org.rafalohaki.wpmecore.addons.skyblock.season;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strażnicy ekranu potwierdzenia zamknięcia sezonu (frozen B4): klikalny
 * POTWIERDZAM na 15 odpala wyłącznie {@code closeSeason(true, false)},
 * podwójne kliknięcie w trakcie lotu future = JEDNO wywołanie closera,
 * a rozjazd numeru sezonu (rollover w tle) blokuje zamknięcie i wraca do
 * panelu z komunikatem na chacie.
 */
@Tag("mockbukkit")
class SeasonCloseConfirmGuardTest {

    private ServerMock server;
    private PlayerMock player;
    private CapturingMenuService menus;

    /** Jedno wywołanie closera: (force, dryRun). Potwierdzenie używa wyłącznie (true, false). */
    private record CloseCall(boolean force, boolean dryRun) { }

    private final List<CloseCall> closeCalls = new ArrayList<>();
    private final List<Player> backActions = new ArrayList<>();
    /** Niedokończony future closera — pozwala trzymać strażnik in-flight „w locie”. */
    private final CompletableFuture<SeasonEndService.CloseOutcome> pending =
            new CompletableFuture<>();
    /** Aktualny numer sezonu widziany przez menu — podmieniany w teście nieaktualności. */
    private final int[] currentSeason = {5};

    private SeasonCloseConfirmMenu menu;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Ui.init(null); // fail-open: żadnej paczki w testach
        player = server.addPlayer();
        menus = new CapturingMenuService();

        org.bukkit.plugin.java.JavaPlugin plugin =
                MockBukkit.createMockPlugin("SeasonCloseConfirmGuardTest");
        BiFunction<Boolean, Boolean, CompletableFuture<SeasonEndService.CloseOutcome>> closer =
                (force, dryRun) -> {
                    closeCalls.add(new CloseCall(force, dryRun));
                    return pending;
                };
        menu = new SeasonCloseConfirmMenu(
                plugin,
                menus,
                MiniMessage.miniMessage(),
                () -> currentSeason[0],
                () -> System.currentTimeMillis() + 86_400_000L,
                () -> "Sezon Letni 2026",
                () -> "Następna edycja: Sezon Jesienny 2026.",
                closer,
                backActions::add);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("layout: czerwona rama, strzałka powrotu 11, TNT 13, świecący POTWIERDZAM 15")
    void confirmLayoutShowsBackSummaryAndGlintedConfirm() {
        menu.populate(player); // otwarcie przy sezonie 5

        CapturingMenuService.CapturingMenu built = menus.last;
        assertEquals(27, built.size(), "ekran potwierdzenia ma mieć 3 rzędy");
        assertEquals("Potwierdź zamknięcie sezonu",
                PlainTextComponentSerializer.plainText().serialize(built.title));

        assertEquals(Material.RED_STAINED_GLASS_PANE, built.items.get(0).getType(),
                "rama ma być czerwona — operacja destrukcyjna");
        assertEquals(Material.ARROW, built.items.get(11).getType(), "powrót do panelu");
        assertNotNull(built.handlers.get(11), "strzałka powrotu ma być klikalna");

        assertEquals(Material.TNT, built.items.get(13).getType(), "podsumowanie TNT");
        assertNull(built.handlers.get(13), "podsumowanie nie może być klikalne");

        assertEquals(Material.RED_CONCRETE, built.items.get(15).getType(),
                "POTWIERDZAM ma być czerwonym betonem (glint)");
        assertNotNull(built.handlers.get(15), "POTWIERDZAM ma być klikalny");
        assertEquals(5, menu.renderedSeasonId(),
                "menu ma zapamiętać numer sezonu z chwili otwarcia");
    }

    @Test
    @DisplayName("dwuklik w trakcie lotu future odpala closer DOKŁADNIE raz; po zakończeniu guard puszcza")
    void doubleClickWhileInFlightFiresCloserOnce() {
        menu.populate(player);
        MenuService.ClickHandler confirm = menus.last.handlers.get(15);

        confirm.onClick(player, ClickType.LEFT);
        confirm.onClick(player, ClickType.LEFT); // drugi klik, future wciąż niedokończony

        assertEquals(1, closeCalls.size(),
                "strażnik in-flight ma zjeść podwójne kliknięcie");

        pending.complete(new SeasonEndService.CloseOutcome(
                true, 5, 6, 0L, 0, 0, 0, null)); // koniec operacji → guard zdjęty

        confirm.onClick(player, ClickType.LEFT);
        assertEquals(2, closeCalls.size(),
                "po zakończeniu poprzedniej operacji świadomy kolejny klik ma przejść");
        assertEquals(List.of(new CloseCall(true, false), new CloseCall(true, false)),
                closeCalls,
                "potwierdzenie ma ZAWSZE wołać closeSeason(true,false), nigdy dry-run");
    }

    @Test
    @DisplayName("nieaktualny sezon (rollover w tle) blokuje zamknięcie i wraca do panelu")
    void staleSeasonBlocksCloseAndReturnsToPanel() {
        menu.populate(player); // przechwycono sezon 5
        currentSeason[0] = 6;  // symulacja: ktoś zamknął sezon w innym oknie

        menus.last.handlers.get(15).onClick(player, ClickType.LEFT);

        assertTrue(closeCalls.isEmpty(),
                "rozjazd numeru sezonu musi zablokować zamknięcie — zero wywołań closera");
        String chat = String.valueOf(player.nextMessage());
        assertTrue(chat.contains("Ekran nieaktualny"),
                "operator ma dostać komunikat o nieaktualnym ekranie: " + chat);
        assertEquals(List.of(player), backActions,
                "po odmowie GUI ma zawrócić operatora do panelu");
    }

    /** Serwis menu przechwytujący zawartość do asercji layoutu (wzorzec CosmeticPlayerMenuTest). */
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
            private final Map<Integer, ItemStack> items = new HashMap<>();
            private final Map<Integer, MenuService.ClickHandler> handlers = new HashMap<>();

            private CapturingMenu(int rows, net.kyori.adventure.text.Component title) {
                this.rows = rows;
                this.title = title;
            }

            @Override public int size() {
                return rows * 9;
            }

            @Override public boolean add(@NotNull ItemStack icon, @NotNull MenuService.ClickHandler handler) {
                return false;
            }

            @Override public @NotNull Menu set(int slot, @NotNull ItemStack icon,
                                               @NotNull MenuService.ClickHandler handler) {
                items.put(slot, icon);
                handlers.put(slot, handler);
                return this;
            }

            @Override public @NotNull Menu decoration(int slot, @NotNull ItemStack icon) {
                items.put(slot, icon);
                handlers.remove(slot);
                return this;
            }

            @Override public void open(@NotNull Player viewer) { }
        }
    }
}
