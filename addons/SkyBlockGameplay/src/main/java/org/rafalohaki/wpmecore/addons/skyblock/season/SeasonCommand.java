package org.rafalohaki.wpmecore.addons.skyblock.season;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * M2.5: komenda {@code /sezon} — własne punkty, pozycja, próg progresji,
 * TOP-10, czas do końca sezonu (spec §4.3).
 *
 * <p><b>Etykiety edycji.</b> Dostawca {@code seasonLabel} zwraca nazwę
 * aktywnej edycji albo legacy zakres dat ({@code null} = bez etykiety).
 * Wariant edition-aware (szósty parametr {@code rangeDetail}) renderuje
 * nagłówek „─── {nazwa} ───” bez podwojonego „Sezon:” oraz opcjonalną
 * szarą linię zakresu (decyzja A1 Q3); gdy edycje są wyłączone, plugin
 * dokłada {@code null} i zachowany jest kształt legacy 1:1.
 */
public final class SeasonCommand {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(SeasonCommand.class.getName());

    /** Uprawnienie operatora — dodaje podpowiedź panelu admina w /sezon. */
    private static final String ADMIN_PERMISSION = "skyblockgameplay.admin";

    private static final String HEADER_PLAIN =
            "<gold><bold>─── Sezon ───</bold></gold>";
    private static final String HEADER_EDITION_AWARE =
            "<gold><bold>─── %s ───</bold></gold>";
    private static final String HEADER_LEGACY_LABEL =
            "<gold><bold>─── Sezon: %s ───</bold></gold>";
    private static final String RANGE_DETAIL_LINE = "<gray>%s</gray>";

    private static final String SEASON_EXPIRED_LINE =
            "<gray>⚠ <white>sezon wygasł — czeka na zamknięcie</white></gray>";
    private static final String DAYS_LEFT_LINE =
            "<gray>Koniec za <white>%d</white> dni.%s";
    private static final String CATCH_UP_SPRINT_SUFFIX =
            " <yellow>AKTUALNIE: +20% punktów (finałowy sprint)!</yellow>";
    private static final String WEEKEND_SUFFIX =
            " <gold><bold>WEEKEND: ×%.1f punktów sezonowych!</bold></gold>";

    /** Weekend ma pierwszeństwo przed sprintem (mnożniki i tak się składają w award). */
    private @NotNull String daysLeftSuffix() {
        if (pointsService.isWeekendBonusActive()) {
            return String.format(WEEKEND_SUFFIX, pointsService.weekendMultiplier());
        }
        return pointsService.isCatchUpActive() ? CATCH_UP_SPRINT_SUFFIX : "";
    }

    /**
     * F24: jedno zdanie „czym to w ogóle jest” — pierwsza rzecz na ekranie po
     * dacie. Gracz, który wpisał /sezon, zwykle nie wie, czym sezon jest ani
     * po co zbiera punkty; sam licznik dni i liczba punktów tego nie mówią.
     *
     * <p>F29: zdanie F24 wymieniało oba źródła punktów jak równorzędne, a nie
     * są. Zadania sezonowe są <b>jednorazowe na sezon</b> (award dedupuje po
     * {@code season-quest:<uuid>:<sezon>:<id>}), więc ich pula to sufit, nie
     * tempo; całą resztę toru karnetu niesie łowienie. Zdanie mówi teraz
     * dokładnie to. Świadomie <b>bez liczb</b>: „8/40” z F24 było usterką
     * dokładnie tego rodzaju — twarda liczba przestała zgadzać się z katalogiem.
     */
    private static final String WHAT_IS_A_SEASON_LINE =
            "<gray>Sezon to wyścig na czas: punkty otwierają poziomy karnetu"
                    + " <dark_gray>(</dark_gray><yellow>/przepustka</yellow><dark_gray>)</dark_gray>."
                    + " <white>Zadania sezonowe</white> niżej robisz <white>raz na sezon</white>"
                    + " — to rozbieg; dalsze poziomy niesie <white>łowienie ryb</white>."
                    + " Nagrody TOP wysp (części kolekcji, tytuły): <yellow>/nagrody</yellow>.</gray>";
    /**
     * F24: było „Odblokowane questy: %d/40”. Liczba 40 to iloczyn progów
     * ({@link DefaultSeasonPointService#UNLOCK_THRESHOLDS} ×
     * {@link DefaultSeasonPointService#SLOTS_PER_TIER}), a nie liczba questów,
     * których w katalogu jest pięć — czyli licznik pokazywał sufit slotów,
     * którego nic nie wypełnia. Zamiast slotów pokazujemy poziom karnetu:
     * to JEST postęp sezonu i to on prowadzi do nagrody.
     */
    /** Migracja Eco: zadania i karnet żyją w EcoQuests/EcoBattlepass — bez „zadań niżej”. */
    private static final String WHAT_IS_A_SEASON_LINE_ECO =
            "<gray>Sezon to wyścig na czas: punkty z zadań dnia i jednorazowych misji sezonu"
                    + " <dark_gray>(</dark_gray><yellow>/zadania</yellow><dark_gray>)</dark_gray> oraz"
                    + " z łowienia ryb liczą się do rankingu i nagród sezonu; karnet z nagrodami:"
                    + " <yellow>/przepustka</yellow>. Nagrody TOP wysp (części kolekcji, tytuły):"
                    + " <yellow>/nagrody</yellow>.</gray>";
    private static final String PERSONAL_POINTS_LINE_ECO =
            "<gray>Twoje punkty: <white><bold>%d</bold></white></gray>";
    private static final String PERSONAL_POINTS_LINE =
            "<gray>Twoje punkty: <white><bold>%d</bold></white>"
                    + " <dark_gray>|</dark_gray> Poziom karnetu: <white>%d/%d</white>";
    private static final String NEXT_PASS_LEVEL_LINE =
            "<gray>Do następnego poziomu karnetu: <white>%d</white> pkt</gray>";
    private static final String BONUS_PASS_LEVEL_LINE =
            "<green>Karnet 28/28 + <white>%d</white> bonus — kolejny poziom bonusowy za <white>%d</white> pkt:"
                    + " <yellow>/przepustka</yellow></green>";
    private static final String MAX_PASS_LEVEL_LINE =
            "<green>Najwyższy poziom karnetu osiągnięty — odbierz nagrody:"
                    + " <yellow>/przepustka</yellow></green>";
    private static final String PERSONAL_RANK_LINE =
            "<gray>Twoja pozycja: <white><bold>#%d</bold></white></gray>";
    private static final String NO_POINTS_YET_LINE =
            "<gray>Nie masz jeszcze punktów — zacznij od zadania sezonowego z listy niżej.</gray>";
    private static final String NO_POINTS_YET_LINE_ECO =
            "<gray>Nie masz jeszcze punktów — otwórz <yellow>/zadania</yellow>"
                    + " i zrób zadanie dnia albo misję sezonu.</gray>";

    /**
     * F24: lista zadań sezonowych. To jedyne źródło punktów, jakie gracz może
     * wykonać na życzenie, a do tej pory <b>nie było jej nigdzie</b>: katalog
     * (config.yml → season.quests) czytał wyłącznie listener zliczający, żaden
     * ekran go nie renderował. /sezon pisał „wykonaj quest sezonowy”, nie mówiąc
     * ani którym, ani ile brakuje. Odczyt jest w całości z pamięci
     * (katalog + mapa postępu), więc nie dokłada zapytań do bazy.
     */
    private static final String SEASON_QUESTS_HEADER =
            "<gold><bold>Zadania sezonowe:</bold></gold>"
                    + " <dark_gray>(liczą się przez cały sezon, nie tylko dziś)</dark_gray>";
    private static final String SEASON_QUEST_DONE =
            "<dark_gray> •</dark_gray> <green>✔</green> <gray>%s</gray>"
                    + " <dark_gray>— zrobione</dark_gray>";
    private static final String SEASON_QUEST_OPEN =
            "<dark_gray> •</dark_gray> <yellow>▶</yellow> <white>%s</white>"
                    + " <gray>%d/%d</gray> <dark_gray>(+%d pkt)</dark_gray>";
    private static final String SEASON_QUEST_LOCKED =
            "<dark_gray> • 🔒 %s — otwiera się przy %d pkt</dark_gray>";
    /**
     * Questy eventu (pakiet aktywnej edycji): osobna sekcja pod slotami, bez
     * progów, widoczna tylko w oknie edycji — {@link SeasonQuestService#eventQuests}
     * zwraca pustą listę poza nim, więc sekcja znika sama.
     */
    private static final String EVENT_QUESTS_HEADER =
            "<light_purple><bold>Zadania eventu:</bold></light_purple>"
                    + " <dark_gray>(bez progów, tylko w czasie trwania edycji)</dark_gray>";
    private static final String EVENT_QUEST_TAG = "<light_purple><bold>EVENT</bold></light_purple> ";

    private static final String TOP_HEADER_LINE =
            "<gold><bold>TOP-%d sezonu:</bold></gold>";
    private static final String TOP_ENTRY_LINE =
            "<gray>%d. <white>%s</white> — <yellow>%d pkt</yellow></gray>";
    private static final String EMPTY_RANKING_LINE =
            "<dark_gray>Ranking sezonowy jeszcze pusty.</dark_gray>";
    /** P1-3: ranking wysp = suma punktów sezonowych członków (Skyllia nie zna wartości wyspy). */
    private static final String ISLAND_TOP_HEADER_LINE =
            "<gold><bold>TOP-%d wysp:</bold></gold> <dark_gray>(suma punktów członków)</dark_gray>";
    private static final String ISLAND_TOP_ENTRY_LINE =
            "<gray>%d. <white>Wyspa %s</white> — <yellow>%d pkt</yellow> <dark_gray>(%d %s)</dark_gray></gray>";
    private static final int ISLAND_TOP_LIMIT = 5;
    private static final int ISLAND_TOP_SAMPLE = 200;

    private static final String ADMIN_HINT_LINE =
            "<dark_gray>Panel operatora: /sezon admin</dark_gray>";

    private final SeasonPointService pointsService;
    private final java.util.function.LongSupplier seasonEndMillis;
    /** Etykieta sezonu: nazwa edycji albo legacy zakres dat (null = bez etykiety). */
    private final java.util.function.Supplier<String> seasonLabel;
    /**
     * Opcjonalny szczegół zakresu dla nagłówka edition-aware ({@code null} =
     * tryb legacy). Nie-null dostawca włącza kształt „─── {nazwa} ───”
     * (decyzja A1 Q3); gdy zwróci {@code null}/pusty, linia zakresu znika,
     * a nagłówek pozostaje w formie nazwy.
     */
    @Nullable private final java.util.function.Supplier<String> rangeDetail;
    @NotNull private final java.util.function.Function<UUID, String> nameResolver;
    private final MiniMessage miniMessage;
    /**
     * F24: katalog i postęp zadań sezonowych do wypisania pod sekcją osobistą.
     * {@code null} = tryb bez listy (testy renderu, sezon bez questów).
     */
    @Nullable private final SeasonQuestService quests;
    /** gracz → migawka wyspy (właściciel do nazwy); {@code null} = bez sekcji wysp (testy, brak Skyllii). */
    @Nullable private java.util.function.Function<UUID, java.util.Optional<
            org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot>> islandResolver;

    /** Włącza sekcję TOP wysp pod TOP graczy. Zwraca {@code this}, żeby wpiąć się w istniejące wywołania. */
    public @NotNull SeasonCommand withIslandResolver(@NotNull java.util.function.Function<UUID, java.util.Optional<
            org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot>> resolver) {
        this.islandResolver = resolver;
        return this;
    }

    public SeasonCommand(@NotNull SeasonPointService pointsService,
                         @NotNull java.util.function.LongSupplier seasonEndMillis,
                         @NotNull java.util.function.Supplier<String> seasonLabel,
                         @NotNull MiniMessage miniMessage) {
        this(pointsService, seasonEndMillis, seasonLabel, miniMessage, LeaderboardService::nameOf);
    }

    /**
     * TOP-10 zna tylko UUID-y — nicki dokłada resolver. Produkcja używa
     * tego samego mechanizmu co tablice wyników: {@code getOfflinePlayer}
     * (lokalny usercache serwera), z fallbackiem do skrótu UUID dla graczy
     * offline bez wpisu. Lookup jest odczytem z pamięci — wywoływany w
     * istniejącym łańcuchu {@code thenAccept} poza wątkiem komendy (Folia).
     */
    public SeasonCommand(@NotNull SeasonPointService pointsService,
                         @NotNull java.util.function.LongSupplier seasonEndMillis,
                         @NotNull java.util.function.Supplier<String> seasonLabel,
                         @NotNull MiniMessage miniMessage,
                         @NotNull java.util.function.Function<UUID, String> nameResolver) {
        this(pointsService, seasonEndMillis, seasonLabel, miniMessage, nameResolver, null, null);
    }

    /**
     * Wariant edition-aware: nie-null {@code rangeDetail} włącza nagłówek
     * „─── {nazwa} ───” z szarą linią zakresu pod spodem (decyzja A1 Q3).
     * {@code null} = kształt legacy „─── Sezon: {etykieta} ───” bez zmian.
     */
    public SeasonCommand(@NotNull SeasonPointService pointsService,
                         @NotNull java.util.function.LongSupplier seasonEndMillis,
                         @NotNull java.util.function.Supplier<String> seasonLabel,
                         @NotNull MiniMessage miniMessage,
                         @NotNull java.util.function.Function<UUID, String> nameResolver,
                         @Nullable java.util.function.Supplier<String> rangeDetail) {
        this(pointsService, seasonEndMillis, seasonLabel, miniMessage, nameResolver,
                rangeDetail, null);
    }

    /**
     * F24: wariant z listą zadań sezonowych. Nie-null {@code quests} dokłada pod
     * sekcją osobistą wykaz zadań z postępem — jedyne miejsce w całej grze, gdzie
     * gracz może zobaczyć, CO właściwie daje punkty sezonowe.
     */
    public SeasonCommand(@NotNull SeasonPointService pointsService,
                         @NotNull java.util.function.LongSupplier seasonEndMillis,
                         @NotNull java.util.function.Supplier<String> seasonLabel,
                         @NotNull MiniMessage miniMessage,
                         @NotNull java.util.function.Function<UUID, String> nameResolver,
                         @Nullable java.util.function.Supplier<String> rangeDetail,
                         @Nullable SeasonQuestService quests) {
        this.quests = quests;
        this.pointsService = pointsService;
        this.seasonEndMillis = seasonEndMillis;
        this.miniMessage = miniMessage;
        this.seasonLabel = seasonLabel;
        this.nameResolver = nameResolver;
        this.rangeDetail = rangeDetail;
    }

    public void show(@NotNull CommandSender sender) {
        long end = seasonEndMillis.getAsLong();
        long now = System.currentTimeMillis();
        boolean expired = now > end;
        long daysLeft = SeasonSchedule.daysUntil(end, now);

        String label = seasonLabel.get();
        if (label != null && label.isEmpty()) {
            label = null; // publicHeaderLabel zwraca „”, gdy nic nie rozstrzyga
        }
        String range = rangeDetail == null ? null : rangeDetail.get();
        if (range != null && range.isEmpty()) {
            range = null;
        }
        if (label == null) {
            sender.sendMessage(miniMessage.deserialize(HEADER_PLAIN));
        } else if (rangeDetail != null) {
            // Edition-aware: sama nazwa, bez podwojonego „Sezon: Sezon …”.
            sender.sendMessage(miniMessage.deserialize(
                    String.format(HEADER_EDITION_AWARE, label)));
        } else {
            sender.sendMessage(miniMessage.deserialize(
                    String.format(HEADER_LEGACY_LABEL, label)));
        }
        if (range != null) {
            sender.sendMessage(miniMessage.deserialize(
                    String.format(RANGE_DETAIL_LINE, range)));
        }
        sender.sendMessage(miniMessage.deserialize(expired
                ? SEASON_EXPIRED_LINE
                : String.format(DAYS_LEFT_LINE, Math.max(0, daysLeft), daysLeftSuffix())));

        if (!(sender instanceof Player player)) {
            top(sender, 10);
            return;
        }
        // F24: zdanie „czym to jest” idzie WYŁĄCZNIE do gracza — konsola i RCON
        // czytają /sezon jako raport, nie jako samouczek, a ich kształt wyjścia
        // jest kontraktem testów operatorskich (SeasonDayCountTest).
        sender.sendMessage(miniMessage.deserialize(migrated() ? WHAT_IS_A_SEASON_LINE_ECO : WHAT_IS_A_SEASON_LINE));
        if (player.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(miniMessage.deserialize(ADMIN_HINT_LINE));
        }
        UUID uuid = player.getUniqueId();

        // Sekcje osobista / pozycja / TOP są niezależnymi łańcuchami:
        // wyjątek w jednej NIE tłumi pozostałych (incydent V1: martwy
        // thenAccept połykał resztę renderu bez śladu w logu).
        pointsService.pointsOf(uuid).thenAccept(points -> {
            int passLevel = passLevel(points);
            sender.sendMessage(miniMessage.deserialize(migrated()
                    ? String.format(PERSONAL_POINTS_LINE_ECO, points)
                    : String.format(PERSONAL_POINTS_LINE,
                    points, Math.min(passLevel, SeasonPassService.PILOT_LEVELS), SeasonPassService.PILOT_LEVELS)));

            if (!migrated()) {

            long toNext = SeasonPassService.pointsForLevel(passLevel + 1) - points;
            if (passLevel >= SeasonPassService.PILOT_LEVELS) {
                // P2-1: po L28 punkty nie są martwe — poziomy bonusowe co 500 pkt.
                sender.sendMessage(miniMessage.deserialize(String.format(BONUS_PASS_LEVEL_LINE,
                        passLevel - SeasonPassService.PILOT_LEVELS, toNext)));
            } else {
                sender.sendMessage(miniMessage.deserialize(
                        String.format(NEXT_PASS_LEVEL_LINE, toNext)));
            }

            }
            seasonQuests(sender, uuid, points);
        }).exceptionally(failure -> {
            LOG.log(java.util.logging.Level.WARNING,
                    "[sezon] sekcja 'Twoje punkty' nie wyrenderowana dla "
                            + uuid, failure);
            return null;
        });

        pointsService.rankOf(uuid).thenAccept(rank ->
                rank.ifPresentOrElse(
                        r -> sender.sendMessage(miniMessage.deserialize(
                                String.format(PERSONAL_RANK_LINE, r))),
                        () -> sender.sendMessage(miniMessage.deserialize(
                                migrated() ? NO_POINTS_YET_LINE_ECO : NO_POINTS_YET_LINE))))
        .exceptionally(failure -> {
            LOG.log(java.util.logging.Level.WARNING,
                    "[sezon] sekcja 'Twoja pozycja' nie wyrenderowana dla "
                            + uuid, failure);
            return null;
        });

        top(sender, 10);
    }

    /**
     * {@code /is top}: renderuje wyłącznie blok TOP-N (bez nagłówka sezonu
     * i sekcji osobistych) — ten sam kształt co ogon {@link #show}.
     */
    public void showTop(@NotNull CommandSender sender, int limit) {
        top(sender, limit);
    }

    private void top(@NotNull CommandSender sender, int limit) {
        pointsService.top(limit).thenAccept(top -> {
            if (top.isEmpty()) {
                sender.sendMessage(miniMessage.deserialize(EMPTY_RANKING_LINE));
                return;
            }
            sender.sendMessage(miniMessage.deserialize(
                    String.format(TOP_HEADER_LINE, top.size())));
            for (int i = 0; i < top.size(); i++) {
                var entry = top.get(i);
                String name;
                try {
                    name = nameResolver.apply(entry.playerId());
                } catch (RuntimeException failure) {
                    LOG.log(java.util.logging.Level.WARNING,
                            "[sezon] nameResolver rzucił dla " + entry.playerId()
                                    + " — fallback na skrócony identyfikator", failure);
                    name = null;
                }
                // Zły lookup nigdy nie może ubić pętli TOP (NPE/blank).
                if (name == null || name.isBlank()) {
                    name = entry.playerId().toString().substring(0, 8) + "…";
                }
                sender.sendMessage(miniMessage.deserialize(
                        String.format(TOP_ENTRY_LINE, i + 1, name, entry.points())));
            }
            renderIslandTop(sender);
        }).exceptionally(failure -> {
            LOG.log(java.util.logging.Level.WARNING,
                    "[sezon] sekcja TOP-" + limit + " nie wyrenderowana", failure);
            return null;
        });
    }

    /** Poziom karnetu z punktów — ta sama arytmetyka co {@link SeasonPassService#levelOf}, bez zapytania. */
    /** Agreguje próbkę TOP graczy po wyspie; wyspa bez członków w próbce nie istnieje w rankingu. */
    static @NotNull List<IslandRow> aggregateIslands(
            @NotNull List<? extends SeasonPointDao.TopRow> rows,
            @NotNull java.util.function.Function<UUID, java.util.Optional<
                    org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot>> resolver,
            int limit) {
        java.util.Map<UUID, IslandRow> byIsland = new java.util.LinkedHashMap<>();
        for (SeasonPointDao.TopRow row : rows) {
            var snapshot = resolver.apply(row.playerId()).orElse(null);
            if (snapshot == null) {
                continue;
            }
            byIsland.merge(snapshot.islandId(),
                    new IslandRow(snapshot.islandId(), snapshot.ownerId(), row.points(), 1),
                    (a, b) -> new IslandRow(a.islandId(), a.ownerId(), a.points() + b.points(), a.members() + b.members()));
        }
        return byIsland.values().stream()
                .sorted(java.util.Comparator.comparingLong(IslandRow::points).reversed())
                .limit(limit)
                .toList();
    }

    record IslandRow(@NotNull UUID islandId, @NotNull UUID ownerId, long points, int members) { }

    private void renderIslandTop(@NotNull CommandSender sender) {
        var resolver = this.islandResolver;
        if (resolver == null) {
            return;
        }
        pointsService.top(ISLAND_TOP_SAMPLE).thenAccept(sample -> {
            List<IslandRow> islands = aggregateIslands(sample, resolver, ISLAND_TOP_LIMIT);
            if (islands.isEmpty()) {
                return;
            }
            sender.sendMessage(miniMessage.deserialize(String.format(ISLAND_TOP_HEADER_LINE, islands.size())));
            for (int i = 0; i < islands.size(); i++) {
                IslandRow island = islands.get(i);
                String owner;
                try {
                    owner = nameResolver.apply(island.ownerId());
                } catch (RuntimeException failure) {
                    owner = null;
                }
                if (owner == null || owner.isBlank()) {
                    owner = island.ownerId().toString().substring(0, 8) + "…";
                }
                String noun = island.members() == 1 ? "gracz" : island.members() < 5 ? "graczy" : "graczy";
                sender.sendMessage(miniMessage.deserialize(String.format(ISLAND_TOP_ENTRY_LINE,
                        i + 1, owner, island.points(), island.members(), noun)));
            }
        }).exceptionally(failure -> {
            LOG.log(java.util.logging.Level.WARNING, "[sezon] sekcja TOP wysp nie wyrenderowana", failure);
            return null;
        });
    }

    private static int passLevel(long points) {
        return SeasonPassService.levelFor(points);
    }

    /**
     * Wykaz zadań sezonowych z postępem. Odczyt wyłącznie z pamięci (katalog
     * z configu + mapa postępu wczytana przy starcie), więc mieści się w tym
     * samym łańcuchu co punkty i nie dokłada zapytania.
     *
     * <p>Zadanie zablokowane progiem punktowym pokazuje próg, a nie postęp —
     * inaczej gracz liczyłby na licznik, którego nic nie rusza
     * ({@link SeasonQuestService#unlockedFor} odrzuca zdarzenia ponad limitem).
     */
    private boolean migrated() {
        return this.quests != null && this.quests.isMigrated();
    }

    private void seasonQuests(@NotNull CommandSender sender, @NotNull UUID uuid, long points) {
        SeasonQuestService service = this.quests;
        if (service == null) {
            return;
        }
        List<SeasonQuestService.Definition> catalog = service.catalog();
        List<SeasonQuestService.Definition> event = service.eventQuests();
        if (catalog.isEmpty() && event.isEmpty()) {
            return;
        }
        sender.sendMessage(miniMessage.deserialize(SEASON_QUESTS_HEADER));
        int unlocked = pointsService.unlockedQuestSlots(points);
        for (int index = 0; index < catalog.size(); index++) {
            SeasonQuestService.Definition def = catalog.get(index);
            if (index >= unlocked) {
                sender.sendMessage(miniMessage.deserialize(String.format(SEASON_QUEST_LOCKED,
                        def.name(), unlockThresholdForIndex(index))));
                continue;
            }
            sender.sendMessage(questLine(service, uuid, def, def.name()));
        }
        if (event.isEmpty()) {
            return;
        }
        sender.sendMessage(miniMessage.deserialize(EVENT_QUESTS_HEADER));
        for (SeasonQuestService.Definition def : event) {
            sender.sendMessage(questLine(service, uuid, def, EVENT_QUEST_TAG + def.name()));
        }
    }

    /** Linia otwartego/zrobionego zadania z postępem; {@code label} = nazwa z ewentualnym tagiem. */
    private @NotNull Component questLine(@NotNull SeasonQuestService service, @NotNull UUID uuid,
                                         @NotNull SeasonQuestService.Definition def,
                                         @NotNull String label) {
        int progress = service.progressOf(uuid, def.id());
        return miniMessage.deserialize(progress >= def.goal()
                ? String.format(SEASON_QUEST_DONE, label)
                : String.format(SEASON_QUEST_OPEN, label, progress, def.goal(), def.points()));
    }

    /**
     * Próg punktowy otwierający zadanie o danym indeksie w katalogu. Ostatni próg
     * obowiązuje dla wszystkiego powyżej — katalog nie może być dłuższy niż
     * łączna liczba slotów ({@code SeasonQuestCatalog.load} pilnuje tego przy starcie).
     */
    private static long unlockThresholdForIndex(int index) {
        long[] thresholds = DefaultSeasonPointService.UNLOCK_THRESHOLDS;
        int tier = Math.min(index / DefaultSeasonPointService.SLOTS_PER_TIER[0],
                thresholds.length - 1);
        return thresholds[tier];
    }
}
