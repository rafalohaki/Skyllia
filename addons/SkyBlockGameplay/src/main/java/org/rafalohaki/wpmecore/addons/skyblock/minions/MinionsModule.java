package org.rafalohaki.wpmecore.addons.skyblock.minions;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.UUID;

/**
 * Wiązanie podsystemu minionków.
 *
 * <p>Konstruktor buduje wszystko, czego funkcja potrzebuje, i przyjmuje to, co
 * współdzielone (ledger, integracja Skyllii, katalog przedmiotów), od korzenia
 * kompozycji. Nic tu nie sięga po globalny rejestr i nic nie konstruuje usług
 * innych funkcji — dzięki temu kolejność tworzenia zostaje widoczna w jednym
 * miejscu, w {@code onEnable}, zamiast rozejść się po module.
 */
public final class MinionsModule {

    private final JavaPlugin plugin;
    private final MinionDao dao;
    private final MinionService service;
    private final MinionDisplayRenderer renderer;
    private final MinionMenu menu;
    private final MinionListener listener;

    public MinionsModule(@NotNull JavaPlugin plugin, @NotNull SqlService sql,
                         @NotNull MenuService menus, @NotNull MiniMessage miniMessage,
                         @NotNull MinionsConfig config, @NotNull LedgerService ledger,
                         @NotNull SkylliaIntegration skyllia,
                         @Nullable CustomItemService customItems) {
        this.plugin = plugin;
        this.dao = new MinionDao(sql);
        this.service = new MinionService(plugin, dao, config, customItems);
        this.renderer = new MinionDisplayRenderer(plugin, config, miniMessage);
        /*
         * Cykl prawdziwy, nie konfiguracja: serwis tyka minionki, a renderer
         * obserwuje ten cykl, żeby odświeżać wyświetlacze. Konstruktor nie umie
         * tego wyrazić, więc zostaje setter — ale nazwany tak, żeby było jasne,
         * że to domknięcie cyklu, a nie opcja do wyłączenia.
         */
        this.service.setCycleObserver(renderer);
        this.menu = new MinionMenu(plugin, menus, miniMessage, service, ledger,
                skyllia, config, customItems, renderer);
        this.listener = new MinionListener(plugin, service, renderer, menu, config,
                miniMessage, skyllia);
    }

    /** Wczytuje stan i uruchamia tykanie; rejestruje listener. */
    public void enable() {
        service.initialize();
        service.startTicking();
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
    }

    public void disable() {
        service.stopTicking();
    }

    /** Sprzątanie po skasowanej wyspie — woła je strażnik cyklu życia z korzenia. */
    public void forgetIsland(@NotNull UUID islandId) {
        renderer.despawnAll(service.unregisterByIsland(islandId));
    }

    public @NotNull MinionService service() {
        return service;
    }

    /** Listener stawiania — korzeń dopina tu bonusowe sloty z prestiżu wyspy. */
    public @NotNull MinionListener listener() {
        return listener;
    }

    public @NotNull MinionMenu menu() {
        return menu;
    }
}
