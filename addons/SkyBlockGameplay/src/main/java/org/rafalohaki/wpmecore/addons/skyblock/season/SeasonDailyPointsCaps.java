package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * P1-1: dzienne capy punktów sezonowych per kanał, wczytane z config.yml
 * (sekcja {@code season.daily-points-caps}).
 *
 * <p>Kanał to nazwa źródła punktów: {@code fish} (ryby z EvenMoreFish) i
 * {@code quest} (zadania dnia). Brak sekcji = capy wyłączone, czyli zachowanie
 * sprzed wprowadzenia limitu (żadne źródło nie traci punktów). Wartość
 * {@code 0} = kanał jawnie bez capu. Wartość ujemna albo nie-liczba =
 * {@link IllegalArgumentException} z nazwą kanału — fail-closed jak
 * {@link SeasonFishPoints}/{@link SeasonSchedule}, warstwa wyżej zatrzymuje
 * start pluginu.
 *
 * <p>Cap jest dobowy (UTC) i per gracz — pilnuje go
 * {@link SeasonDailyPointsService}, tu mieszka wyłącznie konfiguracja.
 */
public record SeasonDailyPointsCaps(@NotNull Map<String, Long> capsByChannel) {

    /** Capy wyłączone — brak sekcji {@code season.daily-points-caps}. */
    public static final SeasonDailyPointsCaps NONE = new SeasonDailyPointsCaps(Map.of());

    /**
     * Parsuje sekcję {@code season.daily-points-caps}. Klucze (nazwy kanałów)
     * normalizujemy do lower-case — kanał podany w komendzie i w configu musi
     * trafić w tę samą wartość.
     */
    public static @NotNull SeasonDailyPointsCaps load(@Nullable ConfigurationSection section) {
        if (section == null) {
            return NONE;
        }
        Map<String, Long> parsed = new LinkedHashMap<>();
        for (String rawKey : section.getKeys(false)) {
            String channel = rawKey.toLowerCase(Locale.ROOT);
            if (parsed.containsKey(channel)) {
                throw new IllegalArgumentException("season.daily-points-caps: duplikat kanału '"
                        + channel + "' (klucze różniące się tylko wielkością liter liczą się jako jeden)");
            }
            Object rawValue = section.get(rawKey);
            if (!(rawValue instanceof Number number)
                    || Math.floor(number.doubleValue()) != number.doubleValue()) {
                throw new IllegalArgumentException("season.daily-points-caps." + rawKey
                        + ": oczekiwano liczby całkowitej punktów dla kanału '" + channel
                        + "', było '" + rawValue + "'");
            }
            long cap = number.longValue();
            if (cap < 0) {
                throw new IllegalArgumentException("season.daily-points-caps." + rawKey
                        + ": cap kanału '" + channel + "' nie może być ujemny, było " + cap);
            }
            parsed.put(channel, cap);
        }
        return new SeasonDailyPointsCaps(Map.copyOf(parsed));
    }

    /**
     * Dzienny cap kanału. {@code 0} = brak capu (także dla kanału bez wpisu
     * i dla {@code null}) — kanał spoza tabeli nie jest limitowany.
     */
    public long capFor(@Nullable String channel) {
        if (channel == null) {
            return 0L;
        }
        return capsByChannel.getOrDefault(channel.toLowerCase(Locale.ROOT), 0L);
    }
}
