package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * OneBlock runtime: the platform holds a real block, so vanilla owns drops, XP
 * and tool requirements (D1). This service only tracks progression, rolls the
 * custom bonus next to the block, freezes milestone loot and places the next
 * block (spec §6.1, steps 1-7).
 */
public final class OneBlockService implements Listener {

    public enum RepairResult {
        SUCCESS,
        NOT_ONEBLOCK,
        NOT_AIR,
        COOLDOWN,
        WORLD_UNLOADED
    }

    private enum InitState { UNINITIALIZED, LOADING, READY, FAILED }

    /** Ile bloków w górę od platformy malować biom rozdziału (3×3 kolumny). */

    /** Obserwator zliczen dla zadan dziennych (pula oneblock); wywolywany po
        udanym rozbiciu, z try/catch — bledy zadan nie moga zamrozic kopania. */
    public interface BreakObserver {
        void onBreak(@NotNull java.util.UUID islandId, @NotNull java.util.UUID playerId,
                     @NotNull Material broken);
    }

    private volatile BreakObserver breakObserver;

    public void bindBreakObserver(@NotNull BreakObserver observer) {
        this.breakObserver = observer;
    }

    /** Czy wyspa ma blok OneBlock (tryb rozdzialu) — zrodlo prawdy dla pul zadan. */
    public boolean isOneblock(@NotNull java.util.UUID islandId) {
        return byIsland.containsKey(islandId);
    }

    static final int BIOME_CEILING = 24;

    private final JavaPlugin plugin;
    private final OneBlockDao dao;
    private final OneBlockMilestoneDao milestoneDao;
    private final OneBlockContent content;
    private final MiniMessage miniMessage;
    private final CustomItemService customItems;
    private final Map<OneBlockState.LocationKey, OneBlockState> byLocation = new ConcurrentHashMap<>();
    private final Map<UUID, OneBlockState> byIsland = new ConcurrentHashMap<>();
    private final Map<UUID, Long> repairCooldowns = new ConcurrentHashMap<>();
    private final CompletableFuture<Void> initFuture = new CompletableFuture<>();
    private final Duration loadRetryDelay;
    private volatile InitState initState = InitState.UNINITIALIZED;

    /**
     * Ile razy ładowanie stanów może się wywrócić, zanim usługa przejdzie w
     * {@link InitState#FAILED}. Awaria puli SQLite sama się goi (zablokowany plik,
     * chwilowy timeout), a poprzednia wersja poddawała się po pierwszej próbie
     * i nie ładowała stanów już nigdy — do restartu serwera (ONEBLOCK-2).
     */
    static final int MAX_LOAD_ATTEMPTS = 5;

    public OneBlockService(@NotNull JavaPlugin plugin, @NotNull OneBlockDao dao,
                           @NotNull OneBlockMilestoneDao milestoneDao,
                           @NotNull OneBlockContent content, @NotNull MiniMessage miniMessage,
                           @Nullable CustomItemService customItems) {
        this(plugin, dao, milestoneDao, content, miniMessage, customItems,
                Duration.ofSeconds(20));
    }

    /** Widoczne w pakiecie wyłącznie po to, żeby test nie czekał 20 s na ponowienie. */
    OneBlockService(@NotNull JavaPlugin plugin, @NotNull OneBlockDao dao,
                    @NotNull OneBlockMilestoneDao milestoneDao,
                    @NotNull OneBlockContent content, @NotNull MiniMessage miniMessage,
                    @Nullable CustomItemService customItems,
                    @NotNull Duration loadRetryDelay) {
        this.plugin = plugin;
        this.dao = dao;
        this.milestoneDao = milestoneDao;
        this.content = content;
        this.miniMessage = miniMessage;
        this.customItems = customItems;
        this.loadRetryDelay = loadRetryDelay;
    }

    public @NotNull CompletableFuture<Void> initialize() {
        attemptLoad(1);
        return initFuture;
    }

    /**
     * Ponowienie biegnie na {@code delayedExecutor} (ForkJoinPool), a nie na
     * schedulerze Bukkita: cały łańcuch dotyka wyłącznie map współbieżnych,
     * DAO i loggera, więc nie ma tu nic, co należałoby do wątku regionu.
     */
    private void attemptLoad(int attempt) {
        initState = InitState.LOADING;
        dao.loadAll().thenAccept(list -> {
            for (OneBlockState state : list) {
                /*
                 * ONEBLOCK-4: mapState czyta phase_id bez walidacji, a jedyny
                 * strażnik (setProgress) stoi na komendzie admina. Po edycji
                 * `id:` w oneblock.yml baza niesie fazę, której już nie ma —
                 * cicho, aż do pierwszego rozbicia. Klamp jest fail-closed
                 * (pierwszy rozdział, nie ostatni) i głośny, bo to zawsze
                 * skutek edycji configu, a nie stan, w którym wolno zostawić
                 * serwer bez śladu w logu.
                 */
                if (!content.isKnownPhase(state.phaseId())) {
                    String fallback = content.chapters().getFirst().id();
                    plugin.getLogger().severe("OneBlock: wyspa " + state.islandId()
                            + " ma nieznaną fazę '" + state.phaseId() + "' (usunięta z oneblock.yml?);"
                            + " klampuję na '" + fallback + "'. Sprawdź `phases[].id`.");
                    state.setPhaseId(fallback);
                }
                byLocation.put(state.locationKey(), state);
                byIsland.put(state.islandId(), state);
            }
            initState = InitState.READY;
            initFuture.complete(null);
            plugin.getLogger().info("Loaded " + list.size() + " OneBlock island(s)");
        }).exceptionally(err -> {
            if (attempt >= MAX_LOAD_ATTEMPTS) {
                initState = InitState.FAILED;
                plugin.getLogger().log(Level.SEVERE, "Failed to load OneBlock states from database"
                        + " after " + attempt + " attempt(s)", err);
                initFuture.completeExceptionally(err);
                return null;
            }
            plugin.getLogger().log(Level.WARNING, "OneBlock state load attempt " + attempt
                    + " failed; retrying in " + loadRetryDelay.toSeconds() + "s", err);
            CompletableFuture.runAsync(() -> attemptLoad(attempt + 1),
                    CompletableFuture.delayedExecutor(
                            loadRetryDelay.toMillis(), TimeUnit.MILLISECONDS));
            return null;
        });
    }

    public boolean isInitialized() {
        return initState == InitState.READY;
    }

    public void registerOneBlock(@NotNull UUID islandId, @NotNull String worldName,
                                 int x, int y, int z) {
        /*
         * SKY-1: idempotentnie — wyspa już zarejestrowana oznacza "nie ruszaj".
         * Rejestracja biega z dwóch miejsc (coordinator po paste + detektor
         * IslandFluidInitializer po załadowaniu chunków) i drugi przebieg
         * zerowałby progress/total_mined świeżym stanem.
         */
        if (byIsland.containsKey(islandId)) {
            return;
        }
        OneBlockState state = new OneBlockState(islandId, worldName, x, y, z,
                content.phaseIds().getFirst(), 0, 0, 0);
        byLocation.put(state.locationKey(), state);
        byIsland.put(islandId, state);
        persist(state);
    }

    public void unregisterOneBlock(@NotNull UUID islandId) {
        OneBlockState removed = byIsland.remove(islandId);
        if (removed != null) {
            byLocation.remove(removed.locationKey());
            repairCooldowns.remove(islandId);
            // Deliberately no persist() here. The row is about to be deleted, and a
            // dirty-state save races the delete: if it lands second it resurrects the
            // row as an orphan pointing at an island that no longer exists.
            removed.clearDirty();
            milestoneDao.deleteByIsland(islandId).exceptionally(err -> {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to delete OneBlock milestones for island " + islandId, err);
                return null;
            });
            dao.deleteByIsland(islandId).exceptionally(err -> {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to delete OneBlock state for island " + islandId, err);
                return null;
            });
        }
    }

    public @NotNull Optional<OneBlockState> findByLocation(@NotNull String world, int x, int y, int z) {
        return Optional.ofNullable(byLocation.get(new OneBlockState.LocationKey(world, x, y, z)));
    }

    public boolean isOneBlockLocation(@NotNull String world, int x, int y, int z) {
        return byLocation.containsKey(new OneBlockState.LocationKey(world, x, y, z));
    }

    public @NotNull Optional<OneBlockState> findByIsland(@NotNull UUID islandId) {
        return Optional.ofNullable(byIsland.get(islandId));
    }

    /** Saves every dirty state — onDisable and island unregistration (spec §6.2). */
    public void flush() {
        for (OneBlockState state : byIsland.values()) {
            if (state.isDirty()) {
                persist(state);
            }
        }
    }

    /**
     * /wyspa oneblock napraw — re-places the platform block.
     * Non-admins must wait out a 30s cooldown and cannot overwrite an existing block.
     */
    public @NotNull RepairResult repairOneBlock(@NotNull UUID islandId, boolean isAdmin) {
        OneBlockState state = byIsland.get(islandId);
        if (state == null) {
            return RepairResult.NOT_ONEBLOCK;
        }
        World world = Bukkit.getWorld(state.worldName());
        if (world == null) {
            return RepairResult.WORLD_UNLOADED;
        }
        long now = System.currentTimeMillis();
        if (!isAdmin) {
            Long lastRepair = repairCooldowns.get(islandId);
            if (lastRepair != null && (now - lastRepair) < 30_000L) {
                return RepairResult.COOLDOWN;
            }
            Block block = world.getBlockAt(state.x(), state.y(), state.z());
            if (!block.getType().isAir()) {
                return RepairResult.NOT_AIR;
            }
        }
        repairCooldowns.put(islandId, now);
        placeNextBlock(state, state.progress() + 1);
        return RepairResult.SUCCESS;
    }

    /** /wyspa oneblock napraw — legacy / admin direct override. */
    public boolean replaceOneBlockBlock(@NotNull UUID islandId) {
        return repairOneBlock(islandId, true) == RepairResult.SUCCESS;
    }

    /** /wyspa oneblock ustaw — admin override, persists immediately. */
    public boolean setProgress(@NotNull UUID islandId, @NotNull String phaseId, int progress) {
        OneBlockState state = byIsland.get(islandId);
        if (state == null || content.phaseIds().stream().noneMatch(phaseId::equals)
                || progress < 0) {
            return false;
        }
        state.setPhaseId(phaseId);
        state.resetProgress();
        for (int i = 0; i < progress; i++) {
            state.incrementProgress();
        }
        persist(state);
        return true;
    }

    public record IslandInfo(@NotNull String phaseId, int progress, int dryStreak,
                             int unclaimedMilestones) {}

    /**
     * @return empty when the island is not a OneBlock island. The previous version
     *         invented {@code IslandInfo(firstChapter, 0, 0, 0)} for those, so a
     *         classic island reported a OneBlock progress it never had.
     *         <p>Asynchronous on purpose: the unclaimed-milestone count comes from
     *         SQL, and spec §7.3 forbids blocking a region thread on the database —
     *         the previous {@code join()} did exactly that on the command thread.
     */
    public @NotNull CompletableFuture<Optional<IslandInfo>> info(@NotNull UUID islandId) {
        OneBlockState state = byIsland.get(islandId);
        if (state == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return milestoneDao.listUnclaimed(islandId).thenApply(rows -> Optional.of(new IslandInfo(
                state.phaseId(), state.progress(), state.dryStreak(), rows.size())));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        OneBlockState state = byLocation.get(new OneBlockState.LocationKey(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
        if (state == null) {
            return;
        }

        /*
         * ONEBLOCK-2: strażnik ładowania stoi ZA lookupem, nie przed nim.
         * Przed lookupem anulował każdy BlockBreakEvent na całym serwerze —
         * także w hubie i na wyspach klasycznych — więc jedna wywrotka
         * dao.loadAll() zamrażała kopanie wszystkim aż do restartu.
         * Fail-closed ma dotyczyć wyłącznie bloków OneBlocka.
         */
        if (initState == InitState.LOADING) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(miniMessage.deserialize(
                    "<red>Serwer wczytuje dane OneBlock. Spróbuj za chwilę.</red>"));
            return;
        }
        if (initState == InitState.FAILED) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(miniMessage.deserialize(
                    "<red>Błąd bazy danych OneBlock. Zgłoś to administracji.</red>"));
            return;
        }

        Player player = event.getPlayer();

        // The platform must never be air. BlockBreakEvent fires before the server
        // removes the block, so replacing it inside the handler would be overwritten
        // and the player would drop into the void. Cancel the break and reproduce it:
        // capture what vanilla would have given first, because getDrops() reads the
        // block that is about to be replaced.
        ItemStack tool = player.getInventory().getItemInMainHand();
        List<ItemStack> drops = event.isDropItems()
                ? List.copyOf(block.getDrops(tool, player))
                : List.of();
        int experience = event.getExpToDrop();
        event.setCancelled(true);

        // Krok 1-2: stan znaleziony, liczniki rosną. Drop/XP pozostaje waniliowy (D1).
        int progress = state.incrementProgress();
        state.incrementTotalMined();
        Material brokenType = block.getType();
        BreakObserver observer = breakObserver;
        if (observer != null) {
            try {
                observer.onBreak(state.islandId(), player.getUniqueId(), brokenType);
            } catch (RuntimeException questFailure) {
                plugin.getLogger().log(Level.WARNING,
                        "Zliczenie zadania OneBlock nie udalo sie dla wyspy " + state.islandId(),
                        questFailure);
            }
        }

        OneBlockContent.Chapter chapter = content.chapter(state.phaseId());

        // Krok 3: bonus z pity.
        rollBonus(chapter, state, player, block);

        // Krok 4: milestone — loot losowany raz i zamrożony (D7).
        OneBlockContent.Milestone milestone = chapter.milestones().get(progress);
        if (milestone != null) {
            String loot = OneBlockLoot.encode(OneBlockContent.rollMilestoneLoot(
                    milestone, ThreadLocalRandom.current()));
            // Announce only once the row is really there: DO NOTHING makes a repeat
            // hit a no-op, and a crash rewinding progress to the last checkpoint can
            // send the counter past the same milestone twice.
            milestoneDao.insertIfAbsent(state.islandId(), chapter.id() + ":" + progress,
                    loot, System.currentTimeMillis()).thenAccept(inserted -> {
                if (inserted == 1) {
                    player.getScheduler().run(plugin,
                            task -> notifyMilestone(player, milestone), null);
                }
            }).exceptionally(err -> {
                plugin.getLogger().log(Level.WARNING,
                        "Failed to persist OneBlock milestone for island " + state.islandId(), err);
                return null;
            });
        }

        // Krok 5: awans fazy.
        boolean advanced = false;
        if (chapter.blocksRequired() > 0 && progress >= chapter.blocksRequired()
                && content.hasNext(chapter.id())) {
            String nextId = content.nextChapterId(chapter.id());
            state.setPhaseId(nextId);
            state.resetProgress();
            progress = 0;
            advanced = true;
            announcePhase(player, content.chapter(nextId), block);
        }

        // Krok 6: następny blok z rozdziału PO ewentualnym awansie; potem moby.
        OneBlockContent.Chapter active = advanced ? content.chapter(state.phaseId()) : chapter;
        placeNextBlockMaterial(block, active, progress + 1);
        payOutBreak(block, player, tool, drops, experience);
        rollMobs(active, block, player);
        showProgress(player, active, progress);

        // Krok 7: checkpoint (§6.2) — awans i milestone zapisują się natychmiast.
        if (advanced || milestone != null || progress % content.settings().checkpointEvery() == 0) {
            persist(state);
        } else {
            state.markDirty();
        }
    }

    /**
     * Hands over what the cancelled break would have produced. {@code getDrops} already
     * honoured the tool, so a chapter that turns to stone still forces a pickaxe (D1) —
     * bare hands simply return nothing. The tool takes a point of durability so OneBlock
     * does not become a source of tools that never wear out.
     *
     * <p>Yield goes straight to the player rather than onto the ground. The platform is
     * a single block over the void, so anything that lands loose is one mistimed step
     * from being gone; the same reasoning covers experience, which would otherwise drift
     * off the edge as orbs. Only what will not fit is dropped, matching how the themed
     * bonus and the milestone rewards already behave.
     */
    private void payOutBreak(@NotNull Block block, @NotNull Player player,
                             @NotNull ItemStack tool, @NotNull List<ItemStack> drops,
                             int experience) {
        if (!drops.isEmpty()) {
            Location at = block.getLocation().add(0.5, 1.0, 0.5);
            player.getInventory().addItem(drops.toArray(ItemStack[]::new)).values()
                    .forEach(overflow -> block.getWorld().dropItemNaturally(at, overflow));
        }
        if (experience > 0) {
            player.giveExp(experience);
        }
        if (!tool.isEmpty()) {
            // Returns a new stack (empty when the tool broke) and handles Unbreaking,
            // the break sound and PlayerItemBreakEvent on the way.
            player.getInventory().setItemInMainHand(tool.damage(1, player));
        }
    }

    private void rollBonus(@NotNull OneBlockContent.Chapter chapter, @NotNull OneBlockState state,
                           @NotNull Player player, @NotNull Block block) {
        OneBlockContent.Bonus bonus = chapter.bonus();
        boolean forced = state.dryStreak() >= bonus.pity();
        if (!forced && ThreadLocalRandom.current().nextDouble(100.0) >= bonus.chancePercent()) {
            state.incrementDryStreak();
            return;
        }
        state.resetDryStreak();
        if (customItems == null) {
            return;
        }
        customItems.create(bonus.itemId()).ifPresent(stack -> {
            var overflow = player.getInventory().addItem(stack);
            overflow.values().forEach(rest ->
                    player.getWorld().dropItemNaturally(block.getLocation().add(0.5, 1.0, 0.5), rest));
        });
    }

    private void notifyMilestone(@NotNull Player player, @NotNull OneBlockContent.Milestone milestone) {
        Component message = miniMessage.deserialize(
                        "<gold>★</gold> <yellow>Osiągnięto kamień milowy: " + milestone.name()
                                + "</yellow> <gray>kliknij, aby odebrać:</gray> <gold><underlined>Odbierz nagrodę</underlined></gold>")
                .clickEvent(ClickEvent.runCommand("/wyspa oneblock nagrody"));
        player.sendMessage(message);
        // Adventure sound (pure data) instead of org.bukkit.Sound: the Bukkit
        // interface resolves every constant through the sound registry at class
        // init, which a plain unit-test JVM cannot do.
        player.playSound(Sound.sound(Key.key("entity.player.levelup"),
                Sound.Source.MASTER, 1.0F, 1.4F));
    }

    private void announcePhase(@NotNull Player player, @NotNull OneBlockContent.Chapter next,
                               @NotNull Block block) {
        player.showTitle(Title.title(
                miniMessage.deserialize("<gold><bold>NOWY ROZDZIAŁ ODBLOKOWANY!</bold></gold>"),
                miniMessage.deserialize(next.name()),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
        player.playSound(Sound.sound(Key.key("ui.toast.challenge_complete"),
                Sound.Source.MASTER, 1.0F, 1.0F));
        player.getWorld().spawnParticle(Particle.FIREWORK,
                block.getLocation().add(0.5, 1.0, 0.5), 30, 0.5, 0.5, 0.5, 0.05);
        player.sendMessage(miniMessage.deserialize(
                "<gold>★</gold> <green>Twoja wyspa OneBlock wchodzi w rozdział " + next.name()
                        + " <dark_gray>(" + next.subtitle() + ")</dark_gray></green>"));
        applyPhaseBiome(block, next.biome());
        // Broadcast na globalnym wątku (wzorzec jak announcePayout w Kopaczu):
        // jesteśmy na wątku encji gracza, a czat serwerowy to sprawa globalna.
        org.bukkit.Server server = plugin.getServer();
        if (server != null && content.settings().broadcastPhases()) {
            Component broadcast = miniMessage.deserialize(
                    "<gold>★</gold> <green>" + player.getName()
                            + " <gray>wchodzi wyspą OneBlock w rozdział " + next.name() + "</gray>");
            server.getGlobalRegionScheduler().run(plugin, task -> server.broadcast(broadcast));
        }
    }

    /**
     * Pas biomu nad wyspą (3×3 kolumny, od bloku w górę) — niebo, mgła i pogoda
     * liczą się od biomu nad pozycją gracza (referencja: AOneBlock setBiome per faza).
     * Wywoływane tylko przy zmianie rozdziału (raz na 250+ kopnięć), więc szeroki
     * zasięg jest tańszy niż efekt wizualny bez wartości.
     */
    private void applyPhaseBiome(@NotNull Block block, @NotNull String biomeName) {
        if (biomeName.isBlank()) {
            return;
        }
        Biome biome = Biome.valueOf(biomeName); // zwalidowane przy parsowaniu configu
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= BIOME_CEILING; dy++) {
                    block.getWorld().setBiome(x + dx, y + dy, z + dz, biome);
                }
            }
        }
    }

    /** Pasek postępu w action barze (text z MiniMessage; rozdziały 0-blokowe bez paska). */
    static @NotNull String progressText(@NotNull OneBlockContent.Chapter chapter, int progress) {
        return "<gray>⛏ " + chapter.name() + " <dark_gray>" + progress + "/"
                + chapter.blocksRequired() + "</dark_gray>";
    }

    private void showProgress(@NotNull Player player, @NotNull OneBlockContent.Chapter chapter,
                              int progress) {
        if (chapter.blocksRequired() <= 0) {
            return;
        }
        player.sendActionBar(miniMessage.deserialize(progressText(chapter, progress)));
    }

    /** Guaranteed[counter] bypasses the roll (spec §4); counter is the upcoming break number. */
    static @NotNull Material nextBlockMaterial(@NotNull OneBlockContent.Chapter chapter,
                                               int nextCounter, @NotNull java.util.Random random) {
        Material guaranteed = chapter.guaranteed().get(nextCounter);
        return guaranteed != null ? guaranteed : OneBlockContent.pickBlock(chapter, random);
    }

    /**
     * Places the platform block from outside the break handler (the {@code napraw}
     * command). The caller may stand anywhere, so the island block usually belongs
     * to a different region than the command thread — the write has to be handed to
     * the region that owns it. An unloaded world simply means nothing to repair.
     *
     * @return {@code false} when the world is not loaded
     */
    private boolean placeNextBlock(@NotNull OneBlockState state, int nextCounter) {
        World world = Bukkit.getWorld(state.worldName());
        if (world == null) {
            return false;
        }
        OneBlockContent.Chapter chapter = content.chapter(state.phaseId());
        Material material = nextBlockMaterial(chapter, nextCounter, ThreadLocalRandom.current());
        Location target = new Location(world, state.x(), state.y(), state.z());
        Bukkit.getRegionScheduler().run(plugin, target,
                task -> world.getBlockAt(state.x(), state.y(), state.z()).setType(material, false));
        return true;
    }

    private void placeNextBlockMaterial(@NotNull Block block,
                                        @NotNull OneBlockContent.Chapter chapter, int nextCounter) {
        block.setType(nextBlockMaterial(chapter, nextCounter, ThreadLocalRandom.current()), false);
    }

    /** Warning first, spawn after mob-warning-ticks, skip when space is blocked (spec §6.3). */
    private void rollMobs(@NotNull OneBlockContent.Chapter chapter, @NotNull Block block,
                          @NotNull Player player) {
        OneBlockContent.Mobs mobs = chapter.mobs();
        if (mobs.table().isEmpty()
                || ThreadLocalRandom.current().nextDouble(100.0) >= mobs.spawnChancePercent()) {
            return;
        }
        EntityType type = OneBlockContent.pickMob(mobs, ThreadLocalRandom.current());
        if (type == null) {
            return;
        }
        player.playSound(Sound.sound(Key.key("entity.elder_guardian.curse"),
                Sound.Source.MASTER, 0.6F, 1.6F));
        player.sendMessage(miniMessage.deserialize("<red>⚠ Coś zbliża się do bloku…</red>"));
        Location spawnLocation = block.getLocation().add(0.5, 1.0, 0.5);
        Bukkit.getRegionScheduler().runDelayed(plugin, spawnLocation, task -> {
            Block feet = spawnLocation.getBlock();
            Block head = feet.getRelative(BlockFace.UP);
            if (feet.getType().isAir() && head.getType().isAir()) {
                spawnLocation.getWorld().spawn(spawnLocation, type.getEntityClass());
            }
        }, content.settings().mobWarningTicks());
    }

    private void persist(@NotNull OneBlockState state) {
        state.clearDirty();
        dao.save(state).exceptionally(err -> {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to persist OneBlock progress for island " + state.islandId(), err);
            return null;
        });
    }
}
