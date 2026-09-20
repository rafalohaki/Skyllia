package org.rafalohaki.wpmecore.addons.skyblock.economy;


import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlDialect;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Zamknięty w sumie kontrolnej schemat SkyBlockGameplay.
 *
 * <p>Historia migracji została <b>zgnieciona do jednego baselineu</b> przed
 * publicznym startem serwera. Dziewięć wcześniejszych wpisów zapisywało wyłącznie
 * przebieg naszej własnej budowy — tworzenie i kasowanie tabel, które nigdy nie
 * zobaczyły cudzych danych — i kosztowało tyle, że zamrożona suma kontrolna
 * baselineu blokowała zmianę kształtu tabeli, którą trzeba było obejść dodatkową
 * migracją. Baza SkyBlocka to jeden lokalny plik SQLite jednego serwera, więc
 * odtworzenie jej było tańsze niż wleczenie tej historii.
 *
 * <p><b>Po publicznym starcie ta swoboda znika.</b> Każda kolejna zmiana schematu
 * musi być dopisaną migracją i nie wolno edytować wydanej — inaczej bazy w terenie
 * przestaną się uruchamiać na niezgodności sumy kontrolnej.
 *
 * <p>Mechanizm zostaje, bo po starcie będzie potrzebny: tabela wersji, suma
 * kontrolna każdej migracji, blokada wyłączności na czas migrowania oraz
 * {@code verifyCurrentSchema}, które przy starcie wykrywa bazę niezgodną z kodem.
 */
public final class SkyBlockSchemaMigrator {

    public static final int CURRENT_VERSION = 13;
    static final String MIGRATIONS_TABLE = "wpme_sb_schema_migrations";

    private static final long POSTGRES_ADVISORY_LOCK = 0x57504D4553424D31L;
    private static final String MIGRATIONS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_schema_migrations (
                schema_version INTEGER NOT NULL PRIMARY KEY,
                description VARCHAR(128) NOT NULL,
                checksum VARCHAR(64) NOT NULL,
                installed_at BIGINT NOT NULL
            )
            """;
    public static final String ONEBLOCK_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_oneblock (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                world_name VARCHAR(64) NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                phase INTEGER NOT NULL,
                progress INTEGER NOT NULL,
                total_mined INTEGER NOT NULL,
                phase_id VARCHAR(32) NOT NULL DEFAULT 'poczatek',
                dry_streak INTEGER NOT NULL DEFAULT 0
            )
            """;
    public static final String MILESTONES_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_oneblock_milestones (
                island_id VARCHAR(36) NOT NULL,
                milestone_key VARCHAR(64) NOT NULL,
                loot TEXT NOT NULL,
                created_at BIGINT NOT NULL,
                claimed_at BIGINT NULL,
                PRIMARY KEY (island_id, milestone_key)
            )
            """;
    public static final String CHUNKERS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_chunkers (
                island_id VARCHAR(36) NOT NULL,
                world VARCHAR(64) NOT NULL,
                chunk_x INTEGER NOT NULL,
                chunk_z INTEGER NOT NULL,
                chest_x INTEGER NOT NULL,
                chest_y INTEGER NOT NULL,
                chest_z INTEGER NOT NULL,
                owner_id VARCHAR(36) NOT NULL,
                PRIMARY KEY (world, chunk_x, chunk_z)
            )
            """;
    public static final String SELL_CHESTS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_sell_chests (
                island_id VARCHAR(36) NOT NULL,
                world VARCHAR(64) NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                owner_id VARCHAR(36) NOT NULL,
                created_at BIGINT NOT NULL,
                PRIMARY KEY (world, x, y, z)
            )
            """;
    public static final String WEEKLY_MILESTONES_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_weekly_milestones (
                account_key VARCHAR(48) NOT NULL,
                week_key VARCHAR(16) NOT NULL,
                claimed_at BIGINT NOT NULL,
                PRIMARY KEY (account_key, week_key)
            )
            """;
    public static final String SEASONAL_CLAIMS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_seasonal_claims (
                season_id INTEGER NOT NULL,
                player_id VARCHAR(36) NOT NULL,
                island_id VARCHAR(36) NOT NULL,
                rank_position INTEGER NOT NULL,
                claimed_at BIGINT NOT NULL,
                PRIMARY KEY (season_id, player_id)
            )
            """;
    public static final String SEASON_STATE_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_season_state (
                id INTEGER NOT NULL PRIMARY KEY,
                current_season INTEGER NOT NULL DEFAULT 1,
                updated_at BIGINT NOT NULL
            )
            """;
    public static final String SEASON_POINTS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_season_points (
                season_id   INTEGER     NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                points      BIGINT      NOT NULL DEFAULT 0,
                quests_done INTEGER     NOT NULL DEFAULT 0,
                updated_at  BIGINT      NOT NULL,
                PRIMARY KEY (season_id, player_uuid)
            )
            """;
    public static final String SEASON_HISTORY_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_season_history (
                season_id   INTEGER      NOT NULL,
                player_uuid VARCHAR(36)  NOT NULL,
                points      BIGINT       NOT NULL,
                rank_position INTEGER    NOT NULL,
                rewarded_at BIGINT       NOT NULL,
                PRIMARY KEY (season_id, player_uuid)
            )
            """;
    public static final String SEASON_PASS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_season_pass (
                season_id    INTEGER     NOT NULL,
                player_uuid  VARCHAR(36) NOT NULL,
                premium      BOOLEAN     NOT NULL DEFAULT FALSE,
                updated_at   BIGINT      NOT NULL,
                PRIMARY KEY (season_id, player_uuid)
            )
            """;
    public static final String SEASON_PASS_CLAIMS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_season_pass_claims (
                season_id   INTEGER     NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                level       INTEGER     NOT NULL,
                track       VARCHAR(8)  NOT NULL,
                claimed_at  BIGINT      NOT NULL,
                PRIMARY KEY (season_id, player_uuid, level, track)
            )
            """;
    /**
     * M2.5b: trwały postęp questów sezonowych per gracz per sezon — restart
     * nie zeruje liczników. Dedup nagród zostaje w reward_claims (już trwały);
     * ta tabela przenosi wyłącznie liczniki drążków questowych.
     */
    public static final String SEASON_QUEST_PROGRESS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_season_quest_progress (
                season_id   INTEGER     NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                quest_id    VARCHAR(64) NOT NULL,
                progress    INTEGER     NOT NULL DEFAULT 0,
                updated_at  BIGINT      NOT NULL,
                PRIMARY KEY (season_id, player_uuid, quest_id)
            )
            """;
    public static final String MINIONS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_minions (
                minion_id VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                type_id VARCHAR(32) NOT NULL,
                tier INT NOT NULL DEFAULT 1,
                world VARCHAR(64) NOT NULL,
                x DOUBLE NOT NULL,
                y DOUBLE NOT NULL,
                z DOUBLE NOT NULL,
                storage_json TEXT NOT NULL,
                fuel_type VARCHAR(64) NULL,
                fuel_expires_at BIGINT NOT NULL DEFAULT 0,
                compactor_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                linked_chest_x INT NULL,
                linked_chest_y INT NULL,
                linked_chest_z INT NULL,
                total_generated BIGINT NOT NULL DEFAULT 0,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """;
    public static final String MINIONS_ISLAND_IDX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_wpme_sb_minions_island ON wpme_sb_minions (island_id)";
    public static final String MINIONS_LOCATION_IDX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_wpme_sb_minions_location ON wpme_sb_minions (world, x, z)";
    // M1-A: island profile + membership guard (exactly one ACTIVE island per UUID)
    public static final String ISLAND_PROFILES_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_island_profiles (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                mode VARCHAR(16) NOT NULL DEFAULT 'CLASSIC',
                status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """;
    public static final String ISLAND_MEMBERSHIP_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_island_membership (
                island_id VARCHAR(36) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                role VARCHAR(16) NOT NULL,
                status VARCHAR(16) NOT NULL,
                joined_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                PRIMARY KEY (island_id, player_uuid),
                FOREIGN KEY (island_id) REFERENCES wpme_sb_island_profiles(island_id) ON DELETE CASCADE
            )
            """;
    public static final String ACTIVE_MEMBERSHIP_IDX_DDL =
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_wpme_sb_active_membership ON wpme_sb_island_membership (player_uuid) WHERE status = 'ACTIVE'";
    public static final String ACTIVE_MEMBERSHIP_HELPER_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_active_membership (
                player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """;
    // M1-B: profiled schema (profile_id, player_uuid) + playtime + backfill foundation
    public static final String PROFILE_ACCOUNTS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_profile_accounts (
                profile_id VARCHAR(36) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                balance_minor BIGINT NOT NULL DEFAULT 0,
                revision BIGINT NOT NULL DEFAULT 0,
                updated_at BIGINT NOT NULL,
                PRIMARY KEY (profile_id, player_uuid)
            )
            """;
    public static final String PROFILE_TRANSITIONS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_profile_transitions (
                operation_id VARCHAR(128) NOT NULL PRIMARY KEY,
                profile_id VARCHAR(36) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                transition_type VARCHAR(24) NOT NULL,
                from_status VARCHAR(16) NOT NULL,
                to_status VARCHAR(16) NOT NULL,
                checkpoint VARCHAR(64) NULL,
                result VARCHAR(16) NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """;
    public static final String PROFILE_TRANSITIONS_IDX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_wpme_sb_profile_transitions_profile ON wpme_sb_profile_transitions (profile_id, player_uuid)";
    public static final String PROFILE_SNAPSHOTS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_profile_snapshots (
                snapshot_id VARCHAR(128) NOT NULL PRIMARY KEY,
                profile_id VARCHAR(36) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                server_scope VARCHAR(32) NOT NULL,
                snapshot_type VARCHAR(16) NOT NULL,
                payload MEDIUMBLOB NULL,
                payload_sha256 VARCHAR(64) NOT NULL,
                content_sha256 VARCHAR(64) NOT NULL,
                created_at BIGINT NOT NULL
            )
            """;
    public static final String PROFILE_SNAPSHOTS_IDX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_wpme_sb_profile_snapshots_profile ON wpme_sb_profile_snapshots (profile_id, player_uuid)";
    public static final String PLAYTIME_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_playtime (
                profile_id VARCHAR(36) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                total_active_ms BIGINT NOT NULL DEFAULT 0,
                total_afk_ms BIGINT NOT NULL DEFAULT 0,
                session_start BIGINT NULL,
                last_heartbeat BIGINT NULL,
                revision BIGINT NOT NULL DEFAULT 0,
                updated_at BIGINT NOT NULL,
                PRIMARY KEY (profile_id, player_uuid)
            )
            """;
    public static final String CONTENT_ASSIGNMENTS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_content_assignments (
                profile_id VARCHAR(36) NOT NULL,
                domain VARCHAR(32) NOT NULL,
                content_version VARCHAR(32) NOT NULL,
                assigned_at BIGINT NOT NULL,
                PRIMARY KEY (profile_id, domain)
            )
            """;
    public static final String REWARD_CLAIMS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_reward_claims (
                profile_id VARCHAR(36) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                reward_id VARCHAR(64) NOT NULL,
                operation_id VARCHAR(128) NOT NULL,
                claimed_at BIGINT NOT NULL,
                PRIMARY KEY (profile_id, player_uuid, reward_id)
            )
            """;
    public static final String REWARD_CLAIMS_OP_IDX_DDL =
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_wpme_sb_reward_claims_operation ON wpme_sb_reward_claims (operation_id)";
    /**
     * Migracja #7: aktywacje nazwanych edycji sezonów (editions.yml).
     * Append-only audyt: PK (season_id, activated_at) czyni powtórny zapis
     * idempotentnym; closed_at znacznik domknięcia ustawiany w transakcji
     * rolloveru. Wydane migracje 1–6 pozostają nietykalne.
     */
    public static final String SEASON_EDITIONS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_season_editions (
                season_id INTEGER NOT NULL, activated_at BIGINT NOT NULL,
                slug VARCHAR(64) NOT NULL, display_name VARCHAR(128) NOT NULL,
                edition_type VARCHAR(8) NOT NULL, start_date VARCHAR(10) NOT NULL, end_date VARCHAR(10) NOT NULL,
                closed_at BIGINT NULL,
                PRIMARY KEY (season_id, activated_at))
            """;
    public static final String SEASON_EDITIONS_IDX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_wpme_sb_season_editions_season "
                    + "ON wpme_sb_season_editions (season_id, activated_at)";
    private static final List<String> SEASON_EDITIONS_MIGRATION_DDL = List.of(
            SEASON_EDITIONS_DDL,
            SEASON_EDITIONS_IDX_DDL);
    private static final Migration SEASON_EDITIONS = new Migration(
            7,
            "season-editions",
            checksum(String.join("\n-- ddl --\n", SEASON_EDITIONS_MIGRATION_DDL)));
    private static final List<String> BASELINE_DDL = List.of(
            LedgerDao.ACCOUNTS_DDL,
            LedgerDao.TRANSACTIONS_DDL,
            LedgerDao.QUESTS_DDL,
            LedgerDao.DAILY_CLAIMS_DDL,
            LedgerDao.INVENTORY_OUTBOX_DDL,
            LedgerDao.INVENTORY_OUTBOX_LINES_DDL,
            ONEBLOCK_DDL,
            MILESTONES_DDL,
            CHUNKERS_DDL,
            SELL_CHESTS_DDL,
            WEEKLY_MILESTONES_DDL,
            SEASONAL_CLAIMS_DDL,
            SEASON_STATE_DDL,
            MINIONS_DDL,
            MINIONS_ISLAND_IDX_DDL,
            MINIONS_LOCATION_IDX_DDL);
    private static final Map<String, Set<String>> BASELINE_COLUMNS = Map.ofEntries(
            Map.entry("wpme_sb_accounts", Set.of(
                    "account_key", "balance_minor", "updated_at")),
            Map.entry("wpme_sb_transactions", Set.of(
                    "transaction_id", "account_key", "counterparty_key",
                    "delta_minor", "reason", "created_at")),
            Map.entry("wpme_sb_quest_progress", Set.of(
                    "account_key", "period_key", "quest_id", "progress", "rewarded",
                    "last_batch_id", "last_batch_owner_id", "last_batch_increment",
                    "last_batch_target", "last_batch_reward",
                    "last_batch_rewarded_now", "updated_at")),
            Map.entry("wpme_sb_daily_claims", Set.of(
                    "owner_id", "period_key", "quest_id",
                    "island_account_key", "created_at")),
            Map.entry("wpme_sb_inventory_outbox", Set.of(
                    "operation_id", "player_id", "operation_type", "transaction_id",
                    "status", "item_payload", "auxiliary_type", "auxiliary_key",
                    "auxiliary_value", "created_at", "updated_at")),
            Map.entry("wpme_sb_inventory_outbox_lines", Set.of(
                    "operation_id", "material_key", "custom_item_id",
                    "remove_amount", "baseline_count")),
            Map.entry("wpme_sb_oneblock", Set.of(
                    "island_id", "world_name", "x", "y", "z", "phase", "progress", "total_mined",
                    "phase_id", "dry_streak")),
            Map.entry("wpme_sb_oneblock_milestones", Set.of(
                    "island_id", "milestone_key", "loot", "created_at", "claimed_at")),
            Map.entry("wpme_sb_chunkers", Set.of(
                    "island_id", "world", "chunk_x", "chunk_z", "chest_x", "chest_y", "chest_z", "owner_id")),
            Map.entry("wpme_sb_sell_chests", Set.of(
                    "island_id", "world", "x", "y", "z", "owner_id", "created_at")),
            Map.entry("wpme_sb_weekly_milestones", Set.of(
                    "account_key", "week_key", "claimed_at")),
            Map.entry("wpme_sb_seasonal_claims", Set.of(
                    "season_id", "player_id", "island_id", "rank_position", "claimed_at")),
            Map.entry("wpme_sb_season_state", Set.of(
                    "id", "current_season", "updated_at")),
            Map.entry("wpme_sb_minions", Set.of(
                    "minion_id", "island_id", "type_id", "tier", "world", "x", "y", "z",
                    "storage_json", "fuel_type", "fuel_expires_at", "compactor_enabled",
                    "linked_chest_x", "linked_chest_y", "linked_chest_z",
                    "total_generated", "created_at", "updated_at")),
            Map.entry("wpme_sb_island_profiles", Set.of(
                    "island_id", "owner_uuid", "mode", "status", "created_at", "updated_at")),
            Map.entry("wpme_sb_island_membership", Set.of(
                    "island_id", "player_uuid", "role", "status", "joined_at", "updated_at")),
            Map.entry("wpme_sb_active_membership", Set.of(
                    "player_uuid", "island_id", "updated_at")),
            Map.entry("wpme_sb_profile_accounts", Set.of(
                    "profile_id", "player_uuid", "balance_minor", "revision", "updated_at")),
            Map.entry("wpme_sb_profile_transitions", Set.of(
                    "operation_id", "profile_id", "player_uuid", "transition_type",
                    "from_status", "to_status", "checkpoint", "result", "created_at", "updated_at")),
            Map.entry("wpme_sb_profile_snapshots", Set.of(
                    "snapshot_id", "profile_id", "player_uuid", "server_scope",
                    "snapshot_type", "payload", "payload_sha256", "content_sha256", "created_at")),
            Map.entry("wpme_sb_playtime", Set.of(
                    "profile_id", "player_uuid", "total_active_ms", "total_afk_ms",
                    "session_start", "last_heartbeat", "revision", "updated_at")),
            Map.entry("wpme_sb_content_assignments", Set.of(
                    "profile_id", "domain", "content_version", "assigned_at")),
            Map.entry("wpme_sb_reward_claims", Set.of(
                    "profile_id", "player_uuid", "reward_id", "operation_id", "claimed_at")));
    private static final Migration BASELINE = new Migration(
            1,
            "baseline-skyblock-schema",
            checksum(String.join("\n-- ddl --\n", BASELINE_DDL)));
    private static final List<String> PROFILE_DDL = List.of(
            ISLAND_PROFILES_DDL,
            ISLAND_MEMBERSHIP_DDL,
            ACTIVE_MEMBERSHIP_IDX_DDL,
            ACTIVE_MEMBERSHIP_HELPER_DDL);
    private static final Migration PROFILE_GUARD = new Migration(
            2,
            "island-profile-membership-guard",
            checksum(String.join("\n-- ddl --\n", PROFILE_DDL)));
    private static final List<String> PROFILE_ISOLATION_DDL = List.of(
            PROFILE_ACCOUNTS_DDL,
            PROFILE_TRANSITIONS_DDL,
            PROFILE_TRANSITIONS_IDX_DDL,
            PROFILE_SNAPSHOTS_DDL,
            PROFILE_SNAPSHOTS_IDX_DDL,
            PLAYTIME_DDL,
            CONTENT_ASSIGNMENTS_DDL,
            REWARD_CLAIMS_DDL,
            REWARD_CLAIMS_OP_IDX_DDL);
    private static final Migration PROFILE_ISOLATION = new Migration(
            3,
            "profile-isolation-playtime-backfill",
            checksum(String.join("\n-- ddl --\n", PROFILE_ISOLATION_DDL)));
    /**
     * SKYBLOCK-1-7: nagroda sezonowa per WYSPA, nie per gracz — bez tego każdy
     * dopisany członek TOP-3 wyspy odbiera pełną pulę (50k/30k/15k monet +
     * Lotosy), a zaproszenie kolegów po zamknięciu sezonu mnoży pulę bez limitu.
     * Unikalność (season_id, island_id) egzekwuje jeden claim na wyspę na sezon.
     * Indeks tworzony po fakcie: CREATE UNIQUE INDEX zawiedzie, gdy stara baza
     * ma już duplikaty per-wyspowe w tym samym sezonie; wtedy migracja raportuje
     * drift zamiast milcząco zostawić lukę (decyzja operatora: wyczyścić dupl.)
     */
    public static final String SEASONAL_CLAIMS_ISLAND_IDX_DDL =
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_wpme_sb_seasonal_claims_island "
                    + "ON wpme_sb_seasonal_claims (season_id, island_id)";
    private static final List<String> SEASONAL_ISLAND_CLAIM_DDL = List.of(
            SEASONAL_CLAIMS_ISLAND_IDX_DDL);
    private static final Migration SEASONAL_ISLAND_CLAIM = new Migration(
            4,
            "seasonal-claim-per-island",
            checksum(String.join("\n-- ddl --\n", SEASONAL_ISLAND_CLAIM_DDL)));
    /**
     * M2.5: fundament sezonów — punkty, historia rankingu, przepustka + claimy.
     * Nowe tabele idą jako migracja (nie baseline), bo baseline checksum jest
     * przypięty w istniejących bazach i jego zmiana = celowy drift.
     */
    private static final List<String> SEASON_FOUNDATION_DDL = List.of(
            SEASON_POINTS_DDL,
            SEASON_HISTORY_DDL,
            SEASON_PASS_DDL,
            SEASON_PASS_CLAIMS_DDL);
    private static final Migration SEASON_FOUNDATION = new Migration(
            5,
            "season-points-foundation",
            checksum(String.join("\n-- ddl --\n", SEASON_FOUNDATION_DDL)));
    private static final List<String> SEASON_QUEST_PROGRESS_MIGRATION_DDL = List.of(
            SEASON_QUEST_PROGRESS_DDL);
    private static final Migration SEASON_QUEST_PROGRESS = new Migration(
            6,
            "season-quest-progress",
            checksum(String.join("\n-- ddl --\n", SEASON_QUEST_PROGRESS_MIGRATION_DDL)));
    /**
     * LEDGER-1: {@code wpme_sb_inventory_outbox} nie miał indeksu ani na
     * {@code player_id}, ani na {@code status}, a {@code PENDING_FOR_PLAYER_SQL}
     * filtruje po obu. {@code EXPLAIN QUERY PLAN} na produkcyjnej bazie pokazywał
     * {@code SCAN}. Zapytanie leci przy każdym wejściu gracza i co najmniej 2× na
     * operację ekonomiczną, na puli SQLite = 1 — przy 1 M wierszy to 79 ms z
     * zamrożonym graczem (strażnik ekwipunku trzyma go przez cały ten czas).
     *
     * <p>Osobna migracja, nie zmiana {@code INVENTORY_OUTBOX_DDL}: suma kontrolna
     * baselineu jest przypięta w istniejących bazach, a jej zmiana wywala start.
     */
    public static final String INVENTORY_OUTBOX_PLAYER_STATUS_IDX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_wpme_sb_inventory_outbox_player_status "
                    + "ON wpme_sb_inventory_outbox (player_id, status)";
    private static final List<String> OUTBOX_INDEX_DDL = List.of(
            INVENTORY_OUTBOX_PLAYER_STATUS_IDX_DDL);
    private static final Migration OUTBOX_PLAYER_STATUS_INDEX = new Migration(
            8,
            "inventory-outbox-player-status-index",
            checksum(String.join("\n-- ddl --\n", OUTBOX_INDEX_DDL)));
    /**
     * Migracja #9: codzienna nagroda i seria logowań. Jeden wiersz na gracza;
     * {@code last_claim_day} to data UTC (YYYY-MM-DD), więc porównanie tekstowe
     * wystarcza, a {@code UPDATE … WHERE last_claim_day <> dziś} daje odbiór
     * idempotentny bez blokad. Wydane migracje 1–8 pozostają nietykalne.
     */
    public static final String DAILY_REWARD_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_daily_reward (
                player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                last_claim_day VARCHAR(10) NOT NULL,
                streak INTEGER NOT NULL,
                claimed_total INTEGER NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """;
    private static final List<String> DAILY_REWARD_MIGRATION_DDL = List.of(
            DAILY_REWARD_DDL);
    private static final Migration DAILY_REWARD = new Migration(
            9,
            "daily-reward-streak",
            checksum(String.join("\n-- ddl --\n", DAILY_REWARD_MIGRATION_DDL)));
    /**
     * Migracja #10: poziom wyspy. Jeden wiersz na wyspę — zapisany poziom (nigdy
     * nie spada), bieżące xp i czas przeliczenia. Nagrody za awans idą przez
     * ledger z idempotentnym txId, więc tabela nie musi ich pamiętać.
     */
    public static final String ISLAND_LEVEL_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_island_level (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                level INTEGER NOT NULL,
                xp BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """;
    private static final List<String> ISLAND_LEVEL_MIGRATION_DDL = List.of(
            ISLAND_LEVEL_DDL);
    private static final Migration ISLAND_LEVEL = new Migration(
            10,
            "island-level",
            checksum(String.join("\n-- ddl --\n", ISLAND_LEVEL_MIGRATION_DDL)));
    /**
     * Migracja #11: dzienny kanał punktów sezonowych z capem per kanał (P1-1).
     * PK {@code (player_uuid, day, channel, operation_id)} czyni powtórkę tego
     * samego zdarzenia w tym samym dniu no-opem, a nowy dzień = nowy wiersz,
     * więc doba jest jedynym okresem rozliczeniowym capu. Indeks obsługuje
     * jedyne zapytanie odczytowe: sumę dnia dla (gracz, kanał). Wydane
     * migracje 1–10 pozostają nietykalne.
     */
    public static final String SEASON_DAILY_POINTS_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_season_daily_points (
                season_id    INTEGER      NOT NULL,
                player_uuid  VARCHAR(36)  NOT NULL,
                day          VARCHAR(10)  NOT NULL,
                channel      VARCHAR(32)  NOT NULL,
                operation_id VARCHAR(128) NOT NULL,
                points       BIGINT       NOT NULL,
                created_at   BIGINT       NOT NULL,
                PRIMARY KEY (player_uuid, day, channel, operation_id)
            )
            """;
    public static final String SEASON_DAILY_POINTS_LOOKUP_IDX_DDL =
            "CREATE INDEX IF NOT EXISTS idx_wpme_sb_season_daily_points_lookup "
                    + "ON wpme_sb_season_daily_points (player_uuid, day, channel)";
    private static final List<String> SEASON_DAILY_POINTS_MIGRATION_DDL = List.of(
            SEASON_DAILY_POINTS_DDL,
            SEASON_DAILY_POINTS_LOOKUP_IDX_DDL);
    private static final Migration SEASON_DAILY_POINTS = new Migration(
            11,
            "season-daily-points-caps",
            checksum(String.join("\n-- ddl --\n", SEASON_DAILY_POINTS_MIGRATION_DDL)));
    /**
     * Migracja #12: Prestiż Wyspy — powtarzalny zlew monet weterana z nagrodą
     * czysto kosmetyczną. Jeden wiersz na wyspę: osiągnięty poziom (nigdy nie
     * spada) i suma monet wyjętych z banku wyspy. Płatność idzie przez ledger
     * z idempotentnym {@code prestige:<wyspa>:<poziom>}, więc tabela nie musi
     * rozliczać transakcji — trzyma tylko stan widoczny w menu i na kafelku.
     * Wydane migracje 1–11 pozostają nietykalne.
     */
    public static final String ISLAND_PRESTIGE_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_island_prestige (
                island_id   VARCHAR(36) NOT NULL PRIMARY KEY,
                level       INTEGER     NOT NULL,
                spent_minor BIGINT      NOT NULL,
                updated_at  BIGINT      NOT NULL
            )
            """;
    private static final List<String> ISLAND_PRESTIGE_MIGRATION_DDL = List.of(
            ISLAND_PRESTIGE_DDL);
    private static final Migration ISLAND_PRESTIGE = new Migration(
            12,
            "island-prestige",
            checksum(String.join("\n-- ddl --\n", ISLAND_PRESTIGE_MIGRATION_DDL)));
    /**
     * Migracja #13: tytuł wyspy — jeden trwały tytuł kosmetyczny na wyspę.
     * Tabela nie trzyma historii: wygrywa tytuł o najwyższym priorytecie
     * źródła (patrz {@code IslandTitleService.priorityOf}), a {@code source_id}
     * jest kluczem idempotencji, więc powtórka nadania z tego samego źródła
     * nie kosztuje drugi raz ani nie nadpisuje lepszego tytułu.
     * Wydane migracje 1–12 pozostają nietykalne.
     */
    public static final String ISLAND_TITLES_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_island_titles (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                title VARCHAR(64) NOT NULL,
                source_id VARCHAR(64) NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """;
    private static final List<String> ISLAND_TITLES_MIGRATION_DDL = List.of(
            ISLAND_TITLES_DDL);
    private static final Migration ISLAND_TITLES = new Migration(
            13,
            "island-titles",
            checksum(String.join("\n-- ddl --\n", ISLAND_TITLES_MIGRATION_DDL)));
    private static final List<Migration> MIGRATIONS = List.of(
            BASELINE, PROFILE_GUARD, PROFILE_ISOLATION, SEASONAL_ISLAND_CLAIM,
            SEASON_FOUNDATION, SEASON_QUEST_PROGRESS, SEASON_EDITIONS,
            OUTBOX_PLAYER_STATUS_INDEX, DAILY_REWARD, ISLAND_LEVEL,
            SEASON_DAILY_POINTS, ISLAND_PRESTIGE, ISLAND_TITLES);

    private final SqlService sql;

    public SkyBlockSchemaMigrator(@NotNull SqlService sql) {
        this.sql = sql;
    }

    public @NotNull CompletableFuture<Void> migrate() {
        return sql.withConnection(connection -> {
            SqlDialect dialect = detectDialect(connection);
            configureConnection(connection, dialect);
            executeDdl(connection, dialect, MIGRATIONS_DDL);
            migratePending(connection, dialect);
            return null;
        });
    }

    private static void configureConnection(Connection connection, SqlDialect dialect)
            throws SQLException {
        if (dialect != SqlDialect.SQLITE) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    private static void migratePending(Connection connection, SqlDialect dialect)
            throws SQLException {
        Map<Integer, AppliedMigration> applied = loadApplied(connection);
        validateHistory(applied);
        for (Migration migration : MIGRATIONS) {
            if (!applied.containsKey(migration.version())) {
                applyMigration(connection, dialect, migration);
                applied = loadApplied(connection);
                validateHistory(applied);
            }
        }
        verifyCurrentSchema(connection);
    }

    private static void applyMigration(Connection connection, SqlDialect dialect,
                                       Migration migration)
            throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (dialect == SqlDialect.POSTGRESQL) {
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(1, POSTGRES_ADVISORY_LOCK);
                    lock.executeQuery().close();
                }
            }

            Map<Integer, AppliedMigration> lockedHistory = loadApplied(connection);
            validateHistory(lockedHistory);
            if (lockedHistory.containsKey(migration.version())) {
                connection.commit();
                restoreAutoCommitAfterCommit(connection, originalAutoCommit);
                return;
            }

            if (migration.version() == BASELINE.version()) {
                for (String ddl : BASELINE_DDL) {
                    executeDdl(connection, dialect, ddl);
                }
            } else if (migration.version() == PROFILE_GUARD.version()) {
                for (String ddl : PROFILE_DDL) {
                    try {
                        executeDdl(connection, dialect, ddl);
                    } catch (SQLException e) {
                        // MySQL does not support partial index WHERE clause; helper table covers it.
                        if (ddl.equals(ACTIVE_MEMBERSHIP_IDX_DDL) && dialect == SqlDialect.MYSQL) {
                            // ignore — helper table is the MySQL guard
                        } else {
                            throw e;
                        }
                    }
                }
            } else if (migration.version() == PROFILE_ISOLATION.version()) {
                for (String ddl : PROFILE_ISOLATION_DDL) {
                    executeDdl(connection, dialect, ddl);
                }
            } else if (migration.version() == SEASONAL_ISLAND_CLAIM.version()) {
                for (String ddl : SEASONAL_ISLAND_CLAIM_DDL) {
                    executeDdl(connection, dialect, ddl);
                }
            } else if (migration.version() == SEASON_FOUNDATION.version()) {
                for (String ddl : SEASON_FOUNDATION_DDL) {
                    executeDdl(connection, dialect, ddl);
                }
            } else if (migration.version() == SEASON_QUEST_PROGRESS.version()) {
                for (String ddl : SEASON_QUEST_PROGRESS_MIGRATION_DDL) {
                    executeDdl(connection, dialect, ddl);
                }
            } else if (migration.version() == SEASON_EDITIONS.version()) {
                for (String ddl : SEASON_EDITIONS_MIGRATION_DDL) {
                    executeDdl(connection, dialect, ddl);
                }
            } else if (migration.version() == OUTBOX_PLAYER_STATUS_INDEX.version()) {
                for (String ddl : OUTBOX_INDEX_DDL) {
                    executeDdl(connection, dialect, ddl);
                }
            } else if (migration.version() == DAILY_REWARD.version()) {
                for (String ddl : DAILY_REWARD_MIGRATION_DDL) {
                    executeDdl(connection, dialect, ddl);
                }
            } else if (migration.version() == ISLAND_LEVEL.version()) {
                for (String ddl : ISLAND_LEVEL_MIGRATION_DDL) {
                    executeDdl(connection, dialect, ddl);
                }
            } else if (migration.version() == SEASON_DAILY_POINTS.version()) {
                for (String ddl : SEASON_DAILY_POINTS_MIGRATION_DDL) {
                    executeDdl(connection, dialect, ddl);
                }
            } else if (migration.version() == ISLAND_PRESTIGE.version()) {
                for (String ddl : ISLAND_PRESTIGE_MIGRATION_DDL) {
                    executeDdl(connection, dialect, ddl);
                }
            } else if (migration.version() == ISLAND_TITLES.version()) {
                for (String ddl : ISLAND_TITLES_MIGRATION_DDL) {
                    executeDdl(connection, dialect, ddl);
                }
            } else {
                throw new SQLException("missing migration implementation "
                        + migration.version());
            }

            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO wpme_sb_schema_migrations
                        (schema_version, description, checksum, installed_at)
                    VALUES (?, ?, ?, ?)
                    """)) {
                insert.setInt(1, migration.version());
                insert.setString(2, migration.description());
                insert.setString(3, migration.checksum());
                insert.setLong(4, System.currentTimeMillis());
                if (insert.executeUpdate() != 1) {
                    throw new SQLException("could not record schema migration "
                            + migration.version());
                }
            }
            connection.commit();
            restoreAutoCommitAfterCommit(connection, originalAutoCommit);
        } catch (Throwable failure) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            try {
                connection.setAutoCommit(originalAutoCommit);
            } catch (SQLException restore) {
                failure.addSuppressed(restore);
            }
            if (failure instanceof SQLException sqlFailure) {
                throw sqlFailure;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new SQLException("schema migration failed", failure);
        }
    }

    private static void restoreAutoCommitAfterCommit(Connection connection,
                                                     boolean originalAutoCommit) {
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException restore) {
            try {
                connection.close();
            } catch (SQLException close) {
                restore.addSuppressed(close);
            }
        }
    }

    private static void executeDdl(Connection connection, SqlDialect dialect, String ddl)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            // The SkyBlock schema is deliberately portable DDL. PostgreSQL's
            // binary type is the sole vendor difference. Executing the full
            // statement also preserves multiline FOREIGN KEY clauses; a
            // line-oriented MySQL schema converter must not split them.
            String nativeDdl = dialect == SqlDialect.POSTGRESQL
                    ? ddl.replaceAll("(?i)\\bMEDIUMBLOB\\b", "BYTEA")
                    : ddl;
            statement.executeUpdate(nativeDdl);
        }
    }

    private static Map<Integer, AppliedMigration> loadApplied(Connection connection)
            throws SQLException {
        Map<Integer, AppliedMigration> applied = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT schema_version, description, checksum, installed_at
                FROM wpme_sb_schema_migrations
                ORDER BY schema_version
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                AppliedMigration row = new AppliedMigration(
                        result.getInt(1), result.getString(2),
                        result.getString(3), result.getLong(4));
                if (applied.put(row.version(), row) != null) {
                    throw new SQLException("duplicate schema migration " + row.version());
                }
            }
        }
        return applied;
    }

    private static void validateHistory(Map<Integer, AppliedMigration> applied)
            throws SQLException {
        List<Integer> versions = new ArrayList<>(applied.keySet());
        versions.sort(Integer::compareTo);
        int expected = 1;
        for (int version : versions) {
            if (version != expected) {
                throw new SQLException("non-contiguous SkyBlock schema history: expected "
                        + expected + " but found " + version);
            }
            if (version > CURRENT_VERSION) {
                throw new SQLException("database schema " + version
                        + " is newer than supported " + CURRENT_VERSION);
            }
            Migration known = MIGRATIONS.get(version - 1);
            AppliedMigration row = applied.get(version);
            if (!known.description().equals(row.description())
                    || !known.checksum().equals(row.checksum())) {
                throw new SQLException("schema migration " + version
                        + " checksum or description mismatch");
            }
            expected++;
        }
    }

    private static void verifyCurrentSchema(Connection connection) throws SQLException {
        for (Map.Entry<String, Set<String>> entry : BASELINE_COLUMNS.entrySet()) {
            Set<String> present = new java.util.HashSet<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM " + entry.getKey() + " WHERE 1 = 0");
                 ResultSet result = statement.executeQuery()) {
                var metadata = result.getMetaData();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    present.add(metadata.getColumnName(index).toLowerCase(Locale.ROOT));
                }
            } catch (SQLException missingTable) {
                throw new SQLException("missing or unreadable schema table "
                        + entry.getKey(), missingTable);
            }
            if (!present.containsAll(entry.getValue())) {
                Set<String> missing = new java.util.TreeSet<>(entry.getValue());
                missing.removeAll(present);
                throw new SQLException("schema drift in " + entry.getKey()
                        + ": missing columns " + missing);
            }
        }
    }

    private SqlDialect detectDialect(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        String normalized = product == null ? "" : product.toLowerCase(Locale.ROOT);
        if (normalized.contains("sqlite")) {
            return SqlDialect.SQLITE;
        }
        if (normalized.contains("postgresql")) {
            return SqlDialect.POSTGRESQL;
        }
        if (normalized.contains("mariadb") || normalized.contains("mysql")) {
            return SqlDialect.MYSQL;
        }
        if (normalized.contains("h2")) {
            return SqlDialect.H2;
        }
        return sql.dialect();
    }

    private static String checksum(String source) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private record Migration(int version, @NotNull String description,
                             @NotNull String checksum) { }

    private record AppliedMigration(int version, @NotNull String description,
                                    @NotNull String checksum, long installedAt) { }
}
