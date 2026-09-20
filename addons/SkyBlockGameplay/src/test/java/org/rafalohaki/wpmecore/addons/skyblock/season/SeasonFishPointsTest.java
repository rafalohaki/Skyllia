package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2.5: tabela rzadkość → punkty za ryby z config.yml (season.fish-points).
 * Fail-closed: brak sekcji, brakująca rzadkość, punkty ≤ 0, zły typ wartości,
 * nieznana rzadkość i duplikaty nazw (różna wielkość liter) = błąd startu.
 */
class SeasonFishPointsTest {

    private static YamlConfiguration yaml(String raw) {
        return YamlConfiguration.loadConfiguration(
                new StringReader(raw));
    }

    private static YamlConfiguration validConfig() {
        return yaml("""
                common: 5
                rare: 15
                epic: 40
                legendary: 120
                """);
    }

    @Test
    void parsesAllFourRaritiesWithConfigValues() {
        SeasonFishPoints points = SeasonFishPoints.load(validConfig());

        assertEquals(Map.of("common", 5, "rare", 15, "epic", 40, "legendary", 120),
                points.pointsByRarity());
        assertEquals(5, points.pointsFor("common"));
        assertEquals(15, points.pointsFor("RARE"), "dopasowanie case-insensitive jak w EMF");
        assertEquals(120, points.pointsFor("legendary"));
    }

    @Test
    void missingSectionFailsClosedWithoutFallback() {
        assertThrows(IllegalArgumentException.class, () -> SeasonFishPoints.load(null),
                "brak sekcji season.fish-points = błąd startu, nie fallback do hardcoded mapy");
        assertThrows(IllegalArgumentException.class,
                () -> SeasonFishPoints.load(new YamlConfiguration()),
                "pusta sekcja też jest błędem");
    }

    @Test
    void missingRarityIsRejected() {
        YamlConfiguration config = yaml("""
                common: 5
                rare: 15
                epic: 40
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SeasonFishPoints.load(config));
        assertTrue(failure.getMessage().contains("legendary"),
                "komunikat wskazuje brakującą rzadkość: " + failure.getMessage());
    }

    @Test
    void zeroAndNegativePointsAreRejected() {
        YamlConfiguration zero = yaml("""
                common: 0
                rare: 15
                epic: 40
                legendary: 120
                """);
        assertThrows(IllegalArgumentException.class, () -> SeasonFishPoints.load(zero));

        YamlConfiguration negative = yaml("""
                common: -1
                rare: 15
                epic: 40
                legendary: 120
                """);
        assertThrows(IllegalArgumentException.class, () -> SeasonFishPoints.load(negative));
    }

    @Test
    void nonIntegerValueIsRejected() {
        YamlConfiguration string = yaml("""
                common: piec
                rare: 15
                epic: 40
                legendary: 120
                """);
        assertThrows(IllegalArgumentException.class, () -> SeasonFishPoints.load(string));

        YamlConfiguration fractional = yaml("""
                common: 5.5
                rare: 15
                epic: 40
                legendary: 120
                """);
        assertThrows(IllegalArgumentException.class, () -> SeasonFishPoints.load(fractional));
    }

    @Test
    void unknownRarityKeyIsRejected() {
        YamlConfiguration config = yaml("""
                common: 5
                rare: 15
                epic: 40
                legendary: 120
                mythic: 999
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SeasonFishPoints.load(config));
        assertTrue(failure.getMessage().contains("mythic"));
    }

    @Test
    void duplicateRarityNamesDifferingOnlyInCaseAreRejected() {
        YamlConfiguration config = yaml("""
                common: 5
                Common: 6
                rare: 15
                epic: 40
                legendary: 120
                """);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SeasonFishPoints.load(config));
        assertTrue(failure.getMessage().contains("common"));
    }

    @Test
    void shippedConfigYamlParsesAndMatchesFormerHardcodedValues() throws Exception {
        var reader = new java.io.InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config.yml"),
                StandardCharsets.UTF_8);
        assertNotNull(reader, "brak wysyłanego config.yml na classpath testów");
        YamlConfiguration shipped = YamlConfiguration.loadConfiguration(reader);

        SeasonFishPoints points = SeasonFishPoints.load(
                shipped.getConfigurationSection("season.fish-points"));

        assertEquals(5, points.pointsFor("common"));
        assertEquals(15, points.pointsFor("rare"));
        assertEquals(40, points.pointsFor("epic"));
        assertEquals(120, points.pointsFor("legendary"));
    }
}
