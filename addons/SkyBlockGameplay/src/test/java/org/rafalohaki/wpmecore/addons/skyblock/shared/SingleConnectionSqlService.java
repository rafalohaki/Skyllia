package org.rafalohaki.wpmecore.addons.skyblock.shared;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlDialect;
import org.rafalohaki.wpmecore.api.service.SqlService;

/**
 * Atrapa SQL na jednym połączeniu do SQLite w pamięci.
 *
 * <p>Mieszkała jako klasa zagnieżdżona w MinionDaoTest, a używa jej osiem klas
 * testowych z różnych funkcji — czyli była wspólnym narzędziem schowanym
 * w cudzym teście. Po podziale na pakiety musiała stanąć tam, gdzie jej
 * miejsce, bo inaczej testy każdej funkcji sięgałyby do testów minionków.
 */
/** Kopia wzorca ze SkyBlockSchemaMigratorTest: jeden connection na :memory: SQLite. */
public final class SingleConnectionSqlService implements SqlService {
    private final Connection connection;

    public SingleConnectionSqlService() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    /** Wariant plikowy dla dry-runów na kopiach rzeczywistych baz (M1-B). */
    public SingleConnectionSqlService(@NotNull String jdbcUrl) throws SQLException {
        connection = DriverManager.getConnection(jdbcUrl);
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
