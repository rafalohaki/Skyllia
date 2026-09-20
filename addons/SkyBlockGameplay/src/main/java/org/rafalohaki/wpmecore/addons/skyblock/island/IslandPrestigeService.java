package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;

import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Prestiż Wyspy: kupno kolejnego poziomu za monety z <b>konta wyspy</b>.
 *
 * <p>Kolejność zakupu jest ta sama co w księdze: odczyt zapisanego poziomu →
 * {@code withdrawIsland} z identyfikatorem transakcji
 * {@code prestige:<wyspa>:<poziom+1>} → zapis przejścia w
 * {@code wpme_sb_island_prestige}. Idempotencję dają dwie warstwy: księga
 * rozlicza dany {@code transactionId} tylko raz, a zapis poziomu jest strażony
 * poziomem wyjściowym. Dlatego podwójne kliknięcie, dwa równoległe żądania i
 * retry po awarii między debetem a zapisem kończą się <b>jednym</b> zakupem.
 *
 * <p>Klasy nie zna Bukkit: emisję nagrody (broadcast) dostarcza konstruktor,
 * a tytuł zapisuje {@link IslandTitleService} — dzięki temu serwis testuje się
 * na SQLite w pamięci z prawdziwym {@link LedgerService}.
 */
public final class IslandPrestigeService {

    /** Wynik próby zakupu — menu tłumaczy go na komunikat po polsku. */
    public enum Status {
        /** Poziom podniesiony w tym wywołaniu. */
        PURCHASED,
        /** Ten poziom był już opłacony (powtórka); stan domknięty, bez drugiego debetu. */
        ALREADY_PURCHASED,
        /** Bank wyspy nie ma dość monet — nic nie zeszło z konta. */
        NO_FUNDS,
        /** Osiągnięty limit {@code max-level}. */
        MAX_LEVEL
    }

    /** Wynik zakupu: status, poziom po operacji, koszt i saldo banku wyspy po operacji. */
    public record PurchaseResult(@NotNull Status status, int level, long cost, long balance) {
    }

    /** Stan wyspy dla menu i kafelka; {@code nextCost} = 0 na limicie. */
    public record State(int level, long spentMinor, long nextCost, boolean maxed,
                        @Nullable String title) {
    }

    /** Awans prestiżu — materiał na broadcast wyspy. */
    public record PrestigeUp(@NotNull UUID islandId, int level, long cost,
                             @Nullable String title) {
    }

    private final Logger logger;
    private final IslandPrestige.Settings settings;
    private final IslandPrestigeDao dao;
    private final LedgerService ledger;
    private final IslandTitleService titles;
    private final Consumer<PrestigeUp> onPrestigeUp;
    /** Poziom z ostatniego odczytu/zakupu — kafelek w Centrum Wyspy czyta go bez bazy. */
    private final Map<UUID, Integer> levels = new ConcurrentHashMap<>();
    /** Jedno kupno na wyspę naraz; równoległe kliknięcia dostają ten sam wynik. */
    private final Map<UUID, CompletableFuture<PurchaseResult>> inFlight = new ConcurrentHashMap<>();

    public IslandPrestigeService(@NotNull Logger logger,
                                 @NotNull IslandPrestige.Settings settings,
                                 @NotNull IslandPrestigeDao dao,
                                 @NotNull LedgerService ledger,
                                 @NotNull IslandTitleService titles,
                                 @NotNull Consumer<PrestigeUp> onPrestigeUp) {
        this.logger = logger;
        this.settings = settings;
        this.dao = dao;
        this.ledger = ledger;
        this.titles = titles;
        this.onPrestigeUp = onPrestigeUp;
    }

    public @NotNull IslandPrestige.Settings settings() {
        return settings;
    }

    /** Ostatnio widziany poziom prestiżu; pusty, gdy wyspy jeszcze nie czytaliśmy. */
    public @NotNull OptionalInt cachedLevel(@NotNull UUID islandId) {
        Integer level = levels.get(islandId);
        return level == null ? OptionalInt.empty() : OptionalInt.of(level);
    }

    /**
     * Bonusowe sloty minionków z prestiżu wyspy — dla ścieżki stawiania
     * minionka (synchroniczna, czyta cache; nieznana wyspa = 0, a nie I/O
     * w wątku interakcji).
     */
    public int minionSlotsFor(@NotNull UUID islandId) {
        return settings.extraMinionSlots(cachedLevel(islandId).orElse(0));
    }

    /** Mnożnik szczęścia na kryształy rozgrywki dla wyspy (1.0 bez bonusu). */
    public double crystalLuckMultiplierFor(@NotNull UUID islandId) {
        return settings.crystalLuckMultiplier(cachedLevel(islandId).orElse(0));
    }

    /** Bieżący stan wyspy z bazy — jedno zapytanie, zero blokowania. */
    public @NotNull CompletableFuture<State> state(@NotNull UUID islandId) {
        return dao.find(islandId).thenApply(stored -> {
            int current = stored.map(IslandPrestigeDao.State::level).orElse(0);
            long spent = stored.map(IslandPrestigeDao.State::spentMinor).orElse(0L);
            levels.put(islandId, current);
            return describe(current, spent);
        });
    }

    /**
     * Kupno kolejnego poziomu. Nigdy nie rzuca synchronicznie; równoległe
     * wywołania dla tej samej wyspy dzielą jeden zakup.
     */
    public @NotNull CompletableFuture<PurchaseResult> purchase(@NotNull UUID islandId) {
        CompletableFuture<PurchaseResult> attempt = new CompletableFuture<>();
        CompletableFuture<PurchaseResult> running = inFlight.putIfAbsent(islandId, attempt);
        if (running != null) {
            return running;
        }
        try {
            attemptPurchase(islandId).whenComplete((result, failure) -> {
                inFlight.remove(islandId, attempt);
                if (failure != null) {
                    attempt.completeExceptionally(failure);
                } else {
                    attempt.complete(result);
                }
            });
        } catch (RuntimeException failure) {
            // Wejście nie może rzucić w rękę klikającego i nie może zostawić
            // wpisu „w locie”, bo kolejne kliknięcia czekałyby na niego zawsze.
            inFlight.remove(islandId, attempt);
            attempt.completeExceptionally(failure);
        }
        return attempt;
    }

    private @NotNull CompletableFuture<PurchaseResult> attemptPurchase(@NotNull UUID islandId) {
        return dao.find(islandId).thenCompose(stored -> {
            int level = stored.map(IslandPrestigeDao.State::level).orElse(0);
            levels.put(islandId, level);
            if (level >= settings.maxLevel()) {
                return CompletableFuture.completedFuture(
                        new PurchaseResult(Status.MAX_LEVEL, level, 0L, 0L));
            }
            long cost = settings.costFor(level);
            String transactionId = "prestige:" + islandId + ":" + (level + 1);
            return ledger.withdrawIsland(islandId, cost, transactionId, "island_prestige")
                    .thenCompose(withdrawal -> settle(islandId, level, cost, withdrawal));
        });
    }

    /**
     * Debet rozliczony raz na {@code transactionId}: drugie wywołanie wraca
     * z {@code applied=false}, więc doliczenie kosztu drugi raz jest niemożliwe.
     * Wtedy tylko domykamy wiersz poziomu (powtórka po awarii między debetem
     * a zapisem) i nie emitujemy drugiego awansu.
     */
    private @NotNull CompletableFuture<PurchaseResult> settle(
            @NotNull UUID islandId, int level, long cost, @NotNull LedgerDao.Mutation withdrawal) {
        if (withdrawal.insufficient()) {
            return CompletableFuture.completedFuture(
                    new PurchaseResult(Status.NO_FUNDS, level, cost, withdrawal.balance()));
        }
        Status status = withdrawal.applied() ? Status.PURCHASED : Status.ALREADY_PURCHASED;
        return dao.recordLevel(islandId, level, level + 1, cost).thenCompose(ignored -> {
            levels.put(islandId, level + 1);
            return grantTitle(islandId, level + 1).thenApply(appliedTitle -> {
                if (status == Status.PURCHASED) {
                    onPrestigeUp.accept(new PrestigeUp(islandId, level + 1, cost, appliedTitle));
                }
                return new PurchaseResult(status, level + 1, cost, withdrawal.balance());
            });
        });
    }

    /**
     * Tytuł kosmetyczny awansu trafia do trwałego magazynu (migracja #13), więc
     * widać go także po restarcie i poza menu prestiżu. Zakup jest już
     * rozliczony w księdze i w tabeli poziomu, więc awaria magazynu tytułów
     * <b>nie</b> może cofnąć zakupu ani kazać graczowi płacić drugi raz —
     * ląduje w logu, a następny poziom nadaje tytuł ponownie (nadanie jest
     * idempotentne po {@code source_id}).
     *
     * @return tytuł, który <b>faktycznie</b> wszedł na wyspę, albo {@code null},
     *         gdy magazyn zachował tytuł o wyższym priorytecie — broadcast nie
     *         może wtedy obiecywać nowego tytułu, którego wyspa nie dostała
     */
    // Bez @Nullable w pozycji typu: boosted-yaml cieniuje starą kopię
    // org.jetbrains.annotations (bez TARGET TYPE_USE) przed właściwym
    // artefaktem na classpath — kompilator odrzuca CompletableFuture<@Nullable String>.
    private @NotNull CompletableFuture<String> grantTitle(@NotNull UUID islandId, int level) {
        String title = settings.titleFor(level);
        if (title == null || title.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return titles.grant(islandId, title, "prestige:" + level)
                .thenApply(status -> status == IslandTitleService.GrantStatus.GRANTED
                        ? title
                        : null)
                .exceptionally(failure -> {
                    logger.log(Level.WARNING, "Nie udało się zapisać tytułu prestiżu "
                            + level + " dla wyspy " + islandId, failure);
                    return null;
                });
    }

    private @NotNull State describe(int level, long spentMinor) {
        boolean maxed = level >= settings.maxLevel();
        return new State(level, spentMinor,
                maxed ? 0L : settings.costFor(level), maxed, settings.titleFor(level));
    }
}
