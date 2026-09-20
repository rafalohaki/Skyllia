package pl.b2t.skylliaminions;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import fr.euphyllia.skyllia.api.event.SkyblockDeleteEvent;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
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
        registerCommands(config, miniMessage);
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

    /**
     * `/minionki daj <gracz> <typ>` — konsolowa ścieżka wręczania minionków.
     * Kuźnia (forge.yml `result-command`) wywołuje ją z konsoli; wcześniej
     * receptury dawały `ecopets give`, czyli kosmetycznego peta bez produkcji
     * — minionek musi mieć PDC kontraktu (MinionItem), inaczej nie stawia się
     * i nie pracuje.
     */
    private void registerCommands(@NotNull MinionsConfig config, @NotNull MiniMessage miniMessage) {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(Commands.literal("minionki")
                        .requires(source -> source.getSender().hasPermission("skylliaminions.admin"))
                        .then(Commands.literal("daj")
                                .then(Commands.argument("gracz", StringArgumentType.word())
                                        .then(Commands.argument("typ", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    config.minions().keySet().forEach(builder::suggest);
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> giveMinion(ctx.getSource().getSender(),
                                                        StringArgumentType.getString(ctx, "gracz"),
                                                        StringArgumentType.getString(ctx, "typ"),
                                                        config, miniMessage)))))
                        .build(), "Wręcz minionka graczowi (kuźnia/admin)", java.util.List.of()));
    }

    private int giveMinion(@NotNull CommandSender sender, @NotNull String targetName,
                           @NotNull String typeId, @NotNull MinionsConfig config,
                           @NotNull MiniMessage miniMessage) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(miniMessage.deserialize(
                    "<red>Gracz " + targetName + " nie jest online.</red>"));
            return 0;
        }
        MinionsConfig.TypeDef type = config.type(typeId);
        if (type == null) {
            sender.sendMessage(miniMessage.deserialize("<red>Nieznany typ minionka: " + typeId
                    + ". Dostępne: " + String.join(", ", config.minions().keySet()) + "</red>"));
            return 0;
        }
        // Ekwipunek ruszamy tylko na wątku regionu gracza (Folia).
        target.getScheduler().run(this, task -> {
            ItemStack item = MinionItem.create(type, 1, false, null, 0, "", miniMessage);
            target.getInventory().addItem(item)
                    .values().forEach(leftover ->
                            target.getWorld().dropItemNaturally(target.getLocation(), leftover));
            sender.sendMessage(miniMessage.deserialize("<green>Wręczono minionka " + type.id()
                    + " graczowi " + target.getName() + ".</green>"));
        }, null);
        return Command.SINGLE_SUCCESS;
    }

    private @Nullable Economy hookEconomy() {
        RegisteredServiceProvider<Economy> rsp =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }
}
