package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * M2.5: przepustka sezonowa (spec §5) — 28 poziomów na pilot.
 * XP przepustki = punkty sezonowe / 50. Tor darmowy + premium (Lotosy).
 * Claim idempotentny przez tabelę claimów (PK season+player+level+track).
 */
public final class SeasonPassService {

    public static final int PILOT_LEVELS = 28;
    /** Ile punktów sezonowych = 1 poziom przepustki. */
    public static final long POINTS_PER_LEVEL = 50L;
    /** P2-1: po L28 każde 500 pkt to poziom bonusowy (29, 30, …) z powtarzalną nagrodą. */
    public static final long BONUS_POINTS_PER_LEVEL = 500L;
    /** Sufit sanity — 200 poziomów bonusowych = 100 000 pkt, poza zasięgiem sezonu. */
    public static final int MAX_BONUS_LEVELS = 200;

    /** Poziom karnetu z punktów: 1..28 co 50 pkt, potem bonus co 500 pkt. */
    public static int levelFor(long points) {
        long base = Math.min(PILOT_LEVELS, points / POINTS_PER_LEVEL);
        if (base < PILOT_LEVELS) {
            return (int) base;
        }
        long bonus = Math.min(MAX_BONUS_LEVELS, (points - PILOT_LEVELS * POINTS_PER_LEVEL) / BONUS_POINTS_PER_LEVEL);
        return PILOT_LEVELS + (int) bonus;
    }

    public static boolean isBonusLevel(int level) {
        return level > PILOT_LEVELS;
    }

    /** Punkty potrzebne na dany poziom (odwrotność {@link #levelFor}). */
    public static long pointsForLevel(int level) {
        if (level <= PILOT_LEVELS) {
            return level * POINTS_PER_LEVEL;
        }
        return PILOT_LEVELS * POINTS_PER_LEVEL + (level - PILOT_LEVELS) * BONUS_POINTS_PER_LEVEL;
    }

    public enum Track { FREE, PREMIUM }


    /** Numer bieżącego sezonu (dostarczany przez koordynator nagród). */
    public int currentSeason() {
        return seasonSupplier.get();
    }
    private final org.rafalohaki.wpmecore.api.service.SqlService sql;
    private final java.util.function.Supplier<Integer> seasonSupplier;

    public SeasonPassService(@NotNull org.rafalohaki.wpmecore.api.service.SqlService sql,
                             @NotNull java.util.function.Supplier<Integer> seasonSupplier) {
        this.sql = sql;
        this.seasonSupplier = seasonSupplier;
    }

    /** Poziom przepustki gracza = min(PILOT_LEVELS, points / POINTS_PER_LEVEL). */
    public @NotNull CompletableFuture<Integer> levelOf(@NotNull UUID playerUuid) {
        int season = seasonSupplier.get();
        return sql.queryOne("""
                        SELECT points FROM wpme_sb_season_points WHERE season_id = ? AND player_uuid = ?
                        """, rs -> rs.getLong(1), season, playerUuid.toString())
                .thenApply(opt -> levelFor(opt.orElse(0L)));
    }

    /**
     * Aktywacja toru premium. Idempotentna: druga aktywacja zwraca false.
     */
    public @NotNull CompletableFuture<Boolean> activatePremium(@NotNull UUID playerUuid) {
        int season = seasonSupplier.get();
        long now = System.currentTimeMillis();
        return activatePremiumInDb(playerUuid, season, now)
                .thenApply(fresh -> {
                    premiumCache.put(season + ":" + playerUuid, true);
                    return fresh;
                });
    }

    private @NotNull CompletableFuture<Boolean> activatePremiumInDb(@NotNull UUID playerUuid, int season, long now) {
        return sql.withConnection((org.rafalohaki.wpmecore.api.service.SqlService.SqlAction<Boolean>) connection -> {
            try (var ps = connection.prepareStatement(
                    "INSERT INTO wpme_sb_season_pass (season_id, player_uuid, premium, updated_at) VALUES (?, ?, TRUE, ?)")) {
                ps.setInt(1, season);
                ps.setString(2, playerUuid.toString());
                ps.setLong(3, now);
                try {
                    return ps.executeUpdate() > 0; // nowy wiersz = świeża aktywacja
                } catch (java.sql.SQLException e) {
                    if (SqlSupport.isConstraintViolation(e)) {
                        try (var q = connection.prepareStatement(
                                "SELECT 1 FROM wpme_sb_season_pass WHERE season_id = ? AND player_uuid = ? AND premium = FALSE")) {
                            q.setInt(1, season);
                            q.setString(2, playerUuid.toString());
                            try (var rs = q.executeQuery()) {
                                if (!rs.next()) return false; // już premium
                            }
                        }
                        try (var up = connection.prepareStatement(
                                "UPDATE wpme_sb_season_pass SET premium = TRUE, updated_at = ? WHERE season_id = ? AND player_uuid = ?")) {
                            up.setLong(1, now);
                            up.setInt(2, season);
                            up.setString(3, playerUuid.toString());
                            return up.executeUpdate() > 0;
                        }
                    }
                    throw e;
                }
            }
        });
    }

    public @NotNull CompletableFuture<Boolean> isPremium(@NotNull UUID playerUuid) {
        int season = seasonSupplier.get();
        return sql.queryOne("""
                        SELECT premium FROM wpme_sb_season_pass WHERE season_id = ? AND player_uuid = ?
                        """, rs -> rs.getBoolean(1), season, playerUuid.toString())
                .thenApply(opt -> {
                    boolean premium = opt.orElse(false);
                    premiumCache.put(season + ":" + playerUuid, premium);
                    return premium;
                });
    }

    private final java.util.Map<String, Boolean> premiumCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Synchroniczny odczyt dla tablicy bocznej (P1-2). Pierwsze pytanie o gracza
     * w sezonie odpala {@link #isPremium} w tle i odpowiada {@code false}; kolejne
     * odświeżenia tablicy widzą już wynik. Aktywacja premium wpisuje {@code true}
     * od razu, więc gracz nie czeka na ponowny odczyt.
     */
    public boolean isPremiumCached(@NotNull UUID playerUuid) {
        String key = seasonSupplier.get() + ":" + playerUuid;
        Boolean cached = premiumCache.putIfAbsent(key, false);
        if (cached == null) {
            isPremium(playerUuid);
            return false;
        }
        return cached;
    }

    /**
     * Claim nagrody z poziomu — idempotentny przez PK claimów.
     *
     * @return true gdy nagroda właśnie odebrana; false gdy już odebrana,
     *         tor nieaktywny albo poziom jeszcze nie osiągnięty
     */
    public @NotNull CompletableFuture<Boolean> claim(@NotNull UUID playerUuid, int level,
                                                     @NotNull Track track) {
        if (level < 1 || level > PILOT_LEVELS + MAX_BONUS_LEVELS) {
            return CompletableFuture.completedFuture(false);
        }
        int season = seasonSupplier.get();

        return sql.withConnection(connection -> {
            // 1. poziom osiągnięty? (po L28 poziomy bonusowe co 500 pkt — levelFor)
            long points = pointsSync(connection, playerUuid, season);
            if (levelFor(points) < level) return false;

            // 2. tor premium aktywny?
            if (track == Track.PREMIUM && !premiumSync(connection, playerUuid, season)) {
                return false;
            }

            // 3. idempotentny insert claimu
            long now = System.currentTimeMillis();
            try (var ps = connection.prepareStatement("""
                    INSERT INTO wpme_sb_season_pass_claims (season_id, player_uuid, level, track, claimed_at)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                ps.setInt(1, season);
                ps.setString(2, playerUuid.toString());
                ps.setInt(3, level);
                ps.setString(4, track.name());
                ps.setLong(5, now);
                try {
                    return ps.executeUpdate() > 0;
                } catch (java.sql.SQLException e) {
                    if (SqlSupport.isConstraintViolation(e)) {
                        return false; // już odebrane
                    }
                    throw e;
                }
            }
        });
    }

    /**
     * Brama UPRAWNIEŃ, oddzielona od idempotencji {@link #claim}: czy gracz w
     * ogóle MA prawo do nagrody z tego poziomu/toru (poziom osiągnięty ORAZ
     * premium aktywne dla toru PREMIUM). Nie dotyka tabeli claimów — samo prawo,
     * nie „czy już odebrano". Menu sprawdza to PRZED jakąkolwiek wypłatą, żeby
     * klik w zablokowany poziom nie inkasował monet ani przedmiotów.
     *
     * @return false gdy poziom poza zakresem, jeszcze nie osiągnięty, albo tor
     *         PREMIUM bez aktywnego premium
     */
    public @NotNull CompletableFuture<Boolean> isEligible(@NotNull UUID playerUuid, int level,
                                                          @NotNull Track track) {
        if (level < 1 || level > PILOT_LEVELS + MAX_BONUS_LEVELS) {
            return CompletableFuture.completedFuture(false);
        }
        int season = seasonSupplier.get();
        return sql.withConnection(connection -> {
            long points = pointsSync(connection, playerUuid, season);
            if (levelFor(points) < level) {
                return false;
            }
            return track != Track.PREMIUM || premiumSync(connection, playerUuid, season);
        });
    }

    /** Czy gracz odebrał nagrodę z danego poziomu/toru. */
    public @NotNull CompletableFuture<Boolean> claimed(@NotNull UUID playerUuid, int level,
                                                       @NotNull Track track) {
        int season = seasonSupplier.get();
        return sql.queryOne("""
                        SELECT 1 FROM wpme_sb_season_pass_claims
                        WHERE season_id = ? AND player_uuid = ? AND level = ? AND track = ?
                        """, rs -> Boolean.TRUE, season, playerUuid.toString(), level, track.name())
                .thenApply(opt -> opt.orElse(false));
    }

    /** Zbiór poziomów odebranych na danym torze — jedno zapytanie zamiast 28. */
    public @NotNull CompletableFuture<java.util.Set<Integer>> claimedLevels(
            @NotNull UUID playerUuid, @NotNull Track track) {
        int season = seasonSupplier.get();
        return sql.query("""
                        SELECT level FROM wpme_sb_season_pass_claims
                        WHERE season_id = ? AND player_uuid = ? AND track = ?
                        """, rs -> rs.getInt(1), season, playerUuid.toString(), track.name())
                .thenApply(java.util.Set::copyOf);
    }

    private static long pointsSync(Connection c, UUID u, int season) throws java.sql.SQLException {
        try (var ps = c.prepareStatement(
                "SELECT COALESCE(points, 0) FROM wpme_sb_season_points WHERE season_id = ? AND player_uuid = ?")) {
            ps.setInt(1, season);
            ps.setString(2, u.toString());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static boolean premiumSync(Connection c, UUID u, int season) throws java.sql.SQLException {
        try (var ps = c.prepareStatement(
                "SELECT COALESCE(premium, FALSE) FROM wpme_sb_season_pass WHERE season_id = ? AND player_uuid = ?")) {
            ps.setInt(1, season);
            ps.setString(2, u.toString());
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
