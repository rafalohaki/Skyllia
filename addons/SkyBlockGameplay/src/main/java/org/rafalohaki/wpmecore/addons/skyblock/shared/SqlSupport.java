package org.rafalohaki.wpmecore.addons.skyblock.shared;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Shared SQL primitives for the ledger DAOs (transactions and constraint-safe inserts). */
public final class SqlSupport {

    private SqlSupport() {
    }

    /**
     * PostgreSQL aborts the entire transaction after a constraint violation,
     * even when Java catches the exception. Use an explicit conflict target
     * there; SQLite/MySQL retain the proven legacy insert-and-classify path.
     */
    public static boolean insertIgnoringConstraint(
            Connection connection, String insertSql, String postgresConflict,
            SqlBinder binder) throws SQLException {
        boolean postgresql = isPostgresql(connection);
        String nativeSql = postgresql
                ? insertSql.stripTrailing() + '\n' + postgresConflict
                : insertSql;
        try (PreparedStatement statement = connection.prepareStatement(nativeSql)) {
            binder.bind(statement);
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            if (!postgresql && isConstraintViolation(failure)) {
                return false;
            }
            throw failure;
        }
    }

    public static boolean isPostgresql(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        return product != null
                && product.toLowerCase(java.util.Locale.ROOT).contains("postgresql");
    }

    public static boolean isConstraintViolation(SQLException failure) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if ((state != null && state.startsWith("23"))
                    || current.getErrorCode() == 19 || current.getErrorCode() == 1_062) {
                return true;
            }
        }
        return false;
    }

    public static <T> T inTransaction(Connection connection, SqlWork<T> work) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            /*
             * COMMIT is the durable outcome. A broken connection that refuses
             * to restore auto-commit must not turn an acknowledged commit into
             * an apparent failure and trigger a compensating retry.
             * Hikari discards/resets that connection on return.
             */
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException ignoredAfterCommit) {
                // The committed result is authoritative; discard the
                // unusable connection instead of returning it in tx mode.
                try {
                    connection.close();
                } catch (SQLException ignoredClose) {
                    // Nothing can make the already committed result less true.
                }
            }
            return result;
        } catch (Throwable failure) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                failure.addSuppressed(rollback);
            }
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException restore) {
                failure.addSuppressed(restore);
            }
            if (failure instanceof SQLException sqlFailure) {
                throw sqlFailure;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new SQLException("ledger transaction failed", failure);
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    public interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
