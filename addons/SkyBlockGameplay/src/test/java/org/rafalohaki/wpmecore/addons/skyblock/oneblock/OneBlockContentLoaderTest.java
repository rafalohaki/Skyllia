package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import org.rafalohaki.wpmecore.addons.skyblock.FakeCustomItemService;


import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.File;
import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Material.isBlock()/EntityType route through org.bukkit.Registry, which needs a
 * RegistryAccess — hence the MockBukkit bootstrap and the entry in the root pom's
 * mockbukkit.test.exclude (runs in the mockbukkit-compat profile, like
 * SkyBlockSettingsTest).
 */
@Tag("mockbukkit")
class OneBlockContentLoaderTest {

    @BeforeAll
    static void startBukkitRegistry() {
        MockBukkit.mock();
    }

    @AfterAll
    static void stopBukkitRegistry() {
        MockBukkit.unmock();
    }

    private static YamlConfiguration yaml(String content) {
        return YamlConfiguration.loadConfiguration(new StringReader(content));
    }

    private static final String VALID_MINIMAL = """
            schema: 1
            settings:
              checkpoint-every: 25
              mob-warning-ticks: 40
            phases:
              - id: poczatek
                name: 'Początek'
                subtitle: 'opis'
                icon: GRASS_BLOCK
                blocks-required: 250
                blocks:
                  DIRT: 100.0
                bonus:
                  item: 'skyblock:crystal/citrine'
                  chance: 0.15
                  pity: 400
                mobs:
                  spawn-chance: 3.0
                  table:
                    PIG: 100.0
                milestones:
                  100:
                    name: 'setka'
                    rolls: 1
                    rewards:
                      - {material: IRON_INGOT, amount: 8, weight: 60}
            """;

    @Test
    void bundledResourceLoadsSevenChapters() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new File("src/main/resources/oneblock.yml"));
        OneBlockContent content = OneBlockContentLoader.parse(config, FakeCustomItemService.SKYBLOCK);
        assertEquals(7, content.chapters().size());
        assertEquals("zima", content.phaseIds().get(2));
        assertEquals("kres", content.phaseIds().getLast());
        assertEquals(Material.OAK_LOG,
                content.chapter("poczatek").guaranteed().get(50));
        assertTrue(content.chapter("poczatek").milestones().containsKey(100));
    }

    @Test
    void minimalValidConfigParses() {
        OneBlockContent content = OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL), FakeCustomItemService.SKYBLOCK);
        assertEquals(1, content.chapters().size());
        assertEquals(25, content.settings().checkpointEvery());
    }

    @Test
    void biomeParsedUppercasedAndOptional() {
        OneBlockContent content = OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("icon: GRASS_BLOCK", "icon: GRASS_BLOCK\n    biome: snowy_plains")),
                FakeCustomItemService.SKYBLOCK);
        assertEquals("SNOWY_PLAINS", content.chapter("poczatek").biome());
        OneBlockContent bare = OneBlockContentLoader.parse(yaml(VALID_MINIMAL),
                FakeCustomItemService.SKYBLOCK);
        assertEquals("", bare.chapter("poczatek").biome());
    }

    @Test
    void unknownBiomeFailsClosed() {
        IllegalArgumentException err = assertThrows(IllegalArgumentException.class,
                () -> OneBlockContentLoader.parse(yaml(VALID_MINIMAL.replace(
                        "icon: GRASS_BLOCK", "icon: GRASS_BLOCK\n    biome: NIE_BIOM")),
                        FakeCustomItemService.SKYBLOCK));
        assertTrue(err.getMessage().contains("phases[0].biome"), err.getMessage());
    }

    @Test
    void broadcastPhasesDefaultsTrueAndOverridable() {
        OneBlockContent def = OneBlockContentLoader.parse(yaml(VALID_MINIMAL),
                FakeCustomItemService.SKYBLOCK);
        assertTrue(def.settings().broadcastPhases());
        OneBlockContent off = OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("checkpoint-every: 25",
                        "checkpoint-every: 25\n  broadcast-phases: false")),
                FakeCustomItemService.SKYBLOCK);
        assertEquals(false, off.settings().broadcastPhases());
    }

    @Test
    void bundledResourceHasBiomesForAllChapters() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new File("src/main/resources/oneblock.yml"));
        OneBlockContent content = OneBlockContentLoader.parse(config, FakeCustomItemService.SKYBLOCK);
        assertEquals("PLAINS", content.chapter("poczatek").biome());
        assertEquals("SNOWY_PLAINS", content.chapter("zima").biome());
        assertEquals("THE_END", content.chapter("kres").biome());
    }

    @Test
    void wrongSchemaFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("schema: 1", "schema: 2")),
                FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void nonBlockMaterialFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("DIRT: 100.0", "STICK: 100.0")),
                FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void emptyBlocksTableFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("blocks:\n      DIRT: 100.0", "blocks: {}")),
                FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void bonusChanceOutOfRangeFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("chance: 0.15", "chance: 150.0")),
                FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void unknownBonusItemFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("skyblock:crystal/citrine", "skyblock:crystal/nieznany")),
                FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void nullCustomItemsServiceFailsWithServiceUnavailableMessage() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OneBlockContentLoader.parse(yaml(VALID_MINIMAL), null));
        assertTrue(e.getMessage().contains("usługa CustomItems nie jest dostępna"),
                "oczekiwano komunikatu o niedostępnej usłudze, było: " + e.getMessage());
        assertTrue(e.getMessage().contains("sprawdź kolejność ładowania pluginów"));
    }

    @Test
    void rewardWithBothItemAndMaterialFails() {
        String both = VALID_MINIMAL.replace(
                "- {material: IRON_INGOT, amount: 8, weight: 60}",
                "- {material: IRON_INGOT, item: 'skyblock:crystal/citrine', amount: 8, weight: 60}");
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(both), FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void rewardWithNeitherItemNorMaterialFails() {
        String neither = VALID_MINIMAL.replace(
                "- {material: IRON_INGOT, amount: 8, weight: 60}",
                "- {amount: 8, weight: 60}");
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(neither), FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void nonPositiveMilestoneKeyFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("      100:", "      zero:")),
                FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void zeroRollsFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("rolls: 1", "rolls: 0")),
                FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void duplicatePhaseIdFails() {
        String duplicated = VALID_MINIMAL + """
                  - id: poczatek
                    name: 'Drugi'
                    subtitle: 'opis'
                    icon: DIRT
                    blocks-required: 10
                    blocks:
                      DIRT: 1.0
                    bonus:
                      item: 'skyblock:crystal/citrine'
                      chance: 1.0
                      pity: 10
                    mobs:
                      spawn-chance: 0.0
                      table: {}
                    milestones: {}
                """;
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(duplicated), FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void zeroBlocksRequiredFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("blocks-required: 250", "blocks-required: 0")),
                FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void emptyPhasesListFails() {
        String emptyPhases = VALID_MINIMAL.substring(0, VALID_MINIMAL.indexOf("phases:")) + "phases: []";
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(emptyPhases), FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void nonPositivePityFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("pity: 400", "pity: 0")),
                FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void negativeMobSpawnChanceFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("spawn-chance: 3.0", "spawn-chance: -5.0")),
                FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void nonSpawnableEntityFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("PIG: 100.0", "PLAYER: 100.0")),
                FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void nonPositiveGuaranteedKeyFails() {
        String zeroKey = VALID_MINIMAL.replace(
                "    bonus:\n",
                "    guaranteed:\n      0: OAK_LOG\n    bonus:\n");
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(zeroKey), FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void emptyMilestoneRewardsFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace(
                        "rewards:\n          - {material: IRON_INGOT, amount: 8, weight: 60}",
                        "rewards: []")),
                FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void nonPositiveRewardWeightFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("weight: 60}", "weight: -60}")),
                FakeCustomItemService.SKYBLOCK));
    }

    @Test
    void nonFiniteBonusChanceFails() {
        assertThrows(IllegalArgumentException.class, () -> OneBlockContentLoader.parse(
                yaml(VALID_MINIMAL.replace("chance: 0.15", "chance: .nan")),
                FakeCustomItemService.SKYBLOCK));
    }
}
