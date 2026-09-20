package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kontrakt rejestru edycji (frozen): interwał półotwarty [start, end+1d),
 * EVENT &gt; REGULAR, fail-closed na duplikatach i nakładkach tego samego typu,
 * sąsiednie REGULAR-y stykające się o północy są legalne.
 *
 * <p>{@link EditionRegistry#load} dostaje sekcję {@code editions} (precedens
 * {@code SeasonSchedule.load}); daty w YAML cytujemy jako stringi, żeby
 * SnakeYAML nie zamienił ich na {@code Date}.
 */
class EditionRegistryTest {

    private static final long DAY_MILLIS = 24L * 3_600_000L;

    private static long millis(String isoInstant) {
        return Instant.parse(isoInstant).toEpochMilli();
    }

    private static final String LATO = """
            editions:
              enabled: true
              list:
                - slug: lato-2026
                  display-name: "Sezon Letni 2026"
                  type: REGULAR
                  start: "2026-06-01"
                  end: "2026-08-31"
            """;

    @Test
    @DisplayName("półotwarty interwał: start włącznie, chwila == końcowy wyłączna")
    void containmentIsHalfOpen() {
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry(LATO);

        Edition edition = registry.currentAt(millis("2026-07-05T12:00:00Z"));
        assertNotNull(edition, "środek okna ma się zawierać");
        assertEquals("lato-2026", edition.slug());

        assertTrue(registry.currentAt(millis("2026-06-01T00:00:00Z")) != null,
                "t == start ma być zawarte");
        assertTrue(registry.currentAt(millis("2026-08-31T23:59:59.999Z")) != null,
                "ostatnia milisekunda dnia końcowego ma być zawarta");
        assertNull(registry.currentAt(millis("2026-09-01T00:00:00Z")),
                "t == koniec półotwarty ma być już poza oknem");

        // Rekordowo: te same brzegi na poziomie Edition.
        assertTrue(edition.contains(millis("2026-06-01T00:00:00Z")));
        assertTrue(edition.contains(millis("2026-09-01T00:00:00Z") - 1));
        assertFalse(edition.contains(millis("2026-09-01T00:00:00Z")));
    }

    @Test
    @DisplayName("dzień końcowy jest włączny: 31.08 → koniec interwału 01.09T00:00Z")
    void inclusiveEndDayConvertsToNextMidnightExclusive() {
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry(LATO);

        Edition edition = registry.bySlug("lato-2026");
        assertNotNull(edition);
        assertEquals(millis("2026-06-01T00:00:00Z"), edition.startInclusiveMillis());
        assertEquals(millis("2026-09-01T00:00:00Z"), edition.endExclusiveMillis(),
                "end zapisany jako dzień włączny ma dać północ następnego dnia UTC");
    }

    @Test
    @DisplayName("EVENT bije REGULAR-a wewnątrz swojej podokna, poza nim wraca REGULAR")
    void eventPrecedesRegularInItsWindow() {
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry("""
                editions:
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

        assertEquals("lato-2026",
                registry.currentAt(millis("2026-06-15T00:00:00Z")).slug(),
                "przed EVENT-em obowiązuje REGULAR");
        assertEquals("wakacyjny-2026",
                registry.currentAt(millis("2026-07-05T00:00:00Z")).slug(),
                "EVENT ma wygrywać z REGULAR-em mimo nakładki");
        assertEquals("lato-2026",
                registry.currentAt(millis("2026-07-15T00:00:00Z")).slug(),
                "po EVENT-ie wraca REGULAR");
        assertEquals(Edition.Type.EVENT,
                registry.bySlug("wakacyjny-2026").type());
    }

    @Test
    @DisplayName("dziura między oknami → currentAt i labelAt null (fallback do legacy)")
    void gapOutsideEveryWindowResolvesToNull() {
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry(LATO);

        assertNull(registry.currentAt(millis("2025-01-10T00:00:00Z")));
        assertNull(registry.labelAt(millis("2025-01-10T00:00:00Z")));
        assertNull(registry.currentAt(millis("2027-01-10T00:00:00Z")));
        assertNull(registry.labelAt(millis("2027-01-10T00:00:00Z")));
    }

    @Test
    @DisplayName("duplikat sluga → fail-closed IllegalArgumentException")
    void duplicateSlugFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> SeasonEditionFixtures.loadRegistry("""
                editions:
                  enabled: true
                  list:
                    - slug: lato-2026
                      display-name: "Sezon Letni 2026"
                      type: REGULAR
                      start: "2026-06-01"
                      end: "2026-08-31"
                    - slug: lato-2026
                      display-name: "Sezon Letni 2026 bis"
                      type: REGULAR
                      start: "2026-10-01"
                      end: "2026-11-30"
                """));
    }

    @Test
    @DisplayName("nakładka EVENT∩EVENT > 0 dni → fail-closed")
    void overlappingEventsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> SeasonEditionFixtures.loadRegistry("""
                editions:
                  enabled: true
                  list:
                    - slug: wakacyjny-2026
                      display-name: "Sezon Wakacyjny 2026"
                      type: EVENT
                      start: "2026-07-01"
                      end: "2026-07-14"
                    - slug: szkolny-2026
                      display-name: "Sezon Szkolny 2026/27"
                      type: EVENT
                      start: "2026-07-10"
                      end: "2026-08-31"
                """));
    }

    @Test
    @DisplayName("REGULAR-y stykające się o północy (31.08 | 01.09) są LEGALNE")
    void regularEditionsTouchingAtMidnightAreLegal() {
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry("""
                editions:
                  enabled: true
                  list:
                    - slug: lato-2026
                      display-name: "Sezon Letni 2026"
                      type: REGULAR
                      start: "2026-06-01"
                      end: "2026-08-31"
                    - slug: jesienny-2026
                      display-name: "Sezon Jesienny 2026"
                      type: REGULAR
                      start: "2026-09-01"
                      end: "2026-11-30"
                """);

        assertTrue(registry.isEnabled(), "styk o północy nie jest nakładką");
        assertEquals("lato-2026", registry.currentAt(millis("2026-08-31T12:00:00Z")).slug());
        assertEquals("jesienny-2026", registry.currentAt(millis("2026-09-01T00:00:00Z")).slug(),
                "północ graniczna należy już do drugiej edycji");
    }

    @Test
    @DisplayName("okno przechodzące przez przełom roku działa i formatuje zakres dd.MM.yyyy")
    void yearBoundaryWindowIsSupportedAndFormatted() {
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry("""
                editions:
                  enabled: true
                  list:
                    - slug: zimowy-2026
                      display-name: "Sezon Zimowy 2026"
                      type: REGULAR
                      start: "2026-12-01"
                      end: "2027-02-28"
                """);

        Edition edition = registry.currentAt(millis("2027-01-15T00:00:00Z"));
        assertNotNull(edition, "styczeń 2027 ma być w oknie zimowym");
        assertEquals("Sezon Zimowy 2026", registry.labelAt(millis("2027-01-15T00:00:00Z")));
        assertEquals("01.12.2026 – 28.02.2027", registry.rangeDetail(edition),
                "format zakresu ma być zgodny ze stałymi SeasonSchedule");
        assertNull(registry.currentAt(millis("2027-03-01T00:00:00Z")));
    }

    @Test
    @DisplayName("nextAfter: przed pierwszym → pierwsza; w dziurze → następna; po wszystkim → null")
    void nextAfterResolvesUpcomingEditions() {
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry("""
                editions:
                  enabled: true
                  list:
                    - slug: lato-2026
                      display-name: "Sezon Letni 2026"
                      type: REGULAR
                      start: "2026-06-01"
                      end: "2026-08-31"
                    - slug: jesienny-2026
                      display-name: "Sezon Jesienny 2026"
                      type: REGULAR
                      start: "2026-10-01"
                      end: "2026-11-30"
                """);

        assertEquals("lato-2026", registry.nextAfter(millis("2026-05-01T00:00:00Z")).slug(),
                "przed pierwszym oknem następna jest pierwsza edycja");
        assertEquals("jesienny-2026", registry.nextAfter(millis("2026-09-15T00:00:00Z")).slug(),
                "w dziurze następna jest przyszła edycja");
        assertNull(registry.nextAfter(millis("2026-12-15T00:00:00Z")),
                "po ostatnim oknu nie ma już nic");
    }

    @Test
    @DisplayName("null root / enabled:false / pusta lista → pusty rejestr, nigdy wyjątek")
    void disabledOrEmptyConfigurationsDegradeToEmptyRegistry() {
        EditionRegistry fromNull = EditionRegistry.load(null);
        assertFalse(fromNull.isEnabled());

        EditionRegistry fromDisabled = SeasonEditionFixtures.loadRegistry("""
                editions:
                  enabled: false
                  list:
                    - slug: lato-2026
                      display-name: "Sezon Letni 2026"
                      type: REGULAR
                      start: "2026-06-01"
                      end: "2026-08-31"
                """);
        assertFalse(fromDisabled.isEnabled());

        EditionRegistry fromEmptyList = SeasonEditionFixtures.loadRegistry("""
                editions:
                  enabled: true
                  list: []
                """);
        assertFalse(fromEmptyList.isEnabled(), "pusta lista + enabled → wyłączone (warn)");

        for (EditionRegistry registry : new EditionRegistry[]{fromNull, fromDisabled, fromEmptyList}) {
            assertNull(registry.currentAt(System.currentTimeMillis()));
            assertNull(registry.labelAt(System.currentTimeMillis()));
            assertNull(registry.nextAfter(0L));
            assertNull(registry.bySlug("lato-2026"));
        }

        assertNotNull(EditionRegistry.empty());
        assertFalse(EditionRegistry.empty().isEnabled());
    }

    @Test
    @DisplayName("labelAt zwraca display-name tylko wewnątrz okna; rangeDetail używa separatora SeasonSchedule")
    void labelAndRangeDetailFollowTheWindow() {
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry(LATO);

        assertEquals("Sezon Letni 2026", registry.labelAt(millis("2026-07-01T00:00:00Z")));
        assertNull(registry.labelAt(millis("2026-09-01T00:00:00Z")));

        assertEquals("01.06.2026 – 31.08.2026",
                registry.rangeDetail(registry.bySlug("lato-2026")));
    }

    // ---- Round H: przypadki brzegowe slug / display-name / quest-pack / dat ----

    /** Dokument z jedną edycją REGULAR 01.06–31.08.2026 o podanym slugu i nazwie. */
    private static String singleRegularDoc(String slug, String displayName) {
        return """
                editions:
                  enabled: true
                  list:
                    - slug: %s
                      display-name: "%s"
                      type: REGULAR
                      start: "2026-06-01"
                      end: "2026-08-31"
                """.formatted(slug, displayName);
    }

    /** Jedna edycja REGULAR z kluczem quest-pack (granice limitu 64 znaków). */
    private static String singleRegularWithQuestPack(String slug, String questPack) {
        return """
                editions:
                  enabled: true
                  list:
                    - slug: %s
                      display-name: "Sezon Questowy"
                      type: REGULAR
                      start: "2026-06-01"
                      end: "2026-08-31"
                      quest-pack: "%s"
                """.formatted(slug, questPack);
    }

    @Test
    @DisplayName("slug o długości granicznej 2 i 64 znaki pasuje do wzorca i jest LEGALNY")
    void slugLengthsTwoAndSixtyFourAreLegal() {
        EditionRegistry shortest = SeasonEditionFixtures.loadRegistry(
                singleRegularDoc("ab", "Sezon Krotki Slug"));
        assertTrue(shortest.isEnabled(), "slug dwuznakowy ma być legalny");
        assertNotNull(shortest.bySlug("ab"));
        assertEquals("ab", shortest.currentAt(millis("2026-07-01T00:00:00Z")).slug());

        String longest = "s".repeat(64);
        EditionRegistry longestOk = SeasonEditionFixtures.loadRegistry(
                singleRegularDoc(longest, "Sezon Dlugi Slug"));
        assertTrue(longestOk.isEnabled(), "slug 64-znakowy ma być legalny");
        assertEquals(longest, longestOk.bySlug(longest).slug());
        assertEquals(longest, longestOk.currentAt(millis("2026-07-01T00:00:00Z")).slug(),
                "aktywna w oknie ma być edycja o najdłuższym slugu");
    }

    @Test
    @DisplayName("slug 1-znakowy i 65-znakowy łamią ^[a-z0-9-]{2,64}$ → fail-closed")
    void slugShorterThanTwoOrLongerThanSixtyFourFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> SeasonEditionFixtures.loadRegistry(
                        singleRegularDoc("a", "Sezon Za Krotki")),
                "slug jednoznakowy ma być odrzucony");
        assertThrows(IllegalArgumentException.class, () -> SeasonEditionFixtures.loadRegistry(
                        singleRegularDoc("s".repeat(65), "Sezon Za Dlugi")),
                "slug 65-znakowy ma być odrzucony");
    }

    @Test
    @DisplayName("display-name dokładnie 48 znaków mieści się w limicie i jest LEGALNY")
    void displayNameOfExactlyFortyEightCharsIsLegal() {
        String maxName = "N".repeat(48);
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry(
                singleRegularDoc("lato-2026", maxName));

        assertTrue(registry.isEnabled(), "display-name 48 znaków nie przekracza limitu");
        assertEquals(maxName, registry.labelAt(millis("2026-07-01T00:00:00Z")),
                "etykieta ma zwracać pełną, graniczną nazwę");
        assertEquals(maxName, registry.bySlug("lato-2026").displayName());
    }

    @Test
    @DisplayName("display-name 49 znaków przekracza limit 48 → fail-closed")
    void displayNameOfFortyNineCharsFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> SeasonEditionFixtures.loadRegistry(
                        singleRegularDoc("lato-2026", "N".repeat(49))),
                "display-name powyżej 48 znaków ma być odrzucony");
    }

    @Test
    @DisplayName("quest-pack dokładnie 64 znaki mieści się w limicie i jest LEGALNY")
    void questPackOfExactlySixtyFourCharsIsLegal() {
        String maxPack = "q".repeat(64);
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry(
                singleRegularWithQuestPack("lato-2026", maxPack));

        assertTrue(registry.isEnabled(), "quest-pack 64 znaki nie przekracza limitu");
        assertEquals(maxPack, registry.questPackBySlug("lato-2026"),
                "quest-pack po slugu ma być zachowany co do znaku");
        assertEquals(maxPack, registry.questPackFor(millis("2026-07-01T00:00:00Z")),
                "quest-pack aktywnej edycji ma być dostępny w oknie");
    }

    @Test
    @DisplayName("quest-pack 65 znaków przekracza limit 64 → fail-closed")
    void questPackOfSixtyFiveCharsFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> SeasonEditionFixtures.loadRegistry(
                        singleRegularWithQuestPack("lato-2026", "q".repeat(65))),
                "quest-pack powyżej 64 znaków ma być odrzucony");
    }

    @Test
    @DisplayName("okno startujące 2028-02-29 (przestępny lutego) parsuje się z poprawnymi instantami UTC")
    void leapDayStart20280229ParsesWithCorrectUtcInstants() {
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry("""
                editions:
                  enabled: true
                  list:
                    - slug: przestepny-2028
                      display-name: "Sezon Przestepny 2028"
                      type: REGULAR
                      start: "2028-02-29"
                      end: "2028-03-05"
                """);

        Edition edition = registry.bySlug("przestepny-2028");
        assertNotNull(edition, "data 29 lutego ma się sparsować jako poprawna data ISO");
        assertEquals(millis("2028-02-29T00:00:00Z"), edition.startInclusiveMillis(),
                "start północ 29.02 UTC");
        assertEquals(millis("2028-03-06T00:00:00Z"), edition.endExclusiveMillis(),
                "dzień końcowy 05.03 włącznie → północ 06.03 UTC");
        assertEquals("przestepny-2028",
                registry.currentAt(millis("2028-02-29T12:00:00Z")).slug(),
                "południe dnia przestępnego ma być w oknie");
        assertNull(registry.currentAt(millis("2028-03-06T00:00:00Z")),
                "północ po dniu końcowym jest już poza oknem");
        assertEquals("29.02.2028 – 05.03.2028", registry.rangeDetail(edition),
                "zakres ma formatować dzień przestępny bez przesunięć");
    }

    @Test
    @DisplayName("EVENT kończący się w dniu startu REGULAR-a (styk nakładkowy) jest LEGALNY")
    void eventEndDayEqualToRegularStartDayIsLegal() {
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry("""
                editions:
                  enabled: true
                  list:
                    - slug: premiera-2026
                      display-name: "Wydarzenie Premierowe"
                      type: EVENT
                      start: "2026-07-01"
                      end: "2026-07-14"
                    - slug: lato-2026
                      display-name: "Sezon Letni 2026"
                      type: REGULAR
                      start: "2026-07-14"
                      end: "2026-08-31"
                """);

        assertTrue(registry.isEnabled(),
                "nachodzenie EVENT↔REGULAR (wspólny dzień 14.07) nie jest błędem danych");
        assertEquals("premiera-2026", registry.currentAt(millis("2026-07-13T00:00:00Z")).slug(),
                "przed wspólnym dniem obowiązuje EVENT");
        assertEquals("premiera-2026",
                registry.currentAt(millis("2026-07-14T12:00:00Z")).slug(),
                "w nakładce EVENT bije REGULAR");
        assertEquals("lato-2026", registry.currentAt(millis("2026-07-15T00:00:00Z")).slug(),
                "po wygaśnięciu EVENT-u wraca REGULAR");
    }

    @Test
    @DisplayName("dwie edycje REGULAR stykające się tylko o północy przełomu roku są LEGALNE")
    void regularEditionsTouchingOnlyAtNewYearMidnightAreLegal() {
        EditionRegistry registry = SeasonEditionFixtures.loadRegistry("""
                editions:
                  enabled: true
                  list:
                    - slug: kalendarzowy-2026
                      display-name: "Sezon Kalendarzowy 2026"
                      type: REGULAR
                      start: "2026-01-01"
                      end: "2026-12-31"
                    - slug: zimowy-2027
                      display-name: "Sezon Zimowy 2027"
                      type: REGULAR
                      start: "2027-01-01"
                      end: "2027-02-28"
                """);

        assertTrue(registry.isEnabled(), "styk wyłącznie na północy 01.01 nie jest nakładką");
        assertEquals("kalendarzowy-2026",
                registry.currentAt(millis("2026-12-31T23:59:59.999Z")).slug(),
                "ostatnia milisekunda roku należy jeszcze do pierwszej edycji");
        assertEquals("zimowy-2027",
                registry.currentAt(millis("2027-01-01T00:00:00Z")).slug(),
                "północ przełomowa należy już do drugiej edycji");
        assertEquals("zimowy-2027", registry.nextAfter(millis("2026-12-31T12:00:00Z")).slug(),
                "nextAfter na styku wskazuje edycję noworoczną");
    }

    @Test
    @DisplayName("load(null) → pusty rejestr, nigdy wyjątek")
    void loadNullRootDegradesToEmptyRegistry() {
        EditionRegistry registry = EditionRegistry.load(null);

        assertNotNull(registry, "load(null) ma zwracać rejestr, nie rzucać");
        assertFalse(registry.isEnabled());
        assertNull(registry.currentAt(System.currentTimeMillis()));
        assertNull(registry.labelAt(System.currentTimeMillis()));
        assertNull(registry.nextAfter(0L));
        assertNull(registry.bySlug("cokolwiek"));
        assertNull(registry.questPackFor(System.currentTimeMillis()),
                "pusty rejestr nigdy nie zwraca quest-paka");
    }

    @Test
    @DisplayName("sekcja bez potomka editions/enabled → empty(), nigdy wyjątek")
    void loadSectionWithoutEditionsChildrenDegradesToEmptyRegistry() {
        // Pusta sekcja editions: brak „enabled” → domyślnie false → pusty rejestr.
        EditionRegistry fromEmptySection =
                SeasonEditionFixtures.loadRegistry("editions: {}\n");
        assertNotNull(fromEmptySection);
        assertFalse(fromEmptySection.isEnabled(), "sekcja bez enabled ma degradować do empty()");

        // Root bez sekcji editions w ogóle (fixtures podaje cały dokument jako node).
        EditionRegistry fromUnrelatedRoot =
                SeasonEditionFixtures.loadRegistry("cos-innego:\n  enabled: true\n");
        assertNotNull(fromUnrelatedRoot);
        assertFalse(fromUnrelatedRoot.isEnabled());

        for (EditionRegistry registry : new EditionRegistry[]{fromEmptySection, fromUnrelatedRoot}) {
            assertNull(registry.currentAt(System.currentTimeMillis()),
                    "pusty rejestr nigdy nie zwraca edycji");
            assertNull(registry.nextAfter(0L));
            assertNull(registry.bySlug("lato-2026"));
            assertNull(registry.questPackFor(System.currentTimeMillis()),
                    "pusty rejestr nigdy nie zwraca quest-paka");
        }
    }
}
