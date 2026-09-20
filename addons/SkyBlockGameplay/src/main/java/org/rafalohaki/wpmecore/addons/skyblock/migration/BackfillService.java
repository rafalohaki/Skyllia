package org.rafalohaki.wpmecore.addons.skyblock.migration;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Backfill existing islands and balances as CLASSIC after dry-run.
 * Reports disabled/locked, missing owners, multiple memberships, accounts without clear profile
 * and provides checksum of balances/assets before and after migration.
 */
public final class BackfillService {

    private final SqlService sql;

    public BackfillService(@NotNull SqlService sql) {
        this.sql = sql;
    }

    /**
     * Dry-run: collects report without mutating.
     */
    public @NotNull CompletableFuture<BackfillReport> dryRun() {
        return sql.withConnection(conn -> {
            long checksumBefore = checksumLegacy(conn);
            List<UUID> missingOwners = findMissingOwners(conn);
            List<UUID> multiple = findMultipleMemberships(conn);
            List<String> noProfile = findAccountsWithoutProfile(conn);
            // disabled/locked islands are reported via profile status or Skyllia; for now we report from island_profiles where status not ACTIVE?
            List<UUID> disabledOrLocked = findDisabledOrLocked(conn);
            return new BackfillReport(
                    checksumBefore, checksumBefore,
                    disabledOrLocked, missingOwners, multiple, noProfile,
                    0, 0, noProfile.size(), true
            );
        });
    }

    /**
     * Execute backfill: create CLASSIC profiles for legacy islands (if any),
     * migrate player:<uuid> balances to profile_accounts where exactly one ACTIVE membership exists.
     * Returns report with checksums.
     */
    public @NotNull CompletableFuture<BackfillReport> backfill(boolean dryRun) {
        if (dryRun) return dryRun();
        return sql.withConnection(conn -> SqlSupport.inTransaction(conn, () -> {
            long before = checksumLegacy(conn);

            // 1. Find all ACTIVE memberships grouped by player
            Map<String, List<String>> membershipsByPlayer = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT player_uuid, island_id FROM wpme_sb_island_membership WHERE status = 'ACTIVE'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        membershipsByPlayer.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                    }
                }
            }

            List<UUID> multiple = new ArrayList<>();
            Map<String, String> uniqueProfileForPlayer = new HashMap<>();
            for (Map.Entry<String, List<String>> e : membershipsByPlayer.entrySet()) {
                if (e.getValue().size() > 1) {
                    multiple.add(UUID.fromString(e.getKey()));
                } else if (e.getValue().size() == 1) {
                    uniqueProfileForPlayer.put(e.getKey(), e.getValue().get(0));
                }
            }

            // 2. Find legacy accounts (player:<uuid>)
            List<Map.Entry<String, Long>> legacyAccounts = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT account_key, balance_minor FROM wpme_sb_accounts WHERE account_key LIKE 'player:%'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        legacyAccounts.add(Map.entry(rs.getString(1), rs.getLong(2)));
                    }
                }
            }

            List<String> noProfile = new ArrayList<>();
            int migrated = 0;
            int islandsBackfilled = 0; // islands already have profile, but we ensure mode CLASSIC

            // Ensure all ACTIVE island_profiles have mode CLASSIC if null/empty? For backfill we set CLASSIC where mode is not set.
            // In our schema default is CLASSIC, so just count islands.
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM wpme_sb_island_profiles")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) islandsBackfilled = rs.getInt(1);
                }
            }

            // Migrate each legacy account where exactly one profile exists
            long sumAmbiguous = 0L;
            for (Map.Entry<String, Long> acc : legacyAccounts) {
                String accountKey = acc.getKey(); // player:<uuid>
                String uuidStr = accountKey.substring("player:".length());
                String profileIdStr = uniqueProfileForPlayer.get(uuidStr);
                if (profileIdStr == null) {
                    noProfile.add(accountKey);
                    sumAmbiguous += acc.getValue();
                    continue;
                }
                UUID profileId = UUID.fromString(profileIdStr);
                UUID playerUuid = UUID.fromString(uuidStr);
                long balance = acc.getValue();

                // Insert into profile_accounts (ignore if already exists)
                boolean inserted = SqlSupport.insertIgnoringConstraint(conn, """
                        INSERT INTO wpme_sb_profile_accounts (profile_id, player_uuid, balance_minor, revision, updated_at)
                        VALUES (?, ?, ?, 0, ?)
                        """, "ON CONFLICT (profile_id, player_uuid) DO NOTHING", stmt -> {
                    stmt.setString(1, profileId.toString());
                    stmt.setString(2, playerUuid.toString());
                    stmt.setLong(3, balance);
                    stmt.setLong(4, System.currentTimeMillis());
                });
                if (inserted) migrated++;

                // Also ensure content assignment default classic-v1
                SqlSupport.insertIgnoringConstraint(conn, """
                        INSERT INTO wpme_sb_content_assignments (profile_id, domain, content_version, assigned_at)
                        VALUES (?, ?, ?, ?)
                        """, "ON CONFLICT (profile_id, domain) DO NOTHING", stmt -> {
                    stmt.setString(1, profileId.toString());
                    stmt.setString(2, "classic");
                    stmt.setString(3, "classic-v1");
                    stmt.setLong(4, System.currentTimeMillis());
                });
            }

            long afterProfile = checksumProfile(conn);
            // Checksum validation: profile sum should equal legacy sum minus ambiguous (quarantined)
            long expectedAfter = before - sumAmbiguous;
            boolean matches = expectedAfter == afterProfile;
            List<UUID> missingOwners = findMissingOwners(conn);
            List<UUID> disabledOrLocked = findDisabledOrLocked(conn);

            return new BackfillReport(
                    before, afterProfile,
                    disabledOrLocked, missingOwners, multiple, noProfile,
                    islandsBackfilled, migrated, noProfile.size(), matches
            );
        }));
    }

    private static String dialectConflictClause(Connection conn) throws SQLException {
        String product = conn.getMetaData().getDatabaseProductName();
        if (product != null && product.toLowerCase(java.util.Locale.ROOT).contains("mysql")) {
            return "ON DUPLICATE KEY UPDATE profile_id=VALUES(profile_id)";
        }
        return "ON CONFLICT (profile_id, player_uuid) DO NOTHING";
    }

    private static long checksumLegacy(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(balance_minor),0) FROM wpme_sb_accounts WHERE account_key LIKE 'player:%'")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static long checksumProfile(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(SUM(balance_minor),0) FROM wpme_sb_profile_accounts")) {
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static List<UUID> findMissingOwners(Connection conn) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT island_id, owner_uuid FROM wpme_sb_island_profiles")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String owner = rs.getString(2);
                    if (owner == null || owner.isBlank()) {
                        out.add(UUID.fromString(rs.getString(1)));
                    } else {
                        // Also check if owner has active membership for that island
                        // If owner not in membership as OWNER, it's suspicious but not necessarily missing
                        // For report we treat blank owners only; real missing owner would be orphaned profile where owner not found in membership
                    }
                }
            }
        }
        // Also find islands where owner membership missing
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.island_id FROM wpme_sb_island_profiles p
                LEFT JOIN wpme_sb_island_membership m ON m.island_id = p.island_id AND m.player_uuid = p.owner_uuid AND m.status = 'ACTIVE'
                WHERE m.player_uuid IS NULL
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString(1));
                    if (!out.contains(id)) out.add(id);
                }
            }
        }
        return out;
    }

    private static List<UUID> findMultipleMemberships(Connection conn) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT player_uuid FROM wpme_sb_island_membership WHERE status = 'ACTIVE' GROUP BY player_uuid HAVING COUNT(*) > 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(UUID.fromString(rs.getString(1)));
            }
        }
        return out;
    }

    private static List<String> findAccountsWithoutProfile(Connection conn) throws SQLException {
        List<String> out = new ArrayList<>();
        // Find player accounts where player has 0 or >1 active memberships
        Map<String, Integer> counts = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT player_uuid, COUNT(*) as c FROM wpme_sb_island_membership WHERE status = 'ACTIVE' GROUP BY player_uuid")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) counts.put(rs.getString(1), rs.getInt(2));
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT account_key FROM wpme_sb_accounts WHERE account_key LIKE 'player:%'")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString(1);
                    String uuidStr = key.substring("player:".length());
                    Integer c = counts.get(uuidStr);
                    if (c == null || c != 1) out.add(key);
                }
            }
        }
        return out;
    }

    private static List<UUID> findDisabledOrLocked(Connection conn) throws SQLException {
        List<UUID> out = new ArrayList<>();
        // island_profiles where status = DELETED, or mode indicates disabled? For now check status not ACTIVE
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT island_id FROM wpme_sb_island_profiles WHERE status != 'ACTIVE'")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(UUID.fromString(rs.getString(1)));
            }
        }
        return out;
    }
}
