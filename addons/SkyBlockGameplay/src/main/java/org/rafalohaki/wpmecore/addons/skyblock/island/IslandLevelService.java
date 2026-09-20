package org.rafalohaki.wpmecore.addons.skyblock.island;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.economy.AccountKey;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonPointDao;
import org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockTopRewardCoordinator.IslandScore;
import org.rafalohaki.wpmecore.api.service.SchedulerService;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Liczy poziom wyspy z danych, które już mamy, i pamięta go w
 * {@code wpme_sb_island_level} (migracja #10 w {@code SkyBlockSchemaMigrator}).
 * Zero Bukkita: źródła danych i powiadomienie
 * o awansie są wstrzykiwane funkcjami, więc serwis testuje się na SQLite w pamięci.
 *
 * <p>Zapisany poziom nigdy nie spada (wypłata z banku nie „degraduje” wyspy);
 * xp w cache jest zawsze bieżące. Nagroda za awans idzie do banku wyspy przez
 * {@link LedgerService#deposit} z idempotentnym {@code txId island-level:<wyspa>:L<n>}
 * — po awarii między wpłatą a zapisem wiersza ponowna próba nie płaci drugi raz.
 */
public final class IslandLevelService {

    /** Stan wyspy w cache: zapisany poziom, bieżące xp i składniki. */
    public record Snapshot(int level, long xp, @NotNull IslandLevel.Breakdown breakdown,
                           long computedAt) {
    }

    /** Awans: nowy poziom i suma monet wpłaconych do banku (za wszystkie przeskoczone poziomy). */
    public record LevelUp(@NotNull UUID islandId, int level, long reward) {
    }

    private final Logger log;
    private final SqlService sql;
    private final LedgerService ledger;
    private final IslandLevel.Settings settings;
    private final Supplier<CompletableFuture<List<IslandScore>>> ranking;
    private final ToIntFunction<UUID> minionCount;
    private final ToIntFunction<UUID> chapterIndex;
    private final Supplier<CompletableFuture<List<SeasonPointDao.TopRow>>> seasonTop;
    private final Function<UUID, Optional<UUID>> islandOfPlayer;
    private final Consumer<LevelUp> onLevelUp;

    private final Map<UUID, Snapshot> cache = new ConcurrentHashMap<>();
    // ponytail: jedno przeliczenie naraz; nakładające się żądania dostają bieżący cache.
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile @Nullable ScheduledTask task;

    public IslandLevelService(@NotNull Logger log,
                              @NotNull SqlService sql,
                              @NotNull LedgerService ledger,
                              @NotNull IslandLevel.Settings settings,
                              @NotNull Supplier<CompletableFuture<List<IslandScore>>> ranking,
                              @NotNull ToIntFunction<UUID> minionCount,
                              @NotNull ToIntFunction<UUID> chapterIndex,
                              @NotNull Supplier<CompletableFuture<List<SeasonPointDao.TopRow>>> seasonTop,
                              @NotNull Function<UUID, Optional<UUID>> islandOfPlayer,
                              @NotNull Consumer<LevelUp> onLevelUp) {
        this.log = log;
        this.sql = sql;
        this.ledger = ledger;
        this.settings = settings;
        this.ranking = ranking;
        this.minionCount = minionCount;
        this.chapterIndex = chapterIndex;
        this.seasonTop = seasonTop;
        this.islandOfPlayer = islandOfPlayer;
        this.onLevelUp = onLevelUp;
    }

    public @NotNull IslandLevel.Settings settings() {
        return settings;
    }

    /**
     * Pętla co {@code refresh-seconds} dla wysp graczy online. {@code onlineIslands}
     * woła się na wątku globalnym (tam wolno czytać listę graczy i cache Skyllii),
     * samo liczenie idzie na pulę SQL.
     */
    public synchronized void start(@NotNull SchedulerService scheduler,
                                   @NotNull Supplier<Collection<UUID>> onlineIslands) {
        stop();
        Duration period = Duration.ofSeconds(settings.refreshSeconds());
        task = scheduler.globalRepeating(Duration.ofSeconds(5), period,
                () -> recompute(onlineIslands.get()));
    }

    public synchronized void stop() {
        ScheduledTask current = task;
        task = null;
        if (current != null) {
            current.cancel();
        }
    }

    public @NotNull Optional<Snapshot> cached(@NotNull UUID islandId) {
        return Optional.ofNullable(cache.get(islandId));
    }

    /** Na żądanie (menu): przelicza jedną wyspę i zwraca jej świeży stan. */
    public @NotNull CompletableFuture<Optional<Snapshot>> refresh(@NotNull UUID islandId) {
        return recompute(Set.of(islandId)).thenApply(ignored -> cached(islandId));
    }

    /**
     * Przelicza podane wyspy. Cztery źródła: ranking (bank + zadania), minionki,
     * rozdział OneBlocka, punkty sezonowe członków (TOP-200 → wyspa gracza).
     * Nigdy nie rzuca — błąd loguje i zostawia poprzedni cache.
     */
    public @NotNull CompletableFuture<Void> recompute(@NotNull Collection<UUID> islands) {
        Set<UUID> targets = new HashSet<>(islands);
        if (targets.isEmpty() || !running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return recomputeLocked(targets);
        } catch (RuntimeException failure) {
            running.set(false);
            log.log(Level.WARNING, "Nie udało się uruchomić przeliczenia poziomu wysp " + targets, failure);
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Void> recomputeLocked(Set<UUID> targets) {
        CompletableFuture<Map<UUID, Long>> scores = ranking.get().thenApply(rows -> {
            Map<UUID, Long> byIsland = new HashMap<>();
            for (IslandScore row : rows) {
                byIsland.put(row.islandId(), row.totalScore());
            }
            return byIsland;
        });
        // ponytail: do 200 wywołań islandOf (cache TTL nad SkylliaAPI) co cykl;
        // gdy zacznie boleć — trzymać mapę gracz→wyspa z membersOf zamiast pytać per wiersz.
        CompletableFuture<Map<UUID, Long>> seasonByIsland = seasonTop.get().thenApply(rows -> {
            Map<UUID, Long> byIsland = new HashMap<>();
            for (SeasonPointDao.TopRow row : rows) {
                islandOfPlayer.apply(row.playerId()).ifPresent(island ->
                        byIsland.merge(island, row.points(), Long::sum));
            }
            return byIsland;
        });
        CompletableFuture<Map<UUID, int[]>> stored = sql.query(
                "SELECT island_id, level FROM wpme_sb_island_level",
                rs -> Map.entry(rs.getString(1), rs.getInt(2))).thenApply(rows -> {
            Map<UUID, int[]> byIsland = new HashMap<>();
            for (Map.Entry<String, Integer> row : rows) {
                try {
                    byIsland.put(UUID.fromString(row.getKey()), new int[]{row.getValue()});
                } catch (IllegalArgumentException ignored) {
                    // obcy wiersz — nie nasz problem
                }
            }
            return byIsland;
        });

        return scores.thenCombine(seasonByIsland, Map::entry)
                .thenCombine(stored, (pair, levels) -> {
                    CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                    for (UUID islandId : targets) {
                        IslandLevel.Breakdown breakdown = IslandLevel.breakdown(settings,
                                pair.getKey().getOrDefault(islandId, 0L),
                                minionCount.applyAsInt(islandId),
                                chapterIndex.applyAsInt(islandId),
                                pair.getValue().getOrDefault(islandId, 0L));
                        // Pierwsze przeliczenie wyspy = punkt odniesienia bez nagród: istniejąca, bogata
                        // wyspa nie dostaje wypłaty za poziomy 1..N naraz (rollout 2026-09-06). Nagrody
                        // idą tylko za awanse względem zapisanego poziomu.
                        boolean firstSeen = !levels.containsKey(islandId);
                        int storedLevel = firstSeen
                                ? IslandLevel.levelFor(settings, breakdown.xp())
                                : levels.get(islandId)[0];
                        chain = chain.thenCompose(ignored -> settle(islandId, breakdown, storedLevel));
                    }
                    return chain;
                })
                .thenCompose(Function.identity())
                .whenComplete((ignored, failure) -> {
                    running.set(false);
                    if (failure != null) {
                        log.log(Level.WARNING, "Nie udało się przeliczyć poziomu wysp " + targets, failure);
                    }
                })
                .exceptionally(failure -> null);
    }

    /** Jedna wyspa: nagroda za każdy przeskoczony poziom, zapis, cache, powiadomienie. */
    private CompletableFuture<Void> settle(UUID islandId, IslandLevel.Breakdown breakdown,
                                           int storedLevel) {
        long xp = breakdown.xp();
        int computed = IslandLevel.levelFor(settings, xp);
        int level = Math.max(storedLevel, computed);
        CompletableFuture<Long> rewards = CompletableFuture.completedFuture(0L);
        for (int next = storedLevel + 1; next <= computed; next++) {
            int target = next;
            long amount = settings.rewardFor(target);
            rewards = rewards.thenCompose(sum -> amount <= 0L
                    ? CompletableFuture.completedFuture(sum)
                    : ledger.deposit(AccountKey.island(islandId), amount,
                            "island-level:" + islandId + ":L" + target, "island_level")
                            .thenApply(mutation -> mutation.applied() ? sum + amount : sum));
        }
        return rewards.thenCompose(paid -> upsert(islandId, level, xp).thenApply(ignored -> paid))
                .thenAccept(paid -> {
                    cache.put(islandId, new Snapshot(level, xp, breakdown, System.currentTimeMillis()));
                    if (level > storedLevel) {
                        onLevelUp.accept(new LevelUp(islandId, level, paid));
                    }
                });
    }

    private CompletableFuture<Void> upsert(UUID islandId, int level, long xp) {
        long now = System.currentTimeMillis();
        String id = islandId.toString();
        return sql.update("UPDATE wpme_sb_island_level SET level = ?, xp = ?, updated_at = ? WHERE island_id = ?",
                        level, xp, now, id)
                .thenCompose(updated -> updated > 0
                        ? CompletableFuture.completedFuture(null)
                        : sql.update("INSERT INTO wpme_sb_island_level (island_id, level, xp, updated_at) VALUES (?, ?, ?, ?)",
                                id, level, xp, now).thenApply(ignored -> null));
    }
}
