package org.rafalohaki.wpmecore.addons.skyblock.hub;

import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;


import org.rafalohaki.wpmecore.addons.skyblock.shop.ServerShop;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Inventories;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Component-era custom tools. Identity and remaining uses live in Bukkit PDC. */
public final class WandService implements Listener {

    private static final long SELL_CONFIRM_MILLIS = 5_000L;

    private static final Map<Material, Material> CROP_SEEDS = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS,
            Material.NETHER_WART, Material.NETHER_WART,
            Material.COCOA, Material.COCOA_BEANS);

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage miniMessage;
    private final LedgerService ledger;
    private final ServerShop shop;
    private final InventoryOutbox outbox;
    private final SkylliaIntegration skyllia;
    private final WandDefinition sellWand;
    private final WandDefinition harvestWand;
    private final int sellWandPercent;
    private final NamespacedKey typeKey;
    private final NamespacedKey usesKey;
    private final NamespacedKey instanceKey;
    private final Map<UUID, SellConfirmation> sellConfirmations = new ConcurrentHashMap<>();

    public WandService(@NotNull JavaPlugin plugin, @NotNull MenuService menus,
                @NotNull MiniMessage miniMessage, @NotNull LedgerService ledger,
                @NotNull ServerShop shop, @NotNull InventoryOutbox outbox,
                @NotNull SkylliaIntegration skyllia,
                @NotNull WandDefinition sellWand,
                @NotNull WandDefinition harvestWand, int sellWandPercent) {
        this.plugin = plugin;
        this.menus = menus;
        this.miniMessage = miniMessage;
        this.ledger = ledger;
        this.shop = shop;
        this.outbox = outbox;
        this.skyllia = skyllia;
        this.sellWand = sellWand;
        this.harvestWand = harvestWand;
        this.sellWandPercent = sellWandPercent;
        this.typeKey = new NamespacedKey(plugin, "wand_type");
        this.usesKey = new NamespacedKey(plugin, "wand_uses");
        this.instanceKey = new NamespacedKey(plugin, "wand_instance");
        outbox.registerAuxiliaryHandler(InventoryOutbox.AUXILIARY_WAND_USE, this::applyAuxiliaryMutation);
    }

    public void open(@NotNull Player player) {
        MenuService.Menu menu = menus.ofRows(3,
                Ui.panelTitle(3, Ui.component(miniMessage, "<gold><bold>Magiczne narzędzia</bold></gold>")));
        Ui.frame(menu, miniMessage, Material.PURPLE_STAINED_GLASS_PANE);
        menu.decoration(4, Ui.item(Material.GOLD_INGOT, miniMessage,
                "<gold>Portfel: </gold>" + Ui.price(ledger.playerBalance(player.getUniqueId())),
                List.of(), true));
        addOffer(menu, 11, sellWand, player);
        addOffer(menu, 15, harvestWand, player);
        menu.close(22, Ui.closeButton(miniMessage));
        menu.open(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSellWand(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        WandData data = read(item);
        if (data == null || !data.type().equals(sellWand.id())) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission("skyblockgameplay.wands.use")) {
            player.sendActionBar(Ui.component(miniMessage,
                    "<red>Nie masz uprawnienia do używania różdżek.</red>"));
            return;
        }
        if (!player.isSneaking()) {
            sellConfirmations.remove(player.getUniqueId());
            player.sendActionBar(Ui.component(miniMessage,
                    "<yellow>Przytrzymaj Shift i użyj PPM, aby zobaczyć podsumowanie sprzedaży.</yellow>"));
            return;
        }
        ServerShop.SaleQuote quote = shop.quoteInventory(
                player.getInventory(),
                sellWandPercent + org.rafalohaki.wpmecore.addons.skyblock.perks.RankPerks.sellBonusPercent(player));
        if (quote.items() <= 0 || quote.payout() <= 0L) {
            sellConfirmations.remove(player.getUniqueId());
            player.sendActionBar(Ui.component(miniMessage,
                    "<red>Nie masz zwykłych przedmiotów skupowanych przez sklep.</red>"));
            return;
        }
        long now = System.currentTimeMillis();
        SellConfirmation previous = sellConfirmations.get(player.getUniqueId());
        if (previous == null || previous.expiresAt() < now
                || !previous.wandInstance().equals(data.instance())
                || !previous.quote().equals(quote)) {
            sellConfirmations.put(player.getUniqueId(), new SellConfirmation(
                    data.instance(), quote, now + SELL_CONFIRM_MILLIS));
            player.sendMessage(Ui.component(miniMessage,
                    "<yellow>Sprzedaż różdżką:</yellow> <white><items> przedmiotów</white>, "
                            + "wartość <gray><gross></gray>, prowizja <red><fee></red>, "
                            + "otrzymasz <gold><net></gold>. "
                            + "<yellow>Powtórz Shift+PPM w ciągu 5 sekund.</yellow>",
                    Placeholder.unparsed("items", String.valueOf(quote.items())),
                    Placeholder.unparsed("gross", Ui.money(quote.gross())),
                    Placeholder.unparsed("fee", Ui.money(quote.gross() - quote.payout())),
                    Placeholder.unparsed("net", Ui.money(quote.payout()))));
            return;
        }
        sellConfirmations.remove(player.getUniqueId(), previous);
        shop.sellAllWithWand(player, sellWandPercent, data.instance(),
                data.uses() - 1, ignored -> {
                    // Charge consumption is part of the same durable outbox.
                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sellConfirmations.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Validate before the event outcome is final so an attempted wand harvest
     * without a seed does not silently degrade into an ordinary block break.
     * Inventory mutations are deliberately deferred to the MONITOR handler.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHarvestValidate(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL
                || !player.hasPermission("skyblockgameplay.wands.use")) {
            return;
        }
        ItemStack wand = player.getInventory().getItemInMainHand();
        WandData data = read(wand);
        if (data == null || !data.type().equals(harvestWand.id())) {
            return;
        }
        if (!(event.getBlock().getBlockData() instanceof Ageable ageable)
                || ageable.getAge() < ageable.getMaximumAge()) {
            player.sendActionBar(Ui.component(miniMessage,
                    "<gray>Ta różdżka działa tylko na dojrzałe uprawy.</gray>"));
            return;
        }
        Material seed = CROP_SEEDS.get(event.getBlock().getType());
        if (seed == null || skyllia.islandAt(player.getUniqueId(),
                event.getBlock().getLocation()).isEmpty()) {
            return;
        }
        if (Inventories.countPlain(player.getInventory(), seed) < 1) {
            event.setCancelled(true);
            player.sendActionBar(Ui.component(miniMessage,
                    "<red>Potrzebujesz jednej zwykłej sadzonki do ponownego zasiania.</red>"));
        }
    }

    /** Commit only after all cancelling listeners have had their turn. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvestCommit(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL
                || !player.hasPermission("skyblockgameplay.wands.use")) {
            return;
        }
        ItemStack wand = player.getInventory().getItemInMainHand();
        WandData data = read(wand);
        if (data == null || !data.type().equals(harvestWand.id())
                || !(event.getBlock().getBlockData() instanceof Ageable ageable)
                || ageable.getAge() < ageable.getMaximumAge()) {
            return;
        }
        Material seed = CROP_SEEDS.get(event.getBlock().getType());
        if (seed == null || skyllia.islandAt(player.getUniqueId(),
                event.getBlock().getLocation()).isEmpty()
                || Inventories.countPlain(player.getInventory(), seed) < 1) {
            return;
        }
        Inventories.removePlain(player.getInventory(), seed, 1);
        consumeInHand(player, data, harvestWand);

        Ageable replanted = (Ageable) ageable.clone();
        replanted.setAge(0);
        Location location = event.getBlock().getLocation();
        plugin.getServer().getRegionScheduler().runDelayed(plugin, location, ignored -> {
            if (location.getBlock().getType().isAir()) {
                location.getBlock().setBlockData(replanted, false);
            }
        }, 1L);
    }

    private void addOffer(MenuService.Menu menu, int slot,
                          WandDefinition definition, Player player) {
        List<String> lore = new ArrayList<>(definition.lore());
        lore.add("<dark_gray> </dark_gray>");
        lore.add("<gray>Użycia: <white>" + definition.uses() + "</white></gray>");
        lore.add("<gray>Cena: </gray>" + Ui.price(definition.price()));
        lore.add("<yellow>Kliknij, aby kupić.</yellow>");
        menu.set(slot, Ui.item(definition.material(), miniMessage, definition.name(), lore, true),
                (viewer, click) -> purchase(viewer, definition));
    }

    private void purchase(Player player, WandDefinition definition) {
        if (player.getInventory().firstEmpty() < 0) {
            player.sendActionBar(Ui.component(miniMessage,
                    "<red>Zwolnij jedno miejsce w ekwipunku.</red>"));
            return;
        }
        String transaction = "wand:buy:" + UUID.randomUUID();
        outbox.beginGrant(player, -definition.price(), transaction, "wand_buy",
                create(definition), outcome -> {
                    switch (outcome) {
                        case SUCCESS -> player.sendActionBar(Ui.component(miniMessage,
                                "<green>Kupiono <item> za <gold><price></gold>.</green>",
                                Placeholder.component("item",
                                        Ui.component(miniMessage, definition.name())),
                                Placeholder.unparsed("price",
                                        Ui.money(definition.price()))));
                        case INSUFFICIENT -> player.sendActionBar(Ui.component(miniMessage,
                                "<red>Nie masz tylu monet.</red>"));
                        case BUSY -> player.sendActionBar(Ui.component(miniMessage,
                                "<gray>Poprzednia operacja jeszcze trwa.</gray>"));
                        case DEFERRED -> player.sendMessage(Ui.component(miniMessage,
                                "<yellow>Zakup zapisano. Zwolnij miejsce i wejdź ponownie, "
                                        + "aby odebrać różdżkę.</yellow>"));
                        case REJECTED, ERROR -> player.sendMessage(Ui.component(miniMessage,
                                "<red>Zakup nie został rozpoczęty. Nic nie pobrano.</red>"));
                    }
                    if (player.isOnline()) {
                        open(player);
                    }
                });
    }

    private ItemStack create(WandDefinition definition) {
        String instance = UUID.randomUUID().toString();
        ItemStack item = Ui.item(definition.material(), miniMessage, definition.name(),
                lore(definition, definition.uses()), true);
        item.editMeta(meta -> {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(typeKey, PersistentDataType.STRING, definition.id());
            pdc.set(usesKey, PersistentDataType.INTEGER, definition.uses());
            pdc.set(instanceKey, PersistentDataType.STRING, instance);
        });
        return item;
    }

    private WandData read(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String type = pdc.get(typeKey, PersistentDataType.STRING);
        Integer uses = pdc.get(usesKey, PersistentDataType.INTEGER);
        String instance = pdc.get(instanceKey, PersistentDataType.STRING);
        if (type == null || uses == null || uses <= 0 || instance == null) {
            return null;
        }
        return new WandData(type, uses, instance);
    }

    private boolean applyAuxiliaryMutation(Player player,
                                           LedgerDao.InventoryOperation operation) {
        if (operation.auxiliaryType() == null) {
            return true;
        }
        if (!InventoryOutbox.AUXILIARY_WAND_USE.equals(operation.auxiliaryType())
                || operation.auxiliaryKey() == null
                || operation.auxiliaryValue() == null
                || operation.auxiliaryValue() < 0L
                || operation.auxiliaryValue() > Integer.MAX_VALUE) {
            return false;
        }
        String instance = operation.auxiliaryKey();
        int targetUses = operation.auxiliaryValue().intValue();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            WandData data = read(item);
            if (data != null && data.instance().equals(instance)) {
                if (!data.type().equals(sellWand.id())
                        || data.uses() < targetUses) {
                    return false;
                }
                if (targetUses == 0) {
                    player.getInventory().setItem(slot, null);
                } else {
                    writeUses(item, sellWand, targetUses);
                    player.getInventory().setItem(slot, item);
                }
                return true;
            }
        }
        // Absence is the idempotent post-state only for the final charge.
        return targetUses == 0;
    }

    private void consumeInHand(Player player, WandData data,
                               WandDefinition definition) {
        if (data.uses() <= 1) {
            player.getInventory().setItemInMainHand(null);
            player.sendActionBar(Ui.component(miniMessage,
                    "<gray>Różdżka zużyła ostatni ładunek.</gray>"));
        } else {
            writeUses(player.getInventory().getItemInMainHand(), definition, data.uses() - 1);
        }
    }

    private void writeUses(ItemStack item, WandDefinition definition, int uses) {
        item.editMeta(meta -> {
            meta.lore(lore(definition, uses).stream()
                    .map(line -> Ui.component(miniMessage, line)).toList());
            meta.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, uses);
        });
    }

    private List<String> lore(WandDefinition definition, int uses) {
        List<String> lore = new ArrayList<>(definition.lore());
        lore.add("<dark_gray> </dark_gray>");
        lore.add("<gray>Pozostałe użycia: <white>" + uses + "</white></gray>");
        return lore;
    }

    private record WandData(@NotNull String type, int uses, @NotNull String instance) { }

    private record SellConfirmation(@NotNull String wandInstance,
                                    @NotNull ServerShop.SaleQuote quote,
                                    long expiresAt) { }
}
