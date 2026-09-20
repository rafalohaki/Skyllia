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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prestiż na prawdziwym {@link LedgerService} i SQLite w pamięci: monety schodzą
 * z konta WYSPY (nie z portfela gracza), koszt rośnie geometrycznie, a podwójne
 * kliknięcie i retry po utraconym potwierdzeniu kończą się jednym debetem.
 */
class IslandPrestigeServiceTest {

    private static final IslandPrestige.Settings SETTINGS = new IslandPrestige.Settings(
            3, 1_000L, 2.0D, List.of("Rybacka", "Kupiecka", "Złota"));

    private SingleConnectionSqlService sql;
    private LedgerService ledger;
    private IslandTitleService titles;
    private IslandPrestigeService service;
    private final UUID island = UUID.randomUUID();
    private final List<IslandPrestigeService.PrestigeUp> broadcasts = new ArrayList<>();

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        new SkyBlockSchemaMigrator(sql).migrate().join();
        ledger = new LedgerService(new LedgerDao(sql), 0L);
        titles = new IslandTitleService(new IslandTitleDao.Sql(sql));
        service = new IslandPrestigeService(Logger.getLogger("PrestigeServiceTest"), SETTINGS,
                new IslandPrestigeDao.Sql(sql), ledger, titles, broadcasts::add);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void purchasePaysFromIslandAccountAndRaisesLevel() {
        seed(10_000L);

        IslandPrestigeService.PurchaseResult result = service.purchase(island).join();

        assertEquals(IslandPrestigeService.Status.PURCHASED, result.status());
        assertEquals(1, result.level());
        assertEquals(1_000L, result.cost());
        assertEquals(9_000L, ledger.authoritativeIslandBalance(island).join(),
                "koszt prestiżu schodzi z konta wyspy");
        assertEquals(1, storedLevel());
        assertEquals(1_000L, storedSpent());
        assertEquals(1, service.cachedLevel(island).orElse(-1),
                "kafelek w Centrum Wyspy czyta poziom z cache, nie z bazy");
        assertEquals(List.of(new IslandPrestigeService.PrestigeUp(island, 1, 1_000L, "Rybacka")),
                broadcasts);
    }

    @Test
    void eachNextLevelCostsMoreAndEmptyBankStops() {
        seed(10_000L);

        assertEquals(1_000L, service.purchase(island).join().cost());
        assertEquals(2_000L, service.purchase(island).join().cost());
        assertEquals(4_000L, service.purchase(island).join().cost());

        // 10000 − 1000 − 2000 − 4000 = 3000, a czwarty poziom kosztowałby 8000
        assertEquals(3_000L, ledger.authoritativeIslandBalance(island).join());
        assertEquals(3, service.cachedLevel(island).orElse(-1));
        assertEquals(7_000L, storedSpent(), "suma wydana na prestiż to 1000 + 2000 + 4000");
    }

    @Test
    void purchaseWithoutFundsChangesNothing() {
        seed(500L);

        IslandPrestigeService.PurchaseResult result = service.purchase(island).join();

        assertEquals(IslandPrestigeService.Status.NO_FUNDS, result.status());
        assertEquals(0, result.level());
        assertEquals(1_000L, result.cost(), "menu musi umieć powiedzieć, ile brakuje");
        assertEquals(500L, ledger.authoritativeIslandBalance(island).join());
        assertEquals(-1, storedLevel(), "nieudany zakup nie może zapisać poziomu");
        assertTrue(broadcasts.isEmpty(), "nieudany zakup nie ogłasza awansu");
    }

    @Test
    void doublePurchaseBuysOnce() {
        // Prawdziwy SQL leci w tle (pula Hikari), więc dwa kliknięcia mieszczą się
        // w oknie jednego zakupu. W teście SQLite odpowiada od razu, dlatego
        // pierwszy odczyt poziomu trzymamy na bramce do chwili drugiego kliknięcia.
        GatedDao gated = new GatedDao(new IslandPrestigeDao.Sql(sql));
        IslandPrestigeService gatedService = new IslandPrestigeService(
                Logger.getLogger("PrestigeServiceTest"), SETTINGS, gated, ledger, titles,
                broadcasts::add);
        seed(10_000L);

        CompletableFuture<IslandPrestigeService.PurchaseResult> first = gatedService.purchase(island);
        CompletableFuture<IslandPrestigeService.PurchaseResult> second = gatedService.purchase(island);

        assertSame(first, second, "drugie kliknięcie czeka na trwający zakup");
        gated.release();
        assertEquals(IslandPrestigeService.Status.PURCHASED, first.join().status());
        assertEquals(1, second.join().level());
        assertEquals(9_000L, ledger.authoritativeIslandBalance(island).join(),
                "jeden zakup = jeden debet");
        assertEquals(1, storedLevel());
        assertEquals(1, broadcasts.size(), "jeden zakup = jeden broadcast");
    }

    /**
     * Awaria między debetem a zapisem wiersza: księga zna transakcję
     * {@code prestige:<wyspa>:1}, tabela prestiżu jeszcze nie. Powtórka musi
     * domknąć stan i NIE ściągnąć monet drugi raz.
     */
    @Test
    void replayAfterLostConfirmationDoesNotChargeTwice() {
        seed(10_000L);
        ledger.withdrawIsland(island, 1_000L, "prestige:" + island + ":1", "island_prestige").join();
        assertEquals(9_000L, ledger.authoritativeIslandBalance(island).join());

        IslandPrestigeService.PurchaseResult result = service.purchase(island).join();

        assertEquals(IslandPrestigeService.Status.ALREADY_PURCHASED, result.status());
        assertEquals(1, result.level());
        assertEquals(9_000L, ledger.authoritativeIslandBalance(island).join(),
                "powtórka nie może zapłacić drugi raz");
        assertEquals(1, storedLevel(), "powtórka domyka brakujący wiersz poziomu");
        assertEquals(1_000L, storedSpent());
        assertTrue(broadcasts.isEmpty(), "powtórka nie ogłasza drugiego awansu");
    }

    @Test
    void maxLevelBlocksFurtherPurchases() {
        seed(100_000L);
        service.purchase(island).join();
        service.purchase(island).join();
        service.purchase(island).join();
        long balanceAtMax = ledger.authoritativeIslandBalance(island).join();

        IslandPrestigeService.PurchaseResult result = service.purchase(island).join();

        assertEquals(IslandPrestigeService.Status.MAX_LEVEL, result.status());
        assertEquals(3, result.level());
        assertEquals(balanceAtMax, ledger.authoritativeIslandBalance(island).join());
        assertEquals(3, storedLevel());
        assertEquals(3, broadcasts.size());
    }

    /**
     * Zapis poziomu jest strażony poziomem wyjściowym: powtórka tego samego
     * przejścia to no-op, a spóźniony zapis z nieaktualnym poziomem nie cofa
     * wiersza i nie dolicza kosztu drugi raz (inaczej awaria między debetem
     * a zapisem pozwoliłaby komuś „zdobyć” prestiż taniej).
     */
    @Test
    void staleLevelWriteCannotInflateSpentOrLowerTheRow() {
        IslandPrestigeDao dao = new IslandPrestigeDao.Sql(sql);

        assertTrue(dao.recordLevel(island, 0, 1, 1_000L).join(), "pierwszy prestiż tworzy wiersz");
        assertFalse(dao.recordLevel(island, 0, 1, 1_000L).join(), "to samo przejście drugi raz to no-op");
        assertEquals(1, storedLevel());
        assertEquals(1_000L, storedSpent());

        assertTrue(dao.recordLevel(island, 1, 2, 2_000L).join());
        assertFalse(dao.recordLevel(island, 0, 1, 1_000L).join(),
                "spóźniony zapis nie może cofnąć poziomu");
        assertEquals(2, storedLevel());
        assertEquals(3_000L, storedSpent(), "spóźniony zapis nie dolicza kosztu drugi raz");
    }

    /**
     * Nagroda prestiżu jest kosmetyczna, ale trwała: tytuł poziomu ląduje
     * w magazynie (migracja #13), więc przeżywa restart i widać go w Centrum
     * Wyspy oraz w {@code %skyblock_island_title%}.
     */
    @Test
    void purchaseStoresTheLevelTitleInTheIslandTitleStore() {
        seed(10_000L);

        service.purchase(island).join();

        assertEquals("Rybacka", titles.titleOf(island).join().orElse(null));
        assertEquals("prestige:1", titles.cachedSourceId(island).orElse(null),
                "źródło tytułu to poziom prestiżu, po nim idzie idempotencja");
        assertEquals("Rybacka", storedTitle());
        assertEquals("prestige:1", storedSourceId());
    }

    /**
     * Zakup prestiżu dochodzi do skutku, ale tytuł o niższym priorytecie nie
     * zjada lepszego (tu: jednorazowego Władcy Lotosu) — a komunikat awansu nie
     * obiecuje tytułu, którego wyspa nie dostała.
     */
    @Test
    void prestigePurchaseKeepsABetterTitleFromAnotherSource() {
        seed(10_000L);
        titles.grant(island, "<gold>Władca Lotosu</gold>", "lotus:master").join();

        service.purchase(island).join();

        assertEquals(9_000L, ledger.authoritativeIslandBalance(island).join(),
                "zakup ma się rozliczyć normalnie");
        assertEquals("<gold>Władca Lotosu</gold>", storedTitle());
        assertEquals("lotus:master", storedSourceId());
        assertEquals(List.of(new IslandPrestigeService.PrestigeUp(island, 1, 1_000L, null)),
                broadcasts, "broadcast nie może ogłaszać tytułu, który nie wszedł");
    }

    @Test
    void stateExposesNextCostAndTitle() {
        seed(10_000L);
        service.purchase(island).join();

        IslandPrestigeService.State state = service.state(island).join();

        assertEquals(1, state.level());
        assertEquals(2_000L, state.nextCost());
        assertEquals(1_000L, state.spentMinor());
        assertEquals("Rybacka", state.title());
        assertFalse(state.maxed());
    }

    private void seed(long amount) {
        ledger.deposit(AccountKey.island(island), amount, "tx:seed:" + amount, "test").join();
    }

    private int storedLevel() {
        return sql.queryOne("SELECT level FROM wpme_sb_island_prestige WHERE island_id = ?",
                rs -> rs.getInt(1), island.toString()).join().orElse(-1);
    }

    private long storedSpent() {
        return sql.queryOne("SELECT spent_minor FROM wpme_sb_island_prestige WHERE island_id = ?",
                rs -> rs.getLong(1), island.toString()).join().orElse(-1L);
    }

    private String storedTitle() {
        return sql.queryOne("SELECT title FROM wpme_sb_island_titles WHERE island_id = ?",
                rs -> rs.getString(1), island.toString()).join().orElse(null);
    }

    private String storedSourceId() {
        return sql.queryOne("SELECT source_id FROM wpme_sb_island_titles WHERE island_id = ?",
                rs -> rs.getString(1), island.toString()).join().orElse(null);
    }

    /** DAO z bramką na odczycie poziomu — pozwala nałożyć dwa zakupy w czasie. */
    private static final class GatedDao implements IslandPrestigeDao {

        private final IslandPrestigeDao delegate;
        private final CompletableFuture<Void> gate = new CompletableFuture<>();

        private GatedDao(IslandPrestigeDao delegate) {
            this.delegate = delegate;
        }

        private void release() {
            gate.complete(null);
        }

        @Override
        public @NotNull CompletableFuture<Optional<State>> find(@NotNull UUID islandId) {
            return gate.thenCompose(ignored -> delegate.find(islandId));
        }

        @Override
        public @NotNull CompletableFuture<Boolean> recordLevel(@NotNull UUID islandId, int fromLevel,
                                                              int toLevel, long costMinor) {
            return delegate.recordLevel(islandId, fromLevel, toLevel, costMinor);
        }
    }
}
