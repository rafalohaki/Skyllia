package org.rafalohaki.wpmecore.addons.skyblock.oneblock;


import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

class OneBlockServiceTest {

    private JavaPlugin plugin;
    private OneBlockDao dao;
    private OneBlockMilestoneDao milestoneDao;
    private CustomItemService customItems;
    private Player player;
    private World world;
    private Block block;
    private PlayerInventory inventory;
    private OneBlockContent content;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        // Bez atrapy loggera każdy log w gałęzi błędu wywala NPE i po cichu
        // zjada ścieżkę ponowienia ładowania (ONEBLOCK-2).
        lenient().when(plugin.getLogger())
                .thenReturn(java.util.logging.Logger.getLogger("OneBlockServiceTest"));
        dao = mock(OneBlockDao.class);
        milestoneDao = mock(OneBlockMilestoneDao.class);
        customItems = mock(CustomItemService.class);
        when(dao.save(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(dao.deleteByIsland(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(milestoneDao.insertIfAbsent(any(), anyString(), anyString(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(1));
        when(milestoneDao.listUnclaimed(any()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        lenient().when(customItems.create(anyString()))
                .thenAnswer(inv -> Optional.of(mock(ItemStack.class)));

        player = mock(Player.class);
        world = mock(World.class);
        block = mock(Block.class);
        Location location = mock(Location.class);
        when(location.clone()).thenReturn(location);
        when(location.add(anyDouble(), anyDouble(), anyDouble())).thenReturn(location);
        when(block.getLocation()).thenReturn(location);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(0);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(0);
        when(world.getName()).thenReturn("world");
        when(world.dropItemNaturally(any(), any())).thenReturn(null);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(location);
        inventory = mock(PlayerInventory.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(player.getInventory()).thenReturn(inventory);
        // Domyślnie gołe ręce: brak dropów i brak narzędzia do zużycia.
        ItemStack bareHand = mock(ItemStack.class);
        lenient().when(bareHand.isEmpty()).thenReturn(true);
        lenient().when(inventory.getItemInMainHand()).thenReturn(bareHand);
        lenient().when(block.getDrops(any(), any())).thenReturn(List.of());

        content = new OneBlockContent(new OneBlockContent.Settings(25, 40), List.of(
                chapter("poczatek", 10, Map.of(50, Material.OAK_LOG),
                        Map.of(5, new OneBlockContent.Milestone("piatka", 1, List.of(
                                new OneBlockContent.MilestoneReward(
                                        null, Material.IRON_INGOT, 8, 100.0))))),
                chapter("kres", 10, Map.of(), Map.of())));
    }

    private static OneBlockContent.Chapter chapter(String id, int blocksRequired,
                                                   Map<Integer, Material> guaranteed,
                                                   Map<Integer, OneBlockContent.Milestone> milestones) {
        return new OneBlockContent.Chapter(id, id, "opis", Material.GRASS_BLOCK, blocksRequired,
                List.of(new OneBlockContent.WeightedMaterial(Material.DIRT, 100.0)),
                guaranteed,
                new OneBlockContent.Bonus("skyblock:crystal/citrine", 0.0, 3),
                new OneBlockContent.Mobs(0.0, List.of()),
                milestones);
    }

    @Test
    void successfulBreakNotifiesQuestObserver() {
        lenient().when(block.getType()).thenReturn(Material.DIRT);
        lenient().when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        OneBlockService service = service();
        java.util.concurrent.atomic.AtomicReference<java.util.UUID> islandSeen =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Material> brokenSeen =
                new java.util.concurrent.atomic.AtomicReference<>();
        service.bindBreakObserver((islandId, playerId, broken) -> {
            islandSeen.set(islandId);
            brokenSeen.set(broken);
        });
        OneBlockState state = registeredIsland(service);
        breakBlock(service, state);
        assertEquals(state.islandId(), islandSeen.get());
        assertEquals(Material.DIRT, brokenSeen.get());
    }

    @Test
    void observerFailureDoesNotBreakMining() {
        OneBlockService service = service();
        service.bindBreakObserver((islandId, playerId, broken) -> {
            throw new IllegalStateException("zadania offline");
        });
        OneBlockState state = registeredIsland(service);
        breakBlock(service, state); // nie wolno rzucic
        assertEquals(1, state.progress());
    }

    private OneBlockService service() {
        return new OneBlockService(plugin, dao, milestoneDao, content,
                MiniMessage.miniMessage(), customItems);
    }

    private OneBlockState registeredIsland(OneBlockService service) {
        UUID islandId = UUID.randomUUID();
        service.registerOneBlock(islandId, "world", 0, 64, 0);
        return service.findByIsland(islandId).orElseThrow();
    }

    @Test
    void progressTextShowsChapterAndCount() {
        OneBlockContent.Chapter ch = chapter("zima", 750, Map.of(), Map.of());
        String text = OneBlockService.progressText(ch, 137);
        assertTrue(text.contains("zima"), text);
        assertTrue(text.contains("137/750"), text);
    }

    @Test
    void eachBreakShowsActionBarProgress() {
        OneBlockService service = service();
        OneBlockState state = registeredIsland(service);
        breakBlock(service, state);
        verify(player).sendActionBar(any(net.kyori.adventure.text.Component.class));
    }


    private void breakBlock(OneBlockService service, OneBlockState state) {
        service.onBlockBreak(new BlockBreakEvent(block, player));
    }

    @Test
    void pityForcesBonusAfterThresholdAndResetsStreak() {
        OneBlockService service = service();
        OneBlockState state = registeredIsland(service);
        // chance = 0 ⇒ tylko pity wymusza; pity = 3
        breakBlock(service, state); // streak 1
        breakBlock(service, state); // streak 2
        breakBlock(service, state); // streak 3
        assertEquals(3, state.dryStreak());
        breakBlock(service, state); // wymuszone trafienie
        assertEquals(0, state.dryStreak());
        verify(customItems, times(1)).create("skyblock:crystal/citrine");
    }

    @Test
    void guaranteedCounterOverridesRandomRoll() {
        OneBlockContent.Chapter chapter = content.chapter("poczatek");
        assertEquals(Material.OAK_LOG,
                OneBlockService.nextBlockMaterial(chapter, 50, new Random(1)));
        assertEquals(Material.DIRT,
                OneBlockService.nextBlockMaterial(chapter, 7, new Random(1)));
    }

    /**
     * The platform must never be air. BlockBreakEvent fires before the server removes
     * the block, so a handler that only calls setType is overwritten and the player
     * falls into the void — the break has to be cancelled and reproduced instead.
     */
    @Test
    void breakIsCancelledSoThePlatformIsNeverAir() {
        OneBlockService service = service();
        registeredIsland(service);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        service.onBlockBreak(event);

        assertTrue(event.isCancelled(), "an uncancelled break lets the server clear the platform");
        verify(block, times(1)).setType(any(), eq(false));
    }

    /**
     * Drops are read from the block before it is replaced and handed out by us, because
     * cancelling took vanilla's break processing away. {@code getDrops(tool, player)}
     * keeps honouring the tool, which is what makes a stone chapter demand a pickaxe (D1).
     */
    @Test
    void paysOutTheDropsVanillaWouldHaveGivenForTheHeldTool() {
        OneBlockService service = service();
        registeredIsland(service);
        ItemStack tool = mock(ItemStack.class);
        when(tool.isEmpty()).thenReturn(false);
        when(tool.damage(eq(1), any())).thenReturn(tool);
        when(inventory.getItemInMainHand()).thenReturn(tool);
        // Realny ItemStack wymagałby rejestru Bukkita, niedostępnego w czystym JVM testowym.
        ItemStack dropped = mock(ItemStack.class);
        when(block.getDrops(tool, player)).thenReturn(List.of(dropped));

        when(inventory.addItem(dropped)).thenReturn(new HashMap<>());

        service.onBlockBreak(new BlockBreakEvent(block, player));

        // Prosto do ekwipunku: na jednym bloku nad pustką luźny drop łatwo przepada.
        verify(inventory, times(1)).addItem(dropped);
        verify(world, never()).dropItemNaturally(any(), eq(dropped));
        verify(tool, times(1)).damage(eq(1), eq(player));
    }

    @Test
    void dropsThatDoNotFitFallBackToTheGround() {
        OneBlockService service = service();
        registeredIsland(service);
        ItemStack tool = mock(ItemStack.class);
        when(tool.isEmpty()).thenReturn(false);
        when(tool.damage(eq(1), any())).thenReturn(tool);
        when(inventory.getItemInMainHand()).thenReturn(tool);
        ItemStack dropped = mock(ItemStack.class);
        ItemStack leftover = mock(ItemStack.class);
        when(block.getDrops(tool, player)).thenReturn(List.of(dropped));
        HashMap<Integer, ItemStack> overflow = new HashMap<>();
        overflow.put(0, leftover);
        when(inventory.addItem(dropped)).thenReturn(overflow);

        service.onBlockBreak(new BlockBreakEvent(block, player));

        verify(world, times(1)).dropItemNaturally(any(), eq(leftover));
    }

    @Test
    void experienceGoesStraightToThePlayerInsteadOfDriftingOffAsOrbs() {
        OneBlockService service = service();
        registeredIsland(service);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        event.setExpToDrop(7);

        service.onBlockBreak(event);

        verify(player, times(1)).giveExp(7);
    }

    @Test
    void bareHandedBreakDropsNothingAndDamagesNoTool() {
        OneBlockService service = service();
        registeredIsland(service);
        ItemStack empty = mock(ItemStack.class);
        when(empty.isEmpty()).thenReturn(true);
        when(inventory.getItemInMainHand()).thenReturn(empty);
        when(block.getDrops(empty, player)).thenReturn(List.of());

        service.onBlockBreak(new BlockBreakEvent(block, player));

        verify(world, never()).dropItemNaturally(any(), any());
        verify(empty, never()).damage(anyInt(), any());
    }

    @Test
    void milestoneRollsOnceAndPersistsFrozenLoot() {
        OneBlockService service = service();
        OneBlockState state = registeredIsland(service);
        for (int i = 0; i < 5; i++) {
            breakBlock(service, state);
        }
        verify(milestoneDao, times(1))
                .insertIfAbsent(eq(state.islandId()), eq("poczatek:5"), anyString(), anyLong());
    }

    @Test
    void lastChapterKeepsCountingWithoutAdvance() {
        OneBlockService service = service();
        OneBlockState state = registeredIsland(service);
        state.setPhaseId("kres");
        state.resetProgress();
        for (int i = 0; i < 12; i++) {
            breakBlock(service, state);
        }
        assertEquals("kres", state.phaseId());
        assertEquals(12, state.progress());
    }

    @Test
    void checkpointSavesOnlyOnBoundaryOrPhaseChangeOrMilestone() {
        OneBlockService service = service();
        OneBlockState state = registeredIsland(service);
        // checkpoint-every = 25; milestone tylko przy 5
        for (int i = 0; i < 4; i++) {
            breakBlock(service, state);
        } // 4 rozbicia: brak milestone, brak granicy ⇒ tylko dirty
        assertTrue(state.isDirty());
        verify(dao, times(1)).save(any()); // rejestracja
        breakBlock(service, state); // 5 ⇒ milestone ⇒ zapis natychmiast
        verify(dao, times(2)).save(any());
        assertFalse(state.isDirty()); // persist czyści flagę dirty
    }

    @Test
    void flushPersistsDirtyStates() {
        OneBlockService service = service();
        OneBlockState state = registeredIsland(service);
        state.markDirty();
        service.flush();
        verify(dao, times(2)).save(any()); // rejestracja + flush
        assertFalse(state.isDirty());
    }

    @Test
    void unregisterDeletesMilestonesToo() {
        OneBlockService service = service();
        UUID islandId = UUID.randomUUID();
        service.registerOneBlock(islandId, "world", 0, 64, 0);
        service.unregisterOneBlock(islandId);
        verify(milestoneDao).deleteByIsland(islandId);
    }

    @Test
    void setProgressRejectsUnknownPhase() {
        OneBlockService service = service();
        UUID islandId = UUID.randomUUID();
        service.registerOneBlock(islandId, "world", 0, 64, 0);
        assertFalse(service.setProgress(islandId, "nieznany", 5));
        assertTrue(service.setProgress(islandId, "kres", 5));
    }

    @Test
    void breakIsCancelledWhileLoadingOrFailed() {
        OneBlockService service = service();
        CompletableFuture<List<OneBlockState>> loadFuture = new CompletableFuture<>();
        when(dao.loadAll()).thenReturn(loadFuture);

        service.initialize();
        assertFalse(service.isInitialized());
        // Strażnik obowiązuje tylko na bloku OneBlocka, więc musi tu być zarejestrowany.
        registeredIsland(service);

        BlockBreakEvent event = new BlockBreakEvent(block, player);
        service.onBlockBreak(event);
        assertTrue(event.isCancelled(), "Break should be cancelled fail-closed while loading");

        // Fail load
        loadFuture.completeExceptionally(new RuntimeException("DB offline"));
        assertFalse(service.isInitialized());

        BlockBreakEvent secondEvent = new BlockBreakEvent(block, player);
        service.onBlockBreak(secondEvent);
        assertTrue(secondEvent.isCancelled(), "Break should be cancelled fail-closed when loading failed");
    }

    /**
     * ONEBLOCK-2. Strażnik ładowania stał przed lookupem {@code byLocation}, więc
     * anulował KAŻDY {@code BlockBreakEvent} na serwerze — w hubie i na wyspach
     * klasycznych też. Na starym kodzie ten test pada: zwykły blok jest anulowany.
     */
    @Test
    void loadingDoesNotCancelBreaksOutsideOneBlockPlatforms() {
        OneBlockService service = service();
        when(dao.loadAll()).thenReturn(new CompletableFuture<>());
        service.initialize();

        // blok pod adresem, którego żadna wyspa nie zarejestrowała
        Block elsewhere = mock(Block.class);
        Location elsewhereLocation = mock(Location.class);
        lenient().when(elsewhereLocation.clone()).thenReturn(elsewhereLocation);
        lenient().when(elsewhere.getLocation()).thenReturn(elsewhereLocation);
        when(elsewhere.getWorld()).thenReturn(world);
        when(elsewhere.getX()).thenReturn(1200);
        when(elsewhere.getY()).thenReturn(70);
        when(elsewhere.getZ()).thenReturn(-40);

        BlockBreakEvent event = new BlockBreakEvent(elsewhere, player);
        service.onBlockBreak(event);

        assertFalse(event.isCancelled(),
                "ładowanie OneBlocka nie ma prawa blokować kopania poza platformami");
    }

    /**
     * ONEBLOCK-2. {@code FAILED} był terminalny: po jednej wywrotce {@code loadAll()}
     * stany nie wczytywały się już nigdy. Test pada na starym kodzie — {@code loadAll}
     * jest tam wołane dokładnie raz.
     */
    @Test
    void failedLoadIsRetriedInsteadOfBeingTerminal() {
        CompletableFuture<List<OneBlockState>> first = new CompletableFuture<>();
        CompletableFuture<List<OneBlockState>> second = new CompletableFuture<>();
        when(dao.loadAll()).thenReturn(first, second);
        OneBlockService service = new OneBlockService(plugin, dao, milestoneDao, content,
                MiniMessage.miniMessage(), customItems, java.time.Duration.ofMillis(1));

        service.initialize();
        first.completeExceptionally(new RuntimeException("baza offline"));

        verify(dao, org.mockito.Mockito.timeout(5_000L).times(2)).loadAll();
        second.complete(List.of());
        assertTrue(service.isInitialized(), "udane ponowienie musi domknąć inicjalizację");
    }

    @Test
    void isOneBlockLocationIdentifiesRegisteredLocations() {
        OneBlockService service = service();
        UUID islandId = UUID.randomUUID();
        service.registerOneBlock(islandId, "world", 10, 64, 20);

        assertTrue(service.isOneBlockLocation("world", 10, 64, 20));
        assertFalse(service.isOneBlockLocation("world", 10, 65, 20));
        assertFalse(service.isOneBlockLocation("other_world", 10, 64, 20));
    }

    @Test
    void repairReturnsNotOneBlockWhenNotFound() {
        OneBlockService service = service();
        assertEquals(OneBlockService.RepairResult.NOT_ONEBLOCK,
                service.repairOneBlock(UUID.randomUUID(), false));
    }
}
