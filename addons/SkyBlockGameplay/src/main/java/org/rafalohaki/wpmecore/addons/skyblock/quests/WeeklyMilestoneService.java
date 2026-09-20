package org.rafalohaki.wpmecore.addons.skyblock.quests;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonDailyPointsService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandOwner;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Tygodniowy kamień milowy w trybie eco: 15 ukończonych zadań dnia w tygodniu
 * ISO = 1 Złoty Lotos dla właściciela wyspy.
 *
 * <p>Obietnica stała w grze od początku natywnej pętli
 * ({@link DailyQuestService#WEEKLY_MILESTONE_GOAL}, lore kafelka zadań), ale w
 * trybie produkcyjnym {@code eco-migration.enabled: true}
 * {@link DailyQuestService} nie jest rejestrowany (SkyBlockGameplay), więc nikt
 * tego Lotosu nie wydawał. W trybie eco dowodem ukończonego zadania dnia jest
 * wiersz w {@code wpme_sb_season_daily_points} (kanał {@code quest},
 * {@code eco-daily:<zadanie>}) dopisany przez
 * {@link SeasonDailyPointsService#awardDaily}; tu liczymy je po CZŁONKACH wyspy
 * (status ACTIVE), a nie po właścicielu — zadanie dnia robi ten, kto jest online.
 *
 * <p>Wypłata idzie istniejącym, idempotentnym po tygodniu claimem
 * ({@code wpme_sb_weekly_milestones}, PK {@code (account_key, week_key)}) i
 * outboxem ekwipunku z deterministycznym {@code operationId} — powtórka po
 * restarcie nie płaci drugi raz. Zero zmian stawek: próg 15 i jedna sztuka na
 * wyspę na tydzień, dokładnie jak w natywnej pętli.
 *
 * <p>Wszystkie odczyty idą asynchronicznym {@link SqlService}; jedyne wywołanie
 * Skyllii po właściciela następuje już po odpowiedzi bazy (wątek SQL, nigdy
 * regionu — {@code SkylliaIntegration#ownerOf} rzuca na wątku tickującym).
 * Odbiorców komunikatu też bierze z bazy (skład wyspy z tego samego zapytania co
 * licznik), a nie z migawki cache Skyllii — po skasowaniu i odtworzeniu wyspy o
 * tym samym {@code island_id} cache bywa pusty i komunikat nie docierał do nikogo.
 */
public final class WeeklyMilestoneService {

    /** Identyfikator nagrody — ten sam, którego używa natywna pętla. */
    public static final String GOLD_LOTUS_ID = DailyQuestService.GOLD_LOTUS_ID;

    /** Kanał dobowy zadania dnia (EcoQuests woła {@code /sezon admin punkty-dzien … quest …}). */
    public static final String QUEST_CHANNEL = "quest";

    /** Próg tygodnia: 15 ukończonych zadań dnia na wyspę. */
    public static final int WEEKLY_GOAL = DailyQuestService.WEEKLY_MILESTONE_GOAL;

    private final JavaPlugin plugin;
    private final LedgerService ledger;
    private final SkylliaIntegration skyllia;
    private final InventoryOutbox outbox;
    private final CustomItemService customItems;
    private final MiniMessage miniMessage;
    private final SqlService sql;
    private final Clock clock;

    public WeeklyMilestoneService(@NotNull JavaPlugin plugin, @NotNull LedgerService ledger,
                                  @NotNull SkylliaIntegration skyllia,
                                  @NotNull InventoryOutbox outbox,
                                  @NotNull CustomItemService customItems,
                                  @NotNull MiniMessage miniMessage,
                                  @NotNull SqlService sql) {
        this(plugin, ledger, skyllia, outbox, customItems, miniMessage, sql, Clock.systemUTC());
    }

    /** Wariant z wstrzykniętym zegarem — tydzień i dzień liczone w UTC (doba kanału dnia). */
    WeeklyMilestoneService(@NotNull JavaPlugin plugin, @NotNull LedgerService ledger,
                           @NotNull SkylliaIntegration skyllia,
                           @NotNull InventoryOutbox outbox,
                           @NotNull CustomItemService customItems,
                           @NotNull MiniMessage miniMessage,
                           @NotNull SqlService sql, @NotNull Clock clock) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.skyllia = skyllia;
        this.outbox = outbox;
        this.customItems = customItems;
        this.miniMessage = miniMessage;
        this.sql = sql;
        this.clock = clock;
    }

    /**
     * Dekoruje dzienny kanał punktów tak, żeby po KAŻDYM udanym przyznaniu
     * punktów kanału {@code quest} sprawdził tygodniowy kamień milowy.
     *
     * <p>Dzięki temu obietnica nie zależy od pojedynczego wołającego: dziś jest
     * nim komenda {@code /sezon admin punkty-dzien} (EcoQuests), ale każde
     * przyszłe źródło zadań dnia wchodzi tą samą bramką. Sprawdzenie startuje
     * z wątku, na którym skończyła się transakcja SQL — nigdy z regionu.
     * Kanały inne niż {@code quest} (np. wędka) nie ruszają milestone'a.
     */
    public @NotNull SeasonDailyPointsService watchingQuestAwards(
            @NotNull SeasonDailyPointsService delegate) {
        return new QuestDayWatcher(this, delegate);
    }

    /**
     * Sprawdza próg tygodnia po ukończeniu zadania dnia. Bez wyspy nie robi nic
     * (gracz na hubie/bez profilu nie ma komu wydać nagrody).
     */
    public @NotNull CompletableFuture<Void> tryClaimAfterDaily(@NotNull UUID playerId) {
        Optional<UUID> islandId;
        try {
            islandId = skyllia.cachedIslandIdOf(playerId);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(null);
        }
        if (islandId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        UUID island = islandId.get();
        LocalDate today = LocalDate.now(clock);
        String weekKey = weekKey(today);
        String startDay = today.with(DayOfWeek.MONDAY).toString();
        String endDay = today.with(DayOfWeek.SUNDAY).toString();

        return loadIslandWeek(island, startDay, endDay)
                .thenCompose(week -> {
                    if (week.completed() < WEEKLY_GOAL) {
                        return CompletableFuture.completedFuture(null);
                    }
                    /*
                     * Właściciel musi być znany PRZED claimem: bez niego nie ma
                     * komu wydać Lotosu, a zapisany claim przepaliłby nagrodę na
                     * zawsze. Pusty wynik = ponowna próba przy kolejnym zadaniu.
                     */
                    Optional<IslandOwner> owner = skyllia.ownerOf(island);
                    if (owner.isEmpty()) {
                        if (plugin.getLogger() != null) {
                            plugin.getLogger().warning("Weekly milestone reached for island " + island
                                    + " but its owner is unknown; retrying on a later quest day");
                        }
                        return CompletableFuture.completedFuture(null);
                    }
                    UUID ownerId = owner.get().playerId();
                    return ledger.claimWeeklyMilestone(island, weekKey)
                            .thenAccept(claimedNow -> {
                                if (claimedNow) {
                                    notifyWeeklyMilestone(island, weekKey, ownerId, week.members());
                                }
                            });
                })
                .exceptionally(failure -> {
                    if (plugin.getLogger() != null) {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to process the weekly milestone for island " + island, failure);
                    }
                    return null;
                });
    }

    /** Skład wyspy (ACTIVE) razem z liczbą ukończonych zadań dnia w oknie tygodnia. */
    record IslandWeek(@NotNull Set<UUID> members, int completed) { }

    /**
     * Ukończone zadania dnia CZŁONKÓW wyspy w oknie tygodnia — jedno zapytanie
     * zwraca i licznik, i SKŁAD wyspy. Okno liczymy w UTC, bo taki jest dzień
     * wiersza ({@code SeasonDailyPointsService} pisze datę UTC), a format klucza
     * tygodnia jest ten sam co {@code QuestCatalog#weekKey}: {@code RRRR-Wnn} z
     * rokiem tygodniowym ISO.
     *
     * <p>Skład bierzemy z bazy, a nie z cache Skyllii: po skasowaniu i
     * odtworzeniu wyspy o tym samym {@code island_id} migawka cache bywa pusta,
     * więc filtrowanie odbiorców komunikatu po niej nie docierało do nikogo
     * (E2E 2026-09-10, nagroda szła outboxem, więc cicho). {@code LEFT JOIN}
     * trzyma w wyniku także członków bez ani jednego zadania w tym tygodniu — to
     * oni mają dostać komunikat.
     */
    private @NotNull CompletableFuture<IslandWeek> loadIslandWeek(@NotNull UUID islandId,
                                                                  @NotNull String startDay,
                                                                  @NotNull String endDay) {
        return sql.query("""
                        SELECT m.player_uuid, COUNT(d.operation_id)
                        FROM wpme_sb_island_membership m
                        LEFT JOIN wpme_sb_season_daily_points d
                          ON d.player_uuid = m.player_uuid AND d.channel = ? AND d.day >= ? AND d.day <= ?
                        WHERE m.island_id = ? AND m.status = 'ACTIVE'
                        GROUP BY m.player_uuid
                        """,
                row -> Map.entry(UUID.fromString(row.getString(1)), row.getInt(2)),
                QUEST_CHANNEL, startDay, endDay, islandId.toString())
                .thenApply(rows -> {
                    Set<UUID> members = new LinkedHashSet<>();
                    int completed = 0;
                    for (Map.Entry<UUID, Integer> row : rows) {
                        members.add(row.getKey());
                        completed += row.getValue();
                    }
                    return new IslandWeek(Set.copyOf(members), completed);
                });
    }

    /**
     * Dokładnie jeden Złoty Lotos na wyspę na tydzień, do właściciela (offline →
     * dług outboxa przy najbliższym wejściu) + komunikat do członków online.
     * Ten sam {@code operationId} co w natywnej pętli, więc przejście
     * eco/natywne w tym samym tygodniu nie wyda dwóch sztuk.
     *
     * <p>Odbiorców bierzemy ze składu wyspy z bazy ({@code members}), a nie z
     * cache Skyllii; właściciel dostaje komunikat zawsze, nawet gdy jego wiersz
     * membership jest chwilowo niespójny. Każdy gracz dostaje najwyżej jedną
     * wiadomość (jedno przejście po graczach online).
     */
    private void notifyWeeklyMilestone(@NotNull UUID islandId, @NotNull String weekKey,
                                       @NotNull UUID ownerId, @NotNull Set<UUID> members) {
        var milestoneMessage = Ui.component(miniMessage,
                "<gold><bold>✦ TYGODNIOWY KAMIEŃ MILOWY OSIĄGNIĘTY! ✦</bold></gold>\n"
                        + "<yellow>Twoja wyspa ukończyła <gold>15</gold> zadań w tym tygodniu!</yellow>\n"
                        + "<green>Nagroda: <gold>1x Złoty Lotos</gold> dla właściciela wyspy.</green>");
        var server = plugin.getServer();
        if (server != null) {
            try {
                server.getGlobalRegionScheduler().execute(plugin, () -> {
                    for (Player online : server.getOnlinePlayers()) {
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

        ItemStack goldLotus = customItems.create(GOLD_LOTUS_ID).orElse(null);
        if (goldLotus == null) {
            if (plugin.getLogger() != null) {
                plugin.getLogger().severe("Weekly milestone lotus could not be created for island "
                        + islandId + " week " + weekKey + " — reward requires manual delivery");
            }
            return;
        }
        goldLotus.setAmount(1);
        String operationId = "milestone:" + islandId + ':' + weekKey;
        Player onlineOwner = server == null ? null : server.getPlayer(ownerId);
        if (onlineOwner != null) {
            outbox.beginGrant(onlineOwner, 0L, operationId, "weekly_milestone", goldLotus,
                    outcome -> { });
        } else {
            outbox.beginOfflineGrant(ownerId, 0L, operationId, "weekly_milestone", goldLotus,
                    outcome -> { });
        }
    }

    /** Klucz tygodnia ISO w formacie {@code RRRR-Wnn} — wspólny z {@code QuestCatalog}. */
    static @NotNull String weekKey(@NotNull LocalDate day) {
        int week = day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int year = day.get(IsoFields.WEEK_BASED_YEAR);
        return String.format(Locale.ROOT, "%04d-W%02d", year, week);
    }

    /** Dzienny kanał punktów z doklejonym sprawdzeniem progu tygodnia. */
    private static final class QuestDayWatcher implements SeasonDailyPointsService {

        private final WeeklyMilestoneService milestones;
        private final SeasonDailyPointsService delegate;

        QuestDayWatcher(@NotNull WeeklyMilestoneService milestones,
                        @NotNull SeasonDailyPointsService delegate) {
            this.milestones = milestones;
            this.delegate = delegate;
        }

        @Override
        public @NotNull CompletableFuture<Long> pointsToday(@NotNull UUID playerUuid,
                                                            @NotNull String channel) {
            return delegate.pointsToday(playerUuid, channel);
        }

        @Override
        public @NotNull CompletableFuture<Long> awardDaily(@NotNull UUID playerUuid,
                                                           @NotNull String channel,
                                                           long points,
                                                           @NotNull String operationId) {
            return delegate.awardDaily(playerUuid, channel, points, operationId)
                    .thenApply(granted -> {
                        if (granted != null && granted > 0L
                                && QUEST_CHANNEL.equalsIgnoreCase(channel)) {
                            try {
                                milestones.tryClaimAfterDaily(playerUuid);
                            } catch (RuntimeException failure) {
                                var logger = milestones.plugin.getLogger();
                                if (logger != null) {
                                    logger.log(Level.WARNING,
                                            "Weekly milestone check failed to start for " + playerUuid,
                                            failure);
                                }
                            }
                        }
                        return granted;
                    });
        }
    }
}
