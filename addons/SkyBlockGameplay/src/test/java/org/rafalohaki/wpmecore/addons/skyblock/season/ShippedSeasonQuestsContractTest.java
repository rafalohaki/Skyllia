package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kontrakt wysyłanego {@code config.yml}: katalog questów sezonowych wypełnia
 * wszystkie sloty odblokowań, każdy cel da się dopasować w handlerze (cele nie są
 * walidowane przez loader — literówka = quest, którego nie da się zrobić), a suma
 * punktów kolejnych progów odblokowuje następny próg samymi questami.
 * Questy pakietowe ({@code pack:}) nie zajmują slotów, ale ich cele też muszą
 * się rozwiązywać, a ich slug musi wskazywać edycję z bundlowanego editions.yml.
 */
class ShippedSeasonQuestsContractTest {

    private static final Path CONFIG = Path.of("src/main/resources/config.yml");
    private static final Path EDITIONS = Path.of("src/main/resources/editions.yml");

    @Test
    void shippedCatalogFillsAllSlotsWithResolvableTargets() throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        try (Reader reader = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        SeasonQuestCatalog catalog = SeasonQuestCatalog.load(yaml.getConfigurationSection("season.quests"));
        List<SeasonQuestService.Definition> defs = catalog.universalQuests();

        int slots = 0;
        for (int per : DefaultSeasonPointService.SLOTS_PER_TIER) {
            slots += per;
        }
        assertEquals(slots, defs.size(), "questy uniwersalne mają wypełniać każdy slot odblokowań");
        assertTrue(catalog.definitions().size() > slots,
                "wysyłka ma questy pakietowe ponad limit slotów (nie liczą się do 40)");

        // Cele wszystkich questów — także pakietowych — muszą się rozwiązywać.
        for (SeasonQuestService.Definition def : catalog.definitions()) {
            for (String target : def.targets()) {
                if (target.equals("*")) {
                    continue;
                }
                boolean kill = def.type() == SeasonQuestService.Definition.Type.KILL;
                boolean resolvable = kill ? isEntity(target) : Material.matchMaterial(target) != null;
                assertTrue(resolvable, def.id() + ": cel '" + target + "' nie jest "
                        + (kill ? "EntityType" : "Material"));
            }
        }

        long cumulative = 0;
        int offset = 0;
        for (int tier = 0; tier < DefaultSeasonPointService.UNLOCK_THRESHOLDS.length - 1; tier++) {
            int per = DefaultSeasonPointService.SLOTS_PER_TIER[tier];
            for (SeasonQuestService.Definition def : defs.subList(offset, offset + per)) {
                cumulative += def.points();
            }
            offset += per;
            long next = DefaultSeasonPointService.UNLOCK_THRESHOLDS[tier + 1];
            assertTrue(cumulative >= next, "próg " + next + " nieosiągalny samymi questami: " + cumulative);
        }
    }

    @Test
    void shippedPackQuestsBelongToShippedEditionAndStayOutOfSlots() throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        try (Reader reader = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        YamlConfiguration editions = new YamlConfiguration();
        try (Reader reader = Files.newBufferedReader(EDITIONS, StandardCharsets.UTF_8)) {
            editions.load(reader);
        }
        editions.set("editions.enabled", true); // wysyłka jest wyłączona; treść walidujemy na kopii
        EditionRegistry registry = EditionRegistry.load(editions.getConfigurationSection("editions"));
        SeasonQuestCatalog catalog = SeasonQuestCatalog.load(config.getConfigurationSection("season.quests"));

        List<SeasonQuestService.Definition> packQuests = new java.util.ArrayList<>(catalog.definitions());
        packQuests.removeAll(catalog.universalQuests());
        assertFalse(packQuests.isEmpty(), "wysyłka ma zawierać questy pakietowe");

        String pack = registry.questPackBySlug("jesienny-event-2026");
        assertEquals("jesienny-event-2026", pack, "edycja eventowa wskazuje pakiet przez quest-pack");
        catalog.setActivePackSupplier(() -> pack);
        assertEquals(packQuests, catalog.activePackQuests(),
                "każdy quest pakietowy z wysyłki należy do pakietu edycji eventowej");
        for (SeasonQuestService.Definition def : packQuests) {
            assertTrue(def.points() >= 100 && def.points() <= 200,
                    def.id() + ": punkty eventu w skali 100–200, było " + def.points());
        }
    }

    private static boolean isEntity(String name) {
        try {
            EntityType.valueOf(name);
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }
}
