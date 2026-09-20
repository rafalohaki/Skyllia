package org.rafalohaki.wpmecore.addons.skyblock.automation;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.shop.ShopCatalog;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.UUID;

/**
 * Wiązanie automatyzacji wyspy: chunkery, skrzynie autosprzedaży i generatory bruku.
 *
 * <p>Trzy rzeczy trzymane razem, bo wszystkie robią to samo: pracują za gracza
 * na jego wyspie, więc dzielą reguły dostępu i sprzątanie po skasowanej wyspie.
 */
public final class AutomationModule {

    private final JavaPlugin plugin;
    private final ChunkerService chunkers;
    private final ChunkerListener chunkerListener;
    private final SellChestService sellChests;
    private final SellChestListener sellChestListener;
    private final CobblestoneGeneratorListener generators;

    public AutomationModule(@NotNull JavaPlugin plugin, @NotNull SqlService sql,
                            @NotNull MiniMessage miniMessage, @NotNull ShopCatalog shop,
                            @NotNull LedgerService ledger,
                            @NotNull SkylliaIntegration skyllia) {
        this.plugin = plugin;
        this.chunkers = new ChunkerService(plugin, new ChunkerDao(sql), miniMessage, skyllia);
        this.chunkerListener = new ChunkerListener(plugin, chunkers, miniMessage, skyllia);
        this.sellChests = new SellChestService(plugin, new SellChestDao(sql), shop, ledger,
                miniMessage, skyllia);
        this.sellChestListener = new SellChestListener(plugin, sellChests, miniMessage, skyllia);
        this.generators = new CobblestoneGeneratorListener(plugin, skyllia);
    }

    public void enable() {
        chunkers.initialize();
        sellChests.initialize();
        sellChests.startTicking();
        plugin.getServer().getPluginManager().registerEvents(chunkerListener, plugin);
        plugin.getServer().getPluginManager().registerEvents(sellChestListener, plugin);
        plugin.getServer().getPluginManager().registerEvents(generators, plugin);
    }

    public void disable() {
        sellChests.stopTicking();
    }

    /** Sprzątanie po skasowanej wyspie — woła je strażnik cyklu życia z korzenia. */
    public void forgetIsland(@NotNull UUID islandId) {
        chunkers.unregisterByIsland(islandId);
        sellChests.unregisterByIsland(islandId);
    }

    public @NotNull ChunkerService chunkers() {
        return chunkers;
    }

    public @NotNull SellChestService sellChests() {
        return sellChests;
    }
}
