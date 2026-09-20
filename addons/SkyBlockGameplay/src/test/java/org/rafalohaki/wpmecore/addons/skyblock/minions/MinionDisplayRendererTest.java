package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinionDisplayRendererTest {

    private Plugin plugin;
    private Server server;
    private World world;
    private Chunk chunk;
    private MinionDisplayRenderer renderer;
    private MinionRecord record;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        plugin = mock(Plugin.class);
        server = mock(Server.class);
        world = mock(World.class);
        chunk = mock(Chunk.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getWorld("w")).thenReturn(world);
        when(world.isChunkLoaded(any(Integer.class), any(Integer.class))).thenReturn(true);
        when(world.getChunkAt(any(Integer.class), any(Integer.class))).thenReturn(chunk);
        when(world.getChunkAt(any(Location.class))).thenReturn(chunk);
        when(chunk.getEntities()).thenReturn(new Entity[0]);
        when(world.spawn(any(Location.class),
                org.mockito.ArgumentMatchers.<Class<Entity>>any(),
                any(Consumer.class)))
                .thenAnswer(invocation -> {
                    Class<? extends Entity> type = (Class<? extends Entity>) invocation.getArgument(1);
                    Consumer<Entity> consumer = (Consumer<Entity>) invocation.getArgument(2);
                    Entity entity = mock(type);
                    when(entity.getPersistentDataContainer()).thenReturn(RecordingPdc.create());
                    consumer.accept(entity);
                    return entity;
                });

        MinionsConfig config = new MinionsConfig(
                new MinionsConfig.Settings(5, 1, 1, 2, 2), Map.of(), Map.of(),
                Map.of("diamond", new MinionsConfig.TypeDef("diamond", "<aqua>Minionek</aqua>",
                        "texture", null, Material.DIAMOND, 1, Map.of())));
        renderer = new MinionDisplayRenderer(plugin, config,
                net.kyori.adventure.text.minimessage.MiniMessage.miniMessage());
        record = new MinionRecord(UUID.randomUUID(), UUID.randomUUID(), "diamond", 2,
                "w", 10.5, 64, 10.5, Map.of(), null, 0L, false, null, null, null, 5L,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    @Test
    void renderSpawnsAllThreeEntityKinds() {
        renderer.render(record);

        verify(world).spawn(any(Location.class), eq(Interaction.class), any(Consumer.class));
        verify(world).spawn(any(Location.class), eq(org.bukkit.entity.ItemDisplay.class), any(Consumer.class));
        verify(world).spawn(any(Location.class), eq(TextDisplay.class), any(Consumer.class));
    }

    @Test
    void despawnRemovesTaggedEntitiesInChunk() {
        TextDisplay stale = mock(TextDisplay.class);
        PersistentDataContainer stalePdc = RecordingPdc.create();
        when(stale.getPersistentDataContainer()).thenReturn(stalePdc);
        stalePdc.set(MinionDisplayRenderer.KEY_ENTITY_MINION, PersistentDataType.STRING,
                record.minionId().toString());
        when(chunk.getEntities()).thenReturn(new Entity[]{stale});

        renderer.despawn(record);

        verify(stale).remove();
    }

    @Test
    void onCycleWithoutActiveDisplaysRendersWhenChunkLoaded() {
        renderer.onCycle(record, true, false);
        verify(world, atLeastOnce()).spawn(any(Location.class), eq(Interaction.class), any(Consumer.class));
    }

    /** Prosty PDC-fake: mapa klucz -> wartość (kształt interfejsu zweryfikowany javap). */
    static final class RecordingPdc implements PersistentDataContainer {
        private final Map<NamespacedKey, Object> values = new java.util.HashMap<>();

        static RecordingPdc create() {
            return new RecordingPdc();
        }

        @Override
        public <T, Z> void set(NamespacedKey key, PersistentDataType<T, Z> type, Z value) {
            values.put(key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T, Z> Z get(NamespacedKey key, PersistentDataType<T, Z> type) {
            return (Z) values.get(key);
        }

        @Override
        public <T, Z> Z getOrDefault(NamespacedKey key, PersistentDataType<T, Z> type, Z def) {
            Z value = get(key, type);
            return value != null ? value : def;
        }

        @Override
        public <T, Z> boolean has(NamespacedKey key, PersistentDataType<T, Z> type) {
            return values.containsKey(key);
        }

        @Override
        public boolean has(NamespacedKey key) {
            return values.containsKey(key);
        }

        @Override
        public java.util.Set<NamespacedKey> getKeys() {
            return values.keySet();
        }

        @Override
        public void remove(NamespacedKey key) {
            values.remove(key);
        }

        @Override
        public boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        public int getSize() {
            return values.size();
        }

        @Override
        public void copyTo(PersistentDataContainer other, boolean replace) {
        }

        @Override
        public org.bukkit.persistence.PersistentDataAdapterContext getAdapterContext() {
            return null;
        }

        @Override
        public byte[] serializeToBytes() throws java.io.IOException {
            return new byte[0];
        }

        @Override
        public void readFromBytes(byte[] bytes, boolean clear) throws java.io.IOException {
        }
    }
}
