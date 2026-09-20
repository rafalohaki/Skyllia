package org.rafalohaki.wpmecore.addons.skyblock.season;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.api.item.CustomItem;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.item.ItemPolicyMarkers;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2#2: menu gracza /kosmetyki — layout slotów, fail-open bez paczki
 * oraz idempotentne zakładanie i zdejmowanie egzemplarzy.
 *
 * <p>Bez paczki klienta ({@code Ui.init(null)}) wszystko musi działać na
 * materiałach zapasowych — dokładnie jak w grze przed załadowaniem paczki.
 */
@Tag("mockbukkit")
class CosmeticPlayerMenuTest {

    private static final String HELMET = "mystical_test_helmet";

    private ServerMock server;
    private PlayerMock player;
    private StubCustomItemService items;
    private CosmeticCatalog catalog;
    private CapturingMenuService menus;
    private CosmeticPlayerMenu menu;

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        Ui.init(null); // fail-open: żadnej paczki w testach

        player = server.addPlayer();
        items = new StubCustomItemService(Map.of(
                "mystical_core_test", Material.PAPER,
                HELMET, Material.NETHERITE_HELMET,
                "mystical_test_chestplate", Material.NETHERITE_CHESTPLATE,
                "mystical_test_leggings", Material.NETHERITE_LEGGINGS,
                "mystical_test_boots", Material.NETHERITE_BOOTS));
        catalog = CosmeticCatalog.parse(SeasonEditionFixtures.yaml("""
                schema-version: 1
                enabled: true
                season-collections:
                  1: test
                top-rewards:
                  1: 4
                collections:
                  test:
                    name: "<aqua><bold>Set Testowy</bold></aqua>"
                    core: mystical_core_test
                    content-version: 1
                    pieces:
                      - mystical_test_helmet
                      - mystical_test_chestplate
                      - mystical_test_leggings
                      - mystical_test_boots
                """), items);
        menus = new CapturingMenuService();
        org.bukkit.plugin.java.JavaPlugin plugin =
                MockBukkit.createMockPlugin("CosmeticPlayerMenuTest");
        // Kalendarz sezonu dla widoku gracza (edycja jako zakres dat).
        plugin.getConfig().set("season.enabled", true);
        plugin.getConfig().set("season.length-days", 56);
        plugin.getConfig().set("season.epoch-start", "2026-09-01T00:00:00Z");
        menu = new CosmeticPlayerMenu(
                plugin, menus, MiniMessage.miniMessage(), catalog, items);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Egzemplarz jak z {@code CosmeticService.stamp}: CustomItem + markery proweniencji. */
    private ItemStack stamped(String pieceId) {
        ItemStack stack = items.create(pieceId).orElseThrow();
        ItemPolicyMarkers.markCollectible(stack, "TOP1", "S1", 1, true);
        return stack;
    }

    /** Suma egzemplarzy hełmu: plecak + slot głowy. */
    private int countHelmets(PlayerMock target) {
        int total = 0;
        for (ItemStack item : target.getInventory().getStorageContents()) {
            if (item != null && !item.isEmpty() && HELMET.equals(items.idOf(item))) {
                total++;
            }
        }
        ItemStack head = target.getInventory().getItem(EquipmentSlot.HEAD);
        if (head != null && !head.isEmpty() && HELMET.equals(items.idOf(head))) {
            total++;
        }
        return total;
    }

    @Test
    @DisplayName("layout: siatka posiadanych, separator, podgląd noszenia i stopka mają swoje strefy")
    void layoutSeparatesOwnedGridSeparatorWornPreviewAndFooter() {
        player.getInventory().setItem(0, stamped(HELMET));

        menu.buildAndOpen(player);
        CapturingMenuService.CapturingMenu built = menus.last;

        assertEquals(54, built.size(), "menu ma mieć 6 rzędów");

        // Siatka posiadanych: dokładnie jedna klikalna część, na pierwszym slocie siatki.
        assertNotNull(built.handlers.get(CosmeticPlayerMenu.FIRST_PIECE_SLOT),
                "posiadana część ma być klikalna");
        assertNull(built.handlers.get(CosmeticPlayerMenu.FIRST_PIECE_SLOT + 1),
                "drugi slot siatki ma być wolny — jest tylko jeden egzemplarz");
        assertEquals(Material.NETHERITE_HELMET,
                built.items.get(CosmeticPlayerMenu.FIRST_PIECE_SLOT).getType());

        // Separator oddziela siatkę od podglądu noszenia; nic nie jest klikalne.
        for (int slot = 37; slot <= 43; slot++) {
            assertEquals(Material.GRAY_STAINED_GLASS_PANE, built.items.get(slot).getType(),
                    "slot " + slot + " ma dostać linię separatora");
            assertNull(built.handlers.get(slot), "separator nie może być klikalny");
        }

        // Podgląd noszenia: cztery puste sloty ciała jako dekoracje.
        for (int slot : CosmeticPlayerMenu.WORN_SLOTS) {
            assertNotNull(built.items.get(slot), "podgląd ciała " + slot + " ma istnieć");
            assertNull(built.handlers.get(slot), "pusty slot ciała nie może być klikalny");
        }

        // Stopka: jedyny inny punkt klikalny to zamknięcie.
        assertNotNull(built.handlers.get(53), "przycisk zamknięcia ma być klikalny");
        for (Map.Entry<Integer, MenuService.ClickHandler> entry : built.handlers.entrySet()) {
            assertTrue(entry.getKey() == CosmeticPlayerMenu.FIRST_PIECE_SLOT
                            || entry.getKey() == 53,
                    "nieoczekiwany klikalny slot: " + entry.getKey());
        }
    }

    @Test
    @DisplayName("fail-open: bez paczki ikony zostają przy materiale zapasowym, menu się otwiera")
    void menuFailsOpenWithoutClientPack() {
        player.getInventory().setItem(0, stamped(HELMET));

        menu.buildAndOpen(player); // bez wyjątku = fail-open działa

        CapturingMenuService.CapturingMenu built = menus.last;
        ItemStack ownedIcon = built.items.get(CosmeticPlayerMenu.FIRST_PIECE_SLOT);
        assertEquals(Material.NETHERITE_HELMET, ownedIcon.getType(),
                "bez paczki ikona ma zostać egzemplarzem z materiałem zapasowym");
        assertTrue(ItemPolicyMarkers.isCollectible(ownedIcon),
                "ikona to kopia egzemplarza — markery proweniencji muszą przetrwać");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE,
                built.items.get(40).getType(),
                "separator bez paczki ma być zwykłą szybą zapasową");
    }

    @Test
    @DisplayName("lore proweniencji pokazuje ZAKRES DAT edycji, nigdy S<n>; marker PDC nietknięty")
    void provenanceLoreShowsDateRangeInsteadOfSeasonNumber() {
        player.getInventory().setItem(0, stamped(HELMET)); // marker PDC: „S1”

        menu.buildAndOpen(player);
        ItemStack icon = menus.last.items.get(CosmeticPlayerMenu.FIRST_PIECE_SLOT);

        String lore = java.util.Objects.requireNonNull(
                        icon.getItemMeta().lore(), "ikona ma mieć lore proweniencji").stream()
                .map(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText()::serialize)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(lore.contains("01.09 – 27.10.2026"),
                "edycja ma być zakresem dat z kalendarza sezonu: " + lore);
        assertTrue(!lore.contains("S1"),
                "numer sezonu nie może wyciekać do widoku gracza: " + lore);
        // Wewnętrzny marker PDC na egzemplarzu zostaje bez zmian.
        assertEquals("S1", ItemPolicyMarkers.collectibleEdition(icon).orElseThrow());
    }

    @Test
    @DisplayName("zakładanie i zdejmowanie jest idempotentne — żadnych duplikatów ani zniknięć")
    void wearingAndRemovingIsIdempotent() {
        player.getInventory().setItem(0, stamped(HELMET));

        menu.wear(player, HELMET);
        assertEquals(1, countHelmets(player));
        assertNotNull(player.getInventory().getItem(EquipmentSlot.HEAD),
                "hełm ma być w slocie głowy");
        assertTrue(isEmptySlot(player.getInventory().getItem(0)),
                "egzemplarz ma opuścić plecak");

        menu.wear(player, HELMET); // ponowne założenie już noszonego: no-op
        assertEquals(1, countHelmets(player), "ponowne założenie nie może dublować");
        assertNotNull(player.getInventory().getItem(EquipmentSlot.HEAD));

        menu.unequip(player, HELMET);
        assertEquals(1, countHelmets(player));
        assertTrue(isEmptySlot(player.getInventory().getItem(EquipmentSlot.HEAD)),
                "po zdjęciu slot głowy ma być pusty");

        menu.unequip(player, HELMET); // ponowne zdjęcie niezakładanego: no-op
        assertEquals(1, countHelmets(player), "ponowne zdjęcie nie może nic zgubić ani dodać");
        assertTrue(isEmptySlot(player.getInventory().getItem(EquipmentSlot.HEAD)));
        assertNotNull(player.getInventory().getItem(0), "egzemplarz ma wrócić do plecaka");
    }

    /** MockBukkit zwraca AIR dla pustych slotów — traktujemy null i AIR jednakowo. */
    private static boolean isEmptySlot(@Nullable ItemStack item) {
        return item == null || item.isEmpty();
    }

    @Test
    @DisplayName("jedna część na slot ciała: zakładanie drugiego hełmu zwraca pierwszy do plecaka")
    void wearingAnotherPieceSwapsThePreviousOneBackToStorage() {
        player.getInventory().setItem(0, stamped(HELMET));
        ItemStack second = stamped(HELMET);
        ItemPolicyMarkers.markCollectible(second, "SKLEP", "S1", 1, true); // inna proweniencja
        player.getInventory().setItem(1, second);

        menu.wear(player, HELMET);
        assertNotNull(player.getInventory().getItem(EquipmentSlot.HEAD));

        menu.wear(player, HELMET); // drugi egzemplarz tej samej części
        assertEquals(2, countHelmets(player), "oba egzemplarze mają istnieć");
        assertNotNull(player.getInventory().getItem(EquipmentSlot.HEAD),
                "slot głowy nadal zajęty przez jeden egzemplarz");
        long inStorage = java.util.Arrays.stream(player.getInventory().getStorageContents())
                .filter(item -> item != null && !item.isEmpty()
                        && HELMET.equals(items.idOf(item)))
                .count();
        assertEquals(1, inStorage, "poprzedni egzemplarz ma wrócić do plecaka");
    }

    @Test
    @DisplayName("kliknięcie ikony posiadanej części wywołuje założenie")
    void clickingAnOwnedPieceWearsIt() {
        player.getInventory().setItem(0, stamped(HELMET));
        menu.buildAndOpen(player);

        menus.last.handlers.get(CosmeticPlayerMenu.FIRST_PIECE_SLOT)
                .onClick(player, ClickType.LEFT);

        assertNotNull(player.getInventory().getItem(EquipmentSlot.HEAD),
                "kliknięcie ma założyć część");
    }

    /** MenuService przechwytujący zawartość do asercji layoutu (wzorzec UiLayoutTest). */
    private static final class CapturingMenuService implements MenuService {

        private CapturingMenu last;

        @Override
        public @NotNull Menu rowsFor(int itemCount, @NotNull net.kyori.adventure.text.Component title) {
            return ofRows((itemCount + 8) / 9, title);
        }

        @Override
        public @NotNull Menu ofRows(int rows, @NotNull net.kyori.adventure.text.Component title) {
            last = new CapturingMenu(rows);
            return last;
        }

        private static final class CapturingMenu implements Menu {

            private final int rows;
            private final Map<Integer, ItemStack> items = new HashMap<>();
            private final Map<Integer, MenuService.ClickHandler> handlers = new HashMap<>();

            private CapturingMenu(int rows) {
                this.rows = rows;
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

    /**
     * CustomItemService z działającym {@code create}/{@code idOf}: identyfikator
     * niesie PDC, więc menu rozpoznaje egzemplarze tak samo jak na produkcji.
     */
    private static final class StubCustomItemService implements CustomItemService {

        private final NamespacedKey idKey =
                new NamespacedKey("wpme", "test_custom_id");
        private final Map<String, Material> known;

        private StubCustomItemService(Map<String, Material> known) {
            this.known = Map.copyOf(known);
        }

        @Override public @NotNull Collection<CustomItem> all() {
            return known.keySet().stream().map(this::stub).toList();
        }

        @Override public @NotNull Optional<CustomItem> byId(@NotNull String id) {
            return Optional.ofNullable(known.get(id.toLowerCase())).map(material -> stub(id));
        }

        private CustomItem stub(String id) {
            return new CustomItem(id, known.get(id.toLowerCase()), null, List.of(), false, Map.of());
        }

        @Override public @NotNull Optional<ItemStack> create(@NotNull String id) {
            Material material = known.get(id.toLowerCase());
            if (material == null) {
                return Optional.empty();
            }
            ItemStack stack = new ItemStack(material);
            stack.editMeta(meta -> meta.getPersistentDataContainer()
                    .set(idKey, PersistentDataType.STRING, id));
            return Optional.of(stack);
        }

        @Override public @Nullable String idOf(@Nullable ItemStack stack) {
            if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
                return null;
            }
            return stack.getItemMeta().getPersistentDataContainer()
                    .get(idKey, PersistentDataType.STRING);
        }
    }
}
