package org.rafalohaki.wpmecore.addons.skyblock.minions;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prezentacja 3D minionka (spec §2.2): ItemDisplay (głowa) + TextDisplay
 * (hologram statusu) + Interaction (hitbox klikalny). Wszystkie byty mają
 * setPersistent(false) i tag PDC wpme:minion_id — źródłem prawdy jest SQL,
 * a ChunkLoadEvent robi re-render.
 */
public class MinionDisplayRenderer implements MinionService.CycleObserver {

    public static final NamespacedKey KEY_ENTITY_MINION = new NamespacedKey("wpme", "minion_id");

    private final Plugin plugin;
    private final MinionsConfig config;
    private final MiniMessage miniMessage;
    private final Map<UUID, ActiveDisplays> active = new ConcurrentHashMap<>();

    private static final class ActiveDisplays {
        final ItemDisplay body;
        final TextDisplay hologram;
        volatile float yaw = 180.0f;

        ActiveDisplays(ItemDisplay body, TextDisplay hologram) {
            this.body = body;
            this.hologram = hologram;
        }
    }

    public MinionDisplayRenderer(@NotNull Plugin plugin, @NotNull MinionsConfig config,
                                 @NotNull MiniMessage miniMessage) {
        this.plugin = plugin;
        this.config = config;
        this.miniMessage = miniMessage;
    }

    /** Wywoływane na wątku regionu chunka minionka. */
    public void render(@NotNull MinionRecord record) {
        Server server = plugin.getServer();
        if (server == null) {
            return;
        }
        World world = server.getWorld(record.world());
        if (world == null || !world.isChunkLoaded(record.blockX() >> 4, record.blockZ() >> 4)) {
            return;
        }
        Location base = new Location(world, record.x(), record.y(), record.z());
        cleanupStale(world, base.getChunk(), record.minionId());
        MinionsConfig.TypeDef type = config.type(record.typeId());
        String displayName = type != null ? type.name() : "<white>Minion</white>";

        ItemStack head = minionHead(type);
        ItemDisplay body = world.spawn(base.clone().add(0, 0.3, 0), ItemDisplay.class, display -> {
            if (head != null) {
                display.setItemStack(head);
            }
            display.setBillboard(Display.Billboard.FIXED);
            display.setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf(),
                    new Vector3f(0.75f, 0.75f, 0.75f), new Quaternionf()));
            display.setViewRange(0.8f);
            display.setPersistent(false);
            display.setInvulnerable(true);
            tag(display, record.minionId());
        });
        TextDisplay hologram = world.spawn(base.clone().add(0, 1.35, 0), TextDisplay.class, display -> {
            display.text(statusText(record, displayName, false));
            display.setBillboard(Display.Billboard.CENTER);
            display.setShadowed(true);
            display.setSeeThrough(false);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setLineWidth(180);
            display.setViewRange(0.6f);
            display.setDefaultBackground(false);
            display.setPersistent(false);
            display.setInvulnerable(true);
            tag(display, record.minionId());
        });
        world.spawn(base, Interaction.class, interaction -> {
            interaction.setInteractionWidth(0.8f);
            interaction.setInteractionHeight(1.2f);
            interaction.setPersistent(false);
            interaction.setInvulnerable(true);
            interaction.setSilent(true);
            tag(interaction, record.minionId());
        });
        active.put(record.minionId(), new ActiveDisplays(body, hologram));
    }

    /** Wywoływane na wątku regionu chunka minionka. */
    public void despawn(@NotNull MinionRecord record) {
        active.remove(record.minionId());
        Server server = plugin.getServer();
        if (server == null) {
            return;
        }
        World world = server.getWorld(record.world());
        if (world == null || !world.isChunkLoaded(record.blockX() >> 4, record.blockZ() >> 4)) {
            return;
        }
        cleanupStale(world, world.getChunkAt(record.blockX() >> 4, record.blockZ() >> 4),
                record.minionId());
    }

    /** Despawn wielu minionków — każdy na własnym wątku regionu (spec §7.1). */
    public void despawnAll(@NotNull List<MinionRecord> records) {
        Server server = plugin.getServer();
        for (MinionRecord record : records) {
            if (server == null) {
                continue;
            }
            World world = server.getWorld(record.world());
            if (world == null) {
                continue;
            }
            try {
                server.getRegionScheduler().execute(plugin,
                        new Location(world, record.x(), record.y(), record.z()),
                        () -> despawn(record));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onCycle(@NotNull MinionRecord record, boolean generated, boolean storageFull) {
        Server server = plugin.getServer();
        World world = server == null ? null : server.getWorld(record.world());
        ActiveDisplays displays = active.get(record.minionId());
        boolean chunkLoaded = world != null
                && world.isChunkLoaded(record.blockX() >> 4, record.blockZ() >> 4);
        if (displays == null && chunkLoaded) {
            render(record); // self-heal po odładowaniu chunku
            displays = active.get(record.minionId());
        }
        MinionsConfig.TypeDef type = config.type(record.typeId());
        String displayName = type != null ? type.name() : "<white>Minion</white>";
        if (displays != null && displays.hologram.isValid()) {
            displays.hologram.text(statusText(record, displayName, storageFull));
        }
        if (world == null) {
            return;
        }
        Location above = new Location(world, record.x(), record.y() + 1.0, record.z());
        if (generated) {
            world.spawnParticle(Particle.HAPPY_VILLAGER, above, 8, 0.2, 0.2, 0.2, 0.02);
            if (displays != null && displays.body.isValid()) {
                displays.yaw = (displays.yaw + 25.0f) % 360.0f;
                displays.body.setRotation(displays.yaw, 0.0f);
            }
        } else {
            world.spawnParticle(Particle.CRIT, above, 4, 0.2, 0.2, 0.2, 0.02);
        }
    }

    private void cleanupStale(@NotNull World world, @NotNull Chunk chunk, @NotNull UUID minionId) {
        for (Entity entity : chunk.getEntities()) {
            try {
                String tag = entity.getPersistentDataContainer()
                        .get(KEY_ENTITY_MINION, PersistentDataType.STRING);
                if (minionId.toString().equals(tag)) {
                    entity.remove();
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Głowa z teksturą base64; konstrukcja ItemStack wymaga serwera — null w testach. */
    private @Nullable ItemStack minionHead(@Nullable MinionsConfig.TypeDef type) {
        try {
            if (type == null) {
                return new ItemStack(Material.PLAYER_HEAD);
            }
            ItemStack head = new ItemStack(type.displayMaterial());
            if (type.headTexture() != null) {
                head.editMeta(SkullMeta.class, meta ->
                        MinionItem.applyHeadTexture(meta, type.headTexture()));
            }
            return head;
        } catch (Throwable offline) {
            return null;
        }
    }

    private static void tag(@NotNull Entity entity, @NotNull UUID minionId) {
        try {
            entity.getPersistentDataContainer().set(
                    KEY_ENTITY_MINION, PersistentDataType.STRING, minionId.toString());
        } catch (Exception ignored) {
        }
    }

    private @NotNull Component statusText(@NotNull MinionRecord record,
                                          @NotNull String displayName, boolean storageFull) {
        StringBuilder text = new StringBuilder(displayName)
                .append("\n<gray>Tier ").append(record.tier()).append("</gray>");
        long now = System.currentTimeMillis();
        if (MinionFuel.isActive(record.fuelExpiresAt(), now)) {
            text.append("\n<gold>Paliwo: ")
                    .append(MinionFuel.remainingSeconds(record.fuelExpiresAt(), now))
                    .append("s</gold>");
        }
        text.append(storageFull ? "\n<red>Magazyn pełny!</red>" : "\n<green>Pracuje...</green>");
        return miniMessage.deserialize(text.toString()).decoration(TextDecoration.ITALIC, false);
    }
}
