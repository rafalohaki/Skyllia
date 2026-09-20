package org.rafalohaki.wpmecore.addons.skyblock;

import org.rafalohaki.wpmecore.addons.skyblock.shared.SkyBlockServices;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles custom resource drops for core SkyBlock gameplay activities:
 * - Fishing: Aquamarine Crystal (1.5% chance)
 * - Mature Crop Farming: Peridot Crystal (0.8% chance)
 * - Lapis Lazuli Mining: Astral Opal Crystal (2.0% chance)
 * - Monster Slain by Player: Ruby Gem (0.5% chance) and Skeleton Key (0.2% chance)
 *
 * <p>F14: każda z tych czterech ścieżek ma dodatkowo okno tempa na chunk
 * ({@code gameplay-drops.cooldown-seconds}) — patrz {@link #claim}.
 */
public final class SkyBlockGameplayDropsListener implements Listener {

    public static final Set<Material> AGRICULTURAL_CROPS = Set.of(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.NETHER_WART,
            Material.COCOA
    );

    public static final String AQUAMARINE_ID = "skyblock:crystal/aquamarine";
    public static final String PERIDOT_ID = "skyblock:crystal/peridot";
    public static final String OPAL_ID = "skyblock:crystal/opal";
    public static final String RUBY_ID = "skyblock:crystal/ruby";
    public static final String SKELETON_KEY_ID = "skyblock:key/skeleton_key";

    public static final double DEFAULT_FISHING_AQUAMARINE_CHANCE = 1.5;
    public static final double DEFAULT_FARMING_PERIDOT_CHANCE = 0.8;
    public static final double DEFAULT_LAPIS_OPAL_CHANCE = 2.0;
    public static final double DEFAULT_MONSTER_RUBY_CHANCE = 0.5;
    public static final double DEFAULT_MONSTER_KEY_CHANCE = 0.2;

    /** Użyte, gdy {@code gameplay-drops.cooldown-seconds} nie ma w configu. */
    public static final long DEFAULT_DROP_COOLDOWN_SECONDS = 240L;

    /**
     * F14: co jest ograniczane oknem tempa. Każda kategoria ma własny licznik
     * na chunk, więc rubin nie zabiera slotu kościanemu kluczowi ani perydot
     * opalowi. Wzorzec przeniesiony 1:1 z
     * {@code CobblestoneGeneratorListener.Slot} — tam okno zatrzymało
     * mnożenie podaży ścianą generatorów, tu zatrzymuje to samo dla ściany
     * upraw i farmy mobów.
     */
    enum DropSlot { FARMING, LAPIS, FISHING, MONSTER_RUBY, MONSTER_KEY }

    /**
     * Klucz okna: chunk, a nie blok i nie gracz. Ograniczane jest MIEJSCE —
     * jedna farma obsługiwana przez sześciu członków wyspy (Skyllia
     * {@code max-members: 6}, wspólny bank) dostaje jeden slot na chunk, a nie
     * sześć.
     */
    private record SlotKey(@NotNull DropSlot slot, @NotNull String world, int x, int z) { }

    /** Powyżej tylu wpisów mapa jest przycinana z wygasłych; patrz {@link #claim}. */
    private static final int COOLDOWN_MAP_PRUNE_THRESHOLD = 1024;

    private long dropCooldownMillis = DEFAULT_DROP_COOLDOWN_SECONDS * 1000L;
    private final Map<SlotKey, Long> cooldowns = new ConcurrentHashMap<>();

    /**
     * DROP-1: rudy, których nie wolno „recyklingować” pętlą postaw-rozbij.
     * Ruda lapisu daje 2 % szansy na Opal Astralny (700 coins w sklepie), więc
     * jeden blok pod autoklikerem to było ~250 000 coins/h. Rejestr obejmuje
     * <b>wyłącznie</b> te materiały, a nie uprawy: zasadzenie pszenicy i
     * zebranie jej po dojrzewaniu to zamierzona pętla rozgrywki, a
     * {@code BlockPlaceEvent} nie odpala się przy wzroście — objęcie upraw
     * skasowałoby perydot każdemu rolnikowi na zawsze.
     */
    private static final Set<Material> PLACE_TRACKED_ORES = Set.of(
            Material.LAPIS_ORE,
            Material.DEEPSLATE_LAPIS_ORE);

    /**
     * Ograniczenie pamięci rejestru — wzorzec z
     * {@code addons/timberfast/.../manager/PlacedBlockManager} (zbiór współbieżny
     * z twardym limitem), przeniesiony tu lokalnie zamiast wyciągania do API:
     * to jedyne dwa miejsca w repo, które go potrzebują. Przepełnienie usuwa
     * najstarszy wpis (FIFO), nie cały zbiór.
     */
    static final int MAX_TRACKED_PLACED_BLOCKS = 20_000;

    private record PlacedKey(@NotNull String world, int x, int y, int z) { }

    private final Set<PlacedKey> playerPlaced = ConcurrentHashMap.newKeySet();
    private final Queue<PlacedKey> playerPlacedOrder = new ConcurrentLinkedQueue<>();

    private final JavaPlugin plugin;
    private final SkylliaIntegration skyllia;
    private CustomItemService customItemService;
    private java.util.function.Predicate<Location> oneBlockLocationFilter;

    private boolean enabled = true;
    private double fishingAquamarineChance = DEFAULT_FISHING_AQUAMARINE_CHANCE;
    private double farmingPeridotChance = DEFAULT_FARMING_PERIDOT_CHANCE;
    private double lapisOpalChance = DEFAULT_LAPIS_OPAL_CHANCE;
    private double monsterRubyChance = DEFAULT_MONSTER_RUBY_CHANCE;
    private double monsterKeyChance = DEFAULT_MONSTER_KEY_CHANCE;

    /**
     * Szczęście na kryształy z prestiżu wyspy: islandId → mnożnik szans.
     * Domyślnie 1.0 — korzeń (SkyBlockGameplay) dopina tu prestiż.
     */
    private java.util.function.Function<java.util.UUID, Double> crystalLuckResolver =
            islandId -> 1.0;

    public SkyBlockGameplayDropsListener(@NotNull JavaPlugin plugin, @Nullable SkylliaIntegration skyllia) {
        this(plugin, skyllia, null);
    }

    public SkyBlockGameplayDropsListener(@NotNull JavaPlugin plugin, @Nullable SkylliaIntegration skyllia,
                                         @Nullable CustomItemService customItemService) {
        this.plugin = plugin;
        this.skyllia = skyllia;
        this.customItemService = customItemService;
        loadConfig();
    }

    public void setOneBlockLocationFilter(@Nullable java.util.function.Predicate<Location> oneBlockLocationFilter) {
        this.oneBlockLocationFilter = oneBlockLocationFilter;
    }

    public void setCrystalLuckResolver(@NotNull java.util.function.Function<java.util.UUID, Double> resolver) {
        this.crystalLuckResolver = resolver;
    }

    /**
     * Mnożnik szans kryształowych dla akcji wykonanej w {@code location} przez
     * {@code player}: bierzemy wyspę na której się dzieje akcja (nie domową
     * gracza), więc gość na prestiżowej wyspie korzysta z jej bonusu.
     */
    private double crystalLuckAt(@Nullable Player player, @Nullable Location location) {
        if (skyllia == null || player == null || location == null) {
            return 1.0;
        }
        return skyllia.islandAt(player.getUniqueId(), location)
                .map(view -> Math.max(1.0,
                        crystalLuckResolver.apply(view.islandId())))
                .orElse(1.0);
    }

    public void loadConfig() {
        if (plugin == null || plugin.getConfig() == null) {
            this.enabled = true;
            this.fishingAquamarineChance = DEFAULT_FISHING_AQUAMARINE_CHANCE;
            this.farmingPeridotChance = DEFAULT_FARMING_PERIDOT_CHANCE;
            this.lapisOpalChance = DEFAULT_LAPIS_OPAL_CHANCE;
            this.monsterRubyChance = DEFAULT_MONSTER_RUBY_CHANCE;
            this.monsterKeyChance = DEFAULT_MONSTER_KEY_CHANCE;
            this.dropCooldownMillis = DEFAULT_DROP_COOLDOWN_SECONDS * 1000L;
            this.cooldowns.clear();
            return;
        }

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("gameplay-drops");
        if (section == null) {
            this.enabled = true;
            this.fishingAquamarineChance = DEFAULT_FISHING_AQUAMARINE_CHANCE;
            this.farmingPeridotChance = DEFAULT_FARMING_PERIDOT_CHANCE;
            this.lapisOpalChance = DEFAULT_LAPIS_OPAL_CHANCE;
            this.monsterRubyChance = DEFAULT_MONSTER_RUBY_CHANCE;
            this.monsterKeyChance = DEFAULT_MONSTER_KEY_CHANCE;
            this.dropCooldownMillis = DEFAULT_DROP_COOLDOWN_SECONDS * 1000L;
        } else {
            this.enabled = section.getBoolean("enabled", true);
            this.fishingAquamarineChance = section.getDouble("fishing.aquamarine", DEFAULT_FISHING_AQUAMARINE_CHANCE);
            this.farmingPeridotChance = section.getDouble("farming.peridot", DEFAULT_FARMING_PERIDOT_CHANCE);
            this.lapisOpalChance = section.getDouble("lapis.opal", DEFAULT_LAPIS_OPAL_CHANCE);
            this.monsterRubyChance = section.getDouble("monsters.ruby", DEFAULT_MONSTER_RUBY_CHANCE);
            this.monsterKeyChance = section.getDouble("monsters.skeleton_key", DEFAULT_MONSTER_KEY_CHANCE);
            this.dropCooldownMillis = Math.max(0L, section.getLong(
                    "cooldown-seconds", DEFAULT_DROP_COOLDOWN_SECONDS)) * 1000L;
        }
        this.cooldowns.clear();
    }

    /** F14: sekundy okna dropu z rozgrywki na chunk; 0 = wyłączone. */
    public long getDropCooldownSeconds() {
        return dropCooldownMillis / 1000L;
    }

    /**
     * F14: próbuje „zająć” slot danej kategorii dla chunka. Zwraca
     * {@code true} tylko wtedy, gdy od ostatniego dropu tej kategorii w tym
     * chunku minęło pełne okno — i wtedy od razu stawia nowy znacznik.
     *
     * <p>Po co okno, skoro szanse są niskie: bo szansa steruje WARTOŚCIĄ
     * zdarzenia, a nie ich LICZBĄ. Przychód to (zdarzeń/s) × (wartość
     * zdarzenia), a tempa nie było dotąd w configu w ogóle — dokładnie ten sam
     * błąd, który F8 naprawiła w generatorze. Przy tempie odniesienia (jedna
     * akcja na sekundę) okno nie wiąże; wiąże pod autoklikerem.
     *
     * <p>{@code nowMillis} jest parametrem, żeby test nie musiał czekać
     * czterech minut na wygaśnięcie.
     */
    boolean claim(@NotNull DropSlot slot, @NotNull String world,
                  int chunkX, int chunkZ, long nowMillis) {
        if (dropCooldownMillis <= 0L) {
            return true;
        }
        if (cooldowns.size() > COOLDOWN_MAP_PRUNE_THRESHOLD) {
            cooldowns.entrySet().removeIf(
                    entry -> nowMillis - entry.getValue() >= dropCooldownMillis);
        }
        SlotKey key = new SlotKey(slot, world, chunkX, chunkZ);
        // Odczyt-i-zapis bez atomowości jest tu bezpieczny: wszystkie cztery
        // zdarzenia lecą na wątku regionu swojego chunka, a jeden chunk ma na
        // Folii dokładnie jednego właściciela — dla danego klucza nie ma
        // drugiego pisarza.
        Long previous = cooldowns.get(key);
        if (previous != null && nowMillis - previous < dropCooldownMillis) {
            return false;
        }
        cooldowns.put(key, nowMillis);
        return true;
    }

    private boolean claimAt(@NotNull DropSlot slot, @NotNull Location location) {
        World world = location.getWorld();
        if (world == null) {
            // Bez świata nie ma chunka do policzenia. Fail-open: drop leci,
            // bo zabranie nagrody z powodu braku metadanych byłoby gorsze niż
            // jedno niepoliczone zdarzenie.
            return true;
        }
        return claim(slot, world.getName(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4,
                System.currentTimeMillis());
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

    public boolean rollFishingDrop() {
        return rollFishingDrop(ThreadLocalRandom.current().nextDouble(100.0));
    }

    public boolean rollFishingDrop(double roll) {
        return rollFishingDrop(roll, 1.0);
    }

    /** Wariant ze szczęściem: {@code luck} mnoży skonfigurowane szanse. */
    public boolean rollFishingDrop(double roll, double luck) {
        return enabled && roll < fishingAquamarineChance * Math.max(1.0, luck);
    }

    public boolean rollFarmingDrop() {
        return rollFarmingDrop(ThreadLocalRandom.current().nextDouble(100.0));
    }

    public boolean rollFarmingDrop(double roll) {
        return rollFarmingDrop(roll, 1.0);
    }

    public boolean rollFarmingDrop(double roll, double luck) {
        return enabled && roll < farmingPeridotChance * Math.max(1.0, luck);
    }

    public boolean rollLapisDrop() {
        return rollLapisDrop(ThreadLocalRandom.current().nextDouble(100.0));
    }

    public boolean rollLapisDrop(double roll) {
        return rollLapisDrop(roll, 1.0);
    }

    public boolean rollLapisDrop(double roll, double luck) {
        return enabled && roll < lapisOpalChance * Math.max(1.0, luck);
    }

    public boolean rollMonsterRubyDrop() {
        return rollMonsterRubyDrop(ThreadLocalRandom.current().nextDouble(100.0));
    }

    public boolean rollMonsterRubyDrop(double roll) {
        return rollMonsterRubyDrop(roll, 1.0);
    }

    public boolean rollMonsterRubyDrop(double roll, double luck) {
        return enabled && roll < monsterRubyChance * Math.max(1.0, luck);
    }

    public boolean rollMonsterKeyDrop() {
        return rollMonsterKeyDrop(ThreadLocalRandom.current().nextDouble(100.0));
    }

    public boolean rollMonsterKeyDrop(double roll) {
        return rollMonsterKeyDrop(roll, 1.0);
    }

    public boolean rollMonsterKeyDrop(double roll, double luck) {
        return enabled && roll < monsterKeyChance * Math.max(1.0, luck);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerFish(@NotNull PlayerFishEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        World world = player.getWorld();
        if (world == null) {
            return;
        }
        if (skyllia != null && !skyllia.isSkyblockWorld(world.getName())) {
            return;
        }

        if (rollFishingDrop(ThreadLocalRandom.current().nextDouble(100.0),
                crystalLuckAt(player, player.getLocation()))) {
            ItemStack customItem = createCustomItem(AQUAMARINE_ID);
            if (customItem != null) {
                Location dropLoc = null;
                Entity caught = event.getCaught();
                if (caught != null) {
                    dropLoc = caught.getLocation();
                } else if (event.getHook() != null) {
                    dropLoc = event.getHook().getLocation();
                } else {
                    dropLoc = player.getLocation();
                }
                // F14: slot zajmujemy dopiero wtedy, gdy naprawdę jest co
                // upuścić — tak samo jak przy krysztale z generatora.
                if (dropLoc != null && claimAt(DropSlot.FISHING, dropLoc)) {
                    world.dropItemNaturally(dropLoc, customItem);
                    world.spawnParticle(Particle.HAPPY_VILLAGER, dropLoc, 6, 0.2, 0.2, 0.2, 0.0);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        if (!enabled) {
            return;
        }

        Block block = event.getBlock();
        if (block == null) {
            return;
        }
        World world = block.getWorld();
        if (world == null) {
            return;
        }
        if (skyllia != null && !skyllia.isSkyblockWorld(world.getName())) {
            return;
        }
        if (oneBlockLocationFilter != null && oneBlockLocationFilter.test(block.getLocation())) {
            return;
        }

        // 1. Mature Ageable crop farming check (wheat, carrots, potatoes, beetroots, cocoa, nether wart)
        if (AGRICULTURAL_CROPS.contains(block.getType()) && block.getBlockData() instanceof Ageable ageable) {
            if (ageable.getAge() == ageable.getMaximumAge()) {
                if (rollFarmingDrop(ThreadLocalRandom.current().nextDouble(100.0),
                        crystalLuckAt(event.getPlayer(), block.getLocation()))) {
                    ItemStack customItem = createCustomItem(PERIDOT_ID);
                    if (customItem != null) {
                        Location loc = block.getLocation();
                        Location centerLocation = (loc != null) ? loc.clone().add(0.5, 0.5, 0.5) : new Location(world, 0.5, 0.5, 0.5);
                        if (claimAt(DropSlot.FARMING, centerLocation)) {
                            world.dropItemNaturally(centerLocation, customItem);
                            world.spawnParticle(Particle.HAPPY_VILLAGER, centerLocation, 6, 0.2, 0.2, 0.2, 0.0);
                        }
                    }
                }
            }
        }

        // 2. Lapis Lazuli Ore mining check
        Material material = block.getType();
        if (material == Material.LAPIS_ORE || material == Material.DEEPSLATE_LAPIS_ORE) {
            // DROP-1: ruda postawiona przez gracza nie losuje nic. Wpis znika
            // przy rozbiciu, więc ponowne postawienie znowu ją oznacza.
            if (forgetPlayerPlaced(block)) {
                return;
            }
            if (rollLapisDrop(ThreadLocalRandom.current().nextDouble(100.0),
                    crystalLuckAt(event.getPlayer(), block.getLocation()))) {
                ItemStack customItem = createCustomItem(OPAL_ID);
                if (customItem != null) {
                    Location loc = block.getLocation();
                    Location centerLocation = (loc != null) ? loc.clone().add(0.5, 0.5, 0.5) : new Location(world, 0.5, 0.5, 0.5);
                    if (claimAt(DropSlot.LAPIS, centerLocation)) {
                        world.dropItemNaturally(centerLocation, customItem);
                        world.spawnParticle(Particle.HAPPY_VILLAGER, centerLocation, 6, 0.2, 0.2, 0.2, 0.0);
                    }
                }
            }
        }
    }

    /**
     * DROP-1: zapamiętuje rudę postawioną przez gracza. MONITOR, bo interesuje
     * nas wyłącznie postawienie, które faktycznie doszło do skutku.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        if (!enabled) {
            return;
        }
        Block block = event.getBlockPlaced();
        if (block == null || !PLACE_TRACKED_ORES.contains(block.getType())) {
            return;
        }
        World world = block.getWorld();
        if (world == null || (skyllia != null && !skyllia.isSkyblockWorld(world.getName()))) {
            return;
        }
        rememberPlayerPlaced(block);
    }

    private void rememberPlayerPlaced(@NotNull Block block) {
        PlacedKey key = new PlacedKey(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (!playerPlaced.add(key)) {
            return;
        }
        playerPlacedOrder.add(key);
        while (playerPlaced.size() > MAX_TRACKED_PLACED_BLOCKS) {
            PlacedKey oldest = playerPlacedOrder.poll();
            if (oldest == null) {
                return;
            }
            playerPlaced.remove(oldest);
        }
    }

    /** @return {@code true}, gdy blok był postawiony przez gracza (wpis usunięty) */
    private boolean forgetPlayerPlaced(@NotNull Block block) {
        PlacedKey key = new PlacedKey(block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ());
        if (!playerPlaced.remove(key)) {
            return false;
        }
        playerPlacedOrder.remove(key);
        return true;
    }

    /** Widoczne w pakiecie dla testu: ile bloków trzyma dziś rejestr. */
    int trackedPlacedBlocks() {
        return playerPlaced.size();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDeath(@NotNull EntityDeathEvent event) {
        if (!enabled) {
            return;
        }

        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Monster)) {
            return;
        }
        if (entity.getKiller() == null) {
            return;
        }

        World world = entity.getWorld();
        if (world == null) {
            return;
        }
        if (skyllia != null && !skyllia.isSkyblockWorld(world.getName())) {
            return;
        }

        Location dropLocation = entity.getLocation();
        if (dropLocation == null) {
            return;
        }

        // 1. Ruby Gem drop roll (0.5% default)
        double luck = crystalLuckAt(entity.getKiller(), dropLocation);
        if (rollMonsterRubyDrop(ThreadLocalRandom.current().nextDouble(100.0), luck)) {
            ItemStack ruby = createCustomItem(RUBY_ID);
            if (ruby != null && claimAt(DropSlot.MONSTER_RUBY, dropLocation)) {
                world.dropItemNaturally(dropLocation, ruby);
                world.spawnParticle(Particle.HAPPY_VILLAGER, dropLocation, 6, 0.2, 0.2, 0.2, 0.0);
            }
        }

        // 2. Skeleton Key drop roll (0.2% default)
        if (rollMonsterKeyDrop(ThreadLocalRandom.current().nextDouble(100.0), luck)) {
            ItemStack key = createCustomItem(SKELETON_KEY_ID);
            if (key != null && claimAt(DropSlot.MONSTER_KEY, dropLocation)) {
                world.dropItemNaturally(dropLocation, key);
                world.spawnParticle(Particle.HAPPY_VILLAGER, dropLocation, 6, 0.2, 0.2, 0.2, 0.0);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getFishingAquamarineChance() {
        return fishingAquamarineChance;
    }

    public double getFarmingPeridotChance() {
        return farmingPeridotChance;
    }

    public double getLapisOpalChance() {
        return lapisOpalChance;
    }

    public double getMonsterRubyChance() {
        return monsterRubyChance;
    }

    public double getMonsterKeyChance() {
        return monsterKeyChance;
    }
}
