package org.rafalohaki.wpmecore.addons.skyblock.shared;


import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.ItemPolicyMarkers;

/**
 * Pure calculation utilities for shop transactions, slot consumption, inventory
 * capacity, and item affordability bounds.
 */
public final class QuantityMath {

    private QuantityMath() {
    }

    /**
     * Calculates the number of inventory slots required to store a given quantity of items
     * with the specified max stack size (ceiling division). Returns 0 for non-positive quantities.
     *
     * @param maxStackSize the maximum stack size of the item (> 0)
     * @param quantity     the quantity of items
     * @return number of slots required, or 0 if quantity &lt;= 0
     * @throws IllegalArgumentException if maxStackSize &lt;= 0
     */
    public static int requiredSlots(int maxStackSize, int quantity) {
        if (maxStackSize <= 0) {
            throw new IllegalArgumentException("maxStackSize must be positive: " + maxStackSize);
        }
        if (quantity <= 0) {
            return 0;
        }
        return (int) ((quantity + (long) maxStackSize - 1) / maxStackSize);
    }

    /**
     * Calculates the maximum quantity of items affordable given a unit price and current balance.
     * Returns 0 if unit price is non-positive or balance is non-positive. Caps at {@link Integer#MAX_VALUE}.
     *
     * @param unitPrice unit price of the item
     * @param balance   available account balance
     * @return maximum affordable quantity (&gt;= 0)
     */
    public static int maxAffordable(long unitPrice, long balance) {
        if (unitPrice <= 0L || balance <= 0L) {
            return 0;
        }
        long count = balance / unitPrice;
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    /**
     * Calculates the total cost for purchasing the given quantity at the specified unit price.
     *
     * @param unitPrice non-negative unit price
     * @param quantity  non-negative item quantity
     * @return total price
     * @throws IllegalArgumentException if unitPrice &lt; 0 or quantity &lt; 0
     * @throws ArithmeticException      if the computation overflows a {@code long}
     */
    public static long totalPrice(long unitPrice, int quantity) {
        if (unitPrice < 0L || quantity < 0) {
            throw new IllegalArgumentException("unitPrice and quantity must be non-negative: price="
                    + unitPrice + ", quantity=" + quantity);
        }
        if (unitPrice == 0L || quantity == 0) {
            return 0L;
        }
        return Math.multiplyExact(unitPrice, (long) quantity);
    }

    /**
     * Clamps an integer value within the inclusive range [min, max].
     *
     * @param value value to clamp
     * @param min   minimum allowable value
     * @param max   maximum allowable value
     * @return clamped value
     * @throws IllegalArgumentException if min &gt; max
     */
    public static int clamp(int value, int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("min (" + min + ") must not be greater than max (" + max + ")");
        }
        return Math.clamp(value, min, max);
    }

    /**
     * Calculates the maximum buyable quantity bounded by available inventory capacity and account balance.
     *
     * @param capacity  available space for the item in inventory
     * @param balance   available player balance
     * @param unitPrice unit purchase price
     * @return maximum buyable quantity (&gt;= 0)
     */
    public static int maxBuyable(int capacity, long balance, long unitPrice) {
        if (capacity <= 0) {
            return 0;
        }
        int affordable = maxAffordable(unitPrice, balance);
        return Math.max(0, Math.min(capacity, affordable));
    }

    /**
     * Determines whether an item stack is a vanilla plain item (without custom meta, lore,
     * display names, or PDC policy markers such as collectibles and soulbound items).
     *
     * @param stack the item stack to inspect
     * @return true if the item is a plain stack eligible for shop transactions and merging
     */
    public static boolean isPlain(@Nullable ItemStack stack) {
        if (stack == null || Materials.isAir(stack.getType())) {
            return false;
        }
        if (!stack.hasItemMeta()) {
            return true;
        }
        if (ItemPolicyMarkers.excludedFromPlayerCommerce(stack)) {
            return false;
        }
        try {
            return stack.isSimilar(new ItemStack(stack.getType()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Calculates the total number of items of the given material that can fit into the provided storage contents.
     * Takes into account empty slots (which fit {@code maxStackSize} items) and space in partially filled
     * plain stacks of the same material.
     *
     * @param storageContents array of item stacks (e.g., from player storage contents)
     * @param material        material to fit
     * @return total capacity in items
     */
    public static int calculateCapacity(@Nullable ItemStack[] storageContents, @Nullable Material material) {
        if (storageContents == null || Materials.isAir(material)) {
            return 0;
        }
        int maxStack = material.getMaxStackSize();
        if (maxStack <= 0) {
            return 0;
        }
        int capacity = 0;
        for (ItemStack stack : storageContents) {
            if (stack == null || Materials.isAir(stack.getType())) {
                capacity += maxStack;
            } else if (stack.getType() == material && isPlain(stack)) {
                int space = maxStack - stack.getAmount();
                if (space > 0) {
                    capacity += space;
                }
            }
        }
        return capacity;
    }

    /**
     * Convenience method to calculate material capacity in a {@link PlayerInventory}.
     *
     * @param inventory player inventory
     * @param material  material to fit
     * @return total capacity in items
     */
    public static int calculateCapacity(@Nullable PlayerInventory inventory, @Nullable Material material) {
        if (inventory == null) {
            return 0;
        }
        return calculateCapacity(inventory.getStorageContents(), material);
    }

    /**
     * Counts the total number of plain items of the specified material in the storage contents.
     * Items with custom meta or PDC policy markers are excluded.
     *
     * @param storageContents array of item stacks
     * @param material        material to count
     * @return count of plain items
     */
    public static int countPlain(@Nullable ItemStack[] storageContents, @Nullable Material material) {
        if (storageContents == null || Materials.isAir(material)) {
            return 0;
        }
        int count = 0;
        for (ItemStack stack : storageContents) {
            if (stack != null && stack.getType() == material && isPlain(stack)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    public static int countPlain(@Nullable PlayerInventory inventory, @Nullable Material material) {
        if (inventory == null) {
            return 0;
        }
        return countPlain(inventory.getStorageContents(), material);
    }

    /**
     * Calculates the capacity for durable outbox grants which require completely empty storage slots.
     *
     * @param storageContents array of item stacks
     * @param material        material to fit
     * @return grant capacity in items
     */
    public static int calculateGrantCapacity(@Nullable ItemStack[] storageContents, @Nullable Material material) {
        if (storageContents == null || Materials.isAir(material)) {
            return 0;
        }
        int maxStack = material.getMaxStackSize();
        if (maxStack <= 0) {
            return 0;
        }
        return countEmptySlots(storageContents) * maxStack;
    }

    /**
     * Convenience method to calculate grant capacity in a {@link PlayerInventory}.
     *
     * @param inventory player inventory
     * @param material  material to fit
     * @return grant capacity in items
     */
    public static int calculateGrantCapacity(@Nullable PlayerInventory inventory, @Nullable Material material) {
        if (inventory == null) {
            return 0;
        }
        return calculateGrantCapacity(inventory.getStorageContents(), material);
    }

    /**
     * Counts the number of empty storage slots.
     *
     * @param storageContents array of item stacks
     * @return count of empty slots
     */
    public static int countEmptySlots(@Nullable ItemStack[] storageContents) {
        if (storageContents == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack stack : storageContents) {
            if (stack == null || Materials.isAir(stack.getType())) {
                count++;
            }
        }
        return count;
    }

    /**
     * Convenience method to count empty storage slots in a {@link PlayerInventory}.
     *
     * @param inventory player inventory
     * @return count of empty storage slots
     */
    public static int countEmptySlots(@Nullable PlayerInventory inventory) {
        if (inventory == null) {
            return 0;
        }
        return countEmptySlots(inventory.getStorageContents());
    }
}

