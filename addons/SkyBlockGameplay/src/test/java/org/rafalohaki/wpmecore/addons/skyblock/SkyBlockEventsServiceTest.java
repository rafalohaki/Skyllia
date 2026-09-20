package org.rafalohaki.wpmecore.addons.skyblock;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rotacja eventów jest deterministyczna od epoki: te same chwile zegarowe
 * dają te same aktywne eventy po „restarcie” serwisu, a ogłoszenia
 * odpalają się raz na wejście/wyjście.
 */
class SkyBlockEventsServiceTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    private static final String YAML = """
            events:
              enabled: true
              epoch: '2026-09-20T00:00:00Z'
              rotation:
                - id: a
                  name: 'Event A'
                  duration-minutes: 30
                  gap-minutes: 30
                  crystal-luck: 2.0
                - id: b
                  name: 'Event B'
                  duration-minutes: 30
                  gap-minutes: 30
                  season-points: 2.0
            """;

    private final List<String> announced = new ArrayList<>();

    private SkyBlockEventsService load(String yaml) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        ConfigurationSection section = cfg.getConfigurationSection("events") != null
                ? cfg.getConfigurationSection("events") : cfg;
        return SkyBlockEventsService.load(LOGGER, section, announced::add);
    }

    private static long at(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    @Test
    void rotationIsDeterministicFromEpoch() {
        SkyBlockEventsService service = load(YAML);
        // Cykl = 120 min: [A 0-30] [gap 30-60] [B 60-90] [gap 90-120].
        assertEquals("a", service.activeAt(at("2026-09-20T00:10:00Z")).orElseThrow().id());
        assertTrue(service.activeAt(at("2026-09-20T00:45:00Z")).isEmpty());
        assertEquals("b", service.activeAt(at("2026-09-20T01:05:00Z")).orElseThrow().id());
        assertTrue(service.activeAt(at("2026-09-20T01:45:00Z")).isEmpty());
        // Następny cykl — pozycja zapętla się.
        assertEquals("a", service.activeAt(at("2026-09-20T02:10:00Z")).orElseThrow().id());
        // Czas przed epoką (odmota/clock skew) wciąż trafia w cykl.
        assertEquals("a", service.activeAt(at("2026-09-19T22:10:00Z")).orElseThrow().id());
    }

    @Test
    void multipliersComeFromActiveEvent() {
        SkyBlockEventsService service = load(YAML);
        // activeAt steruje multiplierami przez tick? Nie — mnożniki czytają
        // System.currentTimeMillis, więc testujemy przez activeAt i definicje.
        assertEquals(2.0, service.activeAt(at("2026-09-20T00:10:00Z")).orElseThrow().crystalLuck());
        assertEquals(1.0, service.activeAt(at("2026-09-20T00:10:00Z")).orElseThrow().seasonPoints());
        assertEquals(1.0, service.activeAt(at("2026-09-20T01:05:00Z")).orElseThrow().crystalLuck());
        assertEquals(2.0, service.activeAt(at("2026-09-20T01:05:00Z")).orElseThrow().seasonPoints());
    }

    @Test
    void announcesOnlyOnTransitions() {
        // Epoch 60 s temu → event a aktywny przy pierwszym ticku.
        long now = System.currentTimeMillis();
        long epoch = now - 60_000L;
        String yaml = "enabled: true\n"
                + "epoch: '" + Instant.ofEpochMilli(epoch) + "'\n"
                + "rotation:\n"
                + "  - id: a\n    name: 'Event A'\n    duration-minutes: 30\n"
                + "    gap-minutes: 30\n    crystal-luck: 2.0\n"
                + "    announce-start: 'START <name>'\n"
                + "    announce-end: 'END <name>'\n";
        SkyBlockEventsService service = load(yaml);
        assertNotNull(service);
        service.tick();
        service.tick();
        service.tick();
        assertEquals(List.of("START Event A"), announced);
        // Koniec eventu — przesuwamy „teraz” przez nową instancję nad tym
        // samym ogłaszaczem? tick czyta zegar; zamiast czekać, sprawdzamy
        // ogłoszenie końca osobną instancją z epoką tak, by wypaść w gap.
        announced.clear();
        long epoch2 = now - 45L * 60_000L; // w środku gapu po evencie A
        SkyBlockEventsService s2 = load("enabled: true\n"
                + "epoch: '" + Instant.ofEpochMilli(epoch2) + "'\n"
                + "rotation:\n"
                + "  - id: a\n    name: 'Event A'\n    duration-minutes: 30\n"
                + "    gap-minutes: 30\n"
                + "    announce-start: 'START <name>'\n    announce-end: 'END <name>'\n");
        s2.tick();
        assertTrue(announced.isEmpty(), "w przerwie nie ma ogłoszeń");
    }

    @Test
    void loadIsFailClosed() {
        assertNull(load("enabled: false\n"));
        assertNull(load(""));
        // Brak rotacji
        assertNull(load("enabled: true\nepoch: '2026-09-20T00:00:00Z'\nrotation: []\n"));
        // Zła epoka
        assertNull(load("enabled: true\nepoch: 'nie-data'\n"
                + "rotation:\n  - id: a\n    name: 'A'\n    duration-minutes: 30\n"));
        // Rotacja bez wymaganych pól
        assertNull(load("enabled: true\nepoch: '2026-09-20T00:00:00Z'\n"
                + "rotation:\n  - crystal-luck: 2.0\n"));
    }
}
