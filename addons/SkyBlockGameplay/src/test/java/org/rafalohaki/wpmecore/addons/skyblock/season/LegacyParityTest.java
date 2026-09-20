package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

/**
 * Parity legacy: rejestr edycji wyłączony (lub chwila poza oknami) →
 * {@code publicHeaderLabel} ma dawać DOKŁADNIE to samo, co
 * {@link SeasonSchedule#label()} — byte-stable zachowanie sprzed edycji,
 * łącznie z {@code null} (sezon off) mapowanym na pusty string.
 */
class LegacyParityTest {

    private static final Instant INSIDE = Instant.parse("2026-09-15T12:00:00Z");
    private static final Instant AFTER = Instant.parse("2027-05-01T00:00:00Z");

    /** Kalendarz jak na labie/proda: 2026-09-01 + 56 dni (precedens SeasonLabelsTest). */
    private static SeasonSchedule enabledSchedule() {
        return SeasonSchedule.load(SeasonEditionFixtures.yaml("""
                enabled: true
                length-days: 56
                epoch-start: "2026-09-01T00:00:00Z"
                """));
    }

    private static SeasonEditionService service(EditionRegistry registry, SeasonSchedule schedule) {
        SeasonEditionService service =
                new SeasonEditionService(SeasonEditionFixtures.NoopDao.INSTANCE, registry);
        service.setLegacyLabelFallback(nowMillis -> schedule.label());
        return service;
    }

    @Test
    @DisplayName("rejestr disabled: nagłówek == SeasonSchedule.label() dla stałych chwil")
    void disabledRegistryMatchesLegacyLabelByteForByte() {
        SeasonSchedule schedule = enabledSchedule();
        String legacy = schedule.label();
        Assertions.assertEquals("01.09.2026 – 27.10.2026", legacy,
                "sanity: kalendarz ma dawać zakres operatorki jak w SeasonLabelsTest");

        SeasonEditionService parity = service(EditionRegistry.empty(), schedule);
        Assertions.assertEquals(legacy, parity.publicHeaderLabel(INSIDE.toEpochMilli()));
        Assertions.assertEquals(legacy, parity.publicHeaderLabel(AFTER.toEpochMilli()),
                "poza sezonem legacy nadal drukuje ostatni zakres — nagłówek musi być identyczny");
    }

    @Test
    @DisplayName("null po legacy (sezon off) mapuje się na pusty string, nie na null")
    void nullLegacyLabelBecomesEmptyString() {
        SeasonSchedule off = SeasonSchedule.disabled();
        Assertions.assertNull(off.label(), "sanity: wyłączony kalendarz ma dawać null");

        Assertions.assertEquals("",
                service(EditionRegistry.empty(), off).publicHeaderLabel(INSIDE.toEpochMilli()),
                "null z legacy ma się pokazać graczowi jako brak etykiety");
    }

    @Test
    @DisplayName("aktywna edycja ZAWSZE bije legacy — nawet gdy oba istnieją")
    void activeEditionBeatsLegacyWhenBothExist() {
        SeasonSchedule schedule = enabledSchedule();
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry("""
                enabled: true
                list:
                  - slug: szkolny-2026-27
                    display-name: "Sezon Szkolny 2026/27"
                    type: REGULAR
                    start: "2026-09-01"
                    end: "2027-06-30"
                """);

        SeasonEditionService withEdition = service(registry, schedule);
        Assertions.assertEquals("Sezon Szkolny 2026/27",
                withEdition.publicHeaderLabel(INSIDE.toEpochMilli()),
                "nazwa edycji ma zastąpić zakres dat z kalendarza");
        // Poza oknem edycji przy WŁĄCZONYCH edycjach: pusty nagłówek
        // (decyzja C13/R2) — nieaktualny zakres z legacy math jest gorszy
        // niż uczciwe „brak etykiety”.
        Assertions.assertEquals("", withEdition.publicHeaderLabel(
                Instant.parse("2028-01-01T00:00:00Z").toEpochMilli()));
    }

    @Test
    @DisplayName("fallback bez kalendarza i bez edycji to pusty string — nigdy numer S<n>")
    void noFallbackAndNoEditionMeansEmptyHeader() {
        SeasonEditionService bare = new SeasonEditionService(
                SeasonEditionFixtures.NoopDao.INSTANCE, EditionRegistry.empty());
        Assertions.assertEquals("", bare.publicHeaderLabel(INSIDE.toEpochMilli()));
        Assertions.assertFalse(bare.publicHeaderLabel(AFTER.toEpochMilli()).matches(".*S\\d+.*"),
                "numer wewnętrzny S<n> nie może wyciec do nagłówka publicznego");
    }

}
