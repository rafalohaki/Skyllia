package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.shared.SqlSupport;
import org.rafalohaki.wpmecore.api.service.SqlService;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Trwały tytuł wyspy ({@code wpme_sb_island_titles}, migracja #13).
 *
 * <p>Jeden wiersz na wyspę, bez historii: tabela trzyma tylko tytuł, który
 * wyspa nosi teraz, i źródło, z którego przyszedł. Źródło ({@code source_id})
 * jest kluczem idempotencji — powtórne nadanie z tego samego źródła nie może
 * drugi raz nic kosztować.
 *
 * <p>Zapis jest porównaniem-i-przestawieniem na {@code source_id}: wołający
 * najpierw czyta wiersz ({@link #find}), decyduje w Javie o priorytecie,
 * a potem pisze {@link #writeIfCurrent} z oczekiwanym źródłem. Gdy w międzyczasie
 * ktoś inny zapisał wiersz, zapis zwraca {@code false} i decyzja jest
 * powtarzana. Dzięki temu priorytet tytułu liczy jedna funkcja w Javie,
 * a nie kopia tej logiki w SQL-u.
 */
public interface IslandTitleDao {

    /** Bieżący tytuł wyspy; pusty {@code Optional}, gdy wyspa nie ma wiersza. */
    @NotNull CompletableFuture<Optional<Stored>> find(@NotNull UUID islandId);

    /**
     * Zapisuje tytuł pod warunkiem, że wiersz stoi dokładnie na
     * {@code expectedSourceId} (albo wiersza jeszcze nie ma, gdy {@code null}).
     *
     * @return true, gdy zapis faktycznie się przyjął; false, gdy ktoś zdążył
     *         zmienić wiersz między odczytem a tym zapisem
     */
    @NotNull CompletableFuture<Boolean> writeIfCurrent(@NotNull UUID islandId, @NotNull String title,
                                                       @NotNull String sourceId,
                                                       @Nullable String expectedSourceId,
                                                       long updatedAt);

    /** Usuwa wiersz wyspy, ale tylko wtedy, gdy należy do {@code sourceId}. */
    @NotNull CompletableFuture<Boolean> deleteIfSource(@NotNull UUID islandId,
                                                       @NotNull String sourceId);

    /**
     * Usuwa wiersz wyspy niezależnie od źródła — wywoływane, gdy wyspa przestaje
     * istnieć. Bez tego tytuł przeżyłby skasowanie wyspy i wrócił na ekranie
     * odtworzonej wyspy o tym samym {@code island_id} (UUID właściciela).
     */
    @NotNull CompletableFuture<Void> deleteAll(@NotNull UUID islandId);

    /** Wiersz tytułu wyspy. */
    record Stored(@NotNull String title, @NotNull String sourceId) { }

    /** Implementacja na {@link SqlService}: jedno połączenie, jedna transakcja na zapis. */
    final class Sql implements IslandTitleDao {

        private final SqlService sql;

        public Sql(@NotNull SqlService sql) {
            this.sql = sql;
        }

        @Override
        public @NotNull CompletableFuture<Optional<Stored>> find(@NotNull UUID islandId) {
            return sql.queryOne("""
                            SELECT title, source_id FROM wpme_sb_island_titles
                            WHERE island_id = ?
                            """,
                    rs -> new Stored(rs.getString(1), rs.getString(2)),
                    islandId.toString());
        }

        @Override
        public @NotNull CompletableFuture<Boolean> writeIfCurrent(@NotNull UUID islandId,
                                                                  @NotNull String title,
                                                                  @NotNull String sourceId,
                                                                  @Nullable String expectedSourceId,
                                                                  long updatedAt) {
            String id = islandId.toString();
            if (expectedSourceId != null) {
                return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () -> {
                    try (var statement = connection.prepareStatement("""
                            UPDATE wpme_sb_island_titles
                               SET title = ?, source_id = ?, updated_at = ?
                             WHERE island_id = ? AND source_id = ?
                            """)) {
                        statement.setString(1, title);
                        statement.setString(2, sourceId);
                        statement.setLong(3, updatedAt);
                        statement.setString(4, id);
                        statement.setString(5, expectedSourceId);
                        return statement.executeUpdate() == 1;
                    }
                }));
            }
            return sql.withConnection(connection -> SqlSupport.inTransaction(connection, () ->
                    SqlSupport.insertIgnoringConstraint(connection, """
                                    INSERT INTO wpme_sb_island_titles
                                        (island_id, title, source_id, updated_at)
                                    VALUES (?, ?, ?, ?)
                                    """, "ON CONFLICT (island_id) DO NOTHING",
                            statement -> {
                                statement.setString(1, id);
                                statement.setString(2, title);
                                statement.setString(3, sourceId);
                                statement.setLong(4, updatedAt);
                            })));
        }

        @Override
        public @NotNull CompletableFuture<Boolean> deleteIfSource(@NotNull UUID islandId,
                                                                  @NotNull String sourceId) {
            return sql.update("""
                            DELETE FROM wpme_sb_island_titles
                            WHERE island_id = ? AND source_id = ?
                            """,
                    islandId.toString(), sourceId).thenApply(deleted -> deleted == 1);
        }

        @Override
        public @NotNull CompletableFuture<Void> deleteAll(@NotNull UUID islandId) {
            return sql.update("""
                            DELETE FROM wpme_sb_island_titles
                            WHERE island_id = ?
                            """,
                    islandId.toString()).thenApply(ignored -> null);
        }
    }
}
