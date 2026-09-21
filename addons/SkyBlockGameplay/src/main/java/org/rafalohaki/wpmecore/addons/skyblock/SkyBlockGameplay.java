package org.rafalohaki.wpmecore.addons.skyblock;

import org.rafalohaki.wpmecore.addons.skyblock.hub.SkyBlockProgressiveObjectiveService;

import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockModule;
import org.rafalohaki.wpmecore.addons.skyblock.automation.AutomationModule;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionsModule;
import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonModule;
import org.rafalohaki.wpmecore.addons.skyblock.season.DefaultSeasonPointService;
import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonQuestService;
import org.rafalohaki.wpmecore.addons.skyblock.season.SqlSeasonPointDao;
import org.rafalohaki.wpmecore.addons.skyblock.hub.HubModule;

import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.economy.IslandBankMenu;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.economy.PlayerOperationCoordinator;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockVaultProvider;
import org.rafalohaki.wpmecore.addons.skyblock.economy.VaultEconomyHook;

import org.rafalohaki.wpmecore.addons.skyblock.hub.HubPedestal;
import org.rafalohaki.wpmecore.addons.skyblock.hub.SkyBlockHub;
import org.rafalohaki.wpmecore.addons.skyblock.hub.SkyBlockPresentationPublisher;
import org.rafalohaki.wpmecore.addons.skyblock.hub.WandService;

import org.rafalohaki.wpmecore.addons.skyblock.season.CosmeticCatalog;
import org.rafalohaki.wpmecore.addons.skyblock.season.CosmeticService;
import org.rafalohaki.wpmecore.addons.skyblock.season.LeaderboardDao;
import org.rafalohaki.wpmecore.addons.skyblock.season.LeaderboardService;
import org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockTopRewardCoordinator;

import org.rafalohaki.wpmecore.addons.skyblock.quests.DailyQuestService;

import org.rafalohaki.wpmecore.addons.skyblock.shop.ServerShop;

import org.rafalohaki.wpmecore.addons.skyblock.forge.ForgeConfig;
import org.rafalohaki.wpmecore.addons.skyblock.forge.ForgeMenu;
import org.rafalohaki.wpmecore.addons.skyblock.forge.ForgeService;

import org.rafalohaki.wpmecore.addons.skyblock.automation.ChunkerDao;
import org.rafalohaki.wpmecore.addons.skyblock.automation.ChunkerListener;
import org.rafalohaki.wpmecore.addons.skyblock.automation.ChunkerService;
import org.rafalohaki.wpmecore.addons.skyblock.automation.CobblestoneGeneratorListener;
import org.rafalohaki.wpmecore.addons.skyblock.automation.SellChestDao;
import org.rafalohaki.wpmecore.addons.skyblock.automation.SellChestListener;
import org.rafalohaki.wpmecore.addons.skyblock.automation.SellChestService;

import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionDao;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionDisplayRenderer;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionItem;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionListener;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionMenu;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionService;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionsConfig;

import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockContent;
import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockContentLoader;
import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockDao;
import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockMilestoneDao;
import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockMilestoneMenu;
import org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockService;

import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.IslandProfileDao;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.ProfileStateService;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandCapabilities;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandFluidInitializer;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandLifecycleGuard;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaBootstrap;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandMode;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandModeFlags;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandCreationCoordinator;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandCreationMenus;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandCenterMenu;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandGuideMenu;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandPrestige;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandPrestigeDao;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandPrestigeMenu;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandPrestigeService;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandUpgrades;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandUpgradesDao;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandUpgradesMenu;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandUpgradesService;
import org.rafalohaki.wpmecore.addons.skyblock.playtime.ProfilePlaytimeDao;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.WpmeAPI;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.api.service.SchedulerService;
import org.rafalohaki.wpmecore.api.util.AddonBootstrap;
import org.rafalohaki.wpmecore.api.util.ConfigUpdater;

import java.util.List;
import java.util.UUID;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Professional, Folia-safe gameplay layer around Skyllia's island core. */
public final class SkyBlockGameplay extends JavaPlugin implements Listener {

    /** Skyllia registers {@code skyllia} with the aliases {@code is} and {@code ob}. */
    private static final Set<String> ISLAND_COMMANDS = Set.of("skyllia", "is", "ob");

    private AddonBootstrap.SqlBinding sqlBinding;
    private LedgerService ledger;
    private SkylliaIntegration skyllia;
    private DailyQuestService quests;
    private DefaultSeasonPointService seasonPoints;
    private SeasonQuestService seasonQuests;
    private org.rafalohaki.wpmecore.addons.skyblock.season.SeasonQuestCatalog seasonQuestCatalog;
    private org.rafalohaki.wpmecore.addons.skyblock.season.SeasonPassService seasonPass;
    private org.rafalohaki.wpmecore.addons.skyblock.season.SeasonPassMenu seasonPassMenu;
    private org.rafalohaki.wpmecore.addons.skyblock.season.CosmeticPlayerMenu cosmeticPlayerMenu;
    private org.rafalohaki.wpmecore.addons.skyblock.season.SeasonSchedule seasonSchedule =
            org.rafalohaki.wpmecore.addons.skyblock.season.SeasonSchedule.disabled();
    private org.rafalohaki.wpmecore.addons.skyblock.season.SeasonFishPoints seasonFishPoints;
    private org.rafalohaki.wpmecore.addons.skyblock.season.SeasonDailyPointsCaps seasonDailyPointsCaps =
            org.rafalohaki.wpmecore.addons.skyblock.season.SeasonDailyPointsCaps.NONE;
    private org.rafalohaki.wpmecore.addons.skyblock.season.SeasonDailyPointsService seasonDailyPoints;
    /** P1-3: 15 zadań dnia w tygodniu → 1 Złoty Lotos dla właściciela wyspy (tryb eco). */
    private org.rafalohaki.wpmecore.addons.skyblock.quests.WeeklyMilestoneService weeklyMilestones;
    private org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEndService seasonCloseService;
    private org.rafalohaki.wpmecore.addons.skyblock.season.EditionRegistry editionRegistry =
            org.rafalohaki.wpmecore.addons.skyblock.season.EditionRegistry.empty();
    private org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEditionDao seasonEditionDao;
    private org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEditionService editionService;
    private org.rafalohaki.wpmecore.addons.skyblock.season.SeasonAdminMenu seasonAdminMenu;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask editionRefreshTask;
    private VaultEconomyHook vaultHook;
    private SkyBlockMenus mainMenu;
    private ServerShop shop;
    private IslandBankMenu bank;
    private PlayerOperationCoordinator operationCoordinator;
    private InventoryOutbox inventoryOutbox;
    private IslandLifecycleGuard lifecycleGuard;
    private IslandFluidInitializer fluidInitializer;
    private NamespacedKey onboardingKey;
    private SchedulerService scheduler;
    private OneBlockModule oneBlock;
    private AutomationModule automation;
    private MinionsModule minions;
    private SeasonModule season;
    private HubModule hubModule;
    private SkyBlockProgressiveObjectiveService progressiveObjectives;
    private SkyBlockPresentationPublisher presentationPublisher;
    /** Migracja Eco (config `eco-migration.enabled`): zadania, questy sezonowe i karnet
     *  obsługują EcoQuests/EcoBattlepass; nasze moduły zostają nieaktywne (bez listenerów,
     *  komendy przekierowane), żeby nie dublować nagród i punktów. */
    private boolean ecoMigration = true;
    private SkyBlockGameplayDropsListener gameplayDropsListener;
    /** Rotacyjne eventy boostów (sekcja events:); null = wyłączone/brak sekcji. */
    private SkyBlockEventsService skyBlockEvents;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask eventsTickTask;
    private org.rafalohaki.wpmecore.addons.skyblock.perks.IslandFlyModule islandFly;

    public org.rafalohaki.wpmecore.addons.skyblock.perks.IslandFlyModule islandFly() {
        return islandFly;
    }

    private org.rafalohaki.wpmecore.addons.skyblock.talisman.TalismanListener talismanListener;
    private org.rafalohaki.wpmecore.addons.skyblock.reward.DailyRewardListener dailyRewardListener;
    private OneBlockContent oneBlockContent;
    private MiniMessage miniMessage;
    private MinionsConfig minionsConfig;
    private ForgeConfig forgeConfig;
    private ForgeService forgeService;
    private ForgeMenu forgeMenu;
    private CosmeticCatalog cosmeticCatalog;
    private SkyBlockRecipeService recipeService;
    private IslandProfileDao islandProfileDao;
    private ProfileStateService profileStateService;
    private org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandProfileCreationListener profileCreationListener;
    private org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandOwnerTransferListener ownerTransferListener;
    // ISLAND-2: projekcja leave/kick na SkyblockRemoveMemberEvent
    private org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandMemberRemoveListener memberRemoveListener;
    // M1-C
    private IslandModeFlags islandModeFlags;
    private IslandCreationCoordinator creationCoordinator;
    private IslandCenterMenu islandCenter;
    private org.rafalohaki.wpmecore.addons.skyblock.island.IslandLevelService islandLevels;
    /** Prestiż Wyspy — {@code null}, gdy config nie ma sekcji {@code island.prestige} (fail-closed). */
    private IslandPrestigeService islandPrestige;
    private IslandPrestigeMenu islandPrestigeMenu;
    /** Ulepszenia Wyspy — {@code null}, gdy config nie ma sekcji {@code island.upgrades} (fail-closed). */
    private IslandUpgradesService islandUpgrades;
    private IslandUpgradesMenu islandUpgradesMenu;
    /** Trwały tytuł wyspy (migracja #13) — jeden na wyspę, z prestiżu/sezonu/zlewu Lotosów. */
    private org.rafalohaki.wpmecore.addons.skyblock.island.IslandTitleService islandTitles;
    private IslandCreationMenus islandCreationMenus;
    private IslandGuideMenu islandGuide;
    private ProfilePlaytimeDao playtimeDao;
    // M1-D recovery
    private org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileTransitionDao transitionDao;
    private org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileSnapshotDao snapshotDao;
    private org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileSnapshotService snapshotService;
    private org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileRecoveryService recoveryService;
    private org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileMutationGuard mutationGuard;
    private org.rafalohaki.wpmecore.addons.skyblock.playtime.ProfilePlaytimeService playtimeService;
    private org.rafalohaki.wpmecore.addons.skyblock.analytics.OnboardingAnalyticsService onboardingAnalytics;
    private org.rafalohaki.wpmecore.addons.skyblock.migration.BackfillService backfillService;
    private org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileAccountDao profileAccountDao;
    private org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileAccountService profileAccountService;
    private org.rafalohaki.wpmecore.addons.skyblock.admin.SkyBlockAdminCommands skyBlockAdminCommands;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask playtimeHeartbeatTask;

    @Override
    public void onEnable() {
        WpmeAPI api = AddonBootstrap.attach(this);
        if (api == null) {
            return;
        }
        saveDefaultConfig();
        this.onboardingKey = new NamespacedKey(this, "onboarding_complete");
        this.scheduler = api.createSchedulerService(this);
        org.rafalohaki.wpmecore.addons.skyblock.shared.Ui.init(api.clientAssetService());
        ConfigUpdater.update(this, "config.yml");

        SkyBlockSettings settings;
        try {
            settings = SkyBlockSettings.load(getConfig());
        } catch (IllegalArgumentException failure) {
            getLogger().log(Level.SEVERE, "Invalid SkyBlockGameplay configuration", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        AddonBootstrap.SqlBinding binding = AddonBootstrap.resolveSqlService(this, api);
        if (binding == null) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Fail-closed (D8): the OneBlock content must be valid before the schema
        // migrator runs — migration 7 backfills phase ids from this very list.
        CustomItemService customItems = api.customItemService();
        try {
            this.oneBlockContent = OneBlockContentLoader.load(this, customItems);
        } catch (IllegalArgumentException e) {
            getLogger().severe("Nieprawidłowa treść OneBlock: " + e.getMessage());
            throw e;
        }
        // Fail-closed jak cosmetics.yml: nieznany id lotosu z daily-reward wali
        // start, zamiast cicho pomijać 7. dzień serii.
        if (settings.dailyReward().enabled()
                && customItems.byId(settings.dailyReward().lotusItem()).isEmpty()) {
            getLogger().severe("Invalid daily-reward.custom-item: " + settings.dailyReward().lotusItem());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.sqlBinding = binding;
        this.ledger = new LedgerService(
                new LedgerDao(binding.service()),
                settings.startingBalance());
        try {
            String questCutoff = settings.quests().period(settings.quests().today()
                    .minusDays(settings.gameplayRetentionDays()));
            // LEDGER-1: to samo pokrętło co questy — outbox ekwipunku też ma retencję.
            long outboxCutoff = System.currentTimeMillis()
                    - java.util.concurrent.TimeUnit.DAYS.toMillis(settings.gameplayRetentionDays());
            ledger.init(questCutoff, outboxCutoff);
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "Failed to initialize the SkyBlock ledger", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // M1-A island profile guard (exactly one ACTIVE membership per UUID)
        this.islandProfileDao = new IslandProfileDao(binding.service());
        this.profileStateService = new ProfileStateService(islandProfileDao);
        this.playtimeDao = new ProfilePlaytimeDao(binding.service());
        this.islandModeFlags = settings.islandModes();

        SkylliaBootstrap skylliaBootstrap = SkylliaBootstrap.create().orElse(null);
        if (skylliaBootstrap == null) {
            getLogger().severe("Wpme lifecycle-capable Skyllia contract is required.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.skyllia = skylliaBootstrap.createIntegration(this, IslandCapabilities.allEnabled(), api.cacheService());
        this.creationCoordinator = new IslandCreationCoordinator(this, skyllia, profileStateService, islandModeFlags);
        // SKY-1: po udanym paste schematu OneBlock coordinator rejestruje magiczny blok
        this.creationCoordinator.setOneBlockRegistrar((islandId, loc) ->
                oneBlock.registerIslandBlock(islandId, loc));
        // Wire IdentityService into SkylliaIntegration for bank/withdraw conflict gate
        try {
            org.rafalohaki.wpmecore.api.identity.IdentityService identity = api.identityService();
            if (identity != null) {
                skyllia.setIdentityService(identity);
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Could not wire IdentityService to SkylliaIntegration", e);
        }

        this.operationCoordinator = new PlayerOperationCoordinator();
        this.inventoryOutbox = new InventoryOutbox(this, ledger, operationCoordinator);
        // Potrzebny także przy odtwarzaniu po restarcie, gdzie nie ma wywołującego,
        // który mógłby podać rejestr — stąd wstrzyknięcie tu, a nie przy zleceniu.
        this.inventoryOutbox.setCustomItemService(customItems);
        try {
            this.cosmeticCatalog = CosmeticCatalog.load(this, customItems);
        } catch (IllegalArgumentException failure) {
            getLogger().log(Level.SEVERE, "Invalid cosmetics.yml", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            this.minionsConfig = MinionsConfig.load(this, customItems);
        } catch (IllegalArgumentException failure) {
            getLogger().log(Level.SEVERE, "Invalid minions.yml", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        /*
         * forge.yml po minions.yml: receptury na minionki odwołują się do typów
         * i mają paść na starcie, gdy typ zniknie z konfiguracji.
         */
        try {
            this.forgeConfig = ForgeConfig.load(this, customItems, minionsConfig.minions().keySet());
        } catch (IllegalArgumentException failure) {
            getLogger().log(Level.SEVERE, "Invalid forge.yml", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // M2.5: kalendarz sezonów z config.yml — fail-closed przy złych wartościach
        try {
            this.seasonSchedule = org.rafalohaki.wpmecore.addons.skyblock.season.SeasonSchedule.load(
                    getConfig().getConfigurationSection("season"));
        } catch (IllegalArgumentException failure) {
            getLogger().log(Level.SEVERE, "Invalid season section in config.yml", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // M2.5: katalog questów sezonowych — fail-closed przy złym configu
        try {
            this.seasonQuestCatalog = org.rafalohaki.wpmecore.addons.skyblock.season.SeasonQuestCatalog.load(
                    getConfig().getConfigurationSection("season.quests"));
        } catch (IllegalArgumentException failure) {
            getLogger().log(Level.SEVERE, "Invalid season.quests section in config.yml", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // C2: aktywny pak questów wynika z edycji AKTUALNEJ W CZASIE (nie z
        // cache po zamknięciu): brak edycji/klucza quest-pack = questa
        // uniwersalne. Pole editionRegistry jest mutowalne — reload panelu
        // podmienia je razem z swapRegistry, więc dostawca zawsze czyta
        // świeży rejestr.
        this.seasonQuestCatalog.setActivePackSupplier(
                () -> editionRegistry.questPackFor(System.currentTimeMillis()));
        // M2.5: tabela rzadkość → punkty za ryby (season.fish-points) —
        // fail-closed przy złym configu; brak sekcji = błąd startu
        try {
            this.seasonFishPoints = org.rafalohaki.wpmecore.addons.skyblock.season.SeasonFishPoints.load(
                    getConfig().getConfigurationSection("season.fish-points"));
        } catch (IllegalArgumentException failure) {
            getLogger().log(Level.SEVERE, "Invalid season.fish-points section in config.yml", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // P1-1: dobowe capy punktów per kanał (season.daily-points-caps).
        // Brak sekcji = capy wyłączone (zachowanie bez zmian); 0 = kanał bez
        // capu; wartość ujemna/nie-liczba = fail-closed z nazwą kanału.
        try {
            this.seasonDailyPointsCaps = org.rafalohaki.wpmecore.addons.skyblock.season.SeasonDailyPointsCaps.load(
                    getConfig().getConfigurationSection("season.daily-points-caps"));
        } catch (IllegalArgumentException failure) {
            getLogger().log(Level.SEVERE, "Invalid season.daily-points-caps section in config.yml", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        SkyBlockVaultProvider vaultProvider = new SkyBlockVaultProvider(
                ledger, operationCoordinator, inventoryOutbox);
        this.vaultHook = new VaultEconomyHook(this);
        if (!vaultHook.register(vaultProvider)) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        MiniMessage miniMessage = api.messageService().miniMessage();
        this.miniMessage = miniMessage;
        MenuService menus = api.createMenuService(this);

        /*
         * Moduły funkcji. Każdy buduje swoje obiekty i przyjmuje to, co
         * współdzielone (ledger, outbox, Skyllia, katalog przedmiotów) tutaj —
         * żaden nie sięga po globalny rejestr i żaden nie konstruuje usług
         * innej funkcji. Dzięki temu kolejność tworzenia jest widoczna w tej
         * jednej metodzie, a nie rozproszona po module.
         */
        this.oneBlock = new OneBlockModule(this, binding.service(), oneBlockContent,
                menus, miniMessage, skyllia, customItems);
        this.automation = new AutomationModule(this, binding.service(), miniMessage,
                settings.shop(), ledger, skyllia);
        this.ecoMigration = getConfig().getBoolean("eco-migration.enabled", true);
        if (ecoMigration) {
            getLogger().info("Migracja Eco aktywna: zadania dzienne/sezonowe i karnet obsługują EcoQuests/EcoBattlepass.");
        }
        if (getConfig().getBoolean("features.minions", false)) {
            this.minions = new MinionsModule(this, binding.service(), menus, miniMessage,
                    minionsConfig, ledger, skyllia, customItems);
        } else {
            getLogger().info("Minionki wyłączone (features.minions: false) — "
                    + "obsługę przejmuje zewnętrzny addon (np. SkylliaMinions).");
        }
        /*
         * Tytuł wyspy (migracja #13): jeden trwały napis na wyspę, który nosi
         * cała załoga. Składają się na niego trzy źródła — prestiż (monety
         * z banku wyspy), nagroda sezonu (miejsce w rankingu) i jednorazowy
         * zlew 3 Diamentowych Lotosów. Zero bonusów: tytuł jest wyłącznie
         * kosmetyczny, dlatego trzyma go osobna tabela, a nie profil wyspy.
         */
        this.islandTitles = new org.rafalohaki.wpmecore.addons.skyblock.island.IslandTitleService(
                new org.rafalohaki.wpmecore.addons.skyblock.island.IslandTitleDao.Sql(
                        binding.service()));
        this.season = new SeasonModule(this, binding.service(), miniMessage,
                settings.leaderboards(), settings.seasons(),
                ledger, inventoryOutbox, cosmeticCatalog, skyllia, customItems,
                // %skyblock_island_title%: tytuł wyspy gracza wprost z migawki
                // magazynu (żadnego SQL-a w wątku rysującym hologram).
                playerId -> islandTitles.placeholderTitle(
                        skyllia.cachedIslandIdOf(playerId).orElse(null)));
        // C1: nagrody top z config.yml (season.top-rewards; fail-closed na
        // 3 domyślne pozycje) oraz resolver nazwy edycji dla komunikatów
        // operatora (null/brak edycji → legacy fallback zakresu dat).
        // Setter-injection tuż po budowie modułu, zanim ktokolwiek trafi w
        // /nagrody — pola koordynatora są volatile.
        var coordinator = season.coordinator();
        var topRewards = SkyBlockSettings.loadTopRewards(
                getConfig().getConfigurationSection("season"));
        // P2-4 fail-closed: próg sezonu bez wpisu rank 4 wpuściłby wyspę przez bramkę
        // uprawnień i odmówił dopiero przy wypłacie (przegląd 2026-09-05).
        if (settings.seasons().thresholdScore() > 0L && topRewards.stream().noneMatch(
                r -> r.rank() == org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockTopRewardCoordinator.THRESHOLD_RANK)) {
            throw new IllegalArgumentException("seasons.threshold-score > 0 wymaga wpisu rank "
                    + org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockTopRewardCoordinator.THRESHOLD_RANK
                    + " w season.top-rewards");
        }
        coordinator.setTopRewards(topRewards);
        /*
         * Nagroda kosmetyczna za miejsce w rankingu (season.title-rewards,
         * fail-closed: brak sekcji = żadne miejsce nie daje tytułu) trafia do
         * trwałego magazynu tytułów wyspy.
         */
        coordinator.setTitleRewards(SkyBlockSettings.loadTitleRewards(
                getConfig().getConfigurationSection("season")));
        coordinator.setIslandTitles(islandTitles);
        coordinator.setEditionNameResolver(sid -> {
            var service = editionService;
            var edition = service == null ? null : service.currentCached(sid);
            return edition == null ? null : edition.displayName();
        });
        /*
         * Nazwane edycje sezonów (editions.yml, precedent cosmetics.yml):
         * brak pliku albo enabled:false = tryb legacy (matematyka z
         * SeasonSchedule, zachowanie byte-stabilne). Zły plik: SEVERE +
         * kontynuacja jako legacy — nie zabijamy pluginu przez literówkę
         * w data-file, bo fallback jest bezpieczny i sprawdzony.
         */
        try {
            this.editionRegistry = loadEditionRegistry();
        } catch (IllegalArgumentException failure) {
            getLogger().log(Level.SEVERE,
                    "Invalid editions.yml — kontynuacja w trybie legacy", failure);
            this.editionRegistry =
                    org.rafalohaki.wpmecore.addons.skyblock.season.EditionRegistry.empty();
        }
        this.seasonEditionDao =
                new org.rafalohaki.wpmecore.addons.skyblock.season.SqlSeasonEditionDao(
                        binding.service());
        this.editionService = new org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEditionService(
                seasonEditionDao, editionRegistry);
        // B1 (rozszerzenia poza zamrożony rdzeń): dostawca bieżącego sid dla
        // refreshActivation oraz legacy-fallback etykiety zakresu dat.
        this.editionService.setSeasonIdSupplier(topRewardCoordinator()::getCachedCurrentSeason);
        this.editionService.setLegacyLabelFallback(nowMillis -> seasonSchedule.label());
        // Boot: pierwsze wiązanie sezon→edycja (idempotentne; kolejne co 60 s).
        this.editionService.refreshActivation(System.currentTimeMillis());

        /*
         * Rzeczy naprawdę międzyfunkcyjne zostają tutaj, bo należą do wszystkich
         * naraz: skasowanie wyspy musi posprzątać w pięciu funkcjach, a menu
         * główne jest jedną tablicą rozdzielczą do sześciu.
         */
        this.lifecycleGuard = IslandLifecycleGuard.register(
                this, skyllia, ledger::authoritativeIslandBalance, miniMessage,
                islandId -> {
                    oneBlock.forgetIsland(islandId);
                    automation.forgetIsland(islandId);
                    if (minions != null) {
                        minions.forgetIsland(islandId);
                    }
                    islandTitles.forgetIsland(islandId).exceptionally(ex -> {
                        getLogger().log(Level.WARNING, "Tytuł wyspy nie został skasowany dla " + islandId, ex);
                        return null;
                    });
                    if (profileStateService != null) {
                        profileStateService.deleteIsland(islandId).exceptionally(ex -> {
                            getLogger().log(Level.WARNING, "Profile delete failed for " + islandId, ex);
                            return false;
                        });
                    }
                }).orElse(null);
        if (lifecycleGuard == null) {
            getLogger().warning("Skyllia island lifecycle guard is inactive; island deletes "
                    + "will not be vetoed on a positive bank balance.");
        } else {
            try {
                org.rafalohaki.wpmecore.api.identity.IdentityService identity = api.identityService();
                if (identity != null) lifecycleGuard.setIdentityService(identity);
                lifecycleGuard.setProfileStateService(profileStateService);
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Could not wire lifecycle guard extras", e);
            }
        }
        // M1-A: island creation -> profile guard (exactly one ACTIVE per UUID)
        this.profileCreationListener = org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandProfileCreationListener.register(this, profileStateService);
        // M1-A: projekcja ownera po transferze wyspy
        this.ownerTransferListener = org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandOwnerTransferListener.register(this, islandProfileDao);
        this.fluidInitializer = IslandFluidInitializer.register(this,
                (islandId, loc) -> oneBlock.registerIslandBlock(islandId, loc));

        oneBlock.enable();
        automation.enable();
        if (minions != null) {
            minions.enable();
        }

        this.forgeService = new ForgeService(this, miniMessage, inventoryOutbox,
                forgeConfig, customItems, minionsConfig);
        this.forgeMenu = new ForgeMenu(this, menus, miniMessage, forgeService, ledger);
        this.shop = new ServerShop(miniMessage, settings.shop(),
                inventoryOutbox);
        this.bank = new IslandBankMenu(this, menus, miniMessage, ledger, skyllia,
                operationCoordinator, inventoryOutbox, settings.bankAmounts());
        this.quests = new DailyQuestService(this, menus, miniMessage, ledger, skyllia,
                settings.quests());
        this.quests.setInventoryOutbox(inventoryOutbox);
        // Zadania dzienne znaja tryb wyspy (pula classic vs oneblock), a OneBlock
        // karmi zadania ONEBLOCK — bo anuluuje wlasne BlockBreakEvent i zadne
        // MONITOR ignoreCancelled by ich inaczej nie zobaczyl.
        this.quests.bindOneblockDetector(oneBlock.service()::isOneblock);
        oneBlock.service().bindBreakObserver((islandId, playerId, broken) -> {
            org.bukkit.entity.Player breaker = org.bukkit.Bukkit.getPlayer(playerId);
            if (breaker != null) {
                if (!ecoMigration) {
                    this.quests.recordOneblockBreak(breaker, islandId, broken);
                }
                if (progressiveObjectives != null) {
                    progressiveObjectives.observeBreak(breaker, broken);
                }
                // F24: trzeci odbiorca tego samego anulowanego rozbicia. Bez niego
                // questy SEZONOWE typu BREAK nie liczyły się z centrum OneBlocka:
                // rozdział „Podziemia i Jaskinia” (500 bloków STONE/COBBLESTONE/
                // COAL_ORE/IRON_ORE) dawał zero postępu w sq_mine_1, mimo że jest
                // to cała rozgrywka tego trybu. Szczegóły: SeasonQuestService#recordBreak.
                if (seasonQuests != null && !ecoMigration) {
                    seasonQuests.recordBreak(playerId, broken.name());
                }
                // Migracja Eco: zadania dzienne OneBlocka w EcoQuests słuchają triggerów
                // `custom_oneblock` / `custom_oneblock_ore` (libreforge nie widzi anulowanego
                // rozbicia jako „OneBlocka”). Komenda z konsoli na wątku globalnym.
                if (org.bukkit.Bukkit.getPluginManager().isPluginEnabled("libreforge")) {
                    String name = breaker.getName();
                    boolean ore = broken.name().endsWith("_ORE");
                    boolean log = broken.name().endsWith("_LOG");
                    org.bukkit.Bukkit.getGlobalRegionScheduler().run(this, task -> {
                        org.bukkit.command.ConsoleCommandSender console = org.bukkit.Bukkit.getConsoleSender();
                        org.bukkit.Bukkit.dispatchCommand(console, "libreforge trigger " + name + " oneblock 1");
                        if (ore) {
                            org.bukkit.Bukkit.dispatchCommand(console, "libreforge trigger " + name + " oneblock_ore 1");
                        }
                        if (log) {
                            org.bukkit.Bukkit.dispatchCommand(console, "libreforge trigger " + name + " oneblock_log 1");
                        }
                    });
                }
            }
        });
        // M2.5: punkty i questy sezonowe — nagradzanie przez award (idempotentne),
        // progi odblokowań z konfiguracji sezonu; koniec sezonu z SeasonSchedule
        this.seasonPoints = new DefaultSeasonPointService(
                new SqlSeasonPointDao(binding.service(), topRewardCoordinator()::getCachedCurrentSeason),
                topRewardCoordinator()::getCachedCurrentSeason,
                seasonEndMillis(),
                java.time.ZoneId.of("Europe/Warsaw"),
                Math.max(1.0D, getConfig().getDouble("season.weekend-points-multiplier", 1.0D)));
        // P1-1: dzienny kanał punktów z capem — wędka i zadania dnia wchodzą
        // tym samym wejściem, więc żadne źródło nie farmi się bez limitu.
        // Punkty do puli sezonowej dopisuje i tak SeasonPointService.award.
        var dailyQuestPoints = org.rafalohaki.wpmecore.addons.skyblock.season.SeasonDailyPointsService.create(
                new org.rafalohaki.wpmecore.addons.skyblock.season.SeasonDailyPointsDao.Sql(
                        binding.service(), topRewardCoordinator()::getCachedCurrentSeason),
                this.seasonPoints,
                this.seasonDailyPointsCaps);
        // P1-3: tygodniowy kamień milowy (15 zadań dnia = 1 Złoty Lotos dla
        // właściciela wyspy) obiecywany przez natywną pętlę, ale w trybie eco
        // DailyQuestService nie jest rejestrowany, więc nikt go nie wydawał.
        // Sprawdzenie wisi na kanale „quest” — po każdym udanym przyznaniu
        // punktów dnia — i startuje z wątku SQL, nigdy z regionu.
        this.weeklyMilestones = new org.rafalohaki.wpmecore.addons.skyblock.quests.WeeklyMilestoneService(
                this, ledger, skyllia, inventoryOutbox, customItems, miniMessage, binding.service());
        this.seasonDailyPoints = weeklyMilestones.watchingQuestAwards(dailyQuestPoints);
        // Sloty progów = questy uniwersalne; questy eventu z dostawcy czytanego
        // przy każdym zdarzeniu, więc okno edycji otwiera i zamyka je bez restartu.
        this.seasonQuests = new SeasonQuestService(this.seasonPoints,
                this.seasonQuestCatalog.universalQuests(),
                this.seasonQuestCatalog::activePackQuests,
                new org.rafalohaki.wpmecore.addons.skyblock.season.SqlSeasonQuestProgressDao(
                        binding.service()),
                getLogger());
        this.seasonQuests.setMigrated(ecoMigration);
        if (!ecoMigration) {
            getServer().getPluginManager().registerEvents(this.seasonQuests, this);
        }
        // M2.5b: postęp questów wraca po restarcie — load per aktywny sezon;
        // migracja #6 już wykonana wcześniej w ledger.init, więc tabela istnieje.
        this.seasonQuests.loadProgress(topRewardCoordinator().getCachedCurrentSeason())
                .exceptionally(failure -> {
                    getLogger().log(Level.WARNING,
                            "Nie udało się wczytać postępu questów sezonowych", failure);
                    return null;
                });
        // M2.5: punkty sezonowe za ryby z EvenMoreFish (gdy plugin obecny na serwerze).
        // registerSafely izoluje niezgodne API EMF (np. NoClassDefFoundError) od startu.
        if (getServer().getPluginManager().getPlugin("EvenMoreFish") != null) {
            org.rafalohaki.wpmecore.addons.skyblock.season.SeasonFishListener.registerSafely(
                    this, this.seasonDailyPoints, this.seasonFishPoints);
        }
        // M2.5: przepustka sezonowa — /sezon pass (okablowanie martwego dotąd serwisu)
        this.seasonPass = new org.rafalohaki.wpmecore.addons.skyblock.season.SeasonPassService(
                binding.service(), topRewardCoordinator()::getCachedCurrentSeason);
        this.seasonPassMenu = new org.rafalohaki.wpmecore.addons.skyblock.season.SeasonPassMenu(
                this, menus, miniMessage, this.seasonPass, ledger, inventoryOutbox,
                api.customItemService(),
                org.rafalohaki.wpmecore.addons.skyblock.season.SeasonPassMenu
                        .resolvePremiumLotusCost(getConfig(), getLogger()),
                null);
        // P2#2: menu kosmetyki gracza (/kosmetyki) — zakładanie i zdejmowanie
        // egzemplarzy; fail-closed przy wyłączonym katalogu (CosmeticPlayerMenu.open).
        this.cosmeticPlayerMenu = new org.rafalohaki.wpmecore.addons.skyblock.season.CosmeticPlayerMenu(
                this, menus, miniMessage, cosmeticCatalog, api.customItemService(),
                sid -> {
                    var e = editionService == null ? null : editionService.currentCached(sid);
                    return e == null ? null
                            : e.displayName() + " ("
                                    + org.rafalohaki.wpmecore.addons.skyblock.season.SeasonLabels.rangeFor(e)
                                    + ")";
                },
                topRewardCoordinator()::getCachedCurrentSeason);
        // Runda 4: zamknięcie sezonu wyłącznie komendą operatora
        // (/sezon zamknij --confirm). Nagrody kosmetyczne rang 1..3 idą przez
        // recordClaim koordynatora (deterministyczny dedupe) + istniejący tor
        // kosmetyczny; broadcast nowego sezonu z zakresu dat dd.MM.yyyy.
        this.seasonCloseService = new org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEndService(
                binding.service(),
                topRewardCoordinator()::getCachedCurrentSeason,
                seasonEndMillis(),
                seasonLengthMillis(),
                cosmeticRewardDispatcher(),
                java.time.ZoneId.of("Europe/Warsaw"),
                getLogger(),
                seasonEditionDao,
                editionRegistry,
                () -> {
                    if (editionService != null) {
                        // Po COMMIT rollovera: domknij stare wpisy i zwiąż NOWY
                        // sid z aktywną edycją (idempotentne append-only).
                        editionService.refreshActivation(System.currentTimeMillis());
                    }
                });
        /*
         * Panel administratora sezonów (GUI; B4). Dostawcy liczą przy KAŻDYM
         * otwarciu slotu — brak stanu w menu, świeże dane po każdym close.
         */
        this.seasonAdminMenu = new org.rafalohaki.wpmecore.addons.skyblock.season.SeasonAdminMenu(
                this, menus, miniMessage,
                topRewardCoordinator()::getCachedCurrentSeason,
                seasonEndMillis(),
                () -> editionService.publicHeaderLabel(System.currentTimeMillis()),
                this::currentRangeDetail,
                this::closeSeason,
                this::loadEditionRegistry,
                editionService,
                binding.service(),
                SkyBlockGameplay::resolveNick);
        // C6/C10: „Przeładuj” (slot 51) — świeży editions.yml → swap rejestru
        // w serwisie → synchronizacja lokalnej referencji (quest-paki i
        // seasonEnd czytają to pole) → ponowne wiązanie sezon→edycja.
        // Zły plik: SEVERE + zostaje ostatni dobry rejestr (jak przy boot).
        this.seasonAdminMenu.setReloadHook(() -> {
            try {
                var fresh = loadEditionRegistry();
                editionService.swapRegistry(fresh);
                this.editionRegistry = fresh;
                editionService.refreshActivation(System.currentTimeMillis());
            } catch (IllegalArgumentException failure) {
                getLogger().log(Level.SEVERE,
                        "Invalid editions.yml — pozostaje poprzedni rejestr",
                        failure);
            }
        });
        this.hubModule = new HubModule(this, menus, miniMessage, settings.hub(), settings.leaderboards(),
                settings.sellWand(), settings.harvestWand(), settings.sellWandPercent(),
                ledger, shop, inventoryOutbox, skyllia);
        this.islandCenter = new IslandCenterMenu(this, menus, miniMessage, skyllia, profileStateService, playtimeDao, islandModeFlags);
        this.islandCreationMenus = new IslandCreationMenus(this, menus, miniMessage, skyllia, profileStateService, islandModeFlags, creationCoordinator);
        this.islandGuide = new IslandGuideMenu(menus, miniMessage);
        this.mainMenu = new SkyBlockMenus(this, menus, miniMessage, ledger, settings,
                quests, bank, hubModule.wands(), inventoryOutbox, automation.chunkers(),
                automation.sellChests(), oneBlock.milestoneMenu(), islandCenter, islandCreationMenus, islandGuide, skyllia, creationCoordinator);
        // F19: /menu z wyspą otwiera Centrum Wyspy, więc bez tego przejścia
        // sklep, zadania i bank nie mają z menu żadnego wejścia.
        this.islandCenter.setGameCenterOpener(this.mainMenu::open);
        this.gameplayDropsListener = new SkyBlockGameplayDropsListener(this, skyllia);
        this.gameplayDropsListener.setOneBlockLocationFilter(oneBlock::isOneBlockLocation);
        // Szczęście na kryształy = prestiż wyspy × aktywny event (obie strony 1.0
        // gdy wyłączone); ustawiamy raz, resolver czyta stan na żywo.
        this.gameplayDropsListener.setCrystalLuckResolver(this::crystalLuckFor);
        getServer().getPluginManager().registerEvents(gameplayDropsListener, this);
        // Rotacyjne eventy: deterministyczny rozkład od epoki z configu, tick co
        // 10 s z GlobalRegionScheduler (ogłoszenia i mnożniki; brak dostępu do
        // stanu regionów, więc wątek globalny jest właściwy).
        this.skyBlockEvents = SkyBlockEventsService.load(getLogger(),
                getConfig().getConfigurationSection("events"),
                text -> {
                    var component = miniMessage.deserialize(text);
                    getServer().getOnlinePlayers().forEach(p -> p.sendMessage(component));
                });
        if (skyBlockEvents != null) {
            try {
                this.eventsTickTask = getServer().getGlobalRegionScheduler().runAtFixedRate(
                        this, task -> skyBlockEvents.tick(), 100L, 200L);
                getLogger().info("Eventy rotacyjne aktywne (events.enabled).");
            } catch (Exception e) {
                getLogger().log(java.util.logging.Level.WARNING,
                        "Nie udało się zaplanować ticka eventów", e);
            }
            seasonPoints.setEventMultiplier(skyBlockEvents::seasonPointsMultiplier);
        }
        this.talismanListener = new org.rafalohaki.wpmecore.addons.skyblock.talisman.TalismanListener(
                this, skyllia, customItems, this.seasonPoints);
        getServer().getPluginManager().registerEvents(talismanListener, this);
        // Codzienna nagroda + seria logowań: auto-odbiór 2 s po wejściu, ręcznie /nagroda.
        if (settings.dailyReward().enabled()) {
            this.dailyRewardListener = new org.rafalohaki.wpmecore.addons.skyblock.reward.DailyRewardListener(
                    this, new org.rafalohaki.wpmecore.addons.skyblock.reward.DailyRewardService(
                            binding.service(), ledger, settings.dailyReward()),
                    inventoryOutbox, customItems, miniMessage, settings.dailyReward().lotusItem());
            getServer().getPluginManager().registerEvents(dailyRewardListener, this);
        }
        // Perki rang (RankPerks): lot na własnej wyspie.
        if (getConfig().getBoolean("features.island-fly", false)) {
            this.islandFly = new org.rafalohaki.wpmecore.addons.skyblock.perks.IslandFlyModule(skyllia, miniMessage);
            getServer().getPluginManager().registerEvents(islandFly, this);
        } else {
            getLogger().info("Island-fly wyłączony (features.island-fly: false) — "
                    + "obsługę przejmuje zewnętrzny addon (np. SkylliaPerks).");
        }
        // Poziom wyspy: nasza liczba (Skyllia go nie zna) z dorobku rankingu, minionków,
        // rozdziału OneBlocka i punktów sezonowych członków; tabela z migracji #10,
        // pętla co 60 s dla wysp graczy online, awans → bank + komunikat.
        this.islandLevels = new org.rafalohaki.wpmecore.addons.skyblock.island.IslandLevelService(
                getLogger(), binding.service(), ledger,
                org.rafalohaki.wpmecore.addons.skyblock.island.IslandLevel.Settings.load(
                        getConfig().getConfigurationSection("island-level")),
                topRewardCoordinator()::calculateRanking,
                islandId -> minions == null ? 0 : minions.service().countForIsland(islandId),
                islandId -> oneBlock.service().findByIsland(islandId)
                        .map(state -> Math.max(0, oneBlockContent.phaseIds().indexOf(state.phaseId())))
                        .orElse(0),
                () -> seasonPoints.top(200),
                playerId -> skyllia.islandOf(playerId)
                        .map(org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView::islandId),
                this::announceIslandLevelUp);
        islandLevels.start(scheduler, this::onlineIslandIds);
        islandCenter.setLevelService(islandLevels);
        // Prestiż Wyspy (zlew monet weterana): kolejny poziom za monety z BANKU
        // WYSPY, nagroda czysto kosmetyczna (tytuł + wpis w Centrum Wyspy).
        // Fail-closed: brak sekcji `island.prestige`, `enabled: false` albo
        // niepoliczalne wartości = brak serwisu, brak kafelka i brak menu —
        // wtyczka startuje dokładnie tak, jak bez tej funkcji.
        org.bukkit.configuration.ConfigurationSection prestigeSection =
                getConfig().getConfigurationSection("island.prestige");
        IslandPrestige.Settings prestigeSettings = IslandPrestige.Settings.load(prestigeSection);
        if (prestigeSettings == null) {
            if (prestigeSection != null) {
                getLogger().warning("island.prestige: sekcja jest, ale prestiż jest wyłączony "
                        + "albo wartości są nieprawidłowe (max-level/base-cost/cost-multiplier) "
                        + "— kafelek prestiżu się nie pokaże");
            }
        } else {
            this.islandPrestige = new IslandPrestigeService(getLogger(), prestigeSettings,
                    new IslandPrestigeDao.Sql(binding.service()), ledger, islandTitles,
                    this::announceIslandPrestige);
            this.islandPrestigeMenu = new IslandPrestigeMenu(this, menus, miniMessage, skyllia,
                    ledger, islandPrestige, islandCenter, islandTitles, customItems);
            islandCenter.setPrestigeMenu(islandPrestigeMenu);
            // Perki mechaniczne prestiżu: bonusowe sloty minionków (nauka czyta
            // cache wyspy — brak I/O w wątku interakcji). Szczęście na kryształy
            // dopina się w resolverze złożonym (crystalLuckFor) — prestiż × event.
            if (minions != null) {
                minions.listener().setIslandExtraSlots(islandPrestige::minionSlotsFor);
            }
            // Perki wyspy przy awansie: rozmiar rośnie procentowo od bieżącego
            // (kumulatywnie), slot członka co `extra-members-every-levels`.
            islandPrestige.setPerkApplier(up -> {
                double step = prestigeSettings.sizeStepPercentPerLevel();
                if (step > 0.0D) {
                    skyllia.multiplyIslandSize(up.islandId(), 1.0D + step / 100.0D);
                }
                if (prestigeSettings.grantsMemberSlot(up.level())) {
                    skyllia.addIslandMemberSlots(up.islandId(), 1);
                }
            });
        }
        // Ulepszenia Wyspy (średniotorowa progresja): poziomy za monety z BANKU
        // WYSPY — rozmiar, miejsca w zespole, sloty minionków. Ta sama logika
        // fail-closed co prestiż: brak sekcji albo niepoliczalne wartości
        // = brak kafelka i menu, wtyczka startuje jak dziś.
        org.bukkit.configuration.ConfigurationSection upgradesSection =
                getConfig().getConfigurationSection("island.upgrades");
        IslandUpgrades.Settings upgradesSettings = IslandUpgrades.Settings.load(upgradesSection);
        if (upgradesSettings == null) {
            if (upgradesSection != null) {
                getLogger().warning("island.upgrades: sekcja jest, ale ulepszenia są wyłączone "
                        + "albo wartości są nieprawidłowe (max-level/base-cost/cost-multiplier) "
                        + "— kafelek ulepszeń się nie pokaże");
            }
        } else {
            this.islandUpgrades = new IslandUpgradesService(getLogger(), upgradesSettings,
                    new IslandUpgradesDao.Sql(binding.service()), ledger,
                    this::announceIslandUpgrade);
            this.islandUpgradesMenu = new IslandUpgradesMenu(this, menus, miniMessage, skyllia,
                    ledger, islandUpgrades, islandCenter);
            islandCenter.setUpgradesMenu(islandUpgradesMenu);
            // Mutacje wyspy przy awansie toru: rozmiar kumulatywnie procentowy
            // (jak w prestiżu), miejsca w zespole +slots-per-level. Tor MINIONS
            // nie woła Skyllii — SkylliaMinions sam czyta wpme_sb_island_upgrades
            // ze współdzielonej bazy (kanał jak wpme_sb_island_prestige).
            islandUpgrades.setPerkApplier((track, up) -> {
                IslandUpgrades.TrackSettings trackSettings = upgradesSettings.track(track);
                if (trackSettings == null) {
                    return;
                }
                switch (track) {
                    case SIZE -> {
                        double step = trackSettings.percentPerLevel();
                        if (step > 0.0D) {
                            skyllia.multiplyIslandSize(up.islandId(), 1.0D + step / 100.0D);
                        }
                    }
                    case MEMBERS -> skyllia.addIslandMemberSlots(up.islandId(),
                            trackSettings.slotsPerLevel());
                    case MINIONS -> { /* sloty czyta SkylliaMinions z bazy */ }
                }
            });
        }
        // Kafel przeglądu w Centrum Wyspy pokazuje tytuł wyspy także bez prestiżu
        // (tytuł może pochodzić z sezonu albo ze zlewu Lotosów).
        islandCenter.setTitleService(islandTitles);
        this.progressiveObjectives = new SkyBlockProgressiveObjectiveService(
                this, ledger, skyllia, quests, miniMessage);
        // F24: przewodnik na tablicy bocznej musi znać tryb wyspy — inaczej gracz
        // OneBlocka dostaje polecenia z rozgrywki klasycznej („rozbij drzewo”,
        // „zbuduj generator bruku z lawy i wody”), których w jego trybie nie ma.
        // Ten sam detektor, którego używają już zadania dzienne (linia wyżej).
        this.progressiveObjectives.bindOneblockDetector(oneBlock.service()::isOneblock);
        // P1-2: etapy 11–15 przewodnika na trwałych źródłach (patrz Javadoc serwisu).
        this.progressiveObjectives.bindMinionCounter(
                islandId -> minions == null ? 0 : minions.service().countForIsland(islandId));
        // P1-3 (2026-09-10): cztery bramki przewodnika czytają to, co obiecują.
        // 1) Próg Kuźni = najtańsza receptura z cost-money > 0 (receptury-pety mają
        //    6 000, `titan_keys_for_gold_lotus` ma 0, więc nie jest progiem wejścia).
        long forgeEntryCost = forgeConfig.recipes().values().stream()
                .mapToLong(ForgeConfig.ForgeRecipe::costMoney)
                .filter(cost -> cost > 0L)
                .min()
                .orElse(0L);
        this.progressiveObjectives.bindForgeEntryCost(() -> forgeEntryCost);
        // 2) Bank wyspy (depozyt) i 3) punkty dnia czytamy asynchronicznie (JDBC) —
        //    serwis trzyma z nich cache i nie blokuje wątku regionu.
        this.progressiveObjectives.bindIslandBankBalance(ledger::authoritativeIslandBalance);
        this.progressiveObjectives.bindDailyQuestPoints(playerId ->
                seasonDailyPoints.pointsToday(playerId, "quest"));
        // 4) Limit miejsc i zlew Złotych Lotosów dla ogona weterana: minionki liczą
        //    sloty wyspy (config + perki rangi), migracja Eco — liczbę petów z kuźni.
        long forgePetTypes = forgeConfig.recipes().values().stream()
                .filter(recipe -> recipe.resultMinionType() != null
                        || (recipe.resultCommand() != null
                                && recipe.resultCommand().startsWith("minionki daj ")))
                .count();
        this.progressiveObjectives.bindSlotLimit(playerId -> {
            if (ecoMigration) {
                return (int) forgePetTypes;
            }
            org.bukkit.entity.Player minionOwner = getServer().getPlayer(playerId);
            return minionsConfig.settings().maxMinionsPerIsland()
                    + (minionOwner == null ? 0 : org.rafalohaki.wpmecore.addons.skyblock.perks.RankPerks
                            .minionExtraSlots(minionOwner));
        });
        this.progressiveObjectives.bindLotusDetector(player -> {
            for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
                if (item == null || item.isEmpty()) {
                    continue;
                }
                if (org.rafalohaki.wpmecore.addons.skyblock.shared.Inventories.matchesCustomItem(
                        item, "skyblock:token/gold_lotus", customItems)) {
                    return true;
                }
            }
            return false;
        });
        if (ecoMigration) {
            // Przewodnik liczy minionki gracza dwojako: sztuki w ekwipunku
            // (PDC `wpme:minion_item` — kontrakt MinionItem) + postawione na
            // jego wyspie. Postawione pisze SkylliaMinions do współdzielonej
            // wpme_sb_minions — odczyt JDBC trzymamy w 30 s cache, odświeżanym
            // poza wątkiem regionu (odczyt w resolveObjective biegnie na wątku
            // gracza, więc ekwipunek tam ruszać wolno).
            MinionDao sharedMinionDao = new MinionDao(binding.service());
            java.util.Map<java.util.UUID, long[]> placedStamp = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.Map<java.util.UUID, Integer> placedCache = new java.util.concurrent.ConcurrentHashMap<>();
            this.progressiveObjectives.enablePetMode(playerId -> {
                org.bukkit.entity.Player online = getServer().getPlayer(playerId);
                if (online == null) {
                    return 0;
                }
                int held = 0;
                for (org.bukkit.inventory.ItemStack item : online.getInventory().getContents()) {
                    if (MinionItem.isMinionItem(item)) {
                        held += Math.max(1, item.getAmount());
                    }
                }
                java.util.UUID islandId = skyllia.islandOf(playerId)
                        .map(org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView::islandId)
                        .orElse(null);
                if (islandId == null) {
                    return held;
                }
                long[] stamp = placedStamp.get(playerId);
                long now = System.currentTimeMillis();
                if (stamp == null || now - stamp[0] >= 30_000) {
                    placedStamp.put(playerId, new long[]{now});
                    sharedMinionDao.findByIsland(islandId)
                            .thenAccept(list -> placedCache.put(playerId, list.size()))
                            .exceptionally(failure -> null);
                }
                return held + placedCache.getOrDefault(playerId, 0);
            });
        }
        this.progressiveObjectives.bindTalismanDetector(player -> java.util.List.of(
                        org.rafalohaki.wpmecore.addons.skyblock.talisman.TalismanListener.HARVEST_TALISMAN_ID,
                        org.rafalohaki.wpmecore.addons.skyblock.talisman.TalismanListener.MINER_TALISMAN_ID,
                        org.rafalohaki.wpmecore.addons.skyblock.talisman.TalismanListener.FISHERMAN_TALISMAN_ID,
                        org.rafalohaki.wpmecore.addons.skyblock.talisman.TalismanListener.AMBER_GUARDIAN_ID)
                .stream().anyMatch(id -> talismanListener.hasTalisman(player, id)));
        this.progressiveObjectives.bindPremiumDetector(seasonPass::isPremiumCached);
        String lastChapter = oneBlockContent.chapters().getLast().id();
        this.progressiveObjectives.bindOneblockFinished(islandId -> oneBlock.service()
                .findByIsland(islandId).map(state -> lastChapter.equals(state.phaseId())).orElse(false));
        // P1-3: odbiór nagród rozdziału OneBlocka jest ręczny, a przewodnik o nich
        // milczał. Sygnał to licznik nieodebranych z `info(islandId)` (SQL) —
        // serwis trzyma z niego cache, więc wątek regionu nie dotyka bazy.
        this.progressiveObjectives.bindOneblockUnclaimedRewards(islandId -> oneBlock.service()
                .info(islandId)
                .thenApply(info -> info
                        .map(org.rafalohaki.wpmecore.addons.skyblock.oneblock.OneBlockService
                                .IslandInfo::unclaimedMilestones)
                        .orElse(0)));

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(operationCoordinator, this);
        getServer().getPluginManager().registerEvents(inventoryOutbox, this);
        if (!ecoMigration) {
            getServer().getPluginManager().registerEvents(quests, this);
        }
        getServer().getPluginManager().registerEvents(progressiveObjectives, this);
        if (!ecoMigration) {
            quests.start();
        }
        inventoryOutbox.start();
        hubModule.enable();
        season.enable();
        this.presentationPublisher = new SkyBlockPresentationPublisher(
                this, api, scheduler, ledger, skyllia, progressiveObjectives);
        this.progressiveObjectives.setPublisher(presentationPublisher);
        // Tryb wyspy do %wpme_island_mode% (EcoQuests bramkuje nim pule zadań dziennych).
        this.presentationPublisher.bindOneblockDetector(oneBlock.service()::isOneblock);
        this.presentationPublisher.setIslandLevels(islandLevels);
        // C5: %wpme_season_name% — nazwa edycji przypiętej do BIEŻĄCEGO
        // sezonu (odświeżana co 5 s przez wydawcę); brak edycji/edycje off
        // → pusty string (renderuje się jako nic, nigdy nie rzuca).
        this.presentationPublisher.setSeasonNameSupplier(() -> {
            var edition = editionService == null ? null
                    : editionService.currentCached(
                            topRewardCoordinator().getCachedCurrentSeason());
            return edition == null ? "" : edition.displayName();
        });
        presentationPublisher.start();
        this.recipeService = new SkyBlockRecipeService(this);
        recipeService.registerRecipes();
        new SkyBlockCommands(this).register();

        // M1-D: recovery infrastructure (reconnect/restart in every non-terminal state)
        this.transitionDao = new org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileTransitionDao(binding.service());
        this.snapshotDao = new org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileSnapshotDao(binding.service());
        this.snapshotService = new org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileSnapshotService(snapshotDao, "skyblock");
        this.playtimeService = new org.rafalohaki.wpmecore.addons.skyblock.playtime.ProfilePlaytimeService(playtimeDao);
        this.recoveryService = new org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileRecoveryService(transitionDao, snapshotDao, islandProfileDao, playtimeDao, snapshotService);
        // ISLAND-1: resume DELETE/RESET wolno mutowac dane dopiero po autorytatywnym
        // potwierdzeniu u Skyllii, ze wyspy naprawde nie ma (journal przezywa odrzucona
        // komende, a recoverAll leci przy kazdym boocie).
        this.recoveryService.setIslandExistsCheck(islandId -> skyllia == null || skyllia.islandExists(islandId));
        this.mutationGuard = new org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileMutationGuard(transitionDao);
        this.onboardingAnalytics = new org.rafalohaki.wpmecore.addons.skyblock.analytics.OnboardingAnalyticsService("skyblock");
        this.backfillService = new org.rafalohaki.wpmecore.addons.skyblock.migration.BackfillService(binding.service());
        this.profileAccountDao = new org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileAccountDao(binding.service());
        this.profileAccountService = new org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileAccountService(profileAccountDao, settings.startingBalance());
        // Wire guard into ledger/outbox/profile wallet so leave/kick/delete/reset blocks new mutations fail-closed
        try { ledger.setMutationGuard(mutationGuard); } catch (Exception ignored) {}
        try { inventoryOutbox.setMutationGuard(mutationGuard); } catch (Exception ignored) {}
        try { profileAccountService.setMutationGuard(mutationGuard); } catch (Exception ignored) {}
        // Reconcile crashed playtime sessions without counting offline time + resume pending transitions
        recoveryService.recoverAll().whenComplete((report, ex) -> {
            if (ex != null) {
                getLogger().log(java.util.logging.Level.WARNING, "M1-D recovery failed", ex);
            } else if (report != null) {
                getLogger().info("M1-D recovery: playtimeReconciled=" + report.playtimeReconciled()
                        + " resumed=" + report.resumed() + " quarantined=" + report.quarantined()
                        + " failed=" + report.failed() + " totalPending=" + report.totalPending());
                if (!report.details().isEmpty()) {
                    getLogger().info("M1-D recovery details: " + String.join(" | ", report.details().subList(0, Math.min(10, report.details().size()))));
                }
            }
        });
        // Periodic playtime heartbeat (Folia global scheduler, never touches region state)
        try {
            this.playtimeHeartbeatTask = getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
                long now = System.currentTimeMillis();
                for (Player p : getServer().getOnlinePlayers()) {
                    profileStateService.activeMembership(p.getUniqueId()).whenComplete((opt, err) -> {
                        if (opt != null && opt.isPresent()) {
                            var m = opt.get();
                            boolean isAfk = false;
                            try { isAfk = p.isSneaking(); } catch (Exception ignored) {}
                            // Folia-safe: heartbeat is pure SQL, no Bukkit state
                            playtimeDao.heartbeat(m.islandId(), p.getUniqueId(), now, isAfk).exceptionally(e -> {
                                getLogger().log(java.util.logging.Level.FINE, "Heartbeat failed for " + p.getUniqueId(), e);
                                return false;
                            });
                        }
                    });
                }
            }, 20L * 60L, 20L * 60L); // every 60 seconds
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING, "Could not schedule playtime heartbeat", e);
        }
        /*
         * Edycje sezonów: odświeżanie wiązania sezon→edycja co 60 s (boot i
         * post-close już wiążą; timer łapie zmiany editions.yml po reloadzie
         * registry oraz przejścia między edycjami). Folia global scheduler —
         * serwis robi SQL asynchronicznie, zero stanu regionów.
         */
        try {
            this.editionRefreshTask = getServer().getGlobalRegionScheduler().runAtFixedRate(
                    this,
                    task -> {
                        if (editionService != null) {
                            editionService.refreshActivation(System.currentTimeMillis());
                        }
                    },
                    20L * 60L, 20L * 60L);
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING,
                    "Could not schedule season edition refresh", e);
        }
        // Admin view of profile and operations (M1-D)
        try {
            this.skyBlockAdminCommands = new org.rafalohaki.wpmecore.addons.skyblock.admin.SkyBlockAdminCommands(
                    this, miniMessage, islandProfileDao, profileStateService, transitionDao, snapshotDao, recoveryService, playtimeDao, onboardingAnalytics, backfillService, new org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao(binding.service()));
            AddonBootstrap.registerCommands(this, registrar -> skyBlockAdminCommands.register(registrar));
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to register SkyBlock admin commands", e);
        }
        // Wire snapshot into lifecycle guard so delete snapshots before cleaning profile state
        try {
            if (lifecycleGuard != null && snapshotService != null) {
                lifecycleGuard.setSnapshotService(snapshotService);
            }
            // M1-D fix (delete-confirm): guard domyka oczekującą tranzycję DELETE
            // po faktycznym obsłużeniu (lub odrzuceniu) usunięcia wyspy przez Skyllię.
            if (lifecycleGuard != null && transitionDao != null) {
                lifecycleGuard.setTransitionDao(transitionDao);
            }
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING, "Could not wire snapshot service to lifecycle guard", e);
        }
        // ISLAND-2: leave/kick — deaktywacja membership OFIARY + domknięcie journala
        // LEAVE/KICK (player_uuid = wykonawcy) na SkyblockRemoveMemberEvent; bez tego
        // wykonawca kicka siedział zablokowany do relogu, a resume (ISLAND-1) już nie mutuje danych.
        try {
            this.memberRemoveListener = org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandMemberRemoveListener
                    .register(this, islandProfileDao, transitionDao);
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING, "Could not register island member-remove listener", e);
        }

        getServer().getOnlinePlayers().forEach(player -> {
            // S2-fix: fire-and-forget z logowaniem — cichy błąd utrudniał diagnostykę ekonomii po reloadzie
            ledger.ensurePlayer(player.getUniqueId())
                    .exceptionally(ex -> { getLogger().log(java.util.logging.Level.WARNING, "Ledger ensure failed for " + player.getUniqueId(), ex); return null; });
            skyllia.islandOf(player.getUniqueId());
            // ensure playtime session started for online players on reload
            profileStateService.activeMembership(player.getUniqueId()).whenComplete((opt, err) -> {
                if (opt != null && opt.isPresent()) {
                    playtimeDao.startSession(opt.get().islandId(), player.getUniqueId(), System.currentTimeMillis())
                            .exceptionally(ex2 -> { getLogger().log(java.util.logging.Level.FINE, "Playtime start failed for " + player.getUniqueId(), ex2); return false; });
                }
            });
            // analytics onboarding without IP
            onboardingAnalytics.onboardingStarted(player.getUniqueId(), "startup:" + player.getUniqueId(), org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileRecoveryService.correlationFor(player.getUniqueId(), "onboarding"));
        });
        getLogger().info("SkyBlockGameplay enabled: durable ledger, shop, island bank, "
                + "daily quests and component/PDC wands. M1-D recovery active.");
    }

    /*
     * Dostęp dla SkyBlockCommands, które leży w tym samym pakiecie.
     * Metody, nie pola: pole oddane na zewnątrz przestaje być stanem, którego
     * właściciel pilnuje, a metoda zostawia mu kontrolę nad tym, co wydaje.
     */
    IslandBankMenu bank() {
        return bank;
    }

    CosmeticCatalog cosmeticCatalog() {
        return cosmeticCatalog;
    }

    CosmeticService cosmeticService() {
        return season.cosmetics();
    }

    ForgeConfig forgeConfig() {
        return forgeConfig;
    }

    ForgeMenu forgeMenu() {
        return forgeMenu;
    }

    @org.jetbrains.annotations.Nullable SkyBlockHub hub() {
        // hubModule jest zerowany w onDisable, a komendy bywają jeszcze osiągalne —
        // wołający sprawdza null, więc akcesor musi umieć go zwrócić.
        return hubModule == null ? null : hubModule.hub();
    }

    HubModule hubModule() {
        return hubModule;
    }

    SkyBlockMenus mainMenu() {
        return mainMenu;
    }

    MiniMessage miniMessage() {
        return miniMessage;
    }

    SchedulerService scheduler() {
        return scheduler;
    }

    /** Codzienna nagroda — null, gdy {@code daily-reward.enabled: false}. */
    @org.jetbrains.annotations.Nullable org.rafalohaki.wpmecore.addons.skyblock.reward.DailyRewardListener dailyReward() {
        return dailyRewardListener;
    }

    MinionsConfig minionsConfig() {
        return minionsConfig;
    }

    OneBlockMilestoneMenu oneBlockMilestoneMenu() {
        return oneBlock.milestoneMenu();
    }

    OneBlockService oneBlockService() {
        return oneBlock.service();
    }

    public boolean ecoMigration() {
        return ecoMigration;
    }

    DailyQuestService quests() {
        return quests;
    }

    DefaultSeasonPointService seasonPoints() {
        return seasonPoints;
    }

    /** P1-1: dzienny kanał punktów z capem (wędka, zadania dnia). */
    org.rafalohaki.wpmecore.addons.skyblock.season.SeasonDailyPointsService seasonDailyPoints() {
        return seasonDailyPoints;
    }

    /** Capy dzienne kanałów — {@code NONE} (capy off), gdy brak sekcji configu. */
    org.rafalohaki.wpmecore.addons.skyblock.season.SeasonDailyPointsCaps seasonDailyPointsCaps() {
        return seasonDailyPointsCaps;
    }

    /** F24: katalog i postęp zadań sezonowych dla wykazu w {@code /sezon}. */
    org.rafalohaki.wpmecore.addons.skyblock.season.SeasonQuestService seasonQuests() {
        return seasonQuests;
    }

    /**
     * Jedno źródło końca sezonu. Edycje nazwane włączone i moment w oknie
     * edycji → koniec AKTYWNEJ edycji (A1/R2: bez tego po pierwszym ręcznym
     * zamknięciu guard wiecznie widział „sezon wygasł”). Poza oknem albo
     * edycje off → legacy math z SeasonSchedule (byte-stabilnie).
     */
    java.util.function.LongSupplier seasonEndMillis() {
        if (editionRegistry.isEnabled()) {
            return () -> {
                long now = System.currentTimeMillis();
                org.rafalohaki.wpmecore.addons.skyblock.season.Edition current =
                        editionRegistry.currentAt(now);
                return current != null ? current.endExclusiveMillis()
                        : legacySeasonEndMillis();
            };
        }
        return this::legacySeasonEndMillis;
    }

    /** Legacy matematyka końca sezonu — dotychczasowe zachowanie 1:1. */
    private long legacySeasonEndMillis() {
        if (seasonSchedule.isEnabled()) {
            return seasonSchedule.seasonEndEpochMillis();
        }
        return System.currentTimeMillis() + 56L * 86_400_000L; // sezon off — pilot
    }

    /**
     * Publiczna etykieta bieżącego sezonu: nazwa aktywnej edycji (editions.yml)
     * → zakres dat dd.MM.yyyy (legacy) → pusty string gdy nic nie jest
     * rozstrzygalne (ZERO numerków sezonu w UI — decyzja operatora 2026-08-25).
     */
    public @org.jetbrains.annotations.NotNull String seasonLabel() {
        return editionService.publicHeaderLabel(System.currentTimeMillis());
    }

    /**
     * Szczegół okna bieżącego sezonu dla panelu admina: zakres dat aktywnej
     * edycji, inaczej legacy etykieta zakresu; null/„” gdy nic nie jest
     * rozstrzygalne (tryb pilotowy).
     */
    @org.jetbrains.annotations.Nullable String currentRangeDetail() {
        long now = System.currentTimeMillis();
        if (editionRegistry.isEnabled()) {
            org.rafalohaki.wpmecore.addons.skyblock.season.Edition current =
                    editionRegistry.currentAt(now);
            if (current != null) {
                return editionRegistry.rangeDetail(current);
            }
        }
        return seasonSchedule.label();
    }

    /** Nazwa następnej edycji po „teraz”; null = brak (fallback legacy). */
    private @org.jetbrains.annotations.Nullable String nextEditionDisplayName() {
        if (!editionRegistry.isEnabled()) {
            return null;
        }
        org.rafalohaki.wpmecore.addons.skyblock.season.Edition next =
                editionRegistry.nextAfter(System.currentTimeMillis());
        return next == null ? null : next.displayName();
    }

    /** Nick gracza z UUID-stringa; fallback = wejście (panel historii). */
    private static @org.jetbrains.annotations.NotNull String resolveNick(
            @org.jetbrains.annotations.NotNull String uuidString) {
        try {
            String name = Bukkit.getOfflinePlayer(UUID.fromString(uuidString)).getName();
            return name != null ? name : uuidString;
        } catch (IllegalArgumentException failure) {
            return uuidString;
        }
    }

    org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEndService seasonEndService() {
        return seasonCloseService;
    }

    /** Panel admina sezonów (GUI); null gdy plugin wyłączony. */
    org.rafalohaki.wpmecore.addons.skyblock.season.SeasonAdminMenu seasonAdminMenu() {
        return seasonAdminMenu;
    }

    /** Serwis wiązania sezon→edycja (dla komendy/menu; może być null po off). */
    org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEditionService editionService() {
        return editionService;
    }

    /**
     * Nazwa następnej edycji po bieżącym końcu sezonu (A1/R2) albo null,
     * gdy edycje są wyłączne / brak kolejnej — wywoławca spada na legacy
     * etykietę zakresu dat. Kanał dla komendy i SeasonCloseReport.
     */
    @org.jetbrains.annotations.Nullable String seasonNextEditionLabel() {
        return nextEditionDisplayName();
    }

    /** Długość sezonu w ms; 0 gdy kalendarz off (brak etykiety zakresu dat). */
    private java.util.function.LongSupplier seasonLengthMillis() {
        if (!seasonSchedule.isEnabled()) {
            return () -> 0L;
        }
        int lengthDays = getConfig().getInt("season.length-days", 56);
        return () -> lengthDays * 86_400_000L;
    }

    /**
     * Wczytuje editions.yml z katalogu danych (precedent cosmetics.yml).
     * Brak pliku = {@link org.rafalohaki.wpmecore.addons.skyblock.season.EditionRegistry#empty()
     * pusty rejestr} — tryb legacy bez tworzenia pliku po stronie operatora.
     * Błędy danych → IllegalArgumentException (wywoławca: SEVERE + legacy).
     * Wywoływane też przy każdym otwarciu panelu admina („Przeładuj”).
     */
    private org.rafalohaki.wpmecore.addons.skyblock.season.EditionRegistry loadEditionRegistry() {
        java.io.File file = new java.io.File(getDataFolder(), "editions.yml");
        if (!file.isFile()) {
            return org.rafalohaki.wpmecore.addons.skyblock.season.EditionRegistry.empty();
        }
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        return org.rafalohaki.wpmecore.addons.skyblock.season.EditionRegistry.load(
                yaml.getConfigurationSection("editions"));
    }

    /**
     * Punkt wejścia komendy {@code /sezon zamknij}. Bez {@code confirm} —
     * DRY-RUN: podsumowanie bez jakiegokolwiek zapisu, z podglądem TOP-3
     * archiwum ({@code topArchivePreview}; pusta tablica punktów = zdanie
     * pomijane). Z {@code confirm}:
     * snapshot TOP-10 → nagrody rang 1..3 (recordClaim ⇒ dedupe) → rollover
     * season_id+1 → czyszczenie punktów zamkniętego sezonu → broadcast
     * „Nowy sezon — {zakres dat}”. Zamknięcie przed czasem wymaga
     * dodatkowo {@code force} (odmowa z liczbą dni pozostałych).
     */
    public @NotNull CompletableFuture<org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEndService.CloseOutcome> closeSeason(
            boolean confirm, boolean force) {
        org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEndService service =
                this.seasonCloseService;
        int current = topRewardCoordinator().getCachedCurrentSeason();
        if (service == null) {
            return CompletableFuture.completedFuture(
                    new org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEndService.CloseOutcome(
                            false, current, current, 0L, 0, 0, 0,
                            "Serwis zamknięcia sezonu niedostępny (plugin wyłączony?)."));
        }
        long now = System.currentTimeMillis();
        if (!confirm) {
            long end = seasonEndMillis().getAsLong();
            String state = now >= end
                    ? "sezon wygasł — czeka na zamknięcie (/sezon zamknij --confirm)"
                    : "do końca sezonu zostało "
                            + ((end - now + 86_399_999L) / 86_400_000L) + " dni";
            // Edycje nazwane: opcjonalna linia o następnej edycji; legacy = brak
            // (string identyczny z dotychczasowym).
            String nextEdition = nextEditionDisplayName();
            // Podgląd TOP-3 archiwum (tylko odczyt); pusta tablica = brak zdania,
            // wtedy tekst identyczny z dotychczasowym.
            return service.topArchivePreview(current).thenApply(topPreview -> {
                StringBuilder preview = new StringBuilder("Podgląd (bez zmian): sezon ")
                        .append(current).append(", ").append(state).append('.');
                if (topPreview != null) {
                    preview.append(' ').append(topPreview);
                }
                preview.append(" Zamknięcie wykona --confirm.");
                if (nextEdition != null) {
                    preview.append(" Następna edycja: ").append(nextEdition).append('.');
                    getLogger().info("DRY-RUN /sezon zamknij: następna edycja "
                            + nextEdition + ".");
                }
                if (topPreview != null) {
                    getLogger().info("DRY-RUN /sezon zamknij: " + topPreview);
                }
                return new org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEndService.CloseOutcome(
                        false, current, current, 0L, 0, 0, 0, preview.toString());
            });
        }
        return service.close(now, force).thenCompose(outcome -> {
            if (!outcome.closed()) {
                return CompletableFuture.completedFuture(outcome);
            }
            // SKYBLOCK-1-9: cache koordynatora dopiero po potwierdzonym zapisie
            // (rollover już siedzi w bazie; setSeason domyka pamięć podręczną).
            return topRewardCoordinator().setSeason(outcome.newSeason())
                    .thenApply(ignored -> {
                        // Post-close hook: domknij wpisy starego sid i zwiąż NOWY
                        // sid z aktywną edycją przed broadcastem (idempotentne).
                        editionService.refreshActivation(System.currentTimeMillis());
                        broadcastNewSeason(service);
                        return outcome;
                    });
        });
    }

    private void broadcastNewSeason(
            org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEndService service) {
        String range = nextEditionDisplayName();
        if (range == null && !editionRegistry.isEnabled()) {
            // Edycje włączone, ale luka w kalendarzu → neutralny komunikat
            // zamiast nieaktualnego zakresu z legacy math.
            range = null;
        } else if (range == null) {
            range = service.nextSeasonRangeLabel();
        }
        String message = range == null
                ? "<gold><bold>Nowy sezon rozpoczął się!</bold></gold>"
                : "<gold><bold>Nowy sezon — " + range + "</bold></gold>";
        getServer().broadcast(miniMessage.deserialize(message));
        getLogger().info("Broadcast nowego sezonu: " + message);
    }

    /**
     * Produkcja dyspozytora nagród kosmetycznych: gracz offline → roszczenie
     * NIE powstaje (DEFERRED_OFFLINE, wpis audytowy); online → recordClaim
     * koordynatora (deterministyczny dedupe per sezon+gracz), a po zapisaniu
     * roszczenia dostawa przez istniejący tor kosmetyczny na wątku gracza.
     */
    private org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEndService.CosmeticDispatcher cosmeticRewardDispatcher() {
        return (playerUuid, seasonId, rank) -> {
            Player online = getServer().getPlayer(playerUuid);
            if (online == null || !online.isOnline()) {
                getLogger().warning("Sezon " + seasonId + ": ranga " + rank
                        + " (" + playerUuid + ") jest offline — nagroda odroczona"
                        + " (DEFERRED_OFFLINE); do wydania przy kolejnym zamknięciu/ręcznie.");
                return CompletableFuture.completedFuture(
                        org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEndService.CosmeticDispatcher.DispatchResult.DEFERRED_OFFLINE);
            }
            java.util.UUID islandId = skyllia.islandOf(playerUuid)
                    .map(org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView::islandId)
                    .orElse(new java.util.UUID(0L, 0L)); // brak wyspy — znacznik zerowy
            return topRewardCoordinator()
                    .recordClaim(seasonId, playerUuid, islandId, rank)
                    .thenCompose(recorded -> {
                        if (!java.lang.Boolean.TRUE.equals(recorded)) {
                            return CompletableFuture.completedFuture(
                                    org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEndService.CosmeticDispatcher.DispatchResult.ALREADY_SETTLED);
                        }
                        CompletableFuture<org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEndService.CosmeticDispatcher.DispatchResult> done =
                                new CompletableFuture<>();
                        schedulePlayer(online, 1L, () -> {
                            try {
                                cosmeticService().grantSeasonReward(online, seasonId, rank, () -> { });
                                done.complete(org.rafalohaki.wpmecore.addons.skyblock.season.SeasonEndService.CosmeticDispatcher.DispatchResult.DELIVERED);
                            } catch (RuntimeException failure) {
                                getLogger().log(java.util.logging.Level.SEVERE,
                                        "Nie udało się wydać kosmetyki sezonowej dla "
                                                + playerUuid, failure);
                                done.completeExceptionally(failure);
                            }
                        });
                        return done;
                    });
        };
    }

    org.rafalohaki.wpmecore.addons.skyblock.season.SeasonPassService seasonPass() {
        return seasonPass;
    }

    org.rafalohaki.wpmecore.addons.skyblock.season.CosmeticPlayerMenu cosmeticPlayerMenu() {
        return cosmeticPlayerMenu;
    }

    org.rafalohaki.wpmecore.addons.skyblock.season.SeasonPassMenu seasonPassMenu() {
        return seasonPassMenu;
    }

    ServerShop shop() {
        return shop;
    }

    SkylliaIntegration skyllia() {
        return skyllia;
    }

    SkyBlockTopRewardCoordinator topRewardCoordinator() {
        return season.coordinator();
    }

    /** Wątek globalny: wyspy graczy online z cache Skyllii (bez bazy) — wejście pętli poziomów. */
    private java.util.Collection<UUID> onlineIslandIds() {
        java.util.Set<UUID> islands = new java.util.HashSet<>();
        SkylliaIntegration integration = skyllia;
        if (integration == null) {
            return islands;
        }
        for (Player player : List.copyOf(getServer().getOnlinePlayers())) {
            integration.cachedIslandIdOf(player.getUniqueId()).ifPresent(islands::add);
        }
        return islands;
    }

    /** Awans wyspy: komunikat i dźwięk dla członków online — każdy na własnym wątku (Folia). */
    private void announceIslandLevelUp(
            org.rafalohaki.wpmecore.addons.skyblock.island.IslandLevelService.LevelUp up) {
        SchedulerService tick = scheduler;
        if (tick == null) {
            return;
        }
        tick.global(() -> {
            SkylliaIntegration integration = skyllia;
            if (integration == null) {
                return;
            }
            for (Player player : List.copyOf(getServer().getOnlinePlayers())) {
                if (!integration.cachedIslandIdOf(player.getUniqueId())
                        .map(up.islandId()::equals).orElse(false)) {
                    continue;
                }
                player.getScheduler().run(this, task -> {
                    player.sendMessage(miniMessage.deserialize(
                            "<light_purple><bold>Wyspa awansowała na poziom " + up.level() + "!</bold></light_purple>"
                                    + " <gold>+" + org.rafalohaki.wpmecore.addons.skyblock.shared.Ui.money(up.reward())
                                    + " do banku</gold>"));
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                }, null);
            }
        });
    }

    /**
     * Prestiż wyspy: komunikat dla członków online — każdy na własnym wątku (Folia).
     * Nagroda jest kosmetyczna, więc broadcast mówi tylko o poziomie i tytule.
     */
    private void announceIslandPrestige(IslandPrestigeService.PrestigeUp up) {
        SchedulerService tick = scheduler;
        if (tick == null) {
            return;
        }
        String title = up.title();
        String titleLine = title == null || title.isBlank()
                ? ""
                : " <gray>Nowy tytuł wyspy: <white>" + title + "</white></gray>";
        tick.global(() -> {
            SkylliaIntegration integration = skyllia;
            if (integration == null) {
                return;
            }
            for (Player player : List.copyOf(getServer().getOnlinePlayers())) {
                if (!integration.cachedIslandIdOf(player.getUniqueId())
                        .map(up.islandId()::equals).orElse(false)) {
                    continue;
                }
                player.getScheduler().run(this, task -> {
                    player.sendMessage(miniMessage.deserialize(
                            "<gold><bold>Prestiż wyspy " + up.level() + "!</bold></gold>" + titleLine));
                    player.playSound(player.getLocation(),
                            org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.1f);
                }, null);
            }
        });
    }

    /** Ogłoszenie awansu toru ulepszeń dla członków wyspy online — jak przy prestiżu. */
    private void announceIslandUpgrade(IslandUpgradesService.UpgradeUp up) {
        SchedulerService tick = scheduler;
        if (tick == null) {
            return;
        }
        String trackLabel = switch (up.track()) {
            case SIZE -> "Rozmiar wyspy";
            case MEMBERS -> "Miejsca w zespole";
            case MINIONS -> "Sloty minionków";
        };
        tick.global(() -> {
            SkylliaIntegration integration = skyllia;
            if (integration == null) {
                return;
            }
            for (Player player : List.copyOf(getServer().getOnlinePlayers())) {
                if (!integration.cachedIslandIdOf(player.getUniqueId())
                        .map(up.islandId()::equals).orElse(false)) {
                    continue;
                }
                player.getScheduler().run(this, task -> {
                    player.sendMessage(miniMessage.deserialize(
                            "<aqua><bold>Ulepszenie wyspy:</bold></aqua> <gray>" + trackLabel
                                    + " na poziom <white>" + up.level() + "</white>.</gray>"));
                }, null);
            }
        });
    }

    @org.jetbrains.annotations.Nullable WandService wands() {
        return hubModule == null ? null : hubModule.wands();
    }

    /**
     * Szczęście na kryształy dla wyspy: prestiż × aktywny event rotacyjny.
     * Multiplikatywnie — event „Gorączka Kryształów” ×2 na prestiżu +40%
     * daje ×2.8, a nie sumę procentów.
     */
    private double crystalLuckFor(@NotNull java.util.UUID islandId) {
        double luck = islandPrestige == null ? 1.0 : islandPrestige.crystalLuckMultiplierFor(islandId);
        if (skyBlockEvents != null) {
            luck *= skyBlockEvents.crystalLuckMultiplier();
        }
        return luck;
    }
    void schedulePlayer(Player player, long delayTicks, Runnable action) {
        try {
            player.getScheduler().runDelayed(this, ignored -> {
                if (player.isOnline() && isEnabled()) {
                    action.run();
                }
            }, null, delayTicks);
        } catch (RuntimeException rejected) {
            getLogger().log(Level.FINE,
                    "Player task was rejected for " + player.getUniqueId(), rejected);
        }
    }

    // Package-private for tests
    ProfileStateService profileStateService() {
        return profileStateService;
    }

    IslandProfileDao islandProfileDao() {
        return islandProfileDao;
    }

    IslandCenterMenu islandCenter() {
        return islandCenter;
    }

    /** Menu prestiżu wyspy; {@code null}, gdy {@code island.prestige} wyłączone. */
    IslandPrestigeMenu islandPrestigeMenu() {
        return islandPrestigeMenu;
    }

    IslandCreationCoordinator creationCoordinator() {
        return creationCoordinator;
    }

    IslandCreationMenus islandCreationMenus() {
        return islandCreationMenus;
    }

    IslandGuideMenu islandGuide() {
        return islandGuide;
    }

    /**
     * Identyfikatory trybów wyspy faktycznie włączonych konfiguracją.
     *
     * <p>Komunikat błędu nie może obiecywać trybu wyłączonego: wcześniej miał
     * wpisany na sztywno „classic, oneblock, expedition", a `expedition` jest
     * domyślnie wyłączony (`island.modes.expedition.enabled: false`) — gracz
     * dostawał propozycję trybu, którego nie da się wybrać (znalezione audytem
     * gotowości produkcyjnej 2026-09-11). Lista liczy się z tej samej flagi, na
     * której opiera się menu tworzenia wyspy.
     */
    @NotNull String enabledModeIds() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (IslandMode mode : IslandMode.values()) {
            if (islandModeFlags != null && islandModeFlags.isEnabled(mode)) {
                ids.add(mode.id());
            }
        }
        return ids.isEmpty() ? "(brak wlaczonych trybow)" : String.join(", ", ids);
    }

    IslandModeFlags islandModeFlags() {
        return islandModeFlags;
    }

    ProfilePlaytimeDao playtimeDao() {
        return playtimeDao;
    }

    @Override
    public void onDisable() {
        InventoryOutbox.ShutdownState outboxShutdown = null;
        if (profileCreationListener != null) {
            profileCreationListener.unregister();
            profileCreationListener = null;
        }
        if (oneBlock != null) {
            oneBlock.disable();
        }
        if (recipeService != null) {
            recipeService.unregisterRecipes();
        }
        if (automation != null) {
            automation.disable();
        }
        if (minions != null) {
            minions.disable();
        }
        if (presentationPublisher != null) {
            presentationPublisher.stop();
        }
        if (islandLevels != null) {
            islandLevels.stop();
            islandLevels = null;
        }
        // Zamykanie sezonu było zagnieżdżone w gałęzi publishera: gdy ten nie
        // powstał, harmonogram tablic i ekspansja PlaceholderAPI zostawały żywe
        // po wyłączeniu wtyczki. Warunki są niezależne, więc i gałęzie.
        if (season != null) {
            season.disable();
        }
        if (lifecycleGuard != null) {
            lifecycleGuard.unregister();
        }
        if (memberRemoveListener != null) {
            memberRemoveListener.unregister();
            memberRemoveListener = null;
        }
        if (fluidInitializer != null) {
            fluidInitializer.unregister();
        }
        if (inventoryOutbox != null) {
            outboxShutdown = inventoryOutbox.stop();
        }
        if (operationCoordinator != null) {
            operationCoordinator.stop();
        }
        if (outboxShutdown != null) {
            getLogger().info("Playerdata checkpoint summary: attempts="
                    + outboxShutdown.checkpointCount()
                    + ", total_ms=" + outboxShutdown.checkpointTotalMillis()
                    + ", max_ms=" + outboxShutdown.checkpointMaxMillis()
                    + ", max_queue_depth=" + outboxShutdown.checkpointMaxQueueDepth()
                    + ", queued=" + outboxShutdown.checkpointQueuedCount()
                    + ", in_progress=" + outboxShutdown.checkpointInProgress());
        }
        if (!Bukkit.isStopping() && outboxShutdown != null) {
            String message = "Runtime disable/reload is unsupported for SkyBlockGameplay: "
                    + "after onDisable the inventory guard listener no longer receives events. "
                    + "Keep players offline and perform a full server restart. state="
                    + outboxShutdown;
            if (outboxShutdown.hasUnsettledWork()) {
                getLogger().severe(message);
            } else {
                getLogger().warning(message);
            }
        }
        if (editionRefreshTask != null) {
            try { editionRefreshTask.cancel(); } catch (Exception ignored) {}
            editionRefreshTask = null;
        }
        if (eventsTickTask != null) {
            try { eventsTickTask.cancel(); } catch (Exception ignored) {}
            eventsTickTask = null;
        }
        if (quests != null) {
            quests.stopAndFlush();
        }
        if (vaultHook != null) {
            vaultHook.unregister();
        }
        if (sqlBinding != null) {
            sqlBinding.shutdownIfOwned();
        }
        if (playtimeHeartbeatTask != null) {
            try { playtimeHeartbeatTask.cancel(); } catch (Exception ignored) {}
            playtimeHeartbeatTask = null;
        }
        quests = null;
        weeklyMilestones = null;
        vaultHook = null;
        sqlBinding = null;
        ledger = null;
        skyllia = null;
        mainMenu = null;
        shop = null;
        bank = null;
        inventoryOutbox = null;
        operationCoordinator = null;
        lifecycleGuard = null;
        fluidInitializer = null;
        presentationPublisher = null;
        oneBlock = null;
        automation = null;
        minions = null;
        season = null;
        hubModule = null;
        scheduler = null;
        oneBlockContent = null;
        miniMessage = null;
        minionsConfig = null;
        forgeMenu = null;
        forgeService = null;
        forgeConfig = null;
        gameplayDropsListener = null;
        recipeService = null;
        islandProfileDao = null;
        profileStateService = null;
        islandModeFlags = null;
        creationCoordinator = null;
        islandCenter = null;
        islandCreationMenus = null;
        islandGuide = null;
        playtimeDao = null;
        transitionDao = null;
        snapshotDao = null;
        snapshotService = null;
        recoveryService = null;
        mutationGuard = null;
        playtimeService = null;
        onboardingAnalytics = null;
        backfillService = null;
        profileAccountDao = null;
        profileAccountService = null;
        skyBlockAdminCommands = null;
    }

    /**
     * M1-C: single entry /is create — bare opens selector, variants go through lifecycle authorization.
     * Bare /is teleports home (entity-thread hop); /is panel opens Island Center when an island
     * exists, else the creation selector. No recursive dispatch, unknown type does not create.
     */
    @EventHandler(ignoreCancelled = true)
    public void onIslandCommand(PlayerCommandPreprocessEvent event) {
        SkylliaIntegration bridge = skyllia;
        SkyBlockMenus menu = mainMenu;
        if (bridge == null || menu == null) {
            return;
        }
        String msg = event.getMessage();
        // Bare /is -> szybki teleport na wyspę (UX: Centrum przeniesione na /is panel)
        if (isBareIslandCommand(msg)) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (bridge.islandOf(player.getUniqueId()).isPresent()) {
                // FOLIA-THREADING: performCommand wolno wywołać tylko na wątku
                // encji gracza — hop przez EntityScheduler (wzorzec z tworzenia).
                player.getScheduler().run(SkyBlockGameplay.this, t -> {
                    if (player.isOnline()) player.performCommand("is home");
                }, null);
            } else {
                whenIdentityClear(player, IDENTITY_CONFLICT_CREATE_MESSAGE,
                        () -> menu.openIslandCreationPicker(player));
            }
            return;
        }
        // Bare /is create -> selector (not creation)
        if (isBareCreateCommand(msg)) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (bridge.islandOf(player.getUniqueId()).isPresent()) {
                player.sendMessage(miniMessage.deserialize("<red>Masz już aktywną wyspę. Użyj <yellow>/is panel</yellow> aby otworzyć Centrum Wyspy.</red>"));
                return;
            }
            whenIdentityClear(player, IDENTITY_CONFLICT_CREATE_MESSAGE,
                    () -> menu.openIslandCreationPicker(player));
            return;
        }
        // F19: ta Skyllia NIE MA podkomend `help` ani `bank` (brak klas
        // HelpSubCommand i dodatku bank w jarze 3.0-163). Bez przechwycenia
        // `/is help` odpowiada „Taka podkomenda nie istnieje. Użyj /is help.”,
        // czyli odsyła sam do siebie — pętla dla gracza, który się zgubił.
        // `/is bank` trafiał w to samo, a wskazywał na niego przewodnik postępu.
        if (isIslandSubCommand(msg, "help", "pomoc")) {
            event.setCancelled(true);
            sendBeginnerHelp(event.getPlayer());
            return;
        }
        if (isIslandSubCommand(msg, "bank")) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (bank != null) {
                bank.open(player);
            } else {
                player.sendMessage(miniMessage.deserialize(
                        "<yellow>Bank wyspy otworzysz komendą <white>/bank</white>.</yellow>"));
            }
            return;
        }
        // UX: /is panel -> Centrum Wyspy; bez wyspy kreator jak gołe /is
        if (isPanelCommand(msg)) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (bridge.islandOf(player.getUniqueId()).isPresent()) {
                if (islandCenter != null) islandCenter.open(player);
                else menu.open(player);
            } else {
                whenIdentityClear(player, IDENTITY_CONFLICT_CREATE_MESSAGE,
                        () -> menu.openIslandCreationPicker(player));
            }
            return;
        }
        // /is create <type> -> lifecycle authorization, no recursive dispatch, unknown type does not create
        String createType = parseCreateType(msg);
        if (createType != null) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (creationCoordinator != null) {
                var state = creationCoordinator.stateOf(player.getUniqueId());
                if (state == org.rafalohaki.wpmecore.addons.skyblock.island.IslandCreationState.CREATING
                        || state == org.rafalohaki.wpmecore.addons.skyblock.island.IslandCreationState.INITIALIZING) {
                    var op = creationCoordinator.operationOf(player.getUniqueId());
                    getLogger().info("Island create still running player=" + player.getUniqueId()
                            + " state=" + state.name() + (op != null ? " op=" + op.operationId() : ""));
                    player.sendMessage(miniMessage.deserialize("<yellow>Twoja wyspa już się tworzy — poczekaj chwilę...</yellow>"));
                    return;
                }
            }
            // F16: konflikt nicku sprawdzany asynchronicznie; reszta ścieżki
            // tworzenia jest kontynuacją, więc wraca na wątek encji gracza.
            whenIdentityClear(player, IDENTITY_CONFLICT_MUTATION_MESSAGE, () -> {
                if (bridge.islandOf(player.getUniqueId()).isPresent()) {
                    player.sendMessage(miniMessage.deserialize("<red>Masz już aktywną wyspę. Możesz mieć tylko jedną.</red>"));
                    return;
                }
                if (profileStateService != null) {
                    try {
                        boolean hasActive = profileStateService.activeMembership(player.getUniqueId())
                                .get(1L, java.util.concurrent.TimeUnit.SECONDS).isPresent();
                        if (hasActive) {
                            player.sendMessage(miniMessage.deserialize("<red>Masz już aktywną wyspę (profil). Możesz mieć tylko jedną.</red>"));
                            return;
                        }
                    } catch (Exception e) {
                        player.sendMessage(miniMessage.deserialize("<red>Nie można zweryfikować profilu wyspy — spróbuj ponownie za chwilę.</red>"));
                        getLogger().log(java.util.logging.Level.WARNING, "Profile check failed for " + player.getUniqueId(), e);
                        return;
                    }
                }
                if (creationCoordinator != null) {
                    creationCoordinator.create(player, createType).whenComplete((result, ex) -> {
                        getServer().getGlobalRegionScheduler().run(SkyBlockGameplay.this, task -> {
                            if (ex != null) {
                                player.sendMessage(miniMessage.deserialize("<red>Nie udało się utworzyć wyspy: " + ex.getMessage() + "</red>"));
                                return;
                            }
                            if (result == null) return;
                            switch (result.kind()) {
                                case SUCCESS -> {
                                    player.sendMessage(miniMessage.deserialize("<green>✅ Wyspa gotowa! Przenoszę Cię na nią.</green>"));
                                    // F19: ta sama instrukcja „co teraz”, co po utworzeniu
                                    // wyspy z menu — inaczej ścieżka /is create <tryb>
                                    // zostawia gracza na wyspie bez słowa wyjaśnienia.
                                    if (islandCreationMenus != null) {
                                        islandCreationMenus.sendFirstSteps(player, result.mode());
                                    }
                                    getLogger().info("Island create OK player=" + player.getUniqueId()
                                            + " mode=" + result.mode().id() + " op=" + result.operationId());
                                    // FOLIA-THREADING: performCommand wolno wywołać tylko
                                    // na wątku encji gracza — hop z globalnego schedulera
                                    // dawał fatal Dispatchując komendę asynchronicznie.
                                    player.getScheduler().runDelayed(SkyBlockGameplay.this, t -> {
                                        if (player.isOnline()) player.performCommand("is home");
                                    }, null, 25);
                                }
                                case MODE_DISABLED -> player.sendMessage(miniMessage.deserialize("<red>" + result.message() + "</red>"));
                                case ALREADY_HAS_ISLAND -> player.sendMessage(miniMessage.deserialize("<red>" + result.message() + "</red>"));
                                case UNKNOWN_TYPE -> player.sendMessage(miniMessage.deserialize("<red>"
                                        + result.message() + " Dostępne: " + enabledModeIds()
                                        + ". Nie utworzono wyspy.</red>"));
                                case ALREADY_CREATING -> player.sendMessage(miniMessage.deserialize("<yellow>" + result.message() + "</yellow>"));
                                case FAILURE -> player.sendMessage(miniMessage.deserialize("<red>" + result.message() + "</red>"));
                            }
                        });
                    });
                } else {
                    player.sendMessage(miniMessage.deserialize("<red>Tworzenie wyspy chwilowo niedostępne.</red>"));
                }
            });
            return;
        }
        // Other mutating commands (accept etc.) -> original guard
        handleIslandMutatingCommand(event);
    }

    private void handleIslandMutatingCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().toLowerCase(java.util.Locale.ROOT);
        // M1-D: destructive transitions (leave/kick/delete/reset) must snapshot before cleaning and block new mutations
        boolean isAccept = msg.matches("^/(is|skyllia|ob)\\s+(accept|join)(\\s+.*)?");
        boolean isLeave = msg.matches("^/(is|skyllia|ob)\\s+(leave)(\\s+.*)?");
        boolean isKick = msg.matches("^/(is|skyllia|ob)\\s+(kick|remove)(\\s+.*)?");
        boolean isDelete = msg.matches("^/(is|skyllia|ob)\\s+(delete|remove|del)(\\s+.*)?");
        boolean isReset = msg.matches("^/(is|skyllia|ob)\\s+(reset)(\\s+.*)?");
        if (isLeave || isKick || isDelete || isReset) {
            handleDestructiveIslandCommand(event, msg, isLeave, isKick, isDelete, isReset);
            // Still run accept guard after? No, destructive path already handled, return to let Skyllia process unless we blocked
            // We do not cancel here unless identity conflict or blocked mutation; snapshot is async but fail-closed still allows command
            // Fall through to accept guard only if not destructive
            if (!isAccept) return;
        }
        if (!isAccept) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        Long passedAt = guardPassed.get(playerUuid);
        boolean guardAlreadyPassed = passedAt != null
                && System.currentTimeMillis() - passedAt < GUARD_PASSED_TTL_MS;
        if (guardAlreadyPassed) {
            guardPassed.remove(playerUuid);
        } else {
            // F16: tożsamość sprawdzana bez blokowania wątku regionu. Komendę trzeba
            // anulować od razu, bo odpowiedź przyjdzie później, niż Skyllia zdążyłaby
            // ją obsłużyć; po czystym wyniku wraca przez performCommand ze znacznikiem
            // TTL, który omija ten przebieg — pętli nie ma, bo znacznik zdejmowany
            // jest tuż wyżej, na wejściu w drugi przebieg.
            event.setCancelled(true);
            String originalCommand = event.getMessage();
            whenIdentityClear(player, IDENTITY_CONFLICT_MUTATION_MESSAGE,
                    () -> retryPastGuard(player, originalCommand));
            return;
        }
        SkylliaIntegration bridge = skyllia;
        if (bridge != null && bridge.islandOf(player.getUniqueId()).isPresent()) {
            event.setCancelled(true);
            player.sendMessage(miniMessage.deserialize("<red>Masz już aktywną wyspę. Możesz mieć tylko jedną.</red>"));
            return;
        }
        ProfileStateService profiles = profileStateService;
        if (profiles != null) {
            try {
                boolean hasActive = profiles.activeMembership(player.getUniqueId())
                        .get(1L, java.util.concurrent.TimeUnit.SECONDS)
                        .isPresent();
                if (hasActive) {
                    event.setCancelled(true);
                    player.sendMessage(miniMessage.deserialize("<red>Masz już aktywną wyspę (profil). Możesz mieć tylko jedną.</red>"));
                }
            } catch (Exception e) {
                event.setCancelled(true);
                player.sendMessage(miniMessage.deserialize("<red>Nie można zweryfikować profilu wyspy — spróbuj ponownie za chwilę.</red>"));
                getLogger().log(java.util.logging.Level.WARNING, "Profile check failed for " + player.getUniqueId(), e);
            }
        }
    }

    /** M1-D: marker komendy ponawianej po async guard check — pomija drugi przebieg guarda. */
    /** M1-D: UUID-y z przepuszczonym guardem (10 s TTL) — pomija drugi przebieg guarda. */
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> guardPassed =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long GUARD_PASSED_TTL_MS = 10_000L;

    private void handleDestructiveIslandCommand(PlayerCommandPreprocessEvent event, String msg, boolean isLeave, boolean isKick, boolean isDelete, boolean isReset) {
        // M1-D fix (delete-confirm): /is delete WITHOUT "confirm" only makes Skyllia
        // print the irreversible-action prompt — no mutation happens, so there is
        // nothing to snapshot or journal. Journaling the prompt step used to insert
        // a result=NULL row that the guard then used to block the actual
        // "/is delete confirm" forever (only rejoin/restart recovery cleared it).
        // The journal is now written solely on the confirmed invocation, i.e.
        // immediately before the real mutation it is meant to protect.
        if (isDelete && !isKick
                && !msg.matches("^/(is|skyllia|ob)\\s+(delete|remove|del)\\s+confirm(\\s+.*)?")) {
            return;
        }
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        Long passedAt = guardPassed.get(playerUuid);
        boolean guardAlreadyPassed = passedAt != null
                && System.currentTimeMillis() - passedAt < GUARD_PASSED_TTL_MS;
        if (guardAlreadyPassed) {
            guardPassed.remove(playerUuid);
        } else {
            // M1-D/F16: tożsamość i strażnik mutacji w JEDNYM przebiegu, oba bez
            // blokowania wątku regionu. Dawniej tożsamość czekała tu 500 ms na tej
            // samej dwuwątkowej puli SQL, do której chwilę później szedł strażnik,
            // i — stojąc przed bramką TTL — była pytana po raz drugi także przy
            // ponowieniu. Event anulowany natychmiast; po przejściu obu sprawdzeń
            // komenda wraca przez performCommand na wątku encji gracza.
            event.setCancelled(true);
            String originalCommand = event.getMessage();
            whenIdentityClear(player, IDENTITY_CONFLICT_MUTATION_MESSAGE,
                    () -> whenMutationAllowed(player, originalCommand));
            return;
        }
        // Snapshot safety before cleaning profile state (M1-D)
        if (snapshotService != null && transitionDao != null && profileStateService != null) {
            String type = isDelete ? "DELETE" : isReset ? "RESET" : isLeave ? "LEAVE" : "KICK";
            profileStateService.activeMembership(player.getUniqueId()).whenComplete((opt, err) -> {
                if (opt != null && opt.isPresent()) {
                    var m = opt.get();
                    String correlation = org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileRecoveryService.correlationFor(player.getUniqueId(), type);
                    String opId = org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileRecoveryService.newOperationId(correlation);
                    // Snapshot before clean (serverScope is inside snapshotService)
                    snapshotService.snapshotBeforeClean(m.islandId(), player.getUniqueId(), type, opId, player)
                            .thenCompose(snap -> {
                                String checkpoint = snap.created() ? "SNAPSHOT_WRITTEN" : "SNAPSHOT_FAILED";
                                return transitionDao.create(opId, m.islandId(), player.getUniqueId(), type, m.status().name(), "CLEANING", checkpoint)
                                        .thenApply(ok -> opId);
                            })
                            .whenComplete((op, ex2) -> {
                                if (ex2 != null) {
                                    getLogger().log(java.util.logging.Level.WARNING, "Failed to journal destructive transition " + type + " for " + player.getUniqueId(), ex2);
                                } else {
                                    getLogger().info("Journaled destructive transition " + type + " op=" + op + " player=" + player.getUniqueId());
                                    if (onboardingAnalytics != null) {
                                        onboardingAnalytics.emit(type.toLowerCase() + "_initiated", player.getUniqueId(), m.islandId(), null, correlation, op, java.util.Map.of("stage", type.toLowerCase()));
                                    }
                                }
                            });
                }
            });
        }
        // Do not cancel: let Skyllia handle the actual island mutation, but we have journaled and will block new outbox/ledger mutations via guard
    }

    /**
     * M1-D/F16: strażnik mutacji pytany obietnicą, nie {@code get()}. Kontynuacja
     * wraca na wątek encji gracza — {@code performCommand} i sam gracz wymagają
     * JEGO regionu, a globalny scheduler tego nie gwarantuje. Limitu czasu nie ma
     * tu po raz drugi: siedzi w samym strażniku (ProfileMutationGuard.VERIFY_TIMEOUT_SECONDS).
     */
    private void whenMutationAllowed(@NotNull Player player, @NotNull String originalCommand) {
        org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileMutationGuard guard = mutationGuard;
        if (guard == null) {
            retryPastGuard(player, originalCommand);
            return;
        }
        UUID playerUuid = player.getUniqueId();
        guard.isBlockedForPlayer(playerUuid).whenComplete((blocked, err) ->
                player.getScheduler().run(this, task -> {
                    if (err != null) {
                        getLogger().log(Level.WARNING,
                                "Mutation guard verification failed for " + playerUuid + " — fail-closed", err);
                        player.sendMessage(miniMessage.deserialize("<red>Nie można zweryfikować blokady mutacji — spróbuj ponownie za chwilę.</red>"));
                        return;
                    }
                    if (Boolean.TRUE.equals(blocked)) {
                        player.sendMessage(miniMessage.deserialize("<red>Operacja zablokowana: masz oczekującą operację usuwania/opuszczania wyspy. Poczekaj na zakończenie.</red>"));
                        return;
                    }
                    retryPastGuard(player, originalCommand);
                }, () -> getLogger().warning("Guard retry discarded — player retired")));
    }

    /**
     * Ponawia oryginalną komendę ze znacznikiem TTL, który omija sprawdzenia przy
     * drugim przebiegu. Ponawiana jest treść z eventu, a nie {@code msg}: ten jest
     * sprowadzony do małych liter na potrzeby dopasowania wzorców i psuł wielkość
     * liter w argumentach (np. nick w {@code /is kick Nick}).
     */
    private void retryPastGuard(@NotNull Player player, @NotNull String originalCommand) {
        guardPassed.put(player.getUniqueId(), System.currentTimeMillis());
        player.performCommand(originalCommand.substring(1));
    }

    /**
     * Wynik sprawdzenia konfliktu nicku. Rozdzielenie „konflikt" od „nie
     * zweryfikowano" jest celowe: pierwsze gracz załatwia z administracją,
     * drugie mija samo i wystarczy ponowić za chwilę.
     */
    private enum IdentityStatus { CLEAR, CONFLICTED, UNVERIFIED }

    /**
     * Bezpiecznik na zawieszoną bazę, nie budżet czasu gracza — nikt na to nie
     * czeka wątkiem. Dlatego jest dłuższy niż {@code busy_timeout=5000} SQLite'a,
     * a nie krótszy, jak dawne 500 ms.
     */
    private static final long IDENTITY_VERIFY_TIMEOUT_SECONDS = 15L;

    private static final String IDENTITY_UNVERIFIED_MESSAGE =
            "<red>Nie można zweryfikować tożsamości konta — spróbuj ponownie za chwilę.</red>";
    private static final String IDENTITY_CONFLICT_CREATE_MESSAGE =
            "<red>Nie można utworzyć wyspy: wykryto konflikt nicku. Skontaktuj się z administracją.</red>";
    private static final String IDENTITY_CONFLICT_MUTATION_MESSAGE =
            "<red>Nie można wykonać tej operacji: wykryto konflikt nicku. Skontaktuj się z administracją.</red>";

    /**
     * F16: sprawdzenie konfliktu nicku bez blokowania wątku wołającego. Dawniej
     * było tu {@code isConflicted(uuid).get(500 ms)} wołane z obsługi komendy,
     * czyli z wątku regionu Folii — a zapytanie szło do tej samej dwuwątkowej
     * puli SQL, przez którą przechodzi strażnik mutacji. Pod obciążeniem limit
     * mijał z gwarancją, a region stał do dziesięciu tików.
     *
     * <p>Brak usługi (albo brak Bukkita w testach jednostkowych) to fail-open,
     * dokładnie jak przed zmianą: bez IdentityService nie ma czego weryfikować.
     * Obietnica nigdy nie kończy się wyjątkiem — porażka to {@code UNVERIFIED}.
     */
    private @NotNull CompletableFuture<IdentityStatus> identityStatus(@NotNull UUID uuid) {
        try {
            org.rafalohaki.wpmecore.api.identity.IdentityService identity = getServer().getServicesManager()
                    .load(org.rafalohaki.wpmecore.api.identity.IdentityService.class);
            if (identity == null) {
                return CompletableFuture.completedFuture(IdentityStatus.CLEAR);
            }
            return identity.isConflicted(uuid)
                    .orTimeout(IDENTITY_VERIFY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                    .handle((conflicted, failure) -> {
                        if (failure != null) {
                            getLogger().log(Level.WARNING,
                                    "Identity conflict check failed for " + uuid + " — fail-closed", failure);
                            return IdentityStatus.UNVERIFIED;
                        }
                        return Boolean.TRUE.equals(conflicted) ? IdentityStatus.CONFLICTED : IdentityStatus.CLEAR;
                    });
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(IdentityStatus.CLEAR);
        }
    }

    /**
     * Uruchamia {@code onClear} tylko wtedy, gdy nick gracza nie jest w konflikcie.
     * Polityka fail-closed bez zmian — i konflikt, i nieudana weryfikacja wstrzymują
     * operację; różni je komunikat.
     *
     * <p>Gdy odpowiedź jest gotowa od ręki (trafienie w cache IdentityService albo
     * brak usługi), kontynuacja biegnie w miejscu — wciąż jesteśmy na wątku
     * wołającego. W przeciwnym razie czekanie idzie poza wątek, a kontynuacja wraca
     * na wątek encji gracza (async-to-region handoff, ten sam wzorzec co strażnik
     * mutacji niżej).
     */
    private void whenIdentityClear(@NotNull Player player, @NotNull String conflictMessage,
                                   @NotNull Runnable onClear) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<IdentityStatus> check = identityStatus(uuid);
        if (check.isDone()) {
            applyIdentityStatus(player, check.join(), conflictMessage, onClear);
            return;
        }
        check.thenAccept(status -> player.getScheduler().run(this,
                task -> applyIdentityStatus(player, status, conflictMessage, onClear),
                () -> getLogger().warning("Identity check discarded — player retired: " + uuid)));
    }

    private void applyIdentityStatus(@NotNull Player player, @NotNull IdentityStatus status,
                                     @NotNull String conflictMessage, @NotNull Runnable onClear) {
        switch (status) {
            case CLEAR -> onClear.run();
            case CONFLICTED -> player.sendMessage(miniMessage.deserialize(conflictMessage));
            case UNVERIFIED -> player.sendMessage(miniMessage.deserialize(IDENTITY_UNVERIFIED_MESSAGE));
        }
    }

    /**
     * True only for Skyllia's island command typed with no arguments — the single
     * form that would otherwise create an island without asking which mode.
     * Anything carrying arguments is left alone, which is what keeps the picker's
     * own {@code is create <type>} from re-entering this handler.
     */
    static boolean isBareIslandCommand(@NotNull String message) {
        if (message.isEmpty() || message.charAt(0) != '/') {
            return false;
        }
        String body = message.substring(1).trim();
        if (body.isEmpty()) {
            return false;
        }
        String[] parts = body.split("\\s+");
        return parts.length == 1 && ISLAND_COMMANDS.contains(parts[0].toLowerCase(Locale.ROOT));
    }

    static boolean isBareCreateCommand(@NotNull String message) {
        if (message.isEmpty() || message.charAt(0) != '/') return false;
        String body = message.substring(1).trim().toLowerCase(java.util.Locale.ROOT);
        String[] parts = body.split("\\s+");
        if (parts.length != 2) return false;
        if (!ISLAND_COMMANDS.contains(parts[0])) return false;
        return parts[1].equals("create") || parts[1].equals("stworz") || parts[1].equals("utworz");
    }

    /**
     * True only for the exactly-two-token island {@code panel} form — opens
     * Island Center (or the picker without an island). Longer forms such as
     * {@code /is panels} or {@code /is panel x} fall through untouched, which
     * keeps unrelated Skyllia subcommands working.
     */
    static boolean isPanelCommand(@NotNull String message) {
        if (message.isEmpty() || message.charAt(0) != '/') {
            return false;
        }
        String[] parts = message.substring(1).trim().split("\\s+");
        return parts.length == 2
                && ISLAND_COMMANDS.contains(parts[0].toLowerCase(Locale.ROOT))
                && (parts[1].equalsIgnoreCase("panel") || parts[1].equalsIgnoreCase("centrum"));
    }

    /**
     * F19: dokładnie dwa człony, pierwszy to alias wyspy, drugi to jedna
     * z podanych podkomend. Ten sam kształt co {@link #isPanelCommand}.
     */
    static boolean isIslandSubCommand(@NotNull String message, @NotNull String... subCommands) {
        if (message.isEmpty() || message.charAt(0) != '/') {
            return false;
        }
        String[] parts = message.substring(1).trim().split("\\s+");
        if (parts.length != 2 || !ISLAND_COMMANDS.contains(parts[0].toLowerCase(Locale.ROOT))) {
            return false;
        }
        for (String candidate : subCommands) {
            if (parts[1].equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * F19: siedem linijek dla kogoś, kto pierwszy raz gra w Minecrafta. Bez
     * listy wszystkich komend — ta i tak nikomu nie pomaga, gdy nie wiadomo,
     * od czego zacząć.
     */
    private void sendBeginnerHelp(@NotNull Player player) {
        boolean hasIsland = skyllia != null && skyllia.islandOf(player.getUniqueId()).isPresent();
        player.sendMessage(miniMessage.deserialize(
                "<gold><bold>Pomoc SkyBlock</bold></gold> <gray>— komendy wpisujesz na czacie (klawisz T).</gray>"));
        player.sendMessage(miniMessage.deserialize(hasIsland
                ? "<yellow>/is</yellow> <gray>— wraca na Twoją wyspę.</gray>"
                : "<yellow>/is</yellow> <gray>— zakłada Twoją wyspę. Zacznij od tego.</gray>"));
        player.sendMessage(miniMessage.deserialize(
                "<yellow>/menu</yellow> <gray>— wszystko w jednym oknie: sklep, zadania, bank.</gray>"));
        player.sendMessage(miniMessage.deserialize(
                "<yellow>/sklep</yellow> <gray>— sprzedajesz to, co wykopałeś, i kupujesz sprzęt.</gray>"));
        player.sendMessage(miniMessage.deserialize(
                "<yellow>/zadania</yellow> <gray>— trzy cele na dziś, z nagrodą dla wyspy.</gray>"));
        player.sendMessage(miniMessage.deserialize(
                "<yellow>/bank</yellow> <gray>— wspólne konto wyspy.</gray>"));
        player.sendMessage(miniMessage.deserialize(
                "<yellow>/spawn</yellow> <gray>— wraca na plac startowy z przewodnikami.</gray>"));
    }

    /** Returns type string if message is /is create <type> with exactly 3 tokens, else null. */
    static String parseCreateType(@NotNull String message) {
        if (message.isEmpty() || message.charAt(0) != '/') return null;
        String body = message.substring(1).trim();
        if (body.isEmpty()) return null;
        String[] parts = body.split("\\s+");
        if (parts.length != 3) return null;
        if (!ISLAND_COMMANDS.contains(parts[0].toLowerCase(Locale.ROOT))) return null;
        String sub = parts[1].toLowerCase(java.util.Locale.ROOT);
        if (!sub.equals("create") && !sub.equals("stworz") && !sub.equals("utworz")) return null;
        return parts[2];
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        SkylliaIntegration bridge = skyllia;
        LedgerService currentLedger = ledger;
        if (bridge == null || currentLedger == null) {
            return;
        }
        SkyBlockHub currentHub = hubModule == null ? null : hubModule.hub();
        bridge.islandOf(player.getUniqueId());
        // M1-D: reconnect in every non-terminal state — if pending CREATE exists, status instead of new create
        if (recoveryService != null) {
            recoveryService.pendingCreateFor(player.getUniqueId()).whenComplete((pending, err) -> {
                if (pending != null && pending.isPresent()) {
                    var tx = pending.get();
                    getLogger().info("Reconnect for " + player.getUniqueId() + " has pending CREATE " + tx.operationId() + " checkpoint=" + tx.checkpoint());
                    getServer().getGlobalRegionScheduler().run(SkyBlockGameplay.this, t -> {
                        if (player.isOnline()) {
                            player.sendMessage(miniMessage.deserialize("<yellow>Twoje tworzenie wyspy zostało przerwane — wznawiam, gdzie skończyłem...</yellow>"));
                            recoveryService.resume(tx.operationId()).whenComplete((res, e2) -> {
                                if (res != null) getLogger().info("Resume on reconnect " + tx.operationId() + " -> " + res.kind());
                            });
                        }
                    });
                }
            });
            // M1-D: reconnect mid-delete/reset/leave — dokończ destrukcyjną operację
            if (recoveryService != null) {
                recoveryService.pendingDestructiveFor(player.getUniqueId()).whenComplete((pending, err2) -> {
                    if (pending != null && pending.isPresent()) {
                        var tx = pending.get();
                        getLogger().info("Reconnect for " + player.getUniqueId() + " has pending " + tx.transitionType() + " " + tx.operationId() + " checkpoint=" + tx.checkpoint() + " — resuming");
                        recoveryService.resume(tx.operationId()).whenComplete((res, e3) -> {
                            if (res != null) getLogger().info("Resume on reconnect " + tx.operationId() + " -> " + res.kind());
                        });
                    }
                });
            }
            // Start playtime session for active profile (idempotent)
            if (profileStateService != null && playtimeDao != null) {
                profileStateService.activeMembership(player.getUniqueId()).whenComplete((opt, er) -> {
                    if (opt != null && opt.isPresent()) {
                        playtimeDao.startSession(opt.get().islandId(), player.getUniqueId(), System.currentTimeMillis())
                                .exceptionally(ex2 -> { getLogger().log(java.util.logging.Level.FINE, "Playtime start failed for " + player.getUniqueId(), ex2); return false; });
                    }
                });
            }
            // Onboarding analytics without IP
            if (onboardingAnalytics != null) {
                String corr = org.rafalohaki.wpmecore.addons.skyblock.profile.ProfileRecoveryService.correlationFor(player.getUniqueId(), "onboarding");
                onboardingAnalytics.onboardingStarted(player.getUniqueId(), "join:" + player.getUniqueId() + ":" + System.currentTimeMillis(), corr);
            }
        }
        currentLedger.ensurePlayer(player.getUniqueId()).whenComplete((result, failure) -> {
            if (failure != null) {
                getLogger().log(Level.SEVERE,
                        "Failed to initialize player ledger account " + player.getUniqueId(), failure);
                schedulePlayer(player, 1L, () -> player.sendMessage(Component.text(
                        "Nie udało się wczytać konta ekonomii. Skontaktuj się z administracją.",
                        NamedTextColor.RED)));
                return;
            }
            long onboardingDelay = currentHub == null
                    ? 40L
                    : currentHub.onboardingDelayTicks();
            schedulePlayer(player, onboardingDelay, () -> {
                SkyBlockMenus menu = mainMenu;
                Byte completed = player.getPersistentDataContainer().get(
                        onboardingKey, PersistentDataType.BYTE);
                if (completed == null && menu != null) {
                    boolean menuOpened = currentHub != null
                            && currentHub.openMenuOnFirstJoin();
                    menu.onboarding(player, menuOpened);
                    player.getPersistentDataContainer().set(
                            onboardingKey, PersistentDataType.BYTE, (byte) 1);
                    // Paper persists PDC through its normal player save/quit
                    // cycle; forcing saveData() here would perform disk I/O on
                    // the player's Folia region thread. Re-showing onboarding
                    // after a process crash is harmless and preferable.
                    if (!menuOpened && currentHub != null
                            && currentHub.openHelpDialogOnFirstJoin()) {
                        // Dialog /pomoc jako mapa serwera dla nowego gracza —
                        // komenda zostaje jedyną ścieżką prawdy, a przy błędzie
                        // klienta fallback wypisuje wersję czatową.
                        new PomocDialog(scheduler(), miniMessage(), getLogger())
                                .open(player, () -> player.sendMessage(
                                        miniMessage().deserialize(
                                                SkyBlockCommands.POMOC)));
                    }
                } else {
                    player.sendActionBar(Component.text(
                            "Centrum gry: /menu  •  Dzisiejsze cele: /zadania",
                            NamedTextColor.YELLOW));
                }
            });
            SkyBlockPresentationPublisher publisher = presentationPublisher;
            if (publisher != null) {
                publisher.refresh(player.getUniqueId());
            }
        });
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // M1-D: idempotent session close without counting offline time
        if (playtimeDao != null && profileStateService != null) {
            profileStateService.activeMembership(player.getUniqueId()).whenComplete((opt, err) -> {
                if (opt != null && opt.isPresent()) {
                    playtimeDao.endSession(opt.get().islandId(), player.getUniqueId(), System.currentTimeMillis())
                            .exceptionally(ex -> { getLogger().log(java.util.logging.Level.FINE, "Playtime end failed for " + player.getUniqueId(), ex); return false; });
                } else {
                    // Try close any session for this player across all profiles (crash case where membership already cleared)
                    // We attempt to close via transitionDao player lookup: find pending profile via snapshot?
                    // For now, attempt to reconcile via playtimeDao direct: try all islands? Instead just reconcile crashed sessions globally
                    playtimeDao.reconcileCrashedSessions(System.currentTimeMillis(), 5 * 60 * 1000L).exceptionally(ex -> null);
                }
            });
        }
        // Also keep inventory outbox fail-closed until rejoin (already handled in InventoryOutbox.onQuit)
    }

    @EventHandler
    public void onKick(org.bukkit.event.player.PlayerKickEvent event) {
        // Treat kick same as quit for playtime reconciliation
        onQuit(new org.bukkit.event.player.PlayerQuitEvent(event.getPlayer(), event.getReason(), org.bukkit.event.player.PlayerQuitEvent.QuitReason.KICKED));
    }


}
