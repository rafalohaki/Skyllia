package org.rafalohaki.wpmecore.addons.skyblock.oneblock;


import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneBlockContentTest {

    private OneBlockContent.Chapter chapter(String id,
                                            Map<Integer, Material> guaranteed,
                                            Map<Integer, OneBlockContent.Milestone> milestones,
                                            int blocksRequired) {
        return new OneBlockContent.Chapter(id, "Test", "opis", Material.GRASS_BLOCK, blocksRequired,
                List.of(new OneBlockContent.WeightedMaterial(Material.DIRT, 60.0),
                        new OneBlockContent.WeightedMaterial(Material.STONE, 40.0)),
                guaranteed,
                new OneBlockContent.Bonus("skyblock:crystal/citrine", 0.15, 400),
                new OneBlockContent.Mobs(0.0,
                        List.of(new OneBlockContent.WeightedEntity(EntityType.PIG, 100.0))),
                milestones);
    }

    @Test
    void weightedBlockPickAtFixedSeedMatchesExpectedDistribution() {
        OneBlockContent.Chapter chapter = chapter("test", Map.of(), Map.of(), 100);
        int dirt = 0;
        Random random = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            if (OneBlockContent.pickBlock(chapter, random) == Material.DIRT) {
                dirt++;
            }
        }
        assertTrue(dirt > 5_500 && dirt < 6_500,
                " oczekiwano ~60% DIRT, było " + dirt);
    }

    @Test
    void milestoneLootRollsWithReplacementAndSumsAmounts() {
        OneBlockContent.Milestone milestone = new OneBlockContent.Milestone("Test", 3, List.of(
                new OneBlockContent.MilestoneReward(null, Material.IRON_INGOT, 2, 100.0)));
        List<OneBlockContent.MilestoneReward> loot =
                OneBlockContent.rollMilestoneLoot(milestone, new Random(7));
        assertEquals(1, loot.size());
        assertEquals(6, loot.getFirst().amount());
    }

    @Test
    void milestoneLootKeepsSeparateRewardsSeparate() {
        OneBlockContent.Milestone milestone = new OneBlockContent.Milestone("Test", 10, List.of(
                new OneBlockContent.MilestoneReward(null, Material.DIAMOND, 1, 50.0),
                new OneBlockContent.MilestoneReward(null, Material.EMERALD, 1, 50.0)));
        List<OneBlockContent.MilestoneReward> loot =
                OneBlockContent.rollMilestoneLoot(milestone, new Random(11));
        assertEquals(2, loot.size());
        assertEquals(10, loot.stream().mapToInt(OneBlockContent.MilestoneReward::amount).sum());
    }

    /**
     * ONEBLOCK-4: klamp nieznanej fazy idzie na rozdział <b>pierwszy</b>, nie
     * ostatni. Ostatni jest najbogatszy i bez następnika, więc literówka w
     * {@code oneblock.yml} awansowała wszystkie wyspy w tej fazie prosto do
     * endgame'u. Fail-closed znaczy tutaj „do najuboższego”.
     */
    @Test
    void chapterLookupClampsUnknownIdToFirstChapter() {
        OneBlockContent content = new OneBlockContent(new OneBlockContent.Settings(25, 40), List.of(
                chapter("alpha", Map.of(), Map.of(), 100),
                chapter("beta", Map.of(), Map.of(), 100)));
        assertEquals("alpha", content.chapter("nieznany").id());
        assertEquals("alpha", content.chapter(null).id());
        assertEquals("alpha", content.nextChapterId("nieznany"));
        assertFalse(content.isKnownPhase("nieznany"));
        assertTrue(content.isKnownPhase("beta"));

        assertTrue(content.hasNext(content.phaseIds().getFirst()));
        assertEquals(content.phaseIds().get(1), content.nextChapterId(content.phaseIds().getFirst()));
        // Ostatni znany rozdział nie ma następnika — zostaje sobą.
        assertEquals("beta", content.nextChapterId("beta"));
    }

    @Test
    void pickMobReturnsNullForEmptyTable() {
        assertNull(OneBlockContent.pickMob(
                new OneBlockContent.Mobs(5.0, List.of()), new Random(1)));
    }

    @Test
    void constructorRejectsNullChaptersList() {
        assertThrows(NullPointerException.class,
                () -> new OneBlockContent(new OneBlockContent.Settings(25, 40), null));
    }
}
