package org.rafalohaki.wpmecore.addons.skyblock.quests;

import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;


import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import io.papermc.paper.event.inventory.ItemCraftedEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.shared.ItemNames;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Shared-island daily quests with buffered, serialized durable increments.
 * Event handlers never perform JDBC work on a Folia region thread.
 */
public final class DailyQuestService implements Listener {

    public static final String GOLD_LOTUS_ID = "skyblock:token/gold_lotus";
    public static final int WEEKLY_MILESTONE_GOAL = 15;

    private static final int[] QUEST_SLOTS = {11, 13, 15, 21, 23};

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage miniMessage;
    private final LedgerService ledger;
    private final SkylliaIntegration skyllia;
    private final QuestCatalog catalog;
    private CustomItemService customItemService;
    private volatile org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox outbox;
    private final Map<QuestRowKey, PendingProgress> pending = new ConcurrentHashMap<>();
    private final Set<QuestRowKey> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<java.util.concurrent.CompletableFuture<?>> activeWrites =
            ConcurrentHashMap.newKeySet();
    private final Set<UUID> opening = ConcurrentHashMap.newKeySet();
    /**
     * Ostatni okres dobowy ({@link QuestCatalog#period}), w którym wyspa
     * zamknęła zadanie. F20: wcześniej był to zwykły {@code Set<UUID>}, którego
     * NIC nie czyściło — po północy „ukończone dziś" zostawało prawdą na zawsze,
     * więc etap 7 przewodnika znikał na stałe po pierwszym zadaniu w życiu
     * wyspy. Klucz okresu zamyka to bez żadnego zadania sprzątającego.
     */
    private final Map<UUID, String> lastQuestPeriod = new ConcurrentHashMap<>();
    private volatile ScheduledTask flushTask;
    /** Tryb wyspy dla pul zadań: true = OneBlock. Setter-wiring po konstrukcji
        (Questy powstaja po module OneBlock, a OneBlock nie zna zadan). */
    private volatile java.util.function.Predicate<UUID> oneblockDetector = id -> false;

    public void bindOneblockDetector(@NotNull java.util.function.Predicate<UUID> detector) {
        this.oneblockDetector = detector;
    }

    private boolean oneblockIsland(@NotNull UUID islandId) {
        try {
            return oneblockDetector.test(islandId);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /**
     * Zliczenie rozbicia bloku centralnego OneBlocka — oneblock anuluje
     * BlockBreakEvent (sam obsluguje dropy i regen), więc vanilla'owe nasluchy
     * MONITOR ignoreCancelled nigdy by go nie zobaczyly. Wołane z watku encji
     * gracza przez obserwator w OneBlockService; karmi zadania typu ONEBLOCK.
     */
    public void recordOneblockBreak(@NotNull Player player, @NotNull UUID islandId,
                                    @NotNull Material broken) {
        LocalDate day = catalog.today();
        String period = catalog.period(day);
        for (QuestCatalog.Definition definition : catalog.active(day, true)) {
            if (definition.type() != QuestCatalog.Type.ONEBLOCK
                    || !definition.matches(broken.name())) {
                continue;
            }
            QuestRowKey key = new QuestRowKey(islandId, period, definition.id());
            QuestPayload payload = new QuestPayload(player.getUniqueId(),
                    definition.goal(), definition.reward());
            pending.compute(key, (ignored, current) -> {
                PendingProgress progress = current == null ? new PendingProgress() : current;
                progress.add(payload, 1L);
                return progress;
            });
        }
    }

    /**
     * Czy wyspa zamknęła dziś jakieś zadanie.
     *
     * <p>Stan sesji, nie prawda z bazy: po restarcie serwera odpowiedź wraca do
     * {@code false}, mimo że wiersz {@code wpme_sb_quest_progress} jest trwały.
     * Odczyt z bazy siedziałby na gorącej ścieżce tablicy bocznej (odświeżanej
     * co 5 s dla każdego gracza), więc trwałość tej odpowiedzi to osobna robota
     * — opisana w F20. Kierunek błędu jest bezpieczny: przewodnik może po
     * restarcie jeszcze raz zaproponować zadanie dnia, ale nigdy nie ukryje
     * zadania, którego nie zrobiono.
     */
    public boolean hasCompletedToday(@NotNull UUID islandId) {
        return catalog.period(catalog.today()).equals(lastQuestPeriod.get(islandId));
    }

    public DailyQuestService(@NotNull JavaPlugin plugin, @NotNull MenuService menus,
                      @NotNull MiniMessage miniMessage, @NotNull LedgerService ledger,
                      @NotNull SkylliaIntegration skyllia, @NotNull QuestCatalog catalog) {
        this(plugin, menus, miniMessage, ledger, skyllia, catalog, null);
    }

    public DailyQuestService(@NotNull JavaPlugin plugin, @NotNull MenuService menus,
                      @NotNull MiniMessage miniMessage, @NotNull LedgerService ledger,
                      @NotNull SkylliaIntegration skyllia, @NotNull QuestCatalog catalog,
                      @org.jetbrains.annotations.Nullable CustomItemService customItemService) {
        this.plugin = plugin;
        this.menus = menus;
        this.miniMessage = miniMessage;
        this.ledger = ledger;
        this.skyllia = skyllia;
        this.catalog = catalog;
        this.customItemService = customItemService;
    }

    /**
     * Outbox sprawia, że nagrody przedmiotowe i tygodniowe lotosy są trwałe:
     * offline właściciel dostaje je przy najbliższym wejściu, a crash po
     * zapisanym „rewarded" nie gubi dostawy (SKYBLOCK-1-4/1-5).
     */
    public void setInventoryOutbox(
            @org.jetbrains.annotations.Nullable org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox outbox) {
        this.outbox = outbox;
    }

    public @org.jetbrains.annotations.Nullable CustomItemService getCustomItemService() {
        if (this.customItemService != null) {
            return this.customItemService;
        }
        try {
            if (org.bukkit.Bukkit.getServer() != null && org.bukkit.Bukkit.getServicesManager() != null) {
                CustomItemService service = org.bukkit.Bukkit.getServicesManager().load(CustomItemService.class);
                if (service != null) {
                    return service;
                }
                org.rafalohaki.wpmecore.api.WpmeAPI api =
                        org.bukkit.Bukkit.getServicesManager().load(org.rafalohaki.wpmecore.api.WpmeAPI.class);
                if (api != null) {
                    return api.customItemService();
                }
            }
        } catch (Throwable ignored) {
            // Fallback gracefully in unit test environments
        }
        return null;
    }

    public void setCustomItemService(@org.jetbrains.annotations.Nullable CustomItemService customItemService) {
        this.customItemService = customItemService;
    }

    public @org.jetbrains.annotations.Nullable org.bukkit.inventory.ItemStack createItemStack(
            @NotNull String id, int amount) {
        if (amount <= 0) {
            return null;
        }
        CustomItemService service = getCustomItemService();
        if (service != null) {
            var opt = service.create(id);
            if (opt.isPresent()) {
                org.bukkit.inventory.ItemStack stack = opt.get();
                stack.setAmount(amount);
                return stack;
            }
        }
        Material mat = Material.matchMaterial(id);
        if (mat != null && mat.isItem() && !mat.isAir()) {
            return new org.bukkit.inventory.ItemStack(mat, amount);
        }
        return null;
    }

    public void giveOrDropItem(@NotNull Player player, @NotNull org.bukkit.inventory.ItemStack item) {
        Map<Integer, org.bukkit.inventory.ItemStack> leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (org.bukkit.inventory.ItemStack drop : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), drop);
            }
            player.sendMessage(Ui.component(miniMessage,
                    "<yellow>Ekwipunek jest pełny — część nagrody została upuszczona na ziemię.</yellow>"));
        }
    }

    public void start() {
        flushTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(
                plugin, ignored -> flush(), 2L, 2L, TimeUnit.SECONDS);
    }

    public void stopAndFlush() {
        ScheduledTask current = flushTask;
        flushTask = null;
        if (current != null) {
            current.cancel();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L);
        while (System.nanoTime() < deadline) {
            flush();
            if (!hasPendingWork()) {
                return;
            }
            java.util.concurrent.CompletableFuture<?>[] writes =
                    activeWrites.toArray(java.util.concurrent.CompletableFuture[]::new);
            if (writes.length == 0) {
                // A failed write remains the active batch with the same
                // idempotency token. Retry it once through flush(); if no
                // future can be accepted, fail closed without a hot spin.
                flush();
                writes = activeWrites.toArray(java.util.concurrent.CompletableFuture[]::new);
                if (writes.length == 0) {
                    break;
                }
            }
            try {
                long remaining = Math.max(1L, deadline - System.nanoTime());
                java.util.concurrent.CompletableFuture.allOf(writes)
                        .get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException ignored) {
                // The completion callback retained the exact failed batch;
                // the next loop iteration retries it while SQL is still alive.
                // Keep shutdown bounded without hammering a failed database.
                try {
                    TimeUnit.MILLISECONDS.sleep(50L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (TimeoutException timeout) {
                break;
            }
        }
        if (hasPendingWork()) {
            plugin.getLogger().warning("Daily quest flush timed out; pending increments remain in memory.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        boolean mature = event.getBlock().getBlockData() instanceof Ageable ageable
                && ageable.getAge() >= ageable.getMaximumAge();
        track(event.getPlayer(), event.getBlock().getLocation(), QuestCatalog.Type.BREAK,
                event.getBlock().getType().name(), mature);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        track(event.getPlayer(), event.getBlock().getLocation(), QuestCatalog.Type.PLACE,
                event.getBlock().getType().name(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            track(killer, event.getEntity().getLocation(), QuestCatalog.Type.KILL,
                event.getEntityType().name(), false);
        }
    }

    /**
     * Paper's {@code ItemCraftedEvent} fires when the result is actually taken from the
     * crafting slot and carries the real stack, so a shift-clicked batch counts once with
     * its true size. {@code CraftItemEvent} only reports the click and would score a
     * whole stack as a single craft.
     *
     * <p>The event is not cancellable and carries no location, so the player's own
     * position decides which island the progress belongs to.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraft(ItemCraftedEvent event) {
        ItemStack crafted = event.getCraftedItem();
        if (crafted.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        track(player, player.getLocation(), QuestCatalog.Type.CRAFT,
                crafted.getType().name(), false, crafted.getAmount());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            track(event.getPlayer(), event.getHook().getLocation(), QuestCatalog.Type.FISH,
                    "ANY", false);
        }
    }

    /**
     * F19: nazwa nagrody zamiast surowego identyfikatora. Lore pokazywało
     * „1x skyblock:token/silver_lotus”, czyli klucz z modeli, a nie przedmiot,
     * który gracz zobaczy w ekwipunku. Nazwa jest MiniMessage, więc wchodzi
     * wprost w linijkę lore; bez CustomItems zostaje sam identyfikator.
     */
    private @NotNull String rewardItemName(@NotNull String itemId) {
        CustomItemService service = getCustomItemService();
        return new ItemNames(service).customLabel(itemId);
    }

    public void open(@NotNull Player player) {
        open(player, false);
    }

    /**
     * Opens the daily-quest menu. When the Skyllia island cache is still cold
     * (e.g. right after /is create), retries once after a short delay instead
     * of showing "create an island first" to a player who just did.
     */
    private void open(@NotNull Player player, boolean isRetry) {
        IslandView island = skyllia.islandOf(player.getUniqueId()).orElse(null);
        if (island == null) {
            if (!isRetry) {
                player.sendActionBar(Ui.component(miniMessage,
                        "<gray>Wczytuję wyspę…</gray>"));
                plugin.getServer().getRegionScheduler().runDelayed(plugin,
                        player.getLocation(), ignored -> open(player, true), 40L);
            } else {
                player.sendMessage(Ui.component(miniMessage,
                        "<red>Nie masz jeszcze wyspy. Wpisz <yellow>/is</yellow>, wybierz tryb "
                                + "i kliknij zielony przycisk — potem wróć do <yellow>/zadania</yellow>.</red>"));
            }
            return;
        }
        if (!opening.add(player.getUniqueId())) {
            player.sendActionBar(Ui.component(miniMessage,
                    "<gray>Wczytuję postęp zadań…</gray>"));
            return;
        }
        LocalDate day = catalog.today();
        String period = catalog.period(day);
        LocalDate startOfWeek = catalog.startOfWeek(day);
        LocalDate endOfWeek = catalog.endOfWeek(day);
        String weekKey = catalog.weekKey(day);
        String startPeriod = catalog.period(startOfWeek);
        String endPeriod = catalog.period(endOfWeek);

        var statusesFuture = ledger.questStatuses(island.islandId(), period);
        var weeklyCountFuture = ledger.countCompletedQuestsInPeriod(
                island.islandId(), startPeriod, endPeriod);
        var milestoneClaimedFuture = ledger.isWeeklyMilestoneClaimed(
                island.islandId(), weekKey);

        java.util.concurrent.CompletableFuture.allOf(
                statusesFuture, weeklyCountFuture, milestoneClaimedFuture)
                .whenComplete((ignored, failure) -> {
            try {
                var scheduled = player.getScheduler().run(plugin, innerIgnored -> {
                    opening.remove(player.getUniqueId());
                    if (failure != null) {
                        plugin.getLogger().log(Level.WARNING, "Daily quest status load failed", failure);
                        player.sendMessage(Ui.component(miniMessage,
                                "<red>Nie udało się wczytać zadań. Spróbuj ponownie.</red>"));
                        return;
                    }
                    Map<String, LedgerDao.QuestStatus> statuses = statusesFuture.join();
                    int weeklyCount = weeklyCountFuture.join();
                    boolean milestoneClaimed = milestoneClaimedFuture.join();
                    openNow(player, day, oneblockIsland(island.islandId()),
                            statuses, weeklyCount, milestoneClaimed);
                }, () -> opening.remove(player.getUniqueId()));
                if (scheduled == null) {
                    opening.remove(player.getUniqueId());
                }
            } catch (RuntimeException rejected) {
                opening.remove(player.getUniqueId());
                plugin.getLogger().log(Level.FINE,
                        "Daily quest menu scheduling was rejected", rejected);
            }
        });
    }

    private void openNow(Player player, LocalDate day, boolean oneblock,
                         Map<String, LedgerDao.QuestStatus> statuses,
                         int weeklyCount, boolean milestoneClaimed) {
        MenuService.Menu menu = menus.ofRows(4,
                Ui.component(miniMessage, "<gold><bold>Dzienne zadania wyspy</bold></gold>"));
        Ui.frame(menu, miniMessage, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        List<QuestCatalog.Definition> active = catalog.active(day, oneblock);
        for (int index = 0; index < active.size(); index++) {
            QuestCatalog.Definition definition = active.get(index);
            LedgerDao.QuestStatus status = statuses.getOrDefault(definition.id(),
                    new LedgerDao.QuestStatus(0L, false));
            List<String> lore = new ArrayList<>(definition.lore());
            lore.add("<dark_gray> </dark_gray>");
            lore.add("<gray>Postęp: <white>" + Math.min(status.progress(), definition.goal())
                    + "/" + definition.goal() + "</white></gray>");
            lore.add("<gray>Nagroda do banku wyspy: </gray>" + Ui.price(definition.reward()));
            if (!definition.rewardItems().isEmpty()) {
                for (QuestCatalog.RewardItem item : definition.rewardItems()) {
                    lore.add("<gray>Nagroda: <yellow>" + item.amount() + "x </yellow>"
                            + rewardItemName(item.itemId()));
                }
            }
            lore.add(status.rewarded()
                    ? "<green>✓ Zrobione na dziś</green>"
                    : "<yellow>Liczy się to, co robi cała wyspa razem</yellow>");
            menu.decoration(QUEST_SLOTS[index], Ui.item(definition.icon(), miniMessage,
                    definition.name(), lore, status.rewarded()));
        }

        List<String> clockLore = new ArrayList<>();
        clockLore.add("<gray>Codziennie inny zestaw, bez kary za przerwę.</gray>");
        clockLore.add("<gray>Liczą się działania wykonane na własnej wyspie.</gray>");
        clockLore.add("<dark_gray>--------------------------------</dark_gray>");
        clockLore.add("<gold><bold>Tygodniowy kamień milowy:</bold></gold>");
        clockLore.add("<gray>Postęp w tygodniu: <white>"
                + Math.min(weeklyCount, WEEKLY_MILESTONE_GOAL) + "/" + WEEKLY_MILESTONE_GOAL + "</white></gray>");
        // F24: było „1x Złoty Lotos (skyblock:token/gold_lotus)” — nawias to klucz
        // z pliku modeli, a nie nazwa przedmiotu (ta sama pomyłka, którą F19
        // usunęła z lore pojedynczych zadań, została tu przeoczona). Do tego
        // TO JEST jedyny most z zadań do sezonu: Złoty Lotos ma w całej grze
        // dokładnie jeden zlew — aktywację toru premium karnetu — i nigdzie nie
        // było o tym słowa w oknie, w którym się go zdobywa.
        clockLore.add("<gray>Nagroda: <gold>1x </gold>" + rewardItemName(GOLD_LOTUS_ID));
        clockLore.add("<gray>Złotym Lotosem otwierasz tor premium karnetu</gray>");
        clockLore.add("<gray>sezonowego <dark_gray>—</dark_gray> <yellow>/przepustka</yellow><gray>.</gray>");
        if (milestoneClaimed) {
            clockLore.add("<green>✓ Kamień milowy odebrany w tym tygodniu!</green>");
        } else if (weeklyCount >= WEEKLY_MILESTONE_GOAL) {
            clockLore.add("<green>★ Odblokowano w tym tygodniu!</green>");
        } else {
            clockLore.add("<yellow>Pozostało jeszcze " + (WEEKLY_MILESTONE_GOAL - weeklyCount) + " zadań.</yellow>");
        }

        menu.decoration(4, Ui.item(Material.CLOCK, miniMessage,
                "<aqua>Zestaw na <date></aqua>",
                clockLore, false,
                Placeholder.unparsed("date", day.toString())));
        menu.close(31, Ui.closeButton(miniMessage));
        menu.open(player);
    }

    private void track(Player player, org.bukkit.Location location, QuestCatalog.Type type,
                       String target, boolean mature) {
        track(player, location, type, target, mature, 1L);
    }

    private void track(Player player, org.bukkit.Location location, QuestCatalog.Type type,
                       String target, boolean mature, long amount) {
        IslandView island = skyllia
                .islandAt(player.getUniqueId(), location).orElse(null);
        if (island == null) {
            return;
        }
        LocalDate day = catalog.today();
        String period = catalog.period(day);
        for (QuestCatalog.Definition definition : catalog.active(day,
                oneblockIsland(island.islandId()))) {
            if (definition.type() != type || !definition.matches(target)
                    || (definition.matureOnly() && !mature)) {
                continue;
            }
            QuestRowKey key = new QuestRowKey(island.islandId(), period, definition.id());
            QuestPayload payload = new QuestPayload(player.getUniqueId(),
                    definition.goal(), definition.reward());
            pending.compute(key, (ignored, current) -> {
                PendingProgress progress = current == null ? new PendingProgress() : current;
                progress.add(payload, amount);
                return progress;
            });
        }
    }

    private void flush() {
        pending.forEach((key, progress) -> {
            if (!inFlight.add(key)) {
                return;
            }
            ActiveBatch batch = progress.nextBatch();
            if (batch == null) {
                inFlight.remove(key);
                removeIfIdle(key, progress);
                return;
            }
            java.util.concurrent.CompletableFuture<LedgerDao.QuestMutation> write;
            try {
                write = ledger.incrementQuest(key.islandId(), batch.payload().ownerId(),
                        key.period(), key.questId(), batch.increment(),
                        batch.payload().goal(), batch.payload().reward(), batch.batchId());
            } catch (RuntimeException rejected) {
                inFlight.remove(key);
                if (progress.shouldLogFailure(batch)) {
                    plugin.getLogger().log(Level.WARNING,
                            "Daily quest progress flush could not start for "
                                    + key.questId(), rejected);
                }
                return;
            }
            activeWrites.add(write);
            write.whenComplete((result, failure) -> {
                        if (failure != null) {
                            if (progress.shouldLogFailure(batch)) {
                                plugin.getLogger().log(Level.WARNING,
                                        "Daily quest progress flush failed for "
                                                + key.questId(), failure);
                            }
                        } else {
                            progress.complete(batch);
                            if (result.rewardedNow()) {
                                notifyCompletion(key, batch);
                            }
                        }
                        inFlight.remove(key);
                        activeWrites.remove(write);
                        removeIfIdle(key, progress);
                    });
        });
    }

    void notifyCompletion(QuestRowKey key, ActiveBatch batch) {
        lastQuestPeriod.put(key.islandId(), key.period());
        var message = Ui.component(miniMessage,
                "<green><bold>Zadanie ukończone!</bold></green> "
                        + "<gray>Do banku wyspy trafiło <gold><reward></gold>.</gray>",
                Placeholder.unparsed("reward", Ui.money(batch.payload().reward())));
        QuestCatalog.Definition definition = catalog.definition(key.questId()).orElse(null);
        if (plugin.getServer() != null) {
            try {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                    for (Player online : plugin.getServer().getOnlinePlayers()) {
                        boolean member = skyllia.cachedIslandIdOf(online.getUniqueId())
                                .map(id -> id.equals(key.islandId()))
                                .orElse(false);
                        if (member) {
                            online.getScheduler().run(plugin, ignored ->
                                    online.sendMessage(message), null);
                        }
                    }
                });
            } catch (RuntimeException rejected) {
                if (plugin.getLogger() != null) {
                    plugin.getLogger().log(Level.FINE,
                            "Daily quest completion broadcast was rejected", rejected);
                }
            }
        }

        if (definition != null && !definition.rewardItems().isEmpty()) {
            grantQuestItemRewards(key, batch, definition);
        }

        checkWeeklyMilestone(key.islandId());
    }

    /**
     * Nagrody przedmiotowe zadań idą trwale: do online właściciela przez
     * beginGrant, a gdy wyszedł przed flushem — jako dług outboxa rozliczany
     * przy wejściu. Deterministyczne id chroni przed duplikatem przy ponownym
     * rozpatrzeniu tej samej nagrody (SKYBLOCK-1-5).
     */
    private void grantQuestItemRewards(QuestRowKey key, ActiveBatch batch,
                                       QuestCatalog.Definition definition) {
        org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox durable = outbox;
        String period = catalog.period(catalog.today());
        UUID ownerId = batch.payload().ownerId();
        Player onlineOwner = plugin.getServer() != null
                ? plugin.getServer().getPlayer(ownerId) : null;
        for (QuestCatalog.RewardItem rewardItem : definition.rewardItems()) {
            org.bukkit.inventory.ItemStack itemStack =
                    createItemStack(rewardItem.itemId(), rewardItem.amount());
            if (itemStack == null) {
                if (plugin.getLogger() != null) {
                    plugin.getLogger().severe("Quest reward item could not be created: "
                            + rewardItem.itemId() + " (quest " + key.questId() + ")");
                }
                continue;
            }
            var rewardMessage = Ui.component(miniMessage,
                    "<green>Otrzymano nagrodę zadania: <yellow><amount>x <item></yellow>!</green>",
                    Placeholder.unparsed("amount", String.valueOf(rewardItem.amount())),
                    Placeholder.unparsed("item", rewardItem.itemId()));
            String operationId = "quest:" + period + ':' + key.questId() + ':'
                    + ownerId + ":items:" + rewardItem.itemId();
            if (durable == null) {
                // Awaryjny fallback bez outboxa: tylko online, jak dawniej.
                if (onlineOwner != null) {
                    giveOrDropItem(onlineOwner, itemStack);
                    onlineOwner.sendMessage(rewardMessage);
                }
                continue;
            }
            java.util.function.Consumer<org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox.Outcome>
                    onGranted = outcome -> {
                if (outcome == org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox.Outcome.SUCCESS) {
                    Player owner = plugin.getServer() != null
                            ? plugin.getServer().getPlayer(ownerId) : null;
                    if (owner != null) {
                        owner.sendMessage(rewardMessage);
                    }
                }
            };
            if (onlineOwner != null) {
                durable.beginGrant(onlineOwner, 0L, operationId,
                        "quest_reward_items", itemStack, onGranted);
            } else {
                durable.beginOfflineGrant(ownerId, 0L, operationId,
                        "quest_reward_items", itemStack, onGranted);
            }
        }
    }

    java.util.concurrent.CompletableFuture<Void> checkWeeklyMilestone(@NotNull UUID islandId) {
        LocalDate today = catalog.today();
        LocalDate startOfWeek = catalog.startOfWeek(today);
        LocalDate endOfWeek = catalog.endOfWeek(today);
        String weekKey = catalog.weekKey(today);
        String startPeriod = catalog.period(startOfWeek);
        String endPeriod = catalog.period(endOfWeek);

        return ledger.countCompletedQuestsInPeriod(islandId, startPeriod, endPeriod)
                .thenCompose(completedCount -> {
                    if (completedCount >= WEEKLY_MILESTONE_GOAL) {
                        /*
                         * Właściciel musi być znany PRZED claimem: bez niego nie ma
                         * komu wydać Lotosa, a zapisany claim przepaliłby nagrodę
                         * na zawsze. Pusty wynik = ponowna próba przy kolejnym flushu.
                         */
                        java.util.Optional<org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandOwner> owner =
                                skyllia.ownerOf(islandId);
                        if (owner.isEmpty()) {
                            if (plugin.getLogger() != null) {
                                plugin.getLogger().warning(
                                        "Weekly milestone reached for island " + islandId
                                                + " but its owner is unknown; retrying on a later flush");
                            }
                            return java.util.concurrent.CompletableFuture.completedFuture(null);
                        }
                        UUID ownerId = owner.get().playerId();
                        /*
                         * Odbiorcy komunikatu ze SKŁADU WYSPY Z BAZY, nie z cache
                         * Skyllii: po skasowaniu i odtworzeniu wyspy o tym samym
                         * island_id migawka cache bywa pusta, więc filtr po niej
                         * nie wysyłał komunikatu nikomu (E2E 2026-09-10 — Lotos
                         * szedł outboxem, więc defekt był cichy). Jedno zapytanie,
                         * ten sam wątek SQL co claim.
                         */
                        return ledger.activeIslandMemberIds(islandId)
                                .thenCompose(members -> ledger.claimWeeklyMilestone(islandId, weekKey)
                                        .thenAccept(claimedNow -> {
                                            if (claimedNow) {
                                                notifyWeeklyMilestone(islandId, weekKey, ownerId, members);
                                            }
                                        }));
                    }
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                })
                .exceptionally(failure -> {
                    if (plugin.getLogger() != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to process weekly milestone for island " + islandId, failure);
                    }
                    return null;
                });
    }

    /**
     * Dokładnie jeden Złoty Lotos na wyspę na tydzień — trwale, do właściciela
     * (offline → dług outboxa przy najbliższym wejściu). Dawniej każdy online
     * członek dostawał osobny egzemplarz, a przy pustej wyspie nikt (SKYBLOCK-1-4).
     *
     * <p>Odbiorców komunikatu bierzemy ze składu wyspy z bazy ({@code members}),
     * a właściciel dostaje go zawsze, nawet gdy jego wiersz membership jest
     * chwilowo niespójny. Cache Skyllii nie bierze udziału w tej decyzji — patrz
     * {@link #checkWeeklyMilestone(UUID)}.
     */
    private void notifyWeeklyMilestone(@NotNull UUID islandId, @NotNull String weekKey,
                                       @NotNull UUID ownerId,
                                       @NotNull Set<UUID> members) {
        var milestoneMessage = Ui.component(miniMessage,
                "<gold><bold>✦ TYGODNIOWY KAMIEŃ MILOWY OSIĄGNIĘTY! ✦</bold></gold>\n"
                        + "<yellow>Twoja wyspa ukończyła <gold>15</gold> zadań w tym tygodniu!</yellow>\n"
                        + "<green>Nagroda: <gold>1x Złoty Lotos</gold> dla właściciela wyspy.</green>");
        if (plugin.getServer() != null) {
            try {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                    for (Player online : plugin.getServer().getOnlinePlayers()) {
                        UUID onlineId = online.getUniqueId();
                        if (!onlineId.equals(ownerId) && !members.contains(onlineId)) {
                            continue;
                        }
                        online.getScheduler().run(plugin, ignored ->
                                online.sendMessage(milestoneMessage), null);
                    }
                });
            } catch (RuntimeException rejected) {
                if (plugin.getLogger() != null) {
                    plugin.getLogger().log(Level.FINE,
                            "Weekly milestone broadcast was rejected", rejected);
                }
            }
        }

        org.bukkit.inventory.ItemStack goldLotus = createItemStack(GOLD_LOTUS_ID, 1);
        if (goldLotus == null) {
            if (plugin.getLogger() != null) {
                plugin.getLogger().severe("Weekly milestone lotus could not be created for island "
                        + islandId + " week " + weekKey + " — reward requires manual delivery");
            }
            return;
        }
        String operationId = "milestone:" + islandId + ':' + weekKey;
        org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox durable = outbox;
        Player onlineOwner = plugin.getServer() != null ? plugin.getServer().getPlayer(ownerId) : null;
        if (durable == null) {
            if (onlineOwner != null) {
                giveOrDropItem(onlineOwner, goldLotus);
            } else if (plugin.getLogger() != null) {
                plugin.getLogger().warning("Weekly milestone lotus for island " + islandId
                        + " week " + weekKey + " skipped: owner offline and no outbox");
            }
            return;
        }
        if (onlineOwner != null) {
            durable.beginGrant(onlineOwner, 0L, operationId, "weekly_milestone", goldLotus, outcome -> { });
        } else {
            durable.beginOfflineGrant(ownerId, 0L, operationId, "weekly_milestone", goldLotus, outcome -> { });
        }
    }

    private boolean hasPendingWork() {
        return !inFlight.isEmpty()
                || pending.values().stream().anyMatch(PendingProgress::hasWork);
    }

    private void removeIfIdle(QuestRowKey key, PendingProgress progress) {
        pending.computeIfPresent(key, (ignored, current) ->
                current == progress && !current.hasWork() && !inFlight.contains(key)
                        ? null : current);
    }

    record QuestRowKey(@NotNull UUID islandId, @NotNull String period,
                       @NotNull String questId) { }

    record QuestPayload(@NotNull UUID ownerId, long goal, long reward) { }

    record ActiveBatch(@NotNull String batchId, @NotNull QuestPayload payload,
                       long increment) { }

    static final class PendingProgress {
        private final ArrayDeque<PendingGroup> waiting = new ArrayDeque<>();
        private ActiveBatch active;
        private int attempts;

        synchronized void add(QuestPayload payload) {
            add(payload, 1L);
        }

        /**
         * @param amount progress won in one go. Crafting reports a whole batch at once,
         *               because shift-clicking a recipe yields a full stack from a single
         *               event — counting one per event would undercount it badly.
         */
        synchronized void add(QuestPayload payload, long amount) {
            if (amount <= 0L) {
                return;
            }
            PendingGroup tail = waiting.peekLast();
            if (tail != null && tail.payload.equals(payload)) {
                tail.increment = Math.addExact(tail.increment, amount);
                return;
            }
            waiting.addLast(new PendingGroup(payload, amount));
        }

        synchronized ActiveBatch nextBatch() {
            if (active == null) {
                PendingGroup next = waiting.pollFirst();
                if (next == null) {
                    return null;
                }
                active = new ActiveBatch("quest:progress:" + UUID.randomUUID(),
                        next.payload, next.increment);
                attempts = 0;
            }
            attempts++;
            return active;
        }

        synchronized void complete(ActiveBatch completed) {
            if (active == null || !active.batchId().equals(completed.batchId())) {
                throw new IllegalStateException("quest batch completion does not match active batch");
            }
            active = null;
            attempts = 0;
        }

        synchronized boolean shouldLogFailure(ActiveBatch failed) {
            if (active == null || !active.batchId().equals(failed.batchId())) {
                return true;
            }
            return attempts == 1 || attempts % 30 == 0;
        }

        synchronized boolean hasWork() {
            return active != null || !waiting.isEmpty();
        }
    }

    private static final class PendingGroup {
        private final QuestPayload payload;
        private long increment;

        private PendingGroup(QuestPayload payload, long increment) {
            this.payload = payload;
            this.increment = increment;
        }
    }
}
