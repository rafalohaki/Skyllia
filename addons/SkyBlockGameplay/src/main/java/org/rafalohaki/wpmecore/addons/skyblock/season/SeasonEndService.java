package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Runda 4: cykl zamknięcia sezonu — komenda operatora {@code /sezon zamknij
 * --confirm} (ZERO auto-zamykania), spec docs/spec/sezony-rankingi.md §6.
 *
 * <p>Przepływ (idempotentny; transakcyjny tam, gdzie jedna baza to umożliwia):
 * <ol>
 *   <li>guard „czy now &gt; end?” — bez force odmowa przed czasem,</li>
 *   <li>guard wyścigu (C4), warstwa 1 — świeży odczyt
 *       {@code wpme_sb_season_state}: gdy DB != seasonSupplier → odmowa
 *       bez JAKIEGOKOLWIEK zapisu (tylko 10-arg ctor; legacy pomija),</li>
 *   <li>snapshot TOP-10 rankingu sezonowego do {@code wpme_sb_season_history}
 *       ({@code INSERT OR IGNORE} — ponowne zamknięcie nie nadpisuje),</li>
 *   <li>nagrody kosmetyczne rang 1..3 (progi z cosmetics.yml: 4/2/1 części)
 *       przez {@link CosmeticDispatcher} — produkcja deleguje do
 *       {@code recordClaim} + istniejącego toru kosmetycznego, więc dedupe
 *       jest naturalny: powtórne zamknięcie tego samego sezonu niczego nie
 *       wyda drugi raz,</li>
 *   <li>rollover {@code current_season + 1} w {@code wpme_sb_season_state}
 *       i czyszczenie punktów zamkniętego sezonu — w JEDNEJ transakcji,
 *       z warstwą 2 guardu wyścigu (C4): licznik podbija warunkowy UPDATE
 *       {@code WHERE id=1 AND current_season=?}; 0 wierszy → SQLException
 *       i pełny ROLLBACK transakcji,</li>
 *   <li>etykieta zakresu dat nowego sezonu (dd.MM.yyyy) dla broadcastu.</li>
 * </ol>
 *
 * <p>Decyzje otwarte (jawne):
 * <ul>
 *   <li>ranking źródłowy nagród = ranking sezonowy graczy
 *       ({@code wpme_sb_season_points}), nie ranking wysp banku;</li>
 *   <li>snapshot do historii ograniczony do TOP-10 (decyzja operatora
 *       2026-08-25); pełne archiwum nie zasila żadnego widoku;</li>
 *   <li>zwycięzca offline przy zamknięciu: roszczenie NIE jest zapisywane,
 *       gracz trafia do logu audytowego ({@code DEFERRED_OFFLINE}); zapis
 *       roszczenia bez dostawy spaliłby nagrodę bezpowrotnie;</li>
 *   <li>monety/Lotosy/participation z tabeli progów pozostają poza tym
 *       przepływem (osobna decyzja operatora); tu tylko kosmetyka top 4/2/1.</li>
 *   <li>edycje nazwane (editions.yml): stampClosed wewnątrz transakcji
 *       rollovera, refreshActivation po COMMIT (postCommitAction); wszystkie
 *       parametry nullable — null = zachowanie legacy byte-stabilne;</li>
 *   <li>guard czasu i etykiety liczą od {@code seasonEndMillis} dostarczonego
 *       wywoławcą — przy włączonych edycjach to koniec AKTYWNEJ edycji
 *       (decyzja A1/R2), nie matematyka epoch+length.</li>

 *   <li>guard wyścigu dwóch zamknięć (CLI --confirm vs GUI confirm, C4):
 *       warstwa 1 to tylko odczyt (odmowa „Sezon został już zamknięty
 *       (wyścig z innym operatorem).” przed jakimikolwiek zapisami); warstwa 2
 *       to warunkowy UPDATE licznika wewnątrz transakcji rollovera. Kolejność
 *       kroków zostawiona (rollover NA KOŃCU, po snapshotie i kosmetyce) —
 *       przeniesienie rollovera przed nagrody złamałoby idempotencję
 *       odtworzeniową: po COMMIT rollovera i awarii dystrybucji ponowna próba
 *       zostałaby permanentnie odmówiona przez warstwę 1 (supplier wciąż na
 *       starym sezonie), a roszczenia top 3 przepadłyby. Przy obecnej
 *       kolejności porażka warstwy 2 następuje PO dystrybucji, ale jej efekty
 *       są netto zerowe ({@code INSERT OR IGNORE} snapshota + deterministyczny
 *       {@link #rewardOperationId} dedupe outboxa); rollback obejmuje CAŁĄ
 *       transakcję rollovera: seed stanu, DELETE punktów, stampClosed edycji
 *       i podbicie licznika,</li>
 *   <li>{@code #currentSeasonFromDb()} zwraca {@code CompletableFuture},
 *       nie gołe {@code int}: {@link SqlService} jest w całości asynchroniczny,
 *       a blokujący join zawiesiłby wątek komendy.</li>
 * </ul>
 */
public final class SeasonEndService {

    /** Progi nagród wg spec §6. */
    record RewardTier(long minRank, long maxRank, long coins, int lotus) { }

    private static final List<RewardTier> TIERS = List.of(
            new RewardTier(1, 1, 50_000L, 10),
            new RewardTier(2, 3, 30_000L, 5),
            new RewardTier(4, 10, 15_000L, 3),
            new RewardTier(11, 50, 5_000L, 1),
            new RewardTier(51, 100, 2_500L, 0));

    private static final long PARTICIPATION_COINS = 500L;

    /** Ile miejsc rankingu sezonowego trafia do historii przy zamknięciu. */
    static final int SNAPSHOT_TOP_N = 10;

    /** Najwyższa ranga otrzymująca kosmetykę (cosmetics.yml: 1→4, 2→2, 3→1 części). */
    static final int COSMETIC_TOP_RANK = 3;

    /** Ile miejsc pokazuje podgląd dry-run („Do archiwum trafią m.in.: …”). */
    static final int DRY_RUN_PREVIEW_TOP_N = 3;

    private static final DateTimeFormatter LABEL_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /**
     * Wydaje kosmetykę nagrodową za miejsce w rankingu. Produkcja:
     * {@code coordinator.recordClaim} (deterministyczny dedupe per sezon+gracz)
     * → dostawa przez istniejący tor kosmetyczny, ale wyłącznie gdy gracz jest
     * online (dostawa wymaga wątku encji gracza).
     */
    public interface CosmeticDispatcher {

        enum DispatchResult {
            /** Wydano teraz (roszczenie zapisane + dostawa zaplanowana). */
            DELIVERED,
            /** Już rozliczone wcześniej — niczego nie wydano drugi raz. */
            ALREADY_SETTLED,
            /** Gracz offline — roszczenie niezapisane, do domknięcia później. */
            DEFERRED_OFFLINE
        }

        @NotNull CompletableFuture<DispatchResult> dispatch(
                @NotNull UUID playerId, int seasonId, int rank);
    }

    /**
     * Wynik próby zamknięcia. {@code closed=false} oznacza odmowę albo dry-run
     * — {@code refusalReason} niesie komunikat dla operatora (dni pozostałe /
     * podsumowanie „co się stanie”).
     */
    public record CloseOutcome(boolean closed,
                               int closedSeason,
                               int newSeason,
                               long archivedRows,
                               int rewardedNow,
                               int alreadySettled,
                               int deferredOffline,
                               @Nullable String refusalReason) {

        public boolean refused() {
            return !closed;
        }
    }

    private final SqlService sql;
    private final Supplier<Integer> seasonSupplier;
    private final LongSupplier seasonEndMillis;
    private final LongSupplier seasonLengthMillis;
    private final CosmeticDispatcher dispatcher;
    private final ZoneId zone;
    private final Logger audit;
    /** Edycje nazwane; null = tryb legacy (stare wywoławcy/testy — noop). */
    private final @Nullable SeasonEditionDao editionDao;
    private final @Nullable EditionRegistry editionRegistry;
    /** Akcja po udanym COMMIT rollovera (np. refreshActivation); null = brak. */
    private final @Nullable Runnable postCommitAction;
    /**
     * C4: czy warstwa 1 guardu wyścigu jest aktywna. True wyłącznie dla
     * rozszerzonego (10-arg) konstruktora; legacy (7-arg) pomija świeży odczyt
     * DB, ale warstwa 2 (warunkowy UPDATE w tx) działa zawsze.
     */
    private final boolean racePreCheckEnabled;

    public SeasonEndService(@NotNull SqlService sql,
                            @NotNull Supplier<Integer> seasonSupplier,
                            @NotNull LongSupplier seasonEndMillis,
                            @NotNull LongSupplier seasonLengthMillis,
                            @NotNull CosmeticDispatcher dispatcher,
                            @NotNull ZoneId zone,
                            @NotNull Logger audit) {
        this(sql, seasonSupplier, seasonEndMillis, seasonLengthMillis,
                dispatcher, zone, audit, null, null, null, false);
    }

    /**
     * Wariant rozszerzony (edycje nazwane): {@code editionDao} dostaje
     * {@code stampClosed} wewnątrz transakcji rollovera, {@code editionRegistry}
     * zasila {@link #nextEditionLabel()}, a {@code postCommitAction} jest
     * odpalany po COMMIT (np. {@code SeasonEditionService.refreshActivation}).
     * Wszystkie trzy opcjonalne — null zachowuje dotychczasowe zachowanie
     * byte-stabilnie.
     */
    public SeasonEndService(@NotNull SqlService sql,
                            @NotNull Supplier<Integer> seasonSupplier,
                            @NotNull LongSupplier seasonEndMillis,
                            @NotNull LongSupplier seasonLengthMillis,
                            @NotNull CosmeticDispatcher dispatcher,
                            @NotNull ZoneId zone,
                            @NotNull Logger audit,
                            @Nullable SeasonEditionDao editionDao,
                            @Nullable EditionRegistry editionRegistry,
                            @Nullable Runnable postCommitAction) {
        this(sql, seasonSupplier, seasonEndMillis, seasonLengthMillis,
                dispatcher, zone, audit, editionDao, editionRegistry,
                postCommitAction, true);
    }

    private SeasonEndService(@NotNull SqlService sql,
                             @NotNull Supplier<Integer> seasonSupplier,
                             @NotNull LongSupplier seasonEndMillis,
                             @NotNull LongSupplier seasonLengthMillis,
                             @NotNull CosmeticDispatcher dispatcher,
                             @NotNull ZoneId zone,
                             @NotNull Logger audit,
                             @Nullable SeasonEditionDao editionDao,
                             @Nullable EditionRegistry editionRegistry,
                             @Nullable Runnable postCommitAction,
                             boolean racePreCheckEnabled) {
        this.sql = sql;
        this.seasonSupplier = seasonSupplier;
        this.seasonEndMillis = seasonEndMillis;
        this.seasonLengthMillis = seasonLengthMillis;
        this.dispatcher = dispatcher;
        this.zone = zone;
        this.audit = audit;
        this.editionDao = editionDao;
        this.editionRegistry = editionRegistry;
        this.postCommitAction = postCommitAction;
        this.racePreCheckEnabled = racePreCheckEnabled;
    }


    /** Odmowa przy przegranej w wyścigu dwóch zamknięć (C4, warstwa 1). */
    static final String RACE_REFUSAL =
            "Sezon został już zamknięty (wyścig z innym operatorem).";
    /**
     * Zamyka bieżący sezon. {@code force=true} omija guard czasu (awaryjnie:
     * testy albo świadoma decyzja operatora przed wygaśnięciem).
     */
    public @NotNull CompletableFuture<CloseOutcome> close(long nowMillis, boolean force) {
        int season = seasonSupplier.get();
        long endMillis = seasonEndMillis.getAsLong();
        if (!force && nowMillis < endMillis) {
            long daysLeft = SeasonSchedule.daysUntil(endMillis, nowMillis);
            String reason = "Sezon jeszcze nie wygasł — zostało " + daysLeft + " dni "
                    + "(do " + label(endMillis) + "). Zamknięcie przed czasem wymaga --force.";
            audit.info("Zamknięcie sezonu " + season + " odmówione: " + reason);
            return CompletableFuture.completedFuture(new CloseOutcome(
                    false, season, season, 0L, 0, 0, 0, reason));
        }

        audit.info("Zamknięcie sezonu " + season + " rozpoczęte"
                + (force ? " (--force przed czasem)" : "") + ".");
        // C4 warstwa 1: świeży odczyt stanu z DB — jeśli inny operator zamknął
        // już sezon (supplier wciąż zwraca stary numer), odmowa BEZ żadnych
        // zapisów. Legacy ctor pomija ten krok (byte-kompatybilne zachowanie).
        if (!racePreCheckEnabled) {
            return runClosePipeline(season);
        }
        return currentSeasonFromDb().thenCompose(dbCurrent -> {
            if (dbCurrent != null && dbCurrent != season) {
                audit.warning("Zamknięcie sezonu " + season + " odmówione: DB ma "
                        + dbCurrent + "; " + RACE_REFUSAL);
                return CompletableFuture.completedFuture(new CloseOutcome(
                        false, season, season, 0L, 0, 0, 0, RACE_REFUSAL));
            }
            return runClosePipeline(season);
        });
    }

    /**
     * C4 warstwa 1: lekki odczyt {@code wpme_sb_season_state}. Zwraca
     * {@code null} gdy wiersz jeszcze nie istnieje (świeża baza — wtedy nic
     * nie zostało jeszcze zamknięte, brak odmowy). Celowo asynchronicznie:
     * blokujący join zawiesiłby wątek komendy.
     */
    private @NotNull CompletableFuture<Integer> currentSeasonFromDb() {
        return sql.query(
                        "SELECT current_season FROM wpme_sb_season_state WHERE id = 1",
                        row -> row.getInt(1))
                .thenApply(rows -> rows.isEmpty() ? null : rows.get(0));
    }

    private @NotNull CompletableFuture<CloseOutcome> runClosePipeline(int season) {
        return snapshotTop10(season)
                .thenCompose(archived -> dispatchCosmeticRewards(season, archived))
                .thenCompose(snapshot -> rolloverAndWipe(season).thenApply(newSeason -> {
                    runPostCommitAction();
                    return finish(snapshot.tally(), snapshot.archivedRows(),
                            season, newSeason);
                }));
    }

    /**
     * Akcja po COMMIT (np. {@code SeasonEditionService.refreshActivation});
     * błąd nie wywala zamknięcia sezonu — tylko wpis audytowy.
     */
    private void runPostCommitAction() {
        if (postCommitAction == null) {
            return;
        }
        try {
            postCommitAction.run();
        } catch (RuntimeException failure) {
            audit.warning("Akcja po zamknięciu sezonu nie powiodła się: "
                    + failure.getMessage());
        }
    }

    /**
     * Etykieta zakresu dat NOWEGO sezonu: [koniec starego, koniec + długość].
     * Null gdy kalendarz wyłączony (tryb pilotowy — brak zakresu dat).
     */
    public @Nullable String nextSeasonRangeLabel() {
        long length = seasonLengthMillis.getAsLong();
        if (length <= 0L) {
            return null;
        }
        long start = seasonEndMillis.getAsLong();
        return label(start) + " – " + label(start + length);
    }

    /**
     * Nazwa wyświetlana następnej edycji (edycje nazwane z editions.yml) po
     * końcu zamykanego sezonu. Null gdy edycje wyłączne albo brak kolejnej —
     * wywoławca spada wtedy na {@link #nextSeasonRangeLabel()}. Celowo bez
     * parametru „teraz”: liczymy od końca sezonu ({@code seasonEndMillis}),
     * nie od zegara wywołania.
     */
    public @Nullable String nextEditionLabel() {
        if (editionDao == null || editionRegistry == null
                || !editionRegistry.isEnabled()) {
            return null;
        }
        Edition next = editionRegistry.nextAfter(seasonEndMillis.getAsLong());
        return next == null ? null : next.displayName();
    }

    private @NotNull CloseOutcome finish(RewardTally tally, long archived,
                                         int closedSeason, int newSeason) {
        audit.info("Sezon " + closedSeason + " zamknięty → sezon " + newSeason
                + "; nagrody teraz: " + tally.rewardedNow()
                + ", już rozliczone: " + tally.alreadySettled()
                + ", odroczone (offline): " + tally.deferredOffline() + ".");
        return new CloseOutcome(true, closedSeason, newSeason,
                archived, tally.rewardedNow(), tally.alreadySettled(),
                tally.deferredOffline(), null);
    }

    private @NotNull String label(long millis) {
        return LABEL_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    /** Krok (b): dopisz brakujące wiersze TOP-10 do historii; zwraca liczbę dopisanych.
     *  INSERT OR IGNORE zamiast … ON CONFLICT: po FROM (subquery) SQLite parsuje
     *  „ON” jako join (udokumentowana niejednoznaczność UPSERT za SELECT-em),
     *  co rozbijało całe zamknięcie sezonu (lab 2026-08-25). */
    private @NotNull CompletableFuture<Long> snapshotTop10(int seasonId) {
        return sql.update("""
                        INSERT OR IGNORE INTO wpme_sb_season_history
                            (season_id, player_uuid, points, rank_position, rewarded_at)
                        SELECT season_id, player_uuid, points,
                               ROW_NUMBER() OVER (ORDER BY points DESC, updated_at ASC),
                               ?
                        FROM (
                            SELECT * FROM wpme_sb_season_points
                            WHERE season_id = ?
                            ORDER BY points DESC, updated_at ASC LIMIT ?
                        )
                        """,
                System.currentTimeMillis(), seasonId, SNAPSHOT_TOP_N)
                .thenApply(inserted -> inserted == null ? 0L : (long) inserted)
                .thenApply(archived -> {
                    audit.info("Snapshot historii: dopisano " + archived
                            + " wierszy (TOP-" + SNAPSHOT_TOP_N + ").");
                    return archived;
                });
    }

    /**
     * Krok (c): nagrody rang 1..3 czytane z zamrożonej historii (nie z żywej
     * tabeli), sekwencyjnie — outbox trzyma jedną dzierżawę na gracza.
     */
    private record SnapshotResult(long archivedRows, RewardTally tally) { }


    private @NotNull CompletableFuture<SnapshotResult> dispatchCosmeticRewards(
            int seasonId, long archivedRows) {
        RewardTally tally = new RewardTally();
        return readTopRanks(seasonId).thenCompose(rows -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (RankedPlayer entry : rows) {
                chain = chain.thenCompose(ignored -> dispatcher
                        .dispatch(entry.playerUuid(), seasonId, entry.rank())
                        .thenApply(result -> {
                            tally.record(result);
                            audit.info("Nagroda sezonu " + seasonId + ", ranga "
                                    + entry.rank() + " (" + entry.playerUuid()
                                    + "): " + result + ".");
                            return null;
                        }));
            }
            return chain.thenApply(ignored -> new SnapshotResult(archivedRows, tally));
        });
    }

    private record RankedPlayer(@NotNull UUID playerUuid, int rank) { }

    private @NotNull CompletableFuture<List<RankedPlayer>> readTopRanks(int seasonId) {
        return sql.query("""
                        SELECT player_uuid, rank_position FROM wpme_sb_season_history
                        WHERE season_id = ? AND rank_position <= ?
                        ORDER BY rank_position
                        """,
                row -> new RankedPlayer(UUID.fromString(row.getString(1)), row.getInt(2)),
                seasonId, COSMETIC_TOP_RANK);
    }

    /**
     * Podgląd dry-run: TOP-3 punktów sezonu, to samo sortowanie co snapshot
     * historii ({@code points DESC, updated_at ASC}). Null gdy tablica punktów
     * pusta — zdanie pomijane w raporcie operatora. Celowo BEZ rozwiązywania
     * nicków offline: krótka forma UUID wystarcza do identyfikacji, a dry-run
     * nie generuje ciężkich zapytań o profil.
     */
    // Bez @Nullable w pozycji typu: boosted-yaml cieniuje starą kopię
    // org.jetbrains.annotations (bez TARGET TYPE_USE) przed właściwym artefaktem
    // na classpath — kompilator wtedy odrzuca CompletableFuture<@Nullable String>.
    public @NotNull CompletableFuture<String> topArchivePreview(
            int seasonId) {
        record TopPoints(@NotNull UUID playerUuid, long points) { }
        return sql.query("""
                        SELECT player_uuid, points FROM wpme_sb_season_points
                        WHERE season_id = ?
                        ORDER BY points DESC, updated_at ASC LIMIT ?
                        """,
                row -> new TopPoints(UUID.fromString(row.getString(1)),
                        row.getLong(2)),
                seasonId, DRY_RUN_PREVIEW_TOP_N)
                .thenApply(rows -> {
                    if (rows.isEmpty()) {
                        return null;
                    }
                    StringBuilder preview =
                            new StringBuilder("Do archiwum trafią m.in.: ");
                    for (int i = 0; i < rows.size(); i++) {
                        if (i > 0) {
                            preview.append(", ");
                        }
                        preview.append(i + 1).append(". ")
                                .append(shortUuid(rows.get(i).playerUuid()))
                                .append(" (").append(rows.get(i).points())
                                .append(" pkt)");
                    }
                    return preview.append('.').toString();
                });
    }

    /** Krótka forma UUID (8 znaków) dla podglądu dry-run. */
    private static @NotNull String shortUuid(@NotNull UUID playerUuid) {
        return playerUuid.toString().substring(0, 8);
    }

    /**
     * Kroki (d)+(e): rollover numeru sezonu i czyszczenie punktów zamkniętego
     * sezonu w jednej transakcji — historia jest już zamrożona, więc usunięcie
     * wierszy bieżących nie gubi danych, a tabela nie rośnie bez sensu.
     * Nowy sezon zaczyna się od pustej tabeli (wpis powstaje leniwie przy
     * pierwszych punktach — istniejący tor addPoints).
     *
     * <p>C4 warstwa 2 (guard wyścigu): licznik podbija warunkowy UPDATE
     * {@code WHERE id = 1 AND current_season = closedSeason}. Gdy równoległe
     * zamknięcie już podbiło licznik, UPDATE trafia 0 wierszy → SQLException
     * i pełny ROLLBACK całej transakcji (seed, DELETE punktów, stampClosed,
     * licznik — nic nie zostaje zapisane). Nowy numer to po prostu
     * {@code closedSeason + 1}: guard gwarantuje, że DB siedzi dokładnie na
     * {@code closedSeason} w chwili podbicia (dryf numerów wychodzi jako
     * porażka guardu zamiast cichego podwójnego inkrementu).
     */
    private @NotNull CompletableFuture<Integer> rolloverAndWipe(int closedSeason) {
        int newSeason = closedSeason + 1;
        long now = System.currentTimeMillis();
        return sql.withConnection(connection ->
                SqlSupport.inTransaction(connection, () -> {
                    // Gwarancja wiersza stanu (idempotentny seed jak fetchCurrentSeasonFromDb).
                    try (var seed = connection.prepareStatement("""
                            INSERT INTO wpme_sb_season_state (id, current_season, updated_at)
                            VALUES (1, ?, ?)
                            ON CONFLICT (id) DO NOTHING
                            """)) {
                        seed.setInt(1, closedSeason);
                        seed.setLong(2, now);
                        seed.executeUpdate();
                    }
                    // C4 warstwa 2: warunkowe podbicie licznika. 0 wierszy =
                    // ktoś zamknął sezon przed nami → wyjątek rozwija CAŁĄ tx.
                    try (var bump = connection.prepareStatement("""
                            UPDATE wpme_sb_season_state
                            SET current_season = ?, updated_at = ?
                            WHERE id = 1 AND current_season = ?
                            """)) {
                        bump.setInt(1, newSeason);
                        bump.setLong(2, now);
                        bump.setInt(3, closedSeason);
                        if (bump.executeUpdate() == 0) {
                            throw new SQLException("concurrent season close detected");
                        }
                    }
                    int wiped;
                    try (var wipe = connection.prepareStatement(
                            "DELETE FROM wpme_sb_season_points WHERE season_id = ?")) {
                        wipe.setInt(1, closedSeason);
                        wiped = wipe.executeUpdate();
                    }
                    // Edycje nazwane: domknięcie otwartych wpisów tego sezonu
                    // w TEJ SAMEJ transakcji (atomowe z rolloverem). join() jest
                    // tu bezpieczny WYŁĄCZNIE dlatego, że kontrakt DAO (patrz
                    // SeasonEditionDao#stampClosed) wymaga synchronicznego
                    // ukończenia na wątku wywołującym — dao pracuje na
                    // przekazanym connection. H1: orTimeout zawodzi głośno, gdyby
                    // przyszła implementacja złamała kontrakt (wiszący future
                    // zablokowałby lane SQL na zawsze zamiast rollbacku).
                    if (editionDao != null) {
                        int stamped = editionDao
                                .stampClosed(connection, closedSeason, now)
                                .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                .join();
                        audit.info("Domknięto wpisy edycji sezonu " + closedSeason
                                + ": " + stamped + " wiersz(y).");
                    }
                    audit.info("Rollover: sezon " + closedSeason + " → " + newSeason
                            + "; wyczyszczono " + wiped + " wierszy punktów.");
                    return newSeason;
                }));
    }

    private static final class RewardTally {
        private int rewardedNow;
        private int alreadySettled;
        private int deferredOffline;

        void record(CosmeticDispatcher.DispatchResult result) {
            switch (result) {
                case DELIVERED -> rewardedNow++;
                case ALREADY_SETTLED -> alreadySettled++;
                case DEFERRED_OFFLINE -> deferredOffline++;
            }
        }

        int rewardedNow() { return rewardedNow; }
        int alreadySettled() { return alreadySettled; }
        int deferredOffline() { return deferredOffline; }
    }

    /**
     * Nagroda za pozycję; puste gdy poza TOP-100.
     */
    static @NotNull OptionalReward rewardForRank(long rank) {
        for (RewardTier tier : TIERS) {
            if (rank >= tier.minRank() && rank <= tier.maxRank()) {
                return new OptionalReward(tier.coins(), tier.lotus());
            }
        }
        return OptionalReward.EMPTY;
    }

    record OptionalReward(long coins, int lotus) {
        static final OptionalReward EMPTY = new OptionalReward(0L, 0);
        boolean isEmpty() { return coins() <= 0 && lotus() <= 0; }
    }

    /**
     * OperationId nagrody dla gracza i pozycji — deterministyczny, więc
     * ponowna dystrybucja jest no-op (idempotencja outboxa).
     */
    static @NotNull String rewardOperationId(int season, @NotNull UUID playerUuid) {
        return "season-end:" + season + ":" + playerUuid;
    }

    /**
     * Uczestnictwo: każdy z ≥1 punktem dostaje drobne monety.
     */
    static boolean qualifiesForParticipation(long points) {
        return points > 0;
    }

    static long participationCoins() {
        return PARTICIPATION_COINS;
    }
}
