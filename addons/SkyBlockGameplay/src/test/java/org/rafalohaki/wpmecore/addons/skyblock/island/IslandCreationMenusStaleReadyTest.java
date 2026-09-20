package org.rafalohaki.wpmecore.addons.skyblock.island;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.ProfileStateService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regresja GUI dead-endu „Tworzenie już w toku! Stan: READY”: potwierdzenie
 * utworzenia renderowało bloker dla DOWOLNEgo wpisu operacji, także martwego
 * READY po usunięciu wyspy — gracz nie mógł nigdy utworzyć wyspy ponownie.
 * Po poprawce: martwy wpis terminalny jest zdjęty na ścieżce pickera
 * ({@code clearFailed}), a bloker zostaje wyłącznie dla żywych stanów
 * CREATING/INITIALIZING. Czysty Mockito + przechwytujący MenuService
 * (wzorzec {@code SeasonAdminMenuLayoutTest}, bez MockBukkit).
 *
 * <p>Menu renderują prawdziwe {@link ItemStack}i przez {@link Ui#item}, co poza
 * serwerem wywala się na rejestrach Material→ItemType ({@code RegistryAccess}).
 * Konstrukcje są więc przechwytywane przez inline mock makera
 * ({@code mockConstruction}): atrapa raportuje materiał z konstruktora, a
 * {@code clone()} jest tożsamościowy — dokładnie tyle potrzebuje layout-menu.
 */
class IslandCreationMenusStaleReadyTest {

    private SkylliaIntegration skyllia;
    private ProfileStateService profiles;
    private IslandCreationCoordinator coordinator;
    private IslandCreationMenus menus;
    private CapturingMenuService menuService;

    /** false = Skyllia nie raportuje wyspy (przed create / po delete); true = raportuje. */
    private final AtomicBoolean skylliaHasIsland = new AtomicBoolean(false);

    @BeforeEach
    void setUp() {
        Ui.init(null); // fail-open: żadnej paczki zasobów w testach
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("StaleReadyTest"));
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("island.paste.enabled", false);
        when(plugin.getConfig()).thenReturn(cfg);

        skyllia = mock(SkylliaIntegration.class);
        profiles = mock(ProfileStateService.class);
        when(skyllia.purgeSoftDeletedIsland(any(UUID.class))).thenReturn(0);
        when(skyllia.islandOf(any(UUID.class))).thenAnswer(inv -> skylliaHasIsland.get()
                ? Optional.of(new IslandView(new IslandSnapshot(
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        null, null, null, null, 0L), IslandRole.OWNER))
                : Optional.empty());
        when(profiles.tryCreateIsland(any(UUID.class), any(UUID.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        coordinator = new IslandCreationCoordinator(plugin, skyllia, profiles,
                new IslandModeFlags(true, false, false));
        menuService = new CapturingMenuService();
        menus = new IslandCreationMenus(plugin, menuService, MiniMessage.miniMessage(),
                skyllia, profiles, new IslandModeFlags(true, false, false), coordinator);
    }

    /**
     * Otwiera menu w zasięgu z przechwyconymi konstrukcjami {@link ItemStack}
     * (poza serwerem realna konstrukcja wymaga rejestrów Paper).
     */
    private void openingMenu(@NotNull Runnable action) {
        try (MockedConstruction<ItemStack> stacks = mockConstruction(ItemStack.class,
                (mock, context) -> {
                    Material material = context.arguments().isEmpty()
                            ? Material.STONE
                            : (Material) context.arguments().get(0);
                    when(mock.getType()).thenReturn(material);
                    when(mock.clone()).thenReturn(mock);
                })) {
            action.run();
        }
    }

    /** Udany create → op READY przy istniejącej wyspie (scenariusz sprzed usunięcia). */
    private void successfulCreate(UUID playerId) throws Exception {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Tester");
        when(skyllia.createIsland(any(UUID.class), eq("starter"), anyString())).thenReturn(true);
        assertEquals(IslandCreationCoordinator.CreateResult.Kind.SUCCESS,
                coordinator.create(player, "classic").get(10, TimeUnit.SECONDS).kind());
        skylliaHasIsland.set(true); // po udanym create wyspa istnieje do czasu delete
    }

    @Test
    @DisplayName("martwy READY: openPicker czyści wpis i otwiera wybór trybu")
    void staleReadyDoesNotBlockPicker() throws Exception {
        UUID playerId = UUID.randomUUID();
        successfulCreate(playerId);
        skylliaHasIsland.set(false); // gracz usunął wyspę

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        openingMenu(() -> menus.openPicker(player));

        // plain() zdejmuje znaczniki MiniMessage — porównujemy sam tekst.
        assertEquals("Wybierz, jak chcesz zacząć", plain(menuService.last.title),
                "picker ma się otworzyć zamiast blokera statusu");
        assertNotNull(menuService.last.handlers.get(11), "tryb CLASSIC ma być klikalny");
        assertNull(coordinator.operationOf(playerId),
                "martwy wpis terminalny ma być zdjęty już na ścieżce pickera");
    }

    @Test
    @DisplayName("martwy READY: ścieżka picker→potwierdzenie (2 okna) ma przycisk POTWIERDZAM")
    void fullJourneyForStaleReadyEndsInConfirmButton() throws Exception {
        UUID playerId = UUID.randomUUID();
        successfulCreate(playerId);
        skylliaHasIsland.set(false);

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);

        openingMenu(() -> menus.openPicker(player));
        assertNull(coordinator.operationOf(playerId), "po pickerze wpis ma być zdjęty");
        // UX 2-okna: klik karty prowadzi od razu do potwierdzenia (okno
        // „szczegóły” usunięte ze ścieżki).
        openingMenu(() -> menuService.last.handlers.get(11).onClick(player, ClickType.LEFT));

        assertEquals("Potwierdź utworzenie", plain(menuService.last.title));
        assertNotNull(menuService.last.items.get(11), "przycisk POTWIERDZAM ma być na slocie 11");
        assertEquals(Material.LIME_CONCRETE, menuService.last.items.get(11).getType(),
                "martwy READY nie może renderować dead-endu — ma być zwykłe potwierdzenie");
        var slot13 = menuService.last.items.get(13);
        assertTrue(slot13 == null || slot13.getType() != Material.ORANGE_CONCRETE,
                "martwy READY nie może renderować blokera ORANGE_CONCRETE na 13");
    }

    @Test
    @DisplayName("ANULUJ czyści martwy wpis terminalny i wraca do pickera (cancel-must-clear)")
    void cancelClearsStaleTerminalOperation() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);

        // Wejście czystą ścieżką: picker → klik karty → potwierdzenie.
        openingMenu(() -> menus.openPicker(player));
        openingMenu(() -> menuService.last.handlers.get(11).onClick(player, ClickType.LEFT));
        assertEquals("Potwierdź utworzenie", plain(menuService.last.title));

        // W międzyczasie pojawił się martwy wpis READY (create → usunięcie wyspy).
        successfulCreate(playerId);
        skylliaHasIsland.set(false);
        assertNotNull(coordinator.operationOf(playerId),
                "martwy wpis terminalny ma istnieć przed anulowaniem");

        openingMenu(() -> menuService.last.handlers.get(15).onClick(player, ClickType.LEFT)); // ANULUJ

        assertNull(coordinator.operationOf(playerId),
                "anulowanie ma zdjąć martwy wpis terminalny z rejestru");
        assertEquals("Wybierz, jak chcesz zacząć", plain(menuService.last.title),
                "ANULUJ ma wrócić do wyboru trybu, nie zostawiać dead-endu");
    }

    @Test
    @DisplayName("żywa operacja CREATING: picker nadal przekierowuje do statusu")
    void liveCreatingOperationStillRedirectsToStatus() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        when(skyllia.createIsland(any(UUID.class), eq("starter"), anyString()))
                .thenAnswer(inv -> {
                    gate.await(5, TimeUnit.SECONDS);
                    return true;
                });
        skylliaHasIsland.set(false); // wyspa jeszcze nie powstała

        UUID playerId = UUID.randomUUID();
        Player creating = mock(Player.class);
        when(creating.getUniqueId()).thenReturn(playerId);
        when(creating.getName()).thenReturn("Tester");
        CompletableFuture<IslandCreationCoordinator.CreateResult> inFlight =
                coordinator.create(creating, "classic");
        try {
            awaitTrue(java.time.Duration.ofSeconds(2),
                    () -> coordinator.operationOf(playerId) != null,
                    "operacja w toku ma być w rejestrze");

            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(playerId);
            openingMenu(() -> menus.openPicker(player));

            assertTrue(plain(menuService.last.title).contains("Status tworzenia"),
                    "żywa operacja ma przekierować do ekranu statusu, było: "
                            + plain(menuService.last.title));
        } finally {
            gate.countDown();
            inFlight.get(10, TimeUnit.SECONDS);
        }
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static void awaitTrue(java.time.Duration timeout, BooleanSupplier condition,
                                  String message) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail(message + " (timeout " + timeout.getSeconds() + " s)");
    }

    /** Serwis menu przechwytujący zawartość do asercji layoutu (wzorzec layout-testów). */
    private static final class CapturingMenuService implements MenuService {

        private CapturingMenu last;

        @Override
        public @NotNull MenuService.Menu rowsFor(int itemCount, @NotNull net.kyori.adventure.text.Component title) {
            return ofRows((itemCount + 8) / 9, title);
        }

        @Override
        public @NotNull MenuService.Menu ofRows(int rows, @NotNull net.kyori.adventure.text.Component title) {
            last = new CapturingMenu(rows, title);
            return last;
        }
    }

    private static final class CapturingMenu implements MenuService.Menu {

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

        @Override public boolean add(@NotNull ItemStack icon,
                                     @NotNull MenuService.ClickHandler handler) {
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
}
