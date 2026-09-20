package org.rafalohaki.wpmecore.addons.skyblock.island;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.ProfileStateService;
import org.rafalohaki.wpmecore.addons.skyblock.playtime.ProfilePlaytimeDao;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M1-C: Centrum Wyspy na MockBukkit (realne ItemStacki przez RegistryAccess).
 * Weryfikuje: otwarcie menu z wyspą (tryb CLASSIC fallback), ścieżkę
 * "brak wyspy" z komunikatem /is create, brak fałszywych przycisków oraz to,
 * że siatka biomów nie wchodzi pod stopkę i da się ją stronicować.
 *
 * <p>Atrapa menu rejestruje handlery slotów, więc test może „kliknąć" kafel
 * dokładnie tak, jak robi to {@code MenuListener} na wątku encji gracza.
 */
class IslandCenterMenuTest {

    private org.mockbukkit.mockbukkit.ServerMock server;
    private Plugin pluginField;
    private SkylliaIntegration skyllia;
    private ProfileStateService profiles;
    private IslandCenterMenu menu;
    private UUID islandId;
    private PlayerMock player;
    private final List<Inventory> inventories = new ArrayList<>();
    private final List<Map<Integer, MenuService.ClickHandler>> slotHandlers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        pluginField = MockBukkit.createMockPlugin("CenterTestPlugin");
        var plugin = (org.bukkit.plugin.java.JavaPlugin) pluginField;

        skyllia = mock(SkylliaIntegration.class);
        profiles = mock(ProfileStateService.class);
        var playtimeDao = mock(ProfilePlaytimeDao.class);

        islandId = UUID.randomUUID();
        var flags = new IslandModeFlags(true, false, false); // M1-C: tylko CLASSIC

        org.rafalohaki.wpmecore.api.service.MenuService menus =
                mock(org.rafalohaki.wpmecore.api.service.MenuService.class);
        when(menus.ofRows(anyInt(), any())).thenAnswer(invocation -> newMenuStub());

        menu = new IslandCenterMenu(plugin, menus,
                MiniMessage.miniMessage(), skyllia, profiles, playtimeDao, flags);
    }

    /** Realne menu na MockBukkit inventory — Ui.item tworzy prawdziwe ItemStacki. */
    private MenuService.Menu newMenuStub() {
        Inventory inv = server.createInventory(null, 54, "menu");
        Map<Integer, MenuService.ClickHandler> handlers = new HashMap<>();
        inventories.add(inv);
        slotHandlers.add(handlers);
        return new MenuService.Menu() {
            @Override public int size() { return inv.getSize(); }
            @Override public boolean add(@NotNull ItemStack icon, @NotNull MenuService.ClickHandler handler) { return true; }
            @Override public @NotNull MenuService.Menu set(int slot, @NotNull ItemStack icon, @NotNull MenuService.ClickHandler handler) {
                inv.setItem(slot, icon);
                handlers.put(slot, handler);
                return this;
            }
            @Override public @NotNull MenuService.Menu decoration(int slot, @NotNull ItemStack icon) {
                inv.setItem(slot, icon);
                handlers.remove(slot);
                return this;
            }
            @Override public void open(@NotNull org.bukkit.entity.Player viewer) { viewer.openInventory(inv); }
        };
    }

    private Inventory lastInventory() {
        return inventories.get(inventories.size() - 1);
    }

    private void click(int slot) {
        MenuService.ClickHandler handler = slotHandlers.get(slotHandlers.size() - 1).get(slot);
        assertNotNull(handler, "slot " + slot + " nie ma akcji, więc klik nie może nic zrobić");
        handler.onClick(player, ClickType.LEFT);
    }

    /** Gracz-właściciel z wyspą; profil pusty = wyspa na szablonie klasycznym. */
    private void openCenterAsOwner() {
        when(skyllia.islandOf(any(UUID.class))).thenReturn(Optional.of(new IslandView(
                new IslandSnapshot(islandId, null, null, null, null, 0L), IslandRole.OWNER)));
        when(profiles.profile(islandId))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(skyllia.warpsOf(islandId)).thenReturn(List.of());
        when(skyllia.biomeOf(islandId)).thenReturn("minecraft:plains");
        player = server.addPlayer();
        assertDoesNotThrow(() -> menu.open(player));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void noIslandShowsCreateHint() {
        when(skyllia.islandOf(any(UUID.class))).thenReturn(Optional.empty());
        var player = server.addPlayer();

        assertDoesNotThrow(() -> menu.open(player));
        // Komunikat z hintem /is create został dostarczony do gracza
        assertTrue(((org.mockbukkit.mockbukkit.entity.PlayerMock) player).nextMessage() != null,
                "gracz bez wyspy musi dostać komunikat");
    }

    @Test
    void islandOwnerOpensCenterWithClassicFallbackMode() {
        when(skyllia.islandOf(any(UUID.class))).thenReturn(Optional.of(new IslandView(
                new IslandSnapshot(islandId, null, null, null, null, 0L), IslandRole.OWNER)));
        when(profiles.profile(islandId))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));
        when(skyllia.warpsOf(islandId)).thenReturn(List.of());
        when(skyllia.biomeOf(islandId)).thenReturn("PLAINS");

        var player = server.addPlayer();
        assertDoesNotThrow(() -> menu.open(player));
        verify(skyllia).islandOf(player.getUniqueId());
        // brak profilu → resolveMode spada do CLASSIC — jedyny tryb w M1-C
    }

    /**
     * A4/A11: biomów jest więcej niż jedna strona. Stopka (powrót, zamknięcie,
     * stronicowanie) musi stać w rzędzie wolnym od treści, a nadmiar biomów ma
     * być osiągalny na kolejnej stronie — nie ucięty po cichu ani nadpisany.
     */
    @Test
    void biomeGridDoesNotCollideWithFooterAndPaginatesRest() {
        openCenterAsOwner();
        when(skyllia.biomeNames()).thenReturn(List.of(
                "minecraft:plains", "minecraft:desert", "minecraft:forest", "minecraft:snowy_plains",
                "minecraft:jungle", "minecraft:swamp", "minecraft:taiga", "minecraft:savanna",
                "minecraft:badlands", "minecraft:beach", "minecraft:ocean", "minecraft:river",
                "minecraft:birch_forest", "minecraft:dark_forest", "minecraft:cherry_grove", "minecraft:meadow",
                "minecraft:grove", "minecraft:snowy_slopes", "minecraft:jagged_peaks", "minecraft:stony_peaks",
                "minecraft:windswept_hills", "minecraft:lush_caves", "minecraft:dripstone_caves", "minecraft:deep_dark",
                "minecraft:nether_wastes", "minecraft:crimson_forest", "minecraft:warped_forest", "minecraft:the_end",
                "minecraft:end_barrens", "minecraft:the_void"));

        click(19); // kafel „Biom” z Centrum Wyspy

        assertEquals(28, biomeTiles(lastInventory()),
                "pierwsza strona musi pokazać pełne 28 biomów — stopka nie może ich nadpisywać");
        Inventory firstPage = lastInventory();
        assertEquals(Material.GRAY_DYE, firstPage.getItem(45).getType(), "pierwsza strona nie ma poprzedniej");
        assertEquals(Material.ARROW, firstPage.getItem(48).getType());
        assertEquals(Material.BARRIER, firstPage.getItem(49).getType());
        assertEquals(Material.SPECTRAL_ARROW, firstPage.getItem(53).getType(), "29. i 30. biom muszą być osiągalne");

        click(53); // następna strona
        assertEquals(2, biomeTiles(lastInventory()),
                "druga strona pokazuje pozostałe dwa biomy");
        assertEquals(Material.ARROW, lastInventory().getItem(45).getType(), "z drugiej strony wraca się na pierwszą");
    }

    /**
     * Ile kafli treści stoi w czterech rzędach siatki (10..43) poza obwódką.
     * Pełne 28 oznacza, że żaden slot siatki nie został oddany stopce.
     */
    private static int biomeTiles(Inventory inventory) {
        int tiles = 0;
        for (int slot = 10; slot <= 43; slot++) {
            int column = slot % 9;
            if (column == 0 || column == 8) {
                continue;
            }
            ItemStack item = inventory.getItem(slot);
            assertNotNull(item, "slot " + slot + " siatki nie może być pusty");
            if (!isFooterOrFrame(item)) {
                tiles++;
            }
        }
        return tiles;
    }

    private static boolean isFooterOrFrame(ItemStack item) {
        return switch (item.getType()) {
            case ARROW, BARRIER, GRAY_DYE, SPECTRAL_ARROW,
                 BLACK_STAINED_GLASS_PANE, LIGHT_BLUE_STAINED_GLASS_PANE -> true;
            default -> false;
        };
    }

    /**
     * Migracja #13: kafel przeglądu pokazuje tytuł, który wyspa naprawdę nosi
     * (magazyn tytułów), a bez tytułu nie pokazuje tej linii wcale. Wartość
     * startowa idzie z migawki cache — na wątku regionu nie ma zapytania SQL.
     */
    @Test
    void overviewTileShowsTheIslandTitleOnlyWhenTheIslandHasOne() {
        openCenterAsOwner();
        ItemStack withoutTitle = lastInventory().getItem(10);
        assertEquals(Material.GRASS_BLOCK, withoutTitle.getType());
        assertFalse(loreText(withoutTitle).stream().anyMatch(line -> line.contains("Tytuł wyspy")),
                "bez tytułu kafel nie może obiecywać linii tytułu: " + loreText(withoutTitle));

        IslandTitleService titles = mock(IslandTitleService.class);
        lenient().when(titles.cachedTitle(islandId))
                .thenReturn(Optional.of("<gold>Władca Lotosu</gold>"));
        lenient().when(titles.titleOf(islandId))
                .thenReturn(CompletableFuture.completedFuture(
                        Optional.of("<gold>Władca Lotosu</gold>")));
        menu.setTitleService(titles);
        openCenterAsOwner();

        assertTrue(loreText(lastInventory().getItem(10)).stream()
                        .anyMatch(line -> line.contains("Tytuł wyspy: Władca Lotosu")),
                "tytuł wyspy musi być widoczny w kafelku przeglądu: "
                        + loreText(lastInventory().getItem(10)));
    }

    private static List<String> loreText(ItemStack item) {
        List<net.kyori.adventure.text.Component> lore = item.lore();
        if (lore == null) {
            return List.of();
        }
        return lore.stream()
                .map(component -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(component))
                .toList();
    }

    /**
     * A9/A10: kafel bez akcji nie może udawać przycisku. Skyllia nie ma ani
     * zarządzania odwiedzinami, ani zbiorczego resetu uprawnień — oba sloty są
     * dekoracjami (bez handlera), więc klik nie może obiecywać niczego.
     */
    @Test
    void visitsAndPermissionsTilesAreDecorationsNotButtons() {
        openCenterAsOwner();
        assertTrue(slotHandlers.get(slotHandlers.size() - 1).containsKey(13), "kafel „Dostęp” musi mieć akcję");
        assertFalse(slotHandlers.get(slotHandlers.size() - 1).containsKey(15),
                "kafel „Odwiedziny” obiecywał zarządzanie, którego Skyllia nie ma");

        click(13); // ekran „Dostęp”
        Map<Integer, MenuService.ClickHandler> accessHandlers = slotHandlers.get(slotHandlers.size() - 1);
        assertTrue(accessHandlers.containsKey(11), "przełącznik dostępu musi realnie działać");
        assertFalse(accessHandlers.containsKey(15),
                "„Reset uprawnień” nie istnieje w Skyllii — kafel musi zostać dekoracją");
    }

    /**
     * Prestiż Wyspy (fail-closed): bez wstrzykniętego menu (config bez sekcji
     * {@code island.prestige}) slot 34 nie dostaje ani kafelka, ani akcji, więc
     * klik nie może otworzyć funkcji, której nie ma. Z wstrzykniętym menu kafelek
     * renderuje się dokładnie w wolnym slocie 34 — bez kolizji z istniejącymi.
     */
    @Test
    void prestigeTileAppearsOnlyWhenPrestigeIsEnabled() {
        IslandPrestigeMenu prestige = mock(IslandPrestigeMenu.class);
        openCenterAsOwner();
        assertFalse(slotHandlers.get(slotHandlers.size() - 1).containsKey(34),
                "bez skonfigurowanego prestiżu slot 34 nie może mieć żadnej akcji");
        verify(prestige, never()).renderTile(any(), any(), anyInt(), any());

        menu.setPrestigeMenu(prestige);
        openCenterAsOwner();

        verify(prestige).renderTile(eq(player), any(), eq(34), eq(islandId));
    }
}
