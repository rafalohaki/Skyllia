package org.rafalohaki.wpmecore.addons.skyblock.automation;

import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;

import org.rafalohaki.wpmecore.addons.skyblock.shop.ShopCatalog;


import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomationIntegrationTest {

    private MiniMessage miniMessage;
    private ChunkerDao chunkerDao;
    private SellChestDao sellChestDao;
    private ChunkerService chunkerService;
    private SellChestService sellChestService;
    private SkylliaIntegration skyllia;
    private LedgerService ledger;
    private ShopCatalog shopCatalog;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        miniMessage = MiniMessage.miniMessage();
        chunkerDao = mock(ChunkerDao.class);
        sellChestDao = mock(SellChestDao.class);
        skyllia = mock(SkylliaIntegration.class);
        ledger = mock(LedgerService.class);
        shopCatalog = mock(ShopCatalog.class);
        plugin = mock(Plugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AutomationTest"));

        when(chunkerDao.loadAll()).thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(chunkerDao.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(chunkerDao.deleteByIsland(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(chunkerDao.deleteByChunk(anyString(), any(int.class), any(int.class))).thenReturn(CompletableFuture.completedFuture(null));

        when(sellChestDao.loadAll()).thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(sellChestDao.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(sellChestDao.deleteByIsland(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(sellChestDao.deleteByLocation(anyString(), any(int.class), any(int.class), any(int.class))).thenReturn(CompletableFuture.completedFuture(null));

        chunkerService = new ChunkerService(plugin, chunkerDao, miniMessage, skyllia);
        chunkerService.initialize().join();

        sellChestService = new SellChestService(plugin, sellChestDao, shopCatalog, ledger, miniMessage, skyllia);
        sellChestService.initialize().join();
    }

    @Test
    void testCustomItemPdcVerification() {
        ItemStack chunkerMock = mock(ItemStack.class);
        ItemMeta chunkerMeta = mock(ItemMeta.class);
        PersistentDataContainer chunkerPdc = mock(PersistentDataContainer.class);
        when(chunkerMock.getType()).thenReturn(Material.HOPPER);
        when(chunkerMock.hasItemMeta()).thenReturn(true);
        when(chunkerMock.getItemMeta()).thenReturn(chunkerMeta);
        when(chunkerMeta.getPersistentDataContainer()).thenReturn(chunkerPdc);
        when(chunkerPdc.get(ChunkerService.KEY_CUSTOM_ITEM, PersistentDataType.STRING)).thenReturn(ChunkerService.CUSTOM_ITEM_TAG);

        assertTrue(chunkerService.isChunkerItem(chunkerMock));
        assertFalse(sellChestService.isSellChestItem(chunkerMock));

        ItemStack sellChestMock = mock(ItemStack.class);
        ItemMeta sellChestMeta = mock(ItemMeta.class);
        PersistentDataContainer sellChestPdc = mock(PersistentDataContainer.class);
        when(sellChestMock.getType()).thenReturn(Material.CHEST);
        when(sellChestMock.hasItemMeta()).thenReturn(true);
        when(sellChestMock.getItemMeta()).thenReturn(sellChestMeta);
        when(sellChestMeta.getPersistentDataContainer()).thenReturn(sellChestPdc);
        when(sellChestPdc.get(SellChestService.KEY_CUSTOM_ITEM, PersistentDataType.STRING)).thenReturn(SellChestService.CUSTOM_ITEM_TAG);

        assertTrue(sellChestService.isSellChestItem(sellChestMock));
        assertFalse(chunkerService.isChunkerItem(sellChestMock));

        ItemStack plainHopper = mock(ItemStack.class);
        ItemMeta plainMeta = mock(ItemMeta.class);
        PersistentDataContainer plainPdc = mock(PersistentDataContainer.class);
        when(plainHopper.getType()).thenReturn(Material.HOPPER);
        when(plainHopper.hasItemMeta()).thenReturn(true);
        when(plainHopper.getItemMeta()).thenReturn(plainMeta);
        when(plainMeta.getPersistentDataContainer()).thenReturn(plainPdc);
        assertFalse(chunkerService.isChunkerItem(plainHopper));
    }

    @Test
    void testIslandDeletionUnregistersChunkersAndSellChests() {
        UUID islandId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        ChunkerRecord chunker = new ChunkerRecord(islandId, "world", 5, -3, 80, 64, -40, ownerId);
        chunkerService.registerChunker(chunker).join();
        assertEquals(1, chunkerService.getChunkerCountForIsland(islandId));
        assertTrue(chunkerService.hasChunker("world", 5, -3));

        SellChestRecord sellChest = new SellChestRecord(islandId, "world", 85, 64, -45, ownerId, System.currentTimeMillis());
        sellChestService.registerSellChest(sellChest).join();
        assertEquals(1, sellChestService.getSellChestCountForIsland(islandId));
        assertTrue(sellChestService.hasSellChest("world", 85, 64, -45));

        // Simulate island delete hook
        chunkerService.unregisterByIsland(islandId).join();
        sellChestService.unregisterByIsland(islandId).join();

        assertEquals(0, chunkerService.getChunkerCountForIsland(islandId));
        assertFalse(chunkerService.hasChunker("world", 5, -3));
        verify(chunkerDao).deleteByIsland(islandId);

        assertEquals(0, sellChestService.getSellChestCountForIsland(islandId));
        assertFalse(sellChestService.hasSellChest("world", 85, 64, -45));
        verify(sellChestDao).deleteByIsland(islandId);
    }
}
