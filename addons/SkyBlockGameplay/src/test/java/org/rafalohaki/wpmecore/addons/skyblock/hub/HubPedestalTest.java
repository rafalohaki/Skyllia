package org.rafalohaki.wpmecore.addons.skyblock.hub;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Podest nie może zniszczyć cudzej pracy.
 *
 * <p>To jedyne zabezpieczenie między błędną współrzędną w konfiguracji a
 * skasowaniem fragmentu spawnu, którego nikt nie odtworzy. Lista dozwolonych
 * materiałów musi obejmować goły teren i nic poza nim — dlatego test wymienia
 * wprost rzeczy, których tknąć nie wolno, zamiast tylko potwierdzać te dozwolone.
 */
class HubPedestalTest {

    @Test
    void bareGroundMayBeReplaced() {
        for (Material ground : List.of(Material.AIR, Material.DIRT, Material.COARSE_DIRT,
                Material.DIRT_PATH, Material.GRASS_BLOCK, Material.GRAVEL, Material.SAND)) {
            assertTrue(HubPedestal.canReplace(ground), ground + " to goły teren");
        }
    }

    @Test
    void anythingSomeoneBuiltIsOffLimits() {
        List<Material> built = List.of(
                Material.ORANGE_TERRACOTTA,   // warstwa pod podestem na naszym spawnie
                Material.BLUE_WOOL,           // faktyczna paleta tego spawnu
                Material.BLUE_CONCRETE_POWDER,
                Material.STONE_BRICK_STAIRS,
                Material.DARK_OAK_STAIRS,
                Material.OAK_LOG,
                Material.CHEST,               // zawartość gracza
                Material.WATER,               // element krajobrazu
                Material.SEA_LANTERN,         // nasz własny podest — nie nadpisujemy się w kółko
                Material.STONE_BRICKS,
                Material.POLISHED_ANDESITE);
        for (Material material : built) {
            assertFalse(HubPedestal.canReplace(material),
                    material + " należy do budowli albo do gracza — podest nie może go ruszyć");
        }
    }
}
