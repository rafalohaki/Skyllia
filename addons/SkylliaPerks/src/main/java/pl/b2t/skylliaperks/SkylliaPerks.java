package pl.b2t.skylliaperks;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Addon Skylli: perki rang na wyspie. Przeniesiony z WpmeCore skyblock
 * (perks/IslandFlyModule) — /latanie włącza lot tylko na własnej wyspie.
 */
public final class SkylliaPerks extends JavaPlugin {

    public static final String FLY_PERMISSION = "skyblockgameplay.fly";

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private IslandFlyModule islandFly;

    @Override
    public void onEnable() {
        this.islandFly = new IslandFlyModule(miniMessage);
        getServer().getPluginManager().registerEvents(islandFly, this);
        // Paper plugins nie mają `commands:` w paper-plugin.yml — rejestracja
        // przez Brigadier w lifecycle. `/fly` nie jest aliasem: na backendach z
        // AdminTools `/fly` to narzędzie administracji (admintools.fly).
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(Commands.literal("latanie")
                    .requires(source -> source.getSender() instanceof Player player
                            && player.hasPermission("skyblockgameplay.use"))
                    .executes(context -> {
                        islandFly.toggle((Player) context.getSource().getSender());
                        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
                    }).build(), "Lot na własnej wyspie (perk rangi)");
        });
        getLogger().info("SkylliaPerks enabled");
    }
}
