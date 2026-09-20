package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.Test;
import org.jetbrains.annotations.NotNull;

import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2.5: progi odblokowań, catch-up, idempotencja punktów sezonowych.
 * DAO mockowane — logika progowa i cache w serwisie.
 */
class DefaultSeasonPointServiceTest {

    private static final int SEASON = 3;

    /** Prosty in-memory DAO: punkty + zużyte operationId. */
    private static final class StubDao implements SeasonPointDao {
        final Map<UUID, long[]> points = new ConcurrentHashMap<>(); // [points, questsDone]
        final Map<String, Boolean> operations = new ConcurrentHashMap<>();
        final Map<UUID, Integer> ranks = new ConcurrentHashMap<>();

        @Override
        public @NotNull CompletableFuture<Boolean> addPoints(@NotNull UUID u, int season,
                                                             long pts, @NotNull String op, boolean quest) {
            if (operations.putIfAbsent(op, true) != null) return CompletableFuture.completedFuture(false);
            points.computeIfAbsent(u, k -> new long[2])[0] += pts;
            if (quest) points.get(u)[1]++;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public @NotNull CompletableFuture<Long> points(@NotNull UUID u, int season) {
            return CompletableFuture.completedFuture(points.getOrDefault(u, new long[2])[0]);
        }

        @Override
        public @NotNull CompletableFuture<Integer> questsDone(@NotNull UUID u, int season) {
            return CompletableFuture.completedFuture((int) points.getOrDefault(u, new long[2])[1]);
        }

        @Override
        public @NotNull CompletableFuture<Optional<Integer>> rank(@NotNull UUID u, int season) {
            return CompletableFuture.completedFuture(Optional.ofNullable(ranks.get(u)));
        }

        @Override
        public @NotNull CompletableFuture<List<TopRow>> top(int season, int limit) {
            return CompletableFuture.completedFuture(points.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                    .limit(limit)
                    .map(e -> new TopRow(e.getKey(), e.getValue()[0]))
                    .toList());
        }

    }

    private DefaultSeasonPointService service(SeasonPointDao dao, long endsInDays) {
        return new DefaultSeasonPointService(dao,
                () -> SEASON,
                () -> System.currentTimeMillis() + endsInDays * 86_400_000L,
                ZoneId.of("Europe/Warsaw"));
    }

    private DefaultSeasonPointService weekendService(SeasonPointDao dao, double multiplier,
                                                     java.time.DayOfWeek day) {
        return new DefaultSeasonPointService(dao, () -> SEASON,
                () -> System.currentTimeMillis() + 30L * 86_400_000L,
                ZoneId.of("Europe/Warsaw"), multiplier, () -> day);
    }

    @Test
    void unlockThresholdsFollowSpecTiers() {
        DefaultSeasonPointService svc = service(new StubDao(), 30);
        assertEquals(8, svc.unlockedQuestSlots(0L), "start: 8 questów");
        assertEquals(16, svc.unlockedQuestSlots(300L), "próg 300: +8");
        assertEquals(24, svc.unlockedQuestSlots(800L), "próg 800: +8");
        assertEquals(32, svc.unlockedQuestSlots(1_600L), "próg 1600: +8");
        assertEquals(40, svc.unlockedQuestSlots(3_000L), "próg 3000: wszystkie");
        assertEquals(8, svc.unlockedQuestSlots(299L), "299 to jeszcze pierwszy tier");
    }

    @Test
    void awardIsIdempotentByOperationId() {
        StubDao dao = new StubDao();
        DefaultSeasonPointService svc = service(dao, 30);
        UUID p = UUID.randomUUID();

        assertTrue(svc.award(p, 100L, "quest-1").join());
        assertFalse(svc.award(p, 100L, "quest-1").join(), "ten sam operationId nie nabija ponownie");
        assertEquals(100L, svc.pointsOf(p).join());
    }

    @Test
    void catchUpMultipliesPointsInLastTwoWeeks() {
        StubDao dao = new StubDao();
        // koniec sezonu za 7 dni → catch-up aktywny
        DefaultSeasonPointService svc = service(dao, 7);
        assertTrue(svc.isCatchUpActive());

        UUID p = UUID.randomUUID();
        svc.award(p, 100L, "q-catch").join();
        assertEquals(120L, svc.pointsOf(p).join(), "+20% w końcówce sezonu");
    }

    @Test
    void noCatchUpMultiplierMidSeason() {
        StubDao dao = new StubDao();
        DefaultSeasonPointService svc = service(dao, 30); // 30 dni do końca
        assertFalse(svc.isCatchUpActive());

        UUID p = UUID.randomUUID();
        svc.award(p, 100L, "q-mid").join();
        assertEquals(100L, svc.pointsOf(p).join(), "środek sezonu bez mnożnika");
    }

    @Test
    void zeroOrNegativeAwardRejected() {
        DefaultSeasonPointService svc = service(new StubDao(), 30);
        assertFalse(svc.award(UUID.randomUUID(), 0L, "zero").join());
        assertFalse(svc.award(UUID.randomUUID(), -5L, "negative").join());
    }

    @Test
    void topOrdersByPointsDescending() {
        StubDao dao = new StubDao();
        DefaultSeasonPointService svc = service(dao, 30);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        svc.award(a, 100L, "a1").join();
        svc.award(b, 500L, "b1").join();
        svc.award(c, 250L, "c1").join();

        List<SeasonPointDao.TopRow> top = svc.top(3).join();
        assertEquals(b, top.get(0).playerId());
        assertEquals(c, top.get(1).playerId());
        assertEquals(a, top.get(2).playerId());
    }

    @Test
    void weekendMultiplierAppliesOnSaturday() {
        StubDao dao = new StubDao();
        DefaultSeasonPointService svc = weekendService(dao, 2.0D, java.time.DayOfWeek.SATURDAY);
        assertTrue(svc.isWeekendBonusActive());

        UUID p = UUID.randomUUID();
        svc.award(p, 100L, "q-weekend").join();
        assertEquals(200L, svc.pointsOf(p).join(), "sobota ×2.0");
    }

    @Test
    void weekendMultiplierIgnoredOnMondayAndWhenOff() {
        StubDao mondayDao = new StubDao();
        DefaultSeasonPointService monday = weekendService(mondayDao, 2.0D, java.time.DayOfWeek.MONDAY);
        assertFalse(monday.isWeekendBonusActive());
        monday.award(UUID.randomUUID(), 100L, "q-mon").join();
        assertEquals(100L, mondayDao.points.entrySet().iterator().next().getValue()[0], "poniedziałek bez bonusu");

        StubDao offDao = new StubDao();
        DefaultSeasonPointService off = weekendService(offDao, 1.0D, java.time.DayOfWeek.SUNDAY);
        assertFalse(off.isWeekendBonusActive(), "mnożnik 1.0 = wyłączony nawet w niedzielę");
    }

    @Test
    void weekendStacksWithCatchUp() {
        StubDao dao = new StubDao();
        // koniec sezonu za 7 dni → catch-up 1.2; sobota → weekend 2.0; 100 → 240
        DefaultSeasonPointService svc = new DefaultSeasonPointService(dao, () -> SEASON,
                () -> System.currentTimeMillis() + 7L * 86_400_000L,
                ZoneId.of("Europe/Warsaw"), 2.0D, () -> java.time.DayOfWeek.SATURDAY);
        assertTrue(svc.isWeekendBonusActive());
        assertTrue(svc.isCatchUpActive());

        UUID p = UUID.randomUUID();
        svc.award(p, 100L, "q-both").join();
        assertEquals(240L, svc.pointsOf(p).join(), "mnożniki się składają: 2.0 × 1.2");
    }
}
