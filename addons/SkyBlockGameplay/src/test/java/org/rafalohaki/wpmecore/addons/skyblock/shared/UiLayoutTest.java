package org.rafalohaki.wpmecore.addons.skyblock.shared;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Layout slotów helperów {@code wpme:gui} w {@link Ui}: separator jako linia
 * oddzielająca strefy menu oraz frame_accent jako wyróżnienie nagłówka.
 * Bez paczki klienta ({@code Ui.init(null)}) wszystko musi działać fail-open
 * na materiałach zapasowych — dokładnie tak, jak w grze przed załadowaniem paczki.
 */
@Tag("mockbukkit")
class UiLayoutTest {

    private ServerMock server;
    private final MiniMessage mm = MiniMessage.miniMessage();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        Ui.init(null); // fail-open: żadnej paczki w testach
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Minimalne menu przechwytujące dekoracje i akcje do mapy slotów. */
    private static final class SlotMapMenu implements MenuService.Menu {
        private final int rows;
        private final Map<Integer, ItemStack> items = new HashMap<>();
        private final Map<Integer, MenuService.ClickHandler> handlers = new HashMap<>();

        private SlotMapMenu(int rows) {
            this.rows = rows;
        }

        @Override public int size() {
            return rows * 9;
        }

        @Override public boolean add(@NotNull ItemStack icon, @NotNull MenuService.ClickHandler handler) {
            return false;
        }

        @Override public @NotNull MenuService.Menu set(int slot, @NotNull ItemStack icon,
                                                      @NotNull MenuService.ClickHandler handler) {
            items.put(slot, icon);
            handlers.put(slot, handler);
            return this;
        }

        @Override public @NotNull MenuService.Menu decoration(int slot, @NotNull ItemStack icon) {
            items.put(slot, icon);
            handlers.remove(slot);
            return this;
        }

        @Override public void open(@NotNull Player viewer) { }
    }

    @Test
    @DisplayName("separatorRow kładzie separatory we wnętrzu rzędu, omijając wskazane sloty")
    void separatorRowFillsInnerColumnsAndSkipsGivenSlots() {
        SlotMapMenu menu = new SlotMapMenu(6);

        // Układ stopki Kuźni: saldo 45, zamknięcie 49, reszta rzędu 5 pod linię.
        Ui.separatorRow(menu, mm, 5, 45, 49);

        for (int column = 0; column < 9; column++) {
            int slot = 45 + column;
            if (column == 0 || column == 8 || slot == 49) {
                assertNull(menu.items.get(slot), "slot " + slot + " ma zostać nietknięty");
            } else {
                assertEquals(Material.GRAY_STAINED_GLASS_PANE,
                        menu.items.get(slot).getType(),
                        "slot " + slot + " ma dostać linię separatora");
                assertNull(menu.handlers.get(slot), "separator nie może być klikalny");
            }
        }
        // Rzędy poza celem muszą zostać czyste.
        assertNull(menu.items.get(13));
        assertNull(menu.items.get(31));
    }

    @Test
    @DisplayName("separatorRow zostawia skrajne kolumny ramy nienaruszone")
    void separatorRowNeverTouchesTheOuterFrameColumns() {
        SlotMapMenu menu = new SlotMapMenu(4);

        Ui.separatorRow(menu, mm, 2);

        assertNull(menu.items.get(18), "kolumna 0 należy do ramy");
        assertNull(menu.items.get(26), "kolumna 8 należy do ramy");
        for (int slot = 19; slot <= 25; slot++) {
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, menu.items.get(slot).getType());
        }
    }

    @Test
    @DisplayName("ponowne położenie linii nadpisuje sloty zamiast dublować stan")
    void repeatedDecorationOverwritesInsteadOfStacking() {
        SlotMapMenu menu = new SlotMapMenu(3);

        Ui.separatorRow(menu, mm, 1);
        Ui.separatorRow(menu, mm, 1, 13);

        assertEquals(Material.GRAY_STAINED_GLASS_PANE, menu.items.get(12).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, menu.items.get(13).getType(),
                "pominięty slot zachowuje linię z pierwszego przebiegu");
        assertNull(menu.handlers.get(12));
    }

    @Test
    @DisplayName("separator i accent są fail-open: bez paczki zwracają materiał zapasowy")
    void separatorAndAccentFallBackToBaseMaterialWithoutThePack() {
        ItemStack line = Ui.separator(mm);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, line.getType());

        ItemStack accent = Ui.accent(mm, Material.COMPARATOR,
                "<white><bold>Tor: DARMOWY</bold></white>", List.of("<gray>opis</gray>"));
        assertEquals(Material.COMPARATOR, accent.getType());
    }
}
