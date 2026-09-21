package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.rafalohaki.wpmecore.addons.skyblock.shared.SingleConnectionSqlService;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.api.service.SqlDialect;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Historia migracji została zgnieciona do jednego baselineu przed publicznym
 * startem SkyBlocka. Te testy pilnują, że baseline tworzy docelowy schemat w
 * całości i że mechanizm wersjonowania — który po starcie będzie jedyną drogą
 * zmiany schematu — nadal odmawia pracy na bazie niezgodnej z kodem.
 */
class SkyBlockSchemaMigratorTest {

    private SingleConnectionSqlService sql;
    private SkyBlockSchemaMigrator migrator;

    @BeforeEach
    void setUp() throws SQLException {
        sql = new SingleConnectionSqlService();
        migrator = new SkyBlockSchemaMigrator(sql);
    }

    @AfterEach
    void tearDown() {
        sql.shutdown();
    }

    @Test
    void baselineIsASingleChecksummedMigrationAndRepeatedStartupIsIdempotent() {
        migrator.migrate().join();
        migrator.migrate().join();

        List<MigrationRow> rows = sql.query("""
                SELECT schema_version, description, checksum
                FROM wpme_sb_schema_migrations
                ORDER BY schema_version
                """, result -> new MigrationRow(
                result.getInt(1), result.getString(2), result.getString(3)))
                .join();

        assertEquals(14, rows.size(), "historia ma baseline + profile guard + profile isolation + seasonal island claim + season foundation + quest progress + season editions + indeks outboxu + codzienna nagroda + poziom wyspy + dzienne punkty sezonowe + prestiż wyspy + tytuły wyspy + ulepszenia wyspy");
        assertEquals(1, rows.get(0).version());
        assertEquals("baseline-skyblock-schema", rows.get(0).description());
        assertTrue(rows.get(0).checksum().matches("[0-9a-f]{64}"));
        assertEquals(2, rows.get(1).version());
        assertEquals("island-profile-membership-guard", rows.get(1).description());
        assertTrue(rows.get(1).checksum().matches("[0-9a-f]{64}"));
        assertEquals(3, rows.get(2).version());
        assertTrue(rows.get(2).checksum().matches("[0-9a-f]{64}"));
        assertEquals(4, rows.get(3).version());
        assertEquals("seasonal-claim-per-island", rows.get(3).description());
        assertTrue(rows.get(3).checksum().matches("[0-9a-f]{64}"));
        assertEquals(5, rows.get(4).version());
        assertEquals("season-points-foundation", rows.get(4).description());
        assertTrue(rows.get(4).checksum().matches("[0-9a-f]{64}"));
        assertEquals(6, rows.get(5).version());
        assertEquals("season-quest-progress", rows.get(5).description());
        assertTrue(rows.get(5).checksum().matches("[0-9a-f]{64}"));
        assertEquals(7, rows.get(6).version());
        assertEquals("season-editions", rows.get(6).description());
        assertTrue(rows.get(6).checksum().matches("[0-9a-f]{64}"));
        assertEquals(8, rows.get(7).version());
        assertEquals("inventory-outbox-player-status-index", rows.get(7).description());
        assertTrue(rows.get(7).checksum().matches("[0-9a-f]{64}"));
        assertEquals(9, rows.get(8).version());
        assertEquals("daily-reward-streak", rows.get(8).description());
        assertTrue(rows.get(8).checksum().matches("[0-9a-f]{64}"));
        assertEquals(10, rows.get(9).version());
        assertEquals("island-level", rows.get(9).description());
        assertTrue(rows.get(9).checksum().matches("[0-9a-f]{64}"));
        assertEquals(11, rows.get(10).version());
        assertEquals("season-daily-points-caps", rows.get(10).description());
        assertTrue(rows.get(10).checksum().matches("[0-9a-f]{64}"));
        assertEquals(12, rows.get(11).version());
        assertEquals("island-prestige", rows.get(11).description());
        assertTrue(rows.get(11).checksum().matches("[0-9a-f]{64}"));
        assertEquals(13, rows.get(12).version());
        assertEquals("island-titles", rows.get(12).description());
        assertTrue(rows.get(12).checksum().matches("[0-9a-f]{64}"));
        assertEquals(14, rows.get(13).version());
        assertEquals("island-upgrades", rows.get(13).description());
        assertTrue(rows.get(13).checksum().matches("[0-9a-f]{64}"));
        assertEquals(SkyBlockSchemaMigrator.CURRENT_VERSION, rows.get(13).version());
    }

    /**
     * LEDGER-1. {@code PENDING_FOR_PLAYER_SQL} filtruje po {@code player_id}
     * i {@code status}, a tabela nie miała żadnego indeksu — {@code EXPLAIN QUERY
     * PLAN} na produkcyjnej bazie pokazywał {@code SCAN}. Zapytanie leci przy
     * każdym wejściu gracza i 2× na operację ekonomiczną, na puli SQLite = 1.
     * Na starym kodzie ten test pada: planem jest SCAN.
     */
    @Test
    void outboxLookupUsesAnIndexInsteadOfScanningTheWholeTable() {
        migrator.migrate().join();

        List<String> plan = sql.query("""
                EXPLAIN QUERY PLAN
                SELECT operation_id FROM wpme_sb_inventory_outbox
                WHERE player_id = 'x' AND status NOT IN ('COMPLETE')
                """, row -> {
            StringBuilder line = new StringBuilder();
            int columns = row.getMetaData().getColumnCount();
            for (int i = 1; i <= columns; i++) {
                line.append(row.getString(i)).append(' ');
            }
            return line.toString();
        }).join();

        String detail = String.join(" | ", plan);
        assertTrue(detail.contains("idx_wpme_sb_inventory_outbox_player_status"),
                "plan zapytania musi użyć indeksu (player_id, status), a był: " + detail);
        assertFalse(detail.contains("SCAN wpme_sb_inventory_outbox"),
                "pełny skan tabeli outboxu zamraża gracza na czas zapytania: " + detail);
    }

    @Test
    void baselineCreatesEveryLiveTable() {
        migrator.migrate().join();

        // 36 tabel danych (24 + 4 M2.5 sezon + postęp questów + edycje sezonów + codzienna nagroda + poziom wyspy + dzienne punkty + prestiż wyspy + tytuły wyspy + ulepszenia wyspy) plus tabela wersji schematu
        assertEquals(36L, scalar("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name LIKE 'wpme_sb_%'
                """));
        for (String table : List.of("wpme_sb_accounts", "wpme_sb_transactions",
                "wpme_sb_quest_progress", "wpme_sb_daily_claims",
                "wpme_sb_inventory_outbox", "wpme_sb_inventory_outbox_lines",
                "wpme_sb_oneblock", "wpme_sb_oneblock_milestones", "wpme_sb_chunkers",
                "wpme_sb_sell_chests", "wpme_sb_weekly_milestones",
                "wpme_sb_island_profiles", "wpme_sb_island_membership", "wpme_sb_active_membership",
                "wpme_sb_profile_accounts", "wpme_sb_profile_transitions", "wpme_sb_profile_snapshots",
                "wpme_sb_playtime", "wpme_sb_content_assignments", "wpme_sb_reward_claims",
                "wpme_sb_season_quest_progress", "wpme_sb_season_editions",
                "wpme_sb_daily_reward", "wpme_sb_island_level",
                "wpme_sb_season_daily_points", "wpme_sb_island_prestige",
                "wpme_sb_island_titles", "wpme_sb_island_upgrades")) {
            assertFalse(tableColumns(table).isEmpty(), "brak tabeli " + table);
        }
    }

    /** Migracja #6: postęp questów sezonowych — klucz (sezon, gracz, quest) egzekwuje jeden wiersz na licznik. */
    @Test
    void seasonQuestProgressTableCarriesPerSeasonPerPlayerPerQuestKey() {
        migrator.migrate().join();

        List<String> columns = tableColumns("wpme_sb_season_quest_progress");
        assertTrue(columns.containsAll(List.of(
                "season_id", "player_uuid", "quest_id", "progress", "updated_at")),
                columns.toString());

        sql.update("""
                INSERT INTO wpme_sb_season_quest_progress
                    (season_id, player_uuid, quest_id, progress, updated_at)
                VALUES (3, '11111111-1111-1111-1111-111111111111', 'sq_craft_1', 5, 1)
                """).join();
        sql.update("""
                UPDATE wpme_sb_season_quest_progress SET progress = 7
                WHERE season_id = 3 AND player_uuid = '11111111-1111-1111-1111-111111111111'
                  AND quest_id = 'sq_craft_1'
                """).join();

        assertEquals(1L, scalar("""
                SELECT COUNT(*) FROM wpme_sb_season_quest_progress
                WHERE season_id = 3 AND player_uuid = '11111111-1111-1111-1111-111111111111'
                """));
        assertEquals(7L, scalar("""
                SELECT progress FROM wpme_sb_season_quest_progress
                WHERE season_id = 3 AND player_uuid = '11111111-1111-1111-1111-111111111111'
                  AND quest_id = 'sq_craft_1'
                """));
    }

    /** Migracja #7: edycje sezonów — klucz (sezon, activated_at), closed_at otwarty = NULL. */
    @Test
    void seasonEditionsTableCarriesAppendOnlyActivationRows() {
        migrator.migrate().join();

        List<String> columns = tableColumns("wpme_sb_season_editions");
        assertEquals(List.of("season_id", "activated_at", "slug", "display_name",
                "edition_type", "start_date", "end_date", "closed_at"), columns,
                "kolumny tabeli edycji muszą zostać dosłownie zgodne z migracją #7");

        sql.update("""
                INSERT INTO wpme_sb_season_editions
                    (season_id, activated_at, slug, display_name, edition_type,
                     start_date, end_date, closed_at)
                VALUES (3, 1000, 'lato-2026', 'Sezon Letni 2026', 'REGULAR',
                        '2026-06-01', '2026-08-31', NULL)
                """).join();
        // Zmiana edycji w trakcie sezonu = nowy wiersz (append-only), stary zostaje.
        sql.update("""
                INSERT INTO wpme_sb_season_editions
                    (season_id, activated_at, slug, display_name, edition_type,
                     start_date, end_date, closed_at)
                VALUES (3, 2000, 'wakacyjny-2026', 'Sezon Wakacyjny 2026', 'EVENT',
                        '2026-07-01', '2026-07-14', NULL)
                """).join();

        assertEquals(2L, scalar("""
                SELECT COUNT(*) FROM wpme_sb_season_editions WHERE season_id = 3
                """));

        CompletionException duplicate = assertThrows(CompletionException.class, () ->
                sql.update("""
                        INSERT INTO wpme_sb_season_editions
                            (season_id, activated_at, slug, display_name, edition_type,
                             start_date, end_date, closed_at)
                        VALUES (3, 1000, 'inna', 'Inna', 'EVENT', '2026-01-01', '2026-01-02', NULL)
                        """).join());
        assertTrue(duplicate.getCause().getMessage().contains("PRIMARY KEY"),
                duplicate.getCause().getMessage());
    }

    /** Kolumny, które przed zgnieceniem dokładała osobna migracja. */
    @Test
    void baselineAlreadyCarriesThePreviouslyMigratedColumns() {
        migrator.migrate().join();

        assertTrue(tableColumns("wpme_sb_oneblock")
                .containsAll(List.of("phase_id", "dry_streak")));
        assertTrue(tableColumns("wpme_sb_quest_progress")
                .containsAll(List.of("last_batch_id", "last_batch_reward")));
        assertEquals(2L, scalar("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'index' AND name LIKE 'idx_wpme_sb_minions%'
                """));
    }

    /** Tabele cyklu życia wysp odeszły razem z przejściem na publiczne API Skyllii — pozostały tylko M1-A profile/membership. */
    @Test
    void baselineDoesNotResurrectTheDroppedLifecycleTables() {
        migrator.migrate().join();

        // Dawne tabele lifecycle (np. wpme_sb_island_invites) nie powinny wrócić;
        // obecne M1-A tabele to dokładnie 2 island_* + active helper. Migracja #10
        // dokłada wpme_sb_island_level (poziom wyspy), #12 wpme_sb_island_prestige
        // (prestiż wyspy), #13 wpme_sb_island_titles (tytuł wyspy), a #14
        // wpme_sb_island_upgrades (ulepszenia wyspy) — to nie lifecycle,
        // stąd wszystkie na liście.
        assertEquals(2L, scalar("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name = 'wpme_sb_island_profiles' OR name = 'wpme_sb_island_membership'
                """));
        assertEquals(0L, scalar("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name LIKE 'wpme_sb_island_%' AND name NOT IN ('wpme_sb_island_profiles','wpme_sb_island_membership','wpme_sb_island_level','wpme_sb_island_prestige','wpme_sb_island_titles','wpme_sb_island_upgrades')
                """));
    }

    @Test
    void inventoryLinesCarryACustomItemIdentity() {
        migrator.migrate().join();

        List<String> columns = tableColumns("wpme_sb_inventory_outbox_lines");
        assertTrue(columns.contains("custom_item_id"), columns.toString());
        assertTrue(columns.contains("baseline_count"), columns.toString());
        assertFalse(columns.contains("baseline_plain"),
                "stara nazwa nie może przetrwać zgniecenia: " + columns);
    }

    /**
     * Klucz główny musi obejmować identyfikator customowy: wszystkie surowce
     * SkyBlocka mają materiał bazowy PAPER, więc bez tego receptura z czterema
     * kryształami dałaby cztery kolidujące wiersze.
     */
    @Test
    void oneOperationCanHoldSeveralCustomItemsOfTheSameMaterial() {
        migrator.migrate().join();
        sql.update("""
                INSERT INTO wpme_sb_inventory_outbox
                    (operation_id, player_id, operation_type, transaction_id, status,
                     created_at, updated_at)
                VALUES ('op:pk', '11111111-1111-1111-1111-111111111111', 'REMOVE',
                        'op:pk', 'PENDING', 1, 1)
                """).join();

        for (String customId : List.of("skyblock:crystal/citrine", "skyblock:crystal/opal",
                "skyblock:crystal/topaz", "skyblock:crystal/onyx")) {
            sql.update("""
                    INSERT INTO wpme_sb_inventory_outbox_lines
                        (operation_id, material_key, custom_item_id,
                         remove_amount, baseline_count)
                    VALUES ('op:pk', 'minecraft:paper', ?, 16, 64)
                    """, customId).join();
        }

        assertEquals(4L, scalar("""
                SELECT COUNT(*) FROM wpme_sb_inventory_outbox_lines
                WHERE operation_id = 'op:pk'
                """));
    }

    @Test
    void aPlainAndACustomLineOfTheSameMaterialStillCollideOnlyWhenTrulyEqual() {
        migrator.migrate().join();
        sql.update("""
                INSERT INTO wpme_sb_inventory_outbox
                    (operation_id, player_id, operation_type, transaction_id, status,
                     created_at, updated_at)
                VALUES ('op:mix', '11111111-1111-1111-1111-111111111111', 'REMOVE',
                        'op:mix', 'PENDING', 1, 1)
                """).join();
        sql.update("""
                INSERT INTO wpme_sb_inventory_outbox_lines
                    (operation_id, material_key, custom_item_id, remove_amount, baseline_count)
                VALUES ('op:mix', 'minecraft:paper', '', 4, 8)
                """).join();
        sql.update("""
                INSERT INTO wpme_sb_inventory_outbox_lines
                    (operation_id, material_key, custom_item_id, remove_amount, baseline_count)
                VALUES ('op:mix', 'minecraft:paper', 'skyblock:crystal/amber', 2, 5)
                """).join();

        assertEquals(2L, scalar("""
                SELECT COUNT(*) FROM wpme_sb_inventory_outbox_lines
                WHERE operation_id = 'op:mix'
                """));

        CompletionException duplicate = assertThrows(CompletionException.class, () ->
                sql.update("""
                        INSERT INTO wpme_sb_inventory_outbox_lines
                            (operation_id, material_key, custom_item_id,
                             remove_amount, baseline_count)
                        VALUES ('op:mix', 'minecraft:paper', '', 1, 1)
                        """).join());
        assertTrue(duplicate.getCause().getMessage().contains("PRIMARY KEY"),
                duplicate.getCause().getMessage());
    }

    @Test
    void rejectsDatabaseCreatedByANewerPlugin() {
        migrator.migrate().join();
        sql.update("""
                INSERT INTO wpme_sb_schema_migrations
                    (schema_version, description, checksum, installed_at)
                VALUES (?, 'future', ?, 1)
                """, SkyBlockSchemaMigrator.CURRENT_VERSION + 1, "f".repeat(64)).join();

        CompletionException failure = assertThrows(
                CompletionException.class, () -> migrator.migrate().join());

        SQLException cause = assertInstanceOf(SQLException.class, failure.getCause());
        assertTrue(cause.getMessage().contains("newer than supported"), cause.getMessage());
    }

    @Test
    void rejectsATamperedBaselineChecksum() {
        migrator.migrate().join();
        sql.update("UPDATE wpme_sb_schema_migrations SET checksum = ? WHERE schema_version = 1",
                "0".repeat(64)).join();

        CompletionException failure = assertThrows(
                CompletionException.class, () -> migrator.migrate().join());

        SQLException cause = assertInstanceOf(SQLException.class, failure.getCause());
        assertTrue(cause.getMessage().contains("checksum or description mismatch"),
                cause.getMessage());
    }

    /** Bez tego rozjazd bazy z kodem wyszedłby dopiero pierwszym błędem zapytania. */
    @Test
    void rejectsPhysicalSchemaDriftAfterMigrationWasRecorded() {
        migrator.migrate().join();
        sql.update("DROP TABLE wpme_sb_minions").join();
        sql.update("""
                CREATE TABLE wpme_sb_minions (minion_id VARCHAR(36) NOT NULL PRIMARY KEY)
                """).join();

        CompletionException failure = assertThrows(
                CompletionException.class, () -> migrator.migrate().join());

        SQLException cause = assertInstanceOf(SQLException.class, failure.getCause());
        assertTrue(cause.getMessage().toLowerCase(Locale.ROOT).contains("wpme_sb_minions"),
                cause.getMessage());
    }

    private long scalar(String query) {
        return sql.query(query, row -> row.getLong(1)).join().getFirst();
    }

    private List<String> tableColumns(String table) {
        return sql.withConnection(connection -> {
            List<String> columns = new ArrayList<>();
            try (ResultSet result = connection.getMetaData()
                    .getColumns(null, null, table, null)) {
                while (result.next()) {
                    columns.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            return columns;
        }).join();
    }

    private record MigrationRow(int version, String description, String checksum) { }

    private static final class SingleConnectionSqlService implements SqlService {
        private final Connection connection;

        private SingleConnectionSqlService() throws SQLException {
            connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        }

        @Override public @NotNull SqlDialect dialect() { return SqlDialect.SQLITE; }
        @Override public boolean isEnabled() { return true; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isHealthy() { return true; }
        @Override public @NotNull Map<String, Object> poolStats() { return Map.of(); }

        @Override
        public void shutdown() {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Test cleanup.
            }
        }

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
                    try (ResultSet result = statement.executeQuery()) {
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
        public <T> @NotNull CompletableFuture<T> withConnection(
                @NotNull SqlAction<T> action) {
            return completed(() -> action.execute(connection));
        }

        @Override public @NotNull Object dataSource() { return connection; }

        private static void bind(PreparedStatement statement, Object... params)
                throws SQLException {
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
