package org.rafalohaki.wpmecore.addons.skyblock.island;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.ProfileStateService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class IslandOwnerOperationsMenu {

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage mm;
    private final SkylliaIntegration skyllia;
    private final ProfileStateService profiles;
    private final IslandCenterMenu center;

    // Track executing operations: operationId -> state
    private final Map<String, String> executing = new ConcurrentHashMap<>();

    public IslandOwnerOperationsMenu(@NotNull JavaPlugin plugin, @NotNull MenuService menus, @NotNull MiniMessage mm,
                                     @NotNull SkylliaIntegration skyllia, @NotNull ProfileStateService profiles,
                                     @NotNull IslandCenterMenu center) {
        this.plugin = plugin;
        this.menus = menus;
        this.mm = mm;
        this.skyllia = skyllia;
        this.profiles = profiles;
        this.center = center;
    }

    public void open(@NotNull Player player, @NotNull UUID islandId) {
        MenuService.Menu menu = menus.ofRows(4, Ui.component(mm, "<dark_red>Operacje właściciela</dark_red>"));
        Ui.frame(menu, mm, Material.RED_STAINED_GLASS_PANE);
        menu.set(11, Ui.item(Material.TNT, mm, "<red><bold>Usuń wyspę</bold></red>",
                List.of("<gray>Wyspa i wszystko, co na niej stoi, znika.</gray>",
                        "<red>Nie da się tego cofnąć. Zapytam o potwierdzenie.</red>"), true),
                (v,c) -> openConsequence(v, islandId, OperationType.DELETE));
        menu.set(13, Ui.item(Material.BARRIER, mm, "<gold><bold>Zacznij wyspę od nowa</bold></gold>",
                List.of("<gray>Kasuje obecną wyspę i daje świeżą, w tym samym trybie.</gray>",
                        "<red>Nie da się tego cofnąć. Zapytam o potwierdzenie.</red>"), true),
                (v,c) -> openConsequence(v, islandId, OperationType.RESET));
        menu.set(15, Ui.item(Material.PLAYER_HEAD, mm, "<yellow><bold>Oddaj wyspę komuś innemu</bold></yellow>",
                List.of("<gray>Nowy właściciel przejmuje wyspę, Ty zostajesz członkiem.</gray>",
                        "<red>Nie da się tego cofnąć. Zapytam o potwierdzenie.</red>"), true),
                (v,c) -> openConsequence(v, islandId, OperationType.TRANSFER));
        menu.set(31, Ui.backButton(mm), (v,c) -> center.open(v));
        menu.open(player);
    }

    enum OperationType { DELETE, RESET, TRANSFER }

    private void openConsequence(Player player, UUID islandId, OperationType type) {
        String operationId = type.name().toLowerCase() + ":" + islandId + ":" + UUID.randomUUID().toString().substring(0,8);
        MenuService.Menu menu = menus.ofRows(3, Ui.component(mm, "<dark_red>Na pewno? " + typeName(type) + "</dark_red>"));
        Ui.frame(menu, mm, Material.RED_STAINED_GLASS_PANE);
        List<String> cons = switch (type) {
            case DELETE -> List.of("<red>• Wyspa znika razem ze wszystkim, co na niej zbudowałeś.</red>",
                    "<red>• Reszta zespołu traci do niej dostęp.</red>",
                    "<red>• Najpierw wypłać wszystko z banku wyspy — inaczej nie pozwolę.</red>",
                    "<gray>Potem możesz założyć nową wyspę komendą <white>/is</white>.</gray>",
                    "<dark_gray>Numer operacji: " + operationId + "</dark_gray>");
            case RESET -> List.of("<red>• Obecna wyspa znika, dostaniesz pustą, startową.</red>",
                    "<red>• Wszystko, co na niej zbudowałeś i zostawiłeś, przepada.</red>",
                    "<gray>Tryb gry zostaje ten sam.</gray>",
                    "<dark_gray>Numer operacji: " + operationId + "</dark_gray>");
            case TRANSFER -> List.of("<red>• Wyspa przechodzi na innego gracza.</red>",
                    "<red>• Ty przestajesz być właścicielem.</red>",
                    "<gray>Po potwierdzeniu wskażesz gracza komendą <white>/is transfer nick</white>.</gray>",
                    "<dark_gray>Numer operacji: " + operationId + "</dark_gray>");
        };
        menu.decoration(13, Ui.item(Material.PAPER, mm, "<yellow>Co się stanie</yellow>", cons, false));
        menu.set(11, Ui.item(Material.LIME_CONCRETE, mm, "<green><bold>TAK, ZRÓB TO</bold></green>",
                List.of("<gray>Kliknij, aby wykonać: " + typeName(type) + "</gray>",
                        "<red>Cofnąć się nie da.</red>"), true),
                (v,c) -> execute(v, islandId, type, operationId));
        menu.set(15, Ui.item(Material.RED_CONCRETE, mm, "<red><bold>ANULUJ</bold></red>",
                List.of("<gray>Nic się nie stanie — wracasz do poprzedniego okna.</gray>"), true),
                (v,c) -> open(v, islandId));
        menu.open(player);
    }

    /** F19: nazwa operacji po polsku — gracz nie musi wiedzieć, co znaczy RESET. */
    private static @NotNull String typeName(@NotNull OperationType type) {
        return switch (type) {
            case DELETE -> "usunięcie wyspy";
            case RESET -> "wyspa od nowa";
            case TRANSFER -> "oddanie wyspy";
        };
    }

    private void execute(Player player, UUID islandId, OperationType type, String operationId) {
        executing.put(operationId, "WYKONYWANIE");
        player.sendMessage(Ui.component(mm, "<yellow>Robię to: " + typeName(type) + "…</yellow>"));
        player.closeInventory();
        UUID playerId = player.getUniqueId();
        // Rola czytana asynchronicznie (DB), odpowiedź wraca na wątek encji gracza.
        CompletableFuture.supplyAsync(() -> skyllia.authoritativeRole(playerId, islandId))
                .whenComplete((role, failure) -> runOnPlayerThread(player, () -> {
                    if (failure != null || role == null) {
                        executing.put(operationId, "ODRZUCONE");
                        plugin.getLogger().log(Level.WARNING,
                                "Nie udało się potwierdzić roli właściciela wyspy " + islandId, failure);
                        player.sendMessage(Ui.component(mm,
                                "<red>Nie udało się potwierdzić uprawnień. Spróbuj ponownie za chwilę.</red>"));
                        return;
                    }
                    if (role != IslandRole.OWNER) {
                        executing.put(operationId, "ODRZUCONE");
                        player.sendMessage(Ui.component(mm, "<red>To może zrobić tylko właściciel wyspy.</red>"));
                        return;
                    }
                    executing.put(operationId, "WYKONANO");
                    switch (type) {
                        case DELETE -> {
                            // Fallback do komendy Skyllii; profil wyspy domyka strażnik cyklu życia.
                            boolean dispatched = player.performCommand("is delete confirm");
                            if (!dispatched) {
                                // Skyllia nie przyjęła potwierdzonego wariantu — sam /is delete
                                // wypisuje u niej tylko ostrzeżenie, więc potwierdzenie leci
                                // ponownie po 20 tickach, wciąż na wątku encji gracza.
                                player.performCommand("is delete");
                                player.getScheduler().runDelayed(plugin, t2 -> {
                                    if (player.isOnline()) {
                                        player.performCommand("is delete confirm");
                                    }
                                }, null, 20);
                            }
                            player.sendMessage(Ui.component(mm, "<green>Wyspa usunięta. Nową założysz komendą <yellow>/is</yellow>.</green>"));
                        }
                        case RESET -> {
                            player.performCommand("is reset confirm");
                            player.sendMessage(Ui.component(mm, "<green>Gotowe — masz świeżą wyspę.</green>"));
                        }
                        case TRANSFER -> {
                            player.sendMessage(Ui.component(mm, "<gray>Wskaż gracza, któremu oddajesz wyspę: napisz na czacie <white>/is transfer nick</white>.</gray>"));
                        }
                    }
                }));
    }

    /**
     * FOLIA-THREADING: {@code sendMessage}, {@code closeInventory} i
     * {@code performCommand} wolno wołać wyłącznie na wątku encji gracza —
     * globalny scheduler tego nie gwarantuje i kończy się fatalem
     * Dispatchując komendę asynchronicznie. {@code run()} zwraca null dla
     * wycofanej encji: wtedy nie ma już wątku, na którym wolno działać,
     * więc operację pomijamy i tylko logujemy.
     */
    private void runOnPlayerThread(@NotNull Player player, @NotNull Runnable action) {
        try {
            var scheduled = player.getScheduler().run(plugin, task -> action.run(),
                    () -> logAbandoned(player));
            if (scheduled == null) {
                logAbandoned(player);
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(Level.WARNING,
                    "Nie udało się zaplanować operacji właściciela dla " + player.getUniqueId(), rejected);
        }
    }

    private void logAbandoned(@NotNull Player player) {
        plugin.getLogger().info("Operacja właściciela dla " + player.getUniqueId()
                + " pominięta — gracz wyszedł z serwera");
    }
}
