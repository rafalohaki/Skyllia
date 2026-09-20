package org.rafalohaki.wpmecore.addons.skyblock.hub;

import org.rafalohaki.wpmecore.addons.skyblock.season.LeaderboardBoard;
import org.rafalohaki.wpmecore.addons.skyblock.season.PedestalSettings;


import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Oprawa pod tablicami wyników: kamienny podest i ściana za plecami tablicy.
 *
 * <p>Powód jest praktyczny, nie tylko estetyczny. Tablice stoją na obszarze
 * gołej ziemi i ścieżek, a jasny tekst nad jasnym podłożem czyta się źle.
 * Ciemna ściana z tyłu daje kontrast, podest odcina tablicę od klepiska.
 *
 * <p><b>Bezpieczeństwo przez konstrukcję:</b> podmieniamy wyłącznie materiały
 * z {@link #EXPENDABLE}. Nawet gdyby współrzędne były błędne, ta klasa nie jest
 * w stanie zniszczyć niczyjej budowli — najwyżej nie postawi nic. Warstwa pod
 * podestem zostaje nietknięta, bo to podłoże położone przez budowniczego spawnu.
 */
public final class HubPedestal {

    /** Co wolno zastąpić: goły teren i powietrze. Nic więcej. */
    private static final Set<Material> EXPENDABLE = Set.of(
            Material.AIR, Material.CAVE_AIR, Material.VOID_AIR,
            Material.DIRT, Material.COARSE_DIRT, Material.DIRT_PATH,
            Material.GRASS_BLOCK, Material.PODZOL, Material.ROOTED_DIRT,
            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN,
            Material.GRAVEL, Material.SAND);

    /**
     * Czy wolno zastąpić ten materiał.
     *
     * <p>Wydzielone, bo to jedyna reguła chroniąca cudzą pracę — i jedyna, którą
     * da się sprawdzić testem bez uruchamiania serwera.
     */
    static boolean canReplace(@NotNull Material material) {
        return EXPENDABLE.contains(material);
    }

    /** Połowa szerokości podestu wzdłuż Z; całość to 2*HALF+1 kratek. */
    private static final int HALF_WIDTH = 3;
    private static final int WALL_HEIGHT = 5;

    private final JavaPlugin plugin;
    private final PedestalSettings settings;

    public HubPedestal(@NotNull JavaPlugin plugin,
                @NotNull PedestalSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public void buildAll(@NotNull List<LeaderboardBoard> boards) {
        if (!settings.enabled()) {
            return;
        }
        for (LeaderboardBoard board : boards) {
            World world = Bukkit.getWorld(board.world());
            if (world == null) {
                continue;
            }
            Location anchor = new Location(world, board.x(), board.y(), board.z());
            /*
             * Chunk tablicy leży poza promieniem trzymanym wokół spawnu, więc przy
             * starcie bywa nierozładowany. Wcześniejsza wersja po prostu z tego
             * rezygnowała i podest nie powstawał nigdy — asynchroniczne wczytanie
             * załatwia to, a callback biegnie już na wątku właściciela regionu.
             */
            world.getChunkAtAsync(anchor, (org.bukkit.Chunk chunk) -> {
                try {
                    build(world, anchor);
                } catch (RuntimeException failure) {
                    plugin.getLogger().log(Level.WARNING,
                            "Nie zbudowano podestu pod tablicą " + board.id(), failure);
                }
            });
        }
    }

    /**
     * @param anchor punkt bazowy tablicy; podłoga leży kratkę niżej, a ściana
     *               dwie kratki za nią, patrząc od strony spawnu
     */
    private void build(World world, Location anchor) {
        int bx = anchor.getBlockX();
        int by = anchor.getBlockY();
        int bz = anchor.getBlockZ();
        int placed = 0;

        /*
         * Podłoga: kratka za tablicą, sama tablica i dojście w stronę spawnu.
         * Długości dojścia nie trzeba dobierać co do kratki — lista dozwolonych
         * materiałów zatrzyma je dokładnie tam, gdzie zaczyna się istniejąca
         * budowla, więc nadmiar jest bezpieczny i nic nie nadpisze.
         */
        for (int dx = -1; dx <= Math.max(1, settings.approach()); dx++) {
            for (int dz = -HALF_WIDTH; dz <= HALF_WIDTH; dz++) {
                placed += set(world, bx + dx, by - 1, bz + dz, settings.floor());
            }
        }
        /*
         * Ściana za tablicą, od strony przeciwnej do spawnu (spawn leży na +X).
         * Światło idzie w tej samej pętli, a nie po niej: gdyby dokładać je
         * osobno, trafiałoby na postawiony przed chwilą narożnik, a ten nie
         * jest materiałem wymienialnym — lampy nie postawiłyby się nigdy.
         */
        int wallX = bx - 2;
        for (int dy = 0; dy < WALL_HEIGHT; dy++) {
            for (int dz = -HALF_WIDTH; dz <= HALF_WIDTH; dz++) {
                boolean edge = dz == -HALF_WIDTH || dz == HALF_WIDTH;
                Material material;
                if (edge && dy == 0) {
                    material = settings.light();
                } else if (edge && dy == WALL_HEIGHT - 1) {
                    material = settings.accent();
                } else {
                    material = settings.wall();
                }
                placed += set(world, wallX, by + dy, bz + dz, material);
            }
        }

        // Zawsze logujemy, także zero: cisza nie odróżniała "już stoi" od "nie zadziałało".
        plugin.getLogger().info("Podest tablicy przy " + bx + "," + by + "," + bz
                + ": postawiono " + placed + " bloków"
                + (placed == 0 ? " (już stoi albo teren zajęty)" : ""));
    }

    /**
     * Stawia blok tylko wtedy, gdy zastępuje goły teren i faktycznie coś zmienia.
     *
     * @return 1 gdy postawiono, 0 gdy pominięto
     */
    private int set(World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() == material) {
            return 0;
        }
        if (!canReplace(block.getType())) {
            return 0;
        }
        block.setType(material, false);
        return 1;
    }
}
