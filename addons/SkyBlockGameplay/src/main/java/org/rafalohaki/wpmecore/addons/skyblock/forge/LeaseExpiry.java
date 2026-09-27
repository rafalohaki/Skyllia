package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * ECO-10: znacznik PDC wygaśnięcia dzierżawy (epoch millis).
 *
 * <p>Znacznik trafia na egzemplarz w momencie wykucia i podróżuje z przedmiotem
 * (ekwipunek, pancerz, skrzynia). Czyszczeniem zajmuje się
 * {@link LeaseSweepListener}: przy wejściu i co 5 minut dla obecnych graczy
 * zdejmujemy z ekwipunku i z noszonych części wszystko, co przekroczyło termin.
 * Kosmetyka dzierżawna jest soulbound, więc przed wygaśnięciem nie opuści
 * ekwipunku właściciela — po nim znika, zamiast krążyć po wyspach.
 */
public final class LeaseExpiry {

    private static final PersistentDataType<String, String> TYPE = PersistentDataType.STRING;

    private final org.bukkit.NamespacedKey expiryKey;

    public LeaseExpiry(@NotNull Plugin plugin) {
        this.expiryKey = new org.bukkit.NamespacedKey(plugin, "lease_expiry");
    }

    /** Stempluje wygaśnięcie na kopii wyrobu (epoch millis). */
    public void stamp(@NotNull ItemStack stack, long expiryEpochMillis) {
        stack.editMeta(meta -> meta.getPersistentDataContainer()
                .set(expiryKey, TYPE, Long.toString(expiryEpochMillis)));
    }

    /** Wygaśnięcie egzemplarza albo 0, gdy przedmiot nie jest dzierżawą. */
    public long expiryOf(@NotNull ItemStack stack) {
        // ItemStack udostępnia PersistentDataContainerView (tylko odczyt) —
        // to samo API czytania co meta, bez edycji.
        io.papermc.paper.persistence.PersistentDataContainerView container =
                stack.getPersistentDataContainer();
        String raw = container.get(expiryKey, TYPE);
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException malformed) {
            return 0L;
        }
    }

    /**
     * Zdejmuje wygasłe dzierżawy z ekwipunku gracza (plecak, pancerz, druga
     * ręka, kursor). Zwraca liczbę usuniętych egzemplarzy; wołane na wątku
     * encji gracza.
     */
    public int sweep(@NotNull Player player) {
        PlayerInventory inventory = player.getInventory();
        int removed = 0;
        // Kopie referencji: mutacja zawartości w pętli po polach ekwipunku jest
        // bezpieczna, bo iterujemy po indeksach z góry ustalonych tablic.
        ItemStack[] storage = inventory.getStorageContents();
        for (int index = 0; index < storage.length; index++) {
            if (expireIfDue(storage[index])) {
                inventory.setItem(index, null);
                removed++;
            }
        }
        ItemStack[] armor = inventory.getArmorContents();
        for (int index = 0; index < armor.length; index++) {
            if (expireIfDue(armor[index])) {
                inventory.setItem(36 + index, null);
                removed++;
            }
        }
        if (expireIfDue(inventory.getItemInOffHand())) {
            inventory.setItemInOffHand(null);
            removed++;
        }
        if (expireIfDue(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            removed++;
        }
        return removed;
    }

    private boolean expireIfDue(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        long expiry = expiryOf(stack);
        return expiry > 0L && expiry <= System.currentTimeMillis();
    }
}
