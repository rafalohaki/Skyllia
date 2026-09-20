package org.rafalohaki.wpmecore.addons.skyblock.minions;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.Optional;
import java.util.UUID;

/**
 * Stawianie minionków z przedmiotu, otwieranie GUI przez klik w Interaction
 * oraz re-render bytów na ChunkLoadEvent (spec §2.2, §7).
 */
public final class MinionListener implements Listener {

    private final org.bukkit.plugin.Plugin plugin;
    private final MinionService service;
    private final MinionDisplayRenderer renderer;
    private final MinionMenu menu;
    private final MinionsConfig config;
    private final MiniMessage miniMessage;
    private final SkylliaIntegration skyllia;

    /**
     * Bonusowe sloty z prestiżu wyspy (IslandPrestigeService.minionSlotsFor).
     * Domyślnie 0 — moduł nie zależy od prestiżu, dopięcie robi korzeń.
     */
    private java.util.function.ToIntFunction<UUID> islandExtraSlots = islandId -> 0;

    public MinionListener(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull MinionService service,
                          @NotNull MinionDisplayRenderer renderer, @NotNull MinionMenu menu,
                          @NotNull MinionsConfig config, @NotNull MiniMessage miniMessage,
                          @Nullable SkylliaIntegration skyllia) {
        this.plugin = plugin;
        this.service = service;
        this.renderer = renderer;
        this.menu = menu;
        this.config = config;
        this.miniMessage = miniMessage;
        this.skyllia = skyllia;
    }

    public void setIslandExtraSlots(@NotNull java.util.function.ToIntFunction<UUID> islandExtraSlots) {
        this.islandExtraSlots = islandExtraSlots;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMinionItemPlace(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!MinionItem.isMinionItem(item)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Block target = clicked.getRelative(event.getBlockFace());
        Material targetType = target.getType();
        boolean free = targetType == Material.AIR
                || targetType == Material.CAVE_AIR
                || targetType == Material.VOID_AIR;
        if (!free) {
            player.sendMessage(miniMessage.deserialize(
                    "<red>Minionek potrzebuje wolnego miejsca (powietrza).</red>"));
            return;
        }
        String typeId = MinionItem.typeId(item);
        MinionsConfig.TypeDef type = typeId == null ? null : config.type(typeId);
        if (type == null) {
            player.sendMessage(miniMessage.deserialize("<red>Nieznany typ minionka.</red>"));
            return;
        }
        Location base = target.getLocation();
        UUID islandId = player.getUniqueId();
        if (skyllia != null) {
            Optional<IslandView> view = skyllia.islandAt(player.getUniqueId(), base);
            if (view.isEmpty()) {
                player.sendMessage(miniMessage.deserialize(
                        "<red>Minionki można stawiać tylko na własnej wyspie.</red>"));
                return;
            }
            islandId = view.get().islandId();
        }
        // świata szukamy przez Block.getWorld() — Location.getWorld() waliduje
        // rejestrację świata na serwerze ("World unloaded" bez serwera)
        Location center = new Location(target.getWorld(),
                base.getBlockX() + 0.5, base.getBlockY(), base.getBlockZ() + 0.5);
        int tier = MinionItem.tier(item);
        /*
         * MINION-5: przedmiot znika z ręki SYNCHRONICZNIE, jeszcze przed
         * round-tripem SQL w tryPlace. Zdejmowanie go dopiero w callbacku
         * pozwalało przełączyć slot hotbara (albo się rozłączyć, bo callback
         * „retired” był null) i zostawić minionka w ekwipunku mimo rekordu
         * w bazie. Porównujemy typ i tier, a nie samo isMinionItem — inaczej
         * dawało się zjeść droższego minionka trzymanego w drugim slocie.
         */
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!MinionItem.isMinionItem(hand)
                || !typeId.equals(MinionItem.typeId(hand))
                || MinionItem.tier(hand) != tier) {
            player.sendMessage(miniMessage.deserialize(
                    "<red>Minionek zniknął z ręki — spróbuj jeszcze raz.</red>"));
            return;
        }
        MinionRecord record = new MinionRecord(
                UUID.randomUUID(), islandId, typeId, tier,
                target.getWorld().getName(), center.getX(), center.getY(), center.getZ(),
                MinionRecord.decodeStorage(MinionItem.storageEncoded(item)),
                MinionItem.fuelType(item), MinionItem.fuelExpiresAt(item),
                MinionItem.compactorEnabled(item), null, null, null, 0L,
                System.currentTimeMillis(), System.currentTimeMillis());
        ItemStack consumed = hand.clone();
        consumed.setAmount(1);
        if (hand.getAmount() > 1) {
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().setItemInMainHand(hand);
        } else {
            player.getInventory().setItemInMainHand(null);
        }
        int extraSlots = org.rafalohaki.wpmecore.addons.skyblock.perks.RankPerks.minionExtraSlots(player)
                + Math.max(0, islandExtraSlots.applyAsInt(islandId));
        service.tryPlace(record, extraSlots).thenAccept(placed ->
                player.getScheduler().run(plugin, task -> {
                    if (!placed) {
                        player.sendMessage(miniMessage.deserialize(
                                "<red>Osiągnięto limit minionków na wyspie ("
                                        + (config.settings().maxMinionsPerIsland() + extraSlots) + ").</red>"));
                        // Odmowa = zwrot; nadmiar spada pod nogi, bo ekwipunek
                        // mógł się zapełnić w czasie round-tripu.
                        player.getInventory().addItem(consumed).values().forEach(rest ->
                                player.getWorld().dropItemNaturally(player.getLocation(), rest));
                        return;
                    }
                    player.sendMessage(miniMessage.deserialize(
                            "<green>Postawiono minionka: </green>" + type.name()));
                    plugin.getServer().getRegionScheduler().run(plugin, center,
                            renderTask -> renderer.render(record));
                }, () -> {
                    if (!placed) {
                        plugin.getLogger().warning("Minionek " + typeId + " (tier " + tier
                                + ") nie wrócił do ekwipunku gracza " + player.getUniqueId()
                                + " — odmowa postawienia zbiegła się z wyjściem z serwera");
                    }
                }));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMinionInteract(PlayerInteractAtEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction interaction)) {
            return;
        }
        String tagged = interaction.getPersistentDataContainer()
                .get(MinionDisplayRenderer.KEY_ENTITY_MINION, PersistentDataType.STRING);
        if (tagged == null) {
            return;
        }
        event.setCancelled(true);
        UUID minionId;
        try {
            minionId = UUID.fromString(tagged);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        Player player = event.getPlayer();
        MinionRecord record = service.snapshot(minionId).orElse(null);
        if (record == null) {
            return;
        }
        if (skyllia != null) {
            Optional<IslandView> view = skyllia.islandOf(player.getUniqueId());
            if (view.isEmpty() || !view.get().islandId().equals(record.islandId())) {
                player.sendMessage(miniMessage.deserialize(
                        "<red>To nie jest minionek Twojej wyspy.</red>"));
                return;
            }
        }
        player.getScheduler().run(plugin, task -> menu.open(player, minionId), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        for (MinionRecord record : service.minionsInChunk(
                chunk.getWorld().getName(), chunk.getX(), chunk.getZ())) {
            Location location = new Location(chunk.getWorld(),
                    record.x(), record.y(), record.z());
            plugin.getServer().getRegionScheduler().run(plugin, location,
                    task -> renderer.render(record));
        }
    }
}
