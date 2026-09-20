package org.rafalohaki.wpmecore.addons.skyblock;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("mockbukkit")
class RecipeIntegrationTest {

    private ServerMock server;
    private Plugin plugin;
    private CustomItemService customItemService;

    private ItemStack rawTungsten;
    private ItemStack refinedTungsten;
    private ItemStack rawUmber;
    private ItemStack refinedUmber;
    private ItemStack tungstenPlate;
    private ItemStack umberPlate;
    private ItemStack perfectPlate;
    private ItemStack tungstenKey;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("SkyBlockRecipeTest");
        customItemService = mock(CustomItemService.class);

        rawTungsten = createTaggedStack(Material.PAPER, SkyBlockRecipeService.RAW_TUNGSTEN_ID, "Surowy Wolfram");
        refinedTungsten = createTaggedStack(Material.PAPER, SkyBlockRecipeService.REFINED_TUNGSTEN_ID, "Rafinowany Wolfram");
        rawUmber = createTaggedStack(Material.PAPER, SkyBlockRecipeService.RAW_UMBER_ID, "Surowa Umbra");
        refinedUmber = createTaggedStack(Material.PAPER, SkyBlockRecipeService.REFINED_UMBER_ID, "Rafinowana Umbra");
        tungstenPlate = createTaggedStack(Material.PAPER, SkyBlockRecipeService.TUNGSTEN_PLATE_ID, "Płyta Wolframowa");
        umberPlate = createTaggedStack(Material.PAPER, SkyBlockRecipeService.UMBER_PLATE_ID, "Płyta Umbry");
        perfectPlate = createTaggedStack(Material.PAPER, SkyBlockRecipeService.PERFECT_PLATE_ID, "Płyta Wzmocniona");
        tungstenKey = createTaggedStack(Material.PAPER, SkyBlockRecipeService.TUNGSTEN_KEY_ID, "Klucz Wolframowy");

        when(customItemService.create(SkyBlockRecipeService.RAW_TUNGSTEN_ID)).thenReturn(Optional.of(rawTungsten));
        when(customItemService.create(SkyBlockRecipeService.REFINED_TUNGSTEN_ID)).thenReturn(Optional.of(refinedTungsten));
        when(customItemService.create(SkyBlockRecipeService.RAW_UMBER_ID)).thenReturn(Optional.of(rawUmber));
        when(customItemService.create(SkyBlockRecipeService.REFINED_UMBER_ID)).thenReturn(Optional.of(refinedUmber));
        when(customItemService.create(SkyBlockRecipeService.TUNGSTEN_PLATE_ID)).thenReturn(Optional.of(tungstenPlate));
        when(customItemService.create(SkyBlockRecipeService.UMBER_PLATE_ID)).thenReturn(Optional.of(umberPlate));
        when(customItemService.create(SkyBlockRecipeService.PERFECT_PLATE_ID)).thenReturn(Optional.of(perfectPlate));
        when(customItemService.create(SkyBlockRecipeService.TUNGSTEN_KEY_ID)).thenReturn(Optional.of(tungstenKey));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ItemStack createTaggedStack(Material mat, String customId, String name) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text(name));
            meta.getPersistentDataContainer().set(
                    new NamespacedKey("customitems", "item_id"),
                    PersistentDataType.STRING,
                    customId
            );
            stack.setItemMeta(meta);
        }
        return stack;
    }

    @Test
    @DisplayName("Should successfully register all 8 custom recipes with ExactChoice")
    void testRegisterAllRecipes() {
        SkyBlockRecipeService service = new SkyBlockRecipeService((org.bukkit.plugin.java.JavaPlugin) plugin, customItemService);
        assertTrue(service.registerRecipes(), "Recipe registration should succeed");

        List<NamespacedKey> registered = service.getRegisteredKeys();
        assertEquals(8, registered.size(), "Should register exactly 8 recipes");

        // 1. Raw Tungsten -> Refined Tungsten (Furnace)
        NamespacedKey tungstenFurnaceKey = service.key(SkyBlockRecipeService.REFINED_TUNGSTEN_FURNACE_KEY);
        assertTrue(service.isRegistered(tungstenFurnaceKey));
        Recipe tungstenFurnace = Bukkit.getRecipe(tungstenFurnaceKey);
        assertNotNull(tungstenFurnace, "Tungsten furnace recipe should be registered");
        assertInstanceOf(FurnaceRecipe.class, tungstenFurnace);
        FurnaceRecipe tf = (FurnaceRecipe) tungstenFurnace;
        assertEquals(refinedTungsten, tf.getResult());
        assertInstanceOf(RecipeChoice.ExactChoice.class, tf.getInputChoice());
        assertTrue(tf.getInputChoice().test(rawTungsten));
        assertFalse(tf.getInputChoice().test(new ItemStack(Material.PAPER)));
        assertFalse(tf.getInputChoice().test(rawUmber));
        assertEquals(0.7f, tf.getExperience(), 0.001f);
        assertEquals(200, tf.getCookingTime());

        // 2. Raw Tungsten -> Refined Tungsten (Blasting)
        NamespacedKey tungstenBlastingKey = service.key(SkyBlockRecipeService.REFINED_TUNGSTEN_BLASTING_KEY);
        assertTrue(service.isRegistered(tungstenBlastingKey));
        Recipe tungstenBlasting = Bukkit.getRecipe(tungstenBlastingKey);
        assertNotNull(tungstenBlasting, "Tungsten blasting recipe should be registered");
        assertInstanceOf(BlastingRecipe.class, tungstenBlasting);
        BlastingRecipe tb = (BlastingRecipe) tungstenBlasting;
        assertEquals(refinedTungsten, tb.getResult());
        assertInstanceOf(RecipeChoice.ExactChoice.class, tb.getInputChoice());
        assertTrue(tb.getInputChoice().test(rawTungsten));
        assertEquals(0.7f, tb.getExperience(), 0.001f);
        assertEquals(100, tb.getCookingTime());

        // 3. Raw Umber -> Refined Umber (Furnace)
        NamespacedKey umberFurnaceKey = service.key(SkyBlockRecipeService.REFINED_UMBER_FURNACE_KEY);
        assertTrue(service.isRegistered(umberFurnaceKey));
        Recipe umberFurnace = Bukkit.getRecipe(umberFurnaceKey);
        assertNotNull(umberFurnace, "Umber furnace recipe should be registered");
        assertInstanceOf(FurnaceRecipe.class, umberFurnace);
        FurnaceRecipe uf = (FurnaceRecipe) umberFurnace;
        assertEquals(refinedUmber, uf.getResult());
        assertInstanceOf(RecipeChoice.ExactChoice.class, uf.getInputChoice());
        assertTrue(uf.getInputChoice().test(rawUmber));
        assertFalse(uf.getInputChoice().test(rawTungsten));
        assertEquals(0.7f, uf.getExperience(), 0.001f);
        assertEquals(200, uf.getCookingTime());

        // 4. Raw Umber -> Refined Umber (Blasting)
        NamespacedKey umberBlastingKey = service.key(SkyBlockRecipeService.REFINED_UMBER_BLASTING_KEY);
        assertTrue(service.isRegistered(umberBlastingKey));
        Recipe umberBlasting = Bukkit.getRecipe(umberBlastingKey);
        assertNotNull(umberBlasting, "Umber blasting recipe should be registered");
        assertInstanceOf(BlastingRecipe.class, umberBlasting);
        BlastingRecipe ub = (BlastingRecipe) umberBlasting;
        assertEquals(refinedUmber, ub.getResult());
        assertInstanceOf(RecipeChoice.ExactChoice.class, ub.getInputChoice());
        assertTrue(ub.getInputChoice().test(rawUmber));
        assertEquals(0.7f, ub.getExperience(), 0.001f);
        assertEquals(100, ub.getCookingTime());

        // 5. 4x Refined Tungsten -> Tungsten Plate (2x2)
        NamespacedKey tungstenPlateKey = service.key(SkyBlockRecipeService.TUNGSTEN_PLATE_KEY);
        assertTrue(service.isRegistered(tungstenPlateKey));
        Recipe tungstenPlateRecipe = Bukkit.getRecipe(tungstenPlateKey);
        assertNotNull(tungstenPlateRecipe, "Tungsten plate recipe should be registered");
        assertInstanceOf(ShapedRecipe.class, tungstenPlateRecipe);
        ShapedRecipe tp = (ShapedRecipe) tungstenPlateRecipe;
        assertEquals(tungstenPlate, tp.getResult());
        assertArrayEquals(new String[]{"TT", "TT"}, tp.getShape());
        Map<Character, RecipeChoice> tpChoiceMap = tp.getChoiceMap();
        assertInstanceOf(RecipeChoice.ExactChoice.class, tpChoiceMap.get('T'));
        assertTrue(tpChoiceMap.get('T').test(refinedTungsten));
        assertFalse(tpChoiceMap.get('T').test(new ItemStack(Material.PAPER)));
        assertFalse(tpChoiceMap.get('T').test(refinedUmber));

        // 6. 4x Refined Umber -> Umber Plate (2x2)
        NamespacedKey umberPlateKey = service.key(SkyBlockRecipeService.UMBER_PLATE_KEY);
        assertTrue(service.isRegistered(umberPlateKey));
        Recipe umberPlateRecipe = Bukkit.getRecipe(umberPlateKey);
        assertNotNull(umberPlateRecipe, "Umber plate recipe should be registered");
        assertInstanceOf(ShapedRecipe.class, umberPlateRecipe);
        ShapedRecipe up = (ShapedRecipe) umberPlateRecipe;
        assertEquals(umberPlate, up.getResult());
        assertArrayEquals(new String[]{"UU", "UU"}, up.getShape());
        Map<Character, RecipeChoice> upChoiceMap = up.getChoiceMap();
        assertInstanceOf(RecipeChoice.ExactChoice.class, upChoiceMap.get('U'));
        assertTrue(upChoiceMap.get('U').test(refinedUmber));
        assertFalse(upChoiceMap.get('U').test(refinedTungsten));

        // 7. 2x Tungsten Plate + 2x Umber Plate + 1x Diamond -> Perfect Plate
        NamespacedKey perfectPlateKey = service.key(SkyBlockRecipeService.PERFECT_PLATE_KEY);
        assertTrue(service.isRegistered(perfectPlateKey));
        Recipe perfectPlateRecipe = Bukkit.getRecipe(perfectPlateKey);
        assertNotNull(perfectPlateRecipe, "Perfect plate recipe should be registered");
        assertInstanceOf(ShapedRecipe.class, perfectPlateRecipe);
        ShapedRecipe pp = (ShapedRecipe) perfectPlateRecipe;
        assertEquals(perfectPlate, pp.getResult());
        assertArrayEquals(new String[]{" T ", "UDU", " T "}, pp.getShape());
        Map<Character, RecipeChoice> ppChoiceMap = pp.getChoiceMap();
        assertInstanceOf(RecipeChoice.ExactChoice.class, ppChoiceMap.get('T'));
        assertTrue(ppChoiceMap.get('T').test(tungstenPlate));
        assertInstanceOf(RecipeChoice.ExactChoice.class, ppChoiceMap.get('U'));
        assertTrue(ppChoiceMap.get('U').test(umberPlate));
        assertTrue(ppChoiceMap.get('D').test(new ItemStack(Material.DIAMOND)));
        assertFalse(ppChoiceMap.get('D').test(new ItemStack(Material.EMERALD)));

        // 8. 4x Perfect Plate + 1x Netherite Ingot -> Tungsten Key
        NamespacedKey tungstenKeyKey = service.key(SkyBlockRecipeService.TUNGSTEN_KEY_KEY);
        assertTrue(service.isRegistered(tungstenKeyKey));
        Recipe tungstenKeyRecipe = Bukkit.getRecipe(tungstenKeyKey);
        assertNotNull(tungstenKeyRecipe, "Tungsten key recipe should be registered");
        assertInstanceOf(ShapedRecipe.class, tungstenKeyRecipe);
        ShapedRecipe tk = (ShapedRecipe) tungstenKeyRecipe;
        assertEquals(tungstenKey, tk.getResult());
        assertArrayEquals(new String[]{" P ", "PNP", " P "}, tk.getShape());
        Map<Character, RecipeChoice> tkChoiceMap = tk.getChoiceMap();
        assertInstanceOf(RecipeChoice.ExactChoice.class, tkChoiceMap.get('P'));
        assertTrue(tkChoiceMap.get('P').test(perfectPlate));
        assertTrue(tkChoiceMap.get('N').test(new ItemStack(Material.NETHERITE_INGOT)));
        assertFalse(tkChoiceMap.get('N').test(new ItemStack(Material.GOLD_INGOT)));
    }

    @Test
    @DisplayName("Should unregister recipes cleanly and remove them from Bukkit registry")
    void testUnregisterRecipes() {
        SkyBlockRecipeService service = new SkyBlockRecipeService((org.bukkit.plugin.java.JavaPlugin) plugin, customItemService);
        assertTrue(service.registerRecipes());
        assertEquals(8, service.getRegisteredKeys().size());

        NamespacedKey tungstenFurnaceKey = service.key(SkyBlockRecipeService.REFINED_TUNGSTEN_FURNACE_KEY);
        assertNotNull(Bukkit.getRecipe(tungstenFurnaceKey));

        service.unregisterRecipes();
        assertEquals(0, service.getRegisteredKeys().size());
        assertNull(Bukkit.getRecipe(tungstenFurnaceKey));
        assertFalse(service.isRegistered(tungstenFurnaceKey));
    }

    @Test
    @DisplayName("Should handle missing CustomItemService gracefully")
    void testMissingCustomItemService() {
        SkyBlockRecipeService service = new SkyBlockRecipeService((org.bukkit.plugin.java.JavaPlugin) plugin, null);
        assertFalse(service.registerRecipes());
        assertEquals(0, service.getRegisteredKeys().size());
    }

    @Test
    @DisplayName("Should handle incomplete custom item definitions gracefully")
    void testIncompleteCustomItems() {
        when(customItemService.create(SkyBlockRecipeService.TUNGSTEN_KEY_ID)).thenReturn(Optional.empty());

        SkyBlockRecipeService service = new SkyBlockRecipeService((org.bukkit.plugin.java.JavaPlugin) plugin, customItemService);
        assertFalse(service.registerRecipes());
        assertEquals(0, service.getRegisteredKeys().size());
    }

    @Test
    @DisplayName("Should re-register cleanly without collision when called multiple times")
    void testReRegistration() {
        SkyBlockRecipeService service = new SkyBlockRecipeService((org.bukkit.plugin.java.JavaPlugin) plugin, customItemService);
        assertTrue(service.registerRecipes());
        assertEquals(8, service.getRegisteredKeys().size());

        assertTrue(service.registerRecipes());
        assertEquals(8, service.getRegisteredKeys().size());
        assertNotNull(Bukkit.getRecipe(service.key(SkyBlockRecipeService.PERFECT_PLATE_KEY)));
    }
}
