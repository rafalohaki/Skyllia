package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.AccountKey;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ulepszenia wyspy na prawdziwym {@link LedgerService} i SQLite w pamięci:
 * monety schodzą z konta WYSPY (nie z portfela gracza), tory mają osobne
 * poziomy i ceny, podwójne kliknięcie i retry po utraconym potwierdzeniu
 * kończą się jednym debetem, a perka odpala się dopiero po zapisie poziomu.
 */
class IslandUpgradesServiceTest {

    private static final IslandUpgrades.Settings SETTINGS = new IslandUpgrades.Settings(Map.of(
            IslandUpgrades.Track.SIZE,
            new IslandUpgrades.TrackSettings(3, 1_000L, 2.0D, 5.0D, 0),
            IslandUpgrades.Track.MEMBERS,
            new IslandUpgrades.TrackSettings(2, 500L, 2.0D, 0.0D, 1),
            IslandUpgrades.Track.MINIONS,
            new IslandUpgrades.TrackSettings(2, 800L, 2.0D, 0.0D, 2)));

    private SingleConnectionSqlService sql;
    private LedgerService ledger;
    private IslandUpgradesService service;
    private final UUID island = UUID.randomUUID();
    private final List<IslandUpgradesService.UpgradeUp> applied = new ArrayList<>();
    private final List<IslandUpgradesService.UpgradeUp> broadcasts = new ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        ledger = new LedgerService(new LedgerDao(sql), 0L);
        service = new IslandUpgradesService(Logger.getLogger("UpgradesServiceTest"), SETTINGS,
                new IslandUpgradesDao.Sql(sql), ledger, broadcasts::add);
        service.setPerkApplier((track, up) -> applied.add(up));
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void purchasePaysFromIslandAccountAndRaisesTrackLevel() {
        seed(10_000L);

        IslandUpgradesService.PurchaseResult result =
                service.purchase(island, IslandUpgrades.Track.SIZE).join();

        assertEquals(IslandUpgradesService.Status.PURCHASED, result.status());
        assertEquals(IslandUpgrades.Track.SIZE, result.track());
        assertEquals(1, result.level());
        assertEquals(1_000L, result.cost());
        assertEquals(9_000L, ledger.authoritativeIslandBalance(island).join(),
                "koszt ulepszenia schodzi z konta wyspy");
        assertEquals(1, storedLevel(IslandUpgrades.Track.SIZE));
        assertEquals(1_000L, storedSpent(IslandUpgrades.Track.SIZE));
        assertEquals(1, service.cachedLevel(island, IslandUpgrades.Track.SIZE).orElse(-1));
        assertEquals(List.of(new IslandUpgradesService.UpgradeUp(
                island, IslandUpgrades.Track.SIZE, 1, 1_000L)), applied);
        assertEquals(applied, broadcasts, "perka i ogłoszenie widzą ten sam awans");
    }

    @Test
    void tracksHaveIndependentLevelsAndPrices() {
        seed(10_000L);

        assertEquals(1_000L, service.purchase(island, IslandUpgrades.Track.SIZE).join().cost());
        assertEquals(500L, service.purchase(island, IslandUpgrades.Track.MEMBERS).join().cost());
        assertEquals(1_000L, service.purchase(island, IslandUpgrades.Track.MEMBERS).join().cost(),
                "drugi poziom MEMBERS kosztuje 500 × 2^1");

        assertEquals(1, storedLevel(IslandUpgrades.Track.SIZE));
        assertEquals(2, storedLevel(IslandUpgrades.Track.MEMBERS));
        assertEquals(-1, storedLevel(IslandUpgrades.Track.MINIONS),
                "niekupiony tor nie ma wiersza");
    }

    @Test
    void purchaseWithoutFundsChangesNothing() {
        seed(300L);

        IslandUpgradesService.PurchaseResult result =
                service.purchase(island, IslandUpgrades.Track.MEMBERS).join();

        assertEquals(IslandUpgradesService.Status.NO_FUNDS, result.status());
        assertEquals(0, result.level());
        assertEquals(500L, result.cost(), "menu musi umieć powiedzieć, ile brakuje");
        assertEquals(300L, ledger.authoritativeIslandBalance(island).join());
        assertEquals(-1, storedLevel(IslandUpgrades.Track.MEMBERS),
                "nieudany zakup nie może zapisać poziomu");
        assertTrue(applied.isEmpty(), "nieudany zakup nie odpala perki");
        assertTrue(broadcasts.isEmpty(), "nieudany zakup nie ogłasza awansu");
    }

    @Test
    void doublePurchaseBuysOnce() {
        // Bramka na odczycie poziomu — jak w teście prestiżu: dwa kliknięcia
        // muszą trafić w okno jednego zakupu.
        GatedDao gated = new GatedDao(new IslandUpgradesDao.Sql(sql));
        IslandUpgradesService gatedService = new IslandUpgradesService(
                Logger.getLogger("UpgradesServiceTest"), SETTINGS, gated, ledger, broadcasts::add);
        seed(10_000L);

        CompletableFuture<IslandUpgradesService.PurchaseResult> first =
                gatedService.purchase(island, IslandUpgrades.Track.SIZE);
        CompletableFuture<IslandUpgradesService.PurchaseResult> second =
                gatedService.purchase(island, IslandUpgrades.Track.SIZE);

        assertSame(first, second, "drugie kliknięcie czeka na trwający zakup");
        gated.release();
        assertEquals(IslandUpgradesService.Status.PURCHASED, first.join().status());
        assertEquals(9_000L, ledger.authoritativeIslandBalance(island).join(),
                "jeden zakup = jeden debet");
        assertEquals(1, storedLevel(IslandUpgrades.Track.SIZE));
    }

    /**
     * Awaria między debetem a zapisem wiersza: księga zna transakcję
     * {@code island_upgrade:<wyspa>:size:1}, tabela ulepszeń jeszcze nie.
     * Powtórka musi domknąć stan i NIE ściągnąć monet drugi raz.
     */
    @Test
    void replayAfterLostConfirmationDoesNotChargeTwice() {
        seed(10_000L);
        ledger.withdrawIsland(island, 1_000L,
                "island_upgrade:" + island + ":size:1", "island_upgrade").join();
        assertEquals(9_000L, ledger.authoritativeIslandBalance(island).join());

        IslandUpgradesService.PurchaseResult result =
                service.purchase(island, IslandUpgrades.Track.SIZE).join();

        assertEquals(IslandUpgradesService.Status.ALREADY_PURCHASED, result.status());
        assertEquals(1, result.level());
        assertEquals(9_000L, ledger.authoritativeIslandBalance(island).join(),
                "powtórka nie może zapłacić drugi raz");
        assertEquals(1, storedLevel(IslandUpgrades.Track.SIZE),
                "powtórka domyka brakujący wiersz poziomu");
        assertEquals(1_000L, storedSpent(IslandUpgrades.Track.SIZE));
        assertTrue(broadcasts.isEmpty(), "powtórka nie ogłasza drugiego awansu");
    }

    @Test
    void maxLevelBlocksFurtherPurchases() {
        seed(100_000L);
        service.purchase(island, IslandUpgrades.Track.MEMBERS).join();
        service.purchase(island, IslandUpgrades.Track.MEMBERS).join();
        long balanceAtMax = ledger.authoritativeIslandBalance(island).join();

        IslandUpgradesService.PurchaseResult result =
                service.purchase(island, IslandUpgrades.Track.MEMBERS).join();

        assertEquals(IslandUpgradesService.Status.MAX_LEVEL, result.status());
        assertEquals(2, result.level());
        assertEquals(balanceAtMax, ledger.authoritativeIslandBalance(island).join());
        assertEquals(2, storedLevel(IslandUpgrades.Track.MEMBERS));
    }

    /**
     * Zapis poziomu jest strażony poziomem wyjściowym (migracja #14): powtórka
     * tego samego przejścia to no-op, a spóźniony zapis z nieaktualnym poziomem
     * nie cofa wiersza ani nie dolicza kosztu drugi raz.
     */
    @Test
    void staleLevelWriteCannotInflateSpentOrLowerTheRow() {
        IslandUpgradesDao dao = new IslandUpgradesDao.Sql(sql);

        assertTrue(dao.recordLevel(island, IslandUpgrades.Track.SIZE, 0, 1, 1_000L).join(),
                "pierwszy poziom tworzy wiersz");
        assertFalse(dao.recordLevel(island, IslandUpgrades.Track.SIZE, 0, 1, 1_000L).join(),
                "to samo przejście drugi raz to no-op");
        assertEquals(1, storedLevel(IslandUpgrades.Track.SIZE));
        assertEquals(1_000L, storedSpent(IslandUpgrades.Track.SIZE));

        assertTrue(dao.recordLevel(island, IslandUpgrades.Track.SIZE, 1, 2, 2_000L).join());
        assertFalse(dao.recordLevel(island, IslandUpgrades.Track.SIZE, 0, 1, 1_000L).join(),
                "spóźniony zapis nie może cofnąć poziomu");
        assertEquals(2, storedLevel(IslandUpgrades.Track.SIZE));
        assertEquals(3_000L, storedSpent(IslandUpgrades.Track.SIZE),
                "spóźniony zapis nie dolicza kosztu drugi raz");
    }

    /**
     * Tor MINIONS nie woła Skyllii — jego jedyna „perka" to wiersz w tabeli,
     * który czyta SkylliaMinions: {@code minionSlotsFor} = poziom ×
     * slots-per-level (tu 2).
     */
    @Test
    void minionSlotsTrackFeedsTheSharedTable() {
        seed(10_000L);

        service.purchase(island, IslandUpgrades.Track.MINIONS).join();
        service.purchase(island, IslandUpgrades.Track.MINIONS).join();

        assertEquals(2, storedLevel(IslandUpgrades.Track.MINIONS));
        assertEquals(4, service.minionSlotsFor(island),
                "2 poziomy × 2 sloty na poziom");
        assertEquals(2, applied.size(), "perkApplier nadal dostaje awans (no-op dla MINIONS)");
    }

    @Test
    void statesExposesPerTrackProgress() {
        seed(10_000L);
        service.purchase(island, IslandUpgrades.Track.MEMBERS).join();

        Map<IslandUpgrades.Track, IslandUpgradesService.TrackState> states =
                service.states(island).join();

        assertEquals(3, states.size(), "każdy tor ma swój stan");
        IslandUpgradesService.TrackState members = states.get(IslandUpgrades.Track.MEMBERS);
        assertEquals(1, members.level());
        assertEquals(500L, members.spentMinor());
        assertEquals(1_000L, members.nextCost());
        assertFalse(members.maxed());
        IslandUpgradesService.TrackState minions = states.get(IslandUpgrades.Track.MINIONS);
        assertEquals(0, minions.level(), "niekupiony tor pokazuje poziom 0, nie błąd");
    }

    /**
     * Perka, która rzuca wyjątek, nie może cofnąć zapisu poziomu ani zabić
     * ogłoszenia — wyspa już zapłaciła, efekt jest trwały w bazie.
     */
    @Test
    void failingPerkDoesNotRollbackThePurchase() {
        seed(10_000L);
        service.setPerkApplier((track, up) -> {
            throw new IllegalStateException("Skyllia offline");
        });

        IslandUpgradesService.PurchaseResult result =
                service.purchase(island, IslandUpgrades.Track.SIZE).join();

        assertEquals(IslandUpgradesService.Status.PURCHASED, result.status());
        assertEquals(1, storedLevel(IslandUpgrades.Track.SIZE));
        assertEquals(1, broadcasts.size(), "awans ogłaszany mimo padniętej perki");
    }

    private void seed(long amount) {
        ledger.deposit(AccountKey.island(island), amount, "tx:seed:" + amount, "test").join();
    }

    private int storedLevel(IslandUpgrades.Track track) {
        return sql.queryOne("SELECT level FROM wpme_sb_island_upgrades WHERE island_id = ? AND track = ?",
                rs -> rs.getInt(1), island.toString(), track.dbId()).join().orElse(-1);
    }

    private long storedSpent(IslandUpgrades.Track track) {
        return sql.queryOne("SELECT spent_minor FROM wpme_sb_island_upgrades WHERE island_id = ? AND track = ?",
                rs -> rs.getLong(1), island.toString(), track.dbId()).join().orElse(-1L);
    }

    /** DAO z bramką na odczycie poziomu — pozwala nałożyć dwa zakupy w czasie. */
    private static final class GatedDao implements IslandUpgradesDao {

        private final IslandUpgradesDao delegate;
        private final CompletableFuture<Void> gate = new CompletableFuture<>();

        private GatedDao(IslandUpgradesDao delegate) {
            this.delegate = delegate;
        }

        private void release() {
            gate.complete(null);
        }

        @Override
        public @NotNull CompletableFuture<Optional<State>> find(@NotNull UUID islandId,
                                                               @NotNull IslandUpgrades.Track track) {
            return gate.thenCompose(ignored -> delegate.find(islandId, track));
        }

        @Override
        public @NotNull CompletableFuture<Boolean> recordLevel(@NotNull UUID islandId,
                                                              @NotNull IslandUpgrades.Track track,
                                                              int fromLevel, int toLevel,
                                                              long costMinor) {
            return delegate.recordLevel(islandId, track, fromLevel, toLevel, costMinor);
        }
    }
}
