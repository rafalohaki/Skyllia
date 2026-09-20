package pl.b2t.skylliaminions;

import fr.euphyllia.skyllia.api.event.SkyblockDeleteEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.WpmeAPI;
import org.rafalohaki.wpmecore.api.util.AddonBootstrap;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.api.service.SqlService;

/**
 * Addon Skylli: system minionków wyspy. Przeniesiony z WpmeCore — storage
 * zostaje w tej samej tabeli {@code wpme_sb_minions} współdzielonej bazy
 * (SqlService z WpmeAPI), więc istniejące minionki działają bez migracji.
 */
public final class SkylliaMinions extends JavaPlugin {

    private AddonBootstrap.SqlBinding sqlBinding;
    private MinionService service;
    private MinionDisplayRenderer renderer;
    private MinionListener listener;
    private MinionMenu menu;

    public @Nullable MinionService minions() {
        return service;
    }

    @Override
    public void onEnable() {
        WpmeAPI api = Bukkit.getServicesManager().load(WpmeAPI.class);
        if (api == null) {
            getLogger().severe("Brak WpmeAPI — WpmeCore nie wstał. Wyłączam.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        AddonBootstrap.SqlBinding binding = AddonBootstrap.resolveSqlService(this, api);
        if (binding == null) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.sqlBinding = binding;
        SqlService sql = binding.service();
        CustomItemService customItems = api.customItemService();
        if (customItems == null) {
            getLogger().warning("CustomItemService niedostępny — paliwa/dropy custom nie zadziałają.");
        }
        MenuService menus = api.createMenuService(this);
        Economy economy = hookEconomy();
        MiniMessage miniMessage = MiniMessage.miniMessage();

        MinionsConfig config;
        try {
            config = MinionsConfig.load(this, customItems);
        } catch (IllegalArgumentException failure) {
            getLogger().severe("Invalid minions.yml: " + failure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        MinionDao dao = new MinionDao(sql);
        this.service = new MinionService(this, dao, config, customItems);
        this.renderer = new MinionDisplayRenderer(this, config, miniMessage);
        this.service.setCycleObserver(renderer);
        this.menu = new MinionMenu(this, menus, miniMessage, service, economy,
                config, customItems, renderer);
        this.listener = new MinionListener(this, service, renderer, menu, config, miniMessage);
        wirePrestigeSlots(sql);

        service.initialize();
        service.startTicking();
        getServer().getPluginManager().registerEvents(listener, this);
        getServer().getPluginManager().registerEvents(new IslandDeleteGuard(), this);
        getLogger().info("SkylliaMinions enabled");
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.stopTicking();
        }
        if (sqlBinding != null) {
            sqlBinding.shutdownIfOwned();
        }
    }

    /** Wyspa skasowana → minionki schodzą z rejestru i znika ich render. */
    private final class IslandDeleteGuard implements Listener {
        @EventHandler
        public void onIslandDelete(SkyblockDeleteEvent event) {
            var island = event.getIsland();
            if (island != null) {
                renderer.despawnAll(service.unregisterByIsland(island.getId()));
            }
        }
    }

    /**
     * Sloty +prestiż: poziom wyspy czytamy z {@code wpme_sb_island_prestige}
     * (współdzielona baza — zapisuje ją SkyBlockGameplay przy zakupie), co ile
     * poziomów daje slot definiuje {@code prestige.minion-slots-every-levels}.
     */
    private void wirePrestigeSlots(@NotNull SqlService sql) {
        int everyLevels = Math.max(0, getConfig().getInt("prestige.minion-slots-every-levels", 0));
        if (everyLevels <= 0) {
            return;
        }
        listener.setIslandExtraSlots(new PrestigeSlots(sql, everyLevels).resolver());
        getLogger().info("Sloty prestiżu dopięte (wpme_sb_island_prestige, co " + everyLevels + " poziomy).");
    }

    private @Nullable Economy hookEconomy() {
        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }
}
