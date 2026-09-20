package pl.b2t.skylliaminions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Przedmiot-minionek: głowa z teksturą base64 + pełny stan w PDC (typ, tier,
 * komparktor, paliwo, magazyn), dzięki czemu podniesienie i postawienie z powrotem
 * nie traci postępu (spec §7.3).
 */
public final class MinionItem {

    public static final NamespacedKey KEY_MINION_ITEM =
            new NamespacedKey("wpme", "minion_item");
    public static final NamespacedKey KEY_MINION_TYPE =
            new NamespacedKey("wpme", "minion_type");
    public static final NamespacedKey KEY_MINION_TIER =
            new NamespacedKey("wpme", "minion_tier");
    public static final NamespacedKey KEY_MINION_COMPACTOR =
            new NamespacedKey("wpme", "minion_compactor");
    public static final NamespacedKey KEY_MINION_FUEL_TYPE =
            new NamespacedKey("wpme", "minion_fuel_type");
    public static final NamespacedKey KEY_MINION_FUEL_EXPIRES =
            new NamespacedKey("wpme", "minion_fuel_expires");
    public static final NamespacedKey KEY_MINION_STORAGE =
            new NamespacedKey("wpme", "minion_storage");
    public static final String TAG = "true";

    private MinionItem() { }

    public static @NotNull ItemStack create(@NotNull MinionsConfig.TypeDef type, int tier,
                                            boolean compactorEnabled, @Nullable String fuelType,
                                            long fuelExpiresAt, @NotNull String storageEncoded,
                                            @NotNull MiniMessage miniMessage) {
        ItemStack item = new ItemStack(type.displayMaterial());
        /*
         * Tekstura osobno: editMeta(SkullMeta.class, ...) na materiale innym niż
         * głowa nie rzuca, tylko cicho nic nie robi. Reszta metadanych musi więc
         * iść przez ItemMeta, inaczej minionek na bloku straciłby nazwę i PDC.
         */
        if (type.headTexture() != null) {
            item.editMeta(SkullMeta.class, meta -> applyHeadTexture(meta, type.headTexture()));
        }
        item.editMeta(meta -> {
            meta.displayName(noItalic(miniMessage.deserialize(type.name()
                    + " <dark_gray>Tier " + tier + "</dark_gray>")));
            meta.lore(List.of(
                    noItalic(miniMessage.deserialize(
                            "<gray>Postaw na swojej wyspie, aby pracował automatycznie.</gray>")),
                    noItalic(miniMessage.deserialize(
                            "<gray>Podniesienie zachowuje tier, ulepszenia i urobek.</gray>"))
            ));
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(KEY_MINION_ITEM, PersistentDataType.STRING, TAG);
            pdc.set(KEY_MINION_TYPE, PersistentDataType.STRING, type.id());
            pdc.set(KEY_MINION_TIER, PersistentDataType.INTEGER, tier);
            pdc.set(KEY_MINION_COMPACTOR, PersistentDataType.BYTE, (byte) (compactorEnabled ? 1 : 0));
            pdc.set(KEY_MINION_FUEL_TYPE, PersistentDataType.STRING,
                    fuelType == null ? "" : fuelType);
            pdc.set(KEY_MINION_FUEL_EXPIRES, PersistentDataType.LONG, fuelExpiresAt);
            pdc.set(KEY_MINION_STORAGE, PersistentDataType.STRING, storageEncoded);
            meta.setEnchantmentGlintOverride(true);
        });
        return item;
    }

    /** Tekstura base64; bez serwera zostaje zwykła głowa (try/catch jak SellChestService). */
    public static void applyHeadTexture(@NotNull SkullMeta meta, @Nullable String headTexture) {
        if (headTexture == null || headTexture.isBlank()) {
            return;
        }
        try {
            com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(
                    UUID.nameUUIDFromBytes(headTexture.getBytes(StandardCharsets.UTF_8)),
                    "wpme_minion");
            profile.setProperty(new ProfileProperty("textures", headTexture));
            meta.setPlayerProfile(profile);
        } catch (Throwable ignored) {
            // brak serwera w unit testach — akceptowalny fallback
        }
    }

    public static boolean isMinionItem(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        return TAG.equals(meta.getPersistentDataContainer()
                .get(KEY_MINION_ITEM, PersistentDataType.STRING));
    }

    public static @Nullable String typeId(@Nullable ItemStack item) {
        return string(item, KEY_MINION_TYPE);
    }

    public static int tier(@Nullable ItemStack item) {
        ItemMeta meta = meta(item);
        if (meta == null) {
            return 1;
        }
        Integer value = meta.getPersistentDataContainer().get(KEY_MINION_TIER, PersistentDataType.INTEGER);
        return value == null || value < 1 ? 1 : value;
    }

    public static boolean compactorEnabled(@Nullable ItemStack item) {
        ItemMeta meta = meta(item);
        if (meta == null) {
            return false;
        }
        Byte value = meta.getPersistentDataContainer().get(KEY_MINION_COMPACTOR, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    public static @Nullable String fuelType(@Nullable ItemStack item) {
        String value = string(item, KEY_MINION_FUEL_TYPE);
        return value == null || value.isBlank() ? null : value;
    }

    public static long fuelExpiresAt(@Nullable ItemStack item) {
        ItemMeta meta = meta(item);
        if (meta == null) {
            return 0L;
        }
        Long value = meta.getPersistentDataContainer().get(KEY_MINION_FUEL_EXPIRES, PersistentDataType.LONG);
        return value == null ? 0L : value;
    }

    public static @NotNull String storageEncoded(@Nullable ItemStack item) {
        String value = string(item, KEY_MINION_STORAGE);
        return value == null ? "" : value;
    }

    private static @Nullable String string(@Nullable ItemStack item, @NotNull NamespacedKey key) {
        ItemMeta meta = meta(item);
        return meta == null ? null
                : meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private static @Nullable ItemMeta meta(@Nullable ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta();
    }

    private static @NotNull Component noItalic(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
