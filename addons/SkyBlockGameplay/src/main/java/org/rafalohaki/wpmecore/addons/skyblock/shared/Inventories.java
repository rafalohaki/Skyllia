package org.rafalohaki.wpmecore.addons.skyblock.shared;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.item.ItemPolicyMarkers;

/**
 * Liczenie i zdejmowanie przedmiotów z ekwipunku gracza.
 *
 * <p>Te operacje mieszkały w {@code ServerShop} i były stamtąd wołane przez
 * outbox — a trwałe wydawanie przedmiotów ze sklepem nie ma nic wspólnego.
 * Powstawał z tego cykl między pakietami wzięty wyłącznie stąd, że pomocniki
 * stały w złym miejscu; nie trzeba było na to żadnej abstrakcji, tylko
 * przeprowadzki.
 *
 * <p><b>Dlaczego to musi być jedno miejsce.</b> Trwałe usuwanie zdejmuje
 * baseline jedną funkcją, a potem zbiega do celu bezwzględnego, licząc drugą.
 * Gdyby te dwie się rozjechały, zbieżność nigdy by nie nastąpiła i operacja
 * utknęłaby na zawsze. Wspólna klasa czyni ten warunek widocznym.
 */
public final class Inventories {

    /** Klucz PDC, którym CustomItems znakuje swoje przedmioty. */
    private static final NamespacedKey CUSTOM_ITEM_ID =
            new NamespacedKey("customitems", "item_id");

    private Inventories() {
    }

    public static int countPlain(@NotNull PlayerInventory inventory, @NotNull Material material) {
        return QuantityMath.countPlain(inventory, material);
    }

    public static void removePlain(@NotNull PlayerInventory inventory,
                                   @NotNull Material material, int amount) {
        ItemStack[] contents = inventory.getStorageContents();
        int remaining = amount;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material || !QuantityMath.isPlain(stack)) {
                continue;
            }
            remaining -= take(contents, slot, stack, remaining);
        }
        inventory.setStorageContents(contents);
    }

    public static int countCustom(@NotNull PlayerInventory inventory,
                                  @NotNull String customItemId,
                                  @Nullable CustomItemService customItemService) {
        int count = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || !isTradableCustom(stack, customItemId, customItemService)) {
                continue;
            }
            count += stack.getAmount();
        }
        return count;
    }

    public static void removeCustom(@NotNull PlayerInventory inventory,
                                    @NotNull String customItemId, int amount,
                                    @Nullable CustomItemService customItemService) {
        ItemStack[] contents = inventory.getStorageContents();
        int remaining = amount;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || !isTradableCustom(stack, customItemId, customItemService)) {
                continue;
            }
            remaining -= take(contents, slot, stack, remaining);
        }
        inventory.setStorageContents(contents);
    }

    /** Zdejmuje ze slotu tyle, ile się da, i zwraca zdjętą liczbę. */
    private static int take(ItemStack[] contents, int slot, ItemStack stack, int remaining) {
        int removed = Math.min(remaining, stack.getAmount());
        if (removed == stack.getAmount()) {
            contents[slot] = null;
        } else {
            stack.setAmount(stack.getAmount() - removed);
        }
        return removed;
    }

    /** Ten przedmiot jest tym customem i wolno nim obracać. */
    public static boolean isTradableCustom(@NotNull ItemStack stack,
                                           @NotNull String customItemId,
                                           @Nullable CustomItemService customItemService) {
        return matchesCustomItem(stack, customItemId, customItemService)
                && !ItemPolicyMarkers.excludedFromPlayerCommerce(stack);
    }

    /**
     * Tożsamość przedmiotu customowego: najpierw pytamy serwis, a gdy go nie ma
     * albo nie rozpoznaje — czytamy znacznik PDC zapisany przez CustomItems.
     */
    public static boolean matchesCustomItem(@NotNull ItemStack stack,
                                            @NotNull String customItemId,
                                            @Nullable CustomItemService customItemService) {
        if (customItemService != null) {
            String id = customItemService.idOf(stack);
            if (id != null) {
                return id.equalsIgnoreCase(customItemId);
            }
        }
        if (stack.hasItemMeta()) {
            PersistentDataContainer pdc = stack.getItemMeta().getPersistentDataContainer();
            String id = pdc.get(CUSTOM_ITEM_ID, PersistentDataType.STRING);
            return id != null && id.equalsIgnoreCase(customItemId);
        }
        return false;
    }
}
