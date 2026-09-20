package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.ProfileStateService;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

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
 * Regresja janitora operacji tworzenia wyspy (defekt „immortal READY”):
 * janitor w {@link IslandCreationCoordinator#updateState} usuwał wpis tylko
 * dla FAILED bez wyspy — operacja READY, której wyspa została potem
 * usunięta, była nieśmiertelna i blokowała GUI dead-endem „Tworzenie już
 * w toku! Stan: READY” do restartu. Teraz: janitor zbiera każdą operację
 * terminalną (READY/FAILED) bez wyspy, a {@code clearFailed} sprząta od razu
 * na ścieżce purge/usunięcia. CREATING/INITIALIZING pozostają chronione.
 *
 * <p>Czysty Mockito (bez MockBukkit — patrz {@code IslandCreationCoordinatorPurgeFailureTest});
 * okno janitora wstrzyknięte pakietowym konstruktorem, żeby test nie spał 60 s.
 */
class IslandCreationJanitorTest {

    private SkylliaIntegration skyllia;
    private ProfileStateService profiles;
    private IslandCreationCoordinator coordinator;

    /** false = wyspa istnieje; true = gracz ją usunął (islandOf → empty). */
    private final AtomicBoolean islandGone = new AtomicBoolean(false);
    /** Licznik wywołań islandOf: pierwsze dwa (gate create + gate doCreate) muszą być puste. */
    private final AtomicInteger islandOfCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("JanitorTest"));
        // paste wyłączony — test nie dotyka świata ani Bukkit.getPluginManager().
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("island.paste.enabled", false);
        when(plugin.getConfig()).thenReturn(cfg);

        skyllia = mock(SkylliaIntegration.class);
        profiles = mock(ProfileStateService.class);
        when(skyllia.purgeSoftDeletedIsland(any(UUID.class))).thenReturn(0);
        when(skyllia.islandOf(any(UUID.class))).thenAnswer(inv -> {
            if (islandOfCalls.incrementAndGet() <= 2) {
                return Optional.empty(); // gate przed create i gate doCreate
            }
            UUID islandId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            return islandGone.get()
                    ? Optional.empty()
                    : Optional.of(new IslandView(new IslandSnapshot(
                            islandId, null, null, null, null, 0L), IslandRole.OWNER));
        });
        when(profiles.tryCreateIsland(any(UUID.class), any(UUID.class), anyString()))
                .thenReturn(CompletableFuture.completedFuture(true));

        // Okno janitora 1 s zamiast 60 s — asercje czekają pollingiem do 5 s.
        coordinator = new IslandCreationCoordinator(plugin, skyllia, profiles,
                new IslandModeFlags(true, false, false), 1L);
    }

    /** Pełny udany przepływ create: op kończy w stanie READY przy istniejącej wyspie. */
    private void successfulCreate(UUID playerId) throws Exception {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Tester");
        when(skyllia.createIsland(any(UUID.class), eq("starter"), anyString())).thenReturn(true);

        var result = coordinator.create(player, "classic").get(10, TimeUnit.SECONDS);

        assertEquals(IslandCreationCoordinator.CreateResult.Kind.SUCCESS, result.kind());
        assertNotNull(coordinator.operationOf(playerId), "udany create rejestruje operację");
        assertEquals(IslandCreationState.READY, coordinator.operationOf(playerId).state());
    }

    @Test
    @DisplayName("READY bez wyspy jest sprzątany przez janitora, a re-create przechodzi")
    void readyWithoutIslandIsReapedAndRecreateIsPossible() throws Exception {
        UUID playerId = UUID.randomUUID();
        successfulCreate(playerId);

        // Gracz usuwa wyspę po udanym utworzeniu (przed oknem janitora).
        islandGone.set(true);

        awaitTrue(java.time.Duration.ofSeconds(5),
                () -> coordinator.operationOf(playerId) == null,
                "janitor ma usunąć terminalną operację READY bez wyspy");
        assertEquals(IslandCreationState.IDLE, coordinator.stateOf(playerId));

        // Re-create po sprzątnięciu: nie może być ALREADY_CREATING.
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Tester");
        var second = coordinator.create(player, "classic").get(10, TimeUnit.SECONDS);
        assertNotEquals(IslandCreationCoordinator.CreateResult.Kind.ALREADY_CREATING, second.kind(),
                "martwy READY nie może blokować ponownego utworzenia");
        assertEquals(IslandCreationCoordinator.CreateResult.Kind.SUCCESS, second.kind());
    }

    @Test
    @DisplayName("FAILED bez wyspy nadal jest sprzątany (stare zachowanie zachowane)")
    void failedWithoutIslandIsStillReaped() throws Exception {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Tester");
        when(skyllia.createIsland(any(UUID.class), eq("starter"), anyString())).thenReturn(false);
        islandGone.set(true); // create padł, wyspa nie powstała

        var result = coordinator.create(player, "classic").get(10, TimeUnit.SECONDS);
        assertEquals(IslandCreationCoordinator.CreateResult.Kind.FAILURE, result.kind());

        awaitTrue(java.time.Duration.ofSeconds(5),
                () -> coordinator.operationOf(playerId) == null,
                "janitor ma usunąć FAILED bez wyspy (regresja starego zachowania)");
    }

    @Test
    @DisplayName("READY z istniejącą wyspą przeżywa okno janitora (nie kasujemy żywej wyspy)")
    void readyWithIslandSurvivesReapWindow() throws Exception {
        UUID playerId = UUID.randomUUID();
        successfulCreate(playerId);
        // Wyspa NIE zostaje usunięta.

        Thread.sleep(1500); // okno janitora 1 s + margines

        assertNotNull(coordinator.operationOf(playerId),
                "READY z aktywną wyspą nie może zostać zebrany przez janitora");
        assertEquals(IslandCreationState.READY, coordinator.stateOf(playerId));
    }

    @Test
    @DisplayName("clearFailed zdejmuje martwy READY natychmiast (ścieżka purge)")
    void clearFailedDropsStaleReadyImmediately() throws Exception {
        UUID playerId = UUID.randomUUID();
        successfulCreate(playerId);
        islandGone.set(true); // wyspa usunięta

        coordinator.clearFailed(playerId);

        assertNull(coordinator.operationOf(playerId),
                "martwy READY ma być zdjęty natychmiast, bez czekania na janitora");
        assertEquals(IslandCreationState.IDLE, coordinator.stateOf(playerId));
    }

    @Test
    @DisplayName("clearFailed nie dotyka żywej operacji CREATING/INITIALIZING")
    void clearFailedKeepsLiveOperation() throws Exception {
        UUID playerId = UUID.randomUUID();
        CountDownLatch gate = new CountDownLatch(1);
        when(skyllia.createIsland(any(UUID.class), eq("starter"), anyString()))
                .thenAnswer(inv -> {
                    gate.await(5, TimeUnit.SECONDS);
                    return true;
                });
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Tester");

        CompletableFuture<IslandCreationCoordinator.CreateResult> inFlight =
                coordinator.create(player, "classic");
        try {
            awaitTrue(java.time.Duration.ofSeconds(2),
                    () -> coordinator.operationOf(playerId) != null
                            && (coordinator.operationOf(playerId).state() == IslandCreationState.CREATING
                                    || coordinator.operationOf(playerId).state() == IslandCreationState.INITIALIZING),
                    "operacja w toku ma być widoczna w rejestrze");

            coordinator.clearFailed(playerId);

            assertNotNull(coordinator.operationOf(playerId),
                    "żywa operacja CREATING/INITIALIZING nie może być zdjęta przez clearFailed");
        } finally {
            gate.countDown();
            inFlight.get(10, TimeUnit.SECONDS);
        }
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
}
