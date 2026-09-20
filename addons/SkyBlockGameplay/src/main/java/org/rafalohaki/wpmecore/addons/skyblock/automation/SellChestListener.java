package org.rafalohaki.wpmecore.addons.skyblock.automation;

import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.Optional;
import java.util.UUID;

/**
 * SellChestListener — handles block placement and breaking of SellChest containers.
 * Tags placed TileState with persistent PDC markers and synchronizes with SellChestService & DAO.
 */
public final class SellChestListener implements Listener {

    private final Plugin plugin;
    private final SellChestService sellChestService;
    private final MiniMessage miniMessage;
    private final SkylliaIntegration skyllia;

    public SellChestListener(@NotNull Plugin plugin,
                             @NotNull SellChestService sellChestService,
                             @NotNull MiniMessage miniMessage,
                             @Nullable SkylliaIntegration skyllia) {
        this.plugin = plugin;
        this.sellChestService = sellChestService;
        this.miniMessage = miniMessage;
        this.skyllia = skyllia;
    }

    public SellChestListener(@NotNull Plugin plugin,
                             @NotNull SellChestService sellChestService,
                             @NotNull MiniMessage miniMessage) {
        this(plugin, sellChestService, miniMessage, null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack itemInHand = event.getItemInHand();
        if (!sellChestService.isSellChestItem(itemInHand)) {
            return;
        }

        Block placed = event.getBlockPlaced();
        Player player = event.getPlayer();

        if (!(placed.getState() instanceof Container)) {
            event.setCancelled(true);
            player.sendMessage(miniMessage.deserialize("<red>Skrzynia Autosprzedaży musi być blokiem pojemnika!</red>")
                    .decoration(TextDecoration.ITALIC, false));
            return;
        }

        String world = placed.getWorld().getName();
        int x = placed.getX();
        int y = placed.getY();
        int z = placed.getZ();

        if (sellChestService.hasSellChest(world, x, y, z)) {
            event.setCancelled(true);
            player.sendMessage(miniMessage.deserialize("<red>W tym miejscu znajduje się już Skrzynia Autosprzedaży!</red>")
                    .decoration(TextDecoration.ITALIC, false));
            return;
        }

        /*
         * AUTO-2: bez wyspy nie ma dokąd wypłacać. Wcześniej islandId zostawał
         * UUID-em gracza i pieniądze szły na klucz island:<uuid-gracza> — konto,
         * którego żaden /bank nie odczyta. Odmawiamy postawienia dokładnie tak,
         * jak robi to MinionListener.
         */
        UUID islandId = player.getUniqueId();
        if (skyllia != null) {
            Optional<IslandView> islandOpt = skyllia.islandAt(player.getUniqueId(), placed.getLocation());
            if (islandOpt.isEmpty()) {
                event.setCancelled(true);
                player.sendMessage(miniMessage.deserialize(
                                "<red>Skrzynię Autosprzedaży można stawiać tylko na własnej wyspie.</red>")
                        .decoration(TextDecoration.ITALIC, false));
                return;
            }
            islandId = islandOpt.get().islandId();
        }

        SellChestRecord record = new SellChestRecord(
                islandId,
                world,
                x,
                y,
                z,
                player.getUniqueId(),
                System.currentTimeMillis()
        );

        // Persistent PDC tag on TileState
        BlockState state = placed.getState();
        if (state instanceof TileState tileState) {
            PersistentDataContainer pdc = tileState.getPersistentDataContainer();
            pdc.set(SellChestService.KEY_CUSTOM_ITEM, PersistentDataType.STRING, SellChestService.CUSTOM_ITEM_TAG);
            pdc.set(SellChestService.KEY_CUSTOM_ITEM_ID, PersistentDataType.STRING, SellChestService.CUSTOM_ITEM_TAG);
            tileState.update();
        }

        sellChestService.registerSellChest(record);
        player.sendMessage(miniMessage.deserialize("<green>Pomyślnie postawiono Skrzynię Autosprzedaży! Przedmioty będą sprzedawane co 20 sekund.</green>")
                .decoration(TextDecoration.ITALIC, false));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String world = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        Optional<SellChestRecord> recordOpt = sellChestService.getSellChest(world, x, y, z);
        boolean hasTileMarker = false;

        BlockState state = block.getState();
        if (state instanceof TileState tileState) {
            PersistentDataContainer pdc = tileState.getPersistentDataContainer();
            if (SellChestService.CUSTOM_ITEM_TAG.equals(pdc.get(SellChestService.KEY_CUSTOM_ITEM, PersistentDataType.STRING))
                    || SellChestService.CUSTOM_ITEM_TAG.equals(pdc.get(SellChestService.KEY_CUSTOM_ITEM_ID, PersistentDataType.STRING))) {
                hasTileMarker = true;
            }
        }

        if (recordOpt.isEmpty() && !hasTileMarker) {
            return;
        }

        /*
         * AUTO-3: rekord potrafi się osierocić (eksplozja nie odpala
         * BlockBreakEvent, a DELETE przy wyrejestrowaniu jest fire-and-forget).
         * Bez sprawdzenia, czy łamany blok NAPRAWDĘ jest skrzynią, gracz
         * dostawał nowy sell-chest (25 000 coins) za rozbicie postawionej tam
         * ziemi — w kółko, co ~15 s. Niezgodność = tylko wyrejestrowanie.
         */
        if (!(state instanceof Container)) {
            sellChestService.unregisterSellChest(world, x, y, z);
            return;
        }

        sellChestService.unregisterSellChest(world, x, y, z);
        event.setDropItems(false);
        block.getWorld().dropItemNaturally(block.getLocation(), sellChestService.createSellChestItem(1));
        event.getPlayer().sendMessage(miniMessage.deserialize("<yellow>Zdeaktywowano i usunięto Skrzynię Autosprzedaży.</yellow>")
                .decoration(TextDecoration.ITALIC, false));
    }
}
