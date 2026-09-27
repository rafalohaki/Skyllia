package org.rafalohaki.wpmecore.addons.skyblock.forge;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.forge.LeaseCarryPolicy.ClickVerb;
import org.rafalohaki.wpmecore.addons.skyblock.forge.LeaseCarryPolicy.ViewKind;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ECO-10: strażnik wynoszenia dzierżawy poza ekwipunek właściciela.
 *
 * <p>Znacznik {@code lease_expiry} sam niczego nie egzekwuje — bez tego
 * listenera gracz mógłby schować wypożyczoną kosmetykę do skrzyni/shulkerka
 * na wyspie i {@link LeaseSweepListener} (czytający wyłącznie ekwipunek)
 * nigdy by jej nie znalazł: przedmiot utrwaliłby się po wygaśnięciu, a
 * noszenie dałoby się spiętrzać między oknami sweepa. Ten guard domyka
 * inwariancję „dzierżawa nie opuści ekwipunku właściciela": blokuje wkładanie
 * do jakiegokolwiek obcego inwentarza (skrzynie, piece, enderchest, GUI),
 * wyrzucanie na ziemię, ramki i stojaki zbroi; automatyczne przesyle
 * (lejki/droppersy) odcina {@code InventoryMoveItemEvent}; przy śmierci
 * egzemplarz zostaje w ekwipunku zamiast w zrzucie do zrabowania.
 * Zabieranie z kontenerów z powrotem do siebie jest dozwolone (ratunek
 * egzemplarzy złożonych przed wdrożeniem guarda).
 *
 * <p>Decyzje dla kliknięć i przeciągnięć żyją w {@link LeaseCarryPolicy}
 * (czysty rdzeń bez Bukkit — testowalny bez serwera), tu jest wyłącznie
 * mapowanie enumów zdarzeniowych na wartości polityki.
 */
public final class LeaseCarryGuard implements Listener {

    /** Częstotliwość podpowiedzi, żeby shift-klik nie zalał czatu. */
    private static final long NOTICE_COOLDOWN_MS = 2_000L;

    /**
     * Widoki, w których vanilla shift-click z dolnego inwentarza przenosi
     * wyłącznie wewnątrz ekwipunku gracza (storage/hotbar/pancerz) — nigdy
     * do slotów górnych; sloty tych stołów zresztą i tak wracają do gracza
     * przy zamknięciu, więc nie potrafią trwale osadzić przedmiotu.
     * Wszystko inne (kontenery, piece, warzelnia, koń, enderchest, GUI
     * pluginów) traktujemy jak kontener — to tam dzierżawa utrwala się na stałe.
     */
    private static final Set<InventoryType> SHIFT_SAFE_VIEWS = EnumSet.of(
            InventoryType.CRAFTING, InventoryType.WORKBENCH, InventoryType.CREATIVE,
            InventoryType.MERCHANT, InventoryType.ENCHANTING, InventoryType.ANVIL,
            InventoryType.GRINDSTONE, InventoryType.STONECUTTER, InventoryType.LOOM,
            InventoryType.CARTOGRAPHY, InventoryType.SMITHING);

    private final JavaPlugin plugin;
    private final LeaseExpiry leaseExpiry;
    private final MiniMessage miniMessage;
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    public LeaseCarryGuard(@NotNull JavaPlugin plugin, @NotNull LeaseExpiry leaseExpiry,
                           @NotNull MiniMessage miniMessage) {
        this.plugin = plugin;
        this.leaseExpiry = leaseExpiry;
        this.miniMessage = miniMessage;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean cursorLease = isLease(event.getCursor());
        boolean currentLease = isLease(event.getCurrentItem());
        if (!cursorLease && !currentLease) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        boolean clickedTop = rawSlot >= 0 && rawSlot < topSize;
        ItemStack hotbar = hotbarItem(event, player);
        if (LeaseCarryPolicy.depositsLease(verbOf(event.getAction()), clickedTop,
                viewKindOf(event.getView().getType()),
                cursorLease, currentLease, isLease(hotbar))) {
            event.setCancelled(true);
            notifyLease(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || !isLease(event.getOldCursor())) {
            return;
        }
        if (LeaseCarryPolicy.dragDepositsLease(true, event.getRawSlots(),
                event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
            notifyLease(player);
        }
    }

    /** Lejki, droppersy i inne automatyczne przesyle — żadnej drogi na zewnątrz. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMoveItem(@NotNull InventoryMoveItemEvent event) {
        if (isLease(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        if (isLease(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            notifyLease(event.getPlayer());
        }
    }

    /**
     * Śmierć nie wyrzuca dzierżawy do zrabowania: pozycje usunięte z listy
     * zrzutu zostają w ekwipunku gracza (klasyczne zachowanie soulbound),
     * a sweep zdejmie je normalnie po wygaśnięciu.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        if (event.getDrops().removeIf(this::isLease)) {
            notifyLease(event.getPlayer());
        }
    }

    /** Ramka (zwykła i świecąca): klik prawym z przedmiotem osadza go na stałe. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        ItemStack used = event.getPlayer().getInventory().getItem(event.getHand());
        if (event.getRightClicked() instanceof ItemFrame && isLease(used)) {
            event.setCancelled(true);
            notifyLease(event.getPlayer());
        }
    }

    /** Stojak zbroi: podmiana ręką trzyma przedmiot gracza — trwale w NBT stojaka. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(@NotNull PlayerArmorStandManipulateEvent event) {
        if (isLease(event.getPlayerItem())) {
            event.setCancelled(true);
            notifyLease(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        lastNotice.remove(event.getPlayer().getUniqueId());
    }

    private static @NotNull ClickVerb verbOf(@NotNull InventoryAction action) {
        return switch (action) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR ->
                    ClickVerb.PLACE_FROM_CURSOR;
            case DROP_ALL_CURSOR, DROP_ONE_CURSOR -> ClickVerb.DROP_FROM_CURSOR;
            case DROP_ALL_SLOT, DROP_ONE_SLOT -> ClickVerb.DROP_FROM_SLOT;
            case MOVE_TO_OTHER_INVENTORY -> ClickVerb.SHIFT_MOVE;
            case HOTBAR_SWAP -> ClickVerb.HOTBAR_SWAP;
            default -> ClickVerb.TAKE;
        };
    }

    private static @NotNull ViewKind viewKindOf(@NotNull InventoryType type) {
        return SHIFT_SAFE_VIEWS.contains(type) ? ViewKind.STOOL : ViewKind.CONTAINER;
    }

    private static @Nullable ItemStack hotbarItem(@NotNull InventoryClickEvent event,
                                                  @NotNull Player player) {
        int button = event.getHotbarButton();
        return button < 0 ? null : player.getInventory().getItem(button);
    }

    private boolean isLease(@Nullable ItemStack stack) {
        return stack != null && !stack.getType().isAir()
                && leaseExpiry.expiryOf(stack) > 0L;
    }

    private void notifyLease(@NotNull Player player) {
        long now = System.currentTimeMillis();
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < NOTICE_COOLDOWN_MS) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        player.sendMessage(Ui.component(miniMessage,
                "<gray>Wypożyczony przedmiot zostaje w twoim ekwipunku do końca dzierżawy.</gray>"));
    }
}
