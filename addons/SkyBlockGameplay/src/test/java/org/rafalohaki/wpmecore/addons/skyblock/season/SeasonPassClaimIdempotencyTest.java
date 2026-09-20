package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.SkyBlockSchemaMigrator;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regresja incydentu „Przepustka … L1 reward grant failed” (lab 2026-08-25):
 * sqlite-jdbc 3.53.x nie ustawia SQLState dla naruszeń ograniczeń
 * (getSQLState() == null), więc stary warunek {@code getSQLState().startsWith("23")}
 * przepuszczał PK-violation z drugiego kliknięcia jako błąd SQL — gracz dostawał
 * nagrodę (INSERT z pierwszego kliknięcia trwał) i jednocześnie komunikat awarii.
 * Claim musi być idempotentny: druga próba zwraca false na każdym sterowniku.
 */
class SeasonPassClaimIdempotencyTest {

    private Connection sqlite;
    private SeasonPassService service;

    @BeforeEach
    void setUp() throws SQLException {
        sqlite = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (var stmt = sqlite.createStatement()) {
            stmt.executeUpdate(SkyBlockSchemaMigrator.SEASON_POINTS_DDL);
            stmt.executeUpdate(SkyBlockSchemaMigrator.SEASON_PASS_CLAIMS_DDL);
            stmt.executeUpdate(SkyBlockSchemaMigrator.SEASON_PASS_DDL);
        }

        SqlService sql = mock(SqlService.class);
        when(sql.withConnection(any())).thenAnswer(invocation -> {
            SqlService.SqlAction<?> action = invocation.getArgument(0);
            try {
                return CompletableFuture.completedFuture(action.execute(sqlite));
            } catch (SQLException failure) {
                // jak HikariSqlService.withConnection: opakowanie w RuntimeException
                CompletableFuture<Object> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("SQL action failed", failure));
                return failed;
            }
        });
        service = new SeasonPassService(sql, () -> 2);
    }

    @AfterEach
    void tearDown() throws SQLException {
        sqlite.close();
    }

    private void seedPoints(UUID player, long points) throws SQLException {
        try (PreparedStatement ps = sqlite.prepareStatement(
                "INSERT INTO wpme_sb_season_points (season_id, player_uuid, points, quests_done, updated_at)"
                        + " VALUES (2, ?, ?, 0, 0)")) {
            ps.setString(1, player.toString());
            ps.setLong(2, points);
            ps.executeUpdate();
        }
    }

    private int countClaims(UUID player) throws SQLException {
        try (PreparedStatement ps = sqlite.prepareStatement(
                "SELECT COUNT(*) FROM wpme_sb_season_pass_claims"
                        + " WHERE season_id = 2 AND player_uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void duplicateFreeClaimReturnsFalseInsteadOfSqlFailure() throws SQLException {
        UUID player = UUID.fromString("405993ee-6d70-4cc4-8477-e975e30d7675");
        seedPoints(player, 50L);

        assertTrue(service.claim(player, 1, SeasonPassService.Track.FREE).join(),
                "pierwszy claim L1 FREE powinien się udać");
        assertFalse(service.claim(player, 1, SeasonPassService.Track.FREE).join(),
                "drugi claim (PK violation) musi zwrócić false, nie RuntimeException");
        assertEquals(1, countClaims(player), "dokładnie jeden wiersz claimu");
    }

    @Test
    void duplicatePremiumClaimReturnsFalseInsteadOfSqlFailure() throws SQLException {
        UUID player = UUID.fromString("7c5bbe03-e336-40de-819a-3b0a1822a472");
        seedPoints(player, 50L);
        try (PreparedStatement ps = sqlite.prepareStatement(
                "INSERT INTO wpme_sb_season_pass (season_id, player_uuid, premium, updated_at)"
                        + " VALUES (2, ?, TRUE, 0)")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }

        assertTrue(service.claim(player, 1, SeasonPassService.Track.PREMIUM).join());
        assertFalse(service.claim(player, 1, SeasonPassService.Track.PREMIUM).join());
        assertEquals(1, countClaims(player));
    }

    @Test
    void levelNotReachedStillRejectedWithoutInsert() throws SQLException {
        UUID player = UUID.randomUUID();
        seedPoints(player, 49L);
        assertFalse(service.claim(player, 1, SeasonPassService.Track.FREE).join());
        assertEquals(0, countClaims(player));
    }

    /** Sterownikowa podstawa regresji: xerial raportuje PK-violation bez SQLState. */
    @Test
    void sqliteDuplicateKeyHasNullSqlStateAndIsClassifiedAsConstraint() throws SQLException {
        UUID player = UUID.randomUUID();
        seedPoints(player, 50L);
        service.claim(player, 1, SeasonPassService.Track.FREE).join();

        SQLException thrown = assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = sqlite.prepareStatement(
                    "INSERT INTO wpme_sb_season_pass_claims (season_id, player_uuid, level, track, claimed_at)"
                            + " VALUES (2, ?, 1, 'FREE', 0)")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
        });
        // Warunek, który łamał stary kod:
        assertTrue(thrown.getSQLState() == null || !thrown.getSQLState().startsWith("23"),
                "xerial nie raportuje SQLState 23* — stary warunek go przepuszczał");
        assertTrue(SqlSupport.isConstraintViolation(thrown),
                "SqlSupport.isConstraintViolation musi klasyfikować ten wyjątek jako naruszenie ograniczenia");
    }

    /** P2-1: po L28 (1400 pkt) każde 500 pkt to poziom bonusowy z własnym, idempotentnym claimem. */
    @Test
    void bonusLevelsEveryFiveHundredPointsAfterTwentyEight() throws SQLException {
        UUID player = UUID.randomUUID();
        seedPoints(player, 1_900L);

        assertEquals(29, SeasonPassService.levelFor(1_900L)); // levelOf idzie przez queryOne, ktorego ten mock nie stubuje
        assertEquals(28, SeasonPassService.levelFor(1_899L));
        assertEquals(30, SeasonPassService.levelFor(2_400L));
        assertEquals(1_900L, SeasonPassService.pointsForLevel(29));

        assertTrue(service.isEligible(player, 29, SeasonPassService.Track.FREE).join());
        assertFalse(service.isEligible(player, 30, SeasonPassService.Track.FREE).join());
        assertTrue(service.claim(player, 29, SeasonPassService.Track.FREE).join(), "pierwszy odbiór L29");
        assertFalse(service.claim(player, 29, SeasonPassService.Track.FREE).join(), "L29 tylko raz");
        assertFalse(service.claim(player, 30, SeasonPassService.Track.FREE).join(), "L30 bez punktów");

        assertEquals(SeasonPassRewards.BONUS_FREE_MONEY, SeasonPassRewards.freeMoney(29));
        assertTrue(SeasonPassRewards.freeItem(29).isPresent());
        assertEquals(2, SeasonPassRewards.premiumItems(29).size());
        assertEquals(30, SeasonPassMenu.nextClaimableBonus(31, java.util.Set.of(29)));
        assertEquals(-1, SeasonPassMenu.nextClaimableBonus(28, java.util.Set.of()));
    }
}
