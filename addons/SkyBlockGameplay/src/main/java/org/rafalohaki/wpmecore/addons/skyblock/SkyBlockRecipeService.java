package org.rafalohaki.wpmecore.addons.skyblock;

import org.rafalohaki.wpmecore.addons.skyblock.shared.SkyBlockServices;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

/**
 * SkyBlockRecipeService — registers and manages custom furnace, blasting,
 * and shaped crafting recipes utilizing {@link RecipeChoice.ExactChoice}
 * to prevent vanilla ingredient collision.
 */
public final class SkyBlockRecipeService {

    public static final String RAW_TUNGSTEN_ID = "skyblock:metal/raw_tungsten";
    public static final String REFINED_TUNGSTEN_ID = "skyblock:metal/refined_tungsten";
    public static final String RAW_UMBER_ID = "skyblock:metal/raw_umber";
    public static final String REFINED_UMBER_ID = "skyblock:metal/refined_umber";
    public static final String TUNGSTEN_PLATE_ID = "skyblock:metal/tungsten_plate";
    public static final String UMBER_PLATE_ID = "skyblock:metal/umber_plate";
    public static final String PERFECT_PLATE_ID = "skyblock:metal/perfect_plate";
    public static final String TUNGSTEN_KEY_ID = "skyblock:key/tungsten_key";

    public static final String REFINED_TUNGSTEN_FURNACE_KEY = "refined_tungsten_furnace";
    public static final String REFINED_TUNGSTEN_BLASTING_KEY = "refined_tungsten_blasting";
    public static final String REFINED_UMBER_FURNACE_KEY = "refined_umber_furnace";
    public static final String REFINED_UMBER_BLASTING_KEY = "refined_umber_blasting";
    public static final String TUNGSTEN_PLATE_KEY = "tungsten_plate";
    public static final String UMBER_PLATE_KEY = "umber_plate";
    public static final String PERFECT_PLATE_KEY = "perfect_plate";
    public static final String TUNGSTEN_KEY_KEY = "tungsten_key";

    public static final float DEFAULT_SMELTING_EXP = 0.7f;
    public static final int DEFAULT_FURNACE_COOK_TIME = 200;
    public static final int DEFAULT_BLASTING_COOK_TIME = 100;

    private final JavaPlugin plugin;
    private CustomItemService customItemService;
    private final List<NamespacedKey> registeredKeys = new ArrayList<>();

    public SkyBlockRecipeService(@NotNull JavaPlugin plugin) {
        this(plugin, null);
    }

    public SkyBlockRecipeService(@NotNull JavaPlugin plugin, @Nullable CustomItemService customItemService) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.customItemService = customItemService;
    }

    public @Nullable CustomItemService getCustomItemService() {
        return SkyBlockServices.customItems(this.customItemService);
    }

    public void setCustomItemService(@Nullable CustomItemService customItemService) {
        this.customItemService = customItemService;
    }

    /**
     * Registers all custom SkyBlock smelting, blasting, and shaped crafting recipes.
     *
     * @return true if recipes were registered, false if CustomItemService or definitions were missing
     */
    public boolean registerRecipes() {
        CustomItemService items = getCustomItemService();
        if (items == null) {
            plugin.getLogger().warning("CustomItemService not found. Skipping SkyBlock custom recipe registration.");
            return false;
        }

        ItemStack rawTungsten = items.create(RAW_TUNGSTEN_ID).orElse(null);
        ItemStack refinedTungsten = items.create(REFINED_TUNGSTEN_ID).orElse(null);
        ItemStack rawUmber = items.create(RAW_UMBER_ID).orElse(null);
        ItemStack refinedUmber = items.create(REFINED_UMBER_ID).orElse(null);
        ItemStack tungstenPlate = items.create(TUNGSTEN_PLATE_ID).orElse(null);
        ItemStack umberPlate = items.create(UMBER_PLATE_ID).orElse(null);
        ItemStack perfectPlate = items.create(PERFECT_PLATE_ID).orElse(null);
        ItemStack tungstenKey = items.create(TUNGSTEN_KEY_ID).orElse(null);

        if (rawTungsten == null || refinedTungsten == null || rawUmber == null || refinedUmber == null
                || tungstenPlate == null || umberPlate == null || perfectPlate == null || tungstenKey == null) {
            plugin.getLogger().warning("Some SkyBlock custom items are missing from CustomItemService. Skipping custom recipes.");
            return false;
        }

        unregisterRecipes();

        // 1. Smelting & Blasting: Raw Tungsten -> Refined Tungsten
        registerRecipe(createFurnaceRecipe(key(REFINED_TUNGSTEN_FURNACE_KEY), refinedTungsten, rawTungsten, DEFAULT_SMELTING_EXP, DEFAULT_FURNACE_COOK_TIME));
        registerRecipe(createBlastingRecipe(key(REFINED_TUNGSTEN_BLASTING_KEY), refinedTungsten, rawTungsten, DEFAULT_SMELTING_EXP, DEFAULT_BLASTING_COOK_TIME));

        // 2. Smelting & Blasting: Raw Umber -> Refined Umber
        registerRecipe(createFurnaceRecipe(key(REFINED_UMBER_FURNACE_KEY), refinedUmber, rawUmber, DEFAULT_SMELTING_EXP, DEFAULT_FURNACE_COOK_TIME));
        registerRecipe(createBlastingRecipe(key(REFINED_UMBER_BLASTING_KEY), refinedUmber, rawUmber, DEFAULT_SMELTING_EXP, DEFAULT_BLASTING_COOK_TIME));

        // 3. Shaped Crafting: 4x Refined Tungsten -> Tungsten Plate (2x2)
        registerRecipe(createTungstenPlateRecipe(key(TUNGSTEN_PLATE_KEY), tungstenPlate, refinedTungsten));

        // 4. Shaped Crafting: 4x Refined Umber -> Umber Plate (2x2)
        registerRecipe(createUmberPlateRecipe(key(UMBER_PLATE_KEY), umberPlate, refinedUmber));

        // 5. Shaped Crafting: 2x Tungsten Plate + 2x Umber Plate + 1x Diamond -> Perfect Plate
        registerRecipe(createPerfectPlateRecipe(key(PERFECT_PLATE_KEY), perfectPlate, tungstenPlate, umberPlate));

        // 6. Shaped Crafting: 4x Perfect Plate + 1x Netherite Ingot -> Tungsten Key
        registerRecipe(createTungstenKeyRecipe(key(TUNGSTEN_KEY_KEY), tungstenKey, perfectPlate));

        plugin.getLogger().info("Registered " + registeredKeys.size() + " custom SkyBlock recipes.");
        return true;
    }

    /**
     * Unregisters all registered custom recipes from Bukkit.
     */
    public void unregisterRecipes() {
        for (NamespacedKey key : registeredKeys) {
            try {
                if (Bukkit.getServer() != null) {
                    Bukkit.removeRecipe(key);
                }
            } catch (Throwable ignored) {
            }
        }
        registeredKeys.clear();
    }

    public @NotNull NamespacedKey key(@NotNull String name) {
        return new NamespacedKey(plugin, name);
    }

    private void registerRecipe(@NotNull Recipe recipe) {
        if (recipe instanceof org.bukkit.Keyed keyed) {
            try {
                if (Bukkit.getServer() != null) {
                    Bukkit.removeRecipe(keyed.getKey());
                    if (Bukkit.addRecipe(recipe)) {
                        registeredKeys.add(keyed.getKey());
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to register recipe: " + keyed.getKey(), t);
            }
        }
    }

    public @NotNull FurnaceRecipe createFurnaceRecipe(@NotNull NamespacedKey key, @NotNull ItemStack result,
                                                      @NotNull ItemStack input, float experience, int cookingTime) {
        return new FurnaceRecipe(key, result.clone(), new RecipeChoice.ExactChoice(input.clone()), experience, cookingTime);
    }

    public @NotNull BlastingRecipe createBlastingRecipe(@NotNull NamespacedKey key, @NotNull ItemStack result,
                                                        @NotNull ItemStack input, float experience, int cookingTime) {
        return new BlastingRecipe(key, result.clone(), new RecipeChoice.ExactChoice(input.clone()), experience, cookingTime);
    }

    public @NotNull ShapedRecipe createTungstenPlateRecipe(@NotNull NamespacedKey key, @NotNull ItemStack result,
                                                          @NotNull ItemStack refinedTungsten) {
        ShapedRecipe recipe = new ShapedRecipe(key, result.clone());
        recipe.shape("TT", "TT");
        recipe.setIngredient('T', new RecipeChoice.ExactChoice(refinedTungsten.clone()));
        return recipe;
    }

    public @NotNull ShapedRecipe createUmberPlateRecipe(@NotNull NamespacedKey key, @NotNull ItemStack result,
                                                       @NotNull ItemStack refinedUmber) {
        ShapedRecipe recipe = new ShapedRecipe(key, result.clone());
        recipe.shape("UU", "UU");
        recipe.setIngredient('U', new RecipeChoice.ExactChoice(refinedUmber.clone()));
        return recipe;
    }

    public @NotNull ShapedRecipe createPerfectPlateRecipe(@NotNull NamespacedKey key, @NotNull ItemStack result,
                                                         @NotNull ItemStack tungstenPlate, @NotNull ItemStack umberPlate) {
        ShapedRecipe recipe = new ShapedRecipe(key, result.clone());
        recipe.shape(" T ", "UDU", " T ");
        recipe.setIngredient('T', new RecipeChoice.ExactChoice(tungstenPlate.clone()));
        recipe.setIngredient('U', new RecipeChoice.ExactChoice(umberPlate.clone()));
        recipe.setIngredient('D', Material.DIAMOND);
        return recipe;
    }

    public @NotNull ShapedRecipe createTungstenKeyRecipe(@NotNull NamespacedKey key, @NotNull ItemStack result,
                                                        @NotNull ItemStack perfectPlate) {
        ShapedRecipe recipe = new ShapedRecipe(key, result.clone());
        recipe.shape(" P ", "PNP", " P ");
        recipe.setIngredient('P', new RecipeChoice.ExactChoice(perfectPlate.clone()));
        recipe.setIngredient('N', Material.NETHERITE_INGOT);
        return recipe;
    }

    public @NotNull List<NamespacedKey> getRegisteredKeys() {
        return List.copyOf(registeredKeys);
    }

    public boolean isRegistered(@NotNull NamespacedKey key) {
        return registeredKeys.contains(key);
    }
}
