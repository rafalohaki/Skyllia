package org.rafalohaki.wpmecore.addons.skyblock.shared;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

/**
 * Wspólne sprawdzenia parserów konfiguracji SkyBlocka, które są fail-closed.
 *
 * <p>Świadomie <b>nie</b> korzysta z {@code api.util.Config}: tamten helper
 * degraduje brakującą sekcję do pustej, żeby wywołujący mógł działać dalej.
 * Tutaj obowiązuje odwrotna polityka — brak sekcji ma zatrzymać start pluginu
 * z dokładną ścieżką, zamiast wpuścić serwer w stan, w którym połowa treści po
 * cichu nie istnieje. To dwa różne kontrakty i nie wolno ich mylić.
 *
 * <p>Egzemplarz nosi nazwę pliku, bo to ona robi komunikat użytecznym: operator
 * czyta {@code forge.yml:recipes.x.result-item: ...} i wie, gdzie zaglądać.
 */
public final class ConfigSchema {

    private final String fileName;

    public ConfigSchema(@NotNull String fileName) {
        this.fileName = fileName;
    }

    public @NotNull IllegalArgumentException fail(@NotNull String path, @NotNull String message) {
        return new IllegalArgumentException(fileName + ':' + path + ": " + message);
    }

    public @NotNull ConfigurationSection section(@NotNull ConfigurationSection root,
                                          @NotNull String path) {
        ConfigurationSection section = root.getConfigurationSection(path);
        if (section == null) {
            throw fail(path, "brak wymaganej sekcji");
        }
        return section;
    }

    public @NotNull String string(@NotNull ConfigurationSection section, @NotNull String path,
                           @NotNull String key) {
        String value = section.getString(key);
        if (value == null || value.isBlank()) {
            throw fail(path + "." + key, "brak wymaganej wartości");
        }
        return value;
    }

    public @NotNull Material material(@NotNull String itemKey, @NotNull String path) {
        Material material = Material.matchMaterial(itemKey);
        if (Materials.isAir(material)) {
            throw fail(path, "nieznany materiał '" + itemKey + "'");
        }
        return material;
    }

    public void requireKnownCustomItem(@Nullable CustomItemService customItems, @NotNull String id,
                                @NotNull String path) {
        if (customItems == null || customItems.byId(id).isEmpty()) {
            throw fail(path, "nieznany identyfikator CustomItems '" + id + "'");
        }
    }
}
