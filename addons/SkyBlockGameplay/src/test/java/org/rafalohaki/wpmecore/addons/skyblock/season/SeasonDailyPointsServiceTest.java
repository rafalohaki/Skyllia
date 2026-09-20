package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-1: dzienny kanał punktów z capem. Sprawdzamy granice capu (przyznanie
 * częściowe i odcięcie na wyczerpaniu), idempotencję po operationId w obrębie
 * dnia, kanał bez wpisu w configu (pełne punkty — zachowanie sprzed P1-1),
 * cap {@code 0} = bez limitu oraz fail-closed przy wartości ujemnej.
 *
 * <p>DAO to atrapa in-memory: liczy się kontrakt serwisu (suma dnia → reszta do
 * capu → wiersz idempotentny), a SQL i PK tabeli pokrywa
 * {@link SeasonDailyPointsDaoTest}.
 */
class SeasonDailyPointsServiceTest {

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant DAY_ONE = Instant.parse("2026-09-10T10:15:30Z");

    /** Atrapa tabeli dziennej: klucz (gracz, dzień, kanał, operationId) → punkty. */
    private static final class InMemoryDao implements SeasonDailyPointsDao {
        final Map<String, Long> rows = new LinkedHashMap<>();

        private static String key(UUID player, String day, String channel, String operationId) {
            return player + "|" + day + "|" + channel + "|" + operationId;
        }

        @Override
        public @NotNull CompletableFuture<Long> sumToday(@NotNull UUID playerUuid,
                                                         @NotNull String day,
                                                         @NotNull String channel) {
            long sum = rows.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(playerUuid + "|" + day + "|" + channel + "|"))
                    .mapToLong(Map.Entry::getValue)
                    .sum();
            return CompletableFuture.completedFuture(sum);
        }

        @Override
        public @NotNull CompletableFuture<Boolean> insertIfAbsent(@NotNull UUID playerUuid,
                                                                  @NotNull String day,
                                                                  @NotNull String channel,
                                                                  @NotNull String operationId,
                                                                  long points) {
            return CompletableFuture.completedFuture(
                    rows.putIfAbsent(key(playerUuid, day, channel, operationId), points) == null);
        }

        @Override
        public @NotNull CompletableFuture<Void> delete(@NotNull UUID playerUuid, @NotNull String day,
                                                       @NotNull String channel,
                                                       @NotNull String operationId) {
            rows.remove(key(playerUuid, day, channel, operationId));
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Rejestruje przyznania do puli sezonowej — bez SQL. */
    private static final class RecordingSeasonPoints implements SeasonPointService {
        record Award(UUID player, long points, String operationId, boolean countAsQuest) { }
        final List<Award> awards = new ArrayList<>();

        /** Gdy true, dopisanie do puli pada wyjatkiem (symulacja awarii SQL w oknie). */
        boolean failNextAward = false;

        @Override
        public @NotNull CompletableFuture<Boolean> award(@NotNull UUID playerUuid, long points,
                                                         @NotNull String operationId) {
            return award(playerUuid, points, operationId, true);
        }

        @Override
        public @NotNull CompletableFuture<Boolean> award(@NotNull UUID playerUuid, long points,
                                                         @NotNull String operationId,
                                                         boolean countAsQuest) {
            if (failNextAward) {
                return CompletableFuture.failedFuture(new IllegalStateException("pula niedostepna"));
            }
            awards.add(new Award(playerUuid, points, operationId, countAsQuest));
            return CompletableFuture.completedFuture(true);
        }

        @Override public int currentSeason() { return 7; }
        @Override public @NotNull CompletableFuture<Long> pointsOf(@NotNull UUID playerUuid) {
            return CompletableFuture.completedFuture(
                    awards.stream().mapToLong(Award::points).sum());
        }
        @Override public @NotNull CompletableFuture<Optional<Integer>> rankOf(@NotNull UUID playerUuid) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        @Override public @NotNull CompletableFuture<List<SeasonPointDao.TopRow>> top(int limit) {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public int unlockedQuestSlots(long seasonPoints) { return 0; }
        @Override public boolean isCatchUpActive() { return false; }
    }

    /** Zegar przesuwalny o dni — doba rozliczeniowa capu. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(DAY_ONE);

        @Override public @NotNull ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public @NotNull Clock withZone(@NotNull ZoneId zone) { return this; }
        @Override public @NotNull Instant instant() { return now.get(); }

        void plusDays(long days) {
            now.updateAndGet(instant -> instant.plus(Duration.ofDays(days)));
        }
    }

    private final InMemoryDao dao = new InMemoryDao();
    private final RecordingSeasonPoints seasonPoints = new RecordingSeasonPoints();
    private final MutableClock clock = new MutableClock();

    private SeasonDailyPointsService service(Map<String, Long> caps) {
        return SeasonDailyPointsService.create(dao, seasonPoints,
                new SeasonDailyPointsCaps(caps), clock);
    }

    private static YamlConfiguration yaml(String raw) {
        return YamlConfiguration.loadConfiguration(new java.io.StringReader(raw));
    }

    @Test
    void questChannelCountsAsQuestButFishDoesNot() {
        SeasonDailyPointsService service = service(Map.of("quest", 100L, "fish", 300L));

        service.awardDaily(PLAYER, "quest", 25, "eco-daily:test").join();
        service.awardDaily(PLAYER, "fish", 12, "emf-fish:test").join();

        assertEquals(2, seasonPoints.awards.size());
        assertTrue(seasonPoints.awards.get(0).countAsQuest(),
                "zadanie dnia ma podbijać quests_done (składnik rankingu wysp)");
        assertFalse(seasonPoints.awards.get(1).countAsQuest(),
                "ryba nie jest ukończonym zadaniem — nie może pompować rankingu wysp");
    }

    @Test
    void capGrantsOnlyWhatFitsAndThenStopsTheDay() {
        SeasonDailyPointsService service = service(Map.of("fish", 150L));

        assertEquals(100L, service.awardDaily(PLAYER, "fish", 100, "q1").join(),
                "pierwsza porcja mieści się w capie");
        assertEquals(50L, service.awardDaily(PLAYER, "fish", 100, "q2").join(),
                "druga porcja przyznaje tylko resztę do capu");
        assertEquals(0L, service.awardDaily(PLAYER, "fish", 10, "q3").join(),
                "po wyczerpaniu capu nic już nie wchodzi");
        assertEquals(150L, service.pointsToday(PLAYER, "fish").join());
        assertEquals(List.of(100L, 50L), seasonPoints.awards.stream()
                .map(RecordingSeasonPoints.Award::points).toList(),
                "do puli sezonowej trafia wyłącznie część mieszcząca się w capie");
        assertEquals(List.of("fish:2026-09-10:" + PLAYER + ":q1", "fish:2026-09-10:" + PLAYER + ":q2"),
                seasonPoints.awards.stream()
                        .map(RecordingSeasonPoints.Award::operationId).toList(),
                "id operacji w puli sezonowej niesie UUID gracza — wpme_sb_reward_claims ma "
                        + "GLOBALNY unikalny indeks na operation_id, więc bez UUID drugi gracz "
                        + "z tym samym kanałem/dniem/zadaniem nie dostałby punktów "
                        + "(regresja z laba 2026-09-10)");
    }

    @Test
    void repeatedOperationIdIsCountedOnceInTheSameDay() {
        SeasonDailyPointsService service = service(Map.of("fish", 150L));

        assertEquals(100L, service.awardDaily(PLAYER, "fish", 100, "eco-quest:q1").join());
        assertEquals(0L, service.awardDaily(PLAYER, "fish", 100, "eco-quest:q1").join(),
                "powtórka tego samego operationId w tym samym dniu nie przyznaje nic");
        assertEquals(100L, service.pointsToday(PLAYER, "fish").join());
        assertEquals(1, dao.rows.size(), "powtórka nie dopisuje drugiego wiersza");
        assertEquals(1, seasonPoints.awards.size());
    }

    @Test
    void nextDayStartsAFreshDailyPool() {
        SeasonDailyPointsService service = service(Map.of("fish", 150L));

        assertEquals(100L, service.awardDaily(PLAYER, "fish", 100, "q1").join());
        clock.plusDays(1);

        assertEquals(100L, service.awardDaily(PLAYER, "fish", 100, "q1").join(),
                "nowy dzień = nowa pula capu, nawet przy tym samym operationId");
        assertEquals(100L, service.pointsToday(PLAYER, "fish").join());
    }

    @Test
    void noCapsSectionLeavesChannelsUncapped() {
        SeasonDailyPointsCaps caps = SeasonDailyPointsCaps.load(null);
        assertEquals(SeasonDailyPointsCaps.NONE, caps);
        SeasonDailyPointsService service = service(caps.capsByChannel());

        assertEquals(1000L, service.awardDaily(PLAYER, "fish", 1000, "q1").join(),
                "brak sekcji = pełne punkty, jak przed P1-1");
        assertEquals(3000L, service.awardDaily(PLAYER, "fish", 3000, "q2").join());
        assertEquals(4000L, service.pointsToday(PLAYER, "fish").join());
    }

    @Test
    void channelMissingFromTheCapTableIsUncapped() {
        SeasonDailyPointsService service = service(Map.of("fish", 150L));

        assertEquals(500L, service.awardDaily(PLAYER, "quest", 500, "d1").join(),
                "kanał bez wpisu nie jest limitowany");
        assertEquals(150L, service.awardDaily(PLAYER, "fish", 500, "d2").join(),
                "sąsiedni kanał ma własną, nietkniętą pulę capu");
        assertEquals(500L, service.pointsToday(PLAYER, "quest").join());
        assertEquals(150L, service.pointsToday(PLAYER, "fish").join());
    }

    @Test
    void zeroCapMeansChannelWithoutLimit() {
        SeasonDailyPointsService service = service(Map.of("fish", 0L));

        assertEquals(10_000L, service.awardDaily(PLAYER, "fish", 10_000, "q1").join(),
                "0 = kanał jawnie bez capu");
    }

    @Test
    void nonPositiveRequestAwardsNothingWithoutTouchingTheDay() {
        SeasonDailyPointsService service = service(Map.of("fish", 150L));

        assertEquals(0L, service.awardDaily(PLAYER, "fish", 0, "q1").join());
        assertEquals(0L, service.awardDaily(PLAYER, "fish", -5, "q2").join());
        assertTrue(dao.rows.isEmpty(), "prośba bez punktów nie tworzy wiersza dnia");
    }

    /**
     * Awaria dopisania do puli nie może zjeść dobowego capu.
     *
     * <p>Regresja (audyt 2026-09-11, D-1): wiersz dnia powstawał przed dopisaniem
     * punktów do puli, więc wyjątek w drugim kroku zostawiał wiersz liczący się do
     * capu, mimo że punkty nigdy nie dotarły — gracz tracił fragment limitu do
     * końca dnia, a powtórka tego samego zadania dostawała `false`.
     */
    @Test
    void nieudaneDopisanieDoPuliCofaWierszDniaIDaPonowic() {
        SeasonDailyPointsService svc = service(Map.of("quest", 75L));
        UUID player = UUID.randomUUID();
        seasonPoints.failNextAward = true;

        long first = svc.awardDaily(player, "quest", 25L, "zadanie-1").join();

        assertEquals(0L, first, "porazka puli = zero przyznanych punktow");
        assertEquals(0L, svc.pointsToday(player, "quest").join(),
                "cap nie moze byc zjedzony bez punktow w puli");

        // Po ustaniu awarii to samo zadanie musi wejsc normalnie.
        seasonPoints.failNextAward = false;
        long retry = svc.awardDaily(player, "quest", 25L, "zadanie-1").join();

        assertEquals(25L, retry, "powtorka po awarii musi przyznac punkty");
        assertEquals(25L, svc.pointsToday(player, "quest").join());
        assertEquals(1, seasonPoints.awards.size(), "do puli trafia dokladnie raz");
    }

    @Test
    void emptyOperationIdIsStillIdempotentPerDay() {
        SeasonDailyPointsService service = service(Map.of("fish", 150L));

        assertEquals(20L, service.awardDaily(PLAYER, "fish", 20, "").join());
        assertEquals(0L, service.awardDaily(PLAYER, "fish", 20, "").join());
        assertEquals(20L, service.pointsToday(PLAYER, "fish").join());
    }

    @Test
    void negativeCapStopsStartupWithChannelName() {
        YamlConfiguration config = yaml("""
                fish: -1
                """);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SeasonDailyPointsCaps.load(config));
        assertTrue(failure.getMessage().contains("fish"), failure.getMessage());
    }

    @Test
    void nonNumericCapStopsStartupWithChannelName() {
        YamlConfiguration config = yaml("""
                quest: dużo
                """);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SeasonDailyPointsCaps.load(config));
        assertTrue(failure.getMessage().contains("quest"), failure.getMessage());
    }

    @Test
    void capsAreReadPerChannelAndCaseInsensitive() {
        YamlConfiguration config = yaml("""
                FISH: 150
                quest: 0
                """);
        SeasonDailyPointsCaps caps = SeasonDailyPointsCaps.load(config);

        assertEquals(150L, caps.capFor("fish"));
        assertEquals(150L, caps.capFor("FISH"));
        assertEquals(0L, caps.capFor("quest"), "0 = jawnie bez capu");
        assertEquals(0L, caps.capFor("mining"), "kanał spoza tabeli = bez capu");
    }
}
