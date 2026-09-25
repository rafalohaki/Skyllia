package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandCapabilities;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandOwner;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R48: fallback id wyspy dla odzaki offline graczy. Migawka TTL nie może
 * blokować wątku renderu, nie może mnożyć zapytań do magazynu (jeden load
 * na okno) i nie może rosnąć ponad limit.
 */
class OfflineIslandIdResolverTest {

    private static final class FakeSkyllia implements SkylliaIntegration {
        private final ConcurrentHashMap<UUID, UUID> cached = new ConcurrentHashMap<>();

        @Override
        public Optional<IslandView> islandOf(UUID player) {
            return Optional.empty();
        }

        @Override
        public Optional<IslandView> islandAt(UUID player, org.bukkit.Location location) {
            return Optional.empty();
        }

        @Override
        public Optional<UUID> cachedIslandIdOf(UUID player) {
            return Optional.ofNullable(cached.get(player));
        }

        @Override
        public Optional<IslandOwner> ownerOf(UUID islandId) {
            return Optional.empty();
        }

        @Override
        public boolean authoritativeCanWithdraw(UUID player, UUID island) {
            return false;
        }

        @Override
        public IslandCapabilities capabilities() {
            return IslandCapabilities.none();
        }

        @Override
        public boolean isSkyblockWorld(String worldName) {
            return false;
        }
    }

    private static final class CountingStorage implements Function<UUID, CompletableFuture<Optional<UUID>>> {
        final AtomicInteger calls = new AtomicInteger();
        final ConcurrentHashMap<UUID, CompletableFuture<Optional<UUID>>> answers = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<Optional<UUID>> apply(UUID playerId) {
            calls.incrementAndGet();
            return answers.getOrDefault(playerId, CompletableFuture.completedFuture(Optional.empty()));
        }
    }

    private static OfflineIslandIdResolver resolver(FakeSkyllia skyllia, CountingStorage storage,
                                                    Predicate<UUID> online) {
        return new OfflineIslandIdResolver(skyllia, storage, online);
    }

    @Test
    @DisplayName("Gracz online: szybka ścieżka z cache Skyllii, magazyn niepytany")
    void onlineUsesFastPath() {
        FakeSkyllia skyllia = new FakeSkyllia();
        CountingStorage storage = new CountingStorage();
        UUID player = UUID.randomUUID();
        UUID island = UUID.randomUUID();
        skyllia.cached.put(player, island);
        OfflineIslandIdResolver resolver = resolver(skyllia, storage, id -> true);

        assertEquals(island, resolver.resolve(player));
        assertEquals(0, storage.calls.get(), "online nie może uderzać w magazyn");
    }

    @Test
    @DisplayName("Gracz offline: pierwsze wywołanie puste, po dociągnięciu z magazynu odznaka wraca")
    void offlineSelfHeals() {
        FakeSkyllia skyllia = new FakeSkyllia();
        CountingStorage storage = new CountingStorage();
        UUID player = UUID.randomUUID();
        UUID island = UUID.randomUUID();
        storage.answers.put(player, CompletableFuture.completedFuture(Optional.of(island)));
        OfflineIslandIdResolver resolver = resolver(skyllia, storage, id -> false);

        assertNull(resolver.resolve(player), "pierwsze wywołanie zwraca migawkę (pustą) — bez blokowania");
        assertEquals(island, resolver.resolve(player), "kolejny refresh TAB widzi wyspę z magazynu");
    }

    @Test
    @DisplayName("Offline bez członkostwa: konsekwentnie pusto, bez lawiny zapytań")
    void offlineWithoutMembershipStaysQuiet() {
        FakeSkyllia skyllia = new FakeSkyllia();
        CountingStorage storage = new CountingStorage();
        UUID player = UUID.randomUUID();
        OfflineIslandIdResolver resolver = resolver(skyllia, storage, id -> false);

        assertNull(resolver.resolve(player));
        assertNull(resolver.resolve(player));
        assertNull(resolver.resolve(player));
        assertEquals(1, storage.calls.get(), "rezerwacja TTL ma zatrzymać powtórki w oknie");
    }

    @Test
    @DisplayName("Nieukończony odczyt magazynu: jedno zapytanie na okno, wynik po zakończeniu")
    void pendingLoadDoesNotStampede() {
        FakeSkyllia skyllia = new FakeSkyllia();
        CountingStorage storage = new CountingStorage();
        UUID player = UUID.randomUUID();
        UUID island = UUID.randomUUID();
        CompletableFuture<Optional<UUID>> pending = new CompletableFuture<>();
        storage.answers.put(player, pending);
        OfflineIslandIdResolver resolver = resolver(skyllia, storage, id -> false);

        assertNull(resolver.resolve(player));
        assertNull(resolver.resolve(player));
        assertNull(resolver.resolve(player));
        assertEquals(1, storage.calls.get());

        pending.complete(Optional.of(island));
        assertEquals(island, resolver.resolve(player));
    }

    @Test
    @DisplayName("Błąd magazynu: brak propagacji, migawka pusta, kolejne refreshy spokojne")
    void storageFailureDegradesToEmpty() {
        FakeSkyllia skyllia = new FakeSkyllia();
        CountingStorage storage = new CountingStorage();
        UUID player = UUID.randomUUID();
        storage.answers.put(player, CompletableFuture.failedFuture(new IllegalStateException("db down")));
        OfflineIslandIdResolver resolver = resolver(skyllia, storage, id -> false);

        assertNull(resolver.resolve(player));
        assertNull(resolver.resolve(player));
        assertTrue(storage.calls.get() >= 1, "magazyn był pytany");
    }

    @Test
    @DisplayName("Limit migawek: mapa nie rośnie ponad MAX_ENTRIES przy rotacji graczy")
    void snapshotStaysBounded() {
        FakeSkyllia skyllia = new FakeSkyllia();
        CountingStorage storage = new CountingStorage();
        OfflineIslandIdResolver resolver = resolver(skyllia, storage, id -> false);

        for (int i = 0; i < OfflineIslandIdResolver.MAX_ENTRIES + 50; i++) {
            resolver.resolve(UUID.randomUUID());
        }
        assertTrue(resolver.snapshotSizeForTest() <= OfflineIslandIdResolver.MAX_ENTRIES,
                "migawka ma trzymać limit, nie " + resolver.snapshotSizeForTest());
    }
}
