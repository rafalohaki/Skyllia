package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kontrakt serwisu aktywacji edycji (frozen): {@code refreshActivation}
 * dopisuje wiersz tylko przy zmianie sluga (idempotentnie), zamknięcie
 * znacznikuje wyłącznie otwarte wiersze danego sezonu, a nagłówek publiczny
 * schodzi edycja → legacy fallback → pusty string. DAO podmienione fałkiem —
 * zero SQL w teście.
 */
class SeasonEditionServiceTest {

    private static final DateTimeFormatter ISO_DAY =
            DateTimeFormatter.ofPattern("uuuu-MM-dd", java.util.Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private static final long T_JUNE = Instant.parse("2026-06-15T00:00:00Z").toEpochMilli();
    private static final long T_JUNE_LATER = Instant.parse("2026-06-20T00:00:00Z").toEpochMilli();
    private static final long T_EVENT = Instant.parse("2026-07-05T00:00:00Z").toEpochMilli();
    private static final long T_AFTER_EVENT = Instant.parse("2026-07-20T00:00:00Z").toEpochMilli();

    /** Lato z wkleszczonym EVENT-em wakacyjnym — pozwala przełączać aktywny slug czasem. */
    private static EditionRegistry summerWithEvent() {
        return SeasonEditionFixtures.loadRegistry("""
                enabled: true
                list:
                  - slug: lato-2026
                    display-name: "Sezon Letni 2026"
                    type: REGULAR
                    start: "2026-06-01"
                    end: "2026-08-31"
                  - slug: wakacyjny-2026
                    display-name: "Sezon Wakacyjny 2026"
                    type: EVENT
                    start: "2026-07-01"
                    end: "2026-07-14"
                """);
    }

    private static Edition editionAt(EditionRegistry registry, long nowMillis) {
        return java.util.Objects.requireNonNull(registry.currentAt(nowMillis));
    }

    /** Fałszywe DAO: wiersze w pamięci, semantyka zgodna z frozen interfejsem. */
    private static final class FakeDao implements SeasonEditionDao {

        private final List<Row> rows = new ArrayList<>();
        private int stampClosedCalls;

        @Override
        public @NotNull CompletableFuture<Map<Integer, Row>> latestPerSeason() {
            Map<Integer, Row> latest = new HashMap<>();
            for (Row row : rows) {
                Row current = latest.get(row.seasonId());
                if (current == null || row.activatedAt() >= current.activatedAt()) {
                    latest.put(row.seasonId(), row);
                }
            }
            return CompletableFuture.completedFuture(Map.copyOf(latest));
        }

        @Override
        public @NotNull CompletableFuture<Void> recordActivation(
                int seasonId, @NotNull Edition edition, long nowMillis) {
            rows.add(new Row(seasonId, nowMillis, edition.slug(), edition.displayName(),
                    edition.type(),
                    ISO_DAY.format(Instant.ofEpochMilli(edition.startInclusiveMillis())),
                    ISO_DAY.format(Instant.ofEpochMilli(edition.endExclusiveMillis() - 1)),
                    0L));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public @NotNull CompletableFuture<Integer> stampClosed(
                @Nullable Connection tx, int seasonId, long closedAtMillis) {
            stampClosedCalls++;
            int marked = 0;
            List<Row> updated = new ArrayList<>();
            for (Row row : rows) {
                if (row.seasonId() == seasonId && row.closedAt() == 0L) {
                    updated.add(new Row(row.seasonId(), row.activatedAt(), row.slug(),
                            row.displayName(), row.type(), row.startDate(), row.endDate(),
                            closedAtMillis));
                    marked++;
                } else {
                    updated.add(row);
                }
            }
            rows.clear();
            rows.addAll(updated);
            return CompletableFuture.completedFuture(marked);
        }

        private List<Row> ofSeason(int seasonId) {
            return rows.stream().filter(row -> row.seasonId() == seasonId).toList();
        }
    }

    private static SeasonEditionService service(FakeDao dao, EditionRegistry registry,
                                                int seasonId) {
        SeasonEditionService service = new SeasonEditionService(dao, registry);
        service.setSeasonIdSupplier(() -> seasonId);
        service.setLegacyLabelFallback(millis -> "legacy-zakres");
        return service;
    }

    /**
     * Zmiana SAMYCH METADANYCH edycji (slug bez zmian) musi trafić do księgi.
     *
     * <p>Regresja z produkcji (2026-09-12): {@code writeIfChanged} porównywał tylko
     * {@code slug}, więc po zmianie daty końca i typu w {@code editions.yml} księga
     * trzymała stare wartości — a czyta je {@code currentCached} (menu kosmetyków
     * gracza, {@code %wpme_season_name%}). Zmierzone na prodzie: księga mówiła
     * „EVENT / 01.09–27.10", config „REGULAR / 01.09–31.10".
     */
    @Test
    @DisplayName("Zmiana daty końca przy tym samym slug dopisuje nowy wiersz aktywacji")
    void changedEndDateWithSameSlugRecordsNewActivation() {
        FakeDao dao = new FakeDao();
        // Pierwsza edycja: ten sam slug, ale koniec 31.08 (jak stara rewizja configu).
        Edition narrow = new Edition("summer", "Lato", Edition.Type.REGULAR,
                Instant.parse("2026-06-01T00:00:00Z").toEpochMilli(),
                Instant.parse("2026-09-01T00:00:00Z").toEpochMilli());
        dao.recordActivation(3, narrow, 50L).join();

        // Rejestr z configu ma TEN SAM slug, ale dłuższy zakres.
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry("""
                enabled: true
                list:
                  - slug: summer
                    display-name: "Lato"
                    type: REGULAR
                    start: "2026-06-01"
                    end: "2026-09-30"
                """);
        SeasonEditionService service = service(dao, registry, 3);

        service.refreshActivation(T_JUNE);

        assertEquals(2, dao.ofSeason(3).size(),
                "zmiana metadanych edycji musi dopisać kolejny wiersz aktywacji");
        SeasonEditionDao.Row latest = dao.ofSeason(3).get(1);
        assertEquals("2026-09-30", latest.endDate(), "księga musi mieć NOWĄ datę końca");
        assertEquals(Edition.Type.REGULAR, latest.type());
    }

    @Test
    @DisplayName("refreshActivation jest idempotentny: powtórki w tej samej edycji nie dopisują wierszy")
    void repeatedRefreshActivationDoesNotDuplicateRows() {
        FakeDao dao = new FakeDao();
        SeasonEditionService service = service(dao, summerWithEvent(), 3);

        service.refreshActivation(T_JUNE);
        assertEquals(1, dao.ofSeason(3).size(), "pierwszy refresh dopisuje wiersz");
        assertEquals("lato-2026", dao.ofSeason(3).getFirst().slug());

        service.refreshActivation(T_JUNE_LATER); // ten sam slug, późniejszy czas
        assertEquals(1, dao.ofSeason(3).size(),
                "powtórzony refresh przy niezmienionym slugu NIE może dopisać wiersza");

        service.refreshActivation(T_JUNE); // dokładnie ta sama chwila też bez zmian
        assertEquals(1, dao.ofSeason(3).size());

        assertEquals(Edition.Type.REGULAR, dao.ofSeason(3).getFirst().type());
        assertEquals("2026-06-01", dao.ofSeason(3).getFirst().startDate());
        assertEquals("2026-08-31", dao.ofSeason(3).getFirst().endDate());
    }

    @Test
    @DisplayName("zmiana aktywnego sluga dopisuje NOWY wiersz (append-only), cached podąża")
    void slugChangeAppendsNewRowAndCacheFollows() {
        FakeDao dao = new FakeDao();
        EditionRegistry registry = summerWithEvent();
        SeasonEditionService service = service(dao, registry, 3);

        service.refreshActivation(T_JUNE);          // REGULAR lato
        service.refreshActivation(T_EVENT);         // EVENT wakacyjny bije lata
        service.refreshActivation(T_AFTER_EVENT);   // z powrotem REGULAR lato

        assertEquals(3, dao.ofSeason(3).size(),
                "każda zmiana sluga = dokładnie jeden nowy wiersz");
        assertEquals(List.of("lato-2026", "wakacyjny-2026", "lato-2026"),
                dao.ofSeason(3).stream().map(SeasonEditionDao.Row::slug).toList(),
                "wiersze mają być historią aktywacji w kolejności chronologicznej");

        SeasonEditionDao.Row latest = dao.ofSeason(3).getLast();
        assertEquals(0L, latest.closedAt(), "świeżo dopisany wiersz jest otwarty");
        assertEquals(registry.currentAt(T_AFTER_EVENT).slug(), latest.slug());

        assertEquals("lato-2026", service.currentCached(3).slug(),
                "currentCached ma zwracać migawkę ostatniego wiersza");
    }


    @Test
    @DisplayName("stampClosed znacznikuje wyłącznie OTWARTE wiersze danego sezonu i zwraca ich liczbę")
    void stampClosedMarksOnlyOpenRowsOfThatSeason() {
        FakeDao dao = new FakeDao();
        // Sezon 3: dwa otwarte wiersze. Sezon 4: jeden otwarty + jeden już zamknięty.
        dao.recordActivation(3, editionAt(summerWithEvent(), T_JUNE), 100L).join();
        dao.recordActivation(3, editionAt(summerWithEvent(), T_EVENT), 200L).join();
        dao.recordActivation(4, editionAt(summerWithEvent(), T_JUNE), 300L).join();

        // Sezon 4: dorabiamy ręcznie historyczny zamknięty wiersz.
        SeasonEditionDao.Row openSeason4 = dao.rows.stream()
                .filter(row -> row.seasonId() == 4).findFirst().orElseThrow();
        dao.rows.remove(openSeason4);
        dao.rows.add(new SeasonEditionDao.Row(4, 300L, openSeason4.slug(),
                openSeason4.displayName(), openSeason4.type(), openSeason4.startDate(),
                openSeason4.endDate(), 900L));
        dao.rows.add(new SeasonEditionDao.Row(4, 350L, "inna-2026", "Inna",
                Edition.Type.EVENT, "2026-01-01", "2026-01-02", 0L));

        Integer marked = dao.stampClosed(null, 3, 5_000L).join();

        assertEquals(2, marked, "znacznikowane mają być oba otwarte wiersze sezonu 3");
        assertTrue(dao.ofSeason(3).stream().allMatch(row -> row.closedAt() == 5_000L));
        assertEquals(1, dao.ofSeason(4).stream().filter(row -> row.closedAt() == 0L).count(),
                "otwarty wiersz innego sezonu musi pozostać otwarty");
        assertTrue(dao.ofSeason(4).stream().anyMatch(row -> row.closedAt() == 900L),
                "już zamknięty wiersz nie może zostać nadpisany");
        assertEquals(1, dao.stampClosedCalls, "fałek rejestruje wywołania transakcyjne");
    }

    @Test
    @DisplayName("publicHeaderLabel: nazwa edycji → legacy fallback → pusty string")
    void publicHeaderLabelFallsThroughEditionLegacyThenEmpty() {
        FakeDao dao = new FakeDao();
        SeasonEditionService service = new SeasonEditionService(dao, summerWithEvent());
        service.setSeasonIdSupplier(() -> 3);
        service.setLegacyLabelFallback(millis -> "legacy-zakres");

        assertEquals("Sezon Letni 2026", service.publicHeaderLabel(T_JUNE),
                "w oknie edycji wygrywa jej display-name");
        assertEquals("", service.publicHeaderLabel(
                        Instant.parse("2027-05-01T00:00:00Z").toEpochMilli()),
                "poza oknem przy włączonych edycjach: pusty nagłówek, nie legacy zakres");

        // Bez fallbacku poza oknem — pusty string (UI bez etykiety).
        SeasonEditionService bare = new SeasonEditionService(dao, summerWithEvent());
        bare.setSeasonIdSupplier(() -> 3);
        assertEquals("", bare.publicHeaderLabel(
                        Instant.parse("2027-05-01T00:00:00Z").toEpochMilli()),
                "brak fallbacku = pusty nagłówek, nigdy null ani numer S<n>");
    }

    @Test
    @DisplayName("bez seasonIdSupplier refresh jest no-opem; currentCached nieznanego sezonu to null")
    void missingSupplierAndUnknownSeasonDegradeQuietly() {
        FakeDao dao = new FakeDao();
        SeasonEditionService service = new SeasonEditionService(dao, summerWithEvent());

        service.refreshActivation(T_JUNE); // brak suppliera — nic nie może pęknąć ani dopisać
        assertEquals(0, dao.rows.size(), "bez suppliera sezonu refresh nie dopisuje nic");
        assertNull(service.currentCached(3));
        assertNull(service.currentCached(99));
    }
}
