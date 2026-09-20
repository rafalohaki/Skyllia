package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.UUID;

/**
 * Wiązanie trybu OneBlock.
 *
 * <p>Treść faz jest wczytywana przez korzeń <b>przed</b> migratorem schematu
 * i wstrzykiwana tutaj, a nie ładowana ponownie: migracja uzupełnia
 * identyfikatory faz właśnie z tej listy, więc obie strony muszą widzieć
 * dokładnie ten sam obiekt.
 */
public final class OneBlockModule {

    private final JavaPlugin plugin;
    private final OneBlockService service;
    private final OneBlockMilestoneMenu milestoneMenu;

    public OneBlockModule(@NotNull JavaPlugin plugin, @NotNull SqlService sql,
                          @NotNull OneBlockContent content, @NotNull MenuService menus,
                          @NotNull MiniMessage miniMessage,
                          @NotNull SkylliaIntegration skyllia,
                          @Nullable CustomItemService customItems) {
        this.plugin = plugin;
        OneBlockMilestoneDao milestones = new OneBlockMilestoneDao(sql);
        this.service = new OneBlockService(plugin, new OneBlockDao(sql, content.phaseIds()),
                milestones, content, miniMessage, customItems);
        this.milestoneMenu = new OneBlockMilestoneMenu(plugin, menus, miniMessage,
                milestones, customItems, skyllia);
    }

    public void enable() {
        // ONEBLOCK-2: future z initialize() był wcześniej wyrzucany do kosza, więc
        // po wyczerpaniu ponowień nie zostawał żaden ślad w logu poza tym z usługi.
        service.initialize().exceptionally(err -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "OneBlock nie wczytał stanów wysp — tryb pozostaje wyłączony", err);
            return null;
        });
        plugin.getServer().getPluginManager().registerEvents(service, plugin);
    }

    public void disable() {
        service.flush();
    }

    public void forgetIsland(@NotNull UUID islandId) {
        service.unregisterOneBlock(islandId);
    }

    /** Nowa wyspa dostaje swój blok w miejscu wyznaczonym przez Skyllię. */
    public void registerIslandBlock(@NotNull UUID islandId, @NotNull Location at) {
        service.registerOneBlock(islandId, at.getWorld().getName(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    /** Czy ta lokacja jest czyimś blokiem OneBlocka — filtr dropów pyta o to co złamanie. */
    public boolean isOneBlockLocation(@NotNull Location at) {
        return service.isOneBlockLocation(at.getWorld().getName(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    public @NotNull OneBlockService service() {
        return service;
    }

    public @NotNull OneBlockMilestoneMenu milestoneMenu() {
        return milestoneMenu;
    }
}
