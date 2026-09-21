package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.shared.ItemNames;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.UUID;

/**
 * Claim screen for frozen OneBlock milestone loot (D6/D7). Claim is atomic via
 * UPDATE ... WHERE claimed_at IS NULL; items are granted only after exactly one
 * row was won. A full inventory reverts the claim with the very same timestamp
 * instead of consuming it, so the reward can be retried; a partial overflow is
 * first rolled back item-for-item, so retrying can never duplicate loot.
 */
public final class OneBlockMilestoneMenu {

    /** Usable slots per menu row once the border columns (0 and 8) are skipped. */
    private static final int SLOTS_PER_ROW = 7;
    private static final int MAX_ROWS = 6;
    private static final int FIRST_SLOT = 10;
    /** Środek rzędu nagłówka — wolny slot, na którym melduje się nadmiar nagród. */
    private static final int HEADER_INFO_SLOT = 4;

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage miniMessage;
    private final OneBlockMilestoneDao milestoneDao;
    private final CustomItemService customItems;
    private final ItemNames itemNames;
    private final SkylliaIntegration skyllia;

    public OneBlockMilestoneMenu(@NotNull JavaPlugin plugin, @NotNull MenuService menus,
                          @NotNull MiniMessage miniMessage,
                          @NotNull OneBlockMilestoneDao milestoneDao,
                          @NotNull CustomItemService customItems,
                          @NotNull SkylliaIntegration skyllia) {
        this.plugin = plugin;
        this.menus = menus;
        this.miniMessage = miniMessage;
        this.milestoneDao = milestoneDao;
        this.customItems = customItems;
        this.itemNames = new ItemNames(customItems);
        this.skyllia = skyllia;
    }

    public void openFor(@NotNull Player player) {
        skyllia.islandOf(player.getUniqueId()).ifPresentOrElse(view ->
                        open(player, view.islandId()),
                () -> player.sendMessage(miniMessage.deserialize(
                        "<red>Nie masz wyspy — utwórz ją, aby zbierać nagrody OneBlock.</red>")));
    }

    void open(@NotNull Player player, @NotNull UUID islandId) {
        open(player, islandId, true);
    }

    /**
     * @param announceWhenEmpty {@code false} when refreshing right after a claim —
     *                          the player already got the claim result message, so
     *                          an extra "nothing to claim" line would be noise.
     */
    private void open(@NotNull Player player, @NotNull UUID islandId, boolean announceWhenEmpty) {
        milestoneDao.listUnclaimed(islandId)
                .thenAccept(rows ->
                        runOnPlayer(player, () -> render(player, islandId, rows, announceWhenEmpty),
                                () -> { }))
                .exceptionally(err -> {
                    plugin.getLogger().log(Level.WARNING,
                            "Failed to list OneBlock milestones for island " + islandId, err);
                    runOnPlayer(player, () -> player.sendMessage(miniMessage.deserialize(
                                    "<red>Nie udało się wczytać nagród. Spróbuj ponownie.</red>")),
                            () -> { });
                    return null;
                });
    }

    private void render(@NotNull Player player, @NotNull UUID islandId,
                        @NotNull List<OneBlockMilestoneDao.MilestoneRow> rows,
                        boolean announceWhenEmpty) {
        if (rows.isEmpty()) {
            if (announceWhenEmpty) {
                player.sendMessage(miniMessage.deserialize(
                        "<gray>Brak nagród do odebrania — kop dalej!</gray>"));
            }
            return;
        }
        int menuRows = Math.min(MAX_ROWS, 1 + (rows.size() + SLOTS_PER_ROW - 1) / SLOTS_PER_ROW);
        MenuService.Menu menu = menus.ofRows(menuRows, Ui.panelTitle(
                menuRows == 4 ? Ui.GUI_ONEBLOCK : Ui.glyphNeutral(menuRows),
                Ui.component(miniMessage, "<gold><bold>Nagrody OneBlock</bold></gold>")));
        Ui.frame(menu, miniMessage, Material.LIME_STAINED_GLASS_PANE);
        int capacity = SLOTS_PER_ROW * (menuRows - 1);
        int slot = FIRST_SLOT;
        int placed = 0;
        for (OneBlockMilestoneDao.MilestoneRow row : rows) {
            if (placed >= capacity) {
                break;
            }
            menu.set(slot, preview(row), (viewer, click) -> claim(viewer, islandId, row));
            slot = nextSlot(slot);
            placed++;
        }
        if (placed < rows.size()) {
            // Menu ma co najwyżej MAX_ROWS rzędów, więc bardzo długa lista nie
            // mieści się w całości. Nadmiaru nie chowamy po cichu: nagłówek mówi,
            // ile nagród widać, żeby gracz wiedział, że odbierając te odsłoni kolejne.
            menu.decoration(HEADER_INFO_SLOT, Ui.item(Material.PAPER, miniMessage,
                    "<gold>Widoczne nagrody: " + placed + " z " + rows.size() + "</gold>",
                    List.of("<gray>Odbierz widoczne, aby zobaczyć kolejne.</gray>"), false));
        }
        // Zamknięcie w prawym dolnym narożniku: treść zajmuje wyłącznie kolumny
        // 1..7, więc ten slot nigdy nie dzieli się z kamieniem milowym
        // (wcześniej stało tu close(size()-5), czyli slot 13 = czwarty kamień).
        menu.close(menu.size() - 1, Ui.closeButton(miniMessage));
        menu.open(player);
    }

    /** Advance to the next slot, skipping the right border column and the next row's left border. */
    private int nextSlot(int slot) {
        int next = slot + 1;
        return next % 9 == 8 ? next + 2 : next;
    }

    private @NotNull ItemStack preview(@NotNull OneBlockMilestoneDao.MilestoneRow row) {
        List<OneBlockLoot.Entry> loot = OneBlockLoot.decode(row.loot());
        OneBlockLoot.Entry first = loot.isEmpty()
                ? new OneBlockLoot.Entry(null, Material.CHEST, 1) : loot.getFirst();
        ItemStack icon = iconFor(first);
        List<Component> lines = new ArrayList<>(loot.stream().map(this::lootLine).toList());
        lines.add(miniMessage.deserialize("<dark_gray> </dark_gray>"));
        lines.add(miniMessage.deserialize("<yellow>Kliknij, aby odebrać nagrodę.</yellow>"));
        icon.editMeta(meta -> meta.lore(lines));
        return icon;
    }

    /** Custom items carry item-model data; plain materials fall back to a vanilla stack. */
    private @NotNull ItemStack iconFor(@NotNull OneBlockLoot.Entry entry) {
        if (entry.item() != null) {
            ItemStack custom = customItems.create(entry.item()).orElse(null);
            if (custom != null) {
                return custom;
            }
        }
        return new ItemStack(entry.material() != null ? entry.material() : Material.CHEST);
    }

    private @NotNull Component lootLine(@NotNull OneBlockLoot.Entry entry) {
        String label = entry.item() != null
                ? itemNames.customLabel(entry.item())
                : itemNames.vanillaLabel(entry.material() != null ? entry.material() : Material.CHEST);
        return miniMessage.deserialize("<gray>• " + label + " ×" + entry.amount() + "</gray>");
    }

    void claim(@NotNull Player player, @NotNull UUID islandId,
               @NotNull OneBlockMilestoneDao.MilestoneRow row) {
        long now = System.currentTimeMillis();
        milestoneDao.claim(islandId, row.milestoneKey(), now).thenAccept(updated ->
                runOnPlayer(player, () -> {
                    if (updated != 1) {
                        player.sendMessage(miniMessage.deserialize(
                                "<red>Nagroda została już odebrana.</red>"));
                        open(player, islandId, false);
                        return;
                    }
                    List<ItemStack> stacks = new ArrayList<>();
                    for (OneBlockLoot.Entry entry : OneBlockLoot.decode(row.loot())) {
                        ItemStack stack = stackFor(entry);
                        if (stack == null) {
                            continue;
                        }
                        stack.setAmount(entry.amount());
                        stacks.add(stack);
                    }
                    ItemStack[] toAdd = stacks.toArray(ItemStack[]::new);
                    // Bukkit mutates the passed stacks while merging — snapshot intended
                    // amounts so the rollback below knows how much each stack was worth.
                    int[] intended = new int[toAdd.length];
                    for (int i = 0; i < toAdd.length; i++) {
                        intended[i] = toAdd[i].getAmount();
                    }
                    Map<Integer, ItemStack> overflow =
                            player.getInventory().addItem(toAdd);
                    if (!overflow.isEmpty()) {
                        takeBackWhatFit(player, toAdd, intended, overflow);
                        // The items are already back with us, so a failed revert leaves the
                        // row claimed and the reward gone for good. Say so loudly instead of
                        // letting the player believe they can simply retry.
                        milestoneDao.revert(islandId, row.milestoneKey(), now).exceptionally(err -> {
                            plugin.getLogger().log(Level.SEVERE,
                                    "Failed to revert OneBlock milestone " + row.milestoneKey()
                                            + " for island " + islandId
                                            + " — reward is lost until restored by hand", err);
                            runOnPlayer(player, () -> player.sendMessage(miniMessage.deserialize(
                                            "<red>Błąd zwrotu nagrody. Zgłoś to administracji.</red>")),
                                    () -> { });
                            return null;
                        });
                        player.sendMessage(miniMessage.deserialize(
                                "<red>Ekwipunek jest pełny — zrób miejsce i odbierz ponownie.</red>"));
                    } else {
                        player.sendMessage(miniMessage.deserialize(
                                "<green>Odebrałeś nagrodę za postęp OneBlock.</green>"));
                        // Adventure sound (pure data) instead of org.bukkit.Sound: the Bukkit
                        // interface resolves every constant through the sound registry at class
                        // init, which a plain unit-test JVM cannot do.
                        player.playSound(Sound.sound(Key.key("entity.player.levelup"),
                                Sound.Source.MASTER, 1.0F, 1.2F));
                    }
                    open(player, islandId, false);
                }, () -> revertUndeliveredClaim(islandId, row, updated, now)))
                .exceptionally(err -> {
                    plugin.getLogger().log(Level.SEVERE,
                            "Failed to claim OneBlock milestone " + row.milestoneKey()
                                    + " for island " + islandId, err);
                    runOnPlayer(player, () -> player.sendMessage(miniMessage.deserialize(
                                    "<red>Nie udało się odebrać nagrody. Spróbuj ponownie.</red>")),
                            () -> { });
                    return null;
                });
    }

    /**
     * Planowanie wydania padło (gracz wyszedł między UPDATE claim a taskiem):
     * oddaj wiersz dokładnie tym samym timestampem, żeby nagroda nie przepadła
     * bezpowrotnie. Idempotentne — retired-callback i null z run() mogą
     * wystąpić dla tego samego planowania (SKYBLOCK-2-3).
     */
    private void revertUndeliveredClaim(@NotNull UUID islandId,
                                        @NotNull OneBlockMilestoneDao.MilestoneRow row,
                                        int updated, long now) {
        if (updated != 1) {
            return;
        }
        plugin.getLogger().warning("OneBlock milestone " + row.milestoneKey()
                + " claimed for island " + islandId
                + " but delivery could not be scheduled — reverting the claim row");
        milestoneDao.revert(islandId, row.milestoneKey(), now).exceptionally(err -> {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to revert OneBlock milestone " + row.milestoneKey()
                            + " for island " + islandId
                            + " — reward is lost until restored by hand", err);
            return null;
        });
    }

    /** Builds the claimable stack for one loot entry; {@code null} = skip (logged). */
    private ItemStack stackFor(@NotNull OneBlockLoot.Entry entry) {
        if (entry.item() != null) {
            ItemStack custom = customItems.create(entry.item()).orElse(null);
            if (custom == null) {
                plugin.getLogger().warning("OneBlock milestone loot contains unknown item id: "
                        + entry.item());
            }
            return custom;
        }
        return entry.material() != null ? new ItemStack(entry.material()) : null;
    }

    /**
     * addItem merges partial stacks: the overflow map holds only the leftovers per
     * argument index. Anything that did fit is removed again (similar stacks are
     * fungible), so the inventory returns to its pre-click state before the claim
     * is reverted — a re-claim can then never duplicate loot.
     */
    private void takeBackWhatFit(@NotNull Player player, @NotNull ItemStack[] added,
                                 @NotNull int[] intended,
                                 @NotNull Map<Integer, ItemStack> overflow) {
        List<ItemStack> takeBacks = new ArrayList<>();
        for (int i = 0; i < added.length; i++) {
            ItemStack leftover = overflow.get(i);
            int kept = leftover == null ? intended[i] : intended[i] - leftover.getAmount();
            if (kept <= 0) {
                continue;
            }
            added[i].setAmount(kept);
            takeBacks.add(added[i]);
        }
        if (!takeBacks.isEmpty()) {
            player.getInventory().removeItem(takeBacks.toArray(ItemStack[]::new));
        }
    }

    /**
     * Inventory access must happen on the player's region thread (Folia).
     *
     * <p>{@code run()} zwraca null dla wycofanej encji <i>i w tym samym
     * przypadku woła retired-callback</i>, a odrzucenie przez scheduler rzuci
     * — wszystkie trzy sygnały trafiają do {@code onRejected}, który przez to
     * musi być idempotentny (SKYBLOCK-2-3).
     */
    private void runOnPlayer(@NotNull Player player, @NotNull Runnable action,
                             @NotNull Runnable onRejected) {
        try {
            var scheduled = player.getScheduler().run(plugin, task -> action.run(), onRejected::run);
            if (scheduled == null) {
                onRejected.run();
            }
        } catch (RuntimeException rejected) {
            onRejected.run();
        }
    }
}
