package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;

import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ulepszenia Wyspy: kupno poziomu toru za monety z <b>konta wyspy</b>.
 *
 * <p>Ten sam kontrakt transakcyjny co {@link IslandPrestigeService}: odczyt
 * zapisanego poziomu → {@code withdrawIsland} z identyfikatorem transakcji
 * {@code island_upgrade:<wyspa>:<tor>:<poziom+1>} → zapis przejścia w
 * {@code wpme_sb_island_upgrades}. Księga rozlicza dany {@code transactionId}
 * tylko raz, a zapis poziomu jest strażony poziomem wyjściowym, więc podwójne
 * kliknięcie i retry kończą się <b>jednym</b> zakupem.
 *
 * <p>Klasa nie zna Bukkita: mutacje wyspy (rozmiar, sloty członków) dopina
 * wtyczka przez {@link #setPerkApplier(BiConsumer)}, a ogłoszenie wyspy przez
 * {@link Consumer} z konstruktora — serwis testuje się na SQLite w pamięci.
 */
public final class IslandUpgradesService {

    /** Wynik próby zakupu — menu tłumaczy go na komunikat po polsku. */
    public enum Status {
        /** Poziom podniesiony w tym wywołaniu. */
        PURCHASED,
        /** Ten poziom był już opłacony (powtórka); bez drugiego debetu. */
        ALREADY_PURCHASED,
        /** Bank wyspy nie ma dość monet — nic nie zeszło z konta. */
        NO_FUNDS,
        /** Osiągnięty limit toru ({@code max-level}) albo tor wyłączony w configu. */
        MAX_LEVEL
    }

    /** Wynik zakupu: status, tor, poziom po operacji, koszt i saldo po operacji. */
    public record PurchaseResult(@NotNull Status status, @NotNull IslandUpgrades.Track track,
                                 int level, long cost, long balance) {
    }

    /** Stan toru wyspy dla menu i kafelka; {@code nextCost} = 0 na limicie. */
    public record TrackState(@NotNull IslandUpgrades.Track track, int level, long spentMinor,
                             long nextCost, boolean maxed) {
    }

    /** Awans toru — materiał na ogłoszenie dla wyspy. */
    public record UpgradeUp(@NotNull UUID islandId, @NotNull IslandUpgrades.Track track,
                            int level, long cost) {
    }

    private final Logger logger;
    private final IslandUpgrades.Settings settings;
    private final IslandUpgradesDao dao;
    private final LedgerService ledger;
    private final Consumer<UpgradeUp> onUpgrade;
    /** Mutacje wyspy przy awansie (rozmiar, sloty członków) — fail-open jak w prestiżu. */
    private volatile BiConsumer<IslandUpgrades.Track, UpgradeUp> perkApplier = (track, up) -> { };
    /** Poziomy z ostatniego odczytu/zakupu per (wyspa, tor) — kafelki czytają bez bazy. */
    private final Map<String, Integer> levels = new ConcurrentHashMap<>();
    /** Jedno kupno na (wyspa, tor) naraz; równoległe kliknięcia dzielą jeden zakup. */
    private final Map<String, CompletableFuture<PurchaseResult>> inFlight = new ConcurrentHashMap<>();

    public IslandUpgradesService(@NotNull Logger logger,
                                 @NotNull IslandUpgrades.Settings settings,
                                 @NotNull IslandUpgradesDao dao,
                                 @NotNull LedgerService ledger,
                                 @NotNull Consumer<UpgradeUp> onUpgrade) {
        this.logger = logger;
        this.settings = settings;
        this.dao = dao;
        this.ledger = ledger;
        this.onUpgrade = onUpgrade;
    }

    public @NotNull IslandUpgrades.Settings settings() {
        return settings;
    }

    /** Dopina mutacje wyspy przy awansie (rozmiar, członkowie); domyślnie no-op. */
    public void setPerkApplier(@NotNull BiConsumer<IslandUpgrades.Track, UpgradeUp> perkApplier) {
        this.perkApplier = perkApplier;
    }

    private static @NotNull String key(@NotNull UUID islandId, @NotNull IslandUpgrades.Track track) {
        return islandId + "|" + track.dbId();
    }

    /** Ostatnio widziany poziom toru; pusty, gdy wyspy jeszcze nie czytaliśmy. */
    public @NotNull OptionalInt cachedLevel(@NotNull UUID islandId, @NotNull IslandUpgrades.Track track) {
        Integer level = levels.get(key(islandId, track));
        return level == null ? OptionalInt.empty() : OptionalInt.of(level);
    }

    /**
     * Bonusowe sloty minionków z toru MINIONS — synchroniczne (cache, brak I/O
     * w wątku interakcji); dopina się obok prestiżu przy budowie resolvera.
     */
    public int minionSlotsFor(@NotNull UUID islandId) {
        IslandUpgrades.TrackSettings track = settings.track(IslandUpgrades.Track.MINIONS);
        if (track == null) {
            return 0;
        }
        return cachedLevel(islandId, IslandUpgrades.Track.MINIONS).orElse(0) * track.slotsPerLevel();
    }

    /** Stan wszystkich włączonych torów wyspy — jedno zapytanie na tor, zero blokowania. */
    public @NotNull CompletableFuture<Map<IslandUpgrades.Track, TrackState>> states(
            @NotNull UUID islandId) {
        EnumMap<IslandUpgrades.Track, TrackState> result = new EnumMap<>(IslandUpgrades.Track.class);
        CompletableFuture<Void> all = CompletableFuture.completedFuture(null);
        for (IslandUpgrades.Track track : settings.tracks().keySet()) {
            all = all.thenCompose(ignored -> state(islandId, track)
                    .thenAccept(state -> result.put(track, state)));
        }
        return all.thenApply(ignored -> result);
    }

    /** Bieżący stan toru wyspy z bazy. */
    public @NotNull CompletableFuture<TrackState> state(@NotNull UUID islandId,
                                                       @NotNull IslandUpgrades.Track track) {
        IslandUpgrades.TrackSettings trackSettings = settings.track(track);
        return dao.find(islandId, track).thenApply(stored -> {
            int current = stored.map(IslandUpgradesDao.State::level).orElse(0);
            long spent = stored.map(IslandUpgradesDao.State::spentMinor).orElse(0L);
            levels.put(key(islandId, track), current);
            return describe(track, trackSettings, current, spent);
        });
    }

    /**
     * Kupno kolejnego poziomu toru. Nigdy nie rzuca synchronicznie; równoległe
     * wywołania dla tej samej (wyspa, tor) dzielą jeden zakup.
     */
    public @NotNull CompletableFuture<PurchaseResult> purchase(@NotNull UUID islandId,
                                                              @NotNull IslandUpgrades.Track track) {
        CompletableFuture<PurchaseResult> attempt = new CompletableFuture<>();
        String key = key(islandId, track);
        CompletableFuture<PurchaseResult> running = inFlight.putIfAbsent(key, attempt);
        if (running != null) {
            return running;
        }
        try {
            attemptPurchase(islandId, track).whenComplete((result, failure) -> {
                inFlight.remove(key, attempt);
                if (failure != null) {
                    attempt.completeExceptionally(failure);
                } else {
                    attempt.complete(result);
                }
            });
        } catch (RuntimeException failure) {
            inFlight.remove(key, attempt);
            attempt.completeExceptionally(failure);
        }
        return attempt;
    }

    private @NotNull CompletableFuture<PurchaseResult> attemptPurchase(@NotNull UUID islandId,
                                                                      @NotNull IslandUpgrades.Track track) {
        IslandUpgrades.TrackSettings trackSettings = settings.track(track);
        if (trackSettings == null) {
            return CompletableFuture.completedFuture(
                    new PurchaseResult(Status.MAX_LEVEL, track, 0, 0L, 0L));
        }
        return dao.find(islandId, track).thenCompose(stored -> {
            int level = stored.map(IslandUpgradesDao.State::level).orElse(0);
            levels.put(key(islandId, track), level);
            if (level >= trackSettings.maxLevel()) {
                return CompletableFuture.completedFuture(
                        new PurchaseResult(Status.MAX_LEVEL, track, level, 0L, 0L));
            }
            long cost = trackSettings.costFor(level);
            String transactionId = "island_upgrade:" + islandId + ":" + track.dbId() + ":" + (level + 1);
            return ledger.withdrawIsland(islandId, cost, transactionId, "island_upgrade")
                    .thenCompose(withdrawal -> settle(islandId, track, trackSettings, level, cost, withdrawal));
        });
    }

    /**
     * Debet rozliczony raz na {@code transactionId}: powtórka wraca z
     * {@code applied=false}, więc tylko domykamy wiersz poziomu i nie emitujemy
     * drugiego awansu — jak w {@link IslandPrestigeService#settle}.
     */
    private @NotNull CompletableFuture<PurchaseResult> settle(
            @NotNull UUID islandId, @NotNull IslandUpgrades.Track track,
            @NotNull IslandUpgrades.TrackSettings trackSettings,
            int level, long cost, @NotNull LedgerDao.Mutation withdrawal) {
        if (withdrawal.insufficient()) {
            return CompletableFuture.completedFuture(
                    new PurchaseResult(Status.NO_FUNDS, track, level, cost, withdrawal.balance()));
        }
        Status status = withdrawal.applied() ? Status.PURCHASED : Status.ALREADY_PURCHASED;
        return dao.recordLevel(islandId, track, level, level + 1, cost).thenCompose(ignored -> {
            levels.put(key(islandId, track), level + 1);
            if (status == Status.PURCHASED) {
                UpgradeUp up = new UpgradeUp(islandId, track, level + 1, cost);
                try {
                    perkApplier.accept(track, up);
                } catch (RuntimeException failure) {
                    logger.log(Level.WARNING, "Perk ulepszenia nie wszedł dla wyspy "
                            + islandId + " (tor " + track.dbId() + ", poziom " + (level + 1) + ")", failure);
                }
                try {
                    onUpgrade.accept(up);
                } catch (RuntimeException failure) {
                    logger.log(Level.WARNING, "Ogłoszenie ulepszenia nie wyszło dla wyspy "
                            + islandId, failure);
                }
            }
            return CompletableFuture.completedFuture(
                    new PurchaseResult(status, track, level + 1, cost, withdrawal.balance()));
        });
    }

    private @NotNull TrackState describe(@NotNull IslandUpgrades.Track track,
                                         @Nullable IslandUpgrades.TrackSettings trackSettings,
                                         int level, long spentMinor) {
        boolean maxed = trackSettings == null || level >= trackSettings.maxLevel();
        return new TrackState(track, level, spentMinor,
                maxed ? 0L : trackSettings.costFor(level), maxed);
    }
}
