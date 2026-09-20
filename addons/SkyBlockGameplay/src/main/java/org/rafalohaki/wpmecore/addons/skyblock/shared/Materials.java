package org.rafalohaki.wpmecore.addons.skyblock.shared;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/** Drobne pytania o materiał, zadawane w wielu miejscach SkyBlocka. */
public final class Materials {

    private Materials() {
    }

    /**
     * Czy materiał jest powietrzem.
     *
     * <p>Bez {@link Material#isAir()}, bo tamta metoda sięga do rejestru serwera
     * i wywraca testy jednostkowe parserów, które rejestru nie mają. Ta wersja
     * stała wcześniej w sześciu kopiach — w dwóch parserach, w menu i serwisie
     * minionków, w skrzyni sprzedaży i w matematyce ilości.
     */
    public static boolean isAir(@Nullable Material material) {
        return material == null
                || material == Material.AIR
                || material == Material.CAVE_AIR
                || material == Material.VOID_AIR;
    }
}
