package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.block.data.Ageable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M2.5: questy sezonowe v1 (pilot) — listener na zdarzeniach ruchu gracza
 * (BREAK/PLACE/KILL/FISH/CRAFT), licznik osobisty per gracz, nagradzanie
 * przez {@link SeasonPointService#award}.
 *
 * <p>Progi odblokowań: gracz ma dostęp do pierwszych N definicji z katalogu,
 * gdzie N = unlockedQuestSlots(sezonPoints). Kolejność definicji = kolejność
 * odblokowywania (config list order).
 *
 * <p>M2.5b: liczniki są trwałe (migracja #6, {@link SeasonQuestProgressDao}) —
 * load przy starcie per aktywny sezon, save async przy każdym przyroście.
 * Restart nie zeruje drążków; nagroda pozostaje idempotentna przez
 * reward_claims, więc powtórzone award po odzyskaniu postępu nie dubluje punktów.
 *
 * <p>D3 (runda 3 odporności): handler zdarzeń działa na wątkach regionu Folia,
 * więc {@code unlockedFor} NIE wolno blokująco czytać punktów z SQL (dawne
 * {@code pointsOf(...).join()} potrafiło zablokować lane SQL na własnym
 * wątku). Punkty trzyma cache {@code pointsCache}; podgrzewany przy wejściu
 * gracza, po loadzie postępu i po każdym award; cold miss = 0 otwartych
 * slotów + asynchroniczne odświeżenie na następne zdarzenie. Nigdy join.
 *
 * <p>Questy eventu (pakiet aktywnej edycji, {@code SeasonQuestCatalog#activePackQuests})
 * przychodzą osobnym dostawcą czytanym przy każdym zdarzeniu: nie mapują się
 * na sloty progów, są otwarte przez całe okno edycji i znikają wraz z nim
 * bez restartu. Katalog slotowy ({@link #catalog()}) to wyłącznie questy
 * uniwersalne.
 */
public final class SeasonQuestService implements Listener {

    /** Definicja jednego questa sezonowego (z config.yml season.quests.definitions). */
    public record Definition(@NotNull String id, @NotNull Type type, @NotNull Set<String> targets,
                             int goal, long points, boolean matureOnly,
                             @NotNull org.bukkit.Material icon,
                             @NotNull String name, @NotNull List<String> lore) {
        public enum Type { BREAK, PLACE, KILL, FISH, CRAFT }
    }

    private final Map<UUID, Map<String, Integer>> progress = new ConcurrentHashMap<>();
    /**
     * D3: ostatnio znane punkty sezonowe per gracz. Jedyny źródło dla
     * {@link #unlockedFor} — czytanie prosto z SQL (dawne join) blokowałoby
     * wątek regionu Folia / lane SQL. Podgrzewane w {@link #refreshPointsCache}
     * (join gracza, load postępu, award); cold miss = 0 slotów + refresh async.
     */
    private final Map<UUID, Long> pointsCache = new ConcurrentHashMap<>();
    private final SeasonPointService pointsService;
    /** Questy uniwersalne: indeks = slot odblokowań. */
    private final List<Definition> catalog;
    /** Questy eventu aktywnej edycji — bez slotów; pusta lista poza edycją. */
    private final java.util.function.Supplier<List<Definition>> eventQuests;
    private final SeasonQuestProgressDao progressDao;
    private final java.util.logging.Logger logger;

    /** Wariant bez questów eventu (sezon bez pakietu, testy). */
    public SeasonQuestService(@NotNull SeasonPointService pointsService,
                              @NotNull List<Definition> catalog,
                              @NotNull SeasonQuestProgressDao progressDao,
                              @NotNull java.util.logging.Logger logger) {
        this(pointsService, catalog, List::of, progressDao, logger);
    }

    public SeasonQuestService(@NotNull SeasonPointService pointsService,
                              @NotNull List<Definition> catalog,
                              @NotNull java.util.function.Supplier<List<Definition>> eventQuests,
                              @NotNull SeasonQuestProgressDao progressDao,
                              @NotNull java.util.logging.Logger logger) {
        this.pointsService = pointsService;
        this.catalog = List.copyOf(catalog);
        this.eventQuests = eventQuests;
        this.progressDao = progressDao;
        this.logger = logger;
    }

    /**
     * M2.5b: wczytanie postępu dla aktywnego sezonu (onEnable). Zastępuje
     * zawartość pamięci — wywoływane raz, zanim gracze zaczną liczyć zdarzenia.
     */
    public @NotNull CompletableFuture<Void> loadProgress(int seasonId) {
        return progressDao.load(seasonId).thenApply(loaded -> {
            progress.clear();
            loaded.forEach((playerUuid, quests) ->
                    progress.put(playerUuid, new ConcurrentHashMap<>(quests)));
            // D3: podgrzej cache punktów dla graczy z postępem — pierwsze
            // zdarzenie po starcie nie może wpaść w cold miss (0 slotów).
            loaded.keySet().forEach(this::refreshPointsCache);
            return null;
        });
    }

    /** Migracja Eco: katalog pusty i liczniki wyłączone — punkty przyznaje EcoQuests. */
    private volatile boolean migrated;

    public void setMigrated(boolean migrated) {
        this.migrated = migrated;
    }

    public boolean isMigrated() {
        return migrated;
    }

    public @NotNull List<Definition> catalog() {
        if (migrated) {
            return List.of();
        }
        return catalog;
    }

    /** Questy eventu w tej chwili (pusta lista poza oknem edycji z pakietem). */
    public @NotNull List<Definition> eventQuests() {
        if (migrated) {
            return List.of();
        }
        return eventQuests.get();
    }

    /** Kandydaci do zliczenia: sloty + event; bez alokacji, gdy eventu nie ma. */
    private @NotNull List<Definition> candidates() {
        List<Definition> event = eventQuests.get();
        if (event.isEmpty()) return catalog;
        List<Definition> all = new java.util.ArrayList<>(catalog.size() + event.size());
        all.addAll(catalog);
        all.addAll(event);
        return all;
    }

    /**
     * D3: ile questów ten gracz ma otwartych — WYŁĄCZNIE z cache punktów.
     * Wywoływane w handlerach zdarzeń (wątki regionu Folia), więc nigdy nie
     * dotyka SQL blokująco. Cold miss = 0 otwartych slotów + asynchroniczne
     * odświeżenie na następne zdarzenie tego gracza.
     */
    public int unlockedFor(@NotNull UUID playerUuid) {
        Long cachedPoints = pointsCache.get(playerUuid);
        if (cachedPoints == null) {
            refreshPointsCache(playerUuid);
            return 0;
        }
        return pointsService.unlockedQuestSlots(cachedPoints);
    }

    /**
     * D3: asynchroniczne odświeżenie cache punktów gracza. Callback jest
     * celowo trywialny (put do ConcurrentHashMap) — wykonuje się na wątku
     * kończącym future (lane SQL), nie na wątku wywołującym.
     */
    public void refreshPointsCache(@NotNull UUID playerUuid) {
        pointsService.pointsOf(playerUuid)
                .thenAccept(points -> pointsCache.put(playerUuid, points));
    }

    /** Postęp gracza na danym queście (0 gdy brak). */
    public int progressOf(@NotNull UUID playerUuid, @NotNull String questId) {
        return progress.getOrDefault(playerUuid, Map.of()).getOrDefault(questId, 0);
    }

    private void increment(@NotNull UUID playerUuid, @NotNull Definition def, int amount) {
        int index = catalog.indexOf(def);
        if (index < 0) {
            if (!eventQuests.get().contains(def)) return; // spoza obu list
        } else if (index >= unlockedFor(playerUuid)) {
            return; // zablokowany progiem (tylko questy slotowe)
        }

        var playerProgress = progress.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>());
        int now = playerProgress.merge(def.id(), amount, Integer::sum);
        // Save przy każdym przyroście (async); błąd zapisu nie cofa licznika w
        // pamięci — kolejny przyrost nadpisze stan, a nagroda jest dedupowana
        // przez reward_claims, więc najwyżej licznik cofnie się o jeden restart.
        progressDao.save(pointsService.currentSeason(), playerUuid, def.id(), now)
                .exceptionally(err -> {
                    logger.log(java.util.logging.Level.WARNING,
                            "Nie udało się zapisać postępu questa " + def.id()
                                    + " dla " + playerUuid, err);
                    return null;
                });
        // ">=" a nie "==": po restarcie licznik wraca już na poziomie celu,
        // a operacja award jest idempotentna (operationId), więc powtórzenia
        // są odrzucane przez ledger i naprawiają ewentualną utraconą nagrodę.
        if (now >= def.goal()) {
            pointsService.award(playerUuid, def.points(), "season-quest:" + playerUuid
                    + ":" + pointsService.currentSeason() + ":" + def.id())
                    .whenComplete((ok, err) -> {
                        if (!Boolean.TRUE.equals(ok) && err != null) {
                            java.util.logging.Logger.getLogger(SeasonQuestService.class.getName())
                                    .warning("Nagroda questu " + def.id() + " dla " + playerUuid
                                            + " nieudana: " + err);
                        }
                        // D3: award mógł podnieść punkty (nowy próg odblokowań)
                        // — odśwież cache, by kolejne zdarzenia widziały progi.
                        refreshPointsCache(playerUuid);
                    });
        }
    }

    /**
     * D3: podgrzanie cache punktów przy wejściu gracza — pierwsze zdarzenie
     * po joinie nie trafia w cold miss. Ciało trywialne (async chain bez
     * blokowania); SQL dzieje się na lane, callback to samo put do mapy.
     */
    @EventHandler
    public void onJoin(@NotNull PlayerJoinEvent event) {
        refreshPointsCache(event.getPlayer().getUniqueId());
    }


    /**
     * CRAFT: jedno kliknięcie warsztatu = jeden postęp, niezależnie od
     * wielkości batcha (shift-click nie mnoży licznika — jak inne eventy,
     * które liczą jednostkowe akcje gracza).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(@NotNull CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) return;
        if (!(event.getRecipe() instanceof CraftingRecipe recipe)) return; // np. ComplexRecipe — bez wyniku do porównania
        ItemStack result = recipe.getResult();
        if (result == null || result.getType() == org.bukkit.Material.AIR) return;
        for (Definition def : candidates()) {
            if (def.type() == Definition.Type.CRAFT && matches(def, result.getType().name())) {
                increment(player.getUniqueId(), def, 1);
            }
        }
    }

    private static boolean matches(@NotNull Definition def, @NotNull String target) {
        return def.targets().contains("*") || def.targets().contains(target.toUpperCase(Locale.ROOT));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(@NotNull BlockBreakEvent event) {
        boolean mature = !(event.getBlock().getBlockData() instanceof Ageable ageable)
                || ageable.getAge() >= ageable.getMaximumAge();
        recordBreak(event.getPlayer().getUniqueId(), event.getBlock().getType().name(), mature);
    }

    /**
     * F24: rozbicie zaliczone POZA nasłuchem waniliowym — dla centrum OneBlocka.
     *
     * <p>{@code OneBlockService} anuluje własny {@link BlockBreakEvent} (platforma
     * nie może zniknąć ani na tik, bo gracz spadłby w pustkę), więc handler
     * z {@code ignoreCancelled = true} nigdy go nie widzi. Skutek na wysyłanym
     * katalogu: rozdział „Podziemia i Jaskinia” ({@code oneblock.yml}, bloki
     * 250-750) składa się z STONE/COBBLESTONE/COAL_ORE/IRON_ORE, czyli dokładnie
     * z celów {@code sq_mine_1} (cel 96) — a gracz OneBlocka przechodził przez
     * niego z zerowym postępem, bo jedyna czynność tego trybu nie docierała do
     * licznika. Ta sama dziura była już domknięta dwa razy dla tego samego
     * zdarzenia: {@code DailyQuestService.recordOneblockBreak} i
     * {@code SkyBlockProgressiveObjectiveService.observeBreak}; to trzeci
     * odbiorca tego samego obserwatora, nie nowy mechanizm.
     *
     * <p>Bez dublowania: skoro zdarzenie waniliowe jest anulowane, jedna akcja
     * gracza trafia tu dokładnie raz.
     */
    public void recordBreak(@NotNull UUID playerUuid, @NotNull String materialName) {
        recordBreak(playerUuid, materialName, true);
    }

    /**
     * @param mature czy blok był dojrzały (dla {@code Ageable}: wiek = maksimum; inne bloki
     *               zawsze). Questy z {@code mature-only} liczą wyłącznie dojrzałe — bez tej
     *               bramki sadzonka łamana w kółko zaliczała „Sezonowe zbiory”.
     */
    public void recordBreak(@NotNull UUID playerUuid, @NotNull String materialName, boolean mature) {
        if (migrated) {
            return;
        }
        for (Definition def : candidates()) {
            if (def.type() == Definition.Type.BREAK && matches(def, materialName)
                    && (mature || !def.matureOnly())) {
                increment(playerUuid, def, 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        for (Definition def : candidates()) {
            if (def.type() == Definition.Type.PLACE
                    && matches(def, event.getBlockPlaced().getType().name())) {
                increment(event.getPlayer().getUniqueId(), def, 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(@NotNull EntityDeathEvent event) {
        if (event.getEntity().getKiller() == null) return;
        for (Definition def : candidates()) {
            if (def.type() == Definition.Type.KILL
                    && matches(def, event.getEntityType().name())) {
                increment(event.getEntity().getKiller().getUniqueId(), def, 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(@NotNull PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        ItemStack caught = event.getCaught() instanceof org.bukkit.entity.Item item
                ? item.getItemStack() : null;
        if (caught == null || caught.getType() == org.bukkit.Material.AIR) return;
        for (Definition def : candidates()) {
            if (def.type() == Definition.Type.FISH && matches(def, caught.getType().name())) {
                increment(event.getPlayer().getUniqueId(), def, 1);
            }
        }
    }
}
