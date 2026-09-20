package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import fr.euphyllia.skyllia.api.skyblock.Island;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.api.service.CacheService;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Cache wyspy gracza musi pamiętać także <b>brak</b> wyspy.
 *
 * <p>Zapamiętywanie wyłącznie trafień to przebicie cache'a: gracz bez wyspy
 * odpytuje bazę przy każdym zapytaniu. A pytamy o to dwa razy na gracza co
 * pięć sekund, z wątku tickowego — więc w dniu startu, gdy nikt jeszcze nie
 * ma wyspy, każde takie zapytanie idzie do bazy Skyllii i blokuje tick.
 */
class IslandViewCacheTest {

    /** Atrapa musi naprawdę pamiętać — mock Mockito zwracałby null w kółko. */
    private static final class MapCache<K, V> implements CacheService.Cache<K, V> {
        private final ConcurrentMap<K, V> map = new ConcurrentHashMap<>();

        @Override public V get(K key) {
            return map.get(key);
        }

        @Override public V getOrCompute(K key, Function<K, V> loader) {
            return map.computeIfAbsent(key, loader);
        }

        @Override public void put(K key, V value) {
            map.put(key, value);
        }

        @Override public void invalidate(K key) {
            map.remove(key);
        }

        @Override public void invalidateAll() {
            map.clear();
        }

        @Override public long estimatedSize() {
            return map.size();
        }

        @Override public ConcurrentMap<K, V> asMap() {
            return map;
        }
    }

    private static final class MapCacheService implements CacheService {
        @Override public <K, V> Cache<K, V> build(String namespace, CacheSpec spec) {
            return new MapCache<>();
        }

        @Override public void invalidateNamespace(String namespace) {
            // atrapa nie potrzebuje przestrzeni nazw
        }
    }

    private final IslandViewCache cache = new IslandViewCache(new MapCacheService());
    private final UUID player = UUID.randomUUID();

    @Test
    void aPlayerWithoutAnIslandIsAskedForOnlyOnce() {
        AtomicInteger asked = new AtomicInteger();
        Function<UUID, Island> noIsland = id -> {
            asked.incrementAndGet();
            return null;
        };

        assertTrue(cache.resolve(player, noIsland).isEmpty());
        assertTrue(cache.resolve(player, noIsland).isEmpty());
        assertTrue(cache.resolve(player, noIsland).isEmpty());

        assertEquals(1, asked.get(),
                "brak wyspy też musi zostać zapamiętany, inaczej każde pytanie idzie do bazy");
    }

    @Test
    void aPlayerWithAnIslandIsAskedForOnlyOnce() {
        Island island = mock(Island.class);
        AtomicInteger asked = new AtomicInteger();
        Function<UUID, Island> hasIsland = id -> {
            asked.incrementAndGet();
            return island;
        };

        assertSame(island, cache.resolve(player, hasIsland).orElseThrow());
        assertSame(island, cache.resolve(player, hasIsland).orElseThrow());

        assertEquals(1, asked.get(), "trafienie było zapamiętywane wcześniej i ma tak zostać");
    }

    @Test
    void invalidationMakesTheNextLookupAskAgain() {
        AtomicInteger asked = new AtomicInteger();
        Function<UUID, Island> noIsland = id -> {
            asked.incrementAndGet();
            return null;
        };

        cache.resolve(player, noIsland);
        cache.invalidate(player);
        cache.resolve(player, noIsland);

        assertEquals(2, asked.get(),
                "unieważnienie musi działać także na zapamiętanym braku — inaczej gracz,"
                        + " który właśnie założył wyspę, zostaje bez niej do wygaśnięcia TTL");
    }
}
