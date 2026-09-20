package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandOwner;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Składa dwie tablice wyników: bieżący sezon i dorobek wszech czasów.
 *
 * <p>Sezonowa liczy się per wyspa, więc trzeba ją przełożyć na właściciela —
 * inaczej nie byłoby czyjej głowy pokazać. Odpytanie Skyllii o właściciela
 * sięga do jej bazy, dlatego cała ścieżka biegnie poza wątkiem tickowym, a
 * wynik trafia do pamięci podręcznej: właściciel zmienia się rzadko, a tablica
 * odświeża się co kilka minut.
 */
public final class LeaderboardService {

    /** Obie tablice naraz — renderer podmienia je jednym ruchem, bez migotania. */
    record Boards(@NotNull List<LeaderboardEntry> season,
                  @NotNull List<LeaderboardEntry> allTime) {
    }

    private final SkyBlockTopRewardCoordinator coordinator;
    private final LeaderboardDao dao;
    private final @Nullable SkylliaIntegration skyllia;
    private final Map<UUID, IslandOwner> ownerCache = new ConcurrentHashMap<>();

    public LeaderboardService(@NotNull SkyBlockTopRewardCoordinator coordinator,
                       @NotNull LeaderboardDao dao,
                       @Nullable SkylliaIntegration skyllia) {
        this.coordinator = coordinator;
        this.dao = dao;
        this.skyllia = skyllia;
    }

    @NotNull CompletableFuture<Boards> refresh(int limit) {
        CompletableFuture<List<LeaderboardEntry>> season =
                coordinator.calculateRanking().thenApply(scores -> seasonEntries(scores, limit));
        CompletableFuture<List<LeaderboardEntry>> allTime =
                dao.allTime(limit).thenApply(LeaderboardService::withResolvedNames);
        return season.thenCombine(allTime, Boards::new);
    }

    private @NotNull List<LeaderboardEntry> seasonEntries(
            List<SkyBlockTopRewardCoordinator.IslandScore> scores, int limit) {
        List<LeaderboardEntry> out = new ArrayList<>();
        for (SkyBlockTopRewardCoordinator.IslandScore score : scores) {
            if (out.size() >= limit) {
                break;
            }
            /*
             * Wyspa bez właściciela wypada z tablicy zamiast zająć miejsce
             * pustym wierszem: usunięta wyspa nadal ma konto w księdze, więc
             * bez tego filtra ranking pokazywałby duchy.
             */
            IslandOwner owner = resolveOwner(score.islandId());
            if (owner == null) {
                continue;
            }
            out.add(new LeaderboardEntry(out.size() + 1, owner.playerId(), owner.name(),
                    score.totalScore(),
                    "<gray>bank</gray> " + Ui.money(score.bankBalance())
                            + " <dark_gray>•</dark_gray> <gray>zadania</gray> "
                            + score.completedQuests()));
        }
        return List.copyOf(out);
    }

    private @Nullable IslandOwner resolveOwner(UUID islandId) {
        IslandOwner cached = ownerCache.get(islandId);
        if (cached != null) {
            return cached;
        }
        if (skyllia == null) {
            return null;
        }
        Optional<IslandOwner> resolved;
        try {
            resolved = skyllia.ownerOf(islandId);
        } catch (RuntimeException failure) {
            // SKYBLOCK-2-9: połknięty błąd = wyspa po cichu znika z tablicy.
            // IllegalStateException ze strażnika wątku to błąd programisty —
            // głośniej (SEVERE); reszta to dane/dostęp — WARNING wystarczy.
            if (failure instanceof IllegalStateException) {
                java.util.logging.Logger.getLogger("wpmecore").log(java.util.logging.Level.SEVERE,
                        "Skyllia owner lookup called from a tick thread for island "
                                + islandId, failure);
            } else {
                java.util.logging.Logger.getLogger("wpmecore").warning("Skyllia owner lookup failed for island "
                        + islandId + ": " + failure);
            }
            return null;
        }
        resolved.ifPresent(owner -> ownerCache.put(islandId, owner));
        return resolved.orElse(null);
    }

    /**
     * Hall of fame zna tylko identyfikatory — nazwy dokłada serwer.
     *
     * <p>Świadomie {@code getOfflinePlayer(UUID).getName()}, bez sięgania do
     * Mojanga: to odczyt z lokalnej pamięci serwera, a każdy gracz z podium
     * kiedyś tu grał, więc nazwa jest znana. Gdy mimo wszystko jej nie ma,
     * zostaje skrócony identyfikator — lepszy niż puste miejsce.
     */
    static @NotNull List<LeaderboardEntry> withResolvedNames(List<LeaderboardEntry> entries) {
        List<LeaderboardEntry> out = new ArrayList<>(entries.size());
        for (LeaderboardEntry entry : entries) {
            out.add(new LeaderboardEntry(entry.rank(), entry.playerId(),
                    nameOf(entry.playerId()), entry.score(), entry.detail()));
        }
        return List.copyOf(out);
    }

    public static @NotNull String nameOf(UUID playerId) {
        try {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
            String name = offline.getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (Throwable headless) {
            // brak serwera w teście — schodzimy do identyfikatora
        }
        return playerId.toString().substring(0, 8);
    }
}
