package org.rafalohaki.wpmecore.addons.skyblock.shared;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.WpmeAPI;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

/**
 * Leniwe dojście do serwisów, których SkyBlock używa opcjonalnie.
 *
 * <p>Ten kod stał wcześniej w pięciu kopiach — w listenerach skrzyń, dropów i
 * stoniarki, w serwisie receptur i w koordynatorze nagród. Kopie zdążyły się
 * rozejść w komentarzach, choć jeszcze nie w zachowaniu; jedna implementacja
 * zdejmuje ryzyko, że następna zmiana rozejdzie się już naprawdę.
 */
public final class SkyBlockServices {

    private SkyBlockServices() {
    }

    /**
     * Zwraca podany serwis, a gdy go nie ma — próbuje go odnaleźć w menedżerze
     * serwisów, najpierw wprost, potem przez fasadę {@link WpmeAPI}.
     *
     * <p>Cała próba jest osłonięta, bo {@link Bukkit#getServicesManager()} nie
     * istnieje w środowisku testowym bez serwera. Zwrócenie {@code null} jest
     * poprawnym wynikiem: wywołujący traktują CustomItems jako integrację
     * opcjonalną i mają ścieżkę bez niej.
     *
     * @param cached wartość pola, jeśli ktoś ją wstrzyknął — wtedy wracamy z niej
     */
    public static @Nullable CustomItemService customItems(@Nullable CustomItemService cached) {
        if (cached != null) {
            return cached;
        }
        try {
            if (Bukkit.getServer() == null || Bukkit.getServicesManager() == null) {
                return null;
            }
            CustomItemService service = Bukkit.getServicesManager().load(CustomItemService.class);
            if (service != null) {
                return service;
            }
            WpmeAPI api = Bukkit.getServicesManager().load(WpmeAPI.class);
            return api == null ? null : api.customItemService();
        } catch (Throwable headless) {
            return null;
        }
    }
}
