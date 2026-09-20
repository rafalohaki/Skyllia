package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2.5: katalog questów sezonowych — parsowanie fail-closed config.yml
 * (season.quests): schema-version, nieznany typ, puste id/cel, materiał ikony,
 * punkty/cel > 0 oraz limit slotów odblokowań (spec §2.1).
 */
class SeasonQuestCatalogTest {

    private static final String VALID = """
            schema-version: 1
            points-default: 100
            definitions:
              sq_a:
                type: BREAK
                targets: [stone_pickaxe, STONE]
                goal: 10
                points: 50
                icon: STONE_PICKAXE
                name: 'Kamieniokop'
                lore: ['<gray>Wykop 10.</gray>']
              sq_b:
                type: KILL
                targets: [ZOMBIE]
                goal: 5
                icon: IRON_SWORD
                name: 'Obrońca'
            """;

    private static YamlConfiguration yaml(String raw) {
        return YamlConfiguration.loadConfiguration(new StringReader(raw));
    }
    @Test
    void parsesValidCatalogWithDefaults() {
        SeasonQuestCatalog catalog = SeasonQuestCatalog.load(yaml(VALID));

        assertEquals(List.of("sq_a", "sq_b"),
                catalog.definitions().stream().map(d -> d.id()).toList(),
                "kolejność definicji = kolejność odblokowywania");
        var second = catalog.definitions().get(1);
        assertEquals(100, second.points(), "brak points → points-default");
        assertEquals(SeasonQuestService.Definition.Type.KILL, second.type());
        assertFalse(second.matureOnly(), "mature-only domyślnie false");
        assertTrue(second.lore().isEmpty(), "lore opcjonalne");
    }

    @Test
    void normalizesTargetsToUpperCase() {
        SeasonQuestCatalog catalog = SeasonQuestCatalog.load(yaml(VALID));
        var first = catalog.definitions().get(0);

        assertTrue(first.targets().containsAll(java.util.Set.of("STONE", "STONE_PICKAXE")),
                "cele znormalizowane do wielkich liter: " + first.targets());
    }

    @Test
    void shippedConfigYamlParsesAndKeepsPilotSet() throws Exception {
        try (var reader = new java.io.InputStreamReader(
                getClass().getClassLoader().getResourceAsStream("config.yml"),
                StandardCharsets.UTF_8)) {
            assertNotNull(reader, "brak wysyłanego config.yml na classpath testów");
            YamlConfiguration shipped = YamlConfiguration.loadConfiguration(reader);
            SeasonQuestCatalog catalog = SeasonQuestCatalog.load(
                    shipped.getConfigurationSection("season.quests"));

            // Pełny katalog (40 wg spec §2.1) sprawdza ShippedSeasonQuestsContractTest;
            // tu tylko regresja: pilotażowa dziesiątka nie może cicho wypaść.
            List<String> ids = catalog.definitions().stream().map(d -> d.id()).toList();
            assertTrue(ids.containsAll(List.of("sq_harvest_1", "sq_mine_1", "sq_kill_1",
                            "sq_fish_1", "sq_craft_1", "sq_mine_2",
                            "sq_harvest_2", "sq_kill_2", "sq_fish_2", "sq_craft_2")),
                    "brakuje questa z zestawu pilotażowego: " + ids);
            assertTrue(catalog.universalQuests().size()
                            <= DefaultSeasonPointService.UNLOCK_THRESHOLDS.length
                                    * DefaultSeasonPointService.SLOTS_PER_TIER[0],
                    "questy uniwersalne mieszczą się w slotach odblokowań");
        }
    }

    @Test
    void packQuestsDoNotConsumeUnlockSlots() {
        int totalSlots = DefaultSeasonPointService.UNLOCK_THRESHOLDS.length
                * DefaultSeasonPointService.SLOTS_PER_TIER[0];
        StringBuilder defs = new StringBuilder();
        for (int i = 0; i < totalSlots; i++) {
            defs.append("  sq_x").append(i).append(": {type: BREAK, targets: [STONE], ")
                    .append("goal: 1, icon: STONE, name: 'x'}\n");
        }
        defs.append("  sq_event: {type: FISH, targets: [COD], goal: 1, icon: COD, ")
                .append("name: 'e', pack: jesien}\n");
        String raw = "schema-version: 1\npoints-default: 10\ndefinitions:\n" + defs;

        SeasonQuestCatalog catalog = SeasonQuestCatalog.load(yaml(raw));

        assertEquals(totalSlots + 1, catalog.definitions().size(), "pełna lista bez filtra");
        assertEquals(totalSlots, catalog.universalQuests().size(),
                "quest z pack nie liczy się do limitu slotów");
        assertTrue(catalog.activePackQuests().isEmpty(), "bez dostawcy pak nieaktywny");
        catalog.setActivePackSupplier(() -> "jesien");
        assertEquals(List.of("sq_event"),
                catalog.activePackQuests().stream().map(d -> d.id()).toList());
    }

    @Test
    void rejectsMissingSection() {
        assertThrows(IllegalArgumentException.class, () -> SeasonQuestCatalog.load(null));
    }

    @Test
    void rejectsMissingSchemaVersion() {
        String raw = VALID.replace("schema-version: 1\n", "");
        assertThrows(IllegalArgumentException.class,
                () -> SeasonQuestCatalog.load(yaml(raw)));
    }

    @Test
    void rejectsUnknownSchemaVersion() {
        String raw = VALID.replace("schema-version: 1", "schema-version: 2");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SeasonQuestCatalog.load(yaml(raw)));
        assertTrue(failure.getMessage().contains("schema-version"), failure.getMessage());
    }

    @Test
    void rejectsUnknownEventType() {
        String raw = VALID.replace("type: BREAK", "type: SMELT");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SeasonQuestCatalog.load(yaml(raw)));
        assertTrue(failure.getMessage().contains("nieznany typ 'SMELT'"), failure.getMessage());
    }

    @Test
    void rejectsBlankQuestId() {
        // Pusty klucz odrzuca już sam parser YAML; biała spacja przechodzi
        // przez YAML i musi zostać złapana przez walidację katalogu.
        String raw = VALID.replace("  sq_b:", "  ' ' :");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SeasonQuestCatalog.load(yaml(raw)));
        assertTrue(failure.getMessage().contains("pusty"), failure.getMessage());
    }

    @Test
    void rejectsEmptyDefinitions() {
        String raw = """
                schema-version: 1
                points-default: 100
                definitions: {}
                """;
        assertThrows(IllegalArgumentException.class,
                () -> SeasonQuestCatalog.load(yaml(raw)));
    }

    @Test
    void rejectsEmptyTargets() {
        String raw = VALID.replace("targets: [ZOMBIE]", "targets: []");
        assertThrows(IllegalArgumentException.class,
                () -> SeasonQuestCatalog.load(yaml(raw)));
    }

    @Test
    void rejectsUnknownIconMaterial() {
        String raw = VALID.replace("icon: IRON_SWORD", "icon: NOT_A_MATERIAL");
        assertThrows(IllegalArgumentException.class,
                () -> SeasonQuestCatalog.load(yaml(raw)));
    }

    @Test
    void rejectsNonPositiveGoal() {
        String raw = VALID.replace("goal: 5", "goal: 0");
        assertThrows(IllegalArgumentException.class,
                () -> SeasonQuestCatalog.load(yaml(raw)));
    }

    @Test
    void rejectsNonPositiveExplicitPoints() {
        String raw = VALID.replace("points: 50", "points: 0");
        assertThrows(IllegalArgumentException.class,
                () -> SeasonQuestCatalog.load(yaml(raw)));
    }

    @Test
    void rejectsMoreQuestsThanUnlockableSlots() {
        int totalSlots = DefaultSeasonPointService.UNLOCK_THRESHOLDS.length
                * DefaultSeasonPointService.SLOTS_PER_TIER[0];
        StringBuilder defs = new StringBuilder();
        for (int i = 0; i <= totalSlots; i++) {
            defs.append("  sq_x").append(i).append(": {type: BREAK, targets: [STONE], ")
                    .append("goal: 1, icon: STONE, name: 'x'}\n");
        }
        String raw = "schema-version: 1\npoints-default: 10\ndefinitions:\n" + defs;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SeasonQuestCatalog.load(yaml(raw)));
        assertTrue(failure.getMessage().contains("slotów odblokowań"), failure.getMessage());
    }
}
