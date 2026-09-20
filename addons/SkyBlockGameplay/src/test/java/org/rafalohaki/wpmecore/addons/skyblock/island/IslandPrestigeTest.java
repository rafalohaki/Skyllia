package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matematyka i konfiguracja prestiżu bez Bukkita: koszt rośnie geometrycznie,
 * a brak sekcji (albo wartości, z których nie da się policzyć kosztu) znaczy
 * „prestiż wyłączony”, nie „wtyczka nie wstaje”.
 */
class IslandPrestigeTest {

    private static ConfigurationSection section(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (Exception failure) {
            throw new AssertionError("nieprawidłowy YAML testu", failure);
        }
        return config.getConfigurationSection("island.prestige");
    }

    @Test
    void costGrowsGeometricallyAndStaysAboveBase() {
        IslandPrestige.Settings settings = new IslandPrestige.Settings(
                6, 1_000L, 2.0D, List.of());

        assertEquals(1_000L, settings.costFor(0));
        assertEquals(2_000L, settings.costFor(1));
        assertEquals(4_000L, settings.costFor(2));
        assertEquals(32_000L, settings.costFor(5));
        for (int level = 0; level < 20; level++) {
            assertTrue(settings.costFor(level + 1) > settings.costFor(level),
                    "koszt prestiżu " + (level + 2) + " musi być wyższy niż " + (level + 1));
            assertTrue(settings.costFor(level) >= settings.baseCost(),
                    "koszt nigdy nie spada pod base-cost");
        }
    }

    @Test
    void costSaturatesInsteadOfOverflowingToNegative() {
        IslandPrestige.Settings settings = new IslandPrestige.Settings(
                10, Long.MAX_VALUE / 4L, 10.0D, List.of());

        assertEquals(Long.MAX_VALUE, settings.costFor(8));
        assertTrue(settings.costFor(9) > 0L, "przepełnienie nie może dać kosztu ujemnego");
    }

    @Test
    void titleComesFromConfigurationOrNothingWhenMissing() {
        IslandPrestige.Settings settings = new IslandPrestige.Settings(
                3, 100L, 1.5D, List.of("Rybacka", "Kupiecka"));

        assertEquals("Rybacka", settings.titleFor(1));
        assertEquals("Kupiecka", settings.titleFor(2));
        assertNull(settings.titleFor(3), "poziom bez tytułu w configu nie może dostać cudzego");
        assertNull(settings.titleFor(0));
    }

    @Test
    void missingSectionDisablesPrestige() {
        assertNull(IslandPrestige.Settings.load(null));
        assertNull(IslandPrestige.Settings.load(section("""
                island:
                  prestige:
                    max-level: 10
                    base-cost: 5000
                    cost-multiplier: 1.6
                """)), "sekcja bez `enabled: true` nie włącza prestiżu");
        assertNull(IslandPrestige.Settings.load(section("""
                island:
                  prestige:
                    enabled: false
                """)), "enabled: false znaczy wyłączony");
    }

    @Test
    void invalidNumbersDisableInsteadOfKillingThePlugin() {
        assertNull(IslandPrestige.Settings.load(section("""
                island:
                  prestige:
                    enabled: true
                    base-cost: 0
                    cost-multiplier: 1.6
                """)), "zerowy koszt bazowy = brak funkcji");
        assertNull(IslandPrestige.Settings.load(section("""
                island:
                  prestige:
                    enabled: true
                    base-cost: 5000
                    cost-multiplier: 1.0
                """)), "mnożnik 1.0 daje koszt stały, więc prestiż nie może rosnąć");
        assertNull(IslandPrestige.Settings.load(section("""
                island:
                  prestige:
                    enabled: true
                    base-cost: 5000
                    cost-multiplier: 1.6
                    max-level: 0
                """)), "limit poziomów poniżej 1 nie ma sensu");
    }

    @Test
    void configuredSectionLoadsEveryKnob() {
        IslandPrestige.Settings settings = IslandPrestige.Settings.load(section("""
                island:
                  prestige:
                    enabled: true
                    max-level: 4
                    base-cost: 25000
                    cost-multiplier: 1.5
                    title-per-level:
                      - "<gold>Wyspa Rybacka</gold>"
                      - "<gold>Wyspa Kupiecka</gold>"
                """));

        assertNotNull(settings);
        assertEquals(4, settings.maxLevel());
        assertEquals(25_000L, settings.costFor(0));
        assertEquals(37_500L, settings.costFor(1));
        assertEquals(25_000L * Math.pow(1.5D, 3), settings.costFor(3), 1.0D);
        assertEquals(List.of("<gold>Wyspa Rybacka</gold>", "<gold>Wyspa Kupiecka</gold>"),
                settings.titles());
    }

    @Test
    void defaultMaxLevelAppliesWhenKeyIsAbsent() {
        IslandPrestige.Settings settings = IslandPrestige.Settings.load(section("""
                island:
                  prestige:
                    enabled: true
                    base-cost: 1000
                    cost-multiplier: 2.0
                """));

        assertNotNull(settings);
        assertEquals(IslandPrestige.DEFAULT_MAX_LEVEL, settings.maxLevel());
        assertEquals(10, IslandPrestige.DEFAULT_MAX_LEVEL);
    }
}
