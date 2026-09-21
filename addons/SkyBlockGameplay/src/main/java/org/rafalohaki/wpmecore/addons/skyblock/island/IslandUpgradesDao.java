package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Trwały stan Ulepszeń Wyspy ({@code wpme_sb_island_upgrades}, migracja #14).
 *
 * <p>Jeden wiersz na (wyspa, tor) — poziom nigdy nie spada, {@code spent_minor}
 * sumuje monety wyjęte z banku wyspy przez ten tor. Zapis jest strażony
 * poziomem wyjściowym, więc powtórka (dwa kliknięcia, retry po awarii) nie
 * dolicza kosztu drugi raz i nie przeskakuje poziomu — dokładnie ten sam
 * kontrakt co {@link IslandPrestigeDao}.
 */
public interface IslandUpgradesDao {

    /** Bieżący poziom toru wyspy i suma wydanych monet (brak wiersza = puste). */
    @NotNull CompletableFuture<Optional<State>> find(@NotNull UUID islandId,
                                                     @NotNull IslandUpgrades.Track track);

    /**
     * Zapisuje przejście na {@code toLevel} pod warunkiem, że wiersz stoi
     * dokładnie na {@code fromLevel} (albo wiersza jeszcze nie ma).
     *
     * @return true, gdy wiersz faktycznie awansował w tym wywołaniu;
     *         false, gdy ktoś już zapisał ten poziom (powtórka jest no-opem)
     */
    @NotNull CompletableFuture<Boolean> recordLevel(@NotNull UUID islandId,
                                                    @NotNull IslandUpgrades.Track track,
                                                    int fromLevel, int toLevel, long costMinor);

    /** Wiersz toru ulepszeń wyspy. */
    record State(int level, long spentMinor) { }

    /** Implementacja na {@link SqlService}: jedno połączenie, jedna transakcja. */
    final class Sql implements IslandUpgradesDao {

        private final SqlService sql;

        public Sql(@NotNull SqlService sql) {
            this.sql = sql;
        }

        @Override
        public @NotNull CompletableFuture<Optional<State>> find(@NotNull UUID islandId,
                                                                @NotNull IslandUpgrades.Track track) {
            return sql.queryOne("""
                            SELECT level, spent_minor FROM wpme_sb_island_upgrades
                            WHERE island_id = ? AND track = ?
                            """,
                    rs -> new State(rs.getInt(1), rs.getLong(2)),
                    islandId.toString(), track.dbId());
        }

        @Override
        public @NotNull CompletableFuture<Boolean> recordLevel(@NotNull UUID islandId,
                                                               @NotNull IslandUpgrades.Track track,
                                                               int fromLevel, int toLevel,
                                                               long costMinor) {
            String id = islandId.toString();
            String trackId = track.dbId();
            long now = System.currentTimeMillis();
            return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () -> {
                int advanced = 0;
                try (var statement = connection.prepareStatement("""
                        UPDATE wpme_sb_island_upgrades
                           SET level = ?, spent_minor = spent_minor + ?, updated_at = ?
                         WHERE island_id = ? AND track = ? AND level = ?
                        """)) {
                    statement.setInt(1, toLevel);
                    statement.setLong(2, costMinor);
                    statement.setLong(3, now);
                    statement.setString(4, id);
                    statement.setString(5, trackId);
                    statement.setInt(6, fromLevel);
                    advanced = statement.executeUpdate();
                }
                if (advanced == 1) {
                    return true;
                }
                // Brak wiersza (pierwszy zakup toru) albo ktoś już go zapisał.
                return SqlSupport.insertIgnoringConstraint(connection, """
                                INSERT INTO wpme_sb_island_upgrades
                                    (island_id, track, level, spent_minor, updated_at)
                                VALUES (?, ?, ?, ?, ?)
                                """, "ON CONFLICT (island_id, track) DO NOTHING",
                        statement -> {
                            statement.setString(1, id);
                            statement.setString(2, trackId);
                            statement.setInt(3, toLevel);
                            statement.setLong(4, costMinor);
                            statement.setLong(5, now);
                        });
            }));
        }
    }
}
