package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SKY-2: Skyllia 3.0-163 soft-delete (disable=1) zostawia wiersz islands, a
 * PK = UUID właściciela, więc ponowne createIsland pada na UNIQUE. Purger
 * czyści SOFT-DELETED wiersze przed create; AKTYWNE wyspy są nietykalne.
 * Test na prawdziwym pliku sqlite (jdbc:sqlite z classpath testowego).
 */
class SkylliaSoftDeletePurgeTest {

    @TempDir
    Path tmp;

    private Path skylliaDb() throws Exception {
        Path db = tmp.resolve("skyllia.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE islands (island_id TEXT PRIMARY KEY, disable INTEGER NOT NULL DEFAULT 0, region_x INTEGER, region_z INTEGER)");
            st.executeUpdate("CREATE TABLE members_in_islands (island_id TEXT, uuid_player TEXT, player_name TEXT)");
            st.executeUpdate("CREATE TABLE islands_warp (island_id TEXT, warp_name TEXT)");
            st.executeUpdate("CREATE TABLE islands_flags (island_id TEXT, world_name TEXT)");
            st.executeUpdate("CREATE TABLE islands_permissions_v2 (island_id TEXT, role TEXT)");
            st.executeUpdate("CREATE TABLE island_center_locations (island_id TEXT, world_name TEXT)");
            st.executeUpdate("CREATE TABLE islands_build_height (island_id TEXT, world_name TEXT)");
            st.executeUpdate("CREATE TABLE island_custom_data_skylliaextra_data (island_id TEXT, data_key TEXT)");
        }
        return db;
    }

    private int count(Path db, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void purgesSoftDeletedIslandForPlayer() throws Exception {
        Path db = skylliaDb();
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO islands VALUES ('" + me + "', 1, 0, -10)");            // soft-deleted ja
            st.executeUpdate("INSERT INTO islands VALUES ('" + other + "', 0, 10, -10)");        // aktywna cudza
            st.executeUpdate("INSERT INTO members_in_islands VALUES ('" + me + "', '" + me + "', 'ja')");
            st.executeUpdate("INSERT INTO members_in_islands VALUES ('" + other + "', '" + other + "', 'ktos')");
            st.executeUpdate("INSERT INTO islands_warp VALUES ('" + me + "', 'home')");
        }

        int removed = SkylliaIntegrationImpl.purgeSoftDeletedIsland(db, me, java.util.logging.Logger.getLogger("test"));

        assertEquals(1, removed, "usunięty dokładnie soft-deleted wiersz islands");
        assertEquals(0, count(db, "SELECT COUNT(*) FROM islands WHERE island_id='" + me + "'"), "mój wiersz zniknął");
        assertEquals(0, count(db, "SELECT COUNT(*) FROM members_in_islands WHERE island_id='" + me + "'"), "members wyczyszczone");
        assertEquals(0, count(db, "SELECT COUNT(*) FROM islands_warp WHERE island_id='" + me + "'"), "warp wyczyszczone");
        assertEquals(1, count(db, "SELECT COUNT(*) FROM islands WHERE island_id='" + other + "'"), "cudza aktywna wyspa NIETYKALNA");
        assertEquals(1, count(db, "SELECT COUNT(*) FROM members_in_islands WHERE player_name='ktos'"), "cudzy members nietknięty");
    }

    @Test
    void leavesActiveIslandAlone() throws Exception {
        Path db = skylliaDb();
        UUID me = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO islands VALUES ('" + me + "', 0, 0, -10)");            // aktywna moja
            st.executeUpdate("INSERT INTO members_in_islands VALUES ('" + me + "', '" + me + "', 'ja')");
        }

        int removed = SkylliaIntegrationImpl.purgeSoftDeletedIsland(db, me, java.util.logging.Logger.getLogger("test"));

        assertEquals(0, removed, "aktywna wyspa — nic do czyszczenia");
        assertEquals(1, count(db, "SELECT COUNT(*) FROM islands"), "aktywna zostaje");
        assertEquals(1, count(db, "SELECT COUNT(*) FROM members_in_islands"), "members zostaje");
    }

    @Test
    void noRowIsNoop() throws Exception {
        Path db = skylliaDb();
        assertEquals(0, SkylliaIntegrationImpl.purgeSoftDeletedIsland(db, UUID.randomUUID(), java.util.logging.Logger.getLogger("test")));
    }
}
