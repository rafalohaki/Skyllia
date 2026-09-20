package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Widok gracza: edycja kolekcji jako ZAKRES DAT (decyzja operatora
 * 2026-08-25) — zero numerków „S&lt;n&gt;” w UI. Arytmetyka zgodna z
 * SeasonSchedule (epoch-start + długość), ale celowo zduplikowana w
 * {@link SeasonLabels} — patrz nagłówek tej klasy.
 */
class SeasonLabelsTest {

    /** epoch-start z config.yml na labie/proda: 2026-09-01T00:00:00Z, 56 dni. */
    private static final long EPOCH = java.time.Instant.parse("2026-09-01T00:00:00Z").toEpochMilli();
    private static final int LENGTH_DAYS = 56;

    @Test
    @DisplayName("edycja 1 przy znanym kalendarzu to zakres dat operatorki: 01.09 – 27.10.2026")
    void editionOneRendersOperatorDateRange() {
        assertEquals("01.09 – 27.10.2026",
                SeasonLabels.editionText(1, EPOCH, LENGTH_DAYS));
    }

    @Test
    @DisplayName("edycja przesuwa się o dokładnie jedną długość sezonu")
    void editionTwoFollowsTheCalendar() {
        // Edycja 2 zaczyna się na granicy 27.10 i trwa 56 dni → 27.10 – 22.12.2026.
        assertEquals("27.10 – 22.12.2026",
                SeasonLabels.editionText(2, EPOCH, LENGTH_DAYS));
        assertTrue(SeasonLabels.editionText(2, EPOCH, LENGTH_DAYS).contains("2026"),
                "rok widoczny na końcu zakresu");
    }
    @Test
    @DisplayName("windowText(): zakres z configu albo null — UI bez numerka i bez zakresu")
    void windowTextFallsBackToNullWithoutCalendar() throws Exception {
        YamlConfiguration enabled = new YamlConfiguration();
        enabled.loadFromString("""
                season:
                  enabled: true
                  length-days: 56
                  epoch-start: '2026-09-01T00:00:00Z'
                """);
        var section = enabled.getConfigurationSection("season");
        assertEquals("01.09 – 27.10.2026", SeasonLabels.windowText(1, section));
        assertNull(SeasonLabels.windowText(1, null));
        assertNull(SeasonLabels.windowText(0, section));
        assertNull(SeasonLabels.windowText(-1, section));
    }


    @Test
    @DisplayName("zakres przekraczający rok drukuje rok na obu końcach")
    void yearCrossingRangePrintsBothYears() {
        long decemberEpoch = java.time.Instant.parse("2026-12-01T00:00:00Z").toEpochMilli();
        String text = SeasonLabels.editionText(1, decemberEpoch, LENGTH_DAYS);
        assertEquals("01.12.2026 – 26.01.2027", text);
    }

    @Test
    @DisplayName("brak kalendarza albo zły numer to „?” — nigdy S<n>")
    void invalidInputsFallBackToQuestionMark() {
        assertEquals("?", SeasonLabels.editionText(0, EPOCH, LENGTH_DAYS));
        assertEquals("?", SeasonLabels.editionText(1, 0L, LENGTH_DAYS));
        assertEquals("?", SeasonLabels.editionText(1, EPOCH, 0));
        assertEquals("?", SeasonLabels.editionText(-3, EPOCH, LENGTH_DAYS));
    }

    @Test
    @DisplayName("seasonNumberOf czyta wyłącznie marker PDC „S<n>”")
    void seasonNumberParsesOnlyInternalMarker() {
        assertEquals(1, SeasonLabels.seasonNumberOf("S1"));
        assertEquals(42, SeasonLabels.seasonNumberOf("S42"));
        assertNull(SeasonLabels.seasonNumberOf(null));
        assertNull(SeasonLabels.seasonNumberOf("?"));
        assertNull(SeasonLabels.seasonNumberOf("2026-S1"), "obce formaty legacy pomijane");
        assertNull(SeasonLabels.seasonNumberOf("Sx"));
        assertNull(SeasonLabels.seasonNumberOf("S"));
    }

    @Test
    @DisplayName("window() czyta sekcję season jak SeasonSchedule.load i fail-openie na śmieciach")
    void windowReadsConfigSection() throws Exception {
        YamlConfiguration enabled = new YamlConfiguration();
        enabled.loadFromString("""
                season:
                  enabled: true
                  length-days: 56
                  epoch-start: '2026-09-01T00:00:00Z'
                """);
        var window = SeasonLabels.window(enabled.getConfigurationSection("season"));
        assertEquals(EPOCH, window.epochStartMillis());
        assertEquals(56, window.lengthDays());

        assertNull(SeasonLabels.window(null));
        assertNull(SeasonLabels.window(enabled.getConfigurationSection("missing")));
        assertNull(SeasonLabels.window(new YamlConfiguration()));

        YamlConfiguration disabled = new YamlConfiguration();
        disabled.loadFromString("""
                season:
                  enabled: false
                  length-days: 56
                  epoch-start: '2026-09-01T00:00:00Z'
                """);
        assertNull(SeasonLabels.window(disabled.getConfigurationSection("season")));

        YamlConfiguration broken = new YamlConfiguration();
        broken.loadFromString("""
                season:
                  enabled: true
                  length-days: 56
                  epoch-start: 'nie-jest-data'
                """);
        assertNull(SeasonLabels.window(broken.getConfigurationSection("season")));
    }
}
