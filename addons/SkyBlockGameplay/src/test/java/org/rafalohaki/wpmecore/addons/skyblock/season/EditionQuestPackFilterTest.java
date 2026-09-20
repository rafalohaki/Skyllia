package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kontrakt C2 (runda C): quest-packi edycji.
 *
 * <p>Dwie strony kontraktu:
 * <ol>
 *   <li>{@link EditionRegistry#questPackFor(long)} /
 *       {@link EditionRegistry#questPackBySlug(String)} — pack AKTYWNEJ edycji
 *       (wg czasu, EVENT bije REGULAR); brak klucza / brak edycji / rejestr
 *       wyłączony → null; {@code quest-pack} dłuższy niż 64 znaki →
 *       fail-closed przy parsowaniu,</li>
 *   <li>{@link SeasonQuestCatalog#setActivePackSupplier(java.util.function.Supplier)}
 *       + {@link SeasonQuestCatalog#activeQuests()} — quest uniwersalny
 *       (bez {@code pack}) zawsze w katalogu aktywnym; otagowany tylko gdy
 *       zgadza się z dostawcą; {@code definitions()} pozostaje NIEFILTROWANE.</li>
 * </ol>
 */
class EditionQuestPackFilterTest {

    // ------------------------------------------------------------------
    // EditionRegistry: pack aktywnej edycji (wg czasu)
    // ------------------------------------------------------------------

    /** Lato (REGULAR, pack „wakacyjne”) + Festiwal (EVENT, pack „festiwal”, lipiec) + Jesień (REGULAR, bez packa). */
    private static final String REGISTRY_YAML = """
            editions:
              enabled: true
              list:
                - slug: lato-2026
                  display-name: 'Lato 2026'
                  type: REGULAR
                  start: '2026-06-01'
                  end: '2026-08-31'
                  quest-pack: wakacyjne
                - slug: festiwal-lipiec
                  display-name: 'Festiwal Lipiec'
                  type: EVENT
                  start: '2026-07-01'
                  end: '2026-07-31'
                  quest-pack: festiwal
                - slug: jesienna-zmiana
                  display-name: 'Jesien 2026'
                  type: REGULAR
                  start: '2026-09-01'
                  end: '2026-11-30'
            """;

    private static EditionRegistry loadRegistry(String raw) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(raw));
        return EditionRegistry.load(yaml.getConfigurationSection("editions"));
    }

    private static long millis(String isoInstant) {
        return Instant.parse(isoInstant).toEpochMilli();
    }

    private static void assertNullPack(String actual, String message) {
        assertTrue(actual == null, message + " (było: '" + actual + "')");
    }

    @Test
    @DisplayName("questPackFor zwraca pack edycji obejmującej dany moment")
    void packFollowsActiveEditionByTime() {
        EditionRegistry registry = loadRegistry(REGISTRY_YAML);

        assertEquals("wakacyjne", registry.questPackFor(millis("2026-06-15T12:00:00Z")),
                "w oknie REGULAR lata → pack lata");
        assertNullPack(registry.questPackFor(millis("2026-10-10T12:00:00Z")),
                "edycja bez quest-pack → null");
        assertNullPack(registry.questPackFor(millis("2026-12-25T12:00:00Z")),
                "poza każdą edycją → null");
    }

    @Test
    @DisplayName("pack podąża za EVENT-em w jego podoknie (EVENT bije REGULAR)")
    void eventEditionPackWinsInsideItsWindow() {
        EditionRegistry registry = loadRegistry(REGISTRY_YAML);

        assertEquals("festiwal", registry.questPackFor(millis("2026-07-15T12:00:00Z")),
                "w podoknie EVENT-u → pack eventu");
        assertEquals("wakacyjne", registry.questPackFor(millis("2026-08-15T12:00:00Z")),
                "po evencie wracamy do packa REGULAR-a");
    }

    @Test
    @DisplayName("questPackBySlug: trafienie / edycja bez packa / nieznany slug")
    void bySlugResolvesOnlyTaggedEditions() {
        EditionRegistry registry = loadRegistry(REGISTRY_YAML);

        assertEquals("wakacyjne", registry.questPackBySlug("lato-2026"));
        assertEquals("festiwal", registry.questPackBySlug("festiwal-lipiec"));
        assertTrue(registry.questPackBySlug("jesienna-zmiana") == null,
                "edycja bez klucza → null");
        assertTrue(registry.questPackBySlug("nie-ma-takiego") == null,
                "nieznany slug → null");
    }

    @Test
    @DisplayName("rejestr wyłączony/pusty i null root → zawsze null, nigdy wyjątek")
    void disabledOrEmptyRegistryNeverThrows() {
        assertTrue(EditionRegistry.empty().questPackFor(0L) == null);
        assertTrue(EditionRegistry.empty().questPackBySlug("cokolwiek") == null);
        assertTrue(EditionRegistry.load(null).questPackFor(millis("2026-07-15T12:00:00Z")) == null);

        String disabled = """
                editions:
                  enabled: false
                  list:
                    - slug: lato-2026
                      display-name: 'Lato 2026'
                      type: REGULAR
                      start: '2026-06-01'
                      end: '2026-08-31'
                      quest-pack: wakacyjne
                """;
        assertTrue(loadRegistry(disabled).questPackFor(millis("2026-07-15T12:00:00Z")) == null,
                "enabled:false → rejestr wyłączony, pack niedostępny mimo klucza");
    }

    @Test
    @DisplayName("quest-pack dokładnie 64 znaki → legalny; 65 → fail-closed")
    void packLengthBoundaryIsFailClosed() {
        String sixtyFour = "a".repeat(64);
        String okYaml = """
                editions:
                  enabled: true
                  list:
                    - slug: lato-2026
                      display-name: 'Lato 2026'
                      type: REGULAR
                      start: '2026-06-01'
                      end: '2026-08-31'
                      quest-pack: %s
                """.formatted(sixtyFour);
        assertEquals(sixtyFour, loadRegistry(okYaml).questPackFor(millis("2026-07-15T12:00:00Z")),
                "64 znaki w granicy inkluzywnej");

        String tooLong = """
                editions:
                  enabled: true
                  list:
                    - slug: lato-2026
                      display-name: 'Lato 2026'
                      type: REGULAR
                      start: '2026-06-01'
                      end: '2026-08-31'
                      quest-pack: %s
                """.formatted("a".repeat(65));
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> loadRegistry(tooLong));
        assertTrue(failure.getMessage().contains("quest-pack"),
                "błąd ma nazywać pole: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("64"),
                "błąd ma wskazywać limit: " + failure.getMessage());
    }

    // ------------------------------------------------------------------
    // SeasonQuestCatalog: filtrowanie po aktywnym packu
    // ------------------------------------------------------------------

    /** Cały dokument = sekcja season.quests (schema-version/definitions na szczycie). */
    private static final String CATALOG_YAML = """
            schema-version: 1
            points-default: 100
            definitions:
              sq_universal:
                type: BREAK
                targets: [STONE]
                goal: 10
                icon: STONE_PICKAXE
                name: 'Uniwersalny'
              sq_summer:
                type: FISH
                targets: [COD]
                goal: 5
                icon: FISHING_ROD
                name: 'Letni'
                pack: wakacyjne
              sq_winter:
                type: CRAFT
                targets: [CRAFTING_TABLE]
                goal: 3
                icon: CRAFTING_TABLE
                name: 'Zimowy'
                pack: zimowe
              sq_blank_pack:
                type: KILL
                targets: [ZOMBIE]
                goal: 5
                icon: IRON_SWORD
                name: 'Pusty tag'
                pack: ''
            """;

    private static SeasonQuestCatalog catalog() {
        YamlConfiguration yaml =
                YamlConfiguration.loadConfiguration(new StringReader(CATALOG_YAML));
        return SeasonQuestCatalog.load(yaml);
    }

    private static List<String> ids(List<SeasonQuestService.Definition> defs) {
        return defs.stream().map(SeasonQuestService.Definition::id).toList();
    }

    @Test
    @DisplayName("definitions() pozostaje NIEFILTROWANE niezależnie od dostawcy")
    void definitionsStayUnfiltered() {
        SeasonQuestCatalog catalog = catalog();
        catalog.setActivePackSupplier(() -> "zimowe");
        assertEquals(List.of("sq_universal", "sq_summer", "sq_winter", "sq_blank_pack"),
                ids(catalog.definitions()),
                "definitions() to surowa lista parsowania — filtra tam nie ma");

        catalog.setActivePackSupplier(null);
        assertEquals(4, catalog.definitions().size(),
                "null dostawca też nie rusza surowej listy");
    }

    @Test
    @DisplayName("brak dostawcy (null domyślnie) → tylko questy uniwersalne")
    void nullSupplierMeansUniversalOnly() {
        SeasonQuestCatalog catalog = catalog();
        assertEquals(List.of("sq_universal", "sq_blank_pack"),
                ids(catalog.activeQuests()),
                "pusty/blank pack = uniwersalny; otagowane bez dostawcy wypadają");
    }

    @Test
    @DisplayName("dostawca „wakacyjne” → uniwersalne + letnie")
    void matchingPackIncludesTaggedQuest() {
        SeasonQuestCatalog catalog = catalog();
        catalog.setActivePackSupplier(() -> "wakacyjne");
        assertEquals(List.of("sq_universal", "sq_summer", "sq_blank_pack"),
                ids(catalog.activeQuests()));
    }

    @Test
    @DisplayName("dostawca „zimowe” → uniwersalne + zimowe (letnie wypadają)")
    void mismatchedPackExcludesOtherTags() {
        SeasonQuestCatalog catalog = catalog();
        catalog.setActivePackSupplier(() -> "zimowe");
        assertEquals(List.of("sq_universal", "sq_winter", "sq_blank_pack"),
                ids(catalog.activeQuests()));
    }

    @Test
    @DisplayName("dostawca zwraca null (aktywna edycja bez packa) → tylko uniwersalne")
    void supplierReturningNullDegradesToUniversal() {
        SeasonQuestCatalog catalog = catalog();
        catalog.setActivePackSupplier(() -> null);
        assertEquals(List.of("sq_universal", "sq_blank_pack"),
                ids(catalog.activeQuests()));
    }

    @Test
    @DisplayName("dostawca czytany na bieżąco — zmiana packu między wywołaniami widoczna")
    void supplierIsConsultedOnEveryEnumeration() {
        SeasonQuestCatalog catalog = catalog();
        java.util.concurrent.atomic.AtomicReference<String> active =
                new java.util.concurrent.atomic.AtomicReference<>("wakacyjne");
        catalog.setActivePackSupplier(active::get);

        assertEquals(List.of("sq_universal", "sq_summer", "sq_blank_pack"),
                ids(catalog.activeQuests()), "aktywny „wakacyjne” → letni zestaw");

        active.set("zimowe");
        assertEquals(List.of("sq_universal", "sq_winter", "sq_blank_pack"),
                ids(catalog.activeQuests()),
                "zmiana aktywnego packu między wywołaniami ma być widoczna "
                        + "(dostawca nie może być cachowany)");
    }

    @Test
    @DisplayName("universalQuests() = tylko bez pack; activePackQuests() = tylko aktywny pak, w kolejności definicji")
    void splitViewsSeparateSlotsFromEventQuests() {
        SeasonQuestCatalog catalog = catalog();
        assertEquals(List.of("sq_universal", "sq_blank_pack"), ids(catalog.universalQuests()),
                "pusty pack = uniwersalny, otagowane nie zajmują slotów");
        assertEquals(List.of(), ids(catalog.activePackQuests()), "brak dostawcy → brak eventu");

        catalog.setActivePackSupplier(() -> "wakacyjne");
        assertEquals(List.of("sq_summer"), ids(catalog.activePackQuests()));
        assertEquals(List.of("sq_universal", "sq_blank_pack"), ids(catalog.universalQuests()),
                "dostawca nie rusza listy slotowej");

        catalog.setActivePackSupplier(() -> "nie-ma-takiego");
        assertEquals(List.of(), ids(catalog.activePackQuests()), "nieznany pak → pusto, nie wyjątek");
        catalog.setActivePackSupplier(() -> null);
        assertEquals(List.of(), ids(catalog.activePackQuests()));
    }

    @Test
    @DisplayName("reset dostawcy na null wraca do trybu uniwersalnego")
    void resettingSupplierRestoresUniversalMode() {
        SeasonQuestCatalog catalog = catalog();
        catalog.setActivePackSupplier(() -> "wakacyjne");
        assertEquals(List.of("sq_universal", "sq_summer", "sq_blank_pack"),
                ids(catalog.activeQuests()));

        catalog.setActivePackSupplier(null);
        assertEquals(List.of("sq_universal", "sq_blank_pack"),
                ids(catalog.activeQuests()));
    }
}
