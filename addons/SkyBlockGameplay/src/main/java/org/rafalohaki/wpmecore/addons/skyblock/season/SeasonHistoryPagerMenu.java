package org.rafalohaki.wpmecore.addons.skyblock.season;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.service.MenuService;
import org.rafalohaki.wpmecore.api.service.SqlService;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Historia edycji (ekran C) — pager po ZAMKNIĘTYCH sezonach, karta na sezon.
 *
 * <p>Źródła (tylko ODCZYT): najnowszy wiersz per sezon z
 * {@code wpme_sb_season_editions} (MAX(activated_at)), uzupełniony o sezony
 * sprzed rejestru ({@code DISTINCT season_id FROM wpme_sb_season_history}) —
 * te renderują się jako „Sezon S&lt;n&gt;”. Podium (rang ≤ 3) i liczba
 * zarchiwizowanych wierszy dochodzą z tej samej tabeli historii; niki przez
 * wstrzyknięty resolver ({@code LeaderboardService::nameOf} w produkcji).
 *
 * <p>Zero mutacji: wyłącznie SELECT-y na wątku SQL, render na wątku encji.
 * Ciężkie wiersze edycji są stronicowane w SQL ({@code LIMIT/OFFSET}, strony
 * po {@link #PAGE_SIZE}); fallback legacy zostaje cięty klientowo (mała lista).
 */
public final class SeasonHistoryPagerMenu {

    /** Karta = jeden zamknięty sezon; pola legacy są dopuszczalnie puste,
     *  {@code runnerUps} nigdy nie jest {@code null} (puste gdy brak danych). */
    public record Card(int seasonId,
                       @Nullable String displayName,
                       @Nullable String editionType,
                       @Nullable String startDateIso,
                       @Nullable String endDateIso,
                       long closedAtMillis,
                       @Nullable String winnerNick,
                       long winnerPoints,
                       long archivedRows,
                       @NotNull List<RunnerUp> runnerUps) {

        /** Wartość „nie wiadomo” dla liczników liczbowych. */
        public static final long UNKNOWN = -1L;
    }

    /** Pozycja 2–3 podium historii sezonu (ranga 1 = pola zwycięzcy). */
    public record RunnerUp(int position, @NotNull String nick, long points) { }

    /** Jedna strona kart: widoczne wiersze + pozycja i rozmiar paginacji. */
    record Page(@NotNull List<Card> rows, int page, int pageCount) {

        boolean hasPrev() {
            return page > 0;
        }

        boolean hasNext() {
            return page < pageCount - 1;
        }
    }

    /** Pusta strona — stan „brak historii”. */
    private static final Page EMPTY_PAGE = new Page(List.of(), 0, 1);

    public static final int PAGE_SIZE = 10;
    /** Pierwszy slot siatki kart (rząd 1); 10 kart mieści się w rzędach 1–3. */
    static final int FIRST_CARD_SLOT = 9;
    static final int LAST_CARD_SLOT = 35;
    static final int SLOT_PREV = 45;
    static final int SLOT_BACK = 49;
    static final int SLOT_NEXT = 51;
    static final int SLOT_CLOSE_UI = 53;

    private static final String TITLE = "<dark_gray>Historia Edycji</dark_gray>";
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT);

    /** Sezony sprzed rejestru — fallback, mała lista cięta klientowo. */
    private static final String SQL_LEGACY_IDS =
            "SELECT DISTINCT season_id FROM wpme_sb_season_history";

    /** Lekki zestaw identyfikatorów — wyznacza uniwersum i granice stron. */
    private static final String SQL_EDITION_IDS =
            "SELECT DISTINCT season_id FROM wpme_sb_season_editions";

    /** Najnowsza edycja per sezon; dane tylko dla widocznej strony ({@code LIMIT/OFFSET}). */
    private static final String SQL_EDITION_PAGE = """
            SELECT e.season_id, e.display_name, e.edition_type,
                   e.start_date, e.end_date, e.closed_at
            FROM wpme_sb_season_editions e
            JOIN (SELECT season_id, MAX(activated_at) AS m
                  FROM wpme_sb_season_editions GROUP BY season_id) t
              ON e.season_id = t.season_id AND e.activated_at = t.m
            ORDER BY e.season_id DESC
            LIMIT ? OFFSET ?
            """;

    /** Wiersz podium historii (rang ≤ 3): UUID + punkty sprzed rolloveru. */
    private record Winner(@NotNull UUID playerId, long points) { }

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage miniMessage;
    @Nullable
    private final SqlService sql;
    @Nullable
    private final UnaryOperator<String> nickResolver;
    private final Consumer<Player> backAction;

    /** Ochrona przed podwójnym otwarciem (wzorzec {@code SeasonPassMenu}). */
    private final Map<UUID, Long> opening = new ConcurrentHashMap<>();

    /** Bieżąca strona ekranu; przeładowywana asynchronicznie przy flipie. */
    private volatile Page current = EMPTY_PAGE;
    private volatile int page;

    public SeasonHistoryPagerMenu(@NotNull JavaPlugin plugin,
                                  @NotNull MenuService menus,
                                  @NotNull MiniMessage miniMessage,
                                  @Nullable SqlService sql,
                                  @Nullable UnaryOperator<String> nickResolver,
                                  @NotNull Consumer<Player> backAction) {
        this.plugin = plugin;
        this.menus = menus;
        this.miniMessage = miniMessage;
        this.sql = sql;
        this.nickResolver = nickResolver;
        this.backAction = backAction;
    }

    /** Otwiera ekran: najpierw wątek encji, potem asynchroniczny odczyt historii. */
    public void open(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long previous = opening.put(uuid, now);
        if (previous != null && now - previous < 1000L) {
            player.sendMessage(Ui.component(miniMessage,
                    "<gray>Historia właśnie się odświeża…</gray>"));
            return; // podwójne kliknięcie: debounce 1 s z podpowiedzią na czacie
        }
        try {
            var scheduled = player.getScheduler().run(plugin, ignored ->
                    loadAndOpen(player), () -> opening.remove(uuid));
            if (scheduled == null) {
                opening.remove(uuid);
            }
        } catch (RuntimeException rejected) {
            opening.remove(uuid);
            plugin.getLogger().fine("Sezony admin: harmonogram historii odrzucony: " + rejected);
            loadAndOpen(player);
        }
    }

    private void loadAndOpen(@NotNull Player player) {
        loadPage(0).thenAccept(loaded -> runOnEntityThread(player, () -> {
            current = loaded;
            page = 0;
            buildAndOpen(player);
        })).exceptionally(error -> {
            plugin.getLogger().fine("Sezony admin: odczyt historii nie powiódł się: "
                    + rootMessage(error));
            runOnEntityThread(player, () -> {
                current = EMPTY_PAGE;
                page = 0;
                buildAndOpen(player);
            });
            return null;
        });
    }

    void buildAndOpen(@NotNull Player player) {
        MenuService.Menu menu = newPagerMenu();
        populate(menu, player, current);
        menu.open(player);
    }

    private @NotNull MenuService.Menu newPagerMenu() {
        return menus.ofRows(6, Ui.component(miniMessage, TITLE));
    }

    /**
     * Wypełnia ekran dla zadanej strony — czysta funkcja danych, package-private
     * dla testów layoutu (karty konstruuje się bezpośrednio).
     */
    void populate(@NotNull MenuService.Menu menu, @NotNull Player viewer,
                  @NotNull Page data) {
        Ui.frame(menu, miniMessage, Material.GRAY_STAINED_GLASS_PANE);

        int slot = FIRST_CARD_SLOT;
        for (Card card : data.rows()) {
            menu.decoration(slot++, cardIcon(card));
        }
        while (slot <= LAST_CARD_SLOT) {
            menu.decoration(slot++, fillerIcon());
        }

        boolean hasPrev = data.hasPrev();
        boolean hasNext = data.hasNext();
        menu.set(SLOT_PREV, hasPrev
                        ? Ui.item(Material.ARROW, miniMessage,
                        "<yellow><bold>Poprzednia strona</bold></yellow>",
                        List.of("<gray>Strona " + (data.page() + 1) + " z "
                                + data.pageCount() + ".</gray>"), false)
                        : edgeIcon("Brak nowszych."),
                (who, click) -> {
                    if (hasPrev && page > 0) {
                        page--;
                        refresh(who);
                    }
                });
        menu.set(SLOT_NEXT, hasNext
                        ? Ui.item(Material.ARROW, miniMessage,
                        "<yellow><bold>Następna strona</bold></yellow>",
                        List.of("<gray>Strona " + (data.page() + 2) + " z "
                                + data.pageCount() + ".</gray>"), false)
                        : edgeIcon("Brak starszych."),
                (who, click) -> {
                    if (hasNext && page < data.pageCount() - 1) {
                        page++;
                        refresh(who);
                    }
                });

        menu.close(SLOT_CLOSE_UI, Ui.closeButton(miniMessage));
        menu.set(SLOT_BACK, Ui.backButton(miniMessage), (who, click) -> backAction.accept(who));
    }

    /** Flip strony: dane nowej strony dociągane asynchronicznie (LIMIT/OFFSET). */
    private void refresh(@NotNull Player viewer) {
        int target = page;
        loadPage(target).thenAccept(loaded -> runOnEntityThread(viewer, () -> {
            current = loaded;
            page = target;
            buildAndOpen(viewer);
        })).exceptionally(error -> {
            plugin.getLogger().fine("Sezony admin: przełączenie strony nie powiodło się: "
                    + rootMessage(error));
            return null;
        });
    }

    private @NotNull ItemStack cardIcon(@NotNull Card card) {
        String name = card.displayName() != null ? card.displayName()
                : "Sezon S" + card.seasonId();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Okno: <white>" + windowText(card) + "</white></gray>");
        if (card.winnerNick() != null) {
            lore.add("<gray>Zwycięzca: <white>" + card.winnerNick() + "</white> — <yellow>"
                    + card.winnerPoints() + " pkt</yellow></gray>");
        } else {
            lore.add("<gray>Zwycięzca: <white>—</white></gray>");
        }
        for (RunnerUp place : card.runnerUps()) {
            lore.add("<gray>" + place.position() + ". <white>" + place.nick()
                    + "</white> — <yellow>" + place.points() + " pkt</yellow></gray>");
        }
        if (card.archivedRows() >= 0) {
            lore.add("<gray>Zarchiwizowano: <white>" + card.archivedRows()
                    + "</white> wierszy.</gray>");
        }
        lore.add(closedAtLine(card));
        lore.add("<dark_gray>wewn. id: S" + card.seasonId() + "</dark_gray>");
        return Ui.item(Material.WRITABLE_BOOK, miniMessage,
                "<aqua><bold>" + name + "</bold></aqua>", List.copyOf(lore), false);
    }

    /**
     * Jedno miejsce formatu „Zamknięto”: NULL {@code closed_at} (getLong → 0)
     * oraz legacy 0 zawsze dają marker „(otwarta)” — format stabilny.
     */
    private static @NotNull String closedAtLine(@NotNull Card card) {
        return card.closedAtMillis() > 0
                ? "<gray>Zamknięto: <white>" + dayText(card.closedAtMillis()) + "</white></gray>"
                : "<gray>Zamknięto: <white>—</white> (otwarta)</gray>";
    }

    private @NotNull ItemStack fillerIcon() {
        return Ui.item(Material.GRAY_DYE, miniMessage, "<dark_gray>—</dark_gray>",
                List.of(), false);
    }

    private @NotNull ItemStack edgeIcon(@NotNull String text) {
        return Ui.item(Material.GRAY_DYE, miniMessage,
                "<dark_gray>" + text + "</dark_gray>", List.of(), false);
    }

    private static @NotNull String windowText(@NotNull Card card) {
        String start = prettyDate(card.startDateIso());
        String end = prettyDate(card.endDateIso());
        if (start == null && end == null) {
            return "?";
        }
        return (start != null ? start : "?") + " – " + (end != null ? end : "?");
    }

    /** ISO {@code yyyy-MM-dd} → {@code dd.MM.yyyy}; surowe gdy nieparsowalne. */
    private static @Nullable String prettyDate(@Nullable String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return DAY.format(LocalDate.parse(iso));
        } catch (RuntimeException unparsable) {
            return iso;
        }
    }

    private static @NotNull String dayText(long millis) {
        return DAY.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /**
     * Strona historii: ciężkie wiersze edycji dociągane z bazy przez
     * {@code LIMIT/OFFSET} tylko dla widocznego okna (strony po
     * {@link #PAGE_SIZE}, sort {@code season_id DESC}); sezony sprzed rejestru
     * dochodzą klientowo z małej listy {@code DISTINCT} — fallback bez zmian.
     * Nigdy nie rzuca — błąd SQL daje pustą stronę lub karty fallbackowe.
     */
    @NotNull CompletableFuture<Page> loadPage(int requestedPage) {
        SqlService service = sql;
        if (service == null || !service.isEnabled()) {
            return CompletableFuture.completedFuture(EMPTY_PAGE);
        }
        return service.query(SQL_LEGACY_IDS, rs -> rs.getInt(1))
                .exceptionally(error -> List.of())
                .thenCompose(legacyIds -> buildPage(service, requestedPage, legacyIds));
    }

    /** Uniwersum sezonów (edycje ∪ legacy-only, malejąco) wyznacza okno strony. */
    private @NotNull CompletableFuture<Page> buildPage(@NotNull SqlService service,
                                                       int requestedPage,
                                                       @NotNull List<Integer> legacyIds) {
        return service.query(SQL_EDITION_IDS, rs -> rs.getInt(1))
                .thenApply(SeasonHistoryPagerMenu::sortDescIds)
                .exceptionally(error -> {
                    plugin.getLogger().fine("Sezony admin: tabela edycji niedostępna: "
                            + rootMessage(error));
                    return List.of();
                })
                .thenCompose(editionIds -> assemblePage(service, requestedPage, editionIds,
                        withoutEditions(sortDescIds(legacyIds), editionIds)));
    }

    /**
     * Skleja stronę: wiersze edycji z jednego zapytania {@code LIMIT/OFFSET}
     * (okno edycji jest spójnym fragmentem porządku DESC), resztę okna
     * uzupełniają karty legacy; wzbogacenie tylko dla widocznych kart.
     */
    private @NotNull CompletableFuture<Page> assemblePage(@NotNull SqlService service,
                                                          int requestedPage,
                                                          @NotNull List<Integer> editionIds,
                                                          @NotNull List<Integer> legacyOnly) {
        Set<Integer> universe = new TreeSet<>(Comparator.reverseOrder());
        universe.addAll(editionIds);
        universe.addAll(legacyOnly);
        int total = universe.size();
        int lastPage = Math.max(0, (total - 1) / PAGE_SIZE);
        int safePage = Math.min(Math.max(0, requestedPage), lastPage);
        int from = safePage * PAGE_SIZE;
        List<Integer> window = List.copyOf(universe)
                .subList(from, Math.min(from + PAGE_SIZE, total));

        List<Integer> windowEditions = window.stream()
                .filter(editionIds::contains)
                .toList();
        // obie listy malejąco: offset = pozycja pierwszej edycji okna
        int offset = windowEditions.isEmpty() ? 0 : editionIds.indexOf(windowEditions.get(0));

        CompletableFuture<Map<Integer, Card>> editionRows = windowEditions.isEmpty()
                ? CompletableFuture.completedFuture(new LinkedHashMap<>())
                : service.query(SQL_EDITION_PAGE,
                        rs -> new Card(rs.getInt(1), rs.getString(2), rs.getString(3),
                                rs.getString(4), rs.getString(5), rs.getLong(6),
                                null, Card.UNKNOWN, Card.UNKNOWN, List.of()),
                        windowEditions.size(), offset)
                        .thenApply(this::indexBySeason)
                        .exceptionally(error -> {
                            plugin.getLogger().fine("Sezony admin: tabela edycji niedostępna: "
                                    + rootMessage(error));
                            return new LinkedHashMap<>();
                        });
        return editionRows.thenCompose(index -> {
            List<Card> rows = new ArrayList<>(window.size());
            for (Integer id : window) {
                Card row = index.get(id);
                rows.add(row != null ? row : legacyCard(id));
            }
            return enrich(rows);
        }).thenApply(enriched -> new Page(enriched, safePage, lastPage + 1));
    }

    /** Karta sezonu sprzed rejestru — fallback, bez danych edycji. */
    private static @NotNull Card legacyCard(int seasonId) {
        return new Card(seasonId, null, null, null, null, 0L,
                null, Card.UNKNOWN, Card.UNKNOWN, List.of());
    }

    /** Malejąco, bez duplikatów ({@code DISTINCT} nie gwarantuje kolejności). */
    private static @NotNull List<Integer> sortDescIds(@NotNull List<Integer> ids) {
        return ids.stream().distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }

    /** Legacy-only = sezony historii nieobecne w rejestrze edycji. */
    private static @NotNull List<Integer> withoutEditions(@NotNull List<Integer> ids,
                                                          @NotNull List<Integer> editions) {
        Set<Integer> known = new HashSet<>(editions);
        return ids.stream().filter(id -> !known.contains(id)).toList();
    }

    private @NotNull Map<Integer, Card> indexBySeason(@NotNull List<Card> rows) {
        Map<Integer, Card> index = new LinkedHashMap<>();
        for (Card row : rows) {
            index.put(row.seasonId(), row);
        }
        return index;
    }

    /**
     * Dokłada podium (rang ≤ 3) i liczbę wierszy historii; sortowanie sezon
     * DESC. Nigdy nie rzuca — błąd SQL daje kartę bez wzbogacenia.
     */
    private @NotNull CompletableFuture<List<Card>> enrich(@NotNull List<Card> input) {
        SqlService service = sql;
        if (input.isEmpty() || service == null) {
            return CompletableFuture.completedFuture(sortDesc(input));
        }
        List<CompletableFuture<Card>> enriched = input.stream().map(card -> {
            CompletableFuture<List<Winner>> podium = service.query(
                            "SELECT player_uuid, points FROM wpme_sb_season_history "
                                    + "WHERE season_id = ? AND rank_position <= 3 "
                                    + "ORDER BY rank_position LIMIT 3",
                            rs -> new Winner(UUID.fromString(rs.getString(1)), rs.getLong(2)),
                            card.seasonId())
                    .exceptionally(error -> List.of());
            CompletableFuture<Long> archived = service.query(
                            "SELECT COUNT(*) FROM wpme_sb_season_history WHERE season_id = ?",
                            rs -> rs.getLong(1), card.seasonId())
                    .thenApply(rows -> rows.isEmpty() ? Card.UNKNOWN : rows.get(0))
                    .exceptionally(error -> Card.UNKNOWN);
            return podium.thenCombine(archived, (p, count) -> withEnrichment(card, p, count));
        }).toList();
        return CompletableFuture.allOf(enriched.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> sortDesc(
                        enriched.stream().map(CompletableFuture::join).toList()));
    }

    /**
     * Skleja podium z kartą; niki przez resolver (produkcja:
     * {@code LeaderboardService::nameOf}), fallback na skrócony UUID.
     * Ranga 1 = pola zwycięzcy, rangi 2–3 = lista {@link RunnerUp}.
     */
    private @NotNull Card withEnrichment(@NotNull Card card,
                                         @NotNull List<Winner> podium,
                                         long archivedRows) {
        if (podium.isEmpty()) {
            return archivedRows == card.archivedRows() ? card
                    : withoutWinner(card, archivedRows);
        }
        Winner champion = podium.get(0);
        String nick = resolveNick(champion.playerId());
        List<RunnerUp> runnerUps = new ArrayList<>();
        for (int i = 1; i < podium.size(); i++) {
            Winner w = podium.get(i);
            runnerUps.add(new RunnerUp(i + 1, resolveNick(w.playerId()), w.points()));
        }
        return new Card(card.seasonId(), card.displayName(), card.editionType(),
                card.startDateIso(), card.endDateIso(), card.closedAtMillis(),
                nick, champion.points(), archivedRows, List.copyOf(runnerUps));
    }

    private @NotNull Card withoutWinner(@NotNull Card card, long archivedRows) {
        return new Card(card.seasonId(), card.displayName(), card.editionType(),
                card.startDateIso(), card.endDateIso(), card.closedAtMillis(),
                null, Card.UNKNOWN, archivedRows, List.of());
    }

    private @NotNull String resolveNick(@NotNull UUID playerId) {
        UnaryOperator<String> resolver = nickResolver;
        if (resolver != null) {
            try {
                String nick = resolver.apply(playerId.toString());
                if (nick != null && !nick.isBlank()) {
                    return nick;
                }
            } catch (RuntimeException offline) {
                // spadamy na UUID
            }
        }
        return playerId.toString();
    }

    private static @NotNull List<Card> sortDesc(@NotNull List<Card> input) {
        return input.stream()
                .sorted(Comparator.comparingInt(Card::seasonId).reversed())
                .toList();
    }

    private void runOnEntityThread(@NotNull Player player, @NotNull Runnable task) {
        try {
            var scheduled = player.getScheduler().run(plugin, ignored -> task.run(), null);
            if (scheduled == null) {
                task.run();
            }
        } catch (RuntimeException rejected) {
            task.run();
        }
    }

    private static @NotNull String rootMessage(@NotNull Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
