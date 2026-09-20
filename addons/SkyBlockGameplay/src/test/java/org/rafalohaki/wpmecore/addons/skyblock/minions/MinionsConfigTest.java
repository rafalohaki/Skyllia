package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinionsConfigTest {

    private static final String VALID = """
            schema-version: 1
            settings:
              max-minions-per-island: 5
              tick-interval-seconds: 1
              adjacent-chest-scan-radius: 1
              default-storage-slots: 2
              slots-per-tier-increase: 2
            fuels:
              coal:
                item: COAL
                duration-seconds: 3600
                speed-multiplier: 1.10
            compact-mappings:
              DIAMOND: DIAMOND_BLOCK
            minions:
              diamond:
                name: "<aqua>Minionek Diamentu</aqua>"
                head-texture: "abc123"
                primary-item: DIAMOND
                primary-amount: 1
                tiers:
                  1: { interval: 35.0, upgrade-cost: { money: 0, items: 0 } }
                  2: { interval: 28.0, storage-slots: 4, upgrade-cost: { money: 5000, items: 32 } }
                  3: { interval: 22.0, storage-slots: 6, upgrade-cost: { money: 20000, items: 64 } }
                  4: { interval: 16.0, storage-slots: 8, upgrade-cost: { money: 60000, items: 128 } }
                  5: { interval: 12.0, storage-slots: 10, upgrade-cost: { money: 150000, items: 256 } }
            """;

    private static YamlConfiguration yaml(String content) {
        return YamlConfiguration.loadConfiguration(new StringReader(content));
    }

    @Test
    void parsesValidDocumentAndDerivesMissingStorageSlots() {
        MinionsConfig config = MinionsConfig.parse(yaml(VALID), null);

        assertEquals(5, config.settings().maxMinionsPerIsland());
        assertEquals(1, config.settings().tickIntervalSeconds());
        assertEquals(2, config.settings().defaultStorageSlots());

        MinionsConfig.TierDef tier1 = config.tier("diamond", 1);
        assertNotNull(tier1);
        assertEquals(35.0, tier1.intervalSeconds());
        assertEquals(2, tier1.storageSlots()); // default + 0 * increase
        assertEquals(6, config.tier("diamond", 3).storageSlots());
        assertEquals(Material.DIAMOND_BLOCK, config.compactMappings().get(Material.DIAMOND));
        assertEquals(1.10, config.fuels().get("coal").speedMultiplier(), 1e-9);
        assertEquals(Material.COAL, config.fuels().get("coal").item());
    }

    @Test
    void rejectsWrongSchemaVersion() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> MinionsConfig.parse(yaml(VALID.replace("schema-version: 1", "schema-version: 2")), null));
        assertTrue(failure.getMessage().contains("schema-version"));
    }

    @Test
    void rejectsUnknownCustomBonusItem() {
        String content = VALID.replace(
                "4: { interval: 16.0, storage-slots: 8, upgrade-cost: { money: 60000, items: 128 } }",
                "4: { interval: 16.0, storage-slots: 8, upgrade-cost: { money: 60000, items: 128 }, "
                        + "bonus-drop: \"skyblock:crystal/nieznany\", bonus-chance: 2.0 }");
        // bonus-drop z nieistniejącym id, a customItems == null -> fail-closed
        assertThrows(IllegalArgumentException.class,
                () -> MinionsConfig.parse(yaml(content), null));
    }

    @Test
    void rejectsFuelWithBothVanillaAndCustomItem() {
        String content = VALID.replace("item: COAL", "item: COAL\n    custom-item: \"skyblock:crystal/amber\"");
        assertThrows(IllegalArgumentException.class, () -> MinionsConfig.parse(yaml(content), null));
    }

    @Test
    void rejectsTierMissingInSequence() {
        String content = VALID.replace("2: { interval: 28.0, storage-slots: 4, upgrade-cost: { money: 5000, items: 32 } }", "");
        assertThrows(IllegalArgumentException.class, () -> MinionsConfig.parse(yaml(content), null));
    }
}
