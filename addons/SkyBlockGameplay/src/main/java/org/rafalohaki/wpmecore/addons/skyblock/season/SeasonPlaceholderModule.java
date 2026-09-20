package org.rafalohaki.wpmecore.addons.skyblock.season;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Most do PlaceholderAPI: rejestruje {@code %skyblock_board_*%} i utrzymuje
 * migawkę wierszy w tle.
 *
 * <p><b>Fail-soft.</b> Brak PlaceholderAPI to nie awaria — moduł zostaje
 * bezczynny, w logu jedna linia, start wtyczki nietknięty. Tak samo każdy inny
 * błąd mostu: łapiemy {@code Throwable}, bo {@code NoClassDefFoundError} przy
 * niedopasowanej wersji PAPI nie jest wyjątkiem.
 *
 * <p><b>Dlaczego odizolowany classloader.</b> SkyBlockGameplay jest wtyczką
 * Papera z własnym classloaderem, a PlaceholderAPI jest wtyczką Bukkita.
 * {@code join-classpath: true} wciągnęłoby całą grupę classloaderów Bukkita,
 * a w niej packetevents z przesłoniętym Adventure — i wtyczka wstawałaby na
 * {@code IncompatibleClassChangeError}. Ten sam problem rozwiązano już w module
 * Chat; tutaj powtórzony jest ten sam układ:
 * <ul>
 *   <li>rodzic = classloader PAPI, żeby {@code PlaceholderExpansion} i singleton
 *       {@code PlaceholderAPIPlugin} pochodziły z właściwej instancji,</li>
 *   <li>dziecko-najpierw <b>tylko</b> dla {@link SkyBlockSeasonExpansion},</li>
 *   <li>zapasowo classloader SkyBlocka — PAPI nie widzi naszych klas.</li>
 * </ul>
 * Dlatego ekspansja jest ładowana i wołana refleksyjnie: nasz własny
 * classloader nie potrafi jej nawet zlinkować.
 */
public final class SeasonPlaceholderModule {

    private static final String EXPANSION =
            "org.rafalohaki.wpmecore.addons.skyblock.season.SkyBlockSeasonExpansion";

    private final JavaPlugin plugin;
    private final LeaderboardPlaceholders boards;
    private final @Nullable Function<UUID, String> islandTitles;
    private final AtomicReference<ScheduledTask> task = new AtomicReference<>();
    private @Nullable Object expansion;
    private @Nullable AutoCloseable isolatedLoader;

    public SeasonPlaceholderModule(@NotNull JavaPlugin plugin,
                                   @NotNull LeaderboardPlaceholders boards,
                                   @Nullable Function<UUID, String> islandTitles) {
        this.plugin = plugin;
        this.boards = boards;
        this.islandTitles = islandTitles;
    }

    /**
     * @return {@code true}, gdy ekspansja stanęła; {@code false} = brak PAPI albo awaria mostu.
     */
    public boolean enable() {
        if (!boards.hasBoards() && islandTitles == null) {
            return false; // leaderboards.enabled: false i nie ma tytułów wyspy — nie ma czego wystawiać
        }
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null) {
            plugin.getLogger().info(
                    "PlaceholderAPI nieobecne — %skyblock_board_*% i %skyblock_island_title% "
                            + "nie zostaną zarejestrowane.");
            return false;
        }
        if (!register(papi)) {
            return false;
        }
        long period = boards.refreshSeconds();
        /*
         * Opóźnienie startowe rozjeżdża się z rendererem (5 s), bo do czasu
         * potwierdzenia migracji oba systemy liczą ranking osobno — zbiegnięcie
         * się ich w jednej sekundzie to dwa pełne przeliczenia naraz.
         */
        task.set(plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin,
                ignored -> boards.refreshNow(), 15L, period, TimeUnit.SECONDS));
        plugin.getLogger().info("Zarejestrowano %skyblock_board_*%: wierszy="
                + boards.entries() + ", odświeżanie=" + period + "s"
                + (islandTitles != null ? " oraz %skyblock_island_title%" : ""));
        return true;
    }

    public void disable() {
        ScheduledTask running = task.getAndSet(null);
        if (running != null) {
            running.cancel();
        }
        if (expansion != null) {
            try {
                expansion.getClass().getMethod("unregister").invoke(expansion);
            } catch (Throwable failure) {
                plugin.getLogger().log(Level.FINE,
                        "Wyrejestrowanie ekspansji PAPI nie powiodło się: " + failure, failure);
            }
            expansion = null;
        }
        if (isolatedLoader != null) {
            try {
                isolatedLoader.close();
            } catch (Throwable ignored) {
                // sprzątanie best-effort; nic od tego nie zależy
            }
            isolatedLoader = null;
        }
    }

    private boolean register(@NotNull Plugin papi) {
        try {
            URL ownJar = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            ClassLoader papiLoader = papi.getClass().getClassLoader();
            ClassLoader ownLoader = plugin.getClass().getClassLoader();

            URLClassLoader isolated = new URLClassLoader(new URL[]{ownJar}, papiLoader) {
                @Override
                protected Class<?> loadClass(@NotNull String name, boolean resolve)
                        throws ClassNotFoundException {
                    if (name.equals(EXPANSION)) {
                        Class<?> found = findLoadedClass(name);
                        if (found == null) {
                            found = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(found);
                        }
                        return found;
                    }
                    try {
                        return super.loadClass(name, resolve);
                    } catch (ClassNotFoundException notInPapi) {
                        return ownLoader.loadClass(name);
                    }
                }
            };
            this.isolatedLoader = isolated;

            Class<?> type = isolated.loadClass(EXPANSION);
            Object instance = type
                    .getConstructor(JavaPlugin.class, LeaderboardPlaceholders.class, Function.class)
                    .newInstance(plugin, boards, islandTitles);
            Method registerMethod = type.getMethod("register");
            if (Boolean.TRUE.equals(registerMethod.invoke(instance))) {
                this.expansion = instance;
                return true;
            }
            plugin.getLogger().warning(
                    "PlaceholderAPI odrzuciło rejestrację %skyblock_board_*%.");
            disable();
            return false;
        } catch (Throwable failure) {
            plugin.getLogger().log(Level.WARNING,
                    "Most do PlaceholderAPI niedostępny: " + failure.getMessage(), failure);
            disable();
            return false;
        }
    }
}
