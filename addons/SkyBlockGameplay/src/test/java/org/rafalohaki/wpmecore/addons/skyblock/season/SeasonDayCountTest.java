package org.rafalohaki.wpmecore.addons.skyblock.season;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonPointDao.TopRow;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Lab 2026-08-25: /sezon (nagłówek, semantyka podłogi) i /sezon zamknij
 * dry-run (ceilDays) rozjechały się o jeden dzień w tej samej sekundzie
 * („Koniec za 62 dni” vs „zostało 63 dni”). Oba komunikaty muszą iść przez
 * JEDNĄ arytmetykę — {@link SeasonSchedule#daysUntil(long, long)} z
 * semantyką sufitu — i zgadzać się na granicach: koniec − 1 ms → 1 dzień,
 * koniec → 0 dni / „wygasł”.
 */
class SeasonDayCountTest {

    private static final long DAY_MILLIS = 24L * 3_600_000L;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    // ── wspólna arytmetyka: granice ─────────────────────────────────────

    @Test
    void oneMillisecondBeforeEndCountsAsFullDay() {
        long end = Instant.parse("2026-10-27T00:00:00Z").toEpochMilli();
        assertEquals(1L, SeasonSchedule.daysUntil(end, end - 1L));
    }

    @Test
    void exactlyAtEndIsZeroDays() {
        long end = Instant.parse("2026-10-27T00:00:00Z").toEpochMilli();
        assertEquals(0L, SeasonSchedule.daysUntil(end, end));
    }

    @Test
    void afterEndStaysZero() {
        long end = Instant.parse("2026-10-27T00:00:00Z").toEpochMilli();
        assertEquals(0L, SeasonSchedule.daysUntil(end, end + DAY_MILLIS));
        assertEquals(0L, SeasonSchedule.daysUntil(end, end + 7L * DAY_MILLIS));
    }

    @Test
    void exactDayBoundariesDoNotRoundUpExtraDay() {
        long end = Instant.parse("2026-10-27T00:00:00Z").toEpochMilli();
        // dokładnie 1 dzień → 1; o 1 ms więcej → 2 (sufit)
        assertEquals(1L, SeasonSchedule.daysUntil(end, end - DAY_MILLIS));
        assertEquals(2L, SeasonSchedule.daysUntil(end, end - DAY_MILLIS - 1L));
    }

    @Test
    void fullSeasonLengthFromStartToEnd() {
        SeasonSchedule schedule = SeasonSchedule.load(SeasonEditionFixtures.yaml(
                "enabled: true\nlength-days: 56\nepoch-start: \"2026-09-01T00:00:00Z\"\n"));
        long start = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli();
        assertEquals(56L,
                SeasonSchedule.daysUntil(schedule.seasonEndEpochMillis(), start));
    }

    /** Sufit: niepełny dzień liczy się jako pełny (62 d 12 h → 63). */
    @Test
    void partialDayRoundsUp() {
        long end = Instant.parse("2026-10-27T00:00:00Z").toEpochMilli();
        assertEquals(63L,
                SeasonSchedule.daysUntil(end, end - 62L * DAY_MILLIS - 12L * 3_600_000L));
    }

    // ── nagłówek /sezon idzie przez tę samą arytmetykę ──────────────────

    @Test
    void headerShowsCeilDaysConsistentWithHelper() {
        long now = System.currentTimeMillis();
        // 62 d + 12 h do końca — sufit stabilnie 63 mimo jitteru zegara
        long end = now + 62L * DAY_MILLIS + 12L * 3_600_000L;

        List<Component> messages = renderHeader(end);

        String daysLine = MM.serialize(messages.get(1));
        assertTrue(daysLine.contains("Koniec za <white>63</white> dni."),
                "nagłówek ma pokazać 63 dni (sufit), a pokazuje: " + daysLine);
    }

    @Test
    void headerUnderOneDayLeftShowsOneDayNotExpired() {
        // show() czyta zegar wewnętrznie — sufitem symulujemy „mniej niż
        // dzień do końca”: minuta przed końcem to stabilnie 1 dzień
        // (podłoga pokazałaby 0), z tolerancją na jitter zegara.
        long now = System.currentTimeMillis();

        List<Component> messages = renderHeader(now + 60_000L);

        String daysLine = MM.serialize(messages.get(1));
        assertTrue(daysLine.contains("Koniec za <white>1</white> dni."),
                "przy końcu tuż-tuż nagłówek ma pokazać 1 dzień: " + daysLine);
        assertFalse(daysLine.contains("wygasł"));
    }

    // ── dry-run /sezon zamknij idzie przez tę samą arytmetykę ───────────

    @Test
    void dryRunRefusalUsesSameCeilDays() {
        long end = Instant.parse("2026-10-27T00:00:00Z").toEpochMilli();
        SeasonEndService service = new SeasonEndService(
                mock(org.rafalohaki.wpmecore.api.service.SqlService.class),
                () -> 2, () -> end, () -> 56L * DAY_MILLIS,
                (playerId, seasonId, rank) -> CompletableFuture.completedFuture(
                        SeasonEndService.CosmeticDispatcher.DispatchResult.DELIVERED),
                ZoneOffset.UTC, Logger.getLogger("test"));

        // koniec − 1 ms → sufit 1 dzień (podłoga powiedziałaby 0)
        SeasonEndService.CloseOutcome justBefore = service.close(end - 1L, false).join();
        assertTrue(justBefore.refused());
        assertTrue(justBefore.refusalReason().contains("zostało 1 dni"),
                "dry-run ma pokazać 1 dni na koniec − 1 ms: " + justBefore.refusalReason());

        // 26 godzin przed końcem → 2 dni
        SeasonEndService.CloseOutcome earlier =
                service.close(end - 26L * 3_600_000L, false).join();
        assertTrue(earlier.refused());
        assertTrue(earlier.refusalReason().contains("zostało 2 dni"),
                "dry-run ma pokazać 2 dni: " + earlier.refusalReason());
    }

    // ── helpery ─────────────────────────────────────────────────────────

    /**
     * Renderuje nagłówek /sezon (nadawca-mock, ranking pusty) i zwraca wysłane
     * komponenty: [0] etykieta, [1] linia „Koniec za N dni”, [2] TOP pusty.
     */
    private static List<Component> renderHeader(long seasonEndMillis) {
        CommandSender sender = mock(CommandSender.class);

        SeasonCommand cmd = new SeasonCommand(new StubPoints(List.of()),
                () -> seasonEndMillis,
                () -> "01.09.2026 – 27.10.2026",
                MM, id -> id.toString());
        cmd.show(sender);

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, times(3)).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    /** Statyczny serwis punktowy: gotowe odpowiedzi, zero Bukkit/DB. */
    private static final class StubPoints implements SeasonPointService {

        private final List<TopRow> rows;

        StubPoints(List<TopRow> rows) {
            this.rows = rows;
        }

        @Override public int currentSeason() { return 2; }

        @Override public @NotNull CompletableFuture<Boolean> award(
                @NotNull UUID u, long p, @NotNull String op) {
            return CompletableFuture.completedFuture(true);
        }

        @Override public @NotNull CompletableFuture<Long> pointsOf(@NotNull UUID u) {
            return CompletableFuture.completedFuture(0L);
        }

        @Override public @NotNull CompletableFuture<Optional<Integer>> rankOf(@NotNull UUID u) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override public @NotNull CompletableFuture<List<TopRow>> top(int limit) {
            return CompletableFuture.completedFuture(rows.stream().limit(limit).toList());
        }

        @Override public int unlockedQuestSlots(long seasonPoints) { return 0; }

        @Override public boolean isCatchUpActive() { return false; }
    }
}
