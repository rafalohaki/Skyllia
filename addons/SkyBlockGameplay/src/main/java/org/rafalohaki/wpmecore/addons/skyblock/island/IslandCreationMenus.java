package org.rafalohaki.wpmecore.addons.skyblock.island;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.ProfileStateService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.ArrayList;
import java.util.List;

public final class IslandCreationMenus {

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage mm;
    private final SkylliaIntegration skyllia;
    private final ProfileStateService profiles;
    private final IslandModeFlags flags;
    private final IslandCreationCoordinator coordinator;

    public IslandCreationMenus(@NotNull JavaPlugin plugin, @NotNull MenuService menus, @NotNull MiniMessage mm,
                               @NotNull SkylliaIntegration skyllia, @NotNull ProfileStateService profiles,
                               @NotNull IslandModeFlags flags, @Nullable IslandCreationCoordinator coordinator) {
        this.plugin = plugin;
        this.menus = menus;
        this.mm = mm;
        this.skyllia = skyllia;
        this.profiles = profiles;
        this.flags = flags;
        this.coordinator = coordinator;
    }

    /** Single entry selector: /is create bare opens this. */
    public void openPicker(@NotNull Player player) {
        // SKY-2: soft-deleted wyspa Skylli widziana przez islandOf blokuje picker
        // i re-create — czyszczenie PRZED checkiem (SkylliaIntegration.purgeSoftDeletedIsland)
        // S1-fix: -1 = SQLException przy czyszczeniu — nie otwieraj pickera na
        // potencjalnie zablokowanym stanie, tylko kaź graczowi spróbować ponownie.
        if (skyllia.purgeSoftDeletedIsland(player.getUniqueId()) < 0) {
            player.sendMessage(Ui.component(mm, "<red>Nie udało się wyczyścić poprzedniej wyspy. Spróbuj ponownie za chwilę.</red>"));
            return;
        }
        // If already has island, teleport the player home instead of refusing:
        // openPicker with an existing island is reachable in practice only through
        // /stworzwyspe — the command wired to the PRZEWODNIK WYSPY npc. A player
        // who owns an island and clicks the guide wants to GO there, not read
        // „Masz już aktywną wyspę” (frustrating dead-end reported on production).
        // Typed /is create and /is panel have their own branches and never land here.
        if (skyllia.islandOf(player.getUniqueId()).isPresent()) {
            player.sendMessage(Ui.component(mm, "<green>Masz już wyspę — przenoszę Cię na nią.</green>"));
            // FOLIA-THREADING: performCommand wyłącznie na wątku encji gracza.
            player.getScheduler().run(plugin, t -> {
                if (player.isOnline()) {
                    player.performCommand("is home");
                }
            }, null);
            return;
        }
        // Reconnect: if creation in progress, show status
        if (coordinator != null) {
            var state = coordinator.stateOf(player.getUniqueId());
            if (state == IslandCreationState.CREATING || state == IslandCreationState.INITIALIZING) {
                player.sendMessage(Ui.component(mm, "<yellow>Twoja wyspa już się tworzy — poczekaj chwilę...</yellow>"));
                // Also show status GUI
                openStatus(player);
                return;
            }
            // Wyspy nie ma, a wpis operacji terminalnej (FAILED / READY po
            // usunięciu wyspy) wisi w rejestrze — sprzątamy od razu, żeby
            // nie przetrwał do okna janitora i nie renderował martwego
            // ekranu „Tworzenie już w toku”.
            coordinator.clearFailed(player.getUniqueId());
        }
        MenuService.Menu menu = menus.ofRows(3, Ui.panelTitle(3, Ui.component(mm, "<gold><bold>Wybierz, jak chcesz zacząć</bold></gold>")));
        Ui.frame(menu, mm, Material.CYAN_STAINED_GLASS_PANE);
        int slot = 11;
        for (IslandMode mode : IslandMode.values()) {
            boolean enabled = flags.isEnabled(mode);
            List<String> lore = new ArrayList<>(List.of(mode.description()));
            if (!enabled) {
                lore.add("<dark_gray> </dark_gray>");
                lore.add("<red>Ten tryb jest jeszcze wyłączony.</red>");
                lore.add("<gray>Wybierz na razie inny — ten wróci później.</gray>");
                menu.decoration(slot, Ui.item(Material.GRAY_DYE, mm, mode.displayName(), lore, false));
            } else {
                // UX 2-okna: treść dawnego okna „szczegóły” rozeszła się do
                // wzbogaconego lore karty (blok „Zawartość”) i potwierdzenia.
                lore.add("<dark_gray> </dark_gray>");
                lore.addAll(List.of(detailLines(mode)));
                lore.add("<dark_gray> </dark_gray>");
                lore.add("<yellow>» Kliknij, aby wybrać ten tryb.</yellow>");
                IslandMode m = mode;
                menu.set(slot, Ui.item(m.icon(), mm, m.displayName(), lore, true),
                        (viewer, click) -> openConfirmation(viewer, m));
            }
            slot += 2; // 11,13,15
        }
        menu.close(22, Ui.closeButton(mm));
        menu.open(player);
    }

    /** Blok „Zawartość” trybu — dawniej osobne okno szczegółów, dziś w lore karty pickera. */
    private static @NotNull String[] detailLines(@NotNull IslandMode mode) {
        return switch (mode) {
            case CLASSIC -> new String[]{
                    "<white>Co dostajesz:</white>",
                    "<gray>• Małą wyspę: trawa, drzewo, poletko</gray>",
                    "<gray>• Skrzynię ze startowym sprzętem</gray>",
                    "<gray>• Wiadro lawy i wiadro wody na generator</gray>",
                    "<dark_gray> </dark_gray>",
                    "<gray>Kamień robisz sam — z lawy i wody.</gray>",
                    // F24: obie karty muszą dawać się porównać jednym zdaniem,
                    // inaczej wybór trybu jest rzutem monetą. OneBlock miał takie
                    // zdanie od F19, klasyk nie miał żadnego.
                    "<yellow>Klasyczny SkyBlock: budujesz wszystko od zera.</yellow>"
            };
            case ONEBLOCK -> new String[]{
                    "<white>Co dostajesz:</white>",
                    "<gray>• Jeden blok, który sam się odnawia</gray>",
                    "<gray>• Z każdym rozbiciem wypada coś innego</gray>",
                    // F24: było „12 rozdziałów”, a oneblock.yml wysyła SIEDEM faz
                    // (poczatek, podziemia, zima, pustynia, dzungla, pieklo, kres).
                    // Liczba w literale i tak rozjedzie się z konfiguracją przy
                    // pierwszej zmianie treści, więc opisujemy łuk, nie licznik.
                    "<gray>• Kolejne rozdziały: od łąki po Kres, z nagrodami</gray>",
                    "<dark_gray> </dark_gray>",
                    "<green>Najprostszy start: rozbijasz blok i grasz.</green>"
            };
            case EXPEDITION -> new String[]{
                    "<white>Co dostajesz:</white>",
                    "<gray>• Placówkę i trzy tereny do odkrycia</gray>",
                    "<gray>• Kolejne tereny odblokowujesz w trakcie gry</gray>"
            };
        };
    }

    private void openConfirmation(@NotNull Player player, @NotNull IslandMode mode) {
        MenuService.Menu menu = menus.ofRows(3, Ui.panelTitle(3, Ui.component(mm, "<gold><bold>Potwierdź utworzenie</bold></gold>")));
        Ui.frame(menu, mm, Material.LIME_STAINED_GLASS_PANE);
        List<String> lore = List.of(
                "<white>Wybrany tryb: " + mode.displayName() + "</white>",
                "<dark_gray> </dark_gray>",
                "<yellow>Kliknij, aby założyć wyspę.</yellow>",
                "<gray>Zajmie to kilka sekund — potem przeniosę Cię na nią.</gray>",
                "<dark_gray>Wyspę da się później skasować i zacząć od nowa.</dark_gray>"
        );
        // Show current state if already in progress — bloker tylko dla ŻYWEJ
        // operacji (CREATING/INITIALIZING). Martwe wpisy terminalne (READY po
        // usunięciu wyspy, FAILED) nie mogą renderować dead-endu „Tworzenie
        // już w toku!” — gracz musi móc potwierdzić nowe utworzenie; wpis
        // i tak sprzątną clearFailed (ścieżka purge) albo janitor.
        if (coordinator != null) {
            var op = coordinator.operationOf(player.getUniqueId());
            if (op != null && (op.state() == IslandCreationState.CREATING
                    || op.state() == IslandCreationState.INITIALIZING)) {
                lore = List.of("<gray>Twoja wyspa właśnie powstaje.</gray>",
                        "<gray>Zamknij to okno i poczekaj kilka sekund.</gray>",
                        "<dark_gray>Numer operacji: " + op.operationId() + "</dark_gray>");
                menu.decoration(13, Ui.item(Material.ORANGE_CONCRETE, mm, "<gold>Zaraz będzie gotowe</gold>", lore, false));
                menu.decoration(22, Ui.closeButton(mm));
                menu.open(player);
                return;
            }
        }
        menu.set(11, Ui.item(Material.LIME_CONCRETE, mm, "<green><bold>POTWIERDZAM — UTWÓRZ</bold></green>",
                lore, true), (viewer, click) -> {
            viewer.closeInventory();
            if (coordinator != null) {
                viewer.sendMessage(Ui.component(mm, "<yellow>⏳ Tworzenie wyspy <white>" + mode.displayName() + "</white> — to chwilę potrwa...</yellow>"));
                coordinator.create(viewer, mode.id()).whenComplete((result, ex) -> {
                    plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                        if (ex != null) {
                            viewer.sendMessage(Ui.component(mm, "<red>Nie udało się utworzyć wyspy — spróbuj ponownie za chwilę.</red>"));
                            plugin.getLogger().log(java.util.logging.Level.WARNING,
                                    "Island create failed player=" + viewer.getUniqueId() + " mode=" + mode.id(), ex);
                            return;
                        }
                        if (result == null) return;
                        switch (result.kind()) {
                            case SUCCESS -> {
                                viewer.sendMessage(Ui.component(mm, "<green>✅ Wyspa gotowa! Przenoszę Cię na nią.</green>"));
                                sendFirstSteps(viewer, mode);
                                plugin.getLogger().info("Island create OK player=" + viewer.getUniqueId()
                                        + " mode=" + result.mode().id() + " op=" + result.operationId());
                                org.bukkit.Location anchor = result.anchor();
                                if (anchor != null && anchor.getWorld() != null) {
                                    // Teleport wprost na zweryfikowaną powierzchnię (top bloku:
                                    // anchor Y + 1.0). Skyllia nie ma sprawdzeń bezpieczeństwa,
                                    // a jej fallback „centr Y=64 +0.5” stawiał stopy W blok.
                                    // FOLIA-THREADING (fix 2026-08-31): callback paste'u przychodzi
                                    // na GLOBALNYM schedulerze, nie na entity gracza — sync
                                    // teleport() z globalnego wątku rzuca
                                    // UnsupportedOperationException ("Must use teleportAsync
                                    // while in region threading"). Cross-region teleport na
                                    // Foli WYŁĄCZNIE teleportAsync; reset fall/velocity dopiero
                                    // po ukończeniu teleportu (stan bytu na regionie docelowym).
                                    viewer.teleportAsync(new org.bukkit.Location(anchor.getWorld(),
                                            anchor.getX() + 0.5d, anchor.getY() + 1.0d, anchor.getZ() + 0.5d)).thenAccept(done -> {
                                        if (!Boolean.TRUE.equals(done) || !viewer.isOnline()) {
                                            return;
                                        }
                                        viewer.setFallDistance(0.0f);
                                        viewer.setVelocity(new org.bukkit.util.Vector(0d, 0d, 0d));
                                    });
                                } else {
                                    // Fallback: Skyllia command po krótkiej pauzie.
                                    // FOLIA-THREADING: performCommand wolno wywołać tylko
                                    // na wątku encji gracza — hop z globalnego schedulera
                                    // przez EntityScheduler dawał fatal „Dispatching
                                    // command async” dla bytu.
                                    viewer.getScheduler().runDelayed(plugin, t2 -> {
                                        if (viewer.isOnline()) viewer.performCommand("is home");
                                    }, null, 20);
                                }
                            }
                            case MODE_DISABLED -> viewer.sendMessage(Ui.component(mm, "<red>" + result.message() + "</red>"));
                            case ALREADY_HAS_ISLAND -> viewer.sendMessage(Ui.component(mm, "<red>" + result.message() + "</red>"));
                            case UNKNOWN_TYPE -> viewer.sendMessage(Ui.component(mm, "<red>" + result.message() + "</red>"));
                            case ALREADY_CREATING -> {
                                viewer.sendMessage(Ui.component(mm, "<yellow>" + result.message() + "</yellow>"));
                                plugin.getLogger().info("Island create dedupe player=" + viewer.getUniqueId()
                                        + " op=" + result.operationId());
                            }
                            case FAILURE -> viewer.sendMessage(Ui.component(mm, "<red>" + result.message() + "</red>"));
                        }
                    });
                });
            } else {
                // Fallback direct dispatch (should not happen in production, but for tests)
                viewer.performCommand("is create " + mode.skylliaTemplate());
            }
        });
        menu.set(15, Ui.item(Material.RED_CONCRETE, mm, "<red><bold>ANULUJ</bold></red>",
                List.of("<gray>Wróć do wyboru — nic się nie stanie.</gray>"), true),
                (v,c) -> {
                    // cancel-must-clear: martwy wpis terminalny (FAILED / READY
                    // po usunięciu wyspy) nie może przetrwać anulowania, żeby
                    // następne /is create nie trafiło na „Tworzenie już w toku”.
                    // clearFailed sam chroni żywe CREATING/INITIALIZING.
                    if (coordinator != null) {
                        var op = coordinator.operationOf(v.getUniqueId());
                        if (op != null && skyllia.islandOf(v.getUniqueId()).isEmpty()) {
                            coordinator.clearFailed(v.getUniqueId());
                        }
                    }
                    openPicker(v);
                });

        menu.open(player);
    }

    /**
     * F19: trzy linijki „co teraz”, wysyłane raz — zaraz po utworzeniu wyspy.
     * To jedyny moment, w którym gracz na pewno patrzy na czat, a tablica boczna
     * ma miejsce na jedną linijkę i instrukcji nie zmieści. Treść jest inna dla
     * każdego trybu, bo pierwsza czynność jest zupełnie inna: klasyk wymaga
     * zbudowania generatora z lawy i wody, OneBlock — rozbicia bloku pod stopami.
     */
    public void sendFirstSteps(@NotNull Player player, @NotNull IslandMode mode) {
        player.sendMessage(Ui.component(mm, "<dark_gray>———————————————</dark_gray>"));
        switch (mode) {
            case ONEBLOCK -> {
                player.sendMessage(Ui.component(mm,
                        "<gold><bold>Co teraz:</bold></gold> <white>rozbij blok, na którym stoisz.</white>"));
                player.sendMessage(Ui.component(mm,
                        "<gray>Przytrzymaj lewy przycisk myszy na bloku. Blok odrodzi się sam, a wypadnie z niego coś nowego — nie spadniesz.</gray>"));
                player.sendMessage(Ui.component(mm,
                        "<gray>Z pierwszych bloków zrób stół rzemieślniczy i kilof, potem kop dalej.</gray>"));
            }
            case CLASSIC -> {
                player.sendMessage(Ui.component(mm,
                        "<gold><bold>Co teraz:</bold></gold> <white>otwórz skrzynię obok siebie.</white>"));
                player.sendMessage(Ui.component(mm,
                        "<gray>Są w niej kilof, jedzenie, sadzonka oraz wiadro lawy i wiadro wody.</gray>"));
                player.sendMessage(Ui.component(mm,
                        "<gray>Kamień robi się sam: wykop rowek na trzy bloki, na jednym końcu wylej lawę, na drugim wodę. W środku pojawi się bruk — rozbijesz go, a powstanie następny.</gray>"));
            }
            case EXPEDITION -> player.sendMessage(Ui.component(mm,
                    "<gold><bold>Co teraz:</bold></gold> <white>rozejrzyj się po placówce i zbierz pierwsze surowce.</white>"));
        }
        // F24: dotąd te linijki mówiły WYŁĄCZNIE, jak wykonać pierwszą czynność.
        // Relacja z sesji testowej („rozwalał bloki, nie wiedząc o co chodzi”) to
        // nie brak instrukcji, tylko brak celu: gracz wiedział CO robić, nie
        // wiedział PO CO. Łańcuch surowiec → monety → rozwój → zadania → sezon
        // istniał w kodzie i w configu, ale nie był nigdzie powiedziany graczowi.
        player.sendMessage(Ui.component(mm,
                "<gold><bold>Po co:</bold></gold> <gray>to, co wykopiesz, sprzedajesz w"
                        + " <yellow>/sklep</yellow>, a za monety kupujesz lepszy sprzęt"
                        + " i maszyny na wyspę.</gray>"));
        player.sendMessage(Ui.component(mm,
                "<gray>Po drodze zbierasz zadania dnia (<yellow>/zadania</yellow>) i punkty"
                        + " sezonu (<yellow>/sezon</yellow>) — z nich są nagrody z karnetu"
                        + " (<yellow>/przepustka</yellow>).</gray>"));
        player.sendMessage(Ui.component(mm,
                "<gray>Gdy się zgubisz, wpisz na czacie <yellow>/is help</yellow>. Wszystkie okna gry są pod <yellow>/menu</yellow>.</gray>"));
        player.sendMessage(Ui.component(mm, "<dark_gray>———————————————</dark_gray>"));
    }

    private void openStatus(@NotNull Player player) {
        if (coordinator == null) return;
        var op = coordinator.operationOf(player.getUniqueId());
        if (op == null) return;
        MenuService.Menu menu = menus.ofRows(3, Ui.panelTitle(3, Ui.component(mm, "<gold><bold>Status tworzenia</bold></gold>")));
        Ui.frame(menu, mm, Material.CLOCK);
        menu.decoration(13, Ui.item(Material.CLOCK, mm, "<yellow>Twoja wyspa powstaje</yellow>",
                List.of("<gray>Tryb: <white>" + op.mode().displayName() + "</white></gray>",
                        "<gray>Zamknij okno i poczekaj kilka sekund —</gray>",
                        "<gray>przeniosę Cię na wyspę, gdy będzie gotowa.</gray>",
                        "<dark_gray>Numer operacji: " + op.operationId() + "</dark_gray>"), false));
        menu.close(22, Ui.closeButton(mm));
        menu.open(player);
    }
}
