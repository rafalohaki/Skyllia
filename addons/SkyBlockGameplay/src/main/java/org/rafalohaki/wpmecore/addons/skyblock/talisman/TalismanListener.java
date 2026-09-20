package org.rafalohaki.wpmecore.addons.skyblock.talisman;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonPointService;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pasywne działanie talizmanów z Kuźni SkyBlock:
 * 1. Talizman Urodzaju (skyblock:talisman/harvest_talisman) — 50% szansy na podwójny plon przy dojrzałych zbiorach.
 * 2. Talizman Górnika (skyblock:talisman/miner_talisman) — 20% szansy na podwójny drop rudy + 5% na odłamek cytrynu.
 * 3. Talizman Rybaka (skyblock:talisman/fisherman_talisman) — 25% szansy na podwójny połów + bonusowe punkty sezonowe.
 * 4. Amulet Bursztynowego Strażnika (skyblock:talisman/amber_guardian) — 15% redukcji obrażeń na wyspie + awaryjna ochrona.
 */
public final class TalismanListener implements Listener {

    public static final String HARVEST_TALISMAN_ID = "skyblock:talisman/harvest_talisman";
    public static final String MINER_TALISMAN_ID = "skyblock:talisman/miner_talisman";
    public static final String FISHERMAN_TALISMAN_ID = "skyblock:talisman/fisherman_talisman";
    public static final String AMBER_GUARDIAN_ID = "skyblock:talisman/amber_guardian";

    public static final Set<Material> CROPS = Set.of(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.NETHER_WART,
            Material.COCOA
    );

    public static final Set<Material> ORES = Set.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE,
            Material.ANCIENT_DEBRIS,
            Material.COBBLESTONE, Material.DEEPSLATE, Material.STONE
    );

    private final Plugin plugin;
    private final SkylliaIntegration skyllia;
    private final CustomItemService customItems;
    private final SeasonPointService seasonPoints;
    private final Map<UUID, Long> emergencyShieldCooldowns = new ConcurrentHashMap<>();

    public TalismanListener(@NotNull Plugin plugin,
                            @Nullable SkylliaIntegration skyllia,
                            @Nullable CustomItemService customItems,
                            @Nullable SeasonPointService seasonPoints) {
        this.plugin = plugin;
        this.skyllia = skyllia;
        this.customItems = customItems;
        this.seasonPoints = seasonPoints;
    }

    public boolean hasTalisman(@NotNull Player player, @NotNull String talismanId) {
        if (customItems == null) return false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                String id = customItems.idOf(item);
                if (talismanId.equalsIgnoreCase(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropHarvest(@NotNull BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!CROPS.contains(block.getType())) return;
        if (block.getBlockData() instanceof Ageable ageable) {
            if (ageable.getAge() < ageable.getMaximumAge()) {
                return;
            }
        }
        Player player = event.getPlayer();
        if (!hasTalisman(player, HARVEST_TALISMAN_ID)) return;

        if (ThreadLocalRandom.current().nextDouble() < 0.50) {
            Collection<ItemStack> drops = block.getDrops(player.getInventory().getItemInMainHand(), player);
            Location loc = block.getLocation().add(0.5, 0.5, 0.5);
            for (ItemStack drop : drops) {
                if (drop != null && drop.getType() != Material.AIR) {
                    block.getWorld().dropItemNaturally(loc, drop.clone());
                }
            }
            try {
                block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc, 6, 0.3, 0.3, 0.3, 0.05);
            } catch (Exception ignored) {}
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMining(@NotNull BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!ORES.contains(block.getType())) return;
        Player player = event.getPlayer();
        if (!hasTalisman(player, MINER_TALISMAN_ID)) return;

        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location loc = block.getLocation().add(0.5, 0.5, 0.5);

        // 20% szansy na podwójny drop wydobywanego materiału
        if (random.nextDouble() < 0.20) {
            Collection<ItemStack> drops = block.getDrops(player.getInventory().getItemInMainHand(), player);
            for (ItemStack drop : drops) {
                if (drop != null && drop.getType() != Material.AIR) {
                    block.getWorld().dropItemNaturally(loc, drop.clone());
                }
            }
            try {
                block.getWorld().spawnParticle(Particle.SCRAPE, loc, 5, 0.2, 0.2, 0.2, 0.02);
            } catch (Exception ignored) {}
        }

        // 5% szansy na kryształ cytrynu
        if (random.nextDouble() < 0.05 && customItems != null) {
            customItems.create("skyblock:crystal/citrine").ifPresent(crystal -> {
                block.getWorld().dropItemNaturally(loc, crystal);
                try {
                    block.getWorld().spawnParticle(Particle.WAX_OFF, loc, 6, 0.3, 0.3, 0.3, 0.05);
                } catch (Exception ignored) {}
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFishing(@NotNull PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!(event.getCaught() instanceof Item caughtItem)) return;
        Player player = event.getPlayer();
        if (!hasTalisman(player, FISHERMAN_TALISMAN_ID)) return;

        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble() < 0.25) {
            ItemStack stack = caughtItem.getItemStack();
            if (stack != null && stack.getType() != Material.AIR) {
                player.getWorld().dropItemNaturally(player.getLocation(), stack.clone());
            }
            try {
                player.getWorld().spawnParticle(Particle.SPLASH, player.getLocation().add(0, 1, 0), 12, 0.4, 0.4, 0.4, 0.1);
            } catch (Exception ignored) {}
            if (seasonPoints != null) {
                String opId = "talisman-fish:" + player.getUniqueId() + ":" + System.currentTimeMillis();
                seasonPoints.award(player.getUniqueId(), 5, opId);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDamage(@NotNull EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!hasTalisman(player, AMBER_GUARDIAN_ID)) return;

        // 15% redukcji obrażeń
        double reduced = event.getDamage() * 0.85;
        event.setDamage(reduced);

        // Awaryjna ochrona przy krytycznym zdrowiu (mniej niż 3 serca)
        if (player.getHealth() - reduced <= 6.0) {
            long now = System.currentTimeMillis();
            Long last = emergencyShieldCooldowns.get(player.getUniqueId());
            if (last == null || now - last > 60000L) {
                emergencyShieldCooldowns.put(player.getUniqueId(), now);
                player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 1));
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
                try {
                    player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 16, 0.5, 0.5, 0.5, 0.1);
                    player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 0.6f, 1.4f);
                } catch (Exception ignored) {}
            }
        }
    }
}
