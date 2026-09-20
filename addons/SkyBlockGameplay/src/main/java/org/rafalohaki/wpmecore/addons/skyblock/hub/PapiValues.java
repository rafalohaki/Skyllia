package org.rafalohaki.wpmecore.addons.skyblock.hub;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;

/**
 * Odczyt placeholderów PlaceholderAPI przez classloader pluginu PAPI (addon ma
 * {@code join-classpath: false}, więc klasy PAPI nie są widoczne wprost). Fail-soft:
 * brak PAPI albo błąd = wartość domyślna.
 */
public final class PapiValues {
    private static volatile Method setPlaceholders;

    private PapiValues() {
    }

    public static @NotNull String parse(@NotNull Player player, @NotNull String text) {
        try {
            Method method = setPlaceholders;
            if (method == null) {
                Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
                if (papi == null) {
                    return text;
                }
                Class<?> type = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true,
                        papi.getClass().getClassLoader());
                method = type.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
                setPlaceholders = method;
            }
            Object result = method.invoke(null, player, text);
            return result == null ? text : result.toString();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return text;
        }
    }

    /** Placeholder liczbowy; wszystko, co nie jest liczbą, = {@code fallback}. */
    public static int parseInt(@NotNull Player player, @NotNull String placeholder, int fallback) {
        String value = parse(player, placeholder).trim();
        try {
            return (int) Math.round(Double.parseDouble(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
