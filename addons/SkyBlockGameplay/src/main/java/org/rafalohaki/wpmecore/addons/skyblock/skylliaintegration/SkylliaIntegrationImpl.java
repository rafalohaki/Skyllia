package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.identity.IdentityService;
import org.rafalohaki.wpmecore.api.service.CacheService;

/**
 * Typowana implementacja {@link SkylliaIntegration} na publicznym API Skyllii.
 * Kompiluje się wyłącznie do artefaktu {@code fr.euphyllia.skyllia:api}: statyczna
 * fasada {@link SkylliaAPI} + typy {@code api.skyblock.*}. Zero klas wewnętrznych.
 *
 * <p>Czytania wyspy idą przez {@link SkylliaAPI} (autorytatywne) z własnym krótkim
 * TTL cache ({@link IslandViewCache}), by UI/menu nie uderzało w bazę przy każdym
 * żądaniu. Geometria regionu replikowana bez {@code Island#isInside} (tańsza niż
 * pełna weryfikacja Skyllii). Dwuetapowa weryfikacja wypłaty: cache do UI,
 * autorytatywna przed commitem.
 */
final class SkylliaIntegrationImpl implements SkylliaIntegration {

    private final JavaPlugin owner;
    private final IslandCapabilities capabilities;
    private final IslandViewCache cache;
    private volatile @Nullable IdentityService identityService;

    SkylliaIntegrationImpl(JavaPlugin owner, IslandCapabilities capabilities,
                           CacheService cacheService) {
        this.owner = owner;
        this.capabilities = capabilities;
        this.cache = new IslandViewCache(cacheService);
    }

    @Override
    public void setIdentityService(@Nullable IdentityService identityService) {
        this.identityService = identityService;
    }

    // ---------------- cache-backed reads ----------------

    @Override
    public Optional<IslandView> islandOf(UUID player) {
        Island island = loadIsland(player);
        return island == null ? Optional.empty() : viewOf(island, player);
    }

    @Override
    public Optional<IslandView> islandAt(UUID player, Location location) {
        Island island = loadIsland(player);
        if (island == null || !insideCachedGeometry(island, location)) {
            return Optional.empty();
        }
        return viewOf(island, player);
    }

    @Override
    public Optional<UUID> cachedIslandIdOf(UUID player) {
        Island island = loadIsland(player);
        return island == null ? Optional.empty() : Optional.of(island.getId());
    }

    /** Wyspa gracza z TTL cache; przy braku — autorytatywne SkylliaAPI + wpis do cache. */
    private Island loadIsland(UUID player) {
        return cache.resolve(player, SkylliaAPI::getIslandByPlayerId).orElse(null);
    }

    /** Buduje per-player widok; rola UNKNOWN gdy gracz nie jest (jeszcze) członkiem. */
    private Optional<IslandView> viewOf(Island island, UUID player) {
        Players owner = island.getOwner();
        UUID ownerId = owner != null ? owner.getMojangId() : null;
        Players member = island.getMember(player);
        IslandRole role = member != null
                ? mapRole(member.getRoleType().name())
                : IslandRole.UNKNOWN;
        IslandSnapshot snapshot = new IslandSnapshot(
                island.getId(),
                ownerId,
                LifecycleState.active(),
                IslandBounds.of(island.getRegionCoordinate().x(),
                        island.getRegionCoordinate().z(),
                        island.getSize()),
                capabilities,
                0L);
        return Optional.of(new IslandView(snapshot, role));
    }

    /** Replikuje geometrię regionu bez pełnego {@code Island#isInside}. */
    private boolean insideCachedGeometry(Island island, Location location) {
        World world = location.getWorld();
        if (world == null || !Boolean.TRUE.equals(SkylliaAPI.isWorldSkyblock(world.getName()))) {
            return false;
        }
        var coord = island.getRegionCoordinate();
        return insideRegionBounds(coord.x(), coord.z(), island.getSize(),
                location.getBlockX(), location.getBlockZ());
    }

    static boolean insideRegionBounds(int regionX, int regionZ, double size, int blockX, int blockZ) {
        if (!Double.isFinite(size) || size <= 0.0D) {
            return false;
        }
        double halfSize = (size - 1.0D) / 2.0D;
        int centerX = (regionX << 9) + 256;
        int centerZ = (regionZ << 9) + 256;
        int minX = (int) Math.floor(centerX - halfSize);
        int maxX = (int) Math.floor(centerX + halfSize);
        int minZ = (int) Math.floor(centerZ - halfSize);
        int maxZ = (int) Math.floor(centerZ + halfSize);
        return blockX >= minX && blockX <= maxX && blockZ >= minZ && blockZ <= maxZ;
    }

    static IslandRole mapRole(String name) {
        return switch (name) {
            case "OWNER" -> IslandRole.OWNER;
            case "CO_OWNER" -> IslandRole.CO_OWNER;
            case "MODERATOR" -> IslandRole.MODERATOR;
            case "MEMBER" -> IslandRole.MEMBER;
            case "VISITOR" -> IslandRole.VISITOR;
            case "BAN", "BANNED" -> IslandRole.BAN;
            default -> IslandRole.UNKNOWN;
        };
    }


    /**
     * SKYBLOCK-2-6: dawniej tylko Bukkit.isGlobalTickThread() — regionowe wątki
     * tickowe (większość kodu gracza na Folii/Canvas) przechodziły strażnika i
     * wykonywały blokujący odczyt bazy na wątku tickującym. API Folia 26.2 nie
     * eksponuje TickThread.isTickThread(), więc wykrycie jest dwuwarstwowe:
     * wątek globalny zawsze, a regionowy przez własność regionu gracza
     * (wzorzec SkyBlockVaultProvider.mutationOnTickThread).
     */
    private static boolean onAnyTickThread() {
        return Bukkit.isGlobalTickThread();
    }

    /** Strażnik dla lookupów z UUID gracza: globalny tick-thread lub jego region. */
    private static boolean onPlayerRegionTickThread(@NotNull UUID player) {
        if (Bukkit.isGlobalTickThread()) {
            return true;
        }
        org.bukkit.entity.Player online = Bukkit.getPlayer(player);
        return online != null && Bukkit.isOwnedByCurrentRegion(online);
    }
    // ---------------- AUTHORITATIVE_ASYNC ----------------

    @Override
    public Optional<IslandOwner> ownerOf(UUID islandId) {
        if (onAnyTickThread()) {
            throw new IllegalStateException("authoritative Skyllia lookup on a tick thread");
        }
        try {
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            if (island == null || island.isDisable()) {
                return Optional.empty();
            }
            Players ownerMember = island.getOwner();
            if (ownerMember == null || ownerMember.getMojangId() == null) {
                return Optional.empty();
            }
            String name = ownerMember.getLastKnowName();
            return Optional.of(new IslandOwner(ownerMember.getMojangId(),
                    name == null || name.isBlank() ? "?" : name));
        } catch (RuntimeException failure) {
            owner.getLogger().warning("Skyllia owner lookup failed for island "
                    + islandId + ": " + failure);
            return Optional.empty();
        }
    }

    @Override
    public boolean islandExists(UUID islandId) {
        if (onAnyTickThread()) {
            throw new IllegalStateException("authoritative Skyllia lookup on a tick thread");
        }
        try {
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            return island != null && !island.isDisable();
        } catch (RuntimeException failure) {
            // Fail-closed: false oznacza „kasuj profil wyspy”, więc niepewność = istnieje.
            owner.getLogger().warning("Skyllia island-exists lookup failed for " + islandId
                    + ": " + failure + " — zakladam, ze wyspa istnieje");
            return true;
        }
    }

    @Override
    public boolean authoritativeCanWithdraw(UUID player, UUID islandId) {
        return authoritativeRole(player, islandId) == IslandRole.OWNER
                || authoritativeRole(player, islandId) == IslandRole.CO_OWNER;
    }

    @Override
    public IslandRole authoritativeRole(UUID player, UUID islandId) {
        if (onPlayerRegionTickThread(player)) {
            throw new IllegalStateException("authoritative Skyllia lookup on a tick thread");
        }
        IdentityService identity = this.identityService;
        if (identity != null) {
            try {
                Boolean conflicted = identity.isConflicted(player)
                        .orTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                        .exceptionally(ex -> Boolean.TRUE).join();
                if (Boolean.TRUE.equals(conflicted)) {
                    owner.getLogger().warning("Blocked island operation for conflicted identity " + player);
                    return IslandRole.UNKNOWN;
                }
            } catch (Exception e) {
                owner.getLogger().warning("Identity check failed for " + player + ", blocking operation: " + e);
                return IslandRole.UNKNOWN;
            }
        }
        try {
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            if (island == null || !islandId.equals(island.getId()) || island.isDisable()) {
                return IslandRole.UNKNOWN;
            }
            Players member = island.getMember(player);
            if (member == null || !islandId.equals(member.getIslandId())) {
                return IslandRole.UNKNOWN;
            }
            return mapRole(member.getRoleType().name());
        } catch (RuntimeException failure) {
            owner.getLogger().warning("Skyllia authoritative role lookup failed for "
                    + player + " on " + islandId + ": " + failure);
            return IslandRole.UNKNOWN;
        }
    }


    /**
     * SKY-2: Skyllia 3.0-163 po /island delete robi SOFT-delete (UPDATE islands
     * SET disable=1 — wiersz zostaje), a islands.island_id = UUID właściciela.
     * Ponowne createIsland tego samego gracza kończy się SQLITE_CONSTRAINT_UNIQUE
     * na PK — gracz nigdy więcej nie założy wyspy. Hard-delete w Skylli nie
     * istnieje (ForceDelete robi ten sam soft-delete), więc przed create
     * czyścimy soft-deleted wiersze bezpośrednio w sqlite Skylli (osobne,
     * krótkie połączenie; aktywne wyspy disable=0 są nietykalne).
     *
     * @return liczba usuniętych wierszy islands (0 gdy nie było co czyścić)
     */
    /**
     * SKY-2 (produkcja 2026-08-28): lista tabel w `tables` zawiera wpisy, których
     * może nie być w schemacie tej instalacji Skyllii (u nas brak
     * {@code island_custom_data_skylliaextra_data}) — SQLException na jednej
     * tabeli wywalała cały purge (-1), a openPicker przerywał wtedy tworzenie
     * z „Nie udało się wyczyścić poprzedniej wyspy” — gracz nigdy nie dochodził
     * do pickera. Tabele nieistniejące po prostu pomijamy.
     */
    private static void deleteIgnoringMissingTable(@NotNull java.sql.Connection c, @NotNull String table,
                                                   @NotNull String islandId) throws java.sql.SQLException {
        try (java.sql.PreparedStatement exists = c.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            exists.setString(1, table);
            try (java.sql.ResultSet rs = exists.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
            }
        }
        try (java.sql.PreparedStatement del = c.prepareStatement("DELETE FROM " + table + " WHERE island_id = ?")) {
            del.setString(1, islandId);
            del.executeUpdate();
        }
    }

    static int purgeSoftDeletedIsland(@NotNull java.nio.file.Path skylliaDb, @NotNull UUID playerId,
                                   @NotNull java.util.logging.Logger logger) {
        String id = playerId.toString();
        String[] tables = {"islands_warp", "islands_flags", "islands_permissions_v2",
                "island_center_locations", "islands_build_height",
                "island_custom_data_skylliaextra_data", "members_in_islands"};
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + skylliaDb + "?busy_timeout=10000&transaction_mode=IMMEDIATE")) {
            // Tani gate-read w auto-commit: najczęstsza ścieżka (brak soft-delete)
            // nie podnosi w ogóle blokady zapisu (commit() w tym driverze od razu
            // wystawia BEGIN IMMEDIATE ponownie — xerial 3.47).
            boolean softDeleted;
            try (java.sql.PreparedStatement check = c.prepareStatement(
                    "SELECT disable FROM islands WHERE island_id = ?")) {
                check.setString(1, id);
                try (java.sql.ResultSet rs = check.executeQuery()) {
                    softDeleted = rs.next() && rs.getInt(1) != 0;
                }
            }
            if (!softDeleted) {
                return 0;
            }
            c.setAutoCommit(false);
            try {
                for (String table : tables) {
                    deleteIgnoringMissingTable(c, table, id);
                }
                int removed;
                try (java.sql.PreparedStatement del = c.prepareStatement(
                        "DELETE FROM islands WHERE island_id = ? AND disable = 1")) {
                    del.setString(1, id);
                    removed = del.executeUpdate();
                }
                c.commit();
                return removed;
            } catch (java.sql.SQLException e) {
                try { c.rollback(); } catch (java.sql.SQLException suppressed) { e.addSuppressed(suppressed); }
                throw e;
            }
        } catch (java.sql.SQLException | RuntimeException e) {
            logger.log(java.util.logging.Level.WARNING,
                    "SKY-2: purge soft-deleted wyspy " + playerId + " nie powiodl sie: " + e, e);
            return -1;
        }
    }

    private @NotNull java.nio.file.Path skylliaDatabase() {
        org.bukkit.plugin.Plugin skyllia = org.bukkit.Bukkit.getPluginManager().getPlugin("Skyllia");
        if (skyllia != null) {
            return skyllia.getDataFolder().toPath().resolve("skyllia.db");
        }
        return java.nio.file.Path.of("plugins", "Skyllia", "skyllia.db");
    }

    @Override
    public int purgeSoftDeletedIsland(UUID playerId) {
        // SQLITE_BUSY (np. dłuższa transakcja Skyllii) blokuje create gracza —
        // trzy krótkie próby zamiast pojedynczego strzału.
        java.sql.SQLException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            int purged = purgeSoftDeletedIsland(skylliaDatabase(), playerId, owner.getLogger());
            if (purged != -1) {
                if (purged > 0) {
                    owner.getLogger().info("SKY-2: wyczyszczono soft-deleted wyspę " + playerId + " przed create");
                }
                return purged;
            }
            last = new java.sql.SQLException("purge attempt " + attempt + " failed");
            try {
                Thread.sleep(80L * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        owner.getLogger().warning("SKY-2: purge soft-deleted wyspy " + playerId + " nie powiódł się po 3 próbach");
        return -1;
    }

    @Override
    public boolean createIsland(UUID playerId, String template, String playerName) {
        // Called off tick thread via coordinator; uses SkylliaAPI directly (no recursive dispatch)
        try {
            // Skyllia templates: classic uses "starter", oneblock uses "oneblock"
            String name = template;
            // Map expedition placeholder to starter if not existent
            fr.euphyllia.skyllia.api.skyblock.model.IslandSettings settings = new fr.euphyllia.skyllia.api.skyblock.model.IslandSettings(name, 5, 100.0);
            fr.euphyllia.skyllia.api.skyblock.Players p = new fr.euphyllia.skyllia.api.skyblock.Players(playerId, playerName, null, fr.euphyllia.skyllia.api.skyblock.model.RoleType.OWNER);
            Boolean result = SkylliaAPI.createIsland(playerId, settings, p);
            if (Boolean.TRUE.equals(result)) {
                cache.invalidate(playerId);
            }
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            owner.getLogger().warning("createIsland failed for " + playerId + " template=" + template + ": " + e);
            return false;
        }
    }

    @Override
    public boolean setupIslandHome(UUID playerId, Location surfaceAnchor) {
        try {
            Island island = SkylliaAPI.getIslandByPlayerId(playerId);
            if (island == null || surfaceAnchor == null || surfaceAnchor.getWorld() == null) {
                return false;
            }
            // HomeSubCommand teleportuje na (warp Y + 0.5), ale islands_warp trzyma
            // INT-y (0.5 jest obcinane), a konwencja Skyllii to „pierwszy wolny blok”
            // — czyli warp Y = powierzchnia + 1 (stopy lądują na topie bloku, +0.5
            // zostaje zapasem). Warp na samym bloku dawał stopy W blok.
            Location home = surfaceAnchor.clone().add(0.5d, 1.5d, 0.5d);
            boolean warps = island.addWarps("home", home, true);
            boolean spawn = island.setSpawnLocation(home);
            if (!warps || !spawn) {
                owner.getLogger().warning("setupIslandHome: Skyllia odrzuciła warp/spawn dla "
                        + playerId + " (warps=" + warps + ", spawn=" + spawn + ")");
            }
            return warps || spawn;
        } catch (Exception e) {
            owner.getLogger().log(java.util.logging.Level.WARNING,
                    "setupIslandHome nie powiódł się dla " + playerId, e);
            return false;
        }
    }

    @Override
    public boolean discardBrokenIsland(UUID playerId) {
        boolean removed = discardBrokenIsland(skylliaDatabase(), playerId, owner.getLogger());
        if (removed) {
            owner.getLogger().severe("SKY-1: usunięto niespójną (niewklejoną) wyspę gracza " + playerId);
            invalidateIslandCache(playerId);
        }
        return removed;
    }

    /**
     * Twardy delete wierszy Skyllii dla island_id = UUID właściciela — niezależnie
     * od flagi disable (purgeSoftDeletedIsland dotyka wyłącznie soft-deleted).
     * Używane wyłącznie po nieudanej weryfikacji paste świeżo utworzonej wyspy.
     */
    static boolean discardBrokenIsland(@NotNull java.nio.file.Path skylliaDb, @NotNull UUID playerId,
                                       @NotNull java.util.logging.Logger logger) {
        String id = playerId.toString();
        String[] tables = {"islands_warp", "islands_flags", "islands_permissions_v2",
                "island_center_locations", "islands_build_height",
                "island_custom_data_skylliaextra_data", "members_in_islands"};
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + skylliaDb + "?busy_timeout=5000&transaction_mode=IMMEDIATE")) {
            c.setAutoCommit(false);
            try {
                for (String table : tables) {
                    deleteIgnoringMissingTable(c, table, id);
                }
                int removed;
                try (java.sql.PreparedStatement del = c.prepareStatement(
                        "DELETE FROM islands WHERE island_id = ?")) {
                    del.setString(1, id);
                    removed = del.executeUpdate();
                }
                c.commit();
                return removed > 0;
            } catch (java.sql.SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (java.sql.SQLException | RuntimeException e) {
            logger.log(java.util.logging.Level.WARNING,
                    "discardBrokenIsland nie powiódł się dla " + playerId + ": " + e, e);
            return false;
        }
    }

    @Override
    public java.util.List<IslandMemberSnapshot> membersOf(UUID islandId) {
        try {
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            if (island == null) return java.util.List.of();
            return island.getMembers().stream()
                    .map(m -> new IslandMemberSnapshot(m.getMojangId(), m.getLastKnowName(), mapRole(m.getRoleType().name())))
                    .toList();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    @Override
    public java.util.List<IslandMemberSnapshot> bannedMembersOf(UUID islandId) {
        try {
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            if (island == null) return java.util.List.of();
            return island.getBannedMembers().stream()
                    .map(m -> new IslandMemberSnapshot(m.getMojangId(), m.getLastKnowName(), IslandRole.BAN))
                    .toList();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    @Override
    public java.util.List<WarpSnapshot> warpsOf(UUID islandId) {
        try {
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            if (island == null) return java.util.List.of();
            return island.getWarps().stream()
                    .map(w -> new WarpSnapshot(w.islandId(), w.warpName(), w.location()))
                    .toList();
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    @Override
    public boolean isPrivateIsland(UUID islandId) {
        try {
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            return island != null && island.isPrivateIsland();
        } catch (Exception e) { return false; }
    }

    @Override
    public boolean multiplyIslandSize(UUID islandId, double factor) {
        try {
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            if (island == null || !(factor > 0) || !Double.isFinite(factor)) return false;
            return island.setSize(island.getSize() * factor);
        } catch (Exception e) {
            owner.getLogger().warning(
                    "multiplyIslandSize nie powiodło się dla " + islandId + ": " + e);
            return false;
        }
    }

    @Override
    public boolean addIslandMemberSlots(UUID islandId, int delta) {
        try {
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            if (island == null) return false;
            return island.setMaxMembers(island.getMaxMembers() + delta);
        } catch (Exception e) {
            owner.getLogger().warning(
                    "addIslandMemberSlots nie powiodło się dla " + islandId + ": " + e);
            return false;
        }
    }

    @Override
    public boolean setPrivateIsland(UUID islandId, boolean isPrivate) {
        try {
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            if (island == null) return false;
            return island.setPrivateIsland(isPrivate);
        } catch (Exception e) { return false; }
    }

    @Override
    public boolean hasVisit(UUID islandId) {
        try {
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            return island != null && island.getVisit() != null;
        } catch (Exception e) { return false; }
    }

    @Override
    public java.util.List<String> biomeNames() {
        try { return SkylliaAPI.getBiomesImpl().getBiomeNameList(); } catch (Exception e) { return java.util.List.of("PLAINS","DESERT","FOREST"); }
    }

    @Override
    public String biomeOf(UUID islandId) {
        // Not directly stored per island in API? Return placeholder via world biome at center
        try {
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            if (island == null) return "PLAINS";
            // Attempt to get biome at center if world loaded
            org.bukkit.World w = org.bukkit.Bukkit.getWorlds().stream().filter(world -> Boolean.TRUE.equals(SkylliaAPI.isWorldSkyblock(world.getName()))).findFirst().orElse(null);
            if (w == null) return "PLAINS";
            org.bukkit.Location center = island.getCenterLocation(w);
            if (center == null) return "PLAINS";
            org.bukkit.block.Biome biome = center.getWorld().getBiome(center);
            return SkylliaAPI.getBiomesImpl().getNameBiome(biome);
        } catch (Exception e) { return "PLAINS"; }
    }

    @Override
    public boolean setBiome(UUID islandId, String biomeName, org.bukkit.World world) {
        try {
            var impl = SkylliaAPI.getBiomesImpl();
            org.bukkit.block.Biome biome = impl.getBiome(biomeName);
            if (biome == null) return false;
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            if (island == null) return false;
            org.bukkit.Location center = island.getCenterLocation(world);
            if (center == null) return false;
            return impl.setBiome(world, center.getBlockX(), center.getBlockZ(), biome);
        } catch (Exception e) { return false; }
    }

    @Override
    public java.util.Optional<org.bukkit.Location> islandCenterLocation(UUID islandId, org.bukkit.World world) {
        try {
            Island island = SkylliaAPI.getIslandByIslandId(islandId);
            if (island == null) return java.util.Optional.empty();
            return java.util.Optional.ofNullable(island.getCenterLocation(world));
        } catch (Exception e) { return java.util.Optional.empty(); }
    }

    @Override
    public void invalidateIslandCache(UUID playerId) {
        cache.invalidate(playerId);
    }

    // ---------------- misc ----------------

    @Override
    public IslandCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public boolean isSkyblockWorld(String worldName) {
        if (worldName == null) {
            return false;
        }
        return Boolean.TRUE.equals(SkylliaAPI.isWorldSkyblock(worldName)) || worldName.startsWith("sky-");
    }
}
