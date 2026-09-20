package org.rafalohaki.wpmecore.addons.skyblock.talisman;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TalismanListenerTest {

    @Test
    @DisplayName("hasTalisman zwraca true gdy gracz posiada przedmiot w ekwipunku")
    void returnsTrueWhenPlayerHasTalisman() {
        Plugin plugin = mock(Plugin.class);
        CustomItemService customItems = mock(CustomItemService.class);
        TalismanListener listener = new TalismanListener(plugin, null, customItems, null);

        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.PAPER);

        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new ItemStack[]{item});
        when(customItems.idOf(item)).thenReturn("skyblock:talisman/harvest_talisman");

        assertTrue(listener.hasTalisman(player, "skyblock:talisman/harvest_talisman"));
        assertFalse(listener.hasTalisman(player, "skyblock:talisman/miner_talisman"));
    }

    @Test
    @DisplayName("hasTalisman zwraca false gdy ekwipunek jest pusty lub customItems to null")
    void returnsFalseWhenEmpty() {
        Plugin plugin = mock(Plugin.class);
        TalismanListener listener = new TalismanListener(plugin, null, null, null);
        Player player = mock(Player.class);

        assertFalse(listener.hasTalisman(player, "skyblock:talisman/harvest_talisman"));
    }
}
