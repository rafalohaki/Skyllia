package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kontrakt wysyłki: bundlowany {@code editions.yml} istnieje, parsuje się
 * fail-closed i jest domyślnie WYŁĄCZONY ({@code enabled:false}) — do czasu
 * gdy operatorka nie wpisze okien, świat działa na legacy epoch-math.
 *
 * <p>Wzorzec: {@link ShippedCosmeticCatalogContractTest}.
 */
class ShippedEditionsCatalogContractTest {

    private static final Path EDITIONS = Path.of("src/main/resources/editions.yml");

    private static YamlConfiguration load(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @Test
    @DisplayName("bundlowany editions.yml istnieje i ma sekcję editions")
    void bundledEditionsYmlExistsAndParses() throws IOException {
        assertTrue(Files.isRegularFile(EDITIONS), "brak " + EDITIONS + " w wysyłce");

        YamlConfiguration yaml = load(EDITIONS);
        assertNotNull(yaml.getConfigurationSection("editions"),
                "sekcja editions musi istnieć na korzeniu pliku");
    }

    @Test
    @DisplayName("domyślna wysyłka jest wyłączona: enabled=false = legacy epoch-math")
    void shippedCatalogIsDisabledByDefault() throws IOException {
        YamlConfiguration yaml = load(EDITIONS);

        assertFalse(yaml.getBoolean("editions.enabled", true),
                "bundlowany plik ma mieć jawnie enabled:false (fallback legacy)");
        assertEquals(false, yaml.get("editions.enabled"),
                "flaga ma być wpisana wprost, nie domyślna");
    }

    @Test
    @DisplayName("wszystkie wpisy z wysyłki przechodzą fail-closed loader rejestru")
    void everyShippedEntryParsesThroughTheRegistryLoader() throws IOException {
        YamlConfiguration yaml = load(EDITIONS);

        // enabled:false → registry.empty(); do walidacji treści podbijamy flagę na kopii.
        yaml.set("editions.enabled", true);
        EditionRegistry registry = EditionRegistry.load(
                yaml.getConfigurationSection("editions"));

        assertNotNull(registry);
        assertTrue(registry.isEnabled(), "po podbiciu flagi katalog ma się wczytać w całości");

        List<Map<?, ?>> entries = yaml.getMapList("editions.list");
        assertFalse(entries.isEmpty(),
                "wysyłka ma pokazywać przykładowy kształt wpisów (choć wyłączony)");
        for (Map<?, ?> entry : entries) {
            String slug = String.valueOf(entry.get("slug"));
            assertNotNull(registry.bySlug(slug),
                    "wpis z wysyłki '" + slug + "' ma być poprawnym slugiem rejestru");
            assertNotNull(registry.rangeDetail(registry.bySlug(slug)),
                    "okno edycji '" + slug + "' ma dawać sformatowany zakres dat");
        }
    }

    @Test
    @DisplayName("slugi w wysyłce są unikalne (fail-closed loader łapie duplikat jako wyjątek)")
    void shippedSlugsAreUniqueSoLoaderDoesNotThrow() throws IOException {
        YamlConfiguration yaml = load(EDITIONS);
        yaml.set("editions.enabled", true);

        // Duplikat rzuciłby IllegalArgumentException — sam fakt załadowania to dowód.
        EditionRegistry registry = EditionRegistry.load(
                yaml.getConfigurationSection("editions"));
        assertTrue(registry.isEnabled());

        long distinct = yaml.getMapList("editions.list").stream()
                .map(entry -> String.valueOf(entry.get("slug")))
                .distinct().count();
        assertEquals(yaml.getMapList("editions.list").size(), distinct,
                "duplikat sluga w wysyłce rozerwałby start serwera");
    }
}
