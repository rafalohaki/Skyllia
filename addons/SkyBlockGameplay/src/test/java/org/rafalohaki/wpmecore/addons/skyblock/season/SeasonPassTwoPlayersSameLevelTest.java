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
import org.mockito.ArgumentCaptor;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEZON-1 (CRITICAL): klucz idempotencji nagród przepustki nie zawierał UUID
 * gracza — {@code season-pass:<sezon>:<tor>:L<poziom>} był GLOBALNY, więc
 * pierwszy gracz odbierający dany poziom „zjadał" nagrody wszystkich kolejnych:
 * {@code BalanceDao.mutate} zwracał cichy no-op na duplikacie transaction_id,
 * a {@code InventoryDao} oddawał cudzy wiersz outboxa. Per-graczowy claim mimo
 * to się commitował z komunikatem sukcesu — drugi gracz tracił nagrodę na zawsze.
 *
 * <p>Regresja: DWAJ gracze odbierają ten sam poziom+tor+sezon — obaj muszą
 * dostać WŁASNE nagrody (osobne transakcje ledgera, własne klucze outboxa).
 * Retry tego samego gracza pozostaje idempotentny (klucz deterministyczny
 * per gracz+poziom, por. {@code SeasonPassClaimIdempotencyTest}).
 */
class SeasonPassTwoPlayersSameLevelTest {

    private Connection sqlite;
    private SingleConnectionSqlService sql;
    private SeasonPassService pass;
    private LedgerService ledger;
    private InventoryOutbox outbox;
    private SeasonPassMenu menu;

    private Player playerA;
    private Player playerB;
    private final UUID uuidA = UUID.fromString("2f1c0a44-0000-4000-8000-00000000000a");
    private final UUID uuidB = UUID.fromString("2f1c0a44-0000-4000-8000-00000000000b");

    @BeforeEach
    void setUp() throws Exception {
        sqlite = DriverManager.getConnection("jdbc:sqlite::memory:");
        sql = new SingleConnectionSqlService(sqlite);
        // Pełny baseline (ledger + sezony) jak LedgerDaoTest — prawdziwe DAO,
        // żeby no-op BalanceDao na duplikacie transaction_id był realny.
        new SkyBlockSchemaMigrator(sql).migrate().join();
        ledger = new LedgerService(new LedgerDao(sql), 0L);
        ledger.init();
        pass = new SeasonPassService(sql, () -> 1);

        outbox = mock(InventoryOutbox.class);
        // beginGrant: SUCCESS przez callback (arg 5) — domek łańcucha claimu.
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<InventoryOutbox.Outcome> callback = inv.getArgument(5);
            callback.accept(InventoryOutbox.Outcome.SUCCESS);
            return null;
        }).when(outbox).beginGrant(any(), anyLong(), anyString(), anyString(), any(), any());

        CustomItemService customItems = mock(CustomItemService.class);
        // Nagrody premium są custom -> zamockowany stack (jak w ClaimGateTest),
        // żeby test nie zależał od Bukkit ItemFactory.
        when(customItems.create(anyString())).thenReturn(Optional.of(mock(ItemStack.class)));

        SkyBlockGameplay plugin = mock(SkyBlockGameplay.class);
        playerA = mockPlayer(uuidA);
        playerB = mockPlayer(uuidB);
        menu = new SeasonPassMenu(plugin, mock(MenuService.class), MiniMessage.miniMessage(),
                pass, ledger, outbox, customItems);
    }

    @AfterEach
    void tearDown() throws SQLException {
        sqlite.close();
    }

    private Player mockPlayer(UUID uuid) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        // open() na końcu udanego claimu planuje przez scheduler encji; null
        // (brak buildAndOpen) — test pina wypłatę, nie odbudowę menu.
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(player.getScheduler()).thenReturn(scheduler);
        return player;
    }

    private void seedLevelOne(UUID uuid) throws SQLException {
        try (PreparedStatement ps = sqlite.prepareStatement(
                "INSERT INTO wpme_sb_season_points (season_id, player_uuid, points, quests_done, updated_at)"
                        + " VALUES (1, ?, 50, 0, 0)")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    private void seedPremium(UUID uuid) throws SQLException {
        try (PreparedStatement ps = sqlite.prepareStatement(
                "INSERT INTO wpme_sb_season_pass (season_id, player_uuid, premium, updated_at)"
                        + " VALUES (1, ?, TRUE, 0)")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    private int countClaims(UUID uuid) throws SQLException {
        try (PreparedStatement ps = sqlite.prepareStatement(
                "SELECT COUNT(*) FROM wpme_sb_season_pass_claims"
                        + " WHERE season_id = 1 AND player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int countRewardTransactions(UUID uuid) throws SQLException {
        try (PreparedStatement ps = sqlite.prepareStatement(
                "SELECT COUNT(*) FROM wpme_sb_transactions WHERE account_key = ?")) {
            ps.setString(1, "player:" + uuid);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    @DisplayName("SEZON-1: dwaj gracze na tym samym poziomie FREE obaj dostają swoje monety (osobne transakcje ledgera)")
    void secondPlayerClaimingSameFreeLevelStillGetsPaid() throws SQLException {
        seedLevelOne(uuidA);
        seedLevelOne(uuidB);
        // Konta istnieją z góry — no-op na duplikacie transaction_id ma być
        // CICHY (Mutation applied=false), a nie wyjątkiem o braku konta.
        ledger.ensurePlayer(uuidA).join();
        ledger.ensurePlayer(uuidB).join();

        menu.claim(playerA, SeasonPassService.Track.FREE, 1);
        menu.claim(playerB, SeasonPassService.Track.FREE, 1);

        long reward = SeasonPassRewards.freeMoney(1);
        // Oba depozyty applied: drugi gracz NIE dostaje no-opu na cudzym kluczu.
        assertEquals(reward, ledger.playerBalance(uuidA), "pierwszy gracz inkasuje nagrodę L1");
        assertEquals(reward, ledger.playerBalance(uuidB),
                "drugi gracz też musi inkasować — klucz idempotencji jest per gracz");
        // Osobne wiersze audytu: po jednym na konto gracza.
        assertEquals(1, countRewardTransactions(uuidA), "własna transakcja pierwszego gracza");
        assertEquals(1, countRewardTransactions(uuidB), "własna transakcja drugiego gracza");
        // Claim per-graczowy commituje się niezależnie — dla obu.
        assertEquals(1, countClaims(uuidA), "claim pierwszego gracza zapisany");
        assertEquals(1, countClaims(uuidB), "claim drugiego gracza zapisany");
    }

    @Test
    @DisplayName("SEZON-1: dwaj gracze na tym samym poziomie PREMIUM dostają własne klucze outboxa")
    void secondPlayerClaimingSamePremiumLevelGetsOwnOutboxOperation() throws SQLException {
        seedLevelOne(uuidA);
        seedLevelOne(uuidB);
        seedPremium(uuidA);
        seedPremium(uuidB);

        menu.claim(playerA, SeasonPassService.Track.PREMIUM, 1);
        menu.claim(playerB, SeasonPassService.Track.PREMIUM, 1);

        ArgumentCaptor<String> opsA = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> opsB = ArgumentCaptor.forClass(String.class);
        verify(outbox, times(1)).beginGrant(eq(playerA), eq(0L), opsA.capture(),
                anyString(), any(), any());
        verify(outbox, times(1)).beginGrant(eq(playerB), eq(0L), opsB.capture(),
                anyString(), any(), any());
        // Własne wiersze outboxa: rozłączne operationId per gracz. Globalny
        // klucz (bez UUID) oddawałby drugiemu graczowi wiersz pierwszego.
        assertTrue(opsA.getAllValues().stream().noneMatch(opsB.getAllValues()::contains),
                "operationId outboxa musi być per gracz (rozłączne zbiory)");
        assertEquals(1, countClaims(uuidA), "claim premium pierwszego gracza zapisany");
        assertEquals(1, countClaims(uuidB), "claim premium drugiego gracza zapisany");
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
