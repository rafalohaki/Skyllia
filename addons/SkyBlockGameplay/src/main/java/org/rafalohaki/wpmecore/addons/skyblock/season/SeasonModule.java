package org.rafalohaki.wpmecore.addons.skyblock.season;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.UUID;
import java.util.function.Function;

/**
 * Wiązanie sezonów: ranking wysp, nagrody, kosmetyka i tablice na spawnie.
 *
 * <p>Trzymane razem, bo to jeden łańcuch: ranking wyłania podium, podium dostaje
 * nagrodę, nagrodą bywa limitowana kosmetyka, a tablice pokazują jedno i drugie.
 */
public final class SeasonModule {

    private final SkyBlockTopRewardCoordinator coordinator;
    private final CosmeticService cosmetics;
    private final SeasonPlaceholderModule placeholders;

    public SeasonModule(@NotNull JavaPlugin plugin, @NotNull SqlService sql,
                        @NotNull MiniMessage miniMessage,
                        @NotNull LeaderboardSettings leaderboards,
                        @NotNull SkyBlockTopRewardCoordinator.SeasonEligibility eligibility,
                        @NotNull LedgerService ledger, @NotNull InventoryOutbox outbox,
                        @NotNull CosmeticCatalog catalog,
                        @NotNull SkylliaIntegration skyllia,
                        @Nullable CustomItemService customItems,
                        @Nullable Function<UUID, String> islandTitles) {
        this.coordinator = new SkyBlockTopRewardCoordinator(plugin, sql, ledger,
                skyllia, miniMessage);
        this.cosmetics = new CosmeticService(plugin, miniMessage, outbox, catalog, customItems);
        /*
         * Kosmetyka i outbox powstają po koordynatorze, więc trafiają do niego
         * setterem. To kolejność, nie cykl: koordynator działa bez nich ścieżką
         * awaryjną, ale ma ich używać, gdy są.
         */
        this.coordinator.setCosmeticService(cosmetics);
        this.coordinator.setInventoryOutbox(outbox);
        this.coordinator.setSeasonEligibility(eligibility);
        LeaderboardService service =
                new LeaderboardService(coordinator, new LeaderboardDao(sql), skyllia);
        /*
         * FancyHolograms rysuje tablice bezpośrednio z placeholderów PlaceholderAPI.
         * %skyblock_island_title% jedzie tym samym mostem: resolver dostarcza
         * wtyczka (Skyllia + magazyn tytułów), bo ekspansja nie może dotknąć
         * klas SkyBlocka przez izolowany classloader.
         */
        this.placeholders = new SeasonPlaceholderModule(plugin,
                new LeaderboardPlaceholders(leaderboards,
                        () -> service.refresh(leaderboards.entries())),
                islandTitles);
    }

    public void enable() {
        placeholders.enable();
    }

    public void disable() {
        placeholders.disable();
    }

    public @NotNull SkyBlockTopRewardCoordinator coordinator() {
        return coordinator;
    }

    public @NotNull CosmeticService cosmetics() {
        return cosmetics;
    }
}
