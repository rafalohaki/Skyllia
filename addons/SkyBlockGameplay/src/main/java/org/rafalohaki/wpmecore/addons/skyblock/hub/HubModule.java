package org.rafalohaki.wpmecore.addons.skyblock.hub;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.season.LeaderboardSettings;
import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.shop.ServerShop;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.service.MenuService;

/**
 * Wiązanie spawnu: podesty i oprawa tablic, skocznie, różdżki.
 * Hologramy stawia FancyHolograms; SkyBlockHub sprząta osierocone encje.
 *
 * <p>Skrzynie odeszły do CrazyCrates (reference/skyblock-crates), więc podest
 * tablic nie sąsiaduje już z niczym, co też podmienia goły teren.
 */
public final class HubModule {

    private final JavaPlugin plugin;
    private final LeaderboardSettings leaderboards;
    private final SkyBlockHub hub;
    private final WandService wands;

    public HubModule(@NotNull JavaPlugin plugin, @NotNull MenuService menus,
                     @NotNull MiniMessage miniMessage, @NotNull HubSettings hubSettings,
                     @NotNull LeaderboardSettings leaderboards,
                     @NotNull WandDefinition sellWand, @NotNull WandDefinition harvestWand,
                     int sellWandPercent,
                     @NotNull LedgerService ledger, @NotNull ServerShop shop,
                     @NotNull InventoryOutbox outbox,
                     @NotNull SkylliaIntegration skyllia) {
        this.plugin = plugin;
        this.leaderboards = leaderboards;
        this.hub = new SkyBlockHub(plugin, miniMessage, hubSettings);
        this.wands = new WandService(plugin, menus, miniMessage, ledger, shop,
                outbox, skyllia, sellWand, harvestWand, sellWandPercent);
    }

    public void enable() {
        /*
         * Dwa listenery, nie jeden. Hub niesie ochronę spawnu, skocznie i respawn
         * (dziewięć handlerów), różdżki cztery. Przy przenoszeniu wiązania do modułu
         * zgubiłem dwie z tych rejestracji — kod kompilował się i wstawał, a
         * funkcje po prostu przestawały działać. Test niżej pilnuje, że są wszystkie.
         */
        plugin.getServer().getPluginManager().registerEvents(hub, plugin);
        plugin.getServer().getPluginManager().registerEvents(wands, plugin);
        hub.start();
        new HubPedestal(plugin, leaderboards.pedestal())
                .buildAll(leaderboards.boards());
    }

    public @NotNull SkyBlockHub hub() {
        return hub;
    }

    public @NotNull WandService wands() {
        return wands;
    }
}
