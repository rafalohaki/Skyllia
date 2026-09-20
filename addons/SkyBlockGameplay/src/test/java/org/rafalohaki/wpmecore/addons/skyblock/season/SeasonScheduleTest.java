package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2.5: kalendarz sezonów — koniec sezonu wyliczany deterministycznie
 * z config.yml (epoch-start + length-days), walidacja fail-closed 42..70,
 * catch-up liczony względem prawdziwego końca sezonu (nie "teraz").
 */
class SeasonScheduleTest {

    private static YamlConfiguration seasonConfig(int lengthDays, String epoch) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("enabled", true);
        config.set("length-days", lengthDays);
        config.set("epoch-start", epoch);
        return config;
    }

    @Test
    void endIsEpochPlusLengthRegardlessOfClock() {
        SeasonSchedule schedule = SeasonSchedule.load(
                seasonConfig(56, "2026-09-01T00:00:00Z"));
        long expected = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()
                + 56L * 86_400_000L;

        assertEquals(expected, schedule.seasonEndEpochMillis());
        // dwie odczyty identyczne — brak "now" w obliczeniu
        assertEquals(schedule.seasonEndEpochMillis(), schedule.seasonEndEpochMillis());
        assertTrue(schedule.isEnabled());
    }

    @Test
    void labelIsDateRangeWithHardDotsAndUtcDeterminism() {
        SeasonSchedule schedule = SeasonSchedule.load(
                seasonConfig(56, "2026-09-01T00:00:00Z"));
        // przykład specyfikacji: epoch 01.09 + 56 dni → zakres dd.MM.yyyy – dd.MM.yyyy
        assertEquals("01.09.2026 – 27.10.2026", schedule.label());
        // deterministyczne — wynik nie zależy od zegara wywołania
        assertEquals(schedule.label(), schedule.label());
    }

    @Test
    void labelIsNullWhenSeasonOff() {
        assertNull(SeasonSchedule.load(null).label(), "sezon off = brak etykiety");
        YamlConfiguration disabled = seasonConfig(56, "2026-09-01T00:00:00Z");
        disabled.set("enabled", false);
        assertNull(SeasonSchedule.load(disabled).label(),
                "enabled:false = brak etykiety (UI bez zakresu dat)");
    }

    @Test
    void labelRollsOverWithSeasonNumberInternally() {
        // kolejny sezon (wewnętrznie N+1) przesuwa zakres o length-days
        SeasonSchedule first = SeasonSchedule.load(seasonConfig(56, "2026-09-01T00:00:00Z"));
        SeasonSchedule second = SeasonSchedule.load(
                seasonConfig(56, "2026-10-27T00:00:00Z"));
        assertEquals("01.09.2026 – 27.10.2026", first.label());
        assertEquals("27.10.2026 – 22.12.2026", second.label());
    }

    @Test
    void missingOrDisabledSectionMeansSeasonOff() {
        assertFalse(SeasonSchedule.load(null).isEnabled(), "brak sekcji = sezon off");
        assertFalse(SeasonSchedule.load(new YamlConfiguration()).isEnabled(),
                "pusta sekcja = sezon off");

        YamlConfiguration disabled = seasonConfig(56, "2026-09-01T00:00:00Z");
        disabled.set("enabled", false);
        assertFalse(SeasonSchedule.load(disabled).isEnabled(), "enabled:false = sezon off");
    }

    @Test
    void rejectsLengthBelowMinimum() {
        assertThrows(IllegalArgumentException.class,
                () -> SeasonSchedule.load(seasonConfig(41, "2026-09-01T00:00:00Z")));
    }

    @Test
    void rejectsLengthAboveMaximum() {
        assertThrows(IllegalArgumentException.class,
                () -> SeasonSchedule.load(seasonConfig(71, "2026-09-01T00:00:00Z")));
    }

    @Test
    void boundariesAreInclusive() {
        assertDoesNotThrow(() -> SeasonSchedule.load(seasonConfig(42, "2026-09-01T00:00:00Z")));
        assertDoesNotThrow(() -> SeasonSchedule.load(seasonConfig(70, "2026-09-01T00:00:00Z")));
    }

    @Test
    void rejectsUnparsableEpochStart() {
        assertThrows(IllegalArgumentException.class,
                () -> SeasonSchedule.load(seasonConfig(56, "wrzesien-2026")));
        assertThrows(IllegalArgumentException.class,
                () -> SeasonSchedule.load(seasonConfig(56, "2026-13-40T99:00:00Z")));
        assertThrows(IllegalArgumentException.class,
                () -> SeasonSchedule.load(seasonConfig(56, "")));
    }

    // ── catch-up względem kalendarzowego końca sezonu ───────────────────

    /** DAO-nioperka: isCatchUpActive nie dotyka bazy. */
    private static final class NoopDao implements SeasonPointDao {
        @Override
        public @NotNull CompletableFuture<Boolean> addPoints(@NotNull UUID u, int season,
                long pts, @NotNull String op, boolean quest) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public @NotNull CompletableFuture<Long> points(@NotNull UUID u, int season) {
            return CompletableFuture.completedFuture(0L);
        }

        @Override
        public @NotNull CompletableFuture<Integer> questsDone(@NotNull UUID u, int season) {
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public @NotNull CompletableFuture<Optional<Integer>> rank(@NotNull UUID u, int season) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public @NotNull CompletableFuture<List<TopRow>> top(int season, int limit) {
            return CompletableFuture.completedFuture(List.of());
        }

    }

    private DefaultSeasonPointService serviceWithSchedule(SeasonSchedule schedule) {
        // stub suppliera zegara: serwis dostaje wyłącznie schedule::seasonEndEpochMillis
        return new DefaultSeasonPointService(new NoopDao(), () -> 3,
                schedule::seasonEndEpochMillis, ZoneId.of("UTC"));
    }

    @Test
    void noCatchUpWhenMoreThanFourteenDaysLeftToCalendarEnd() {
        // epoch-start wczoraj, długość 70 → koniec za ~69 dni (daleko od catch-up)
        String epoch = Instant.now().minus(Duration.ofDays(1)).toString();
        DefaultSeasonPointService svc = serviceWithSchedule(
                SeasonSchedule.load(seasonConfig(70, epoch)));

        assertFalse(svc.isCatchUpActive(), ">14 dni do końca = bez mnożnika");
    }

    @Test
    void catchUpActiveWhenCalendarEndWithinFourteenDays() {
        // epoch-start 50 dni temu, długość 56 → koniec za ~6 dni (catch-up)
        String epoch = Instant.now().minus(Duration.ofDays(50)).toString();
        DefaultSeasonPointService svc = serviceWithSchedule(
                SeasonSchedule.load(seasonConfig(56, epoch)));

        assertTrue(svc.isCatchUpActive(), "<14 dni do końca = +20% punktów");
    }

    @Test
    void shippedConfigYamlParsesAndValidates() throws Exception {
        try (var reader = new java.io.InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config.yml"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            assertNotNull(reader, "brak wysyłanego config.yml na classpath testów");
            YamlConfiguration shipped = YamlConfiguration.loadConfiguration(reader);
            SeasonSchedule schedule = SeasonSchedule.load(
                    shipped.getConfigurationSection("season"));

            assertTrue(schedule.isEnabled(), "wysyłany config ma sezon włączony");
            assertEquals(Instant.parse("2026-09-05T00:00:00Z").toEpochMilli()
                            + 56L * 86_400_000L,
                    schedule.seasonEndEpochMillis(),
                    "koniec sezonu #0 wg wysłanego configu");
        }
    }
}
