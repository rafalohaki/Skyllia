package org.rafalohaki.wpmecore.addons.skyblock.season;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.SkyBlockGameplay;
import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bug walutowy #1: przepustka wypłacała PRZED bramą uprawnień. Handler slotu
 * bezwarunkowo wołał {@code claim} dla KAŻDEGO poziomu, a {@code claim} najpierw
 * deponował monety i wydawał przedmioty, a bramę „poziom osiągnięty / premium
 * aktywne" sprawdzał dopiero na końcu przez {@code pass.claim}. Skutek: gracz z
 * 0 pkt klikał poziom 28 i inkasował monety + przedmioty; tor PREMIUM wydawał
 * przedmioty bez premium.
 *
 * <p>Druga oś: dostawa szła przez {@code outbox.deliverTaggedProduct} (poza
 * kontraktem outboxa, idempotencja licząca sztuki w ekwipunku) zamiast trwałego
 * {@code beginGrant} z deterministycznym operationId.
 */
class SeasonPassMenuClaimGateTest {

    private Connection sqlite;
    private SingleConnectionSqlService sql;
    private SeasonPassService pass;
    private LedgerService ledger;
    private InventoryOutbox outbox;
    private CustomItemService customItems;
    private SkyBlockGameplay plugin;
    private Player player;
    private SeasonPassMenu menu;

    private final UUID uuid = UUID.fromString("2f1c0a44-0000-4000-8000-000000000001");

    @BeforeEach
    void setUp() throws SQLException {
        sqlite = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var stmt = sqlite.createStatement()) {
            stmt.executeUpdate(SkyBlockSchemaMigrator.SEASON_POINTS_DDL);
            stmt.executeUpdate(SkyBlockSchemaMigrator.SEASON_PASS_CLAIMS_DDL);
            stmt.executeUpdate(SkyBlockSchemaMigrator.SEASON_PASS_DDL);
        }
        sql = new SingleConnectionSqlService(sqlite);
        pass = new SeasonPassService(sql, () -> 1);

        ledger = mock(LedgerService.class);
        when(ledger.depositPlayer(any(), anyLong(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(new LedgerDao.Mutation(true, false, 0L)));

        outbox = mock(InventoryOutbox.class);
        // beginGrant: zwróć SUCCESS przez callback (arg 5), żeby łańcuch domknął claim.
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<InventoryOutbox.Outcome> callback = inv.getArgument(5);
            callback.accept(InventoryOutbox.Outcome.SUCCESS);
            return null;
        }).when(outbox).beginGrant(any(), anyLong(), anyString(), anyString(), any(), any());

        customItems = mock(CustomItemService.class);
        // Custom przedmioty premium -> zamockowany stack, żeby test nie zależał od
        // Bukkit ItemFactory (dostawa i tak idzie do zamockowanego outboxa).
        when(customItems.create(anyString())).thenReturn(Optional.of(mock(ItemStack.class)));

        plugin = mock(SkyBlockGameplay.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        // open() na końcu udanego claimu planuje przez scheduler encji; zwracamy
        // null (brak buildAndOpen), bo test pina wypłatę, nie odbudowę menu.
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);

        MenuService menus = mock(MenuService.class);
        menu = new SeasonPassMenu(plugin, menus, MiniMessage.miniMessage(), pass, ledger, outbox, customItems);
    }

    @AfterEach
    void tearDown() throws SQLException {
        sqlite.close();
    }

    private void seedPoints(long points) throws SQLException {
        try (PreparedStatement ps = sqlite.prepareStatement(
                "INSERT INTO wpme_sb_season_points (season_id, player_uuid, points, quests_done, updated_at)"
                        + " VALUES (1, ?, ?, 0, 0)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, points);
            ps.executeUpdate();
        }
    }

    private void seedPremium() throws SQLException {
        try (PreparedStatement ps = sqlite.prepareStatement(
                "INSERT INTO wpme_sb_season_pass (season_id, player_uuid, premium, updated_at)"
                        + " VALUES (1, ?, TRUE, 0)")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("Klik w poziom powyżej osiągniętego (FREE) nie wypłaca ani monet, ani przedmiotów")
    void clickingUnreachedFreeLevelPaysNothing() throws SQLException {
        seedPoints(0L); // poziom 0 — poziom 28 niedostępny

        menu.claim(player, SeasonPassService.Track.FREE, 28);

        verify(ledger, never()).depositPlayer(any(), anyLong(), anyString(), anyString());
        verify(outbox, never()).beginGrant(any(), anyLong(), anyString(), anyString(), any(), any());
        verify(outbox, never()).deliverTaggedProduct(any(), anyString(), any());
    }

    @Test
    @DisplayName("Klik w PREMIUM bez aktywnego premium nie wydaje przedmiotów premium")
    void clickingPremiumWithoutPremiumDeliversNothing() throws SQLException {
        seedPoints(1400L); // poziom 28 osiągnięty, ale premium NIE aktywne

        menu.claim(player, SeasonPassService.Track.PREMIUM, 5);

        verify(outbox, never()).beginGrant(any(), anyLong(), anyString(), anyString(), any(), any());
        verify(outbox, never()).deliverTaggedProduct(any(), anyString(), any());
        verify(ledger, never()).depositPlayer(any(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("Uprawniona dostawa idzie przez trwały beginGrant (nie deliverTaggedProduct), idempotentnie po operationId")
    void eligibleDeliveryGoesThroughDurableOutbox() throws SQLException {
        seedPoints(50L); // poziom 1
        seedPremium();

        // Pierwszy odbiór.
        menu.claim(player, SeasonPassService.Track.PREMIUM, 1);
        // Powtórny klik tego samego poziomu.
        menu.claim(player, SeasonPassService.Track.PREMIUM, 1);

        // SEZON-1: klucz per gracz (UUID odbierającego) — patrz SeasonPassTwoPlayersSameLevelTest.
        String opId = "season-pass:1:PREMIUM:L1:" + uuid + ":item0";
        // Dostawa MUSI iść przez trwały outbox z deterministycznym operationId...
        verify(outbox, times(2)).beginGrant(eq(player), eq(0L), eq(opId),
                anyString(), any(), any());
        // ...a nie przez deliverTaggedProduct (idempotencja po sztukach w ekwipunku).
        verify(outbox, never()).deliverTaggedProduct(any(), anyString(), any());
    }

    /** Minimalny SqlService nad jednym połączeniem SQLite (jak w innych testach sezonu). */
    private static final class SingleConnectionSqlService implements SqlService {
        private final Connection connection;

        private SingleConnectionSqlService(Connection connection) {
            this.connection = connection;
        }

        @Override public boolean isEnabled() { return true; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isHealthy() { return true; }
        @Override public @NotNull Map<String, Object> poolStats() { return Map.of(); }
        @Override public @NotNull Object dataSource() { return connection; }

        @Override
        public @NotNull CompletableFuture<Integer> update(@NotNull String query,
                                                          @NotNull Object... params) {
            return completed(() -> {
                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    bind(statement, params);
                    return statement.executeUpdate();
                }
            });
        }

        @Override
        public <T> @NotNull CompletableFuture<List<T>> query(
                @NotNull String query, @NotNull RowMapper<T> mapper,
                @NotNull Object... params) {
            return completed(() -> {
                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    bind(statement, params);
                    try (var result = statement.executeQuery()) {
                        List<T> rows = new ArrayList<>();
                        while (result.next()) {
                            rows.add(mapper.map(result));
                        }
                        return rows;
                    }
                }
            });
        }

        @Override
        public <T> @NotNull CompletableFuture<T> withConnection(@NotNull SqlAction<T> action) {
            try {
                return CompletableFuture.completedFuture(action.execute(connection));
            } catch (SQLException failure) {
                if (SqlSupport.isConstraintViolation(failure)) {
                    // dopasuj się do produkcyjnego opakowania błędów, nieużywane tu
                }
                return CompletableFuture.failedFuture(new RuntimeException("SQL action failed", failure));
            }
        }

        private static void bind(PreparedStatement statement, Object... params) throws SQLException {
            for (int index = 0; index < params.length; index++) {
                statement.setObject(index + 1, params[index]);
            }
        }

        private static <T> CompletableFuture<T> completed(SqlSupplier<T> supplier) {
            try {
                return CompletableFuture.completedFuture(supplier.get());
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        @FunctionalInterface
        private interface SqlSupplier<T> {
            T get() throws Exception;
        }
    }
}
