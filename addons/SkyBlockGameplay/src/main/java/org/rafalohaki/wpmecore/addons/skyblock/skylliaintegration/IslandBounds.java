package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

/** Niezmiennik geometrii wyspy (region x/z + rozmiar); bez typów Bukkit. */
public record IslandBounds(int regionX, int regionZ, double size) {
    public static IslandBounds of(int regionX, int regionZ, double size) {
        return new IslandBounds(regionX, regionZ, size);
    }
}
