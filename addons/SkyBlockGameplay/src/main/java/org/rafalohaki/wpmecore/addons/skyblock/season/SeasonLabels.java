package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Widok gracza: edycja kolekcji jako ZAKRES DAT sezonu (decyzja operatora
 * 2026-08-25) — publicznie zero numerków „S&lt;n&gt;”. Wewnętrzny marker PDC
 * ({@code ItemPolicyMarkers} / {@code CosmeticCatalog.editionOf}) zostaje
 * bez zmian: dedupe i kolekcje nadal kluczą po „S&lt;n&gt;”.
 *
 * <p><b>Decyzja projektowa — arytmetyka.</b> Celowo NIE importujemy
 * {@code SeasonSchedule} (plik w innej strefie własności): duplikujemy tu
 * minimalną arytmetykę {@code epoch-start + długość w dniach} i przyjmujemy
 * oba parametry jako dane wejściowe ({@link #editionText}) albo czytamy je
 * z sekcji {@code season} config.yml ({@link #window}). Semantyka zgodna z
 * runtime: licznik sezonów w bazie startuje od 1 i rośnie przy każdym
 * zamknięciu, więc sezon N trwa
 * {@code [epoch + (N-1)*L, epoch + N*L)}. Data końcowa wyświetlana jest jako
 * granica końca sezonu (dzień po ostatnim dniu gry) — dokładnie jak w
 * przykładzie operatorkim: 01.09 + 56 dni → „01.09 – 27.10.2026”.
 */
public final class SeasonLabels {

    /** Ta sama stała co w SeasonSchedule — duplikat celowy, patrz nagłówek klasy. */
    private static final long DAY_MILLIS = 24L * 3_600_000L;

    private static final DateTimeFormatter DAY_MONTH =
            DateTimeFormatter.ofPattern("dd.MM").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FULL_DATE =
            DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneOffset.UTC);

    /** Okno kalendarzowe sezonu odczytane z sekcji {@code season} config.yml. */
    public record SeasonWindow(long epochStartMillis, int lengthDays) {
    }

    private SeasonLabels() {
    }

    /**
     * Zakres dat sezonu dla gracza, np. „01.09 – 27.10.2026”. Rok drukowany
     * raz, na końcu zakresu; przy przekroczeniu roku na obu końcach.
     * Zwraca „?” gdy dane wejściowe nie pozwalają wyliczyć okna
     * (sezon kalendarzowy off / zły numer) — fail-open bez ujawniania S&lt;n&gt;.
     */
    public static @NotNull String editionText(int seasonId,
                                              long epochStartMillis,
                                              int lengthDays) {
        if (seasonId < 1 || lengthDays <= 0 || epochStartMillis <= 0L) {
            return "?";
        }
        long startMillis = epochStartMillis + (long) (seasonId - 1) * lengthDays * DAY_MILLIS;
        long endMillis = startMillis + (long) lengthDays * DAY_MILLIS;
        LocalDate start = Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end = Instant.ofEpochMilli(endMillis).atZone(ZoneOffset.UTC).toLocalDate();
        String endText = FULL_DATE.format(end);
        String startText = start.getYear() == end.getYear()
                ? DAY_MONTH.format(start)
                : FULL_DATE.format(start);
        return startText + " – " + endText;
    }

    /**
     * Numer sezonu ze znacznika PDC „S&lt;n&gt;”; {@code null}, gdy marker
     * nieobecny albo w obcym formacie (np. legacy „2026-S1”).
     */
    public static @Nullable Integer seasonNumberOf(@Nullable String editionMarker) {
        if (editionMarker == null || editionMarker.length() < 2
                || editionMarker.charAt(0) != 'S') {
            return null;
        }
        try {
            return Integer.valueOf(editionMarker.substring(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Czyta {@code epoch-start} (ISO-8601 UTC, jak w SeasonSchedule) oraz
     * {@code length-days} z sekcji {@code season}; {@code null}, gdy sekcja
     * nieobecna, wyłączona albo zepsuta — wywoławca pokazuje wtedy „?”.
     */
    public static @Nullable SeasonWindow window(@Nullable ConfigurationSection seasonSection) {
        if (seasonSection == null || !seasonSection.getBoolean("enabled", false)) {
            return null;
        }
        int lengthDays = seasonSection.getInt("length-days", -1);
        if (lengthDays <= 0) {
            return null;
        }
        final Instant epochStart;
        try {
            epochStart = Instant.parse(seasonSection.getString("epoch-start", ""));
        } catch (java.time.format.DateTimeParseException | NullPointerException ignored) {
            return null;
        }
        return new SeasonWindow(epochStart.toEpochMilli(), lengthDays);
    }

    /**
     * Zakres dat edycji dla numeru sezonu z sekcji {@code season} config.yml;
     * {@code null}, gdy numer jest zły albo kalendarz wyłączony/zepsuty —
     * UI pokazuje wtedy komunikat bez zakresu (fallback „null = bez zakresu”,
     * decyzja operatora 2026-08-25).
     */
    public static @Nullable String windowText(int seasonId,
                                              @Nullable ConfigurationSection seasonSection) {
        if (seasonId < 1) {
            return null;
        }
        SeasonWindow window = window(seasonSection);
        if (window == null) {
            return null;
        }
        return editionText(seasonId, window.epochStartMillis(), window.lengthDays());
    }

    /**
     * Zakres dat nazwanej edycji w formacie operatora
     * {@code dd.MM.yyyy – dd.MM.yyyy} (dzień końcowy WŁĄCZNIE — granica
     * wyłączna rekordu jest cofana o jeden dzień). {@code null}, gdy edycja
     * {@code null} albo okno nie pozwala się wyrenderować — wywoławca
     * pomija wtedy linię zakresu (fail-open jak w {@link #editionText}).
     * Stałe formatujące współdzielone z matematyką legacy.
     */
    public static @Nullable String rangeFor(@Nullable Edition edition) {
        if (edition == null) {
            return null;
        }
        long startMillis = edition.startInclusiveMillis();
        long endExclusiveMillis = edition.endExclusiveMillis();
        if (startMillis <= 0L || endExclusiveMillis <= startMillis) {
            return null;
        }
        LocalDate start = Instant.ofEpochMilli(startMillis)
                .atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end = Instant.ofEpochMilli(endExclusiveMillis - DAY_MILLIS)
                .atZone(ZoneOffset.UTC).toLocalDate();
        return FULL_DATE.format(start) + " – " + FULL_DATE.format(end);
    }
}
