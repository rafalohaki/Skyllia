package org.rafalohaki.wpmecore.addons.skyblock.hub;


import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkyBlockHubSpawnProtectionTest {

    private JavaPlugin createPluginMock() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("SkyBlockGameplay");
        when(plugin.getLogger()).thenReturn(Logger.getGlobal());
        return plugin;
    }

    @Test
    @DisplayName("Food level change is cancelled on spawn world and keeps food at 20")
    void cancelsFoodLevelChangeOnSpawn() {
        JavaPlugin mockPlugin = createPluginMock();
        MiniMessage miniMessage = MiniMessage.miniMessage();
        SpawnPoint spawnPoint = new SpawnPoint(
                "world", 0.0D, 80.0D, 0.0D, 0.0F, 0.0F);
        HubSettings settings = new HubSettings(
                spawnPoint, true, 40, false, false, List.of(),
                true, true, true, true);

        SkyBlockHub hub = new SkyBlockHub(mockPlugin, miniMessage, settings);

        World spawnWorld = mock(World.class);
        when(spawnWorld.getName()).thenReturn("world");

        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(spawnWorld);

        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, 15);
        hub.onFoodLevelChange(event);

        assertTrue(event.isCancelled(), "Food level change must be cancelled on spawn world");
        verify(player).setFoodLevel(20);
        verify(player).setSaturation(20.0F);
    }

    @Test
    @DisplayName("Food level change is NOT cancelled on island world")
    void allowsFoodLevelChangeOnIslands() {
        JavaPlugin mockPlugin = createPluginMock();
        MiniMessage miniMessage = MiniMessage.miniMessage();
        SpawnPoint spawnPoint = new SpawnPoint(
                "world", 0.0D, 80.0D, 0.0D, 0.0F, 0.0F);
        HubSettings settings = new HubSettings(
                spawnPoint, true, 40, false, false, List.of(),
                true, true, true, true);

        SkyBlockHub hub = new SkyBlockHub(mockPlugin, miniMessage, settings);

        World islandWorld = mock(World.class);
        when(islandWorld.getName()).thenReturn("sky-overworld");

        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(islandWorld);

        FoodLevelChangeEvent event = new FoodLevelChangeEvent(player, 15);
        hub.onFoodLevelChange(event);

        assertFalse(event.isCancelled(), "Food level change must NOT be cancelled on island worlds");
        verify(player, never()).setFoodLevel(20);
    }

    @Test
    @DisplayName("Damage is cancelled on spawn world when invulnerable is enabled")
    void cancelsDamageOnSpawnWorld() {
        JavaPlugin mockPlugin = createPluginMock();
        MiniMessage miniMessage = MiniMessage.miniMessage();
        SpawnPoint spawnPoint = new SpawnPoint(
                "world", 0.0D, 80.0D, 0.0D, 0.0F, 0.0F);
        HubSettings settings = new HubSettings(
                spawnPoint, true, 40, false, false, List.of(),
                true, true, true, true);

        SkyBlockHub hub = new SkyBlockHub(mockPlugin, miniMessage, settings);

        World spawnWorld = mock(World.class);
        when(spawnWorld.getName()).thenReturn("world");

        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(spawnWorld);

        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.FALL);

        hub.onDamage(event);

        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("Damage is NOT cancelled on island world")
    void allowsDamageOnIslandWorld() {
        JavaPlugin mockPlugin = createPluginMock();
        MiniMessage miniMessage = MiniMessage.miniMessage();
        SpawnPoint spawnPoint = new SpawnPoint(
                "world", 0.0D, 80.0D, 0.0D, 0.0F, 0.0F);
        HubSettings settings = new HubSettings(
                spawnPoint, true, 40, false, false, List.of(),
                true, true, true, true);

        SkyBlockHub hub = new SkyBlockHub(mockPlugin, miniMessage, settings);

        World islandWorld = mock(World.class);
        when(islandWorld.getName()).thenReturn("sky-overworld");

        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(islandWorld);

        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);

        hub.onDamage(event);

        verify(event, never()).setCancelled(true);
    }
}
