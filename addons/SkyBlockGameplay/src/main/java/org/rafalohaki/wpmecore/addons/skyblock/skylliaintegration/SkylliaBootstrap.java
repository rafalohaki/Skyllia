package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.rafalohaki.wpmecore.api.service.CacheService;

/**
 * Typowane wykrycie pluginu Skyllia i fabryka adaptera {@link SkylliaIntegration}.
 *
 * <p>Po przejściu na publiczny artefakt {@code fr.euphyllia.skyllia:api} bootstrap
 * nie trzyma już referencji do klas wewnętrznych Skyllii (menedżery, cache, zapytania).
 * Implementacja adaptera opiera się wyłącznie na statycznej fasadzie
 * {@link fr.euphyllia.skyllia.api.SkylliaAPI} i typach {@code api.skyblock.*},
 * z własnym krótkim TTL cache nad nią (bo {@code SkylliaAPI} jest autorytatywne).
 */
public final class SkylliaBootstrap {

    private SkylliaBootstrap() {
    }

    /** Buduje bootstrap, gdy plugin Skyllia jest załadowany i włączony; puste w p.p. */
    public static Optional<SkylliaBootstrap> create() {
        return Bukkit.getPluginManager().isPluginEnabled("Skyllia")
                ? Optional.of(new SkylliaBootstrap())
                : Optional.empty();
    }

    /**
     * Buduje typowany {@link SkylliaIntegration}. {@code SkylliaIntegrationImpl} jest
     * package-private — to jedyna droga jego utworzenia.
     *
     * @param owner        plugin-owner (SkyBlockGameplay), używany też do schedulerów w impl
     * @param capabilities zestaw capability rozstrzygnięty przez ownera
     * @param cacheService serwis cache WpmeAPI; adapter buduje nad nim TTL cache island-view
     */
    public SkylliaIntegration createIntegration(JavaPlugin owner, IslandCapabilities capabilities,
                                                CacheService cacheService) {
        return new SkylliaIntegrationImpl(owner, capabilities, cacheService);
    }
}
