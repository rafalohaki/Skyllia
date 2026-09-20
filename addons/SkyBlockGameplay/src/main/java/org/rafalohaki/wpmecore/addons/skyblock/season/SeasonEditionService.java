package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holder cache'a aktywacji edycji ({@code wpme_sb_season_editions}).
 *
 * <p>Bezpieczeństwo wątków / widoczność pamięci: odczyty
 * ({@link #currentCached(int)}, {@link #publicHeaderLabel(long)}) wolno wołać
 * z wątku encji — operują wyłącznie na mapie {@code ConcurrentHashMap}
 * (happens-before między {@code put}/{@code get}) przechowującej niezmienne
 * rekordy {@code Row}, więc publikacja jest bezpieczna bez dodatkowych
 * barier. Pozostałe pola współdzielone ({@code registry},
 * {@code legacyLabelFallback}, {@code seasonIdSupplier}) są {@code volatile}:
 * czytane raz do lokalnej referencji przed użyciem, żeby jeden odczyt widział
 * jeden spójny obiekt. SQL (refresh/insert) dzieje się poza wątkiem encji,
 * w łańcuchach {@code SqlService}.
 *
 * <p>Podmiana rejestru w locie ({@link #swapRegistry(EditionRegistry)}) to
 * zwykły volatile write: każdy czytelnik widzi albo STARY, albo NOWY kompletny
 * rejestr (nigdy stan pośredni); cache aktywacji zostaje celowo zachowany —
 * opisuje on historię zapisaną w DB, nie zawartość editions.yml. Wywoławca
 * odpala po zamianie {@link #refreshActivation(long)} sam.
 *
 * <p>{@link #refreshActivation(long)} musi być odporny na złe dane: wyjątek
 * z parsowania (np. {@link IllegalArgumentException}) kończy się wpisem
 * audytowym SEVERE i pustym ukończeniem — nigdy śmiercią wątku timera 60 s.
 *
 * <p>Podgrzanie cache następuje przy budowie asynchronicznie (wzorzec
 * {@code SkyBlockTopRewardCoordinator.initSeasonCache}: bez blokującego
 * {@code join()} na main threadzie; błąd = warning i pusta mapa).
 */
public final class SeasonEditionService {

    private static final Logger LOG =
            Logger.getLogger(SeasonEditionService.class.getName());

    private volatile EditionRegistry registry;
    private final Map<Integer, SeasonEditionDao.Row> cache = new ConcurrentHashMap<>();
    private final SeasonEditionDao dao;

    /**
     * Legacy fallback dla nagłówka (arytmetyka {@link SeasonSchedule#label()}).
     * Referencja nie wchodzi do konstruktora, bo kalendarz żyje w warstwie
     * plugina — wiring ustawia go raz po utworzeniu serwisu.
     */
    private volatile @Nullable LongFunction<String> legacyLabelFallback;

    /**
     * Dostawca bieżącego id sezonu (zwykle z {@code wpme_sb_season_state}).
     * Gdy ustawiony, {@link #refreshActivation(long)} wiąże edycję także
     * z BIEŻĄCYM sezonem — konieczne po rolloverze, bo NOWY id nie ma jeszcze
     * żadnego wiersza i nie wyskoczy z {@code latestPerSeason()}.
     */
    private volatile @Nullable java.util.function.IntSupplier seasonIdSupplier;

    public SeasonEditionService(@NotNull SeasonEditionDao dao,
                                @NotNull EditionRegistry registry) {
        this.dao = dao;
        this.registry = registry;
        // Podgrzanie cache (asynchronicznie, jak initSeasonCache — bez join()).
        dao.latestPerSeason().thenAccept(cache::putAll).exceptionally(err -> {
            LOG.warning("edycje sezonów: nie udało się podgrzać cache aktywacji: "
                    + err.getMessage());
            return null;
        });
    }

    /** Ustawia dostawcę bieżącego id sezonu (wiring po konstrukcji). */
    public void setSeasonIdSupplier(@Nullable java.util.function.IntSupplier supplier) {
        this.seasonIdSupplier = supplier;
    }

    /**
     * Ustawia legacy fallback nagłówka (zwykle {@code schedule::label});
     * null wraca do pustego nagłówka.
     */
    public void setLegacyLabelFallback(@Nullable LongFunction<String> fallback) {
        this.legacyLabelFallback = fallback;
    }

    /**
     * Podmienia rejestr edycji na świeżo zbudowany (np. po re-read
     * {@code editions.yml}). Volatile write: czytelnicy widzą albo stary,
     * albo nowy kompletny rejestr. Cache aktywacji NIE jest czyszczony —
     * odzwierciedla wiersze w DB, nie plik konfiguracyjny. Wywoływany z
     * GUI reload (slot 51) / CLI; wywoławca odpala potem
     * {@link #refreshActivation(long)}.
     */
    public void swapRegistry(@NotNull EditionRegistry fresh) {
        this.registry = fresh;
    }

    /**
     * Idempotentne odświeżenie aktywacji: gdy edycja aktywna w rejestrze
     * różni się od ostatnio zapisanej dla znanego sezonu — dopisuje wiersz
     * (append-only) i podmienia snapshot w cache.
     *
     * <p>BIEŻĄCY sezon (jeśli dostawca id ustawiony) dostaje wiersz nawet,
     * gdy nie ma jeszcze żadnego — domyka rollover do nowej numeracji.
     */
    public void refreshActivation(long nowMillis) {
        dao.latestPerSeason().thenCompose(latest -> {
            try {
                cache.putAll(latest);
                // Jeden odczyt volatile = jedna spójna wersja rejestru dla
                // całego przebiegu (nawet gdy swapRegistry przyjdzie w trakcie).
                EditionRegistry snapshot = registry;
                Edition active = snapshot.currentAt(nowMillis);
                if (active == null || !snapshot.isEnabled()) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                List<CompletableFuture<Void>> writes = new ArrayList<>();
                writeIfChanged(latest, active, nowMillis, writes);
                java.util.function.IntSupplier sidSupplier = seasonIdSupplier;
                if (sidSupplier != null) {
                    int currentSid = sidSupplier.getAsInt();
                    SeasonEditionDao.Row cached = cache.get(currentSid);
                    if (cached == null || !active.slug().equals(cached.slug())) {
                        writes.add(dao.recordActivation(currentSid, active, nowMillis)
                                .thenRun(() -> cache.put(currentSid,
                                        snapshotRow(currentSid, active, nowMillis)))
                                .exceptionally(err -> {
                                    LOG.warning("edycje sezonów: zapis aktywacji S"
                                            + currentSid + " nieudany: " + err.getMessage());
                                    return null;
                                }));
                    }
                }
                return CompletableFuture.allOf(
                        writes.toArray(CompletableFuture[]::new));
            } catch (IllegalArgumentException badData) {
                // Defensywnie: rejestr jest już zbudowany i zwalidowany przy
                // budowie, więc IAE tu nie powinno się zdarzyć — ale złe dane
                // (np. przyszła regresja parsowania) NIE mogą ubić wątku timera
                // 60 s. Wpis audytowy SEVERE + pustka zamiast propagacji.
                LOG.log(Level.SEVERE,
                        "edycje sezonów: refreshActivation odrzucony — złe dane: "
                                + badData.getMessage(),
                        badData);
                return CompletableFuture.<Void>completedFuture(null);
            }
        }).exceptionally(err -> {
            LOG.warning("edycje sezonów: refreshActivation nieudany: " + err.getMessage());
            return null;
        });
    }

    private void writeIfChanged(@NotNull Map<Integer, SeasonEditionDao.Row> snapshot,
                                @NotNull Edition active, long nowMillis,
                                @NotNull List<CompletableFuture<Void>> writes) {
        for (Map.Entry<Integer, SeasonEditionDao.Row> entry : snapshot.entrySet()) {
            int seasonId = entry.getKey();
            SeasonEditionDao.Row row = entry.getValue();
            if (sameEditionMetadata(row, active)) {
                continue;
            }
            writes.add(dao.recordActivation(seasonId, active, nowMillis)
                    .thenRun(() -> cache.put(seasonId, new SeasonEditionDao.Row(
                            seasonId, nowMillis, active.slug(), active.displayName(),
                            active.type(), isoDay(active.startInclusiveMillis()),
                            isoDayEnd(active.endExclusiveMillis()), 0L)))
                    .exceptionally(err -> {
                        LOG.warning("edycje sezonów: zapis aktywacji S" + seasonId
                                + " nieudany: " + err.getMessage());
                        return null;
                    }));
        }
    }

    /**
     * Czy wiersz księgi opisuje DOKŁADNIE tę samą edycję co rejestr z configu?
     *
     * <p>Porównanie po samym {@code slug} było defektem (znalezionym 2026-09-12 na
     * produkcji): gdy operator zmienił w {@code editions.yml} daty albo typ edycji,
     * a slug został ten sam („szkolny-2026-27"), księga <b>nigdy</b> nie dostała
     * nowego wiersza — więc cokolwiek czyta wiersz, a nie rejestr
     * ({@link #currentCached} → menu kosmetyków i {@code %wpme_season_name%}),
     * pokazywało graczom nieaktualny zakres i typ. Zmierzone: prod miał
     * „EVENT / 01.09–27.10", a config „REGULAR / 01.09–31.10".
     *
     * <p>Porównujemy więc cały zestaw pól, które księga przechowuje, używając tych
     * samych konwersji co zapis ({@link #isoDay(long)} i {@link #isoDayEnd(long)} —
     * data końcowa w rejestrze jest wyłączna, w wierszu włącznie).
     */
    private static boolean sameEditionMetadata(@NotNull SeasonEditionDao.Row row,
                                               @NotNull Edition active) {
        return active.slug().equals(row.slug())
                && active.displayName().equals(row.displayName())
                && active.type() == row.type()
                && isoDay(active.startInclusiveMillis()).equals(row.startDate())
                && isoDayEnd(active.endExclusiveMillis()).equals(row.endDate());
    }

    private static @NotNull SeasonEditionDao.Row snapshotRow(
            int seasonId, @NotNull Edition active, long nowMillis) {
        return new SeasonEditionDao.Row(seasonId, nowMillis, active.slug(),
                active.displayName(), active.type(),
                isoDay(active.startInclusiveMillis()),
                isoDayEnd(active.endExclusiveMillis()), 0L);
    }

    /**
     * Ostatnia zapisana edycja sezonu jako obiekt {@link Edition}
     * (daty z wiersza przeliczone na granice UTC); brak/zepsuty wiersz → null.
     *
     * <p>Bezpieczne z dowolnego wątku: {@code cache} jest
     * {@code ConcurrentHashMap} (happens-before put→get), a {@code Row}
     * to niezmienny rekord — referencja odczytana raz, więc widziana jest
     * albo pełna stara, albo pełna nowa wartość; nigdy częściowa.
     */
    public @Nullable Edition currentCached(int seasonId) {
        SeasonEditionDao.Row row = cache.get(seasonId);
        if (row == null || row.slug().isEmpty()) {
            return null;
        }
        try {
            long start = LocalDate.parse(row.startDate())
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long endExclusive = LocalDate.parse(row.endDate()).plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            return new Edition(row.slug(), row.displayName(), row.type(),
                    start, endExclusive);
        } catch (DateTimeParseException | NullPointerException corrupt) {
            LOG.warning("edycje sezonów: uszkodzony wiersz S" + seasonId
                    + " pominięty: " + corrupt.getMessage());
            return null;
        }
    }

    /**
     * Publiczna etykieta nagłówka: nazwa aktywnej edycji → legacy math
     * ({@link SeasonSchedule#label()} przez supplier) → pusty string.
     */
    public @NotNull String publicHeaderLabel(long nowMillis) {
        String label = registry.labelAt(nowMillis);
        if (label != null) {
            return label;
        }
        if (registry.isEnabled()) {
            // Luka w kalendarzu przy włączonych edycjach: pusty nagłówek
            // zamiast nieaktualnego zakresu z legacy arytmetyki.
            return "";
        }
        LongFunction<String> legacy = legacyLabelFallback;
        String legacyLabel = legacy != null ? legacy.apply(nowMillis) : null;
        return legacyLabel != null ? legacyLabel : "";
    }

    private static @NotNull String isoDay(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    private static @NotNull String isoDayEnd(long endExclusiveMillis) {
        return Instant.ofEpochMilli(endExclusiveMillis)
                .atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).toString();
    }
}
