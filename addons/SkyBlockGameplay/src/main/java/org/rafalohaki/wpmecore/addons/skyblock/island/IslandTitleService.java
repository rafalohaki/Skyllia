package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tytuł wyspy: nagroda <b>wyłącznie kosmetyczna</b>, którą wyspa nosi jako jeden
 * napis w MiniMessage (placeholdery, Centrum Wyspy, komunikaty).
 *
 * <p>Tytuły pochodzą z trzech źródeł i każde ma swój identyfikator:
 * <ul>
 *   <li>{@code prestige:<poziom>} — prestiż wyspy kupowany za monety z banku wyspy,</li>
 *   <li>{@code season:<sezon>:rank<miejsce>} — nagroda sezonu za dokładne miejsce,</li>
 *   <li>{@code lotus:master} — jednorazowy zlew 3 Diamentowych Lotosów.</li>
 * </ul>
 *
 * <p><b>Idempotencja.</b> Nadanie z tego samego {@code source_id} jest no-opem:
 * powtórka (podwójne kliknięcie, retry, ponowne odebranie nagrody) nie może
 * nadpisać tytułu ani drugi raz niczego kosztować. O tym, czy nowszy tytuł
 * w ogóle wejdzie, decyduje {@link #priorityOf(String)} — tytuł o niższym
 * priorytecie nie zjada lepszego.
 *
 * <p><b>Odczyt bez blokowania.</b> {@link #cachedTitle(UUID)} czyta ostatnią
 * migawkę z pamięci, a {@link #titleOf(UUID)} dociąga wiersz w tle przez
 * {@link IslandTitleDao}; na wątku regionu ani jedno zapytanie SQL.
 */
public final class IslandTitleService {

    /** Wynik nadania — menu tłumaczy go na komunikat po polsku. */
    public enum GrantStatus {
        /** Tytuł zapisany w tym wywołaniu. */
        GRANTED,
        /** Tytuł z tego samego źródła już jest — powtórka bez zapisu. */
        ALREADY_GRANTED,
        /** Wyspa ma tytuł o co najmniej tak wysokim priorytecie — nowszy nie wchodzi. */
        LOWER_PRIORITY,
        /** Ktoś inny zapisał wiersz w trakcie; po wyczerpaniu prób nic nie zmieniono. */
        NOT_APPLIED
    }

    /** Próg prestiżu: najniższy z uporządkowanych tytułów. */
    static final int PRESTIGE_TIER = 1_000;
    /** Próg sezonu: sezonu nie da się powtórzyć, więc wygrywa z prestiżem. */
    static final int SEASON_TIER = 5_000;
    /** Próg zlewu: jednorazowy koszt 3 Diamentowych Lotosów — najwyżej. */
    static final int LOTUS_TIER = 10_000;
    /** Nieznane źródło: nigdy nie nadpisuje tytułu, który umiemy umiejscowić. */
    static final int UNKNOWN_TIER = 0;

    /** Ile razy powtarzamy zapis, gdy ktoś zdążył zmienić wiersz między odczytem a zapisem. */
    private static final int WRITE_ATTEMPTS = 3;

    private final IslandTitleDao dao;
    /** Migawka wyspa → tytuł; {@code Optional.empty()} znaczy „wczytane, wyspa bez tytułu”. */
    private final Map<UUID, Optional<IslandTitleDao.Stored>> cache = new ConcurrentHashMap<>();
    /** Jedno wczytanie wiersza na wyspę naraz — placeholder nie mnoży zapytań. */
    private final Map<UUID, CompletableFuture<Optional<IslandTitleDao.Stored>>> loads =
            new ConcurrentHashMap<>();

    public IslandTitleService(@NotNull IslandTitleDao dao) {
        this.dao = dao;
    }

    /**
     * Priorytet tytułu wyprowadzony z identyfikatora źródła. Jeden wiersz na
     * wyspę znaczy, że nowsze nadanie nie zawsze ma wygrywać — bez progów
     * tytuł za miejsce #1 w sezonie znikałby przy każdym kolejnym poziomie
     * prestiżu, a jednorazowy tytuł Władcy Lotosu zjadałby powtarzalny prestiż.
     * Progi, od najwyższego:
     * <ul>
     *   <li>{@code lotus:*} → {@value #LOTUS_TIER}: jednorazowy zlew 3 Diamentowych Lotosów,</li>
     *   <li>{@code season:<sezon>:rank<N>} → {@value #SEASON_TIER} − N: im lepsze
     *       miejsce, tym wyżej (miejsce 1 → 4 999), bo sezonu nie da się powtórzyć,</li>
     *   <li>{@code prestige:<N>} → {@value #PRESTIGE_TIER} + N: im wyższy poziom, tym wyżej,</li>
     *   <li>źródło nierozpoznane → {@value #UNKNOWN_TIER}: nigdy nie nadpisuje znanego tytułu.</li>
     * </ul>
     */
    static int priorityOf(@NotNull String sourceId) {
        if (sourceId.startsWith("lotus:")) {
            return LOTUS_TIER;
        }
        if (sourceId.startsWith("season:")) {
            int marker = sourceId.lastIndexOf("rank");
            OptionalInt rank = marker < 0 ? OptionalInt.empty()
                    : wholeNumber(sourceId.substring(marker + "rank".length()));
            return rank.isPresent() && rank.getAsInt() >= 1
                    ? SEASON_TIER - rank.getAsInt()
                    : UNKNOWN_TIER;
        }
        if (sourceId.startsWith("prestige:")) {
            OptionalInt level = wholeNumber(sourceId.substring("prestige:".length()));
            return level.isPresent() && level.getAsInt() >= 1
                    ? PRESTIGE_TIER + level.getAsInt()
                    : UNKNOWN_TIER;
        }
        return UNKNOWN_TIER;
    }

    private static @NotNull OptionalInt wholeNumber(@NotNull String raw) {
        try {
            return OptionalInt.of(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException notANumber) {
            return OptionalInt.empty();
        }
    }

    /** Bieżący tytuł wyspy z bazy (a potem z cache) — nigdy nie blokuje wołającego. */
    public @NotNull CompletableFuture<Optional<String>> titleOf(@NotNull UUID islandId) {
        return recordOf(islandId).thenApply(stored -> stored.map(IslandTitleDao.Stored::title));
    }

    /** Ostatnio wczytany tytuł wyspy; pusty także wtedy, gdy wyspy jeszcze nie czytaliśmy. */
    public @NotNull Optional<String> cachedTitle(@NotNull UUID islandId) {
        return cachedRecord(islandId).map(IslandTitleDao.Stored::title);
    }

    /** Ostatnio wczytane źródło tytułu — po nim kafelek poznaje, że zlew już się odbył. */
    public @NotNull Optional<String> cachedSourceId(@NotNull UUID islandId) {
        return cachedRecord(islandId).map(IslandTitleDao.Stored::sourceId);
    }

    /**
     * Tytuł dla placeholdera: wyłącznie odczyt migawki. Wyspa bez wczytanego
     * tytułu (pierwsze wezwanie po restarcie) dostaje <b>pusty napis</b>,
     * a wiersz jest dociągany w tle — nigdy „null” i nigdy blokada wątku,
     * który rysuje hologram.
     */
    public @NotNull String placeholderTitle(@Nullable UUID islandId) {
        if (islandId == null) {
            return "";
        }
        Optional<IslandTitleDao.Stored> known = cache.get(islandId);
        if (known != null) {
            return known.map(IslandTitleDao.Stored::title).orElse("");
        }
        recordOf(islandId);
        return "";
    }

    /**
     * Czy wyspa nosi tytuł z dokładnie tego źródła? Odpowiedź idzie przez cache
     * i bazę, więc jest autorytatywna także po restarcie — to po niej zlew
     * Lotosów poznaje, że wyspa już ma tytuł i nie wolno jej skasować drugi raz.
     */
    public @NotNull CompletableFuture<Boolean> hasTitleFrom(@NotNull UUID islandId,
                                                            @NotNull String sourceId) {
        return recordOf(islandId).thenApply(stored ->
                stored.map(IslandTitleDao.Stored::sourceId).filter(sourceId::equals).isPresent());
    }

    /**
     * Nadaje tytuł. Zapis jest warunkowy: gdy między odczytem a zapisem ktoś
     * zmienił wiersz, decyzja jest powtarzana (do {@value #WRITE_ATTEMPTS} prób),
     * więc równoległe nadania nie zostawiają tytułu o niższym priorytecie.
     */
    public @NotNull CompletableFuture<GrantStatus> grant(@NotNull UUID islandId,
                                                         @NotNull String title,
                                                         @NotNull String sourceId) {
        CompletableFuture<GrantStatus> result = new CompletableFuture<>();
        attemptGrant(islandId, title, sourceId, priorityOf(sourceId), WRITE_ATTEMPTS, result);
        return result;
    }

    /**
     * Zdejmuje tytuł wyspy, ale tylko wtedy, gdy pochodzi dokładnie z
     * {@code sourceId} — tabela nie trzyma historii, więc zdjęcie tytułu
     * zostawia wyspę bez żadnego (kolejne nadanie wypełni wiersz od nowa).
     */
    public @NotNull CompletableFuture<Boolean> revoke(@NotNull UUID islandId,
                                                      @NotNull String sourceId) {
        return dao.deleteIfSource(islandId, sourceId).thenApply(deleted -> {
            if (deleted) {
                cache.put(islandId, Optional.empty());
            }
            return deleted;
        });
    }

    /**
     * Wyspa przestała istnieć: kasuje wiersz tytułu i zapomnianą migawkę.
     *
     * <p>Bez tego tytuł przeżyłby skasowanie wyspy — a że {@code island_id} to
     * UUID właściciela, ta sama osoba odtwarzająca wyspę zobaczyłaby na niej
     * tytuł, którego nigdy nie kupiła (i zlew Lotosów byłby dla niej zamknięty
     * jako „już wykorzystany”).
     */
    public @NotNull CompletableFuture<Void> forgetIsland(@NotNull UUID islandId) {
        cache.remove(islandId);
        loads.remove(islandId);
        return dao.deleteAll(islandId);
    }

    /** Wiersz z cache albo z bazy; równoległe odczyty tej samej wyspy dzielą jedno zapytanie. */
    private @NotNull CompletableFuture<Optional<IslandTitleDao.Stored>> recordOf(
            @NotNull UUID islandId) {
        Optional<IslandTitleDao.Stored> known = cache.get(islandId);
        if (known != null) {
            return CompletableFuture.completedFuture(known);
        }
        CompletableFuture<Optional<IslandTitleDao.Stored>> fresh = new CompletableFuture<>();
        CompletableFuture<Optional<IslandTitleDao.Stored>> running = loads.putIfAbsent(islandId, fresh);
        if (running != null) {
            return running;
        }
        dao.find(islandId).whenComplete((stored, failure) -> {
            loads.remove(islandId, fresh);
            if (failure != null) {
                fresh.completeExceptionally(failure);
                return;
            }
            Optional<IslandTitleDao.Stored> value = stored == null ? Optional.empty() : stored;
            cache.put(islandId, value);
            fresh.complete(value);
        });
        return fresh;
    }

    private @NotNull Optional<IslandTitleDao.Stored> cachedRecord(@NotNull UUID islandId) {
        return cache.getOrDefault(islandId, Optional.empty());
    }

    private void attemptGrant(@NotNull UUID islandId, @NotNull String title,
                              @NotNull String sourceId, int priority, int attemptsLeft,
                              @NotNull CompletableFuture<GrantStatus> result) {
        dao.find(islandId).whenComplete((stored, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            Optional<IslandTitleDao.Stored> current =
                    stored == null ? Optional.empty() : stored;
            if (current.isPresent()) {
                IslandTitleDao.Stored row = current.get();
                if (row.sourceId().equals(sourceId)) {
                    cache.put(islandId, current);
                    result.complete(GrantStatus.ALREADY_GRANTED);
                    return;
                }
                if (priorityOf(row.sourceId()) >= priority) {
                    cache.put(islandId, current);
                    result.complete(GrantStatus.LOWER_PRIORITY);
                    return;
                }
            }
            dao.writeIfCurrent(islandId, title, sourceId,
                            current.map(IslandTitleDao.Stored::sourceId).orElse(null),
                            System.currentTimeMillis())
                    .whenComplete((applied, writeFailure) -> {
                        if (writeFailure != null) {
                            result.completeExceptionally(writeFailure);
                            return;
                        }
                        if (Boolean.TRUE.equals(applied)) {
                            cache.put(islandId, Optional.of(
                                    new IslandTitleDao.Stored(title, sourceId)));
                            result.complete(GrantStatus.GRANTED);
                            return;
                        }
                        if (attemptsLeft <= 1) {
                            result.complete(GrantStatus.NOT_APPLIED);
                            return;
                        }
                        attemptGrant(islandId, title, sourceId, priority, attemptsLeft - 1, result);
                    });
        });
    }
}
