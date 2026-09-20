package org.rafalohaki.wpmecore.addons.skyblock.content;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Content version assignments per profile and domain (e.g. campaign, loot, sector).
 * Foundation for content migration without touching player data.
 */
public final class ContentAssignmentDao {

    private final SqlService sql;

    public ContentAssignmentDao(@NotNull SqlService sql) {
        this.sql = sql;
    }

    public @NotNull CompletableFuture<Boolean> assign(
            @NotNull UUID profileId, @NotNull String domain, @NotNull String contentVersion) {
        return sql.withConnection(conn -> {
            // Portable upsert: try insert, else update
            try (var ps = conn.prepareStatement(
                    "INSERT INTO wpme_sb_content_assignments (profile_id, domain, content_version, assigned_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, profileId.toString());
                ps.setString(2, domain);
                ps.setString(3, contentVersion);
                ps.setLong(4, System.currentTimeMillis());
                try {
                    ps.executeUpdate();
                    return true;
                } catch (java.sql.SQLException e) {
                    // Constraint -> update
                    String state = e.getSQLState();
                    if (state != null && state.startsWith("23") || e.getErrorCode() == 19 || e.getErrorCode() == 1062) {
                        try (var upd = conn.prepareStatement(
                                "UPDATE wpme_sb_content_assignments SET content_version = ?, assigned_at = ? WHERE profile_id = ? AND domain = ?")) {
                            upd.setString(1, contentVersion);
                            upd.setLong(2, System.currentTimeMillis());
                            upd.setString(3, profileId.toString());
                            upd.setString(4, domain);
                            return upd.executeUpdate() == 1;
                        }
                    }
                    throw e;
                }
            }
        });
    }

    public @NotNull CompletableFuture<Optional<String>> findVersion(@NotNull UUID profileId, @NotNull String domain) {
        return sql.queryOne(
                "SELECT content_version FROM wpme_sb_content_assignments WHERE profile_id = ? AND domain = ?",
                rs -> rs.getString(1),
                profileId.toString(), domain);
    }
}
