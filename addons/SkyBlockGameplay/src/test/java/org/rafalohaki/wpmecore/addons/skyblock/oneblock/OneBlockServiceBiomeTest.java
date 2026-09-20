package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Klasa {@code org.bukkit.block.Biome} inicjuje się przez RegistryAccess — bez
 * serwera w JVM (headless) ładuje się z ExceptionInInitializerError. Ten test
 * chodzi więc w profilu mockbukkit-compat, tak jak {@code OneBlockContentLoaderTest}.
 * Makiety gracza/świata pozostają Mockito; MockBukkit tylko udostępnia registry.
 */
@Tag("mockbukkit")
class OneBlockServiceBiomeTest {

    @BeforeAll
    static void startRegistry() {
        org.mockbukkit.mockbukkit.MockBukkit.mock();
    }

    @AfterAll
    static void stopRegistry() {
        org.mockbukkit.mockbukkit.MockBukkit.unmock();
    }

    @Test
    void phaseAdvancePaintsChapterBiomeOverIsland() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        lenient().when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("biome-test"));
        OneBlockDao dao = mock(OneBlockDao.class);
        OneBlockMilestoneDao milestoneDao = mock(OneBlockMilestoneDao.class);
        when(dao.loadAll()).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(dao.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(milestoneDao.insertIfAbsent(any(), anyString(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(1));
        CustomItemService customItems = mock(CustomItemService.class);
        lenient().when(customItems.create(anyString())).thenReturn(java.util.Optional.empty());

        Player player = mock(Player.class);
        World world = mock(World.class);
        Block block = mock(Block.class);
        Location location = mock(Location.class);
        when(location.clone()).thenReturn(location);
        when(location.add(anyDouble(), anyDouble(), anyDouble())).thenReturn(location);
        when(block.getLocation()).thenReturn(location);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(0);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(0);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        PlayerInventory inventory = mock(PlayerInventory.class, Mockito.RETURNS_DEEP_STUBS);
        when(player.getInventory()).thenReturn(inventory);
        org.bukkit.inventory.ItemStack bareHand = mock(org.bukkit.inventory.ItemStack.class);
        lenient().when(bareHand.isEmpty()).thenReturn(true);
        lenient().when(inventory.getItemInMainHand()).thenReturn(bareHand);
        lenient().when(block.getDrops(any(), any())).thenReturn(List.of());
        when(player.getName()).thenReturn("Tester");

        OneBlockContent content = new OneBlockContent(new OneBlockContent.Settings(25, 40), List.of(
                new OneBlockContent.Chapter("poczatek", "poczatek", "opis", Material.GRASS_BLOCK, 1,
                        List.of(new OneBlockContent.WeightedMaterial(Material.DIRT, 100.0)),
                        Map.of(),
                        new OneBlockContent.Bonus("skyblock:crystal/citrine", 0.0, 3),
                        new OneBlockContent.Mobs(0.0, List.of()),
                        Map.of(), ""),
                new OneBlockContent.Chapter("kres", "kres", "opis", Material.SNOW_BLOCK, 10,
                        List.of(new OneBlockContent.WeightedMaterial(Material.DIRT, 100.0)),
                        Map.of(),
                        new OneBlockContent.Bonus("skyblock:crystal/citrine", 0.0, 3),
                        new OneBlockContent.Mobs(0.0, List.of()),
                        Map.of(), "SNOWY_PLAINS")));

        OneBlockService service = new OneBlockService(plugin, dao, milestoneDao, content,
                MiniMessage.miniMessage(), customItems);
        service.initialize().join();
        UUID islandId = UUID.randomUUID();
        service.registerOneBlock(islandId, "world", 0, 64, 0);

        service.onBlockBreak(new BlockBreakEvent(block, player)); // awans po 1 rozbiciu

        verify(world, atLeastOnce()).setBiome(anyInt(), anyInt(), anyInt(),
                eq(org.bukkit.block.Biome.SNOWY_PLAINS));
        verify(world, never()).setBiome(anyInt(), anyInt(), anyInt(),
                eq(org.bukkit.block.Biome.PLAINS));
    }
}
