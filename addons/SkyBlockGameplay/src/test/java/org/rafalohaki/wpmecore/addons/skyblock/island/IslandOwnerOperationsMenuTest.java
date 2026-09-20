package org.rafalohaki.wpmecore.addons.skyblock.island;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.ProfileStateService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FOLIA-THREADING (A1): operacje właściciela (usuń / od nowa) odpalały
 * {@code performCommand} z globalnego schedulera, co na Foli kończy się
 * fatalem przy dispatchu komendy dla bytu. Test pilnuje, że komenda leci
 * WYŁĄCZNIE z taska planowanego na wątku encji gracza — scheduler encji jest
 * tu atrapą, która task zapamiętuje zamiast go wykonać, więc widać dokładnie,
 * co dzieje się przed hopem, a co po nim.
 *
 * <p>Menu renderuje prawdziwe ItemStacki przez {@link Ui}, które poza serwerem
 * wymagają rejestrów Paper — konstrukcje są przechwycone przez inline mock
 * maker (wzorzec {@code IslandCreationMenusStaleReadyTest}, bez MockBukkit).
 */
class IslandOwnerOperationsMenuTest {

    private CapturingMenuService menus;
    private Player player;
    private EntityScheduler entityScheduler;
    /** Taski zaplanowane na wątku encji — uruchamiane ręcznie, po asercjach. */
    private List<Runnable> entityTasks;
    private List<Runnable> delayedTasks;
    private CountDownLatch hoppedToEntityThread;
    private IslandOwnerOperationsMenu menu;

    @BeforeEach
    void setUp() {
        Ui.init(null); // fail-open: żadnej paczki zasobów w testach
        JavaPlugin plugin = mock(JavaPlugin.class);
        lenient().when(plugin.getLogger()).thenReturn(Logger.getLogger("OwnerOpsTest"));
        menus = new CapturingMenuService();

        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);
        lenient().when(skyllia.authoritativeRole(any(), any())).thenReturn(IslandRole.OWNER);

        player = mock(Player.class);
        lenient().when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        lenient().when(player.isOnline()).thenReturn(true);

        entityTasks = new ArrayList<>();
        delayedTasks = new ArrayList<>();
        hoppedToEntityThread = new CountDownLatch(1);
        entityScheduler = mock(EntityScheduler.class);
        lenient().when(player.getScheduler()).thenReturn(entityScheduler);
        lenient().when(entityScheduler.run(any(), any(), any())).thenAnswer(invocation -> {
            Consumer<ScheduledTask> task = invocation.getArgument(1);
            entityTasks.add(() -> task.accept(null));
            hoppedToEntityThread.countDown();
            return mock(ScheduledTask.class);
        });
        lenient().when(entityScheduler.runDelayed(any(), any(), any(), anyLong())).thenAnswer(invocation -> {
            Consumer<ScheduledTask> task = invocation.getArgument(1);
            delayedTasks.add(() -> task.accept(null));
            return mock(ScheduledTask.class);
        });

        menu = new IslandOwnerOperationsMenu(plugin, menus, MiniMessage.miniMessage(), skyllia,
                mock(ProfileStateService.class), mock(IslandCenterMenu.class));
    }

    /** Menu renderuje prawdziwe ItemStacki — poza serwerem wymagają rejestrów Paper. */
    private void openingMenu(@NotNull Runnable action) {
        try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class,
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

    private void click(int slot) {
        MenuService.ClickHandler handler = menus.last.handlers.get(slot);
        assertNotNull(handler, "slot " + slot + " ma być klikalny");
        handler.onClick(player, ClickType.LEFT);
    }

    @Test
    void deleteCommandWaitsForThePlayerEntityThread() throws Exception {
        UUID islandId = UUID.randomUUID();
        openingMenu(() -> {
            menu.open(player, islandId); // ekran operacji
            click(11);                   // Usuń wyspę → ekran konsekwencji
            click(11);                   // TAK, ZRÓB TO
        });

        assertTrue(hoppedToEntityThread.await(5, TimeUnit.SECONDS),
                "potwierdzona operacja ma wrócić na wątek encji gracza");
        verify(player, never()).performCommand(anyString());

        List.copyOf(entityTasks).forEach(Runnable::run);

        verify(player).performCommand("is delete confirm");
        // Awaryjny drugi krok też jedzie z taska encji, nie z globalnego schedulera.
        delayedTasks.forEach(Runnable::run);
        verify(player).performCommand("is delete");
        verify(entityScheduler).runDelayed(any(), any(), any(), eq(20L));
    }

    @Test
    void confirmationScreenKeepsConfirmAndCancelSemantics() {
        UUID islandId = UUID.randomUUID();
        openingMenu(() -> menu.open(player, islandId));

        openingMenu(() -> click(11)); // Usuń wyspę
        assertNotNull(menus.last.handlers.get(11), "potwierdzenie ma być klikalne");
        assertNotNull(menus.last.handlers.get(15), "anulowanie ma być klikalne");
        assertNull(menus.last.handlers.get(13), "informacja o skutkach to dekoracja");
        verify(player, never()).performCommand(anyString());

        openingMenu(() -> click(15)); // ANULUJ
        assertNotNull(menus.last.handlers.get(31),
                "anulowanie wraca do ekranu operacji, nie odpala niczego");
        verify(player, never()).performCommand(anyString());
    }

    /** Menu-podszywacz: ostatnio zbudowane okno z klikalnymi slotami. */
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
                handlers.put(slot, handler);
                return this;
            }

            @Override
            public @NotNull Menu decoration(int slot, @NotNull ItemStack icon) {
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
