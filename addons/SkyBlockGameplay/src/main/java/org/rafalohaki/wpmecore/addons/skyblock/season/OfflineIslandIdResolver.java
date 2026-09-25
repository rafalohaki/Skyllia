package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Awaryjne źródło identyfikatora wyspy dla odzaki wyspy (%skyblock_island_badge%)
 * graczy <b>offline</b> (r48). Dotąd łańcuch placeholdera kończył się na
 * {@link SkylliaIntegration#cachedIslandIdOf(UUID)}, który dla świeżo-offline
 * gracza (TAB trzyma ducha gracza jeszcze chwilę po wyjściu) potrafi wrócić
 * pusto i odznaka gaśnie.
 *
 * <p><b>Kontrakt bez blokowania</b> — jak w {@code IslandTitleService}:
 * metoda {@link #resolve(UUID)} biegnie na wątku rysującym TAB/hologram i tylko
 * czyta migawkę z pamięci. Ścieżki:
 * <ul>
 *   <li>gracz online → szybka ścieżka Skyllii ({@code cachedIslandIdOf},
 *       z warm cache po joinie — bez SQL) i odświeżenie migawki,</li>
 *   <li>gracz offline → wyłącznie migawka; przy pudle lub przeterminowaniu
 *       jednorazowo (jeden refresh na okno TTL) uruchamia <b>asynchroniczny</b>
 *       odczyt z magazynu ({@code wpme_sb_island_membership}, status ACTIVE),
 *       a bieżące wywołanie zwraca ostatni znany wynik lub pusto — następny
 *       refresh TAB pokaże odznakę.</li>
 * </ul>
 *
 * <p><b>Cache/limit.</b> TTL {@value #TTL_MS} ms i twardy limit
 * {@value #MAX_ENTRIES} wpisów: po przekroczeniu najpierw wypadają
 * przeterminowane, potem najstarsze — mapa nie rośnie bez granic przy dużej
 * rotacji graczy.
 */
public final class OfflineIslandIdResolver {

    /** Jak długo migawka (także pusta) jest świeża bez ponownego pytania magazynu. */
    static final long TTL_MS = 60_000L;
    /** Twardy limit migawek; przy rotacji graczy mapa nie rośnie ponad to. */
    static final int MAX_ENTRIES = 512;

    private record Entry(@NotNull Optional<UUID> islandId, long at) { }

    private final SkylliaIntegration skyllia;
    private final Function<UUID, CompletableFuture<Optional<UUID>>> storageLookup;
    private final Predicate<UUID> online;
    private final ConcurrentHashMap<UUID, Entry> snapshot = new ConcurrentHashMap<>();

    /**
     * @param skyllia       szybka ścieżka dla graczy online
     * @param storageLookup autorytatywne źródło offline (aktywne członkostwo);
     *                      wołane poza wątkiem renderu, wynik wraca do migawki
     * @param online        rozstrzyga ścieżkę; przekazywany, żeby klasa była
     *                      testowalna bez serwera Bukkit
     */
    public OfflineIslandIdResolver(@NotNull SkylliaIntegration skyllia,
                                   @NotNull Function<UUID, CompletableFuture<Optional<UUID>>> storageLookup,
                                   @NotNull Predicate<UUID> online) {
        this.skyllia = skyllia;
        this.storageLookup = storageLookup;
        this.online = online;
    }

    /**
     * Identyfikator wyspy gracza dla placeholdera: {@code null}, gdy nieznany
     * (odznaka wtedy jest pusta — jak dotąd). Nigdy nie blokuje wołającego
     * i nigdy nie dotyka SQL-a na wątku renderu.
     */
    public @Nullable UUID resolve(@NotNull UUID playerId) {
        if (online.test(playerId)) {
            try {
                Optional<UUID> live = skyllia.cachedIslandIdOf(playerId);
                if (live.isPresent()) {
                    snapshot.put(playerId, new Entry(live, System.currentTimeMillis()));
                    return live.get();
                }
            } catch (RuntimeException failure) {
                // szybka ścieżka zawiodła — zostaje migawka/pusty wynik jak dotąd
            }
        }
        Entry entry = snapshot.get(playerId);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.at() <= TTL_MS) {
            return entry.islandId().orElse(null);
        }
        // Pudło/przeterminowanie: rezerwujemy okno TTL i dociągamy magazyn w tle.
        // Rezerwacja zatrzymuje lawinę zapytań przy każdym refreshu TAB.
        long reservedAt = now;
        snapshot.put(playerId, new Entry(entry == null ? Optional.empty() : entry.islandId(), reservedAt));
        trim(now);
        scheduleLoad(playerId, reservedAt);
        return entry == null ? null : entry.islandId().orElse(null);
    }

    private void scheduleLoad(@NotNull UUID playerId, long reservedAt) {
        try {
            storageLookup.apply(playerId).whenComplete((value, error) -> {
                if (error != null) {
                    return; // rezerwacja wygaśnie po TTL; kolejny refresh spróbuje ponownie
                }
                snapshot.compute(playerId, (ignored, current) ->
                        current == null || current.at() == reservedAt
                                ? new Entry(value, System.currentTimeMillis())
                                : current);
            });
        } catch (RuntimeException ignored) {
            // magazyn niedostępny — odznaka offline zostaje pusta, jak przed r48
        }
    }

    /** Limit migawek: najpierw przeterminowane, potem najstarsze (deterministycznie). */
    private void trim(long now) {
        if (snapshot.size() <= MAX_ENTRIES) {
            return;
        }
        snapshot.entrySet().removeIf(e -> now - e.getValue().at() > TTL_MS);
        while (snapshot.size() > MAX_ENTRIES) {
            snapshot.entrySet().stream()
                    .min(Comparator.comparingLong((java.util.Map.Entry<UUID, Entry> e) -> e.getValue().at()))
                    .ifPresent(e -> snapshot.remove(e.getKey(), e.getValue()));
        }
    }

    /** Pakietowy podgląd dla testów: rozmiar migawki. */
    int snapshotSizeForTest() {
        return snapshot.size();
    }
}
