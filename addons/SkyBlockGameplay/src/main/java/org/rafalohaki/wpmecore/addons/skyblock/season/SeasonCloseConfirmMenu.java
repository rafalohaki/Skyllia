package org.rafalohaki.wpmecore.addons.skyblock.season;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Ekran potwierdzenia zamknięcia sezonu (ekran B) — JEDYNE miejsce w GUI,
 * z którego schodzi {@code closeSeason(true, false)}, czyli dokładnie ta sama
 * ścieżka co {@code /sezon zamknij --confirm}. Force NIE jest tu dostępny
 * (pozostaje CLI-only).
 *
 * <p>Zabezpieczenia: (1) blokada podwójnego kliknięcia
 * ({@code Set<UUID>} add-przed-strzałem, usunięcie w finally-torze
 * {@code whenComplete}); (2) guard nieaktualności — numer sezonu w chwili
 * renderu vs bieżący; rozjazd oznacza, że ktoś zamknął sezon w międzyczasie.
 */
public final class SeasonCloseConfirmMenu {

    static final int SLOT_BACK = 11;
    static final int SLOT_SUMMARY = 13;
    static final int SLOT_CONFIRM = 15;

    private static final String TITLE = "<gold><bold>Potwierdź zamknięcie sezonu</bold></gold>";

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage miniMessage;
    private final IntSupplier currentSeason;
    private final LongSupplier seasonEndMillis;
    private final Supplier<String> headerLabelSupplier;
    private final Supplier<String> nextLabelSupplier;
    private final BiFunction<Boolean, Boolean,
            java.util.concurrent.CompletableFuture<SeasonEndService.CloseOutcome>> closer;
    private final Consumer<Player> backAction;

    /** Kliknięcia „w locie” — jedno zamknięcie na widza naraz. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    /** Ochrona przed podwójnym otwarciem ekranu (wzorzec {@code SeasonPassMenu}). */
    private final Map<UUID, Boolean> opening = new ConcurrentHashMap<>();

    /** Numer sezonu w chwili ostatniego renderu — do guarda nieaktualności. */
    private volatile int renderedSeasonId;

    public SeasonCloseConfirmMenu(@NotNull JavaPlugin plugin,
                                  @NotNull MenuService menus,
                                  @NotNull MiniMessage miniMessage,
                                  @NotNull IntSupplier currentSeason,
                                  @NotNull LongSupplier seasonEndMillis,
                                  @NotNull Supplier<String> headerLabelSupplier,
                                  @NotNull Supplier<String> nextLabelSupplier,
                                  @NotNull BiFunction<Boolean, Boolean,
                                          java.util.concurrent.CompletableFuture<SeasonEndService.CloseOutcome>> closer,
                                  @NotNull Consumer<Player> backAction) {
        this.plugin = plugin;
        this.menus = menus;
        this.miniMessage = miniMessage;
        this.currentSeason = currentSeason;
        this.seasonEndMillis = seasonEndMillis;
        this.headerLabelSupplier = headerLabelSupplier;
        this.nextLabelSupplier = nextLabelSupplier;
        this.closer = closer;
        this.backAction = backAction;
    }

    /** Otwiera ekran na wątku encji gracza; podwójne otwarcie ignorowane. */
    public void open(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        if (opening.putIfAbsent(uuid, true) != null) {
            return;
        }
        try {
            var scheduled = player.getScheduler().run(plugin, ignored -> {
                opening.remove(uuid);
                buildAndOpen(player);
            }, () -> opening.remove(uuid));
            if (scheduled == null) {
                opening.remove(uuid);
            }
        } catch (RuntimeException rejected) {
            opening.remove(uuid);
            plugin.getLogger().fine("Sezony admin: harmonogram potwierdzenia odrzucony: "
                    + rejected);
            buildAndOpen(player);
        }
    }

    void buildAndOpen(@NotNull Player player) {
        MenuService.Menu menu = menus.ofRows(3, Ui.panelTitle(3, Ui.component(miniMessage, TITLE)));
        populate(menu, player);
        menu.open(player);
    }

    /** Buduje i wypełnia ekran BEZ otwarcia — package-private dla testów. */
    void populate(@NotNull Player player) {
        MenuService.Menu menu = menus.ofRows(3, Ui.component(miniMessage, TITLE));
        populate(menu, player);
    }

    /** Wypełnia sloty ekranu; package-private dla testów layoutu. */
    void populate(@NotNull MenuService.Menu menu, @NotNull Player player) {
        Ui.frame(menu, miniMessage, Material.RED_STAINED_GLASS_PANE);
        renderedSeasonId = currentSeason.getAsInt();

        menu.set(SLOT_BACK, Ui.backButton(miniMessage), (who, click) -> backAction.accept(who));

        long end = seasonEndMillis.getAsLong();
        boolean expired = System.currentTimeMillis() >= end;
        String activeName = headerLabelSupplier.get();
        String shownName = activeName == null || activeName.isBlank()
                ? "sezon S" + renderedSeasonId : activeName;
        menu.decoration(SLOT_SUMMARY, Ui.item(Material.TNT, miniMessage,
                "<red><bold>Zamknąć sezon?</bold></red>",
                List.of(
                        "<gray>Zamykasz: <white>" + shownName + "</white>"
                                + (expired ? " <yellow>(już wygasł)</yellow>" : "") + ".</gray>",
                        "<gray>Wykona się: TOP-10 → historia,</gray>",
                        "<gray>nagrody rang 1–3 (jednorazowo, dedupe),</gray>",
                        "<gray>rollover do kolejnego sezonu,</gray>",
                        "<gray>wyczyszczenie punktów bieżącego rankingu.</gray>",
                        "<red>Cofnąć się nie da.</red>",
                        "<dark_gray>wewn. id: S" + renderedSeasonId + "</dark_gray>"),
                false));

        menu.set(SLOT_CONFIRM, Ui.item(Material.RED_CONCRETE, miniMessage,
                "<red><bold>POTWIERDZAM ZAMKNIĘCIE</bold></red>",
                List.of(
                        "<gray>Kliknij raz. Efekt identyczny z</gray>",
                        "<gray><white>/sezon zamknij --confirm</white>.</gray>",
                        "<dark_gray>Zamknięcie przed czasem nadal wymaga --force w CLI.</dark_gray>"),
                true), this::confirm);

        try {
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f);
        } catch (Throwable ignored) {
            // Dźwięk jest ozdobą i nigdy nie wywraca ekranu.
        }
    }

    /** Numer sezonu z ostatniego renderu — dla asercji testowych. */
    int renderedSeasonId() {
        return renderedSeasonId;
    }

    /**
     * Jedyna ścieżka mutacji GUI: {@code closer.apply(true, false)}.
     * Guard nieaktualności i blokada podwójnego kliknięcia idą PRZED strzałem.
     */
    private void confirm(@NotNull Player viewer,
                         @SuppressWarnings("unused") org.bukkit.event.inventory.ClickType click) {
        UUID uuid = viewer.getUniqueId();
        if (currentSeason.getAsInt() != renderedSeasonId) {
            send(viewer, "<red>Ekran nieaktualny — otwórz ponownie.</red>");
            backAction.accept(viewer);
            return;
        }
        if (!inFlight.add(uuid)) {
            return; // podwójne kliknięcie / zamknięcie już trwa
        }
        try {
            String nextLabel = nextLabelSupplier.get();
            closer.apply(true, false).whenComplete((outcome, error) -> {
                inFlight.remove(uuid); // finally-tor: zawsze zwolnij widza
                runOnEntityThread(viewer, () -> {
                    if (error != null) {
                        send(viewer, "<red>Błąd podczas zamykania sezonu: "
                                + errorMessage(error) + "</red>");
                        return;
                    }
                    SeasonCloseReport.send(viewer, outcome, nextLabel);
                    if (outcome != null && outcome.closed()) {
                        celebrate(viewer);
                        viewer.closeInventory();
                    }
                });
            });
        } catch (RuntimeException fireFailure) {
            inFlight.remove(uuid);
            send(viewer, "<red>Błąd podczas zamykania sezonu: "
                    + errorMessage(fireFailure) + "</red>");
        }
    }

    private void runOnEntityThread(@NotNull Player player, @NotNull Runnable task) {
        try {
            var scheduled = player.getScheduler().run(plugin, ignored -> task.run(), null);
            if (scheduled == null) {
                task.run();
            }
        } catch (RuntimeException rejected) {
            task.run();
        }
    }

    /** Fanfara wyłącznie na ścieżce sukcesu; ozdoba, nigdy wyjątek. */
    private void celebrate(@NotNull Player player) {
        try {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE,
                    1.0f, 1.2f);
        } catch (Throwable ignored) {
            // pomijalne
        }
    }

    private static @NotNull String errorMessage(@NotNull Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private void send(@NotNull Player player, @NotNull String message) {
        player.sendMessage(Ui.component(miniMessage, message));
    }
}
