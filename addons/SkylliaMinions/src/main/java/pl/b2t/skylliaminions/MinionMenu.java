package pl.b2t.skylliaminions;

import net.milkbowl.vault.economy.Economy;


import pl.b2t.skylliaminions.shared.ItemNames;
import pl.b2t.skylliaminions.shared.Materials;
import pl.b2t.skylliaminions.shared.Ui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;
import fr.euphyllia.skyllia.api.skyblock.Island;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * GUI minionka (spec §6): 54 sloty. Handlery działają na wątku entity gracza
 * (kontrakt MenuService); płatności asynchronicznie przez LedgerService z
 * powrotem na wątek gracza i odświeżeniem slotów. Fabryki handlerów i stackFor
 * są pakietowe — testy domyślnego suite nie mają RegistryAccess (brak serwera).
 */
public class MinionMenu {

    private static final int[] STORAGE_SLOTS = {
            10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int INFO_SLOT = 4;
    private static final int FUEL_SLOT = 37;
    private static final int COMPACTOR_SLOT = 39;
    private static final int UPGRADE_SLOT = 41;
    private static final int COLLECT_SLOT = 43;
    private static final int PICKUP_SLOT = 49;
    private static final String CUSTOM_PREFIX = "custom:";

    private final org.bukkit.plugin.Plugin plugin;
    private final MenuService menus;
    private final MiniMessage miniMessage;
    private final MinionService service;
    private final Economy economy;
    private final MinionsConfig config;
    private final CustomItemService customItems;
    private final MinionDisplayRenderer renderer;
    /**
     * A-raw: gracz widzi nazwy przedmiotów, nie surowe identyfikatory
     * ({@code DIAMOND}) — wzorzec {@code ItemNames} jak w OneBlockMilestoneMenu.
     */
    private final ItemNames itemNames;
    /**
     * MINION-6/7: ulepszenie biegnie przez dwa asynchroniczne round-tripy
     * (saldo, wypłata), a {@code setTier} ustawia wartość bezwzględną. Bez blokady
     * dwa szybkie kliknięcia płaciły dwa razy za ten sam tier. Klucz = minion.
     */
    private final java.util.Set<UUID> upgrading = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MinionMenu(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull MenuService menus,
                      @NotNull MiniMessage miniMessage, @NotNull MinionService service,
                      @Nullable Economy economy,
                      @NotNull MinionsConfig config, @Nullable CustomItemService customItems,
                      @NotNull MinionDisplayRenderer renderer) {
        this.plugin = plugin;
        this.menus = menus;
        this.miniMessage = miniMessage;
        this.service = service;
        this.economy = economy;
        this.config = config;
        this.customItems = customItems;
        this.renderer = renderer;
        this.itemNames = new ItemNames(customItems);
    }

    /** Wywoływane na wątku entity gracza. */
    public void open(@NotNull Player player, @NotNull UUID minionId) {
        MinionRecord record = service.snapshot(minionId).orElse(null);
        if (record == null) {
            return;
        }
        MenuService.Menu menu = menus.ofRows(6,
                Ui.component(miniMessage, "<gold><bold>Minionek</bold></gold>"));
        Ui.frame(menu, miniMessage, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        menu.close(53, Ui.closeButton(miniMessage));
        renderSlots(player, minionId, menu);
        menu.open(player);
    }

    private void renderSlots(@NotNull Player player, @NotNull UUID minionId,
                             @NotNull MenuService.Menu menu) {
        MinionRecord record = service.snapshot(minionId).orElse(null);
        if (record == null) {
            return;
        }
        MinionsConfig.TypeDef type = config.type(record.typeId());
        MinionsConfig.TierDef tier = config.tier(record.typeId(), record.tier());
        if (type == null || tier == null) {
            return;
        }
        MinionStorage storage = service.storage(minionId);

        // Slot 4: statystyki — głowa minionka z jego teksturą, nie książka.
        menu.decoration(INFO_SLOT, infoIcon(type, record, tier));

        // Sloty urobku (10-16, 19-25)
        Map<String, Long> contents = storage != null ? storage.snapshot() : Map.of();
        java.util.List<String> orderedKeys = java.util.List.copyOf(contents.keySet());
        int unlocked = tier.storageSlots();
        for (int index = 0; index < STORAGE_SLOTS.length; index++) {
            int slot = STORAGE_SLOTS[index];
            if (index >= unlocked) {
                menu.decoration(slot, Ui.item(Material.GRAY_DYE, miniMessage,
                        "<dark_gray>Zablokowany slot</dark_gray>",
                        java.util.List.of("<gray>Odblokujesz na wyższym poziomie.</gray>"), false));
                continue;
            }
            if (index < orderedKeys.size()) {
                String key = orderedKeys.get(index);
                ItemStack icon = iconFor(key, (int) Math.min(contents.get(key), 64));
                menu.set(slot, icon, storageClick(minionId, menu, key));
            } else {
                menu.decoration(slot, Ui.item(Material.BLACK_STAINED_GLASS_PANE, miniMessage,
                        "<dark_gray> </dark_gray>", java.util.List.of(), false));
            }
        }

        // Slot 37: paliwo
        long now = System.currentTimeMillis();
        boolean fuelActive = MinionFuel.isActive(record.fuelExpiresAt(), now);
        String fuelName = fuelActive
                ? "<gold>Paliwo: " + MinionFuel.remainingSeconds(record.fuelExpiresAt(), now) + " s</gold>"
                : "<gray>Brak aktywnego paliwa</gray>";
        menu.set(FUEL_SLOT, Ui.item(fuelActive ? Material.LAVA_BUCKET : Material.BUCKET, miniMessage,
                fuelName,
                java.util.List.of("<gray>Kliknij z paliwem w kursorze, aby zatankować.</gray>"),
                fuelActive), refuelClick(minionId, menu));

        // Slot 39: komparktor
        menu.set(COMPACTOR_SLOT, Ui.item(
                        record.compactorEnabled() ? Material.HOPPER : Material.FURNACE, miniMessage,
                        record.compactorEnabled()
                                ? "<green>Auto-Kompaktor: WŁĄCZONY</green>"
                                : "<red>Auto-Kompaktor: WYŁĄCZONY</red>",
                        java.util.List.of("<gray>Automatycznie zbija 9 surowców w blok.</gray>"),
                        record.compactorEnabled()),
                compactorClick(minionId, menu));

        // Slot 41: ulepszenie
        MinionsConfig.TierDef next = config.tier(record.typeId(), record.tier() + 1);
        if (next == null) {
            menu.decoration(UPGRADE_SLOT, Ui.item(Material.NETHER_STAR, miniMessage,
                    "<gold>Maksymalny poziom</gold>", java.util.List.of(), true));
        } else {
            menu.set(UPGRADE_SLOT, Ui.item(Material.EXPERIENCE_BOTTLE, miniMessage,
                    "<green>Ulepsz do poziomu " + next.tier() + "</green>",
                    java.util.List.of(
                            "<gray>Koszt: <white>" + Ui.money(next.upgradeCostMoney())
                                    + "</white> z konta wyspy</gray>",
                            "<gray>+ <white>" + next.upgradeCostItems() + " x "
                                    + itemNames.vanillaLabel(type.primaryItem()) + "</white> z ekwipunku</gray>",
                            "<gray>Nowy cykl: <white>" + next.intervalSeconds() + " s</white></gray>"),
                    true), upgradeClick(minionId, menu));
        }

        // Slot 43: zbierz wszystko
        menu.set(COLLECT_SLOT, Ui.item(Material.CHEST_MINECART, miniMessage,
                "<yellow>Zbierz cały urobek</yellow>",
                java.util.List.of("<gray>Przenosi wszystkie surowce do ekwipunku.</gray>"), false),
                collectAllClick(minionId, menu));

        // Slot 49: podnieś minionka
        menu.set(PICKUP_SLOT, Ui.item(Material.ARMOR_STAND, miniMessage,
                "<red>Podnieś minionka</red>",
                java.util.List.of("<gray>Zwraca przedmiot z zachowaniem poziomu i ulepszeń.</gray>"),
                false), pickUpClick(minionId));
    }

    /**
     * Ikona statystyk: głowa z teksturą typu. Pakietowa widoczność jak reszta
     * seamów — budowa meta głowy wymaga serwera, testy podmieniają.
     * {@code Ui.decorate} klonuje i edytuje kopię meta, więc tekstura przeżywa.
     */
    @NotNull ItemStack infoIcon(@NotNull MinionsConfig.TypeDef type,
                               @NotNull MinionRecord record,
                               @NotNull MinionsConfig.TierDef tier) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (type.headTexture() != null) {
            head.editMeta(org.bukkit.inventory.meta.SkullMeta.class, meta ->
                    MinionItem.applyHeadTexture(meta, type.headTexture()));
        }
        return Ui.decorate(head, miniMessage,
                type.name() + " <dark_gray>Poziom " + record.tier() + "</dark_gray>",
                java.util.List.of(
                        "<gray>Cykl co: <white>" + tier.intervalSeconds() + " s</white></gray>",
                        "<gray>Wygenerowano łącznie: <white>"
                                + record.totalGenerated() + "</white></gray>",
                        "<gray>Sloty magazynu: <white>" + tier.storageSlots() + "</white></gray>"),
                true);
    }

    // ---- fabryki handlerów (pakietowe: testy wołają bez budowania ikon) ----

    MenuService.ClickHandler storageClick(@NotNull UUID minionId,
                                               @NotNull MenuService.Menu menu, @NotNull String key) {
        return (viewer, click) -> {
            MinionStorage storage = service.storage(minionId);
            if (storage == null) {
                return;
            }
            long take = Math.min(storage.count(key), storage.slotCapacity(key));
            if (take <= 0L) {
                return;
            }
            long given = giveToPlayer(viewer, key, take);
            if (given > 0L) {
                storage.remove(key, given);
                service.markDirty(minionId);
            }
            if (menu != null) {
                renderSlots(viewer, minionId, menu);
            }
        };
    }

    MenuService.ClickHandler collectAllClick(@NotNull UUID minionId,
                                                  @NotNull MenuService.Menu menu) {
        return (viewer, click) -> {
            MinionStorage storage = service.storage(minionId);
            if (storage == null) {
                return;
            }
            for (Map.Entry<String, Long> entry : storage.snapshot().entrySet()) {
                long given = giveToPlayer(viewer, entry.getKey(), entry.getValue());
                if (given > 0L) {
                    storage.remove(entry.getKey(), given);
                }
            }
            service.markDirty(minionId);
            if (menu != null) {
                renderSlots(viewer, minionId, menu);
            }
        };
    }

    MenuService.ClickHandler refuelClick(@NotNull UUID minionId, @NotNull MenuService.Menu menu) {
        return (viewer, click) -> {
            ItemStack cursor = viewer.getItemOnCursor();
            if (cursor == null || Materials.isAir(cursor.getType())) {
                viewer.sendMessage(miniMessage.deserialize(
                        "<gray>Weź przedmiot-paliwo w kursor i kliknij slot paliwa.</gray>"));
                return;
            }
            MinionsConfig.FuelDef fuel = resolveFuel(cursor);
            if (fuel == null) {
                viewer.sendMessage(miniMessage.deserialize("<red>Ten przedmiot nie jest paliwem.</red>"));
                return;
            }
            // MINION-8: paliwo schodzi z kursora dopiero po udanej aktywacji —
            // wcześniej odrzucenie (minionek zdjęty w międzyczasie) zjadało
            // przedmiot bez słowa.
            if (!service.activateFuel(minionId, fuel)) {
                viewer.sendMessage(miniMessage.deserialize(
                        "<red>Nie udało się zatankować — minionek nie jest już aktywny.</red>"));
                viewer.closeInventory();
                return;
            }
            cursor.setAmount(cursor.getAmount() - 1);
            viewer.setItemOnCursor(cursor.getAmount() > 0 ? cursor : null);
            viewer.sendMessage(miniMessage.deserialize("<green>Zatankowano: "
                    + fuel.durationSeconds() / 3600 + "h (x" + fuel.speedMultiplier() + ").</green>"));
            if (menu != null) {
                renderSlots(viewer, minionId, menu);
            }
        };
    }

    MenuService.ClickHandler compactorClick(@NotNull UUID minionId, @NotNull MenuService.Menu menu) {
        return (viewer, click) -> {
            MinionRecord record = service.snapshot(minionId).orElse(null);
            if (record == null) {
                return;
            }
            boolean next = !record.compactorEnabled();
            if (service.setCompactorEnabled(minionId, next)) {
                viewer.sendMessage(miniMessage.deserialize(next
                        ? "<green>Kompaktor włączony.</green>"
                        : "<yellow>Kompaktor wyłączony.</yellow>"));
            }
            if (menu != null) {
                renderSlots(viewer, minionId, menu);
            }
        };
    }

    MenuService.ClickHandler upgradeClick(@NotNull UUID minionId, @NotNull MenuService.Menu menu) {
        return (viewer, click) -> {
            MinionRecord record = service.snapshot(minionId).orElse(null);
            if (record == null) {
                return;
            }
            MinionsConfig.TierDef next = config.tier(record.typeId(), record.tier() + 1);
            MinionsConfig.TypeDef type = config.type(record.typeId());
            if (next == null || type == null) {
                return;
            }
            Island myIsland = Islands.islandOf(viewer.getUniqueId());
            if (myIsland == null || !myIsland.getId().equals(record.islandId())
                    || !Islands.canWithdraw(myIsland, viewer.getUniqueId())) {
                viewer.sendMessage(miniMessage.deserialize(
                        "<red>Tylko właściciel lub zastępca wyspy może ulepszać minionki.</red>"));
                return;
            }
            Material primary = type.primaryItem();
            if (next.upgradeCostItems() > 0 && countMaterial(viewer, primary) < next.upgradeCostItems()) {
                viewer.sendMessage(miniMessage.deserialize("<red>Potrzebujesz "
                        + next.upgradeCostItems() + " x " + itemNames.vanillaLabel(primary)
                        + " w ekwipunku.</red>"));
                return;
            }
            if (next.upgradeCostMoney() > 0) {
                if (economy == null) {
                    viewer.sendMessage(miniMessage.deserialize("<red>Ekonomia nie jest dostępna.</red>"));
                    return;
                }
                if (!upgrading.add(minionId)) {
                    viewer.sendMessage(miniMessage.deserialize(
                            "<yellow>Ulepszenie tego minionka już trwa.</yellow>"));
                    return;
                }
                try {
                    if (economy.getBalance(viewer) < next.upgradeCostMoney()) {
                        viewer.sendMessage(miniMessage.deserialize(
                                "<red>Za mało monet (koszt: "
                                        + Ui.money(next.upgradeCostMoney()) + ").</red>"));
                        return;
                    }
                    // materiały zdejmujemy PRZED płatnością — składniki łatwiej oddać
                    if (next.upgradeCostItems() > 0
                            && countMaterial(viewer, primary) < next.upgradeCostItems()) {
                        viewer.sendMessage(miniMessage.deserialize("<red>Potrzebujesz "
                                + next.upgradeCostItems() + " x " + itemNames.vanillaLabel(primary)
                                + " w ekwipunku.</red>"));
                        return;
                    }
                    removeMaterial(viewer, primary, next.upgradeCostItems());
                    if (!economy.withdrawPlayer(viewer, next.upgradeCostMoney()).transactionSuccess()) {
                        refundMaterial(viewer, primary, next.upgradeCostItems());
                        viewer.sendMessage(miniMessage.deserialize(
                                "<red>Płatność nie powiodła się.</red>"));
                        return;
                    }
                    if (service.setTier(minionId, next.tier())) {
                        viewer.sendMessage(miniMessage.deserialize(
                                "<green>Ulepszono do poziomu " + next.tier() + "!</green>"));
                    }
                    renderSlots(viewer, minionId, menu);
                } finally {
                    upgrading.remove(minionId);
                }
            } else {
                removeMaterial(viewer, primary, next.upgradeCostItems());
                if (service.setTier(minionId, next.tier())) {
                    viewer.sendMessage(miniMessage.deserialize(
                            "<green>Ulepszono do poziomu " + next.tier() + "!</green>"));
                }
                if (menu != null) {
                renderSlots(viewer, minionId, menu);
            }
            }
        };
    }

    MenuService.ClickHandler pickUpClick(@NotNull UUID minionId) {
        return (viewer, click) -> {
            MinionRecord current = service.snapshot(minionId).orElse(null);
            if (current == null) {
                viewer.closeInventory();
                return;
            }
            // MINION-10: ulepszanie wymagało roli, podnoszenie nie — świeżo przyjęty
            // członek mógł zabrać minionka właściciela do plecaka.
            Island myIsland = Islands.islandOf(viewer.getUniqueId());
            if (myIsland == null || !myIsland.getId().equals(current.islandId())
                    || !Islands.canWithdraw(myIsland, viewer.getUniqueId())) {
                viewer.sendMessage(miniMessage.deserialize(
                        "<red>Tylko właściciel lub zastępca wyspy może podnieść minionka.</red>"));
                return;
            }
            // MINION-9: pickUp kasuje wiersz i RAM zanim cokolwiek wróci do gracza —
            // przy nieznanym typie (stary config) minionek znikał bez przedmiotu.
            if (config.type(current.typeId()) == null) {
                viewer.sendMessage(miniMessage.deserialize(
                        "<red>Nieznany typ minionka (" + current.typeId()
                                + ") — zgłoś to administracji, minionek zostaje na miejscu.</red>"));
                return;
            }
            MinionRecord record = service.pickUp(minionId).orElse(null);
            if (record == null) {
                viewer.closeInventory();
                return;
            }
            World world = null;
            try {
                world = Bukkit.getWorld(record.world());
            } catch (Throwable offline) {
                // Bukkit.server bywa nullem w testach bez serwera
            }
            if (world != null) {
                plugin.getServer().getRegionScheduler().execute(plugin,
                        new Location(world, record.x(), record.y(), record.z()),
                        () -> renderer.despawn(record));
            }
            Map<String, Long> travelling = handOverStorage(viewer, record.storage());
            viewer.closeInventory();
            MinionsConfig.TypeDef type = config.type(record.typeId());
            if (type != null) {
                try {
                    ItemStack minionItem = MinionItem.create(type, record.tier(),
                            record.compactorEnabled(), record.fuelType(), record.fuelExpiresAt(),
                            MinionRecord.encodeStorage(travelling), miniMessage);
                    Map<Integer, ItemStack> leftover = viewer.getInventory().addItem(minionItem);
                    leftover.values().forEach(stack ->
                            viewer.getWorld().dropItemNaturally(viewer.getLocation(), stack));
                } catch (Throwable failure) {
                    // budowa meta wymaga serwera; w produkcji nie powinno się zdarzyć
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to build minion item on pickup", failure);
                }
            }
            if (!travelling.isEmpty()) {
                viewer.sendMessage(miniMessage.deserialize(
                        "<gold>Ekwipunek pełny — reszta urobku jedzie w minionku."
                                + " Postaw go i opróżnij.</gold>"));
            }
            viewer.sendMessage(miniMessage.deserialize("<yellow>Podniesiono minionka.</yellow>"));
        };
    }

    // ---- pomocnicze ----

    private @Nullable MinionsConfig.FuelDef resolveFuel(@NotNull ItemStack cursor) {
        for (MinionsConfig.FuelDef fuel : config.fuels().values()) {
            if (fuel.item() != null && fuel.item() == cursor.getType()) {
                return fuel;
            }
            if (fuel.customItemId() != null && customItems != null) {
                String id = customItems.idOf(cursor);
                if (id != null && id.equalsIgnoreCase(fuel.customItemId())) {
                    return fuel;
                }
            }
        }
        return null;
    }

    /**
     * Oddaje graczowi magazyn podniesionego minionka i zwraca resztę, która ma
     * pojechać zakodowana w przedmiocie.
     *
     * <p>Każda sztuka musi pojechać dokładnie jedną z tych dwóch dróg. Wydanie
     * całości graczowi i zakodowanie tej samej całości w przedmiocie daje pętlę
     * postaw–podnieś, która mnoży urobek bez ograniczenia; wydanie tylko tego, co
     * się zmieściło, i porzucenie reszty — cicho ją gubi przy pełnym ekwipunku.
     */
    @NotNull Map<String, Long> handOverStorage(@NotNull Player viewer,
                                               @NotNull Map<String, Long> storage) {
        Map<String, Long> travelling = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : storage.entrySet()) {
            long owed = entry.getValue();
            long left = owed - giveToPlayer(viewer, entry.getKey(), owed);
            if (left > 0L) {
                travelling.put(entry.getKey(), left);
            }
        }
        return travelling;
    }

    long giveToPlayer(@NotNull Player viewer, @NotNull String key, long count) {
        long given = 0L;
        while (count > 0L) {
            int batch = (int) Math.min(count, 64L);
            ItemStack stack = stackFor(key, batch);
            if (stack == null) {
                break;
            }
            Map<Integer, ItemStack> leftover = viewer.getInventory().addItem(stack);
            int returned = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            int accepted = batch - returned;
            if (accepted <= 0) {
                break;
            }
            given += accepted;
            count -= accepted;
        }
        return given;
    }

    /** Pakietowa widoczność: testy podmieniają (realna konstrukcja wymaga serwera). */
    @Nullable ItemStack stackFor(@NotNull String key, int amount) {
        if (key.startsWith(CUSTOM_PREFIX)) {
            if (customItems == null) {
                return null;
            }
            Optional<ItemStack> created = customItems.create(key.substring(CUSTOM_PREFIX.length()));
            if (created.isEmpty()) {
                return null;
            }
            ItemStack stack = created.get();
            stack.setAmount(amount);
            return stack;
        }
        Material material = Material.matchMaterial(key);
        if (material == null || Materials.isAir(material)) {
            return null;
        }
        ItemStack stack = new ItemStack(material);
        stack.setAmount(amount);
        return stack;
    }

    private @NotNull ItemStack iconFor(@NotNull String key, int amount) {
        ItemStack stack = stackFor(key, amount);
        return stack != null ? stack : new ItemStack(Material.PAPER);
    }

    /** Oddaje składniki po nieudanej wypłacie; co nie wejdzie do plecaka, ląduje pod nogami. */
    private void refundMaterial(@NotNull Player viewer, @NotNull Material material, int amount) {
        if (amount <= 0) {
            return;
        }
        ItemStack stack = stackFor(material.name(), amount);
        if (stack == null) {
            return;
        }
        viewer.getInventory().addItem(stack).values()
                .forEach(rest -> viewer.getWorld().dropItemNaturally(viewer.getLocation(), rest));
    }

    private int countMaterial(@NotNull Player viewer, @NotNull Material material) {
        int total = 0;
        for (ItemStack stack : viewer.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private void removeMaterial(@NotNull Player viewer, @NotNull Material material, int amount) {
        ItemStack[] contents = viewer.getInventory().getStorageContents();
        for (int index = 0; index < contents.length && amount > 0; index++) {
            ItemStack stack = contents[index];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int taken = Math.min(amount, stack.getAmount());
            stack.setAmount(stack.getAmount() - taken);
            contents[index] = stack.getAmount() > 0 ? stack : null;
            amount -= taken;
        }
        viewer.getInventory().setStorageContents(contents);
    }

    private void playerThread(@NotNull Player viewer, @NotNull Runnable action) {
        viewer.getScheduler().run(plugin, task -> action.run(), null);
    }
}
