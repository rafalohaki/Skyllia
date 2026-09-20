package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
/**
 * M2.5: kalendarz sezonów z config.yml (sekcja {@code season}).
 *
 * <p>Jedyne miejsce wyliczania końca sezonu: {@code epoch-start + length-days}.
 * Deterministyczne — wynik nie zależy od zegara w chwili wywołania, więc
 * /sezon i DefaultSeasonPointService widzą ten sam koniec sezonu.
 *
 * <p>Fail-closed przy enable: długość poza 42..70 albo unparsowalny epoch-start
 * rzuca {@link IllegalArgumentException} (wzorzec jak CosmeticCatalog /
 * MinionsConfig / ForgeConfig). Sekcja nieobecna lub {@code enabled:false}
 * oznacza sezon off — warstwa wyżej zachowuje dotychczasowe zachowanie pilotowe.
 */
public final class SeasonSchedule {

    /** Dozwolona długość sezonu w dniach (config.yml: validator 42..70). */
    static final int MIN_LENGTH_DAYS = 42;
    static final int MAX_LENGTH_DAYS = 70;
    /** Długość używana wyłącznie przez tryb disabled (zachowanie pilotowe). */
    static final int PILOT_LENGTH_DAYS = 56;
    /** Format daty etykiety: sztywne kropki, Locale neutralny (ROOT). */
    private static final DateTimeFormatter LABEL_DAY =
            DateTimeFormatter.ofPattern("dd.MM.yyyy", java.util.Locale.ROOT);

    private static final long DAY_MILLIS = 24L * 3_600_000L;

    private final boolean enabled;
    private final long epochStartMillis;
    private final int lengthDays;

    private SeasonSchedule(boolean enabled, long epochStartMillis, int lengthDays) {
        this.enabled = enabled;
        this.epochStartMillis = epochStartMillis;
        this.lengthDays = lengthDays;
    }

    /** Sezon off — brak kalendarza; wywoławcy wracają do zachowania pilotowego. */
    public static @NotNull SeasonSchedule disabled() {
        return new SeasonSchedule(false, 0L, PILOT_LENGTH_DAYS);
    }

    /**
     * Parsuje sekcję {@code season}. Sekcja nieobecna lub {@code enabled:false}
     * → {@link #disabled()}. Zła długość albo format daty → IllegalArgumentException.
     */
    public static @NotNull SeasonSchedule load(@Nullable ConfigurationSection section) {
        if (section == null || !section.getBoolean("enabled", false)) {
            return disabled();
        }
        int lengthDays = section.getInt("length-days", -1);
        if (lengthDays < MIN_LENGTH_DAYS || lengthDays > MAX_LENGTH_DAYS) {
            throw new IllegalArgumentException("season.length-days musi być w zakresie "
                    + MIN_LENGTH_DAYS + ".." + MAX_LENGTH_DAYS + ", było '" + lengthDays + "'");
        }
        String rawEpoch = section.getString("epoch-start", "");
        final Instant epochStart;
        try {
            epochStart = Instant.parse(rawEpoch);
        } catch (DateTimeParseException | NullPointerException failure) {
            throw new IllegalArgumentException("season.epoch-start musi być ISO-8601 UTC"
                    + " (np. '2026-09-01T00:00:00Z'), było '" + rawEpoch + "'", failure);
        }
        return new SeasonSchedule(true, epochStart.toEpochMilli(), lengthDays);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Jedyna metoda licząca koniec sezonu — epoch-start + length-days,
     * niezależnie od zegara wywołania.
     */
    public long seasonEndEpochMillis() {
        return epochStartMillis + (long) lengthDays * DAY_MILLIS;
    }

    /**
     * Wspólna arytmetyka „ile dni do końca sezonu” dla KAŻDEGO komunikatu
     * z licznikiem dni (/sezon nagłówek, dry-run {@code /sezon zamknij}):
     * semantyka sufitu — niepełny dzień liczy się jako pełny dzień, a w samej
     * chwili końca (i później) zostaje 0. Jedno źródło prawdy, żeby nagłówek
     * i dry-run nigdy nie rozjechały się o jeden dzień (lab 2026-08-25:
     * „Koniec za 62 dni” vs „zostało 63 dni” w tej samej sekundzie).
     */
    public static long daysUntil(long endMillis, long nowMillis) {
        long remaining = endMillis - nowMillis;
        if (remaining <= 0L) {
            return 0L;
        }
        return (remaining + DAY_MILLIS - 1) / DAY_MILLIS;
    }

    /**
     * Publiczna etykieta sezonu jako zakres dat „dd.MM.yyyy – dd.MM.yyyy”
     * (decyzja operatora 2026-08-25: zero numerków sezonu w UI — numeracja
     * zostaje wyłącznie wewnętrznie, w DB/PDC/ledgerze). Daty liczone w UTC,
     * więc wynik nie zależy od strefy serwera; separator to sztywny „ – ”.
     *
     * <p>Data końcowa to dzień momentu {@link #seasonEndEpochMillis()} (granica
     * rozłącznie) — zgodnie z przykładem specyfikacji: epoch 01.09 + 56 dni
     * → „01.09.2026 – 27.10.2026”. Sezon off → {@code null}; wywoławca pokazuje
     * wtedy UI bez zakresu dat.
     */
    public @Nullable String label() {
        if (!enabled) {
            return null;
        }
        LocalDate start = Instant.ofEpochMilli(epochStartMillis)
                .atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end = Instant.ofEpochMilli(seasonEndEpochMillis())
                .atZone(ZoneOffset.UTC).toLocalDate();
        return LABEL_DAY.format(start) + " – " + LABEL_DAY.format(end);
    }
}
