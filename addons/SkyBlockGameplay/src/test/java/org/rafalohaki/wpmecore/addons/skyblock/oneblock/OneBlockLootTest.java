package org.rafalohaki.wpmecore.addons.skyblock.oneblock;


import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OneBlockLoot#decode(String)} reads a database column, so its input can
 * be older than the current content file or hand-edited. Every malformed entry
 * must be skipped rather than thrown: the decoder runs while rendering the claim
 * menu and while handing out the reward, and an exception there takes the whole
 * screen down for loot that is otherwise fine.
 */
class OneBlockLootTest {

    @Test
    void roundTripsMixedRewards() {
        String encoded = OneBlockLoot.encode(List.of(
                new OneBlockContent.MilestoneReward("skyblock:token/gold_lotus", null, 3, 10.0),
                new OneBlockContent.MilestoneReward(null, Material.DIAMOND, 2, 90.0)));

        List<OneBlockLoot.Entry> decoded = OneBlockLoot.decode(encoded);

        assertEquals(2, decoded.size());
        assertEquals("skyblock:token/gold_lotus", decoded.get(0).item());
        assertEquals(3, decoded.get(0).amount());
        assertEquals(Material.DIAMOND, decoded.get(1).material());
        assertEquals(2, decoded.get(1).amount());
    }

    @Test
    void skipsEntryWithAnUnparsableAmountAndKeepsTheRest() {
        List<OneBlockLoot.Entry> decoded =
                OneBlockLoot.decode("material:IRON_INGOT:osiem;material:COAL:4");

        assertEquals(1, decoded.size());
        assertEquals(Material.COAL, decoded.getFirst().material());
        assertEquals(4, decoded.getFirst().amount());
    }

    @Test
    void skipsMaterialThatNoLongerExists() {
        List<OneBlockLoot.Entry> decoded =
                OneBlockLoot.decode("material:BLOK_KTORY_USUNIETO:1;material:COAL:4");

        assertEquals(1, decoded.size());
        assertEquals(Material.COAL, decoded.getFirst().material());
    }

    @Test
    void skipsNonPositiveAmounts() {
        assertTrue(OneBlockLoot.decode("material:COAL:0").isEmpty());
        assertTrue(OneBlockLoot.decode("material:COAL:-5").isEmpty());
    }

    /**
     * An entry carrying neither an item nor a material silently became a chest at
     * claim time, so a broken row paid out the wrong reward instead of nothing.
     */
    @Test
    void neverProducesAnEntryWithoutAnItemOrMaterial() {
        for (OneBlockLoot.Entry entry : OneBlockLoot.decode(
                "material:NIE_ISTNIEJE:1;item:skyblock:crystal/ruby:2;material:COAL:1")) {
            assertTrue(entry.item() != null || entry.material() != null,
                    "decoded entry must identify what to give");
        }
    }

    @Test
    void toleratesEmptyAndMalformedInput() {
        assertNotNull(OneBlockLoot.decode(""));
        assertTrue(OneBlockLoot.decode("").isEmpty());
        assertTrue(OneBlockLoot.decode(";;").isEmpty());
        assertTrue(OneBlockLoot.decode("bezdwukropka").isEmpty());
    }
}
