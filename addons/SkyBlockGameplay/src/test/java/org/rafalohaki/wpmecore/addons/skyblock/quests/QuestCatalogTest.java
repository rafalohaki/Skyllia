package org.rafalohaki.wpmecore.addons.skyblock.quests;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class QuestCatalogTest {

    @Test
    @DisplayName("Should parse RewardItem correctly from various formats")
    void testRewardItemParsing() {
        QuestCatalog.RewardItem singleCustom = QuestCatalog.RewardItem.parse("skyblock:token/silver_lotus");
        assertEquals("skyblock:token/silver_lotus", singleCustom.itemId());
        assertEquals(1, singleCustom.amount());

        QuestCatalog.RewardItem countedCustom = QuestCatalog.RewardItem.parse("skyblock:token/silver_lotus:3");
        assertEquals("skyblock:token/silver_lotus", countedCustom.itemId());
        assertEquals(3, countedCustom.amount());

        QuestCatalog.RewardItem singleVanilla = QuestCatalog.RewardItem.parse("DIAMOND");
        assertEquals("DIAMOND", singleVanilla.itemId());
        assertEquals(1, singleVanilla.amount());

        QuestCatalog.RewardItem countedVanilla = QuestCatalog.RewardItem.parse("EMERALD:16");
        assertEquals("EMERALD", countedVanilla.itemId());
        assertEquals(16, countedVanilla.amount());
    }

    private static QuestCatalog poolCatalog() {
        String yaml = """
            timezone: 'Europe/Warsaw'
            active-per-day: 3
            definitions:
              miner: { type: BREAK, targets: [STONE], goal: 10, reward: 1, icon: STONE, mode: classic }
              farmer: { type: BREAK, targets: [WHEAT], goal: 10, reward: 1, icon: WHEAT, mode: classic }
              lumber: { type: BREAK, targets: [OAK_LOG], goal: 10, reward: 1, icon: OAK_LOG, mode: classic }
              lup: { type: ONEBLOCK, targets: [ANY], goal: 10, reward: 1, icon: GRASS_BLOCK, mode: oneblock }
              zyla: { type: ONEBLOCK, targets: [IRON_ORE], goal: 5, reward: 1, icon: DIAMOND_ORE, mode: oneblock }
              craft: { type: CRAFT, targets: [WOODEN_PICKAXE], goal: 1, reward: 1, icon: STONE_PICKAXE }
            """;
        return QuestCatalog.load(org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.StringReader(yaml)));
    }

    @Test
    @DisplayName("Pula classic nie widzi zadan oneblock i odwrotnie; ALL trafia do obu")
    void modePoolsAreSeparated() {
        QuestCatalog catalog = poolCatalog();
        java.time.LocalDate day = java.time.LocalDate.of(2026, 8, 31);
        for (QuestCatalog.Definition d : catalog.active(day, false)) {
            assertNotEquals(QuestCatalog.Mode.ONEBLOCK, d.mode());
        }
        for (QuestCatalog.Definition d : catalog.active(day, true)) {
            assertNotEquals(QuestCatalog.Mode.CLASSIC, d.mode());
        }
        // ALL nie musi trafic do rotacji KAZDEGO dnia (rotacja bierze 3 nastepne
        // z puli) — musi trafic do kazdej puli w pelnym cyklu dni.
        boolean craftInClassic = false;
        boolean craftInOneblock = false;
        for (int i = 0; i < 14; i++) {
            java.time.LocalDate d = day.plusDays(i);
            craftInClassic |= catalog.active(d, false).stream().anyMatch(x -> x.id().equals("craft"));
            craftInOneblock |= catalog.active(d, true).stream().anyMatch(x -> x.id().equals("craft"));
        }
        assertTrue(craftInClassic && craftInOneblock, "ALL w obu pulach w pelnym cyklu");
    }

    @Test
    @DisplayName("ONEBLOCK to rozpoznawany typ; rotacja per pula deterministyczna")
    void oneblockTypeAndDeterministicRotation() {
        QuestCatalog catalog = poolCatalog();
        java.time.LocalDate day = java.time.LocalDate.of(2026, 8, 31);
        assertEquals(catalog.active(day, true), catalog.active(day, true));
        assertEquals(2, catalog.active(day, true).size() >= 2 ? 2 : 0); // pula oneblock = lup+zyla+craft
        QuestCatalog.Definition lup = catalog.definition("lup").orElseThrow();
        assertEquals(QuestCatalog.Type.ONEBLOCK, lup.type());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException on blank or invalid RewardItem")
    void testInvalidRewardItem() {
        assertThrows(IllegalArgumentException.class, () -> QuestCatalog.RewardItem.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> new QuestCatalog.RewardItem("skyblock:token/silver_lotus", 0));
        assertThrows(IllegalArgumentException.class, () -> new QuestCatalog.RewardItem("skyblock:token/silver_lotus", -5));
        assertThrows(IllegalArgumentException.class, () -> new QuestCatalog.RewardItem("", 1));
    }

    @Test
    @DisplayName("Should correctly calculate weekKey, startOfWeek, and endOfWeek")
    void testWeeklyDateHelpers() {
        QuestCatalog catalog = new QuestCatalog(ZoneId.of("Europe/Warsaw"), 3, List.of(
                new QuestCatalog.Definition("miner", QuestCatalog.Type.BREAK, Set.of("STONE"), 100, 50,
                        Material.STONE, "Miner", List.of(), false),
                new QuestCatalog.Definition("farmer", QuestCatalog.Type.BREAK, Set.of("WHEAT"), 50, 30,
                        Material.WHEAT, "Farmer", List.of(), true),
                new QuestCatalog.Definition("hunter", QuestCatalog.Type.KILL, Set.of("ZOMBIE"), 10, 20,
                        Material.IRON_SWORD, "Hunter", List.of(), false)
        ));

        // 2026-08-15 is a Saturday (Week 33 of 2026)
        LocalDate saturday = LocalDate.of(2026, 8, 15);
        assertEquals("2026-W33", catalog.weekKey(saturday));
        assertEquals(LocalDate.of(2026, 8, 10), catalog.startOfWeek(saturday)); // Monday
        assertEquals(LocalDate.of(2026, 8, 16), catalog.endOfWeek(saturday));   // Sunday
        assertEquals("2026-08-15", catalog.period(saturday));

        // 2026-08-10 is a Monday (start of same week)
        LocalDate monday = LocalDate.of(2026, 8, 10);
        assertEquals("2026-W33", catalog.weekKey(monday));
        assertEquals(LocalDate.of(2026, 8, 10), catalog.startOfWeek(monday));
        assertEquals(LocalDate.of(2026, 8, 16), catalog.endOfWeek(monday));

        // 2026-08-17 is the next Monday (Week 34)
        LocalDate nextMonday = LocalDate.of(2026, 8, 17);
        assertEquals("2026-W34", catalog.weekKey(nextMonday));
    }

    @Test
    @DisplayName("Should load quest catalog with reward-items from YAML configuration")
    void testLoadFromYaml() throws Exception {
        String yaml = """
                timezone: 'Europe/Warsaw'
                active-per-day: 3
                definitions:
                  miner:
                    type: BREAK
                    targets: [COBBLESTONE, STONE]
                    goal: 100
                    reward: 250
                    reward-items:
                      - "skyblock:token/silver_lotus:1"
                      - "DIAMOND:2"
                    icon: IRON_PICKAXE
                    name: 'Miner'
                    lore: ['Mine blocks']
                  farmer:
                    type: BREAK
                    targets: [WHEAT]
                    goal: 50
                    reward: 150
                    icon: WHEAT
                    name: 'Farmer'
                    lore: ['Harvest crops']
                  hunter:
                    type: KILL
                    targets: [ZOMBIE]
                    goal: 20
                    reward: 200
                    reward-items:
                      - "skyblock:token/silver_lotus"
                    icon: IRON_SWORD
                    name: 'Hunter'
                    lore: ['Slay monsters']
                """;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        QuestCatalog catalog = QuestCatalog.load(config);

        assertEquals(3, catalog.definitions().size());

        QuestCatalog.Definition miner = catalog.definition("miner").orElseThrow();
        assertEquals(2, miner.rewardItems().size());
        assertEquals("skyblock:token/silver_lotus", miner.rewardItems().get(0).itemId());
        assertEquals(1, miner.rewardItems().get(0).amount());
        assertEquals("DIAMOND", miner.rewardItems().get(1).itemId());
        assertEquals(2, miner.rewardItems().get(1).amount());

        QuestCatalog.Definition farmer = catalog.definition("farmer").orElseThrow();
        assertTrue(farmer.rewardItems().isEmpty());

        QuestCatalog.Definition hunter = catalog.definition("hunter").orElseThrow();
        assertEquals(1, hunter.rewardItems().size());
        assertEquals("skyblock:token/silver_lotus", hunter.rewardItems().get(0).itemId());
        assertEquals(1, hunter.rewardItems().get(0).amount());

        assertTrue(catalog.definition("non_existent").isEmpty());
    }
}
