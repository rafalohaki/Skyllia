package org.rafalohaki.wpmecore.addons.skyblock.automation;

import org.rafalohaki.wpmecore.addons.skyblock.shared.SkyBlockServices;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CobblestoneGeneratorListener — converts standard cobblestone and stone generation
 * into custom ore drops based on configurable weights, world height, and environment-specific
 * custom items (crystals, metals).
 */
public final class CobblestoneGeneratorListener implements Listener {

    public record OreDrop(Material material, Material deepslateMaterial, double weight, boolean isRare) {}

    public record CustomDrop(String itemId, double chance) {
        public CustomDrop {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    private final JavaPlugin plugin;
    private final SkylliaIntegration skyllia;
    private CustomItemService customItemService;
    private boolean enabled = true;
    private boolean customDropsEnabled = true;
    private final List<OreDrop> drops = new ArrayList<>();
    private double totalWeight = 0.0;

    private final List<CustomDrop> overworldCustomDrops = new ArrayList<>();
    private final List<CustomDrop> deepslateCustomDrops = new ArrayList<>();
    private final List<CustomDrop> netherCustomDrops = new ArrayList<>();

    /**
     * F8: co jest ograniczane oknem tempa. Każdy wariant ma własne okno w
     * configu i własny licznik na chunk, więc okna nie zjadają się nawzajem.
     */
    private enum Slot { ORE, RARE, CUSTOM_DROP }

    /**
     * AUTO-7: klucz cooldownu. Chunk, nie pojedynczy blok — inaczej stack
     * generatorów obok siebie mnoży podaż liniowo i cooldown niczego nie
     * ogranicza.
     */
    private record SlotKey(@NotNull Slot slot, @NotNull String world, int x, int z) {}

    /** Powyżej tylu wpisów mapa jest przycinana z wygasłych; patrz {@link #claim}. */
    private static final int COOLDOWN_MAP_PRUNE_THRESHOLD = 1024;

    /** Użyte, gdy {@code generator.ores.rare-cooldown-seconds} nie ma w configu. */
    static final long DEFAULT_RARE_COOLDOWN_SECONDS = 120L;

    /** Użyte, gdy {@code generator.ores.ore-cooldown-seconds} nie ma w configu. */
    static final long DEFAULT_ORE_COOLDOWN_SECONDS = 20L;

    /** Użyte, gdy {@code generator.custom-drops.cooldown-seconds} nie ma w configu. */
    static final long DEFAULT_CUSTOM_DROP_COOLDOWN_SECONDS = 240L;

    private long rareCooldownMillis;
    private long oreCooldownMillis;
    private long customDropCooldownMillis;
    private final Map<SlotKey, Long> cooldowns = new ConcurrentHashMap<>();

    public CobblestoneGeneratorListener(@NotNull JavaPlugin plugin, @Nullable SkylliaIntegration skyllia) {
        this(plugin, skyllia, null);
    }

    public CobblestoneGeneratorListener(@NotNull JavaPlugin plugin, @Nullable SkylliaIntegration skyllia,
                                        @Nullable CustomItemService customItemService) {
        this.plugin = plugin;
        this.skyllia = skyllia;
        this.customItemService = customItemService;
        loadConfig();
    }

    public void loadConfig() {
        drops.clear();
        totalWeight = 0.0;
        overworldCustomDrops.clear();
        deepslateCustomDrops.clear();
        netherCustomDrops.clear();

        cooldowns.clear();
        rareCooldownMillis = DEFAULT_RARE_COOLDOWN_SECONDS * 1000L;
        oreCooldownMillis = DEFAULT_ORE_COOLDOWN_SECONDS * 1000L;
        customDropCooldownMillis = DEFAULT_CUSTOM_DROP_COOLDOWN_SECONDS * 1000L;

        if (plugin == null || plugin.getConfig() == null) {
            enabled = true;
            customDropsEnabled = true;
            addDefaultDrops();
            addDefaultCustomDrops();
            return;
        }

        ConfigurationSection oresSection = plugin.getConfig().getConfigurationSection("generator.ores");
        if (oresSection == null) {
            enabled = true;
            addDefaultDrops();
        } else {
            enabled = oresSection.getBoolean("enabled", true);
            rareCooldownMillis = Math.max(0L, oresSection.getLong(
                    "rare-cooldown-seconds", DEFAULT_RARE_COOLDOWN_SECONDS)) * 1000L;
            oreCooldownMillis = Math.max(0L, oresSection.getLong(
                    "ore-cooldown-seconds", DEFAULT_ORE_COOLDOWN_SECONDS)) * 1000L;
            ConfigurationSection rates = oresSection.getConfigurationSection("rates");
            if (rates == null) {
                addDefaultDrops();
            } else {
                for (String key : rates.getKeys(false)) {
                    Material mat = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
                    if (mat == null) {
                        continue;
                    }
                    double weight = rates.getDouble(key, 0.0);
                    if (weight <= 0.0) {
                        continue;
                    }
                    Material deepslateMat = mapToDeepslate(mat);
                    boolean rare = isRareMaterial(mat);
                    drops.add(new OreDrop(mat, deepslateMat, weight, rare));
                    totalWeight += weight;
                }

                if (drops.isEmpty()) {
                    addDefaultDrops();
                }
            }
        }

        ConfigurationSection customSection = plugin.getConfig().getConfigurationSection("generator.custom-drops");
        if (customSection == null) {
            customDropsEnabled = true;
            addDefaultCustomDrops();
        } else {
            customDropsEnabled = customSection.getBoolean("enabled", true);
            customDropCooldownMillis = Math.max(0L, customSection.getLong(
                    "cooldown-seconds", DEFAULT_CUSTOM_DROP_COOLDOWN_SECONDS)) * 1000L;
            loadCustomCategory(customSection.getConfigurationSection("overworld"), overworldCustomDrops);
            loadCustomCategory(customSection.getConfigurationSection("deepslate"), deepslateCustomDrops);
            loadCustomCategory(customSection.getConfigurationSection("nether"), netherCustomDrops);
            if (overworldCustomDrops.isEmpty() && deepslateCustomDrops.isEmpty() && netherCustomDrops.isEmpty()) {
                addDefaultCustomDrops();
            }
        }
    }

    private void loadCustomCategory(@Nullable ConfigurationSection section, @NotNull List<CustomDrop> targetList) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            double chance = section.getDouble(key, 0.0);
            if (chance > 0.0) {
                targetList.add(new CustomDrop(key, chance));
            }
        }
    }

    private void addDefaultDrops() {
        addDrop(Material.COBBLESTONE, Material.COBBLED_DEEPSLATE, 55.0, false);
        addDrop(Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE, 15.0, false);
        addDrop(Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE, 10.0, false);
        addDrop(Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, 8.0, false);
        addDrop(Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, 5.0, true);
        addDrop(Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE, 3.0, false);
        addDrop(Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE, 2.0, false);
        addDrop(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE, 1.5, true);
        addDrop(Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE, 0.5, true);
    }

    private void addDefaultCustomDrops() {
        overworldCustomDrops.clear();
        deepslateCustomDrops.clear();
        netherCustomDrops.clear();
        customDropsEnabled = true;

        overworldCustomDrops.add(new CustomDrop("skyblock:crystal/citrine", 0.8));
        overworldCustomDrops.add(new CustomDrop("skyblock:metal/raw_tungsten", 0.3));

        deepslateCustomDrops.add(new CustomDrop("skyblock:crystal/onyx", 0.4));
        deepslateCustomDrops.add(new CustomDrop("skyblock:metal/raw_umber", 0.2));

        netherCustomDrops.add(new CustomDrop("skyblock:crystal/topaz", 0.5));
    }

    private void addDrop(Material mat, Material deepslateMat, double weight, boolean rare) {
        drops.add(new OreDrop(mat, deepslateMat, weight, rare));
        totalWeight += weight;
    }

    public static Material mapToDeepslate(Material mat) {
        return switch (mat) {
            case COBBLESTONE, STONE -> Material.COBBLED_DEEPSLATE;
            case COAL_ORE -> Material.DEEPSLATE_COAL_ORE;
            case COPPER_ORE -> Material.DEEPSLATE_COPPER_ORE;
            case IRON_ORE -> Material.DEEPSLATE_IRON_ORE;
            case GOLD_ORE -> Material.DEEPSLATE_GOLD_ORE;
            case REDSTONE_ORE -> Material.DEEPSLATE_REDSTONE_ORE;
            case LAPIS_ORE -> Material.DEEPSLATE_LAPIS_ORE;
            case DIAMOND_ORE -> Material.DEEPSLATE_DIAMOND_ORE;
            case EMERALD_ORE -> Material.DEEPSLATE_EMERALD_ORE;
            default -> mat;
        };
    }

    public static boolean isRareMaterial(Material mat) {
        return mat == Material.DIAMOND_ORE || mat == Material.EMERALD_ORE
                || mat == Material.GOLD_ORE || mat == Material.ANCIENT_DEBRIS;
    }

    /**
     * F8: wypełniacz generatora — to, co formuje się „za darmo” i jako jedyne
     * nie podlega oknu tempa. Bruk musi lecieć bez ograniczeń: jest podstawowym
     * materiałem budowlanym wyspy i składnikiem receptury minionka (512 sztuk),
     * a jego cena skupu (1 coin) jest kotwicą całej ekonomii SkyBlocka.
     */
    public static boolean isFillerMaterial(Material mat) {
        return mat == Material.COBBLESTONE || mat == Material.STONE;
    }

    public @Nullable CustomItemService getCustomItemService() {
        return SkyBlockServices.customItems(this.customItemService);
    }

    public void setCustomItemService(@Nullable CustomItemService customItemService) {
        this.customItemService = customItemService;
    }

    public @Nullable ItemStack createCustomItem(@NotNull String id) {
        CustomItemService service = getCustomItemService();
        if (service != null) {
            return service.create(id).orElse(null);
        }
        return null;
    }

    public @NotNull List<CustomDrop> getCustomDropsFor(@NotNull World.Environment environment, int y) {
        if (environment == World.Environment.NETHER) {
            return List.copyOf(netherCustomDrops);
        } else if (environment == World.Environment.NORMAL) {
            return (y <= 0) ? List.copyOf(deepslateCustomDrops) : List.copyOf(overworldCustomDrops);
        }
        return List.of();
    }

    public @Nullable String rollCustomDrop(@NotNull World.Environment environment, int y) {
        return rollCustomDrop(environment, y, ThreadLocalRandom.current().nextDouble(100.0));
    }

    public @Nullable String rollCustomDrop(@NotNull World.Environment environment, int y, double roll) {
        if (!customDropsEnabled) {
            return null;
        }
        List<CustomDrop> candidates = getCustomDropsFor(environment, y);
        if (candidates.isEmpty()) {
            return null;
        }
        double cumulative = 0.0;
        for (CustomDrop drop : candidates) {
            cumulative += drop.chance();
            if (roll < cumulative) {
                return drop.itemId();
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockForm(@NotNull BlockFormEvent event) {
        if (!enabled && !customDropsEnabled) {
            return;
        }
        Material formed = event.getNewState().getType();
        if (formed != Material.COBBLESTONE && formed != Material.STONE && formed != Material.BASALT) {
            return;
        }

        World world = event.getBlock().getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL && world.getEnvironment() != World.Environment.NETHER) {
            return;
        }
        if (skyllia != null && !skyllia.isSkyblockWorld(world.getName())) {
            return;
        }

        Block block = event.getBlock();
        Location centerLocation = block.getLocation().add(0.5, 0.5, 0.5);
        boolean particleSpawned = false;
        long now = System.currentTimeMillis();
        int chunkX = block.getX() >> 4;
        int chunkZ = block.getZ() >> 4;

        // 1. Standard Ore conversion (for Normal / Overworld environment)
        if (enabled && totalWeight > 0.0 && world.getEnvironment() == World.Environment.NORMAL) {
            OreDrop selected = pickRandomDrop();
            // AUTO-7 + F8: ruda tylko wtedy, gdy chunk ma wolny slot. Najpierw
            // okno na dowolną rudę — to ono trzyma tempo niezależnie od tego,
            // jak szybko ktoś kopie — a rzadka potrzebuje dodatkowo węższego
            // okna rzadkich. Przy zajętym cooldownie generator formuje zwykły
            // bruk: działa dalej, po prostu nie drukuje rud w tempie
            // autoklikera. Slot zajęty pod rudę, która potem nie przejdzie
            // okna rzadkich, przepada — celowo, bo pomyłka w tę stronę tylko
            // obniża przychód.
            if (selected != null && !isFillerMaterial(selected.material())
                    && (!claimOreSlot(world.getName(), chunkX, chunkZ, now)
                            || (selected.isRare()
                                    && !claimRareSlot(world.getName(), chunkX, chunkZ, now)))) {
                selected = null;
            }
            if (selected != null) {
                Material targetMat = (block.getY() <= 0) ? selected.deepslateMaterial() : selected.material();
                if (targetMat != formed) {
                    event.getNewState().setType(targetMat);
                    if (selected.isRare()) {
                        world.spawnParticle(Particle.HAPPY_VILLAGER, centerLocation, 6, 0.2, 0.2, 0.2, 0.0);
                        particleSpawned = true;
                    }
                }
            }
        }

        // 2. Custom environmental drop roll
        if (customDropsEnabled) {
            String customDropId = rollCustomDrop(world.getEnvironment(), block.getY());
            if (customDropId != null) {
                ItemStack customItem = createCustomItem(customDropId);
                // F8: to samo okno co przy rudach, tylko własne. Kryształ ma
                // cenę skupu, więc jest przychodem i musi mieć sufit niezależny
                // od tempa kopania. Slot zajmujemy dopiero wtedy, gdy naprawdę
                // jest co upuścić.
                if (customItem != null
                        && claimCustomDropSlot(world.getName(), chunkX, chunkZ, now)) {
                    world.dropItemNaturally(centerLocation, customItem);
                    if (!particleSpawned) {
                        world.spawnParticle(Particle.HAPPY_VILLAGER, centerLocation, 6, 0.2, 0.2, 0.2, 0.0);
                        particleSpawned = true;
                    }
                }
            }
        }
    }

    /** Sekundy cooldownu rzadkich rud; 0 = wyłączony. */
    public long getRareCooldownSeconds() {
        return rareCooldownMillis / 1000L;
    }

    /** F8: sekundy cooldownu dowolnej rudy; 0 = wyłączony. */
    public long getOreCooldownSeconds() {
        return oreCooldownMillis / 1000L;
    }

    /** F8: sekundy cooldownu dropu z generatora (kryształ, surowiec); 0 = wyłączony. */
    public long getCustomDropCooldownSeconds() {
        return customDropCooldownMillis / 1000L;
    }

    /** AUTO-7: slot rzadkiej rudy (złoto/diament/szmaragd/ancient debris). */
    boolean claimRareSlot(@NotNull String world, int chunkX, int chunkZ, long nowMillis) {
        return claim(Slot.RARE, rareCooldownMillis, world, chunkX, chunkZ, nowMillis);
    }

    /** F8: slot dowolnej rudy — nadrzędny wobec {@link #claimRareSlot}. */
    boolean claimOreSlot(@NotNull String world, int chunkX, int chunkZ, long nowMillis) {
        return claim(Slot.ORE, oreCooldownMillis, world, chunkX, chunkZ, nowMillis);
    }

    /** F8: slot dropu z {@code generator.custom-drops}. */
    boolean claimCustomDropSlot(@NotNull String world, int chunkX, int chunkZ, long nowMillis) {
        return claim(Slot.CUSTOM_DROP, customDropCooldownMillis, world, chunkX, chunkZ, nowMillis);
    }

    /**
     * AUTO-7 / F8: próbuje „zająć” slot danego rodzaju dla chunka.
     *
     * <p>Zwraca {@code true} tylko wtedy, gdy od ostatniego trafienia tego
     * rodzaju w tym chunku minęło pełne okno — i wtedy od razu zapisuje nowy
     * znacznik czasu. {@code false} znaczy „nic nie płać”: generator działa
     * dalej, po prostu formuje zwykły bruk zamiast rudy albo nie upuszcza
     * kryształu.
     *
     * <p>Chunk, a nie blok, bo ograniczane jest MIEJSCE, nie gracz: ścianę
     * generatorów można rozbijać kilkoma parami rąk jednej wyspy naraz, a
     * bank jest wspólny.
     *
     * <p>{@code nowMillis} jest parametrem, żeby test nie musiał czekać dwóch
     * minut na wygaśnięcie.
     *
     * <p>Mapa rośnie o jeden wpis na rodzaj slotu na chunk z generatorem.
     * Przycinanie jest oportunistyczne: przy przekroczeniu progu kasujemy
     * wygasłe wpisy, bo one i tak nie wpływają już na wynik.
     */
    private boolean claim(@NotNull Slot slot, long windowMillis, @NotNull String world,
                          int chunkX, int chunkZ, long nowMillis) {
        if (windowMillis <= 0L) {
            return true;
        }
        if (cooldowns.size() > COOLDOWN_MAP_PRUNE_THRESHOLD) {
            cooldowns.entrySet().removeIf(
                    entry -> nowMillis - entry.getValue() >= windowFor(entry.getKey().slot()));
        }
        SlotKey key = new SlotKey(slot, world, chunkX, chunkZ);
        // Odczyt-i-zapis bez atomowości jest tu bezpieczny: BlockFormEvent leci
        // na wątku regionu swojego chunka, a jeden chunk ma na Folii dokładnie
        // jednego właściciela — więc dla danego klucza nie ma drugiego pisarza.
        Long previous = cooldowns.get(key);
        if (previous != null && nowMillis - previous < windowMillis) {
            return false;
        }
        cooldowns.put(key, nowMillis);
        return true;
    }

    private long windowFor(@NotNull Slot slot) {
        return switch (slot) {
            case ORE -> oreCooldownMillis;
            case RARE -> rareCooldownMillis;
            case CUSTOM_DROP -> customDropCooldownMillis;
        };
    }

    public OreDrop pickRandomDrop() {
        if (drops.isEmpty() || totalWeight <= 0.0) {
            return null;
        }
        double roll = ThreadLocalRandom.current().nextDouble(totalWeight);
        double cumulative = 0.0;
        for (OreDrop drop : drops) {
            cumulative += drop.weight();
            if (roll < cumulative) {
                return drop;
            }
        }
        return drops.getFirst();
    }

    public List<OreDrop> getDrops() {
        return List.copyOf(drops);
    }

    public double getTotalWeight() {
        return totalWeight;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isCustomDropsEnabled() {
        return customDropsEnabled;
    }

    public List<CustomDrop> getOverworldCustomDrops() {
        return List.copyOf(overworldCustomDrops);
    }

    public List<CustomDrop> getDeepslateCustomDrops() {
        return List.copyOf(deepslateCustomDrops);
    }

    public List<CustomDrop> getNetherCustomDrops() {
        return List.copyOf(netherCustomDrops);
    }
}
