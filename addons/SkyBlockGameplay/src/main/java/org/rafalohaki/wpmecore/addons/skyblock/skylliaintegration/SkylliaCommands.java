package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.commands.SubCommandInterface;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Rejestracja subkomend pod komendą Skylli ({@code /is}, {@code /island}).
 *
 * <p>R-API-01: cała styczność z typami {@code fr.euphyllia.skyllia} musi zostać
 * w pakiecie {@code skylliaintegration}. Reszta dodatku podaje wyłącznie
 * uprawnienie + akcję ({@link Consumer} po {@link CommandSender}), nie widząc
 * żadnego typu Skylli. Subkomendy bezargumentowe (np. {@code /is top}), więc
 * {@code args} jest ignorowane, a tab-complete puste.
 */
public final class SkylliaCommands {

    private SkylliaCommands() {
    }

    /**
     * Rejestruje bezargumentową subkomendę Skylli. Guard: brak/niekompatybilna
     * Skyllia nie może ubić rejestracji reszty komend dodatku.
     *
     * @return {@code true} gdy Skyllia przyjęła rejestrację
     */
    public static boolean registerSubCommand(@NotNull Plugin plugin,
                                             @NotNull String permission,
                                             @NotNull Consumer<CommandSender> action,
                                             @NotNull String... labels) {
        try {
            return SkylliaAPI.registerCommands(new SubCommandInterface() {
                @Override
                public void onExecute(@NotNull Plugin caller, @NotNull CommandSender sender,
                                      @NotNull String[] args) {
                    action.accept(sender);
                }

                @Override
                public @NotNull List<String> onTabComplete(@NotNull Plugin caller,
                                                           @NotNull CommandSender sender,
                                                           @NotNull String[] args) {
                    return List.of();
                }

                @Override
                public @NotNull String permission() {
                    return permission;
                }
            }, labels);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "Rejestracja subkomendy Skylli " + String.join("/", labels) + " nie powiodła się", t);
            return false;
        }
    }
}
