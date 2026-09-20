package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.rafalohaki.wpmecore.api.service.SqlDialect;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
@EnabledIfEnvironmentVariable(
        named = "WPME_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.+")
class LedgerPostgreSqlIntegrationTest {

    /** Canonical OneBlock chapter ids (oneblock.yml order), used for migration 7. */
    private static final List<String> PHASE_IDS = List.of(
            "poczatek", "podziemia", "zima", "pustynia", "dzungla", "pieklo", "kres");

    private String jdbcUrl;
    private String username;
    private String password;
    private String schema;
    private JdbcSqlService sql;
    private LedgerDao dao;

    @BeforeEach
    void setUp() throws SQLException {
        jdbcUrl = System.getenv("WPME_TEST_POSTGRES_URL");
        username = environment("WPME_TEST_POSTGRES_USER", "wpme");
        password = environment("WPME_TEST_POSTGRES_PASSWORD", "wpme");
        schema = "wpme_it_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE SCHEMA " + schema);
        }
        openService();
        dao.createSchema().join();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (sql != null) {
            sql.shutdown();
        }
        if (schema != null) {
            try (Connection connection = DriverManager.getConnection(
                    jdbcUrl, username, password);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void migratesLedgerAndPersistsBinaryOutboxIdempotently() {
        dao.createSchema().join();
        assertEquals(1L, scalar("SELECT COUNT(*) FROM wpme_sb_schema_migrations"));

        UUID playerId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID islandId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        AccountKey player = AccountKey.player(playerId);
        AccountKey island = AccountKey.island(islandId);
        dao.ensureAccount(player, 100L, "pg:player:start").join();

        LedgerDao.Transfer first = dao.transfer(
                player, island, 100L, 40L, "pg:transfer", "integration").join();
        LedgerDao.Transfer retry = dao.transfer(
                player, island, 100L, 40L, "pg:transfer", "integration").join();

        assertTrue(first.applied());
        assertFalse(retry.applied());
        assertEquals(60L, retry.sourceBalance());
        assertEquals(40L, retry.targetBalance());
        assertEquals(0L, scalar("""
                SELECT COALESCE(SUM(delta_minor), 0)
                FROM wpme_sb_transactions
                WHERE transaction_id LIKE 'pg:transfer:%'
                """));

        byte[] payload = {0, 1, 2, 3, 127, -1};
        LedgerDao.InventoryOperation operation = new LedgerDao.InventoryOperation(
                "pg:outbox:1", playerId,
                LedgerDao.InventoryOperationType.GRANT,
                "pg:outbox:transaction", LedgerDao.InventoryStatus.PENDING,
                payload, null, null, null, List.of(), 1L, 1L);
        LedgerDao.InventoryMutation mutation = dao.mutateWithInventoryOperation(
                player, 100L, -5L, "pg:outbox:transaction",
                "integration", operation).join();

        assertTrue(mutation.mutation().applied());
        Optional<LedgerDao.InventoryOperation> stored =
                dao.inventoryOperation(operation.operationId()).join();
        assertTrue(stored.isPresent());
        assertArrayEquals(payload, stored.orElseThrow().itemPayload());
    }

    private void openService() {
        sql = new JdbcSqlService(jdbcUrl, username, password, schema);
        dao = new LedgerDao(sql);
    }

    private long scalar(String query) {
        return sql.query(query, row -> row.getLong(1)).join().getFirst();
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static final class JdbcSqlService implements SqlService {
        private final String jdbcUrl;
        private final String username;
        private final String password;
        private final String schema;
        private final ExecutorService executor = Executors.newFixedThreadPool(4);
        private volatile boolean shutdown;

        private JdbcSqlService(String jdbcUrl, String username,
                               String password, String schema) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
            this.schema = schema;
        }

        @Override public @NotNull SqlDialect dialect() { return SqlDialect.POSTGRESQL; }
        @Override public boolean isEnabled() { return !shutdown; }
        @Override public boolean isConnected() { return !shutdown; }
        @Override public boolean isHealthy() { return !shutdown; }
        @Override public @NotNull Map<String, Object> poolStats() {
            return Map.of("dialect", "POSTGRESQL", "threads", 4);
        }

        @Override
        public void shutdown() {
            shutdown = true;
            executor.shutdownNow();
            try {
                executor.awaitTermination(5L, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public @NotNull CompletableFuture<Integer> update(@NotNull String query,
                                                           @NotNull Object... params) {
            return submit(() -> {
                try (Connection connection = open();
                     PreparedStatement statement = connection.prepareStatement(query)) {
                    bind(statement, params);
                    return statement.executeUpdate();
                }
            });
        }

        @Override
        public <T> @NotNull CompletableFuture<List<T>> query(
                @NotNull String query, @NotNull RowMapper<T> mapper,
                @NotNull Object... params) {
            return submit(() -> {
                try (Connection connection = open();
                     PreparedStatement statement = connection.prepareStatement(query)) {
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
            return submit(() -> {
                try (Connection connection = open()) {
                    return action.execute(connection);
                }
            });
        }

        @Override public @NotNull Object dataSource() { return this; }

        private Connection open() throws SQLException {
            Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
            connection.setSchema(schema);
            return connection;
        }

        private <T> CompletableFuture<T> submit(SqlSupplier<T> supplier) {
            if (shutdown) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("test SQL service is shut down"));
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return supplier.get();
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            }, executor);
        }

        private static void bind(PreparedStatement statement, Object... params)
                throws SQLException {
            for (int index = 0; index < params.length; index++) {
                statement.setObject(index + 1, params[index]);
            }
        }

        @FunctionalInterface
        private interface SqlSupplier<T> {
            T get() throws Exception;
        }
    }
}
