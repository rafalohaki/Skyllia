package org.rafalohaki.wpmecore.addons.skyblock.hub;

import org.rafalohaki.wpmecore.addons.skyblock.economy.CompactBalanceFormatter;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;

import org.rafalohaki.wpmecore.addons.skyblock.hub.SkyBlockProgressiveObjectiveService;

import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.WpmeAPI;
import org.rafalohaki.wpmecore.api.presentation.PresentationField;
import org.rafalohaki.wpmecore.api.presentation.PresentationService;
import org.rafalohaki.wpmecore.api.service.SchedulerService;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Publishes SkyBlock cache state into the shared snapshot-only data plane.
 *
 * <p>F20: publikujemy wyłącznie pola, których wartość naprawdę znamy.
 * {@code ISLAND_LEVEL}, {@code ISLAND_MEMBERS} i {@code ISLAND_MODE} były tu
 * zaszytymi stałymi {@code "1"}, {@code "1"} i {@code "Standard"} — tablica
 * boczna pokazywała każdemu graczowi przez cały sezon „Poziom: 1". Skyllia
 * 3.0-163 nie zna pojęcia poziomu ani wartości wyspy (potwierdzone
 * {@code javap} na {@code Skyllia-3.0-163-all.jar}: {@code api.skyblock.Island}
 * ma name/description/owner/createDate/id/size/warps/members/maxMembers/flagi/
 * granice i nic więcej), a liczba członków i tryb wyspy są w naszym adapterze
 * oznaczone AUTHORITATIVE — to zapytanie do bazy, którego nie wolno puścić
 * z pętli odświeżanej co 5 s dla każdego gracza. Niepublikowane pole renderuje
 * się jako {@code "—"} z {@link PresentationField}, czyli „nie wiem" zamiast
 * wymyślonej liczby. Wiersz „Poziom" zdjęty równolegle z {@code TAB/config.yml}.
 */
public final class SkyBlockPresentationPublisher {

    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(5);
    private static final Duration VALUE_TTL = Duration.ofSeconds(20);

    private final JavaPlugin plugin;
    private final WpmeAPI api;
    private final SchedulerService scheduler;
    private final LedgerService ledger;
    private final SkylliaIntegration skyllia;
    private final SkyBlockProgressiveObjectiveService objectiveService;
    private volatile @Nullable ScheduledTask task;
    private volatile @NotNull java.util.function.Supplier<String> seasonNameSupplier =
            () -> "";
    /** Poziom wyspy z cache serwisu (setter — serwis powstaje przed wydawcą, ale to wydawca jest ostatni w wiring). */
    private volatile @Nullable org.rafalohaki.wpmecore.addons.skyblock.island.IslandLevelService islandLevels;
    /** Rate-limit szumu przy starszej rodzinie jarów (NoSuchFieldError). */
    private volatile long lastNoiseMinute;
    
    public SkyBlockPresentationPublisher(@NotNull JavaPlugin plugin,
                                  @NotNull WpmeAPI api,
                                  @NotNull SchedulerService scheduler,
                                  @NotNull LedgerService ledger,
                                  @NotNull SkylliaIntegration skyllia,
                                  @NotNull SkyBlockProgressiveObjectiveService objectiveService) {
        this.plugin = plugin;
        this.api = api;
        this.scheduler = scheduler;
        this.ledger = ledger;
        this.skyllia = skyllia;
        this.objectiveService = objectiveService;
    }

    /** Dostawca nazwy aktywnej edycji sezonu (%wpme_season_name%); null → "". */
    public void setSeasonNameSupplier(
            @Nullable java.util.function.Supplier<String> supplier) {
        this.seasonNameSupplier =
                supplier != null ? supplier : () -> "";
    }

    /** Źródło {@code %wpme_island_level%}; bez niego pole zostaje niepublikowane („—”). */
    public void setIslandLevels(
            @Nullable org.rafalohaki.wpmecore.addons.skyblock.island.IslandLevelService levels) {
        this.islandLevels = levels;
    }

    public synchronized void start() {
        stop();
        task = scheduler.globalRepeating(
                Duration.ofSeconds(1), REFRESH_INTERVAL, this::refreshOnline);
    }

    public synchronized void stop() {
        ScheduledTask current = task;
        task = null;
        if (current != null) {
            current.cancel();
        }
    }

    private volatile java.util.function.Predicate<UUID> oneblockDetector = id -> false;

    /** Setter-wiring po konstrukcji: publisher powstaje przed modułem OneBlock. */
    public void bindOneblockDetector(@NotNull java.util.function.Predicate<UUID> detector) {
        this.oneblockDetector = detector;
    }

    public void refresh(@NotNull UUID playerId) {
        PresentationService presentation = api.presentationService();
        if (presentation == null) {
            return;
        }
        try {
            long balance = ledger.playerBalance(playerId);
            publish(presentation, playerId, PresentationField.BALANCE,
                    CompactBalanceFormatter.exact(balance), Long.toString(balance));
            publish(presentation, playerId, PresentationField.BALANCE_COMPACT,
                    CompactBalanceFormatter.compact(balance), Long.toString(balance));

            String objective = objectiveService.resolveObjective(playerId);
            publish(presentation, playerId, PresentationField.NEXT_OBJECTIVE,
                    objective, objective);

            IslandView island = skyllia.islandOf(playerId).orElse(null);
            if (island == null) {
                publish(presentation, playerId, PresentationField.ISLAND_STATE,
                        "Brak wyspy", "NONE");
                publish(presentation, playerId, PresentationField.ISLAND_RANK,
                        "—", "0");
                return;
            }
            publish(presentation, playerId, PresentationField.ISLAND_STATE,
                    "Aktywna", "ACTIVE");
            publish(presentation, playerId, PresentationField.ISLAND_RANK,
                    "—", "0");
            // Tryb wyspy: EcoQuests bramkuje pule zadań dziennych warunkiem
            // placeholder_equals na %wpme_island_mode% (classic / oneblock).
            String mode = oneblockDetector.test(island.islandId()) ? "oneblock" : "classic";
            publish(presentation, playerId, PresentationField.ISLAND_MODE, mode, mode);
            // Poziom wyspy: tylko z cache (żadnej bazy na wątku gracza); przed
            // pierwszym przeliczeniem pole zostaje niepublikowane → „—”.
            var levels = islandLevels;
            if (levels != null) {
                levels.cached(island.islandId()).ifPresent(snapshot -> publish(presentation, playerId,
                        PresentationField.ISLAND_LEVEL,
                        Integer.toString(snapshot.level()), Integer.toString(snapshot.level())));
            }
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE,
                    "Could not refresh SkyBlock presentation for " + playerId, failure);
        }
    }
    private void refreshOnline() {
        publishSeasonName();
        // Folia: pętla chodzi na GlobalRegionScheduler, a resolveObjective czyta ekwipunek
        // (kilof, talizman) — to wolno robić tylko na wątku właściciela encji. Przegląd 2026-09-05.
        for (Player player : List.copyOf(plugin.getServer().getOnlinePlayers())) {
            UUID playerId = player.getUniqueId();
            var scheduled = player.getScheduler().run(plugin, task -> refresh(playerId), null);
            if (scheduled == null) {
                refresh(playerId); // gracz już offline/w trakcie wylogowania — ścieżka bez ekwipunku
            }
        }
    }

    /**
     * C5: SEASON_NAME jest Scope.SERVER — wystarczy jedna publikacja na
     * cykl odświeżania (nie per gracz). Nigdy nie rzuca na wątek timera.
     */
    private void publishSeasonName() {
        PresentationService presentation = api.presentationService();
        if (presentation == null) {
            return;
        }
        try {
            String seasonName = seasonNameSupplier.get();
            presentation.publishServer(PresentationField.SEASON_NAME,
                    seasonName, seasonName, VALUE_TTL);
        } catch (Throwable failure) {
            // Rodzina jarów starsza niż SEASON_NAME rzuca NoSuchFieldError co
            // cykl — loguj raz na minutę (FINE), nie zalewaj konsoli.
            long nowMinute = System.currentTimeMillis() / 60_000L;
            if (nowMinute != lastNoiseMinute) {
                lastNoiseMinute = nowMinute;
                plugin.getLogger().log(Level.FINE,
                        "Could not publish season name presentation", failure);
            }
        }
    }

    private static void publish(PresentationService presentation,
                                UUID playerId,
                                PresentationField field,
                                String display,
                                String raw) {
        presentation.publishPlayer(playerId, field, display, raw, VALUE_TTL);
    }
}
