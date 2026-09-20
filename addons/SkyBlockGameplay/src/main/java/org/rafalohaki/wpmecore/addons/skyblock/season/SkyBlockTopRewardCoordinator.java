package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.rafalohaki.wpmecore.addons.skyblock.economy.CompactBalanceFormatter;
import org.rafalohaki.wpmecore.addons.skyblock.SkyBlockSettings;
import org.rafalohaki.wpmecore.addons.skyblock.economy.ConvergenceRetryPolicy;
import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;


import org.rafalohaki.wpmecore.addons.skyblock.shared.SkyBlockServices;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandTitleService;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates seasonal ranking calculations, season transitions, and
 * delivers seasonal rewards (Diamond Lotus and coins) for TOP 3 islands.
 */
public final class SkyBlockTopRewardCoordinator {

    public static final NamespacedKey KEY_CUSTOM_ITEM_ID = new NamespacedKey("customitems", "item_id");
    public static final String DIAMOND_LOTUS_ID = "skyblock:token/diamond_lotus";
    public static final long QUEST_SCORE_WEIGHT = 500L;

    public record IslandScore(
            @NotNull UUID islandId,
            long bankBalance,
            int completedQuests,
            long totalScore,
            int rank
    ) {}

    public record SeasonalClaim(
            int seasonId,
            @NotNull UUID playerId,
            @NotNull UUID islandId,
            int rankPosition,
            long claimedAt
    ) {}

    /**
     * Kosmetyka sezonowa. Wstrzykiwana setterem, bo koordynator powstaje wcześniej
     * niż katalog kolekcji, a nagroda działa poprawnie także bez niej.
     */
    private volatile CosmeticService cosmetics;
    /**
     * Trwałe wydawanie nagród. Wstrzykiwane setterem, bo koordynator powstaje
     * wcześniej niż outbox; bez niego nagroda schodzi ścieżką awaryjną.
     */
    private volatile InventoryOutbox outbox;
    /**
     * Warunki odbioru nagrody. Zamknięte, dopóki konfiguracja nie powie inaczej —
     * pomyłka po tej stronie kosztuje limitowaną kosmetykę całego sezonu.
     */
    private volatile SeasonEligibility eligibility = SeasonEligibility.CLOSED;

    /**
     * Tytuły wyspy za dokładne miejsce w rankingu (sekcja
     * {@code season.title-rewards}). Volatile + kopia niemutowalna, tak jak
     * {@code top-rewards}: setter może nadpisać wartość już po konstrukcji.
     */
    private volatile Map<Integer, String> titleRewards = Map.of();
    /**
     * Trwały magazyn tytułów wyspy (migracja #13). Wstrzykiwany setterem, bo
     * koordynator powstaje wcześniej niż magazyn; bez niego nagroda sezonowa
     * działa jak dotąd, tylko nie nadaje tytułu.
     */
    private volatile IslandTitleService islandTitles;

    public void setTitleRewards(@NotNull Map<Integer, String> titleRewards) {
        this.titleRewards = Map.copyOf(titleRewards);
    }

    public void setIslandTitles(@Nullable IslandTitleService islandTitles) {
        this.islandTitles = islandTitles;
    }

    public void setSeasonEligibility(@NotNull SeasonEligibility eligibility) {
        this.eligibility = eligibility;
    }

    public void setInventoryOutbox(@org.jetbrains.annotations.Nullable InventoryOutbox outbox) {
        this.outbox = outbox;
    }

    public void setCosmeticService(@org.jetbrains.annotations.Nullable CosmeticService cosmetics) {
        this.cosmetics = cosmetics;
    }

    /**
     * Nagrody TOP 3 pochodzą z sekcji {@code season.top-rewards} w config.yml
     * ({@link SkyBlockSettings#loadTopRewards}); trafiają do koordynatora przez
     * konstruktor. Poniższe wartości to fallback legacy — identyczne liczby,
     * które działały, zanim nagrody trafiły do konfiguracji.
     */
    public static final List<SkyBlockSettings.TopReward> DEFAULT_TOP_REWARDS = List.of(
            new SkyBlockSettings.TopReward(1, 3, 50_000L),
            new SkyBlockSettings.TopReward(2, 2, 30_000L),
            new SkyBlockSettings.TopReward(3, 1, 15_000L),
            // P2-4: próg sezonu — wirtualne 4. miejsce dla wysp poza podium z wynikiem ≥ threshold-score.
            new SkyBlockSettings.TopReward(4, 1, 5_000L)); // 4 = THRESHOLD_RANK (stała zdefiniowana niżej — forward reference)

    /**
     * Nagroda przewidziana dla danego miejsca w rankingu albo pusta, gdy
     * konfiguracja jej nie mapuje (ścieżka odmowy „brak nagrody dla rangi”).
     */
    public @NotNull Optional<SkyBlockSettings.TopReward> forRank(int rank) {
        return Optional.ofNullable(rewardForRank(this.topRewards, rank));
    }

    /**
     * Czyste wyszukiwanie nagrody po randze — celowo statyczne i bez stanu,
     * żeby dało się testować bez MockBukkit. Pierwszy wpis o pasującym randze
     * wygrywa (duplikaty są odrzucane już na poziomie parsera konfiguracji).
     */
    static @Nullable SkyBlockSettings.TopReward rewardForRank(
            @NotNull List<SkyBlockSettings.TopReward> rewards, int rank) {
        for (SkyBlockSettings.TopReward reward : rewards) {
            if (reward.rank() == rank) {
                return reward;
            }
        }
        return null;
    }

    public record ClaimResult(
            @NotNull ClaimStatus status,
            int rankPosition,
            int diamondLotusGiven,
            long coinsGiven,
            @NotNull String message
    ) {}

    public enum ClaimStatus {
        SUCCESS,
        ALREADY_CLAIMED,
        NOT_IN_TOP,
        NO_ISLAND,
        SEASON_OPEN,
        RANKING_TOO_SMALL,
        ERROR
    }

    /**
     * Warunki dopuszczenia do nagrody sezonowej.
     *
     * <p>Domyślnie <b>zamknięte</b>. Sam warunek „miejsce w TOP 3" nie wystarcza:
     * dopóki na serwerze jest mniej wysp niż miejsc na podium, każda wyspa jest
     * w TOP 3 z wynikiem zero i limitowana kosmetyka sezonu wychodzi pierwszego
     * dnia. Próg liczy wyspy <i>z dorobkiem</i>, bo puste konto łatwo założyć.
     *
     * @param claimsOpen       czy operator otworzył okno odbioru (koniec sezonu)
     * @param minScore         najmniejszy dorobek, który w ogóle jest wynikiem
     * @param minRankedIslands ile wysp z dorobkiem musi być, by podium coś znaczyło
     */
    /** P2-4: wyspa poza podium z wynikiem ≥ thresholdScore odbiera nagrodę „progu” (rank {@link #THRESHOLD_RANK}). */
    public static final int THRESHOLD_RANK = 4;

    public record SeasonEligibility(boolean claimsOpen, long minScore, int minRankedIslands, long thresholdScore) {
        public static final SeasonEligibility CLOSED = new SeasonEligibility(false, 1L, 5, 0L);

        /** Zgodność wsteczna: bez progu (0 = próg wyłączony, tylko podium). */
        public SeasonEligibility(boolean claimsOpen, long minScore, int minRankedIslands) {
            this(claimsOpen, minScore, minRankedIslands, 0L);
        }

        public boolean meetsThreshold(long totalScore) {
            return thresholdScore > 0L && totalScore >= thresholdScore;
        }
    }

    private static @NotNull String refusalMessage(@NotNull ClaimStatus refusal, int rank) {
        return switch (refusal) {
            case SEASON_OPEN -> "Sezon jeszcze trwa. Nagrody wydajemy dopiero po jego zamknięciu.";
            case RANKING_TOO_SMALL ->
                    "Ranking sezonu jest jeszcze zbyt ubogi, żeby wyłonić podium.";
            default -> rank > 0
                    ? "Twoja wyspa zajmuje miejsce #" + rank + ". Nagrody dostaje podium (TOP 3)"
                            + " i każda wyspa, która przekroczyła próg sezonu (patrz /sezon)."
                    : "Twoja wyspa nie posiada punktów w bieżącym rankingu sezonu.";
        };
    }

    /**
     * @return powód odmowy albo {@code null}, gdy nagroda się należy
     */
    static @Nullable ClaimStatus rejectClaim(@NotNull SeasonEligibility rules,
                                             @NotNull List<IslandScore> ranking,
                                             @Nullable IslandScore mine) {
        if (!rules.claimsOpen()) {
            return ClaimStatus.SEASON_OPEN;
        }
        if (mine == null || mine.totalScore() < rules.minScore()) {
            return ClaimStatus.NOT_IN_TOP;
        }
        // P2-4: podium (1–3) albo próg sezonu — środek stawki też ma po co grać do końca.
        if (mine.rank() > 3 && !rules.meetsThreshold(mine.totalScore())) {
            return ClaimStatus.NOT_IN_TOP;
        }
        long contenders = ranking.stream()
                .filter(entry -> entry.totalScore() >= rules.minScore())
                .count();
        if (contenders < rules.minRankedIslands()) {
            return ClaimStatus.RANKING_TOO_SMALL;
        }
        return null;
    }

    private final JavaPlugin plugin;
    private final SqlService sql;
    private final LedgerService ledger;
    private final SkylliaIntegration skyllia;
    private final MiniMessage miniMessage;
    private final AtomicInteger cachedCurrentSeason = new AtomicInteger(1);
    /**
     * Nagrody TOP 3 z config.yml (season.top-rewards). Volatile + kopia
     * niemutowalna: setter może nadpisać wartość z konfiguracji już po
     * konstrukcji (wiring w SeasonModule/SkyBlockGameplay), a odczyty biegną
     * z wątków asynchronicznych.
     */
    private volatile List<SkyBlockSettings.TopReward> topRewards = DEFAULT_TOP_REWARDS;
    /** seasonId → nazwa edycji dla widoków operatora; null/brak → fallback SeasonLabels. */
    private volatile IntFunction<String> editionNameResolver;
    private @Nullable CustomItemService customItemService;

    public SkyBlockTopRewardCoordinator(@NotNull JavaPlugin plugin,
                                        @NotNull SqlService sql,
                                        @NotNull LedgerService ledger,
                                        @NotNull SkylliaIntegration skyllia,
                                        @NotNull MiniMessage miniMessage) {
        this(plugin, sql, ledger, skyllia, miniMessage, null,
                DEFAULT_TOP_REWARDS, null);
    }

    public SkyBlockTopRewardCoordinator(@NotNull JavaPlugin plugin,
                                        @NotNull SqlService sql,
                                        @NotNull LedgerService ledger,
                                        @NotNull SkylliaIntegration skyllia,
                                        @NotNull MiniMessage miniMessage,
                                        @Nullable CustomItemService customItemService) {
        this(plugin, sql, ledger, skyllia, miniMessage, customItemService,
                DEFAULT_TOP_REWARDS, null);
    }

    /**
     * Pełna forma: nagrody TOP 3 pochodzą z config.yml
     * ({@link SkyBlockSettings#loadTopRewards}), a {@code editionNameResolver}
     * mapuje wewnętrzny numer sezonu na nazwę edycji dla komunikatów
     * operatora; gdy jest nullem albo zwraca null/pustkę, używany jest
     * legacy zakres dat z sekcji {@code season}.
     */
    public SkyBlockTopRewardCoordinator(@NotNull JavaPlugin plugin,
                                        @NotNull SqlService sql,
                                        @NotNull LedgerService ledger,
                                        @NotNull SkylliaIntegration skyllia,
                                        @NotNull MiniMessage miniMessage,
                                        @Nullable CustomItemService customItemService,
                                        @NotNull List<SkyBlockSettings.TopReward> topRewards,
                                        @Nullable IntFunction<String> editionNameResolver) {
        this.plugin = plugin;
        this.sql = sql;
        this.ledger = ledger;
        this.skyllia = skyllia;
        this.miniMessage = miniMessage;
        this.customItemService = customItemService;
        setTopRewards(topRewards);
        this.editionNameResolver = editionNameResolver;
        initSeasonCache();
    }

    public void setTopRewards(@NotNull List<SkyBlockSettings.TopReward> topRewards) {
        this.topRewards = List.copyOf(topRewards);
    }

    public void setEditionNameResolver(@Nullable IntFunction<String> editionNameResolver) {
        this.editionNameResolver = editionNameResolver;
    }

    public @NotNull CompletableFuture<Integer> initSeasonCache() {
        return fetchCurrentSeasonFromDb().thenApply(season -> {
            cachedCurrentSeason.set(season);
            return season;
        }).exceptionally(err -> {
            if (plugin != null && plugin.getLogger() != null) {
                plugin.getLogger().warning("Failed to initialize seasonal state cache: " + err.getMessage());
            }
            return cachedCurrentSeason.get();
        });
    }

    public void setCustomItemService(@Nullable CustomItemService customItemService) {
        this.customItemService = customItemService;
    }

    public @Nullable CustomItemService getCustomItemService() {
        return SkyBlockServices.customItems(this.customItemService);
    }

    /**
     * Calculates current seasonal ranking based on island bank balance + quest activity.
     */
    public @NotNull CompletableFuture<List<IslandScore>> calculateRanking() {
        // Składnik „zadania" liczymy z ŻYWEGO źródła. Do 2026-09-10 sięgał on po
        // `wpme_sb_quest_progress` — tabelę, którą zapełniał wyłącznie natywny
        // DailyQuestService, a ten nie jest rejestrowany w trybie eco
        // (`SkyBlockGameplay.java:734-740`). Skutek: `WHERE rewarded = 1` nie
        // zwracało nic, ranking = sam bank wyspy, a punkty sezonowe (realna
        // waluta rywalizacji) nie wchodziły do wyniku wcale.
        //
        // Teraz „zadania" to suma ukończonych zadań sezonowych CZŁONKÓW wyspy
        // w bieżącym sezonie (`wpme_sb_season_points.quests_done`, rosnące przy
        // każdym przyznaniu punktów za zadanie). Formuła z configu bez zmian:
        // score = bank + 500 × zadania.
        int seasonId = cachedCurrentSeason.get();
        return sql.query("""
                SELECT 
                    island_accounts.account_key,
                    COALESCE(a.balance_minor, 0) AS bank_balance,
                    COALESCE(q.completed_count, 0) AS completed_quests
                FROM (
                    SELECT account_key FROM wpme_sb_accounts WHERE account_key LIKE 'island:%'
                    UNION
                    SELECT 'island:' || island_id FROM wpme_sb_island_membership
                     WHERE status = 'ACTIVE'
                ) island_accounts
                LEFT JOIN wpme_sb_accounts a ON island_accounts.account_key = a.account_key
                LEFT JOIN (
                    SELECT m.island_id AS island_key,
                           COALESCE(SUM(sp.quests_done), 0) AS completed_count
                    FROM wpme_sb_island_membership m
                    JOIN wpme_sb_season_points sp
                      ON sp.player_uuid = m.player_uuid AND sp.season_id = ?
                    WHERE m.status = 'ACTIVE'
                    GROUP BY m.island_id
                ) q ON island_accounts.account_key = 'island:' || q.island_key
                """, row -> {
            String accountKey = row.getString(1);
            long bankBalance = row.getLong(2);
            int completedQuests = row.getInt(3);
            return new ProvisionalIslandScore(accountKey, bankBalance, completedQuests);
        }, seasonId).thenApply(rawList -> {
            List<IslandScore> provisional = new ArrayList<>();
            for (ProvisionalIslandScore raw : rawList) {
                if (raw.accountKey() == null || !raw.accountKey().startsWith("island:")) {
                    continue;
                }
                String rawUuid = raw.accountKey().substring("island:".length());
                UUID islandId;
                try {
                    islandId = UUID.fromString(rawUuid);
                } catch (IllegalArgumentException invalid) {
                    continue;
                }
                long totalScore = Math.max(0L, raw.bankBalance()) + ((long) raw.completedQuests() * QUEST_SCORE_WEIGHT);
                provisional.add(new IslandScore(islandId, raw.bankBalance(), raw.completedQuests(), totalScore, 0));
            }

            provisional.sort((a, b) -> {
                int cmp = Long.compare(b.totalScore(), a.totalScore());
                if (cmp != 0) return cmp;
                cmp = Long.compare(b.bankBalance(), a.bankBalance());
                if (cmp != 0) return cmp;
                cmp = Integer.compare(b.completedQuests(), a.completedQuests());
                if (cmp != 0) return cmp;
                return a.islandId().compareTo(b.islandId());
            });

            List<IslandScore> ranked = new ArrayList<>(provisional.size());
            for (int i = 0; i < provisional.size(); i++) {
                IslandScore item = provisional.get(i);
                ranked.add(new IslandScore(item.islandId(), item.bankBalance(), item.completedQuests(), item.totalScore(), i + 1));
            }
            return ranked;
        });
    }

    private record ProvisionalIslandScore(String accountKey, long bankBalance, int completedQuests) {}

    /**
     * Determines top N islands for the current season.
     */
    public @NotNull CompletableFuture<List<IslandScore>> getTopIslands(int limit) {
        return calculateRanking().thenApply(ranking -> {
            if (ranking.size() <= limit) {
                return ranking;
            }
            return ranking.subList(0, limit);
        });
    }

    /**
     * Returns cached current season number synchronously.
     */
    public int getCachedCurrentSeason() {
        return cachedCurrentSeason.get();
    }

    /**
     * Retrieves current season number (returns memoized/cached value).
     */
    public @NotNull CompletableFuture<Integer> getCurrentSeason() {
        return CompletableFuture.completedFuture(cachedCurrentSeason.get());
    }

    /**
     * Fetches current season directly from DB.
     */
    public @NotNull CompletableFuture<Integer> fetchCurrentSeasonFromDb() {
        return sql.query("SELECT current_season FROM wpme_sb_season_state WHERE id = 1",
                row -> row.getInt(1))
                .thenCompose(rows -> {
                    if (!rows.isEmpty()) {
                        return CompletableFuture.completedFuture(rows.getFirst());
                    }
                    long now = System.currentTimeMillis();
                    return sql.update("""
                            INSERT INTO wpme_sb_season_state (id, current_season, updated_at)
                            VALUES (1, 1, ?)
                            """, now).thenApply(ignored -> 1);
                });
    }

    /**
     * Advances season to next season ID and updates cache.
     */
    public @NotNull CompletableFuture<Integer> endSeason() {
        long now = System.currentTimeMillis();
        int next = cachedCurrentSeason.get() + 1;
        // SKYBLOCK-1-9: cache dopiero po udanym UPDATE — awaria bazy nie może
        // zostawić rozjazdu (cache N+1 vs baza N) do restartu.
        return sql.update("""
                UPDATE wpme_sb_season_state
                SET current_season = ?, updated_at = ?
                WHERE id = 1
                """, next, now).thenApply(ignored -> {
                    cachedCurrentSeason.set(next);
                    return next;
                });
    }

    /**
     * Sets specific season ID and updates cache.
     */
    public @NotNull CompletableFuture<Void> setSeason(int season) {
        long now = System.currentTimeMillis();
        // SKYBLOCK-1-9: jak w endSeason — cache po potwierdzonym zapisie.
        return sql.update("""
                UPDATE wpme_sb_season_state
                SET current_season = ?, updated_at = ?
                WHERE id = 1
                """, season, now).thenApply(ignored -> {
                    cachedCurrentSeason.set(season);
                    return null;
                });
    }

    /**
     * Checks if player has claimed reward for given season.
     */
    public @NotNull CompletableFuture<Boolean> isClaimed(int seasonId, @NotNull UUID playerId) {
        return sql.query("""
                SELECT 1 FROM wpme_sb_seasonal_claims
                WHERE season_id = ? AND player_id = ?
                """, row -> true, seasonId, playerId.toString())
                .thenApply(rows -> !rows.isEmpty());
    }

    /**
     * Records seasonal claim idempotently.
     */
    public @NotNull CompletableFuture<Boolean> recordClaim(int seasonId, @NotNull UUID playerId,
                                                            @NotNull UUID islandId, int rankPosition) {
        long now = System.currentTimeMillis();
        return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () ->
                SqlSupport.insertIgnoringConstraint(connection, """
                        INSERT INTO wpme_sb_seasonal_claims
                            (season_id, player_id, island_id, rank_position, claimed_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, "ON CONFLICT (season_id, player_id) DO NOTHING",
                        statement -> {
                            statement.setInt(1, seasonId);
                            statement.setString(2, playerId.toString());
                            statement.setString(3, islandId.toString());
                            statement.setInt(4, rankPosition);
                            statement.setLong(5, now);
                        })));
    }

    /**
     * Delivers seasonal reward for player if eligible and not yet claimed.
     */
    public @NotNull CompletableFuture<ClaimResult> claimReward(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        Optional<IslandView> islandOpt = skyllia.islandOf(playerId);
        if (islandOpt.isEmpty()) {
            ClaimResult result = new ClaimResult(ClaimStatus.NO_ISLAND, 0, 0, 0,
                    "Nie posiadasz wyspy, aby odebrać nagrodę sezonową.");
            player.sendMessage(miniMessage.deserialize("<red>" + result.message() + "</red>"));
            return CompletableFuture.completedFuture(result);
        }
        UUID islandId = islandOpt.get().islandId();
        int seasonId = cachedCurrentSeason.get();

        return calculateRanking().thenCompose(ranking -> {
            IslandScore mine = ranking.stream()
                    .filter(entry -> entry.islandId().equals(islandId))
                    .findFirst()
                    .orElse(null);

            ClaimStatus refusal = rejectClaim(eligibility, ranking, mine);
            if (refusal != null) {
                int rank = mine == null ? 0 : mine.rank();
                ClaimResult result = new ClaimResult(refusal, rank, 0, 0,
                        refusalMessage(refusal, rank));
                player.sendMessage(miniMessage.deserialize("<yellow>" + result.message() + "</yellow>"));
                return CompletableFuture.completedFuture(result);
            }

            IslandScore score = mine;
            int rank = score.rank();
            /*
             * P2-4: poza podium stawka dzieli się na dwie ścieżki. Miejsce
             * z własnym wpisem w season.top-rewards (5, 10, 50…) bierze swoją
             * nagrodę; reszta ogona dostaje wpis progu (rank 4). Podium nigdy
             * nie spada na próg: brakujący wpis dla miejsca 1–3 to odmowa,
             * a nie nagroda za czwarte miejsce.
             */
            boolean exactReward = rewardForRank(topRewards, rank) != null;
            int rewardRank = exactReward || rank <= 3 ? rank : THRESHOLD_RANK;
            SkyBlockSettings.TopReward reward = rewardForRank(topRewards, rewardRank);
            if (reward == null) {
                // Ranga bez wpisu w season.top-rewards — brak nagrody (ścieżka odmowy).
                ClaimResult result = new ClaimResult(ClaimStatus.NOT_IN_TOP, rank, 0, 0,
                        "Brak nagrody dla rangi #" + rank
                                + ". Nagrody przysługują wyłącznie dla miejsc wskazanych w konfiguracji sezonu.");
                player.sendMessage(miniMessage.deserialize("<yellow>" + result.message() + "</yellow>"));
                return CompletableFuture.completedFuture(result);
            }

            /*
             * Kolejność od najtańszej awarii do najdroższej: monety i Lotosy mają
             * deterministyczne identyfikatory (idempotentne na powtórkę), więc
             * porażka w środku niczego nie przepala — wpis roszczenia powstaje
             * dopiero na końcu, gdy wypłata faktycznie doszła do skutku
             * (SKYBLOCK-1-6). Przy BUSY/awarii gracz po prostu klika ponownie.
             * Kwoty z config.yml; audyt księgowy zostaje numeryczny (seasonId).
             */
            return ledger.depositPlayer(playerId, reward.coins(),
                        "season_reward:" + seasonId + ":" + islandId, "seasonal_top_reward")
                    .thenCompose(mutation -> grantSeasonLotus(player, seasonId, islandId, reward))
                    .thenCompose(lotusOutcome -> {
                        if (lotusOutcome != InventoryOutbox.Outcome.SUCCESS
                                && lotusOutcome != InventoryOutbox.Outcome.DEFERRED) {
                            ClaimResult result = new ClaimResult(ClaimStatus.ERROR, rank, 0, 0,
                                    "Nie udało się wydać Lotosów (" + lotusOutcome
                                            + "). Spróbuj ponownie za chwilę.");
                            player.sendMessage(miniMessage.deserialize("<red>" + result.message() + "</red>"));
                            return CompletableFuture.completedFuture(result);
                        }
                        return recordClaim(seasonId, playerId, islandId, rank).thenCompose(recorded -> {
                            if (!recorded) {
                                // SKYBLOCK-1-7: claim per wyspa — ktoś z załogi już odebrał.
                                // Widok gracza: zakres dat zamiast numerka (decyzja 2026-08-25).
                                String edition = editionName(seasonId);
                                player.sendMessage(miniMessage.deserialize("<red>Twoja wyspa odebrała już nagrodę za "
                                        + (edition != null ? "edycję " + edition : "tę edycję") + "!</red>"));
                                return CompletableFuture.completedFuture(
                                        new ClaimResult(ClaimStatus.ALREADY_CLAIMED, rank, 0, 0,
                                                "Twoja wyspa odebrała już nagrodę za "
                                                        + (edition != null ? "edycję " + edition : "tę edycję") + "!"));
                            }
                            schedulePlayer(player, () -> {
                                playRewardEffects(player);
                                sendClaimSuccessMessage(player, seasonId, rank, reward);
                            });
                            if (outbox == null) {
                                // Fallback bez outboxa: dostawa dopiero po recordClaim,
                                // bo nie jest idempotentna (SKYBLOCK-1-6). Ta sama bramka
                                // co w grantSeasonLotus: `lotus: 0` = nie wydajemy nic.
                                if (reward.lotus() > 0) {
                                    deliverDirectly(player, createDiamondLotus(reward.lotus()));
                                }
                                deliverCosmetics(player, seasonId, rewardRank);
                            } else {
                                scheduleCosmetics(player, seasonId, rewardRank);
                            }
                            /*
                             * Tytuł wyspy za dokładne miejsce (season.title-rewards).
                             * Nadanie jest idempotentne po source_id, więc powtórka
                             * po restarcie nic nie psuje; awaria magazynu tytułów
                             * nie może unieważnić już wypłaconej nagrody, dlatego
                             * kończy się logiem, a nie błędem roszczenia.
                             */
                            return grantSeasonTitle(islandId, seasonId, rank).thenApply(appliedTitle -> {
                                if (appliedTitle != null) {
                                    schedulePlayer(player, () -> player.sendMessage(miniMessage.deserialize(
                                            "<gold>Nowy tytuł wyspy: <white>" + appliedTitle
                                                    + "</white> — zobaczysz go w Centrum Wyspy.</gold>")));
                                }
                                return new ClaimResult(ClaimStatus.SUCCESS, rank, reward.lotus(), reward.coins(),
                                        "Pomyślnie odebrano nagrodę sezonową!");
                            });
                        });
                    })
                    .exceptionally(err -> {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE,
                                "Seasonal reward claim failed for " + playerId
                                        + " season " + seasonId, err);
                        ClaimResult result = new ClaimResult(ClaimStatus.ERROR, rank, 0, 0,
                                "Błąd przy odbiorze nagrody sezonowej. Spróbuj ponownie.");
                        player.sendMessage(miniMessage.deserialize("<red>" + result.message() + "</red>"));
                        return result;
                    });
        });
    }

    /**
     * Shows seasonal ranking and rewards summary to command sender.
     */
    public @NotNull CompletableFuture<Void> showRankingSummary(@NotNull CommandSender sender) {
        int seasonId = cachedCurrentSeason.get();
        return calculateRanking().thenAccept(ranking -> {
            List<IslandScore> top3 = ranking.size() > 3 ? ranking.subList(0, 3) : ranking;

                    UUID playerIslandId = null;
                    Integer playerRank = null;
                    Long playerScore = null;
                    if (sender instanceof Player player) {
                        Optional<IslandView> islandOpt = skyllia.islandOf(player.getUniqueId());
                        if (islandOpt.isPresent()) {
                            playerIslandId = islandOpt.get().islandId();
                            for (IslandScore entry : ranking) {
                                if (entry.islandId().equals(playerIslandId)) {
                                    playerRank = entry.rank();
                                    playerScore = entry.totalScore();
                                    break;
                                }
                            }
                        }
                    }
                    // Widok gracza: nazwa edycji z rejestru, fallback — zakres dat (decyzja 2026-08-25).
                    String currentEdition = editionName(seasonId);
                    sender.sendMessage(miniMessage.deserialize(
                            "<gray>Aktualny Sezon: <gold><bold>"
                                    + (currentEdition != null ? currentEdition : "w trakcie") + "</bold></gold></gray>"));
                    sender.sendMessage(Component.empty());
                    sender.sendMessage(miniMessage.deserialize("<yellow><bold>🏆 TOP 3 WYSP SEZONU:</bold></yellow>"));

                    if (top3.isEmpty()) {
                        sender.sendMessage(miniMessage.deserialize("<dark_gray>  Brak wysp w rankingu.</dark_gray>"));
                    } else {
                        for (IslandScore entry : top3) {
                            String rankColor = switch (entry.rank()) {
                                case 1 -> "<gold><bold>#1</bold></gold>";
                                case 2 -> "<white><bold>#2</bold></white>";
                                case 3 -> "<color:#cd7f32><bold>#3</bold></color>";
                                default -> "<gray>#" + entry.rank() + "</gray>";
                            };
                            sender.sendMessage(miniMessage.deserialize(
                                    "  " + rankColor + " <aqua>Wyspa " + entry.islandId().toString().substring(0, 8) + "</aqua> "
                                            + "<dark_gray>•</dark_gray> Punkty: <yellow><bold>" + CompactBalanceFormatter.exact(entry.totalScore()) + "</bold></yellow> "
                                            + "<dark_gray>(Bank: <green>" + CompactBalanceFormatter.exact(entry.bankBalance()) + "</green>, Zadania: <light_purple>" + entry.completedQuests() + "</light_purple>)</dark_gray>"
                            ));
                        }
                    }

                    sender.sendMessage(Component.empty());
                    sender.sendMessage(miniMessage.deserialize("<gold><bold>🎁 NAGRODY ZA MIEJSCA W RANKINGU:</bold></gold>"));
                    List<SkyBlockSettings.TopReward> rewards = topRewards.stream()
                            .sorted(Comparator.comparingInt(SkyBlockSettings.TopReward::rank))
                            .toList();
                    for (SkyBlockSettings.TopReward configured : rewards) {
                        String placeLabel = switch (configured.rank()) {
                            case 1 -> "<gold><bold>#" + configured.rank() + " Miejsce:</bold></gold>";
                            case 2 -> "<white><bold>#" + configured.rank() + " Miejsce:</bold></white>";
                            case 3 -> "<color:#cd7f32><bold>#" + configured.rank() + " Miejsce:</bold></color>";
                            default -> "<yellow><bold>#" + configured.rank() + " Miejsce:</bold></yellow>";
                        };
                        sender.sendMessage(miniMessage.deserialize("  " + placeLabel
                                + " <aqua>" + configured.lotus() + "x Diamentowy Lotos</aqua> + <yellow>"
                                + CompactBalanceFormatter.exact(configured.coins()) + " monet</yellow>"
                                + titleSuffix(configured.rank())));
                    }

                    if (sender instanceof Player player) {
                        sender.sendMessage(Component.empty());
                        if (playerRank != null) {
                            sender.sendMessage(miniMessage.deserialize(
                                    "<gray>Twoja wyspa: Miejsce <yellow><bold>#" + playerRank + "</bold></yellow> (Punkty: <yellow>" + CompactBalanceFormatter.exact(playerScore) + "</yellow>)</gray>"));
                        }
                        /*
                         * Przycisk odbioru widzi dokładnie ta wyspa, której
                         * roszczenie przeszłoby bramkę uprawnień — decyduje ta
                         * sama funkcja co w claimReward, więc widok i wypłata
                         * nie mogą się rozjechać. Miejsce z własnym wpisem
                         * (5/10/50) ma przycisk tak samo jak podium.
                         */
                        final int finalRank = playerRank == null ? 0 : playerRank;
                        IslandScore mine = ranking.stream()
                                .filter(entry -> entry.rank() == finalRank)
                                .findFirst()
                                .orElse(null);
                        boolean entitled = mine != null
                                && rejectClaim(eligibility, ranking, mine) == null;
                        if (entitled) {
                            isClaimed(seasonId, player.getUniqueId()).thenAccept(claimed -> {
                                if (claimed) {
                                    String claimedEdition = editionName(seasonId);
                                    player.sendMessage(miniMessage.deserialize("<green>✔ Odebrano już nagrodę za "
                                            + (claimedEdition != null ? "edycję " + claimedEdition : "tę edycję") + ".</green>"));
                                } else {
                                    Component claimBtn = miniMessage.deserialize("<gradient:#43e97b:#38f9d7><bold>[ KLIKNIJ TUTAJ, ABY ODEBRAĆ NAGRODĘ ]</bold></gradient>")
                                            .hoverEvent(HoverEvent.showText(miniMessage.deserialize("<yellow>Kliknij, aby odebrać nagrodę sezonową!</yellow>")))
                                            .clickEvent(ClickEvent.runCommand("/nagrody claim"));
                                    player.sendMessage(claimBtn);
                                }
                                player.sendMessage(miniMessage.deserialize(
                                        "<gradient:#00d2ff:#00a8ff><bold>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</bold></gradient>"));
                            }).exceptionally(error -> {
                                plugin.getLogger().warning("Nagrody sezonu "
                                        + seasonId + ": status claimu nieudany: " + error);
                                return null;
                            });
                            return;
                        }
                    }
                    sender.sendMessage(miniMessage.deserialize(
                            "<gradient:#00d2ff:#00a8ff><bold>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</bold></gradient>"));
                }).exceptionally(error -> {
                    plugin.getLogger().warning(
                            "Nagrody sezonu: render rankingu nieudany: " + error);
                    sender.sendMessage(miniMessage.deserialize(
                            "<red>Nie udało się pobrać rankingu sezonowego — spróbuj ponownie.</red>"));
                    return null;
                });
    }

    /**
     * Creates Diamond Lotus item stack.
     */

    /**
     * Wydaje przedmiotową część nagrody sezonowej: Lotosy, a po nich kosmetykę.
     *
     * <p>Obie idą przez outbox, więc przeżywają awarię serwera i pełny ekwipunek.
     * Wcześniej Lotosy dodawano wprost do plecaka, przez co awaria między zapisem
     * w księdze a dodaniem gubiła nagrodę bezpowrotnie.
     *
     * <p>Identyfikator operacji jest <b>deterministyczny</b>. Odebranie nagrody
     * jest już strzeżone tabelą roszczeń, ale klucz główny outboxa domyka to
     * drugim zamkiem: ponowione wydanie za ten sam sezon nie przejdzie.
     */
    /**
     * Lotosy przez outbox z deterministycznym identyfikatorem operacji —
     * powtórne wywołanie (drugi klik, odtwarzanie po awarii) jest no-opem,
     * więc grant może biec przed wpisem roszczenia.
     *
     * <p>Bez outboxa (awaryjny fallback) niczego tu nie wydajemy: dostawa
     * nietrwała nie jest idempotentna, więc musi czekać na recordClaim.
     */
    private @NotNull CompletableFuture<InventoryOutbox.Outcome> grantSeasonLotus(
            @NotNull Player player, int seasonId, @NotNull UUID islandId,
            @NotNull SkyBlockSettings.TopReward reward) {
        InventoryOutbox durable = outbox;
        if (reward.lotus() <= 0) {
            // Miejsce z `lotus: 0` w configu (TOP 10 i TOP 50) dostaje TYLKO to, co
            // ma w wpisie — dla tych miejsc jest to kosmetyk (część kolekcji i/lub
            // tytuł wyspy). Wcześniej `createDiamondLotus(0)` przepuszczalo kwotę
            // przez `Math.max(1, amount)`, więc miejsce 10 dostawało 1 Diamentowy
            // Lotos, czyli darmową 1/3 zlewu na tytuł Władcy Lotosu — wprost
            // sprzeczne z decyzją właściciela o celowych zerach (audyt 2026-09-11).
            return CompletableFuture.completedFuture(InventoryOutbox.Outcome.SUCCESS);
        }
        if (durable == null) {
            return CompletableFuture.completedFuture(InventoryOutbox.Outcome.SUCCESS);
        }
        CompletableFuture<InventoryOutbox.Outcome> done = new CompletableFuture<>();
        // SKYBLOCK-1-7: operationId per WYSPA, nie per gracz — kolejny członek
        // zwycięskiej wyspy trafia w ten sam wiersz outboxa (PK operation_id) i
        // jest no-opem, tak jak wypłata monet po transaction_id.
        durable.beginGrant(player, 0L,
                "season_reward:lotus:" + seasonId + ':' + islandId,
                "seasonal_lotus", createDiamondLotus(reward.lotus()), done::complete);
        return done;
    }

    /**
     * Tytuł wyspy za dokładnie to miejsce w rankingu ({@code season.title-rewards}).
     * Brak wpisu dla miejsca = brak tytułu (tak jak brak wpisu w top-rewards =
     * brak nagrody). Nadanie idempotentne po {@code source_id}, więc powtórka
     * po restarcie ani drugi członek wyspy nie kosztują niczego.
     *
     * <p>Ranga w identyfikatorze to <b>miejsce z rankingu</b>, nie miejsce
     * spadnięte do progu — miejsce 50 i miejsce 7 mają różne tytuły tylko
     * wtedy, gdy konfiguracja ma dla nich osobne wpisy.
     *
     * @return tytuł, który faktycznie wszedł na wyspę, albo {@code null}, gdy
     *         miejsce nie ma tytułu w konfiguracji lub magazyn zachował tytuł
     *         o wyższym priorytecie
     */
    // Bez @Nullable w pozycji typu: boosted-yaml cieniuje starą kopię
    // org.jetbrains.annotations (bez TARGET TYPE_USE) przed właściwym
    // artefaktem na classpath — kompilator odrzuca CompletableFuture<@Nullable String>.
    private @NotNull CompletableFuture<String> grantSeasonTitle(@NotNull UUID islandId,
                                                                int seasonId, int rank) {
        IslandTitleService titles = this.islandTitles;
        String title = titleRewards.get(rank);
        if (titles == null || title == null || title.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return titles.grant(islandId, title, "season:" + seasonId + ":rank" + rank)
                .thenApply(status -> status == IslandTitleService.GrantStatus.GRANTED
                        ? title
                        : null)
                .exceptionally(failure -> {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Nie udało się nadać tytułu sezonu " + seasonId + " dla wyspy "
                                    + islandId + " (miejsce #" + rank + ")", failure);
                    return null;
                });
    }

    /**
     * Dopisek do wiersza nagrody w podsumowaniu, gdy to miejsce daje dodatkowo
     * tytuł wyspy ({@code season.title-rewards}) — inaczej nikt nie wie, że
     * miejsce 10 czy 50 płaci także tytułem.
     */
    private @NotNull String titleSuffix(int rank) {
        String title = titleRewards.get(rank);
        return title == null || title.isBlank()
                ? ""
                : " <dark_gray>•</dark_gray> <gray>tytuł wyspy:</gray> <white>" + title + "</white>";
    }

    private void scheduleCosmetics(Player player, int seasonId, int rank) {
        try {
            var scheduled = player.getScheduler().runDelayed(plugin,
                    ignored -> deliverCosmetics(player, seasonId, rank),
                    null, ConvergenceRetryPolicy.FAST_DELAY_TICKS);
            if (scheduled == null) {
                plugin.getLogger().warning("Nie zaplanowano kosmetyki sezonowej dla "
                        + player.getUniqueId() + "; do ręcznego wydania");
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Planowanie kosmetyki sezonowej odrzucone dla "
                    + player.getUniqueId(), rejected);
        }
    }

    private void deliverCosmetics(Player player, int seasonId, int rank) {
        CosmeticService cosmeticService = cosmetics;
        if (cosmeticService != null) {
            cosmeticService.grantSeasonReward(player, seasonId, rank, () -> { });
        }
    }

    /** Ścieżka awaryjna, gdy outbox jest niedostępny — tak działało to wcześniej. */
    private void deliverDirectly(Player player, ItemStack lotusStack) {
        HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(lotusStack);
        if (leftovers.isEmpty()) {
            return;
        }
        for (ItemStack item : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        player.sendMessage(miniMessage.deserialize(
                "<yellow>Twój ekwipunek był pełny! Część nagród upuszczono pod Twoimi stopami.</yellow>"));
    }

    /**
     * Stos Diamentowych Lotosów na {@code amount} sztuk.
     *
     * <p>Wołający muszą najpierw odsiać {@code amount <= 0} (patrz {@link
     * #grantSeasonLotus}): to metoda budująca stos, więc dla zera zwraca stos
     * o liczbie 1 wyłącznie dlatego, że {@code ItemStack} nie ma stanu „zero
     * sztuk". Jedynym poprawnym użyciem z zerem jest zapytanie o materiał/typ.
     */
    public @NotNull ItemStack createDiamondLotus(int amount) {
        int count = Math.max(1, amount);
        CustomItemService customItems = getCustomItemService();
        if (customItems != null) {
            Optional<ItemStack> itemOpt = customItems.create(DIAMOND_LOTUS_ID);
            if (itemOpt.isPresent()) {
                ItemStack stack = itemOpt.get();
                stack.setAmount(count);
                return stack;
            }
        }
        ItemStack stack = new ItemStack(Material.PAPER, count);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(miniMessage.deserialize("<aqua><bold>Diamentowy Lotos</bold></aqua>"));
            meta.lore(List.of(
                    miniMessage.deserialize("<gray>Legendarne trofeum przyznawane najlepszym wyspom w rankingu sezonu (/nagrody).</gray>"),
                    Component.empty(),
                    miniMessage.deserialize("<aqua>Najwyższy symbol prestiżu</aqua>"),
                    miniMessage.deserialize("<dark_gray>Trofeum Sezonowe • 2b2t.pl</dark_gray>")
            ));
            meta.getPersistentDataContainer().set(KEY_CUSTOM_ITEM_ID, PersistentDataType.STRING, DIAMOND_LOTUS_ID);
            meta.setEnchantmentGlintOverride(true);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void schedulePlayer(@NotNull Player player, @NotNull Runnable action) {
        try {
            player.getScheduler().run(plugin, task -> action.run(), null);
        } catch (Throwable fallback) {
            action.run();
        }
    }

    private void playRewardEffects(@NotNull Player player) {
        try {
            Location location = player.getLocation();
            World world = location.getWorld();
            if (world != null) {
                world.playSound(location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                world.playSound(location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                world.spawnParticle(Particle.TOTEM_OF_UNDYING, location.clone().add(0, 1.0, 0), 35, 0.5, 0.5, 0.5, 0.15);
                world.spawnParticle(Particle.FIREWORK, location.clone().add(0, 1.0, 0), 20, 0.4, 0.4, 0.4, 0.08);
            }
        } catch (Throwable ignored) {
            // Headless / mock test environment
        }
    }

    private void sendClaimSuccessMessage(@NotNull Player player, int seasonId, int rank,
                                         @NotNull SkyBlockSettings.TopReward reward) {
        String rankLabel = switch (rank) {
            case 1 -> "<gold><bold>1. MIEJSCE</bold></gold>";
            case 2 -> "<white><bold>2. MIEJSCE</bold></white>";
            case 3 -> "<color:#cd7f32><bold>3. MIEJSCE</bold></color>";
            // Miejsce z własnym wpisem (5/10/50) nie jest „progiem sezonu” —
            // próg to tylko nagroda dla miejsc bez własnego wpisu.
            default -> rewardForRank(topRewards, rank) != null
                    ? "<yellow><bold>MIEJSCE #" + rank + "</bold></yellow>"
                    : "<yellow><bold>PRÓG SEZONU</bold></yellow> <gray>(miejsce #" + rank + ")</gray>";
        };
        player.sendMessage(miniMessage.deserialize(
                "<gradient:#00d2ff:#00a8ff><bold>━━━━━━━━━━━━━ [ NAGRODA SEZONOWA ] ━━━━━━━━━━━━━</bold></gradient>"));
        // Widok gracza: nazwa edycji z rejestru, fallback — zakres dat (decyzja 2026-08-25).
        String edition = editionName(seasonId);
        player.sendMessage(miniMessage.deserialize(
                "<green>Gratulacje! Twoja wyspa zdobyła " + rankLabel + " w "
                        + (edition != null ? "<gold>edycji " + edition + "</gold>" : "<gold>tej edycji</gold>") + "!</green>"));
        player.sendMessage(miniMessage.deserialize(
                "<yellow>Otrzymano nagrody:</yellow> <aqua><bold>" + reward.lotus() + "x Diamentowy Lotos</bold></aqua> <gray>oraz</gray> <gold><bold>" + CompactBalanceFormatter.exact(reward.coins()) + " monet</bold></gold>!"));
        player.sendMessage(miniMessage.deserialize(
                "<gradient:#00d2ff:#00a8ff><bold>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━</bold></gradient>"));
    }

    /**
     * Nazwa edycji dla widoków operatora: najpierw resolver z wiringu
     * (rejestr nazwanych edycji), a gdy brak resolvera albo nie zna sezonu —
     * legacy zakres dat z sekcji {@code season} config.yml. Nigdy nie rzuca.
     */
    private @Nullable String editionName(int seasonId) {
        IntFunction<String> resolver = editionNameResolver;
        if (resolver != null) {
            try {
                String name = resolver.apply(seasonId);
                if (name != null && !name.isBlank()) {
                    return name;
                }
            } catch (RuntimeException brokenResolver) {
                plugin.getLogger().warning("editionNameResolver rzucił dla sezonu " + seasonId
                        + "; fallback na zakres dat: " + brokenResolver.getMessage());
            }
        }
        return SeasonLabels.windowText(seasonId,
                plugin.getConfig().getConfigurationSection("season"));
    }
}
