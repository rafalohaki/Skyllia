package org.rafalohaki.wpmecore.addons.skyblock.automation;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.automation.CobblestoneGeneratorListener.CustomDrop;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CobblestoneGeneratorListenerTest {

    @Test
    @DisplayName("Deepslate mapping converts standard ores to deepslate variants below Y=0")
    void testDeepslateMapping() {
        assertEquals(Material.COBBLED_DEEPSLATE, CobblestoneGeneratorListener.mapToDeepslate(Material.COBBLESTONE));
        assertEquals(Material.DEEPSLATE_DIAMOND_ORE, CobblestoneGeneratorListener.mapToDeepslate(Material.DIAMOND_ORE));
        assertEquals(Material.DEEPSLATE_GOLD_ORE, CobblestoneGeneratorListener.mapToDeepslate(Material.GOLD_ORE));
        assertEquals(Material.DEEPSLATE_IRON_ORE, CobblestoneGeneratorListener.mapToDeepslate(Material.IRON_ORE));
        assertEquals(Material.DEEPSLATE_COAL_ORE, CobblestoneGeneratorListener.mapToDeepslate(Material.COAL_ORE));
    }

    @Test
    @DisplayName("Rare materials classification flags diamond, emerald and gold")
    void testRareMaterials() {
        assertTrue(CobblestoneGeneratorListener.isRareMaterial(Material.DIAMOND_ORE));
        assertTrue(CobblestoneGeneratorListener.isRareMaterial(Material.EMERALD_ORE));
        assertTrue(CobblestoneGeneratorListener.isRareMaterial(Material.GOLD_ORE));
        assertFalse(CobblestoneGeneratorListener.isRareMaterial(Material.COBBLESTONE));
        assertFalse(CobblestoneGeneratorListener.isRareMaterial(Material.COAL_ORE));
    }

    @Test
    @DisplayName("Default custom drops configured properly for Overworld, Deepslate and Nether")
    void testDefaultEnvironmentDrops() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);

        CobblestoneGeneratorListener listener = new CobblestoneGeneratorListener(plugin, skyllia);

        // Overworld (NORMAL, Y > 0)
        List<CustomDrop> overworldDrops = listener.getCustomDropsFor(World.Environment.NORMAL, 64);
        assertEquals(2, overworldDrops.size());
        assertEquals("skyblock:crystal/citrine", overworldDrops.get(0).itemId());
        assertEquals(0.8, overworldDrops.get(0).chance(), 0.001);
        assertEquals("skyblock:metal/raw_tungsten", overworldDrops.get(1).itemId());
        assertEquals(0.3, overworldDrops.get(1).chance(), 0.001);

        // Deepslate (NORMAL, Y <= 0)
        List<CustomDrop> deepslateDrops = listener.getCustomDropsFor(World.Environment.NORMAL, -10);
        assertEquals(2, deepslateDrops.size());
        assertEquals("skyblock:crystal/onyx", deepslateDrops.get(0).itemId());
        assertEquals(0.4, deepslateDrops.get(0).chance(), 0.001);
        assertEquals("skyblock:metal/raw_umber", deepslateDrops.get(1).itemId());
        assertEquals(0.2, deepslateDrops.get(1).chance(), 0.001);

        // Boundary Y = 0 (Deepslate)
        List<CustomDrop> boundaryDeepslate = listener.getCustomDropsFor(World.Environment.NORMAL, 0);
        assertEquals(2, boundaryDeepslate.size());
        assertEquals("skyblock:crystal/onyx", boundaryDeepslate.get(0).itemId());

        // Boundary Y = 1 (Overworld)
        List<CustomDrop> boundaryOverworld = listener.getCustomDropsFor(World.Environment.NORMAL, 1);
        assertEquals(2, boundaryOverworld.size());
        assertEquals("skyblock:crystal/citrine", boundaryOverworld.get(0).itemId());

        // Nether (NETHER, any Y)
        List<CustomDrop> netherDropsY64 = listener.getCustomDropsFor(World.Environment.NETHER, 64);
        assertEquals(1, netherDropsY64.size());
        assertEquals("skyblock:crystal/topaz", netherDropsY64.get(0).itemId());
        assertEquals(0.5, netherDropsY64.get(0).chance(), 0.001);

        List<CustomDrop> netherDropsYNeg = listener.getCustomDropsFor(World.Environment.NETHER, -10);
        assertEquals(1, netherDropsYNeg.size());
        assertEquals("skyblock:crystal/topaz", netherDropsYNeg.get(0).itemId());

        // End (THE_END)
        List<CustomDrop> endDrops = listener.getCustomDropsFor(World.Environment.THE_END, 64);
        assertTrue(endDrops.isEmpty());
    }

    @Test
    @DisplayName("Overworld custom drop rolls citrine or raw_tungsten based on roll value")
    void testOverworldDropRolling() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);

        CobblestoneGeneratorListener listener = new CobblestoneGeneratorListener(plugin, skyllia);

        // Citrine: 0.0 <= roll < 0.8
        assertEquals("skyblock:crystal/citrine", listener.rollCustomDrop(World.Environment.NORMAL, 64, 0.0));
        assertEquals("skyblock:crystal/citrine", listener.rollCustomDrop(World.Environment.NORMAL, 64, 0.5));
        assertEquals("skyblock:crystal/citrine", listener.rollCustomDrop(World.Environment.NORMAL, 64, 0.799));

        // Raw Tungsten: 0.8 <= roll < 1.1 (0.8 + 0.3 = 1.1)
        assertEquals("skyblock:metal/raw_tungsten", listener.rollCustomDrop(World.Environment.NORMAL, 64, 0.8));
        assertEquals("skyblock:metal/raw_tungsten", listener.rollCustomDrop(World.Environment.NORMAL, 64, 1.0));
        assertEquals("skyblock:metal/raw_tungsten", listener.rollCustomDrop(World.Environment.NORMAL, 64, 1.099));

        // No drop: roll >= 1.1
        assertNull(listener.rollCustomDrop(World.Environment.NORMAL, 64, 1.11));
        assertNull(listener.rollCustomDrop(World.Environment.NORMAL, 64, 50.0));
        assertNull(listener.rollCustomDrop(World.Environment.NORMAL, 64, 99.9));
    }

    @Test
    @DisplayName("Deepslate custom drop rolls onyx or raw_umber based on roll value")
    void testDeepslateDropRolling() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);

        CobblestoneGeneratorListener listener = new CobblestoneGeneratorListener(plugin, skyllia);

        // Onyx: 0.0 <= roll < 0.4
        assertEquals("skyblock:crystal/onyx", listener.rollCustomDrop(World.Environment.NORMAL, 0, 0.0));
        assertEquals("skyblock:crystal/onyx", listener.rollCustomDrop(World.Environment.NORMAL, -20, 0.2));
        assertEquals("skyblock:crystal/onyx", listener.rollCustomDrop(World.Environment.NORMAL, -50, 0.399));

        // Raw Umber: 0.4 <= roll < 0.6 (0.4 + 0.2 = 0.6)
        assertEquals("skyblock:metal/raw_umber", listener.rollCustomDrop(World.Environment.NORMAL, 0, 0.4));
        assertEquals("skyblock:metal/raw_umber", listener.rollCustomDrop(World.Environment.NORMAL, -20, 0.5));
        assertEquals("skyblock:metal/raw_umber", listener.rollCustomDrop(World.Environment.NORMAL, -50, 0.599));

        // No drop: roll >= 0.6
        assertNull(listener.rollCustomDrop(World.Environment.NORMAL, 0, 0.61));
        assertNull(listener.rollCustomDrop(World.Environment.NORMAL, -20, 10.0));
    }

    @Test
    @DisplayName("Nether custom drop rolls topaz based on roll value")
    void testNetherDropRolling() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);

        CobblestoneGeneratorListener listener = new CobblestoneGeneratorListener(plugin, skyllia);

        // Topaz: 0.0 <= roll < 0.5
        assertEquals("skyblock:crystal/topaz", listener.rollCustomDrop(World.Environment.NETHER, 64, 0.0));
        assertEquals("skyblock:crystal/topaz", listener.rollCustomDrop(World.Environment.NETHER, 64, 0.25));
        assertEquals("skyblock:crystal/topaz", listener.rollCustomDrop(World.Environment.NETHER, 64, 0.499));

        // No drop: roll >= 0.5
        assertNull(listener.rollCustomDrop(World.Environment.NETHER, 64, 0.51));
        assertNull(listener.rollCustomDrop(World.Environment.NETHER, 64, 5.0));
    }

    @Test
    @DisplayName("Custom drops load correctly from yaml configuration")
    void testConfigLoading() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("generator.ores.enabled", true);
        config.set("generator.ores.rates.COBBLESTONE", 50.0);
        config.set("generator.custom-drops.enabled", true);
        config.set("generator.custom-drops.overworld.skyblock:crystal/citrine", 1.5);
        config.set("generator.custom-drops.deepslate.skyblock:crystal/onyx", 2.0);
        config.set("generator.custom-drops.nether.skyblock:crystal/topaz", 3.0);

        when(plugin.getConfig()).thenReturn(config);
        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);

        CobblestoneGeneratorListener listener = new CobblestoneGeneratorListener(plugin, skyllia);

        List<CustomDrop> overworld = listener.getCustomDropsFor(World.Environment.NORMAL, 64);
        assertEquals(1, overworld.size());
        assertEquals("skyblock:crystal/citrine", overworld.get(0).itemId());
        assertEquals(1.5, overworld.get(0).chance(), 0.001);

        List<CustomDrop> deepslate = listener.getCustomDropsFor(World.Environment.NORMAL, -5);
        assertEquals(1, deepslate.size());
        assertEquals("skyblock:crystal/onyx", deepslate.get(0).itemId());
        assertEquals(2.0, deepslate.get(0).chance(), 0.001);

        List<CustomDrop> nether = listener.getCustomDropsFor(World.Environment.NETHER, 64);
        assertEquals(1, nether.size());
        assertEquals("skyblock:crystal/topaz", nether.get(0).itemId());
        assertEquals(3.0, nether.get(0).chance(), 0.001);
    }

    @Test
    @DisplayName("CustomItemService resolution and creation creates item or falls back gracefully")
    void testCustomItemCreation() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);
        CustomItemService customItemService = mock(CustomItemService.class);

        ItemStack mockCitrine = mock(ItemStack.class);
        when(customItemService.create("skyblock:crystal/citrine")).thenReturn(Optional.of(mockCitrine));
        when(customItemService.create("nonexistent:item")).thenReturn(Optional.empty());

        CobblestoneGeneratorListener listener = new CobblestoneGeneratorListener(plugin, skyllia, customItemService);

        assertEquals(mockCitrine, listener.createCustomItem("skyblock:crystal/citrine"));
        assertNull(listener.createCustomItem("nonexistent:item"));

        // When customItemService is null
        listener.setCustomItemService(null);
        assertNull(listener.createCustomItem("skyblock:crystal/citrine"));
    }

    @Test
    @DisplayName("BlockFormEvent spawns custom item drop and particles when roll succeeds")
    void testBlockFormSpawnsCustomDrop() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("generator.ores.enabled", false);
        config.set("generator.custom-drops.enabled", true);
        config.set("generator.custom-drops.overworld.skyblock:crystal/citrine", 100.0); // 100% chance
        when(plugin.getConfig()).thenReturn(config);

        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);
        when(skyllia.isSkyblockWorld("skyblock_world")).thenReturn(true);

        CustomItemService customItemService = mock(CustomItemService.class);
        ItemStack mockCitrine = mock(ItemStack.class);
        when(customItemService.create("skyblock:crystal/citrine")).thenReturn(Optional.of(mockCitrine));

        CobblestoneGeneratorListener listener = new CobblestoneGeneratorListener(plugin, skyllia, customItemService);

        BlockFormEvent event = mock(BlockFormEvent.class);
        Block block = mock(Block.class);
        BlockState newState = mock(BlockState.class);
        World world = mock(World.class);

        when(event.getBlock()).thenReturn(block);
        when(event.getNewState()).thenReturn(newState);
        when(newState.getType()).thenReturn(Material.COBBLESTONE);
        when(block.getWorld()).thenReturn(world);
        when(block.getY()).thenReturn(64);
        when(block.getLocation()).thenAnswer(inv -> new Location(world, 10, 64, 20));
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getName()).thenReturn("skyblock_world");

        listener.onBlockForm(event);

        verify(world).dropItemNaturally(eq(new Location(world, 10.5, 64.5, 20.5)), eq(mockCitrine));
        verify(world).spawnParticle(eq(Particle.HAPPY_VILLAGER), eq(new Location(world, 10.5, 64.5, 20.5)), eq(6), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("BlockFormEvent in Nether spawns topaz drop")
    void testBlockFormNetherSpawnsTopaz() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("generator.custom-drops.enabled", true);
        config.set("generator.custom-drops.nether.skyblock:crystal/topaz", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);
        when(skyllia.isSkyblockWorld("skyblock_nether")).thenReturn(true);

        CustomItemService customItemService = mock(CustomItemService.class);
        ItemStack mockTopaz = mock(ItemStack.class);
        when(customItemService.create("skyblock:crystal/topaz")).thenReturn(Optional.of(mockTopaz));

        CobblestoneGeneratorListener listener = new CobblestoneGeneratorListener(plugin, skyllia, customItemService);

        BlockFormEvent event = mock(BlockFormEvent.class);
        Block block = mock(Block.class);
        BlockState newState = mock(BlockState.class);
        World world = mock(World.class);

        when(event.getBlock()).thenReturn(block);
        when(event.getNewState()).thenReturn(newState);
        when(newState.getType()).thenReturn(Material.BASALT);
        when(block.getWorld()).thenReturn(world);
        when(block.getY()).thenReturn(80);
        when(block.getLocation()).thenAnswer(inv -> new Location(world, 5, 80, 5));
        when(world.getEnvironment()).thenReturn(World.Environment.NETHER);
        when(world.getName()).thenReturn("skyblock_nether");

        listener.onBlockForm(event);

        verify(world).dropItemNaturally(eq(new Location(world, 5.5, 80.5, 5.5)), eq(mockTopaz));
    }

    @Test
    @DisplayName("BlockFormEvent ignored if world is not a Skyllia world")
    void testBlockFormIgnoredInNonSkyblockWorld() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);
        when(skyllia.isSkyblockWorld("spawn_world")).thenReturn(false);

        CobblestoneGeneratorListener listener = new CobblestoneGeneratorListener(plugin, skyllia);

        BlockFormEvent event = mock(BlockFormEvent.class);
        Block block = mock(Block.class);
        BlockState newState = mock(BlockState.class);
        World world = mock(World.class);

        when(event.getBlock()).thenReturn(block);
        when(event.getNewState()).thenReturn(newState);
        when(newState.getType()).thenReturn(Material.COBBLESTONE);
        when(block.getWorld()).thenReturn(world);
        when(world.getName()).thenReturn("spawn_world");
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);

        listener.onBlockForm(event);

        verify(world, never()).dropItemNaturally(any(), any());
        verify(newState, never()).setType(any());
    }

    @Test
    @DisplayName("BlockFormEvent spawns HAPPY_VILLAGER particle at most once even when both rare ore and custom drop succeed")
    void testBlockFormWithRareOreAndCustomDropSpawnsParticleAtMostOnce() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("generator.ores.enabled", true);
        config.set("generator.ores.rates.DIAMOND_ORE", 100.0); // 100% rare ore
        config.set("generator.custom-drops.enabled", true);
        config.set("generator.custom-drops.overworld.skyblock:crystal/citrine", 100.0); // 100% custom drop
        when(plugin.getConfig()).thenReturn(config);

        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);
        when(skyllia.isSkyblockWorld("skyblock_world")).thenReturn(true);

        CustomItemService customItemService = mock(CustomItemService.class);
        ItemStack mockCitrine = mock(ItemStack.class);
        when(customItemService.create("skyblock:crystal/citrine")).thenReturn(Optional.of(mockCitrine));

        CobblestoneGeneratorListener listener = new CobblestoneGeneratorListener(plugin, skyllia, customItemService);

        BlockFormEvent event = mock(BlockFormEvent.class);
        Block block = mock(Block.class);
        BlockState newState = mock(BlockState.class);
        World world = mock(World.class);

        when(event.getBlock()).thenReturn(block);
        when(event.getNewState()).thenReturn(newState);
        when(newState.getType()).thenReturn(Material.COBBLESTONE);
        when(block.getWorld()).thenReturn(world);
        when(block.getY()).thenReturn(64);
        Location blockLoc = new Location(world, 10, 64, 20);
        when(block.getLocation()).thenReturn(blockLoc);
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getName()).thenReturn("skyblock_world");

        listener.onBlockForm(event);

        Location dropLoc = new Location(world, 10.5, 64.5, 20.5);
        verify(newState).setType(Material.DIAMOND_ORE);
        verify(world).dropItemNaturally(eq(dropLoc), eq(mockCitrine));
        // Particle must be spawned EXACTLY ONCE (times(1))
        verify(world, org.mockito.Mockito.times(1))
                .spawnParticle(eq(Particle.HAPPY_VILLAGER), eq(dropLoc), eq(6), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    /**
     * AUTO-7: rzadkie rudy (złoto/diament/szmaragd) mają cooldown liczony na
     * chunk. Bez niego generator formował ~2 bloki/s bez żadnego ogranicznika,
     * czyli ~86 diamentów i ~29 szmaragdów na godzinę z jednego generatora,
     * a stack generatorów mnożył to liniowo.
     */
    @Test
    @DisplayName("Rare ore cooldown is per chunk, respects the window and can be disabled")
    void testRareCooldownWindow() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("generator.ores.rare-cooldown-seconds", 120);
        config.set("generator.ores.rates.DIAMOND_ORE", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        CobblestoneGeneratorListener listener =
                new CobblestoneGeneratorListener(plugin, mock(SkylliaIntegration.class));
        assertEquals(120L, listener.getRareCooldownSeconds());

        long start = 1_000_000L;
        assertTrue(listener.claimRareSlot("w", 0, 0, start), "pierwsze trafienie wolne");
        assertFalse(listener.claimRareSlot("w", 0, 0, start + 119_999), "okno jeszcze trwa");
        assertTrue(listener.claimRareSlot("w", 1, 0, start + 1), "sąsiedni chunk ma własny licznik");
        assertTrue(listener.claimRareSlot("w", 0, 0, start + 120_000), "okno minęło");
        assertFalse(listener.claimRareSlot("w", 0, 0, start + 120_001), "nowe okno wystartowało");

        YamlConfiguration off = new YamlConfiguration();
        off.set("generator.ores.rare-cooldown-seconds", 0);
        off.set("generator.ores.rates.DIAMOND_ORE", 100.0);
        when(plugin.getConfig()).thenReturn(off);
        listener.loadConfig();
        assertEquals(0L, listener.getRareCooldownSeconds());
        assertTrue(listener.claimRareSlot("w", 0, 0, start));
        assertTrue(listener.claimRareSlot("w", 0, 0, start), "0 = cooldown wyłączony");
    }

    /** Drugie formowanie w tym samym chunku nie może dać kolejnej rzadkiej rudy. */
    @Test
    @DisplayName("Second form in the same chunk stays plain cobblestone")
    void testRareCooldownBlocksSecondFormInSameChunk() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("generator.ores.enabled", true);
        config.set("generator.ores.rare-cooldown-seconds", 120);
        config.set("generator.ores.rates.DIAMOND_ORE", 100.0);
        config.set("generator.custom-drops.enabled", false);
        when(plugin.getConfig()).thenReturn(config);

        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);
        when(skyllia.isSkyblockWorld("skyblock_world")).thenReturn(true);
        CobblestoneGeneratorListener listener = new CobblestoneGeneratorListener(plugin, skyllia);

        BlockFormEvent event = mock(BlockFormEvent.class);
        Block block = mock(Block.class);
        BlockState newState = mock(BlockState.class);
        World world = mock(World.class);
        when(event.getBlock()).thenReturn(block);
        when(event.getNewState()).thenReturn(newState);
        when(newState.getType()).thenReturn(Material.COBBLESTONE);
        when(block.getWorld()).thenReturn(world);
        when(block.getY()).thenReturn(64);
        when(block.getLocation()).thenReturn(new Location(world, 10, 64, 20));
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getName()).thenReturn("skyblock_world");

        listener.onBlockForm(event);
        listener.onBlockForm(event);

        verify(newState, org.mockito.Mockito.times(1)).setType(Material.DIAMOND_ORE);
    }

    /**
     * F8: okno na DOWOLNĄ rudę, nadrzędne wobec okna rud rzadkich. To ono, a
     * nie wagi, trzyma tempo generatora — w wagach nie ma jak wyrazić limitu
     * „na godzinę”, a przychód to (formowań/s) × (wartość formowania).
     */
    @Test
    @DisplayName("Ore cooldown is per chunk, independent of the other windows and can be disabled")
    void testOreCooldownWindow() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("generator.ores.ore-cooldown-seconds", 20);
        config.set("generator.ores.rates.COAL_ORE", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        CobblestoneGeneratorListener listener =
                new CobblestoneGeneratorListener(plugin, mock(SkylliaIntegration.class));
        assertEquals(20L, listener.getOreCooldownSeconds());

        long start = 1_000_000L;
        assertTrue(listener.claimOreSlot("w", 0, 0, start), "pierwsze trafienie wolne");
        assertFalse(listener.claimOreSlot("w", 0, 0, start + 19_999), "okno jeszcze trwa");
        assertTrue(listener.claimOreSlot("w", 1, 0, start + 1), "sąsiedni chunk ma własny licznik");
        assertTrue(listener.claimOreSlot("w", 0, 0, start + 20_000), "okno minęło");

        // Trzy okna, trzy niezależne liczniki: zajęty slot rudy nie zabiera
        // slotu rzadkiej ani slotu kryształu w tym samym chunku.
        assertTrue(listener.claimRareSlot("w", 0, 0, start), "rzadkie mają własny licznik");
        assertTrue(listener.claimCustomDropSlot("w", 0, 0, start), "kryształ ma własny licznik");

        YamlConfiguration off = new YamlConfiguration();
        off.set("generator.ores.ore-cooldown-seconds", 0);
        off.set("generator.ores.rates.COAL_ORE", 100.0);
        when(plugin.getConfig()).thenReturn(off);
        listener.loadConfig();
        assertEquals(0L, listener.getOreCooldownSeconds());
        assertTrue(listener.claimOreSlot("w", 0, 0, start));
        assertTrue(listener.claimOreSlot("w", 0, 0, start), "0 = okno wyłączone");
    }

    /**
     * F8: wypełniacz (bruk/kamień) NIE podlega oknu — jest podstawowym
     * materiałem budowlanym i składnikiem receptury minionka, więc musi lecieć
     * zawsze. Kryształ z custom-drops ma za to własne okno, bo ma cenę skupu.
     */
    @Test
    @DisplayName("Filler bypasses the ore window while the custom drop window blocks the second drop")
    void testFillerBypassesOreWindowAndCustomDropIsWindowed() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("generator.ores.enabled", true);
        config.set("generator.ores.ore-cooldown-seconds", 20);
        config.set("generator.ores.rates.STONE", 100.0);
        config.set("generator.custom-drops.enabled", true);
        config.set("generator.custom-drops.cooldown-seconds", 240);
        config.set("generator.custom-drops.overworld.skyblock:crystal/citrine", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);
        when(skyllia.isSkyblockWorld("skyblock_world")).thenReturn(true);
        CustomItemService customItemService = mock(CustomItemService.class);
        ItemStack mockCitrine = mock(ItemStack.class);
        when(customItemService.create("skyblock:crystal/citrine")).thenReturn(Optional.of(mockCitrine));

        CobblestoneGeneratorListener listener =
                new CobblestoneGeneratorListener(plugin, skyllia, customItemService);
        assertEquals(240L, listener.getCustomDropCooldownSeconds());

        BlockFormEvent event = mock(BlockFormEvent.class);
        Block block = mock(Block.class);
        BlockState newState = mock(BlockState.class);
        World world = mock(World.class);
        when(event.getBlock()).thenReturn(block);
        when(event.getNewState()).thenReturn(newState);
        when(newState.getType()).thenReturn(Material.COBBLESTONE);
        when(block.getWorld()).thenReturn(world);
        when(block.getY()).thenReturn(64);
        when(block.getLocation()).thenAnswer(inv -> new Location(world, 10, 64, 20));
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(world.getName()).thenReturn("skyblock_world");

        listener.onBlockForm(event);
        listener.onBlockForm(event);

        verify(newState, org.mockito.Mockito.times(2)).setType(Material.STONE);
        verify(world, org.mockito.Mockito.times(1))
                .dropItemNaturally(any(Location.class), eq(mockCitrine));
    }
}
