package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import fr.euphyllia.skyllia.api.skyblock.Island;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.CacheService;

/**
 * Krótki TTL cache (5 s, max 1000 wpisów) wysp indeksowanych po graczu, nad
 * autorytatywną (potencjalnie JDBC) fasadą {@link fr.euphyllia.skyllia.api.SkylliaAPI}.
 *
 * <p>Po przejściu na publiczne API Skyllia nie wystawia już swojego cold-cache
 * (dawniej {@code SkyblockManager#getIslandByPlayerIdCachedOnly}); { @code SkylliaAPI}
 * jest autorytatywne, więc adapter trzyma własny TTL, żeby UI/menu/bank nie
 * uderzało w bazę przy każdym kliknięciu.
 *
 * <p><b>Pamiętamy również brak wyspy.</b> Wcześniej zapisywane były wyłącznie
 * trafienia, więc gracz bez wyspy odpytywał bazę przy każdym pytaniu — a pytamy
 * o to dwa razy na gracza co pięć sekund, z wątku tickowego. W dniu startu,
 * kiedy nikt jeszcze nie ma wyspy, każde takie pytanie było blokującym JDBC
 * na ticku. To klasyczne przebicie cache'a i lekarstwo jest standardowe:
 * zapamiętać także wynik pusty, z tym samym krótkim TTL.
 */
final class IslandViewCache {

    private static final long MAX_SIZE = 1000L;
    private static final Duration TTL = Duration.ofSeconds(5L);

    /**
     * Wartością jest {@link Optional}, bo cache nie przyjmuje nulli, a brak wyspy
     * musi być odróżnialny od „jeszcze nie pytaliśmy".
     */
    private final CacheService.Cache<UUID, Optional<Island>> cache;

    IslandViewCache(CacheService cacheService) {
        this.cache = cacheService.build("skyblockgameplay-islands",
                CacheService.CacheSpec.ttl(MAX_SIZE, TTL));
    }

    /**
     * Wyspa gracza z cache'u, a przy pudle — z podanego źródła.
     *
     * <p>Wynik zapamiętywany jest zawsze, także pusty. Ładowanie idzie przez
     * {@code getOrCompute}, więc równoległe pytania o tego samego gracza
     * zbiegają się do jednego zapytania, zamiast rozjechać się na kilka.
     *
     * @param loader autorytatywne źródło; zwraca {@code null}, gdy gracz nie ma wyspy
     */
    @NotNull Optional<Island> resolve(@NotNull UUID playerId,
                                      @NotNull Function<UUID, Island> loader) {
        return cache.getOrCompute(playerId, id -> Optional.ofNullable(loader.apply(id)));
    }

    /** Unieważnia wpis gracza — także zapamiętany brak wyspy (np. po jej założeniu). */
    void invalidate(UUID playerId) {
        cache.invalidate(playerId);
    }

    void invalidateAll() {
        cache.invalidateAll();
    }
}
