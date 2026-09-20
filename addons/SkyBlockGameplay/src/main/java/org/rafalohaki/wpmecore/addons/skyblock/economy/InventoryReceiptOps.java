package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Inventories;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.bukkit.persistence.PersistentDataType;

/**
 * Pure receipt math for the inventory outbox: idempotent grants and convergent
 * removals over the tagged inventory. Stateless and dependency-free so the
 * crash-recovery invariants are unit-testable in isolation.
 */
final class InventoryReceiptOps {

    private InventoryReceiptOps() {
    }

    static boolean applyGrant(PlayerInventory inventory,
                              LedgerDao.InventoryOperation operation,
                              NamespacedKey receiptKey) {
        byte[] payload = operation.itemPayload();
        if (payload == null) {
            throw new IllegalStateException("grant operation has no item payload");
        }
        ItemStack template = ItemStack.deserializeBytes(payload);
        String embeddedReceipt = template.getItemMeta().getPersistentDataContainer().get(
                receiptKey, PersistentDataType.STRING);
        if (!operation.operationId().equals(embeddedReceipt)) {
            throw new IllegalStateException("grant receipt does not match operation");
        }
        int expected = template.getAmount();
        int present = receiptAmount(inventory, operation.operationId(), receiptKey);
        if (present > expected) {
            removeReceiptAmount(inventory, operation.operationId(), receiptKey,
                    present - expected);
            present = expected;
        }
        int missing = expected - present;
        if (missing > 0) {
            if (grantCapacity(inventory, template) < missing) {
                /*
                 * Never leave a partial paid grant available for use while a
                 * player clears space. If an older/partial checkpoint exists,
                 * roll only the tagged inventory side back; the durable
                 * PENDING row still guarantees the full future delivery.
                 */
                removeReceiptAmount(inventory, operation.operationId(), receiptKey, present);
                return false;
            }
            ItemStack addition = template.clone();
            addition.setAmount(missing);
            if (!inventory.addItem(addition).isEmpty()) {
                throw new IllegalStateException("preflighted grant did not fit inventory");
            }
        }
        return receiptAmount(inventory, operation.operationId(), receiptKey) == expected;
    }

    static boolean applyTaggedDelivery(PlayerInventory inventory, String operationId,
                                        NamespacedKey receiptKey, ItemStack product) {
        int expected = product.getAmount();
        int present = receiptAmount(inventory, operationId, receiptKey);
        if (present > expected) {
            removeReceiptAmount(inventory, operationId, receiptKey, present - expected);
            present = expected;
        }
        int missing = expected - present;
        if (missing > 0) {
            if (grantCapacity(inventory, product) < missing) {
                return false;
            }
            ItemStack addition = product.clone();
            addition.setAmount(missing);
            /*
             * Paragon musi powstać razem z dokładką, nie osobnym krokiem: bez
             * znacznika kolejne ponowienie operacji PENDING widzi present=0
             * i dokłada kolejny pełny wyrób (audyt SKYBLOCK-1-2/2-2).
             */
            addition.editMeta(meta -> meta.getPersistentDataContainer().set(
                    receiptKey, PersistentDataType.STRING, operationId));
            if (!inventory.addItem(addition).isEmpty()) {
                throw new IllegalStateException("preflighted delivery did not fit inventory");
            }
        }
        return receiptAmount(inventory, operationId, receiptKey) == expected;
    }

    static void stripReceipt(PlayerInventory inventory, String operationId,
                             NamespacedKey receiptKey) {
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (!hasReceipt(item, operationId, receiptKey)) {
                continue;
            }
            item.editMeta(meta -> meta.getPersistentDataContainer().remove(receiptKey));
            inventory.setItem(slot, item);
        }
    }

    /**
     * Zbieżność do celu bezwzględnego: {@code baseline - remove}.
     *
     * <p>To jedyny mechanizm idempotencji przy usuwaniu — nie ma żadnego znacznika
     * operacji na stosie. Implementacja w stylu „usuń N pasujących" podwoiłaby
     * usunięcie przy odtwarzaniu po awarii.
     *
     * <p>Liczenie i usuwanie muszą używać <b>tego samego predykatu</b>, którym
     * zdjęto baseline; inaczej cel nigdy nie zostanie osiągnięty, a operacja utknie.
     */
    /**
     * Dlaczego zbieżność się nie udała. Wcześniej wszystkie trzy przypadki
     * wracały jako {@code false} i wywołujący nie umiał ich odróżnić, choć
     * wymagają zupełnie różnych decyzji.
     */
    enum RemovalResult {
        /** Ekwipunek jest dokładnie w stanie, którego żąda operacja. */
        CONVERGED,
        /**
         * Gracz ma <b>mniej</b> niż stan po usunięciu — nie ma czego zabierać.
         * Po wyczerpaniu budżetu prób nie ma tu nic do uratowania: przedmiotów
         * już nie ma, a dalsze odmawianie zostawia tylko nierozstrzygnięty wiersz.
         */
        BELOW_TARGET,
        /**
         * Przedmioty są w ekwipunku, ale usunięcie ich nie sięgnęło. Domknięcie
         * operacji oddałoby graczowi i towar, i zapłatę, więc decyzja należy do
         * człowieka.
         */
        INEFFECTIVE,
        /** Cel wychodzi ujemny — wiersz jest uszkodzony. */
        CORRUPT
    }

    static RemovalResult applyRemoval(PlayerInventory inventory,
                                      LedgerDao.InventoryOperation operation,
                                      @Nullable CustomItemService customItems) {
        RemovalResult worst = RemovalResult.CONVERGED;
        for (LedgerDao.InventoryLine line : operation.lines()) {
            Material material = Material.matchMaterial(line.materialKey());
            if (material == null) {
                throw new IllegalStateException("unknown outbox material " + line.materialKey());
            }
            int target = line.baselineCount() - line.removeAmount();
            if (target < 0) {
                return RemovalResult.CORRUPT;
            }
            int current = count(inventory, line, material, customItems);
            if (current < target) {
                /*
                 * Kolejne linie mogą jeszcze wymagać usunięcia, więc nie
                 * przerywamy — zapamiętujemy tylko najgorszy napotkany wynik.
                 * INEFFECTIVE jest gorsze od BELOW_TARGET, bo tam przedmioty
                 * nadal są u gracza.
                 */
                worst = worse(worst, RemovalResult.BELOW_TARGET);
                continue;
            }
            if (current > target) {
                if (line.isCustom()) {
                    Inventories.removeCustom(inventory, line.customItemId(),
                            current - target, customItems);
                } else {
                    Inventories.removePlain(inventory, material, current - target);
                }
            }
            if (count(inventory, line, material, customItems) != target) {
                worst = worse(worst, RemovalResult.INEFFECTIVE);
            }
        }
        return worst;
    }

    private static RemovalResult worse(RemovalResult current, RemovalResult candidate) {
        return candidate.ordinal() > current.ordinal() ? candidate : current;
    }

    private static int count(PlayerInventory inventory, LedgerDao.InventoryLine line,
                             Material material, @Nullable CustomItemService customItems) {
        return line.isCustom()
                ? Inventories.countCustom(inventory, line.customItemId(), customItems)
                : Inventories.countPlain(inventory, material);
    }

    static int receiptAmount(PlayerInventory inventory, String operationId,
                             NamespacedKey receiptKey) {
        int amount = 0;
        for (ItemStack item : inventory.getContents()) {
            if (hasReceipt(item, operationId, receiptKey)) {
                amount = Math.addExact(amount, item.getAmount());
            }
        }
        return amount;
    }

    static int grantCapacity(PlayerInventory inventory, ItemStack template) {
        int capacity = 0;
        int max = template.getMaxStackSize();
        for (ItemStack item : inventory.getStorageContents()) {
            if (item == null || item.getType().isAir()) {
                capacity = Math.addExact(capacity, max);
            } else if (item.isSimilar(template)) {
                capacity = Math.addExact(capacity, Math.max(0, max - item.getAmount()));
            }
        }
        return capacity;
    }

    static void removeReceiptAmount(PlayerInventory inventory, String operationId,
                                    NamespacedKey receiptKey, int amount) {
        ItemStack[] contents = inventory.getContents();
        int remaining = amount;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (!hasReceipt(item, operationId, receiptKey)) {
                continue;
            }
            int removed = Math.min(remaining, item.getAmount());
            if (removed == item.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - removed);
                inventory.setItem(slot, item);
            }
            remaining -= removed;
        }
    }

    private static boolean hasReceipt(ItemStack item, String operationId,
                                      NamespacedKey receiptKey) {
        return item != null && !item.getType().isAir() && item.hasItemMeta()
                && operationId.equals(item.getItemMeta().getPersistentDataContainer().get(
                receiptKey, PersistentDataType.STRING));
    }
}
