package org.rafalohaki.wpmecore.addons.skyblock;

import org.rafalohaki.wpmecore.addons.skyblock.hub.HubBlock;
import org.rafalohaki.wpmecore.addons.skyblock.hub.HubSettings;
import org.rafalohaki.wpmecore.addons.skyblock.hub.JumpPadDefinition;
import org.rafalohaki.wpmecore.addons.skyblock.hub.SpawnPoint;
import org.rafalohaki.wpmecore.addons.skyblock.hub.WandDefinition;
import org.rafalohaki.wpmecore.addons.skyblock.season.LeaderboardBoard;
import org.rafalohaki.wpmecore.addons.skyblock.season.LeaderboardSettings;
import org.rafalohaki.wpmecore.addons.skyblock.season.PedestalSettings;

import org.rafalohaki.wpmecore.addons.skyblock.island.IslandModeFlags;

import org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockTopRewardCoordinator;

import org.rafalohaki.wpmecore.addons.skyblock.quests.QuestCatalog;

import org.rafalohaki.wpmecore.addons.skyblock.shop.ShopCatalog;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record SkyBlockSettings(long startingBalance,
                        @NotNull String menuTitle,
                        @NotNull List<ExternalAction> externalActions,
                        @NotNull HubSettings hub,
                        int gameplayRetentionDays,
                        @NotNull ShopCatalog shop,
                        @NotNull QuestCatalog quests,
                        @NotNull WandDefinition sellWand,
                        @NotNull WandDefinition harvestWand,
                        int sellWandPercent,
                        @NotNull List<Long> bankAmounts,
                        @NotNull AutomationSettings automation,
                        @NotNull LeaderboardSettings leaderboards,
                        @NotNull SkyBlockTopRewardCoordinator.SeasonEligibility seasons,
                        @NotNull IslandModeFlags islandModes,
                        @NotNull DailyRewardSettings dailyReward) {

    private static final Pattern COMMAND = Pattern.compile(
            "[A-Za-z0-9:_-]+(?: [A-Za-z0-9:_-]+)*");
    private static final Pattern PLUGIN_NAME = Pattern.compile("[A-Za-z0-9_.-]{0,64}");
    private static final Pattern WORLD_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");
    private static final Pattern HUB_ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Set<Integer> RESERVED_MENU_SLOTS = Set.of(4, 11, 13, 15, 22, 31, 33, 40);

    public SkyBlockSettings {
        externalActions = List.copyOf(externalActions);
        bankAmounts = List.copyOf(bankAmounts);
    }

    static @NotNull SkyBlockSettings load(@NotNull FileConfiguration config) {
        long starting = config.getLong("economy.starting-balance", 100L);
        if (starting < 0L || starting > 1_000_000L) {
            throw new IllegalArgumentException("economy.starting-balance outside 0..1000000");
        }
        // SKYBLOCK-2-8: opcja island.reset-cooldown-hours usunięta — walidacja
        // istniała, ale żaden kod nie czytał wartości (martwa funkcja, fałszywe
        // poczucie zamknięcia wektora nadużyć). Przywrócić razem z logiką resetu.
        ConfigurationSection menu = required(config, "menu");
        ConfigurationSection actions = required(menu, "external-actions");
        List<ExternalAction> externalActions = new ArrayList<>();
        Set<Integer> slots = new HashSet<>();
        for (String id : actions.getKeys(false)) {
            ConfigurationSection action = required(actions, id);
            int slot = range(action.getInt("slot"), 0, 44, "menu action slot");
            if (!slots.add(slot) || RESERVED_MENU_SLOTS.contains(slot)) {
                throw new IllegalArgumentException("Duplicate menu action slot " + slot);
            }
            String command = action.getString("command", "");
            if (!COMMAND.matcher(command).matches()) {
                throw new IllegalArgumentException("Unsafe command for menu action " + id);
            }
            String requiredPlugin = action.getString("required-plugin", "").trim();
            if (!PLUGIN_NAME.matcher(requiredPlugin).matches()) {
                throw new IllegalArgumentException("Unsafe required-plugin for menu action " + id);
            }
            externalActions.add(new ExternalAction(id, slot,
                    material(action.getString("material"), "menu action " + id),
                    action.getString("name", id), action.getStringList("lore"), command,
                    requiredPlugin));
        }
        HubSettings hub = hub(required(config, "hub"));
        ShopCatalog shop = ShopCatalog.load(required(config, "shop"));
        QuestCatalog quests = QuestCatalog.load(required(config, "quests"));
        int retentionDays = range(config.getInt("storage.gameplay-retention-days", 35),
                7, 365, "storage.gameplay-retention-days");
        ConfigurationSection wands = required(config, "wands");
        WandDefinition sell = wand(required(wands, "sell"), "sell");
        WandDefinition harvest = wand(required(wands, "harvest"), "harvest");
        int payout = range(wands.getInt("sell.payout-percent", 90), 1, 100,
                "wands.sell.payout-percent");
        List<Long> bankAmounts = config.getLongList("bank.amounts");
        if (bankAmounts.isEmpty() || bankAmounts.size() > 4
                || bankAmounts.stream().anyMatch(value -> value <= 0L || value > 1_000_000_000L)) {
            throw new IllegalArgumentException("bank.amounts requires 1..4 positive safe amounts");
        }
        ConfigurationSection automation = config.getConfigurationSection("automation");
        long chunkerPrice = automation != null ? automation.getLong("chunker.price", 15_000L) : 15_000L;
        long sellChestPrice = automation != null ? automation.getLong("sell-chest.price", 25_000L) : 25_000L;
        if (chunkerPrice <= 0L || chunkerPrice > 1_000_000_000L) {
            throw new IllegalArgumentException("automation.chunker.price outside 1..1000000000");
        }
        if (sellChestPrice <= 0L || sellChestPrice > 1_000_000_000L) {
            throw new IllegalArgumentException("automation.sell-chest.price outside 1..1000000000");
        }
        AutomationSettings automationSettings = new AutomationSettings(chunkerPrice, sellChestPrice);
        IslandModeFlags islandModes = new IslandModeFlags(config);
        return new SkyBlockSettings(starting, menu.getString("title", "<dark_gray>SkyBlock"),
                externalActions, hub, retentionDays, shop, quests, sell, harvest, payout, bankAmounts,
                automationSettings, leaderboards(config.getConfigurationSection("leaderboards")),
                seasons(config.getConfigurationSection("seasons")), islandModes,
                dailyReward(config.getConfigurationSection("daily-reward")));
    }

    /**
     * Codzienna nagroda i seria logowań (sekcja {@code daily-reward}).
     * Kwota = baseCoins + perStreakDay × min(seria, streakCap); co 7. dzień
     * serii dodatkowo {@code lotusItem} przez outbox ekwipunku.
     *
     * <p>Brak sekcji znaczy <b>wyłączone</b> — ta sama asymetria co w
     * {@link #seasons}: nie wypłacamy monet z domyślnych, których nikt nie zapisał.
     */
    public record DailyRewardSettings(boolean enabled, long baseCoins, long perStreakDay,
                                      int streakCap, @NotNull String lotusItem) {
        public static final DailyRewardSettings DISABLED =
                new DailyRewardSettings(false, 250L, 50L, 7, "skyblock:token/silver_lotus");
    }

    private static @NotNull DailyRewardSettings dailyReward(@Nullable ConfigurationSection section) {
        if (section == null) {
            return DailyRewardSettings.DISABLED;
        }
        long base = section.getLong("base-coins", 250L);
        if (base < 0L || base > 1_000_000L) {
            throw new IllegalArgumentException("daily-reward.base-coins outside 0..1000000");
        }
        long perDay = section.getLong("per-streak-day", 50L);
        if (perDay < 0L || perDay > 1_000_000L) {
            throw new IllegalArgumentException("daily-reward.per-streak-day outside 0..1000000");
        }
        int cap = range(section.getInt("streak-cap", 7), 1, 365, "daily-reward.streak-cap");
        String item = section.getString("custom-item", "skyblock:token/silver_lotus");
        if (item == null || !item.matches("[a-z0-9_]+:[a-z0-9_/.-]{1,64}")) {
            throw new IllegalArgumentException("daily-reward.custom-item is not a custom item id: " + item);
        }
        return new DailyRewardSettings(section.getBoolean("enabled", true), base, perDay, cap, item);
    }

    /**
     * Warunki odbioru nagrody sezonowej.
     *
     * <p>Brak sekcji znaczy <b>zamknięte</b>. Ta asymetria jest celowa: pomyłka
     * w stronę „otwarte" wydaje limitowaną kosmetykę całego sezonu i nie da się
     * jej cofnąć, pomyłka w stronę „zamknięte" to jedna linijka w konfiguracji.
     */
    private static @NotNull SkyBlockTopRewardCoordinator.SeasonEligibility seasons(
            @Nullable ConfigurationSection section) {
        if (section == null) {
            return SkyBlockTopRewardCoordinator.SeasonEligibility.CLOSED;
        }
        long minScore = section.getLong("min-score", 1L);
        if (minScore < 1L) {
            throw new IllegalArgumentException(
                    "seasons.min-score musi być dodatnie — zerowy dorobek nie jest wynikiem");
        }
        long thresholdScore = section.getLong("threshold-score", 25_000L);
        if (thresholdScore < 0L) {
            throw new IllegalArgumentException("seasons.threshold-score nie może być ujemne (0 = próg wyłączony)");
        }
        return new SkyBlockTopRewardCoordinator.SeasonEligibility(
                section.getBoolean("rewards-claimable", false),
                minScore,
                range(section.getInt("min-ranked-islands", 5), 1, 10_000,
                        "seasons.min-ranked-islands"),
                thresholdScore);
    }

    /**
     * Nagroda za miejsce w TOP 3 rankingu sezonu
     * (sekcja {@code season.top-rewards} w config.yml).
     *
     * @param rank  miejsce w rankingu — dodatnie, unikalne w ramach listy
     * @param lotus liczba Diamentowych Lotosów (skyblock:token/diamond_lotus), &gt;= 0
     * @param coins monety, &gt;= 0
     */
    public record TopReward(int rank, int lotus, long coins) {
    }

    /**
     * Wbudowane domyślne progi — dokładnie te liczby, które działały zanim
     * nagrody trafiły do config.yml (byte-compat dla braku sekcji).
     */
    public static final List<TopReward> DEFAULT_TOP_REWARDS = List.of(
            new TopReward(1, 3, 50_000L),
            new TopReward(2, 2, 30_000L),
            new TopReward(3, 1, 15_000L));

    /**
     * Czyta {@code season.top-rewards} z config.yml. Fail-closed: brak sekcji
     * albo pusta lista → dokładnie trzy domyślne progi jak dotychczas.
     * Błędny wpis (rank niedodatni albo niecałkowity, zduplikowany rank,
     * ujemne lotus/coins) → {@link IllegalArgumentException} o komunikacie
     * z prefiksem „season.top-rewards” — przy starcie kończy się to SEVERE
     * i wyłączeniem pluginu, nigdy cichym fallbackiem. Obce klucze w wpisach
     * są ignorowane; kolejność wpisów dowolna.
     */
    public static @NotNull List<TopReward> loadTopRewards(@Nullable ConfigurationSection seasonSection) {
        if (seasonSection == null) {
            return DEFAULT_TOP_REWARDS;
        }
        List<Map<?, ?>> raw = seasonSection.getMapList("top-rewards");
        if (raw.isEmpty()) {
            return DEFAULT_TOP_REWARDS;
        }
        Set<Integer> seenRanks = new HashSet<>();
        List<TopReward> rewards = new ArrayList<>(raw.size());
        int entryIndex = 0;
        for (Map<?, ?> entry : raw) {
            entryIndex++;
            int rank = (int) requireWholeNumber(entry.get("rank"), entryIndex, "rank", 1);
            int lotus = (int) requireWholeNumber(entry.get("lotus"), entryIndex, "lotus", 0);
            long coins = requireWholeNumber(entry.get("coins"), entryIndex, "coins", 0);
            if (!seenRanks.add(rank)) {
                throw new IllegalArgumentException("season.top-rewards wpis #" + entryIndex
                        + ": zduplikowany rank " + rank);
            }
            rewards.add(new TopReward(rank, lotus, coins));
        }
        return List.copyOf(rewards);
    }

    /** Maksymalna długość tytułu = szerokość kolumny {@code wpme_sb_island_titles.title}. */
    static final int MAX_TITLE_LENGTH = 64;

    /**
     * Czyta {@code season.title-rewards} z config.yml: mapa miejsce w rankingu →
     * tytuł wyspy w MiniMessage (np. {@code 50: '<gray>Wyspiarz'}). Tytuł jest
     * nagrodą <b>kosmetyczną</b> i nie daje żadnych bonusów.
     *
     * <p>Fail-closed i cichy: brak sekcji albo pusta mapa znaczą „żadne miejsce
     * nie daje tytułu” — dokładnie tak, jak działa dzisiaj. Błędny wpis (klucz
     * nienumeryczny, miejsce poniżej 1, pusty tytuł albo dłuższy niż
     * {@value #MAX_TITLE_LENGTH} znaków) → {@link IllegalArgumentException}
     * z prefiksem „season.title-rewards”: przy starcie to SEVERE i wyłączenie
     * pluginu, nigdy ciche zjedzenie nagrody.
     */
    public static @NotNull Map<Integer, String> loadTitleRewards(
            @Nullable ConfigurationSection seasonSection) {
        if (seasonSection == null) {
            return Map.of();
        }
        ConfigurationSection section = seasonSection.getConfigurationSection("title-rewards");
        if (section == null) {
            return Map.of();
        }
        Map<Integer, String> titles = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            int rank;
            try {
                rank = Integer.parseInt(key.trim());
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException("season.title-rewards." + key
                        + ": kluczem musi być miejsce w rankingu (liczba całkowita)");
            }
            if (rank < 1) {
                throw new IllegalArgumentException("season.title-rewards." + key
                        + ": miejsce musi być >= 1");
            }
            String title = section.getString(key);
            if (title == null || title.isBlank() || title.length() > MAX_TITLE_LENGTH) {
                throw new IllegalArgumentException("season.title-rewards." + key
                        + ": tytuł jest wymagany i nie może przekraczać "
                        + MAX_TITLE_LENGTH + " znaków");
            }
            titles.put(rank, title);
        }
        return Map.copyOf(titles);
    }

    private static long requireWholeNumber(Object value, int entryIndex, String field, long minimum) {
        if (!(value instanceof Number number)
                || (number instanceof Float || number instanceof Double)
                        && number.doubleValue() != number.longValue()) {
            throw new IllegalArgumentException("season.top-rewards wpis #" + entryIndex
                    + ": " + field + " musi być liczbą całkowitą");
        }
        long parsed = number.longValue();
        if (parsed < minimum) {
            throw new IllegalArgumentException("season.top-rewards wpis #" + entryIndex
                    + ": " + field + " musi być >= " + minimum);
        }
        return parsed;
    }

    private static HubSettings hub(ConfigurationSection section) {
        ConfigurationSection spawnSection = required(section, "spawn");
        SpawnPoint spawn = new SpawnPoint(
                world(spawnSection.getString("world", "world"), "hub.spawn.world"),
                coordinate(spawnSection.getDouble("x"), "hub.spawn.x"),
                coordinate(spawnSection.getDouble("y"), "hub.spawn.y"),
                coordinate(spawnSection.getDouble("z"), "hub.spawn.z"),
                angle(spawnSection.getDouble("yaw"), -360.0D, 360.0D,
                        "hub.spawn.yaw"),
                angle(spawnSection.getDouble("pitch"), -90.0D, 90.0D,
                        "hub.spawn.pitch"));

        ConfigurationSection onboarding = required(section, "onboarding");
        int onboardingDelay = range(onboarding.getInt("delay-ticks", 40),
                1, 200, "hub.onboarding.delay-ticks");
        boolean openMenu = onboarding.getBoolean("open-menu-on-first-join", false);
        boolean openHelpDialog = onboarding.getBoolean(
                "open-help-dialog-on-first-join", true);

        List<JumpPadDefinition> jumpPads = new ArrayList<>();
        Set<HubBlock> occupiedBlocks = new HashSet<>();
        ConfigurationSection pads = required(section, "jump-pads");
        for (String id : pads.getKeys(false)) {
            validateHubId(id, "jump pad");
            ConfigurationSection definition = required(pads, id);
            String padWorld = world(definition.getString("world", spawn.world()),
                    "hub.jump-pads." + id + ".world");
            Material plate = material(definition.getString("plate-material"),
                    "hub jump pad " + id);
            if (!plate.name().endsWith("_PRESSURE_PLATE")) {
                throw new IllegalArgumentException(
                        "hub jump pad " + id + " must use a pressure plate");
            }
            List<HubBlock> blocks = new ArrayList<>();
            for (Map<?, ?> block : definition.getMapList("blocks")) {
                HubBlock position = new HubBlock(padWorld,
                        integer(block.get("x"), "hub jump pad x"),
                        integer(block.get("y"), "hub jump pad y"),
                        integer(block.get("z"), "hub jump pad z"));
                if (!occupiedBlocks.add(position)) {
                    throw new IllegalArgumentException(
                            "Duplicate hub jump pad block " + position);
                }
                blocks.add(position);
            }
            if (blocks.isEmpty() || blocks.size() > 32) {
                throw new IllegalArgumentException(
                        "hub jump pad " + id + " requires 1..32 blocks");
            }
            ConfigurationSection velocity = required(definition, "velocity");
            jumpPads.add(new JumpPadDefinition(id, plate, blocks,
                    boundedDouble(velocity.getDouble("x"), -3.0D, 3.0D,
                            "hub jump pad velocity.x"),
                    boundedDouble(velocity.getDouble("y"), 0.1D, 3.0D,
                            "hub jump pad velocity.y"),
                    boundedDouble(velocity.getDouble("z"), -3.0D, 3.0D,
                            "hub jump pad velocity.z"),
                    range(definition.getInt("cooldown-ms", 1500),
                            250, 10_000, "hub jump pad cooldown-ms")));
        }

        boolean protectSpawn = section.getBoolean("protect-spawn", true);
        boolean disableHunger = section.getBoolean("disable-hunger", true);
        boolean invulnerable = section.getBoolean("invulnerable", true);
        boolean healOnEntry = section.getBoolean("heal-on-entry", true);

        return new HubSettings(spawn, section.getBoolean("teleport-on-join", true),
                onboardingDelay, openMenu, openHelpDialog, jumpPads,
                protectSpawn, disableHunger, invulnerable, healOnEntry);
    }

    private static void validateHubId(String id, String type) {
        if (!HUB_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Unsafe hub " + type + " id " + id);
        }
    }

    private static String world(String raw, String path) {
        String value = raw == null ? "" : raw.trim();
        if (!WORLD_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Unsafe world name in " + path);
        }
        return value;
    }

    private static int integer(Object raw, String path) {
        if (!(raw instanceof Number value)) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        double decimal = value.doubleValue();
        int integer = value.intValue();
        if (!Double.isFinite(decimal) || decimal != integer
                || integer < -30_000_000 || integer > 30_000_000) {
            throw new IllegalArgumentException("Invalid integer in " + path);
        }
        return integer;
    }

    private static double coordinate(double value, String path) {
        return boundedDouble(value, -30_000_000.0D, 30_000_000.0D, path);
    }

    private static float angle(double value, double min, double max, String path) {
        return (float) boundedDouble(value, min, max, path);
    }

    private static double boundedDouble(double value, double min, double max, String path) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(path + " outside " + min + ".." + max);
        }
        return value;
    }

    private static WandDefinition wand(ConfigurationSection section, String id) {
        int uses = range(section.getInt("uses"), 1, 100_000, "wand uses");
        long price = section.getLong("price");
        if (price <= 0L || price > 1_000_000_000L) {
            throw new IllegalArgumentException("Invalid wand price " + id);
        }
        return new WandDefinition(id, material(section.getString("material"), "wand " + id),
                section.getString("name", id), section.getStringList("lore"), price, uses);
    }

    private static ConfigurationSection required(ConfigurationSection parent, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException("Missing configuration section " + path);
        }
        return section;
    }

    private static Material material(String raw, String path) {
        Material material = raw == null ? null : Material.matchMaterial(raw);
        if (material == null || material.isAir() || !material.isItem()) {
            throw new IllegalArgumentException("Invalid material in " + path + ": " + raw);
        }
        return material;
    }

    private static int range(int value, int min, int max, String path) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(path + " outside " + min + ".." + max);
        }
        return value;
    }

    public record ExternalAction(@NotNull String id, int slot, @NotNull Material material,
                          @NotNull String name, @NotNull List<String> lore,
                          @NotNull String command, @NotNull String requiredPlugin) {
        public ExternalAction {
            lore = List.copyOf(lore);
            requiredPlugin = requiredPlugin.trim();
        }
    }




    private static LeaderboardSettings leaderboards(@Nullable ConfigurationSection section) {
        if (section == null || !section.getBoolean("enabled", false)) {
            return LeaderboardSettings.disabled();
        }
        int refresh = range(section.getInt("refresh-seconds", 300), 30, 3600,
                "leaderboards.refresh-seconds");
        int entries = range(section.getInt("entries", 5), 1, 10, "leaderboards.entries");
        double scale = boundedDouble(section.getDouble("scale", 1.0D), 0.5D, 4.0D,
                "leaderboards.scale");

        List<LeaderboardBoard> boards = new ArrayList<>();
        ConfigurationSection boardSection = required(section, "boards");
        for (String id : boardSection.getKeys(false)) {
            validateHubId(id, "leaderboard");
            ConfigurationSection board = required(boardSection, id);
            String source = board.getString("source", "");
            if (!"season".equals(source) && !"all-time".equals(source)) {
                throw new IllegalArgumentException("leaderboards.boards." + id
                        + ".source musi być 'season' albo 'all-time', było '" + source + "'");
            }
            String title = board.getString("title", "");
            if (title.isBlank() || title.length() > 256) {
                throw new IllegalArgumentException("leaderboards.boards." + id
                        + ".title jest wymagany i nie może przekraczać 256 znaków");
            }
            float yaw = (float) board.getDouble("yaw", -90.0D);
            float pitch = (float) board.getDouble("pitch", 0.0D);
            Display.Billboard billboard;
            try {
                billboard = Display.Billboard.valueOf(board.getString("billboard", "FIXED").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                billboard = Display.Billboard.FIXED;
            }
            boards.add(new LeaderboardBoard(id, "all-time".equals(source),
                    world(board.getString("world", "world"),
                            "leaderboards.boards." + id + ".world"),
                    coordinate(board.getDouble("x"), "leaderboards.boards." + id + ".x"),
                    coordinate(board.getDouble("y"), "leaderboards.boards." + id + ".y"),
                    coordinate(board.getDouble("z"), "leaderboards.boards." + id + ".z"),
                    yaw, pitch, billboard,
                    title, board.getString("metric", "wynik")));
        }
        if (boards.isEmpty()) {
            throw new IllegalArgumentException("leaderboards.boards nie może być puste, "
                    + "gdy leaderboards.enabled = true");
        }
        ConfigurationSection pedestalSection = section.getConfigurationSection("pedestal");
        PedestalSettings pedestal = pedestalSection == null
                || !pedestalSection.getBoolean("enabled", false)
                ? PedestalSettings.disabled()
                : new PedestalSettings(true,
                        material(pedestalSection.getString("floor"), "leaderboards.pedestal.floor"),
                        material(pedestalSection.getString("wall"), "leaderboards.pedestal.wall"),
                        material(pedestalSection.getString("accent"), "leaderboards.pedestal.accent"),
                        material(pedestalSection.getString("light"), "leaderboards.pedestal.light"),
                        range(pedestalSection.getInt("approach", 1), 1, 16,
                                "leaderboards.pedestal.approach"));
        return new LeaderboardSettings(true, refresh, entries, scale, boards, pedestal);
    }







    public record AutomationSettings(long chunkerPrice, long sellChestPrice) {
    }
}
