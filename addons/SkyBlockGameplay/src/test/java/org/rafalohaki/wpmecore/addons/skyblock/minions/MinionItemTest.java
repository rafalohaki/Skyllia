package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinionItemTest {

    private ItemStack minionItemStack(String typeId, int tier, boolean compactor,
                                      String fuelType, long fuelExpires, String storage) {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.PLAYER_HEAD);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(eq(MinionItem.KEY_MINION_ITEM), eq(PersistentDataType.STRING)))
                .thenReturn("true");
        when(pdc.get(eq(MinionItem.KEY_MINION_TYPE), eq(PersistentDataType.STRING)))
                .thenReturn(typeId);
        when(pdc.get(eq(MinionItem.KEY_MINION_TIER), eq(PersistentDataType.INTEGER)))
                .thenReturn(tier);
        when(pdc.get(eq(MinionItem.KEY_MINION_COMPACTOR), eq(PersistentDataType.BYTE)))
                .thenReturn((byte) (compactor ? 1 : 0));
        when(pdc.get(eq(MinionItem.KEY_MINION_FUEL_TYPE), eq(PersistentDataType.STRING)))
                .thenReturn(fuelType);
        when(pdc.get(eq(MinionItem.KEY_MINION_FUEL_EXPIRES), eq(PersistentDataType.LONG)))
                .thenReturn(fuelExpires);
        when(pdc.get(eq(MinionItem.KEY_MINION_STORAGE), eq(PersistentDataType.STRING)))
                .thenReturn(storage);
        return item;
    }

    @Test
    void roundTripsAllPdcTags() {
        ItemStack item = minionItemStack("diamond", 3, true, "coal", 123456L, "DIAMOND=9");

        assertTrue(MinionItem.isMinionItem(item));
        assertEquals("diamond", MinionItem.typeId(item));
        assertEquals(3, MinionItem.tier(item));
        assertTrue(MinionItem.compactorEnabled(item));
        assertEquals("coal", MinionItem.fuelType(item));
        assertEquals(123456L, MinionItem.fuelExpiresAt(item));
        assertEquals("DIAMOND=9", MinionItem.storageEncoded(item));
    }

    @Test
    void rejectsNonMinionItemsAndDefaults() {
        ItemStack plain = mock(ItemStack.class);
        when(plain.getType()).thenReturn(Material.DIAMOND);
        when(plain.hasItemMeta()).thenReturn(false);

        assertFalse(MinionItem.isMinionItem(plain));
        assertFalse(MinionItem.isMinionItem(null));
        assertEquals(1, MinionItem.tier(plain));      // wartość domyślna
        assertEquals("", MinionItem.storageEncoded(plain));
        assertFalse(MinionItem.compactorEnabled(plain));
    }
}
