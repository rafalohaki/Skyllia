package org.rafalohaki.wpmecore.addons.skyblock;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

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

class SkyBlockGameplayDropsListenerTest {

    private JavaPlugin plugin;
    private SkylliaIntegration skyllia;
    private CustomItemService customItemService;
    private World world;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        skyllia = mock(SkylliaIntegration.class);
        when(skyllia.isSkyblockWorld(any())).thenReturn(true);
        customItemService = mock(CustomItemService.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("skyblock_world");
    }

    @Test
    @DisplayName("Default drop probabilities and item IDs are configured according to specification")
    void testDefaultProbabilitiesAndConstants() {
        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        assertEquals("skyblock:crystal/aquamarine", SkyBlockGameplayDropsListener.AQUAMARINE_ID);
        assertEquals("skyblock:crystal/peridot", SkyBlockGameplayDropsListener.PERIDOT_ID);
        assertEquals("skyblock:crystal/opal", SkyBlockGameplayDropsListener.OPAL_ID);
        assertEquals("skyblock:crystal/ruby", SkyBlockGameplayDropsListener.RUBY_ID);
        assertEquals("skyblock:key/skeleton_key", SkyBlockGameplayDropsListener.SKELETON_KEY_ID);

        assertEquals(1.5, listener.getFishingAquamarineChance(), 0.001);
        assertEquals(0.8, listener.getFarmingPeridotChance(), 0.001);
        assertEquals(2.0, listener.getLapisOpalChance(), 0.001);
        assertEquals(0.5, listener.getMonsterRubyChance(), 0.001);
        assertEquals(0.2, listener.getMonsterKeyChance(), 0.001);
        assertTrue(listener.isEnabled());
    }

    @Test
    @DisplayName("Roll methods respect exact percentage boundaries")
    void testRollMethodsBoundaries() {
        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        // Fishing: 1.5%
        assertTrue(listener.rollFishingDrop(0.0));
        assertTrue(listener.rollFishingDrop(1.499));
        assertFalse(listener.rollFishingDrop(1.5));
        assertFalse(listener.rollFishingDrop(50.0));

        // Farming: 0.8%
        assertTrue(listener.rollFarmingDrop(0.0));
        assertTrue(listener.rollFarmingDrop(0.799));
        assertFalse(listener.rollFarmingDrop(0.8));
        assertFalse(listener.rollFarmingDrop(99.0));

        // Lapis: 2.0%
        assertTrue(listener.rollLapisDrop(0.0));
        assertTrue(listener.rollLapisDrop(1.999));
        assertFalse(listener.rollLapisDrop(2.0));
        assertFalse(listener.rollLapisDrop(10.0));

        // Monster Ruby: 0.5%
        assertTrue(listener.rollMonsterRubyDrop(0.0));
        assertTrue(listener.rollMonsterRubyDrop(0.499));
        assertFalse(listener.rollMonsterRubyDrop(0.5));
        assertFalse(listener.rollMonsterRubyDrop(5.0));

        // Monster Skeleton Key: 0.2%
        assertTrue(listener.rollMonsterKeyDrop(0.0));
        assertTrue(listener.rollMonsterKeyDrop(0.199));
        assertFalse(listener.rollMonsterKeyDrop(0.2));
        assertFalse(listener.rollMonsterKeyDrop(20.0));
    }

    @Test
    @DisplayName("Disabled listener rejects all rolls")
    void testDisabledListener() {
        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);
        listener.setEnabled(false);

        assertFalse(listener.rollFishingDrop(0.0));
        assertFalse(listener.rollFarmingDrop(0.0));
        assertFalse(listener.rollLapisDrop(0.0));
        assertFalse(listener.rollMonsterRubyDrop(0.0));
        assertFalse(listener.rollMonsterKeyDrop(0.0));
    }

    @Test
    @DisplayName("Custom configuration loads custom drop chances properly")
    void testConfigLoading() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.enabled", true);
        config.set("gameplay-drops.fishing.aquamarine", 3.0);
        config.set("gameplay-drops.farming.peridot", 1.2);
        config.set("gameplay-drops.lapis.opal", 5.0);
        config.set("gameplay-drops.monsters.ruby", 1.0);
        config.set("gameplay-drops.monsters.skeleton_key", 0.5);

        when(plugin.getConfig()).thenReturn(config);
        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        assertEquals(3.0, listener.getFishingAquamarineChance(), 0.001);
        assertEquals(1.2, listener.getFarmingPeridotChance(), 0.001);
        assertEquals(5.0, listener.getLapisOpalChance(), 0.001);
        assertEquals(1.0, listener.getMonsterRubyChance(), 0.001);
        assertEquals(0.5, listener.getMonsterKeyChance(), 0.001);
    }

    @Test
    @DisplayName("PlayerFishEvent with CAUGHT_FISH drops aquamarine at caught/hook location")
    void testPlayerFishEventDropsAquamarine() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.fishing.aquamarine", 100.0); // 100% chance for test
        when(plugin.getConfig()).thenReturn(config);

        ItemStack mockAquamarine = mock(ItemStack.class);
        when(customItemService.create(SkyBlockGameplayDropsListener.AQUAMARINE_ID))
                .thenReturn(Optional.of(mockAquamarine));

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);

        PlayerFishEvent event = mock(PlayerFishEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getState()).thenReturn(PlayerFishEvent.State.CAUGHT_FISH);

        Entity caught = mock(Entity.class);
        Location caughtLoc = new Location(world, 15, 63, 25);
        when(caught.getLocation()).thenReturn(caughtLoc);
        when(event.getCaught()).thenReturn(caught);

        listener.onPlayerFish(event);

        verify(world).dropItemNaturally(eq(caughtLoc), eq(mockAquamarine));
        verify(world).spawnParticle(eq(Particle.HAPPY_VILLAGER), eq(caughtLoc), eq(6), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("PlayerFishEvent with CAUGHT_FISH falls back to hook or player location if caught entity is null")
    void testPlayerFishEventFallbackLocations() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.fishing.aquamarine", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        ItemStack mockAquamarine = mock(ItemStack.class);
        when(customItemService.create(SkyBlockGameplayDropsListener.AQUAMARINE_ID))
                .thenReturn(Optional.of(mockAquamarine));

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);
        // F14: gracz stoi w INNYM chunku niż spławik, bo okno tempa
        // (gameplay-drops.cooldown-seconds) jest liczone na chunk — dwa łowienia
        // w tym samym chunku dałyby jeden drop i test mierzyłby okno zamiast
        // łańcucha lokalizacji. Samo okno sprawdza testDropCooldownWindow.
        Location playerLoc = new Location(world, 100, 64, 100);
        when(player.getLocation()).thenReturn(playerLoc);

        FishHook hook = mock(FishHook.class);
        Location hookLoc = new Location(world, 12, 63, 15);
        when(hook.getLocation()).thenReturn(hookLoc);

        PlayerFishEvent event = mock(PlayerFishEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getState()).thenReturn(PlayerFishEvent.State.CAUGHT_FISH);
        when(event.getCaught()).thenReturn(null);
        when(event.getHook()).thenReturn(hook);

        listener.onPlayerFish(event);
        verify(world).dropItemNaturally(eq(hookLoc), eq(mockAquamarine));

        // When hook is also null
        when(event.getHook()).thenReturn(null);
        listener.onPlayerFish(event);
        verify(world).dropItemNaturally(eq(playerLoc), eq(mockAquamarine));
    }

    @Test
    @DisplayName("PlayerFishEvent ignored if state is not CAUGHT_FISH")
    void testPlayerFishEventIgnoredWhenNotCaughtFish() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.fishing.aquamarine", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);

        PlayerFishEvent event = mock(PlayerFishEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getState()).thenReturn(PlayerFishEvent.State.FISHING);

        listener.onPlayerFish(event);
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    @DisplayName("PlayerFishEvent ignored in non-Skyblock world")
    void testPlayerFishEventIgnoredInNonSkyblockWorld() {
        when(skyllia.isSkyblockWorld("spawn_world")).thenReturn(false);
        when(world.getName()).thenReturn("spawn_world");

        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.fishing.aquamarine", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(world);

        PlayerFishEvent event = mock(PlayerFishEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getState()).thenReturn(PlayerFishEvent.State.CAUGHT_FISH);

        listener.onPlayerFish(event);
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    @DisplayName("BlockBreakEvent on mature Ageable crop drops peridot")
    void testBlockBreakMatureCropDropsPeridot() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.farming.peridot", 100.0); // 100% chance
        when(plugin.getConfig()).thenReturn(config);

        ItemStack mockPeridot = mock(ItemStack.class);
        when(customItemService.create(SkyBlockGameplayDropsListener.PERIDOT_ID))
                .thenReturn(Optional.of(mockPeridot));

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(Material.WHEAT);
        Location blockLoc = new Location(world, 20, 64, 30);
        when(block.getLocation()).thenReturn(blockLoc);

        Ageable ageable = mock(Ageable.class);
        when(ageable.getAge()).thenReturn(7);
        when(ageable.getMaximumAge()).thenReturn(7);
        when(block.getBlockData()).thenReturn(ageable);

        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);

        listener.onBlockBreak(event);

        Location dropLoc = new Location(world, 20.5, 64.5, 30.5);
        verify(world).dropItemNaturally(eq(dropLoc), eq(mockPeridot));
        verify(world).spawnParticle(eq(Particle.HAPPY_VILLAGER), eq(dropLoc), eq(6), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("F14: okno tempa na chunk — kategorie niezależne, chunki niezależne, 0 wyłącza")
    void testDropCooldownWindow() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.cooldown-seconds", 240);
        when(plugin.getConfig()).thenReturn(config);
        SkyBlockGameplayDropsListener listener =
                new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);
        assertEquals(240L, listener.getDropCooldownSeconds());

        long now = 1_000_000L;
        // Pierwszy drop w chunku zajmuje slot, drugi odpada aż do końca okna.
        assertTrue(listener.claim(SkyBlockGameplayDropsListener.DropSlot.FARMING, "w", 0, 0, now));
        assertFalse(listener.claim(SkyBlockGameplayDropsListener.DropSlot.FARMING, "w", 0, 0, now + 1));
        assertFalse(listener.claim(SkyBlockGameplayDropsListener.DropSlot.FARMING, "w", 0, 0,
                now + 239_999L));
        assertTrue(listener.claim(SkyBlockGameplayDropsListener.DropSlot.FARMING, "w", 0, 0,
                now + 240_000L));

        // Sąsiedni chunk ma własny licznik — okno ogranicza MIEJSCE, nie gracza.
        assertTrue(listener.claim(SkyBlockGameplayDropsListener.DropSlot.FARMING, "w", 1, 0, now));
        // Inny świat też.
        assertTrue(listener.claim(SkyBlockGameplayDropsListener.DropSlot.FARMING, "inny", 0, 0, now));

        // Kategorie nie zjadają się nawzajem: zajęty slot rolnictwa nie zabiera
        // slotu lapisowi, rubinowi ani kościanemu kluczowi w tym samym chunku.
        assertTrue(listener.claim(SkyBlockGameplayDropsListener.DropSlot.LAPIS, "w", 0, 0, now));
        assertTrue(listener.claim(SkyBlockGameplayDropsListener.DropSlot.FISHING, "w", 0, 0, now));
        assertTrue(listener.claim(SkyBlockGameplayDropsListener.DropSlot.MONSTER_RUBY, "w", 0, 0, now));
        assertTrue(listener.claim(SkyBlockGameplayDropsListener.DropSlot.MONSTER_KEY, "w", 0, 0, now));

        // 0 = okno wyłączone, stan sprzed F14.
        YamlConfiguration off = new YamlConfiguration();
        off.set("gameplay-drops.cooldown-seconds", 0);
        when(plugin.getConfig()).thenReturn(off);
        SkyBlockGameplayDropsListener unlimited =
                new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);
        assertEquals(0L, unlimited.getDropCooldownSeconds());
        assertTrue(unlimited.claim(SkyBlockGameplayDropsListener.DropSlot.FARMING, "w", 0, 0, now));
        assertTrue(unlimited.claim(SkyBlockGameplayDropsListener.DropSlot.FARMING, "w", 0, 0, now));
    }

    @Test
    @DisplayName("F14: okno tnie drugi zbiór w tym samym chunku, przez prawdziwy onBlockBreak")
    void testSecondHarvestInSameChunkYieldsNoPeridot() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.farming.peridot", 100.0);
        config.set("gameplay-drops.cooldown-seconds", 240);
        when(plugin.getConfig()).thenReturn(config);

        ItemStack mockPeridot = mock(ItemStack.class);
        when(customItemService.create(SkyBlockGameplayDropsListener.PERIDOT_ID))
                .thenReturn(Optional.of(mockPeridot));

        SkyBlockGameplayDropsListener listener =
                new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        // Dwa różne bloki, ten sam chunk — dokładnie ściana upraw pod autoklikerem.
        for (int x : new int[] {1, 2}) {
            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(world);
            when(block.getType()).thenReturn(Material.WHEAT);
            when(block.getLocation()).thenReturn(new Location(world, x, 64, 1));
            Ageable ageable = mock(Ageable.class);
            when(ageable.getAge()).thenReturn(7);
            when(ageable.getMaximumAge()).thenReturn(7);
            when(block.getBlockData()).thenReturn(ageable);
            BlockBreakEvent event = mock(BlockBreakEvent.class);
            when(event.getBlock()).thenReturn(block);
            listener.onBlockBreak(event);
        }

        verify(world).dropItemNaturally(eq(new Location(world, 1.5, 64.5, 1.5)), eq(mockPeridot));
        verify(world, never()).dropItemNaturally(eq(new Location(world, 2.5, 64.5, 1.5)), eq(mockPeridot));
    }

    @Test
    @DisplayName("BlockBreakEvent on immature crop does not drop peridot")
    void testBlockBreakImmatureCropDoesNotDropPeridot() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.farming.peridot", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(Material.CARROTS);

        Ageable ageable = mock(Ageable.class);
        when(ageable.getAge()).thenReturn(2);
        when(ageable.getMaximumAge()).thenReturn(7);
        when(block.getBlockData()).thenReturn(ageable);

        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);

        listener.onBlockBreak(event);
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    @DisplayName("BlockBreakEvent on non-ageable block does not drop peridot")
    void testBlockBreakNonAgeableDoesNotDropPeridot() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.farming.peridot", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(Material.STONE);
        BlockData nonAgeableData = mock(BlockData.class);
        when(block.getBlockData()).thenReturn(nonAgeableData);

        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);

        listener.onBlockBreak(event);
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    @DisplayName("BlockBreakEvent on all agricultural crops (CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA) drops peridot when mature")
    void testAllAgriculturalCropsDropPeridotWhenMature() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.farming.peridot", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        ItemStack mockPeridot = mock(ItemStack.class);
        when(customItemService.create(SkyBlockGameplayDropsListener.PERIDOT_ID))
                .thenReturn(Optional.of(mockPeridot));

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Material[] crops = {Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.NETHER_WART, Material.COCOA};
        for (int i = 0; i < crops.length; i++) {
            Material crop = crops[i];
            World testWorld = mock(World.class);
            when(testWorld.getName()).thenReturn("skyblock_world");

            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(testWorld);
            when(block.getType()).thenReturn(crop);
            // F14: każda uprawa w OSOBNYM chunku (co 16 kratek). Okno tempa
            // z gameplay-drops.cooldown-seconds jest liczone na chunk, a ten
            // test sprawdza listę upraw, nie okno.
            Location blockLoc = new Location(testWorld, 10 + 16 * i, 64, 10);
            when(block.getLocation()).thenReturn(blockLoc);

            Ageable ageable = mock(Ageable.class);
            when(ageable.getAge()).thenReturn(7);
            when(ageable.getMaximumAge()).thenReturn(7);
            when(block.getBlockData()).thenReturn(ageable);

            BlockBreakEvent event = mock(BlockBreakEvent.class);
            when(event.getBlock()).thenReturn(block);

            listener.onBlockBreak(event);

            Location expectedLoc = new Location(testWorld, 10.5 + 16 * i, 64.5, 10.5);
            verify(testWorld).dropItemNaturally(eq(expectedLoc), eq(mockPeridot));
        }
    }

    @Test
    @DisplayName("BlockBreakEvent on non-crop Ageables (stems, saplings) does not drop peridot")
    void testNonCropAgeablesDoNotDropPeridot() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.farming.peridot", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Material[] nonCrops = {Material.PUMPKIN_STEM, Material.MELON_STEM, Material.OAK_SAPLING};
        for (Material nonCrop : nonCrops) {
            World testWorld = mock(World.class);
            when(testWorld.getName()).thenReturn("skyblock_world");

            Block block = mock(Block.class);
            when(block.getWorld()).thenReturn(testWorld);
            when(block.getType()).thenReturn(nonCrop);

            Ageable ageable = mock(Ageable.class);
            when(ageable.getAge()).thenReturn(7);
            when(ageable.getMaximumAge()).thenReturn(7);
            when(block.getBlockData()).thenReturn(ageable);

            BlockBreakEvent event = mock(BlockBreakEvent.class);
            when(event.getBlock()).thenReturn(block);

            listener.onBlockBreak(event);
            verify(testWorld, never()).dropItemNaturally(any(), any());
        }
    }

    @Test
    @DisplayName("BlockBreakEvent on LAPIS_ORE and DEEPSLATE_LAPIS_ORE drops opal")
    void testBlockBreakLapisOreDropsOpal() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.lapis.opal", 100.0); // 100% chance
        when(plugin.getConfig()).thenReturn(config);

        ItemStack mockOpal = mock(ItemStack.class);
        when(customItemService.create(SkyBlockGameplayDropsListener.OPAL_ID))
                .thenReturn(Optional.of(mockOpal));

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        // 1. LAPIS_ORE
        Block block1 = mock(Block.class);
        when(block1.getWorld()).thenReturn(world);
        when(block1.getType()).thenReturn(Material.LAPIS_ORE);
        when(block1.getLocation()).thenReturn(new Location(world, 5, 50, 5));
        when(block1.getBlockData()).thenReturn(mock(BlockData.class));

        BlockBreakEvent event1 = mock(BlockBreakEvent.class);
        when(event1.getBlock()).thenReturn(block1);

        listener.onBlockBreak(event1);
        verify(world).dropItemNaturally(eq(new Location(world, 5.5, 50.5, 5.5)), eq(mockOpal));

        // 2. DEEPSLATE_LAPIS_ORE — F14: inny chunk, bo okno tempa liczy się
        // na chunk; ten test sprawdza oba warianty rudy, nie okno.
        Block block2 = mock(Block.class);
        when(block2.getWorld()).thenReturn(world);
        when(block2.getType()).thenReturn(Material.DEEPSLATE_LAPIS_ORE);
        when(block2.getLocation()).thenReturn(new Location(world, 100, -20, 100));
        when(block2.getBlockData()).thenReturn(mock(BlockData.class));

        BlockBreakEvent event2 = mock(BlockBreakEvent.class);
        when(event2.getBlock()).thenReturn(block2);

        listener.onBlockBreak(event2);
        verify(world).dropItemNaturally(eq(new Location(world, 100.5, -19.5, 100.5)), eq(mockOpal));
    }

    @Test
    @DisplayName("BlockBreakEvent on other ores does not drop opal")
    void testBlockBreakOtherOresNoOpal() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.lapis.opal", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(Material.DIAMOND_ORE);
        when(block.getBlockData()).thenReturn(mock(BlockData.class));

        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);

        listener.onBlockBreak(event);
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    @DisplayName("BlockBreakEvent ignored in non-Skyblock world")
    void testBlockBreakIgnoredInNonSkyblockWorld() {
        when(skyllia.isSkyblockWorld("spawn_world")).thenReturn(false);
        when(world.getName()).thenReturn("spawn_world");

        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.lapis.opal", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getType()).thenReturn(Material.LAPIS_ORE);
        when(block.getBlockData()).thenReturn(mock(BlockData.class));

        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);

        listener.onBlockBreak(event);
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    @DisplayName("EntityDeathEvent on Monster killed by Player drops ruby and skeleton key when rolls succeed")
    void testMonsterDeathKilledByPlayerDropsRubyAndKey() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.monsters.ruby", 100.0); // 100% chance
        config.set("gameplay-drops.monsters.skeleton_key", 100.0); // 100% chance
        when(plugin.getConfig()).thenReturn(config);

        ItemStack mockRuby = mock(ItemStack.class);
        ItemStack mockKey = mock(ItemStack.class);
        when(customItemService.create(SkyBlockGameplayDropsListener.RUBY_ID)).thenReturn(Optional.of(mockRuby));
        when(customItemService.create(SkyBlockGameplayDropsListener.SKELETON_KEY_ID)).thenReturn(Optional.of(mockKey));

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Monster monster = mock(Monster.class);
        Player killer = mock(Player.class);
        when(monster.getKiller()).thenReturn(killer);
        when(monster.getWorld()).thenReturn(world);
        Location deathLoc = new Location(world, 50, 65, 50);
        when(monster.getLocation()).thenReturn(deathLoc);

        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(monster);

        listener.onEntityDeath(event);

        verify(world).dropItemNaturally(eq(deathLoc), eq(mockRuby));
        verify(world).dropItemNaturally(eq(deathLoc), eq(mockKey));
    }

    @Test
    @DisplayName("EntityDeathEvent on Monster without player killer drops nothing")
    void testMonsterDeathWithoutPlayerKillerDropsNothing() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.monsters.ruby", 100.0);
        config.set("gameplay-drops.monsters.skeleton_key", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Monster monster = mock(Monster.class);
        when(monster.getKiller()).thenReturn(null); // No player killer (died to lava/fall)
        when(monster.getWorld()).thenReturn(world);

        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(monster);

        listener.onEntityDeath(event);
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    @DisplayName("EntityDeathEvent on non-Monster entity (e.g. Cow) drops nothing")
    void testNonMonsterDeathDropsNothing() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.monsters.ruby", 100.0);
        config.set("gameplay-drops.monsters.skeleton_key", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Cow cow = mock(Cow.class);
        Player killer = mock(Player.class);
        when(cow.getKiller()).thenReturn(killer);
        when(cow.getWorld()).thenReturn(world);

        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(cow);

        listener.onEntityDeath(event);
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    @DisplayName("EntityDeathEvent in non-Skyblock world drops nothing")
    void testEntityDeathIgnoredInNonSkyblockWorld() {
        when(skyllia.isSkyblockWorld("spawn_world")).thenReturn(false);
        when(world.getName()).thenReturn("spawn_world");

        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.monsters.ruby", 100.0);
        config.set("gameplay-drops.monsters.skeleton_key", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Monster monster = mock(Monster.class);
        Player killer = mock(Player.class);
        when(monster.getKiller()).thenReturn(killer);
        when(monster.getWorld()).thenReturn(world);

        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(monster);

        listener.onEntityDeath(event);
        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    @DisplayName("CustomItemService resolution and creation creates item or falls back gracefully")
    void testCustomItemCreationAndFallbacks() {
        ItemStack mockRuby = mock(ItemStack.class);
        when(customItemService.create(SkyBlockGameplayDropsListener.RUBY_ID)).thenReturn(Optional.of(mockRuby));
        when(customItemService.create("nonexistent:item")).thenReturn(Optional.empty());

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        assertEquals(mockRuby, listener.createCustomItem(SkyBlockGameplayDropsListener.RUBY_ID));
        assertNull(listener.createCustomItem("nonexistent:item"));

        // Fallback when customItemService is null
        listener.setCustomItemService(null);
        assertNull(listener.createCustomItem(SkyBlockGameplayDropsListener.RUBY_ID));
    }

    @Test
    @DisplayName("BlockBreakEvent on OneBlock location skips custom drops")
    void testOneBlockLocationFilterSkipsCustomDrops() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.lapis.opal", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        SkyBlockGameplayDropsListener listener = new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);
        Location oneBlockLoc = new Location(world, 10, 64, 10);
        listener.setOneBlockLocationFilter(loc -> loc.equals(oneBlockLoc));

        Block lapisBlock = mock(Block.class);
        when(lapisBlock.getType()).thenReturn(Material.LAPIS_ORE);
        when(lapisBlock.getWorld()).thenReturn(world);
        when(lapisBlock.getLocation()).thenReturn(oneBlockLoc);

        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(lapisBlock);

        listener.onBlockBreak(event);
        verify(world, never()).dropItemNaturally(any(), any());
    }

    /**
     * DROP-1. Nie było żadnego rejestru bloków postawionych przez gracza, więc
     * pętla postaw-rozbij na jednej rudzie lapisu (2 % na Opal Astralny po 700 coins)
     * dawała ~250 000 coins/h pod autoklikera. Na starym kodzie ten test pada:
     * postawiona ruda losuje tak samo jak wykopana.
     */
    @Test
    @DisplayName("DROP-1: ruda postawiona przez gracza nie losuje opalu")
    void testPlayerPlacedLapisOreYieldsNoOpal() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("gameplay-drops.lapis.opal", 100.0);
        when(plugin.getConfig()).thenReturn(config);

        ItemStack mockOpal = mock(ItemStack.class);
        when(customItemService.create(SkyBlockGameplayDropsListener.OPAL_ID))
                .thenReturn(Optional.of(mockOpal));

        SkyBlockGameplayDropsListener listener =
                new SkyBlockGameplayDropsListener(plugin, skyllia, customItemService);

        Block lapis = mock(Block.class);
        when(lapis.getWorld()).thenReturn(world);
        when(lapis.getType()).thenReturn(Material.LAPIS_ORE);
        when(lapis.getX()).thenReturn(7);
        when(lapis.getY()).thenReturn(60);
        when(lapis.getZ()).thenReturn(7);
        when(lapis.getLocation()).thenReturn(new Location(world, 7, 60, 7));
        when(lapis.getBlockData()).thenReturn(mock(BlockData.class));

        BlockPlaceEvent place = mock(BlockPlaceEvent.class);
        when(place.getBlockPlaced()).thenReturn(lapis);
        listener.onBlockPlace(place);
        assertEquals(1, listener.trackedPlacedBlocks());

        BlockBreakEvent breakPlaced = mock(BlockBreakEvent.class);
        when(breakPlaced.getBlock()).thenReturn(lapis);
        listener.onBlockBreak(breakPlaced);

        verify(world, never()).dropItemNaturally(any(), any());
        assertEquals(0, listener.trackedPlacedBlocks(),
                "wpis musi zniknąć po rozbiciu, inaczej rejestr rośnie bez końca");

        // Naturalna ruda w tym samym miejscu losuje normalnie.
        BlockBreakEvent breakNatural = mock(BlockBreakEvent.class);
        when(breakNatural.getBlock()).thenReturn(lapis);
        listener.onBlockBreak(breakNatural);
        verify(world).dropItemNaturally(eq(new Location(world, 7.5, 60.5, 7.5)), eq(mockOpal));
    }
}
