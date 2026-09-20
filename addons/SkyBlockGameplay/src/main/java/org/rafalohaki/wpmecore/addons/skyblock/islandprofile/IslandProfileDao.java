package org.rafalohaki.wpmecore.addons.skyblock.islandprofile;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * DAO for wpme_sb_island_profiles and wpme_sb_island_membership.
 * Enforces exactly one ACTIVE membership per UUID via partial unique index
 * (SQLite/PostgreSQL) and application guard for MySQL/H2.
 */
public final class IslandProfileDao {

    public static final String PROFILES_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_island_profiles (
                island_id VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                mode VARCHAR(16) NOT NULL DEFAULT 'CLASSIC',
                status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """;

    public static final String MEMBERSHIP_DDL = """
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

    public static final String ACTIVE_MEMBERSHIP_IDX = """
            CREATE UNIQUE INDEX IF NOT EXISTS ux_wpme_sb_active_membership
                ON wpme_sb_island_membership (player_uuid)
                WHERE status = 'ACTIVE'
            """;

    // Fallback for MySQL/H2 where partial index not supported: a helper table with PK player_uuid
    public static final String ACTIVE_MEMBERSHIP_HELPER_DDL = """
            CREATE TABLE IF NOT EXISTS wpme_sb_active_membership (
                player_uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                island_id VARCHAR(36) NOT NULL,
                updated_at BIGINT NOT NULL
            )
            """;

    private final SqlService sql;

    public IslandProfileDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    public @NotNull CompletableFuture<Optional<IslandProfile>> findProfile(@NotNull UUID islandId) {
        return sql.withConnection(connection -> {
            String sqlStr = "SELECT island_id, owner_uuid, mode, status, created_at, updated_at FROM wpme_sb_island_profiles WHERE island_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sqlStr)) {
                ps.setString(1, islandId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapProfile(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    public @NotNull CompletableFuture<Optional<IslandMembership>> findActiveMembership(@NotNull UUID playerUuid) {
        return sql.withConnection(connection -> {
            String sqlStr = "SELECT island_id, player_uuid, role, status, joined_at, updated_at FROM wpme_sb_island_membership WHERE player_uuid = ? AND status = 'ACTIVE' LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(sqlStr)) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(mapMembership(rs));
                    }
                    return Optional.empty();
                }
            }
        });
    }

    /**
     * Attempt to create a profile + owner membership atomically.
     * Returns true if inserted, false if player already has an ACTIVE membership
     * or island already exists.
     * Uses one JDBC transaction with Pg advisory lock when on PostgreSQL,
     * otherwise relies on per-UUID serialization in the service + DB constraint.
     */
    public @NotNull CompletableFuture<Boolean> tryCreateProfile(
            @NotNull UUID islandId, @NotNull UUID ownerUuid, @NotNull String mode) {
        return sql.withConnection(connection -> {
            boolean pg = isPostgres(connection);
            if (pg) {
                try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(1, pidFor(ownerUuid));
                    lock.executeQuery().close();
                }
            }
            return inTx(connection, () -> {
                // Check active membership already exists
                try (PreparedStatement check = connection.prepareStatement(
                        "SELECT 1 FROM wpme_sb_island_membership WHERE player_uuid = ? AND status = 'ACTIVE' LIMIT 1")) {
                    check.setString(1, ownerUuid.toString());
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            return false;
                        }
                    }
                }
                long now = System.currentTimeMillis();
                // Insert profile. Skyllia derives island_id from the owner UUID, so a
                // recreated island reuses the SAME island_id as the deleted one and the
                // retained DELETED row violates the PK. On that collision reactivate the
                // row instead of failing — the one-ACTIVE-per-player guard above already
                // ran, so reactivation cannot resurrect a foreign or concurrent island.
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO wpme_sb_island_profiles (island_id, owner_uuid, mode, status, created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)")) {
                    ps.setString(1, islandId.toString());
                    ps.setString(2, ownerUuid.toString());
                    ps.setString(3, mode);
                    ps.setLong(4, now);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (isConstraint(e)) {
                        try (PreparedStatement reactivate = connection.prepareStatement(
                                "UPDATE wpme_sb_island_profiles SET owner_uuid = ?, mode = ?, status = 'ACTIVE', updated_at = ? "
                                        + "WHERE island_id = ? AND status = 'DELETED' AND owner_uuid = ?")) {
                            reactivate.setString(1, ownerUuid.toString());
                            reactivate.setString(2, mode);
                            reactivate.setLong(3, now);
                            reactivate.setString(4, islandId.toString());
                            reactivate.setString(5, ownerUuid.toString());
                            if (reactivate.executeUpdate() != 1) {
                                return false;
                            }
                        }
                    } else {
                        throw e;
                    }
                }
                // Insert membership (same reactivation rule for the LEFT row)
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO wpme_sb_island_membership (island_id, player_uuid, role, status, joined_at, updated_at) VALUES (?, ?, 'OWNER', 'ACTIVE', ?, ?)")) {
                    ps.setString(1, islandId.toString());
                    ps.setString(2, ownerUuid.toString());
                    ps.setLong(3, now);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (isConstraint(e)) {
                        try (PreparedStatement reactivate = connection.prepareStatement(
                                "UPDATE wpme_sb_island_membership SET role = 'OWNER', status = 'ACTIVE', joined_at = ?, updated_at = ? "
                                        + "WHERE island_id = ? AND player_uuid = ? AND status = 'LEFT'")) {
                            reactivate.setLong(1, now);
                            reactivate.setLong(2, now);
                            reactivate.setString(3, islandId.toString());
                            reactivate.setString(4, ownerUuid.toString());
                            if (reactivate.executeUpdate() != 1) {
                                return false;
                            }
                        }
                    } else {
                        throw e;
                    }
                }
                // MySQL helper table for active membership uniqueness
                if (isMysql(connection)) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO wpme_sb_active_membership (player_uuid, island_id, updated_at) VALUES (?, ?, ?)")) {
                        ps.setString(1, ownerUuid.toString());
                        ps.setString(2, islandId.toString());
                        ps.setLong(3, now);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        if (isConstraint(e)) return false;
                        throw e;
                    }
                }
                return true;
            });
        });
    }

    public @NotNull CompletableFuture<Boolean> tryJoin(
            @NotNull UUID islandId, @NotNull UUID playerUuid, @NotNull String role) {
        return sql.withConnection(connection -> {
            boolean pg = isPostgres(connection);
            if (pg) {
                try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(1, pidFor(playerUuid));
                    lock.executeQuery().close();
                }
            }
            return inTx(connection, () -> {
                // Must not already have ACTIVE
                try (PreparedStatement check = connection.prepareStatement(
                        "SELECT 1 FROM wpme_sb_island_membership WHERE player_uuid = ? AND status = 'ACTIVE' LIMIT 1")) {
                    check.setString(1, playerUuid.toString());
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) return false;
                    }
                }
                // Island must exist and be ACTIVE
                try (PreparedStatement check = connection.prepareStatement(
                        "SELECT 1 FROM wpme_sb_island_profiles WHERE island_id = ? AND status = 'ACTIVE' LIMIT 1")) {
                    check.setString(1, islandId.toString());
                    try (ResultSet rs = check.executeQuery()) {
                        if (!rs.next()) return false;
                    }
                }
                long now = System.currentTimeMillis();
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO wpme_sb_island_membership (island_id, player_uuid, role, status, joined_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)")) {
                    ps.setString(1, islandId.toString());
                    ps.setString(2, playerUuid.toString());
                    ps.setString(3, role);
                    ps.setLong(4, now);
                    ps.setLong(5, now);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (isConstraint(e)) return false;
                    throw e;
                }
                if (isMysql(connection)) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO wpme_sb_active_membership (player_uuid, island_id, updated_at) VALUES (?, ?, ?)")) {
                        ps.setString(1, playerUuid.toString());
                        ps.setString(2, islandId.toString());
                        ps.setLong(3, now);
                        ps.executeUpdate();
                    } catch (SQLException e) {
                        if (isConstraint(e)) return false;
                        throw e;
                    }
                }
                return true;
            });
        });
    }

    public @NotNull CompletableFuture<Boolean> deactivateMembership(@NotNull UUID islandId, @NotNull UUID playerUuid) {
        return sql.withConnection(connection -> {
            boolean pg = isPostgres(connection);
            if (pg) {
                try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(1, pidFor(playerUuid));
                    lock.executeQuery().close();
                }
            }
            return inTx(connection, () -> {
                int updated;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE wpme_sb_island_membership SET status = 'LEFT', updated_at = ? WHERE island_id = ? AND player_uuid = ? AND status = 'ACTIVE'")) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.setString(2, islandId.toString());
                    ps.setString(3, playerUuid.toString());
                    updated = ps.executeUpdate();
                }
                if (isMysql(connection) && updated > 0) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "DELETE FROM wpme_sb_active_membership WHERE player_uuid = ? AND island_id = ?")) {
                        ps.setString(1, playerUuid.toString());
                        ps.setString(2, islandId.toString());
                        ps.executeUpdate();
                    }
                }
                return updated > 0;
            });
        });
    }

    /**
     * M1-A: projekcja ownera po transferze — podmienia owner_uuid profilu
     * i degraduje rolę starego właściciela do MEMBER. Zwraca true gdy UPDATE
     * zmienił wiersz.
     */
    public @NotNull CompletableFuture<Boolean> refreshOwnerProjection(
            @NotNull UUID islandId, @NotNull UUID newOwnerUuid) {
        return sql.withConnection(connection -> {
            boolean pg = isPostgres(connection);
            if (pg) {
                try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(1, pidFor(newOwnerUuid));
                    lock.executeQuery().close();
                }
            }
            return inTx(connection, () -> {
                long now = System.currentTimeMillis();
                int updated;
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE wpme_sb_island_profiles SET owner_uuid = ?, updated_at = ? WHERE island_id = ? AND status = 'ACTIVE'")) {
                    ps.setString(1, newOwnerUuid.toString());
                    ps.setLong(2, now);
                    ps.setString(3, islandId.toString());
                    updated = ps.executeUpdate();
                }
                // stary OWNER (jeśli jest członkiem) → MEMBER; nowy → OWNER
                try (PreparedStatement demote = connection.prepareStatement(
                        "UPDATE wpme_sb_island_membership SET role = 'MEMBER', updated_at = ? WHERE island_id = ? AND role = 'OWNER' AND player_uuid <> ?")) {
                    demote.setLong(1, now);
                    demote.setString(2, islandId.toString());
                    demote.setString(3, newOwnerUuid.toString());
                    demote.executeUpdate();
                }
                try (PreparedStatement promote = connection.prepareStatement(
                        "UPDATE wpme_sb_island_membership SET role = 'OWNER', updated_at = ? WHERE island_id = ? AND player_uuid = ?")) {
                    promote.setLong(1, now);
                    promote.setString(2, islandId.toString());
                    promote.setString(3, newOwnerUuid.toString());
                    promote.executeUpdate();
                }
                return updated > 0;
            });
        });
    }

    public @NotNull CompletableFuture<Boolean> deleteProfile(@NotNull UUID islandId) {
        return sql.withConnection(connection -> {
            return inTx(connection, () -> {
                // Mark profile deleted and deactivate all memberships
                long now = System.currentTimeMillis();
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE wpme_sb_island_profiles SET status = 'DELETED', updated_at = ? WHERE island_id = ? AND status = 'ACTIVE'")) {
                    ps.setLong(1, now);
                    ps.setString(2, islandId.toString());
                    if (ps.executeUpdate() == 0) return false;
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "UPDATE wpme_sb_island_membership SET status = 'LEFT', updated_at = ? WHERE island_id = ? AND status = 'ACTIVE'")) {
                    ps.setLong(1, now);
                    ps.setString(2, islandId.toString());
                    ps.executeUpdate();
                }
                if (isMysql(connection)) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "DELETE FROM wpme_sb_active_membership WHERE island_id = ?")) {
                        ps.setString(1, islandId.toString());
                        ps.executeUpdate();
                    }
                }
                return true;
            });
        });
    }

    private static IslandProfile mapProfile(ResultSet rs) throws SQLException {
        return new IslandProfile(
                UUID.fromString(rs.getString(1)),
                UUID.fromString(rs.getString(2)),
                rs.getString(3),
                IslandProfile.ProfileStatus.parse(rs.getString(4)),
                rs.getLong(5),
                rs.getLong(6));
    }

    private static IslandMembership mapMembership(ResultSet rs) throws SQLException {
        return new IslandMembership(
                UUID.fromString(rs.getString(1)),
                UUID.fromString(rs.getString(2)),
                rs.getString(3),
                IslandMembership.MembershipStatus.parse(rs.getString(4)),
                rs.getLong(5),
                rs.getLong(6));
    }

    private static boolean isPostgres(Connection c) throws SQLException {
        String p = c.getMetaData().getDatabaseProductName();
        return p != null && p.toLowerCase(Locale.ROOT).contains("postgresql");
    }

    private static boolean isMysql(Connection c) throws SQLException {
        String p = c.getMetaData().getDatabaseProductName();
        if (p == null) return false;
        String l = p.toLowerCase(Locale.ROOT);
        return l.contains("mysql") || l.contains("mariadb") || l.contains("h2");
        // H2 runs in MariaDB mode but has no partial index support, use helper table as well
    }

    private static boolean isConstraint(SQLException e) {
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) return true;
        int code = e.getErrorCode();
        return code == 19 || code == 1062 || code == 23505;
    }

    private static long pidFor(UUID uuid) {
        // Deterministic 64-bit advisory lock id from UUID (xor halves)
        return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
    }

    private static <T> T inTx(Connection c, SqlWork<T> work) throws SQLException {
        boolean ac = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T r = work.run();
            c.commit();
            try { c.setAutoCommit(ac); } catch (SQLException e) { try { c.close(); } catch (SQLException ignore) {} }
            return r;
        } catch (Throwable t) {
            try { c.rollback(); } catch (SQLException ignore) {}
            try { c.setAutoCommit(ac); } catch (SQLException ignore) {}
            if (t instanceof SQLException s) throw s;
            if (t instanceof RuntimeException re) throw re;
            throw new SQLException(t);
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> { T run() throws Exception; }
}
