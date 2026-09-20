package org.rafalohaki.wpmecore.addons.skyblock.island;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.rafalohaki.wpmecore.addons.skyblock.economy.AccountKey;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Menu Prestiżu: właściciel kupuje przez potwierdzenie, członek widzi sam stan,
 * brak monet kończy się komunikatem (nie zakupem), a rola właściciela jest
 * potwierdzana autorytatywnie także wtedy, gdy kafel zdążył już wystawić
 * przycisk. Scheduler encji jest atrapą zbierającą zadania — test uruchamia je
 * ręcznie, więc widać dokładnie, co dzieje się przed powrotem na wątek encji.
 */
class IslandPrestigeMenuTest {

    private static final IslandPrestige.Settings SETTINGS = new IslandPrestige.Settings(
            3, 1_000L, 2.0D, List.of("Rybacka", "Kupiecka", "Złota"));

    private SingleConnectionSqlService sql;
    private LedgerService ledger;
    private IslandTitleService titles;
    private IslandPrestigeService service;
    private IslandPrestigeMenu menu;
    private CapturingMenuService menus;
    private Player player;
    private PlayerInventory inventory;
    private ItemStack[] storage;
    private CustomItemService customItems;
    private UUID islandId;
    private IslandRole authoritativeRole;
    /** Rola gracza w widoku wyspy — testy podmieniają ją między scenariuszami. */
    private IslandRole viewRole;
    private final List<IslandPrestigeService.PrestigeUp> broadcasts = new ArrayList<>();
    private final List<Runnable> entityTasks = new ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        Ui.init(null);
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        ledger = new LedgerService(new LedgerDao(sql), 0L);
        titles = new IslandTitleService(new IslandTitleDao.Sql(sql));
        service = new IslandPrestigeService(Logger.getLogger("PrestigeMenuTest"), SETTINGS,
                new IslandPrestigeDao.Sql(sql), ledger, titles, broadcasts::add);
        islandId = UUID.randomUUID();
        authoritativeRole = IslandRole.OWNER;
        viewRole = IslandRole.OWNER;

        JavaPlugin plugin = mock(JavaPlugin.class);
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("PrestigeTest"));
        Server server = mock(Server.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        lenient().when(plugin.getServer()).thenReturn(server);
        lenient().when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        lenient().when(asyncScheduler.runNow(any(), any())).thenAnswer(invocation -> {
            Consumer<ScheduledTask> task = invocation.getArgument(1);
            task.accept(null);
            return mock(ScheduledTask.class);
        });

        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);
        lenient().when(skyllia.islandOf(any(UUID.class))).thenAnswer(invocation ->
                Optional.of(new IslandView(new IslandSnapshot(islandId, null, null, null, null, 0L),
                        viewRole)));
        lenient().when(skyllia.authoritativeRole(any(), any()))
                .thenAnswer(invocation -> authoritativeRole);

        player = mock(Player.class);
        lenient().when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(player.isOnline()).thenReturn(true);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        lenient().when(player.getScheduler()).thenReturn(entityScheduler);
        lenient().when(entityScheduler.run(any(), any(), any())).thenAnswer(invocation -> {
            Consumer<ScheduledTask> task = invocation.getArgument(1);
            entityTasks.add(() -> task.accept(null));
            return mock(ScheduledTask.class);
        });

        /*
         * Ekwipunek gracza na prawdziwym kontrakcie `getStorageContents` /
         * `setStorageContents`: zlew Lotosów zapisuje wynik tej samej tablicy,
         * więc testy widzą, ile sztuk naprawdę zdjęto.
         */
        storage = new ItemStack[36];
        inventory = mock(PlayerInventory.class);
        lenient().when(player.getInventory()).thenReturn(inventory);
        lenient().when(inventory.getStorageContents()).thenAnswer(invocation -> storage.clone());
        lenient().doAnswer(invocation -> {
            ItemStack[] given = invocation.getArgument(0);
            System.arraycopy(given, 0, storage, 0, storage.length);
            return null;
        }).when(inventory).setStorageContents(any());

        customItems = mock(CustomItemService.class);
        menus = new CapturingMenuService();
        menu = new IslandPrestigeMenu(plugin, menus, MiniMessage.miniMessage(), skyllia, ledger,
                service, mock(IslandCenterMenu.class), titles, customItems);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void ownerBuysPrestigeThroughConfirmation() {
        seed(10_000L);
        openPrestigeMenu();

        assertNotNull(menus.last.handlers.get(24), "właściciel z monetami widzi przycisk zakupu");
        assertNotNull(menus.last.handlers.get(36), "powrót do Centrum Wyspy");
        assertNotNull(menus.last.handlers.get(40), "zamknięcie menu");

        click(24); // „Podnieś prestiż” → ekran potwierdzenia
        assertNotNull(menus.last.handlers.get(11), "potwierdzenie musi być klikalne");
        assertNotNull(menus.last.handlers.get(15), "anulowanie musi być klikalne");

        click(11); // TAK, PODNIEŚ PRESTIŻ

        assertEquals(9_000L, ledger.authoritativeIslandBalance(islandId).join(),
                "koszt prestiżu schodzi z konta wyspy");
        assertEquals(1, storedLevel());
        assertEquals(1, broadcasts.size(), "awans prestiżu ogłasza się wyspie raz");
        assertEquals(1, broadcasts.getFirst().level());
        assertTrue(messages().stream().anyMatch(text -> text.contains("Prestiż wyspy podniesiony")),
                "gracz musi dostać komunikat o podniesionym prestiżu: " + messages());
    }

    @Test
    void memberSeesStateWithoutBuyButton() {
        seed(10_000L);
        viewRole = IslandRole.MEMBER;
        openPrestigeMenu();

        assertNotNull(menus.last.handlers.get(36), "członek też może wrócić do Centrum Wyspy");
        assertFalse(menus.last.handlers.containsKey(24),
                "prestiż podnosi tylko właściciel — członek nie może dostać przycisku zakupu");
    }

    @Test
    void buyButtonWithoutFundsExplainsInsteadOfOpeningConfirmation() {
        seed(500L);
        openPrestigeMenu();

        assertNotNull(menus.last.handlers.get(24), "brak monet to nadal klikalny, wyjaśniający kafel");
        click(24);

        assertEquals(500L, ledger.authoritativeIslandBalance(islandId).join());
        assertEquals(-1, storedLevel(), "brak środków = brak zakupu");
        assertTrue(messages().stream().anyMatch(text -> text.contains("Brakuje")),
                "komunikat musi powiedzieć, ile monet brakuje: " + messages());
    }

    @Test
    void authoritativeRoleCheckBlocksBuyWhenRoleChangedAfterRender() {
        seed(10_000L);
        openPrestigeMenu();
        click(24); // potwierdzenie widoczne, bo w widoku gracz był właścicielem

        authoritativeRole = IslandRole.MEMBER; // rola zmieniła się między renderem a kliknięciem
        click(11);

        assertEquals(10_000L, ledger.authoritativeIslandBalance(islandId).join());
        assertEquals(-1, storedLevel());
        assertTrue(broadcasts.isEmpty());
        assertTrue(messages().stream().anyMatch(text -> text.contains("tylko właściciel wyspy")),
                "odmowa musi być widoczna dla gracza: " + messages());
    }

    /**
     * Tytuł Władcy Lotosu kosztuje 3 Diamentowe Lotosy z ekwipunku klikającego
     * i jest jednorazowy na wyspę: pierwsze kliknięcie zabiera 3 sztuki i nadaje
     * tytuł, drugie (nawet z tego samego, wcześniej zbudowanego kafelka) już
     * niczego nie zabiera.
     */
    @Test
    void lotusSinkGrantsTheTitleOnceAndOnlyForThreeLotuses() {
        giveLotuses(3);
        openPrestigeMenu();

        MenuService.ClickHandler sink = menus.last.handlers.get(22);
        assertNotNull(sink, "właściciel z Lotosami musi widzieć klikalny kafelek zlewu");
        session(() -> sink.onClick(player, ClickType.LEFT));

        assertEquals("<gold>Władca Lotosu</gold>", titles.titleOf(islandId).join().orElse(null));
        assertEquals("lotus:master", titles.cachedSourceId(islandId).orElse(null));
        assertEquals(0, lotuses(), "zlew musi zdjąć dokładnie 3 Diamentowe Lotosy");
        assertTrue(messages().stream().anyMatch(text -> text.contains("Władca Lotosu")),
                "gracz musi dostać potwierdzenie nadania tytułu: " + messages());

        // Powtórka z tego samego kafelka: tytuł już jest, więc ekwipunek zostaje w spokoju.
        giveLotuses(3);
        session(() -> sink.onClick(player, ClickType.LEFT));

        assertEquals(3, lotuses(), "drugie kliknięcie nie może zabrać Lotosów drugi raz");
        assertEquals("lotus:master", titles.cachedSourceId(islandId).orElse(null));
    }

    @Test
    void lotusSinkRefusesWhenThePlayerHasTooFewLotuses() {
        giveLotuses(2);
        openPrestigeMenu();

        click(22);

        assertEquals(2, lotuses(), "brak Lotosów = brak zlewu");
        assertTrue(titles.titleOf(islandId).join().isEmpty(), "za mało Lotosów nie może nadać tytułu");
        assertTrue(messages().stream().anyMatch(text -> text.contains("brakuje 1")),
                "komunikat musi powiedzieć, ile sztuk brakuje: " + messages());
    }

    @Test
    void lotusSinkIsOwnerOnly() {
        giveLotuses(3);
        viewRole = IslandRole.MEMBER;
        authoritativeRole = IslandRole.MEMBER;
        openPrestigeMenu();

        click(22);

        assertEquals(3, lotuses(), "członek nie może zapłacić Lotosami za tytuł wyspy");
        assertTrue(titles.titleOf(islandId).join().isEmpty());
        assertTrue(messages().stream().anyMatch(text -> text.contains("tylko właściciel wyspy")),
                "odmowa musi być widoczna dla gracza: " + messages());
    }

    /** Wyspa, która już nosi tytuł, dostaje kafelek stanu zamiast przycisku. */
    @Test
    void ownedLotusTitleShowsStateInsteadOfBuyButton() {
        titles.grant(islandId, "<gold>Władca Lotosu</gold>", "lotus:master").join();

        giveLotuses(3);
        openPrestigeMenu();

        assertNull(menus.last.handlers.get(22),
                "posiadany tytuł nie może być drugi raz kupiony");
        assertEquals(Material.LILY_PAD, menus.last.icons.get(22).getType());
        assertEquals(3, lotuses());
    }

    @Test
    void centerTileShowsPrestigeLevelAndOpensMenu() {
        seed(10_000L);
        service.purchase(islandId).join();

        CapturingMenuService.Capturing tileMenu =
                (CapturingMenuService.Capturing) menus.ofRows(6, Component.text("Centrum Wyspy"));
        session(() -> menu.renderTile(player, tileMenu, 34, islandId));

        assertNotNull(tileMenu.handlers.get(34),
                "kafelek prestiżu w Centrum Wyspy musi być klikalny, gdy prestiż jest włączony");
        assertEquals(Material.AMETHYST_CLUSTER, tileMenu.icons.get(34).getType());

        session(() -> tileMenu.handlers.get(34).onClick(player, ClickType.LEFT));

        assertNotNull(menus.last.handlers.get(24),
                "klik w kafelek otwiera menu prestiżu z przyciskiem zakupu");
    }

    private void seed(long amount) {
        ledger.deposit(AccountKey.island(islandId), amount, "tx:seed:" + amount, "test").join();
    }

    /**
     * Wkłada do ekwipunku {@code amount} Diamentowych Lotosów. Przedmiot jest
     * mockiem rozpoznawanym przez {@code CustomItemService.idOf}, bo meta
     * prawdziwego ItemStacka wymaga rejestrów Papera.
     */
    private void giveLotuses(int amount) {
        ItemStack lotus = lotusStack(amount);
        storage[0] = lotus;
        lenient().when(customItems.idOf(lotus))
                .thenReturn(org.rafalohaki.wpmecore.addons.skyblock.season
                        .SkyBlockTopRewardCoordinator.DIAMOND_LOTUS_ID);
    }

    /** Ile Diamentowych Lotosów zostało w ekwipunku (po ostatnim zapisie). */
    private int lotuses() {
        int total = 0;
        for (ItemStack stack : storage) {
            if (stack != null) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private ItemStack lotusStack(int amount) {
        ItemStack stack = mock(ItemStack.class);
        lenient().when(stack.getAmount()).thenReturn(amount);
        lenient().when(stack.getType()).thenReturn(Material.PAPER);
        lenient().when(stack.isEmpty()).thenReturn(false);
        ItemMeta meta = mock(ItemMeta.class);
        lenient().when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        lenient().when(stack.getItemMeta()).thenReturn(meta);
        return stack;
    }

    private void openPrestigeMenu() {
        session(() -> menu.open(player));
    }

    private void click(int slot) {
        MenuService.ClickHandler handler = menus.last.handlers.get(slot);
        assertNotNull(handler, "slot " + slot + " ma być klikalny");
        session(() -> handler.onClick(player, ClickType.LEFT));
    }

    /**
     * Akcja menu i wszystko, co z niej wynikło: menu renderuje prawdziwe
     * ItemStacki (poza serwerem wymagają rejestrów Paper — mockConstruction),
     * a zadania zaplanowane na wątku encji uruchamiamy ręcznie, więc widać
     * dokładnie, co dzieje się przed powrotem na wątek gracza i po nim.
     */
    private void session(@NotNull Runnable action) {
        try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class,
                (mock, context) -> {
                    Material material = context.arguments().isEmpty()
                            ? Material.STONE
                            : (Material) context.arguments().get(0);
                    when(mock.getType()).thenReturn(material);
                    when(mock.clone()).thenReturn(mock);
                })) {
            action.run();
            while (!entityTasks.isEmpty()) {
                List<Runnable> batch = List.copyOf(entityTasks);
                entityTasks.clear();
                batch.forEach(Runnable::run);
            }
        }
    }

    /** Wszystkie komunikaty gracza jako zwykły tekst — asercje nie zależą od kolorów. */
    private List<String> messages() {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(captor.capture());
        List<String> texts = new ArrayList<>();
        for (Component component : captor.getAllValues()) {
            texts.add(PlainTextComponentSerializer.plainText().serialize(component));
        }
        return texts;
    }

    private int storedLevel() {
        return sql.queryOne("SELECT level FROM wpme_sb_island_prestige WHERE island_id = ?",
                rs -> rs.getInt(1), islandId.toString()).join().orElse(-1);
    }

    /** Menu-podszywacz: ostatnio zbudowane okno z klikalnymi slotami i ikonami. */
    private static final class CapturingMenuService implements MenuService {

        private Capturing last;

        @Override
        public @NotNull Menu rowsFor(int itemCount, @NotNull Component title) {
            return ofRows(Math.clamp((itemCount + 8) / 9, 1, 6), title);
        }

        @Override
        public @NotNull Menu ofRows(int rows, @NotNull Component title) {
            last = new Capturing(rows * 9);
            return last;
        }

        private static final class Capturing implements Menu {

            private final int size;
            private final Map<Integer, ClickHandler> handlers = new HashMap<>();
            private final Map<Integer, ItemStack> icons = new HashMap<>();

            private Capturing(int size) {
                this.size = size;
            }

            @Override
            public int size() {
                return size;
            }

            @Override
            public boolean add(@NotNull ItemStack icon, @NotNull ClickHandler handler) {
                return false;
            }

            @Override
            public @NotNull Menu set(int slot, @NotNull ItemStack icon, @NotNull ClickHandler handler) {
                icons.put(slot, icon);
                handlers.put(slot, handler);
                return this;
            }

            @Override
            public @NotNull Menu decoration(int slot, @NotNull ItemStack icon) {
                icons.put(slot, icon);
                handlers.remove(slot);
                return this;
            }

            @Override
            public @NotNull Menu close(int slot, @NotNull ItemStack icon) {
                return set(slot, icon, (viewer, click) -> viewer.closeInventory());
            }

            @Override
            public void open(@NotNull Player viewer) {
            }
        }
    }
}
