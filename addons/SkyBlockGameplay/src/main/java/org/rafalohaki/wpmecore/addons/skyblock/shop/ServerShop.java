package org.rafalohaki.wpmecore.addons.skyblock.shop;

import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Inventories;

import org.rafalohaki.wpmecore.addons.skyblock.shared.QuantityMath;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.UUID;
import java.util.function.Consumer;

public final class ServerShop {

    private final MiniMessage miniMessage;
    private final ShopCatalog catalog;
    private final InventoryOutbox outbox;
    private @Nullable CustomItemService customItems;

    public ServerShop(@NotNull MiniMessage miniMessage,
               @NotNull ShopCatalog catalog, @NotNull InventoryOutbox outbox) {
        this(miniMessage, catalog, outbox, null);
    }

    public ServerShop(@NotNull MiniMessage miniMessage,
               @NotNull ShopCatalog catalog, @NotNull InventoryOutbox outbox,
               @Nullable CustomItemService customItems) {
        this.miniMessage = miniMessage;
        this.catalog = catalog;
        this.outbox = outbox;
        this.customItems = customItems;
    }

    /** Cennik do odczytu (np. /wartosc); mutacje tylko przez przeładowanie configu. */
    public @NotNull ShopCatalog catalog() {
        return catalog;
    }

    public @Nullable CustomItemService getCustomItemService() {
        if (customItems != null) {
            return customItems;
        }
        try {
            return Bukkit.getServicesManager().load(CustomItemService.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void setCustomItemService(@Nullable CustomItemService customItems) {
        this.customItems = customItems;
    }

    public void sellAllWithWand(@NotNull Player player, int payoutPercent,
                         @NotNull String wandInstance, int remainingUses,
                         @NotNull Consumer<Boolean> completion) {
        SaleQuote quote = quoteInventory(player.getInventory(),
                payoutPercent + org.rafalohaki.wpmecore.addons.skyblock.perks.RankPerks.sellBonusPercent(player));
        if (quote.items() == 0 || quote.payout() <= 0L) {
            player.sendActionBar(Ui.component(miniMessage,
                    "<red>Nie masz zwykłych przedmiotów skupowanych przez sklep.</red>"));
            completion.accept(false);
            return;
        }
        String transaction = "wand:sell:" + UUID.randomUUID();
        outbox.beginRemoval(player, quote.payout(), transaction, "sell_wand",
                quote.counts(),
                new InventoryOutbox.AuxiliaryMutation(
                        InventoryOutbox.AUXILIARY_WAND_USE,
                        wandInstance, remainingUses),
                outcome -> {
                    if (outcome == InventoryOutbox.Outcome.SUCCESS) {
                        player.sendActionBar(Ui.component(miniMessage,
                                "<green>Różdżka sprzedała <white><amount> "
                                        + "przedmiotów</white> za "
                                        + "<gold><price></gold>.</green>",
                                Placeholder.unparsed("amount",
                                        String.valueOf(quote.items())),
                                Placeholder.unparsed("price", Ui.money(quote.payout()))));
                        player.sendMessage(Ui.component(miniMessage,
                                "<dark_gray>[</dark_gray><gold><bold>SPRZEDANO</bold></gold><dark_gray>]</dark_gray> "
                                        + "<gray>Różdżka sprzedaży spieniężyła</gray> <white><amount> przedmiotów</white> "
                                        + "<gray>na łączną kwotę</gray> <gold><price></gold><gray>.</gray>",
                                Placeholder.unparsed("amount",
                                        String.valueOf(quote.items())),
                                Placeholder.unparsed("price", Ui.money(quote.payout()))));
                        try {
                            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.5f);
                        } catch (Throwable ignored) {
                        }
                        completion.accept(true);
                    } else {
                        if (outcome == InventoryOutbox.Outcome.BUSY) {
                            player.sendActionBar(Ui.component(miniMessage,
                                    "<gray>Poprzednia operacja jeszcze trwa.</gray>"));
                        } else {
                            player.sendMessage(Ui.component(miniMessage,
                                    "<red>Sprzedaż różdżką nie została dokończona. "
                                            + "Trwały zapis zostanie sprawdzony ponownie.</red>"));
                        }
                        completion.accept(false);
                    }
                });
    }

    /** Sufit wypłaty: 90 % różdżki + 50 % maks. perku rangi (RankPerks) i nic więcej — przegląd 2026-09-05. */
    static final int MAX_SELL_PERCENT = 150;

    public @NotNull SaleQuote quoteInventory(PlayerInventory inventory, int percent) {
        percent = Math.max(0, Math.min(MAX_SELL_PERCENT, percent));
        java.util.Map<Material, Integer> counts = new java.util.EnumMap<>(Material.class);
        long gross = 0L;
        int itemCount = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (!QuantityMath.isPlain(stack)) {
                continue;
            }
            ShopCatalog.Product product = catalog.product(stack.getType());
            if (product == null || product.sell() <= 0L) {
                continue;
            }
            counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
            gross = Math.addExact(gross, Math.multiplyExact(product.sell(), stack.getAmount()));
            itemCount = Math.addExact(itemCount, stack.getAmount());
        }
        long payout = Math.multiplyExact(gross, percent) / 100L;
        return new SaleQuote(java.util.Map.copyOf(counts), itemCount, gross, payout);
    }

    public int countMatching(@NotNull PlayerInventory inventory, @NotNull ShopCatalog.Product product) {
        if (product.customItem() != null) {
            return countCustom(inventory, product.customItem());
        }
        return Inventories.countPlain(inventory, product.material());
    }

    public int countCustom(@NotNull PlayerInventory inventory, @NotNull String customItemId) {
        return countCustom(inventory, customItemId, getCustomItemService());
    }

    /**
     * Statyczny wariant dla trwałego usuwania.
     *
     * <p>{@link InventoryReceiptOps} musi liczyć <b>dokładnie tą samą funkcją</b>,
     * którą zdjęto baseline. Gdyby te dwie się rozjechały, zbieżność do celu
     * bezwzględnego nigdy by nie nastąpiła i operacja utknęłaby na zawsze.
     */
    public static int countCustom(@NotNull PlayerInventory inventory,
                                  @NotNull String customItemId,
                                  @Nullable CustomItemService customItemService) {
        return Inventories.countCustom(inventory, customItemId, customItemService);
    }

    public void removeCustom(@NotNull PlayerInventory inventory, @NotNull String customItemId, int amount) {
        removeCustom(inventory, customItemId, amount, getCustomItemService());
    }

    public static void removeCustom(@NotNull PlayerInventory inventory,
                                    @NotNull String customItemId, int amount,
                                    @Nullable CustomItemService customItemService) {
        Inventories.removeCustom(inventory, customItemId, amount, customItemService);
    }

    /**
     * Dopasowanie po identyfikatorze <b>plus</b> bramka handlowa.
     *
     * <p>Ścieżka vanilla odrzuca przedmioty oznaczone jako kolekcjonerskie lub
     * przypisane do gracza przez {@code isPlain}; customowa dotąd nie, więc
     * soulbound dawał się sprzedać. {@code ItemPolicyMarkers} jest w API opisany
     * jako jedna polityka dla wszystkich modułów handlu — tu ją wyrównujemy.
     */

    public static boolean matchesCustomItem(@NotNull ItemStack stack, @NotNull String customItemId,
                                            @Nullable CustomItemService customItemService) {
        return Inventories.matchesCustomItem(stack, customItemId, customItemService);
    }

    public record SaleQuote(@NotNull java.util.Map<Material, Integer> counts,
                     int items, long gross, long payout) {
        public SaleQuote {
            counts = java.util.Map.copyOf(counts);
        }
    }
}
