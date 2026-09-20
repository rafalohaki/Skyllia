package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Trwały stan Prestiżu Wyspy ({@code wpme_sb_island_prestige}, migracja #12).
 *
 * <p>Dwa zapytania na jednej tabeli, więc implementacja mieszka w pliku
 * kontraktu: odczyt bieżącego poziomu i suma wyjętych monet oraz zapis
 * przejścia {@code fromLevel → toLevel}. Zapis jest strażony poziomem
 * wyjściowym, więc powtórka (dwa kliknięcia, retry po awarii) nie dolicza
 * kosztu drugi raz i nie przeskakuje poziomu.
 */
public interface IslandPrestigeDao {

    /** Bieżący poziom prestiżu wyspy i suma monet wyjętych z banku (0 gdy brak wiersza). */
    @NotNull CompletableFuture<Optional<State>> find(@NotNull UUID islandId);

    /**
     * Zapisuje przejście na {@code toLevel} pod warunkiem, że wiersz stoi
     * dokładnie na {@code fromLevel} (albo wiersza jeszcze nie ma).
     *
     * @return true, gdy wiersz faktycznie awansował w tym wywołaniu;
     *         false, gdy ktoś już zapisał ten poziom (powtórka jest no-opem)
     */
    @NotNull CompletableFuture<Boolean> recordLevel(@NotNull UUID islandId, int fromLevel,
                                                    int toLevel, long costMinor);

    /** Wiersz prestiżu wyspy. */
    record State(int level, long spentMinor) { }

    /** Implementacja na {@link SqlService}: jedno połączenie, jedna transakcja, zero I/O poza pulą SQL. */
    final class Sql implements IslandPrestigeDao {

        private final SqlService sql;

        public Sql(@NotNull SqlService sql) {
            this.sql = sql;
        }

        @Override
        public @NotNull CompletableFuture<Optional<State>> find(@NotNull UUID islandId) {
            return sql.queryOne("""
                            SELECT level, spent_minor FROM wpme_sb_island_prestige
                            WHERE island_id = ?
                            """,
                    rs -> new State(rs.getInt(1), rs.getLong(2)),
                    islandId.toString());
        }

        @Override
        public @NotNull CompletableFuture<Boolean> recordLevel(@NotNull UUID islandId, int fromLevel,
                                                               int toLevel, long costMinor) {
            String id = islandId.toString();
            long now = System.currentTimeMillis();
            return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () -> {
                int advanced = 0;
                try (var statement = connection.prepareStatement("""
                        UPDATE wpme_sb_island_prestige
                           SET level = ?, spent_minor = spent_minor + ?, updated_at = ?
                         WHERE island_id = ? AND level = ?
                        """)) {
                    statement.setInt(1, toLevel);
                    statement.setLong(2, costMinor);
                    statement.setLong(3, now);
                    statement.setString(4, id);
                    statement.setInt(5, fromLevel);
                    advanced = statement.executeUpdate();
                }
                if (advanced == 1) {
                    return true;
                }
                // Brak wiersza (pierwszy prestiż) albo ktoś już go zapisał —
                // wstawiamy tylko wtedy, gdy wiersza naprawdę nie ma.
                return SqlSupport.insertIgnoringConstraint(connection, """
                                INSERT INTO wpme_sb_island_prestige
                                    (island_id, level, spent_minor, updated_at)
                                VALUES (?, ?, ?, ?)
                                """, "ON CONFLICT (island_id) DO NOTHING",
                        statement -> {
                            statement.setString(1, id);
                            statement.setInt(2, toLevel);
                            statement.setLong(3, costMinor);
                            statement.setLong(4, now);
                        });
            }));
        }
    }
}
