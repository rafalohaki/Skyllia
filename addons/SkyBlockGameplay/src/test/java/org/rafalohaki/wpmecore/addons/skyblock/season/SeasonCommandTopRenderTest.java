package org.rafalohaki.wpmecore.addons.skyblock.season;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.rafalohaki.wpmecore.addons.skyblock.season.SeasonPointDao.TopRow;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2#1: TOP-10 w /sezon ma pokazywać nicki zamiast prefiksów UUID.
 * Resolver mockowany — testuje wyłącznie rendering komendy.
 */
class SeasonCommandTopRenderTest {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Statyczny serwis punktowy: gotowe odpowiedzi, zero Bukkit. */
    private static final class StubPoints implements SeasonPointService {
        private final List<TopRow> rows;

        StubPoints(List<TopRow> rows) {
            this.rows = rows;
        }

        @Override public int currentSeason() { return 3; }

        @Override public @NotNull CompletableFuture<Boolean> award(
                @NotNull UUID u, long p, @NotNull String op) {
            return CompletableFuture.completedFuture(true);
        }

        @Override public @NotNull CompletableFuture<Long> pointsOf(@NotNull UUID u) {
            return CompletableFuture.completedFuture(100L);
        }

        @Override public @NotNull CompletableFuture<Optional<Integer>> rankOf(@NotNull UUID u) {
            return CompletableFuture.completedFuture(Optional.of(1));
        }

        @Override public @NotNull CompletableFuture<List<TopRow>> top(int limit) {
            return CompletableFuture.completedFuture(rows.stream().limit(limit).toList());
        }

        @Override public int unlockedQuestSlots(long seasonPoints) { return 8; }

        @Override public boolean isCatchUpActive() { return false; }
    }

    /** Uruchamia /sezon i zwraca wszystkie wysłane komponenty. */
    private static List<Component> render(List<TopRow> rows,
                                          Function<UUID, String> resolver) {
        return renderAt(rows, resolver,
                System.currentTimeMillis() + 86_400_000L,
                "01.09.2026 – 27.10.2026");
    }

    /** Wariant z kontrolowanym końcem sezonu i etykietą datową. */
    private static List<Component> renderAt(List<TopRow> rows,
                                            Function<UUID, String> resolver,
                                            long seasonEndMillis,
                                            String seasonLabel) {
        Player sender = mock(Player.class);
        when(sender.getUniqueId()).thenReturn(UUID.randomUUID());

        SeasonCommand cmd = new SeasonCommand(new StubPoints(rows),
                () -> seasonEndMillis, () -> seasonLabel, MM, resolver);
        cmd.show(sender);

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        // F24: +1 wiersz — zdanie „czym jest sezon” dla gracza (bez listy zadań,
        // bo ten wariant konstruktora nie dostaje SeasonQuestService).
        verify(sender, times(7 + rows.size())).sendMessage(captor.capture());
        return captor.getAllValues();
    }

    /** Skleja wiersze TOP (po 6 liniach nagłówka) do postaci tekstu. */
    private static String renderedTop(List<Component> messages) {
        StringBuilder out = new StringBuilder();
        // F24: przed blokiem TOP idzie sześć wierszy (nagłówek, dni, „czym jest
        // sezon”, punkty, poziom karnetu, pozycja) — było pięć.
        for (Component c : messages.subList(6, messages.size())) {
            out.append(MM.serialize(c)).append('\n');
        }
        return out.toString();
    }

    /** Tak działa fallback produkcji (LeaderboardService.nameOf). */
    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    // ── F24: wykaz zadań sezonowych w /sezon ────────────────────────────

    /** Punkty sterowane w teście: stała wartość i stała liczba otwartych slotów. */
    private static SeasonPointService pointsWith(long points, int openSlots) {
        return new SeasonPointService() {
            @Override public int currentSeason() { return 1; }
            @Override public @NotNull CompletableFuture<Boolean> award(
                    @NotNull UUID u, long p, @NotNull String op) {
                return CompletableFuture.completedFuture(true);
            }
            @Override public @NotNull CompletableFuture<Long> pointsOf(@NotNull UUID u) {
                return CompletableFuture.completedFuture(points);
            }
            @Override public @NotNull CompletableFuture<Optional<Integer>> rankOf(@NotNull UUID u) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            @Override public @NotNull CompletableFuture<List<TopRow>> top(int limit) {
                return CompletableFuture.completedFuture(List.of());
            }
            @Override public int unlockedQuestSlots(long seasonPoints) { return openSlots; }
            @Override public boolean isCatchUpActive() { return false; }
        };
    }

    /** Postęp questów tylko w pamięci — komenda i tak go nie zapisuje. */
    private static SeasonQuestProgressDao memoryProgressDao() {
        return new SeasonQuestProgressDao() {
            @Override public @NotNull CompletableFuture<Map<UUID, Map<String, Integer>>> load(
                    int seasonId) {
                return CompletableFuture.completedFuture(Map.of());
            }
            @Override public @NotNull CompletableFuture<Void> save(int seasonId,
                    @NotNull UUID playerUuid, @NotNull String questId, int progress) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static SeasonQuestService.Definition breakQuest(String id, String target,
                                                            int goal, long pts, String name) {
        return new SeasonQuestService.Definition(id, SeasonQuestService.Definition.Type.BREAK,
                java.util.Set.of(target), goal, pts, false,
                org.bukkit.Material.STONE_PICKAXE, name, List.of());
    }

    /**
     * F24: zadania sezonowe to jedyne źródło punktów, które gracz może wykonać
     * na żądanie — a przed tą falą nie było ich widać NIGDZIE: katalog
     * z config.yml czytał wyłącznie listener zliczający, żaden ekran go nie
     * renderował, a /sezon pisał „wykonaj quest sezonowy”, nie mówiąc którym.
     * Wykaz musi pokazać postęp otwartych i próg zamkniętych.
     */
    @Test
    void seasonQuestsAreListedWithProgressAndLockThreshold() {
        UUID uuid = UUID.randomUUID();
        Player sender = mock(Player.class);
        when(sender.getUniqueId()).thenReturn(uuid);

        // Produkcyjna arytmetyka progów: przy 0 pkt otwarty jest pierwszy tier,
        // czyli osiem slotów (DefaultSeasonPointService.SLOTS_PER_TIER), więc
        // dziewiąte zadanie czeka na próg 300 pkt.
        SeasonPointService points = pointsWith(0L,
                DefaultSeasonPointService.SLOTS_PER_TIER[0]);
        List<SeasonQuestService.Definition> catalog = new java.util.ArrayList<>();
        catalog.add(breakQuest("sq_open", "STONE", 4, 50L, "Górnik sezonu"));
        for (int i = 1; i < DefaultSeasonPointService.SLOTS_PER_TIER[0]; i++) {
            catalog.add(breakQuest("sq_fill" + i, "GRAVEL", 4, 50L, "Wypełniacz " + i));
        }
        catalog.add(breakQuest("sq_locked", "DIRT", 4, 50L, "Kopacz ziemi"));
        SeasonQuestService quests = new SeasonQuestService(points, catalog,
                memoryProgressDao(),
                java.util.logging.Logger.getLogger("SeasonCommandTopRenderTest"));
        quests.refreshPointsCache(uuid);
        quests.recordBreak(uuid, "STONE");

        new SeasonCommand(points, () -> System.currentTimeMillis() + 86_400_000L,
                () -> "01.09.2026 – 27.10.2026", MM,
                SeasonCommandTopRenderTest::shortId, null, quests).show(sender);

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        String all = captor.getAllValues().stream()
                .map(MM::serialize).reduce("", (a, b) -> a + b + '\n');

        assertTrue(all.contains("Zadania sezonowe"), all);
        assertTrue(all.contains("Górnik sezonu"), "otwarte zadanie musi być na liście: " + all);
        assertTrue(all.contains("1/4"), "otwarte zadanie pokazuje postęp: " + all);
        assertTrue(all.contains("Kopacz ziemi"), "zamknięte zadanie też musi być widoczne: " + all);
        assertTrue(all.contains("otwiera się przy 300 pkt"),
                "zamknięte zadanie mówi próg, a nie postęp, którego nic nie rusza: " + all);
        assertFalse(all.contains("/40"),
                "sufit slotów odblokowań nie może udawać liczby questów: " + all);
    }

    /**
     * Questy eventu (pakiet aktywnej edycji) nie mapują się na sloty: przy pełnym
     * pierwszym tierze i 0 pkt slotowy dziewiąty quest jest zamknięty, a eventowy
     * pokazuje postęp z tagiem EVENT. Poza oknem edycji dostawca zwraca pustą
     * listę i sekcja znika bez restartu.
     */
    @Test
    void eventQuestsAreTaggedAndShownOnlyWhileEditionIsActive() {
        UUID uuid = UUID.randomUUID();
        Player sender = mock(Player.class);
        when(sender.getUniqueId()).thenReturn(uuid);

        SeasonPointService points = pointsWith(0L,
                DefaultSeasonPointService.SLOTS_PER_TIER[0]);
        List<SeasonQuestService.Definition> catalog = new java.util.ArrayList<>();
        for (int i = 0; i < DefaultSeasonPointService.SLOTS_PER_TIER[0]; i++) {
            catalog.add(breakQuest("sq_fill" + i, "GRAVEL", 4, 50L, "Wypełniacz " + i));
        }
        catalog.add(breakQuest("sq_locked", "DIRT", 4, 50L, "Kopacz ziemi"));
        java.util.concurrent.atomic.AtomicReference<List<SeasonQuestService.Definition>> event =
                new java.util.concurrent.atomic.AtomicReference<>(
                        List.of(breakQuest("sq_event", "PUMPKIN", 4, 150L, "Jesienne żniwa")));
        SeasonQuestService quests = new SeasonQuestService(points, catalog, event::get,
                memoryProgressDao(),
                java.util.logging.Logger.getLogger("SeasonCommandTopRenderTest"));
        quests.refreshPointsCache(uuid);
        quests.recordBreak(uuid, "PUMPKIN");
        SeasonCommand cmd = new SeasonCommand(points, () -> System.currentTimeMillis() + 86_400_000L,
                () -> "03.10.2026 – 16.10.2026", MM,
                SeasonCommandTopRenderTest::shortId, null, quests);

        cmd.show(sender);
        String inWindow = rendered(sender);
        assertTrue(inWindow.contains("Zadania eventu"), inWindow);
        assertTrue(inWindow.contains("EVENT"), "quest eventu ma wyraźny tag EVENT: " + inWindow);
        assertTrue(inWindow.contains("Jesienne żniwa</white> <gray>1/4"),
                "quest eventu liczy się od razu, bez progu: " + inWindow);
        assertTrue(inWindow.contains("Kopacz ziemi — otwiera się przy 300 pkt"),
                "slotowy dziewiąty quest nadal zamknięty progiem: " + inWindow);

        event.set(List.of()); // koniec okna edycji
        Player later = mock(Player.class);
        when(later.getUniqueId()).thenReturn(uuid);
        cmd.show(later);
        String outside = rendered(later);
        assertTrue(outside.contains("Zadania sezonowe"), outside);
        assertFalse(outside.contains("EVENT"), "poza edycją sekcji eventu nie ma: " + outside);
        assertFalse(outside.contains("Jesienne żniwa"), outside);
    }

    private static String rendered(Player sender) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .map(MM::serialize).reduce("", (a, b) -> a + b + '\n');
    }

    @Test
    void rendersResolvedNicknamesInsteadOfUuidPrefixes() {
        UUID notch = UUID.randomUUID();
        UUID herobrine = UUID.randomUUID();
        Map<UUID, String> known = Map.of(notch, "Notch", herobrine, "Herobrine");

        List<Component> msgs = render(
                List.of(new TopRow(notch, 500L), new TopRow(herobrine, 300L)),
                known::get);
        String board = renderedTop(msgs);

        assertTrue(board.contains("1. <white>Notch</white>"), board);
        assertTrue(board.contains("2. <white>Herobrine</white>"), board);
    }

    @Test
    void unknownOfflinePlayersFallBackToShortenedUuid() {
        UUID ghost = UUID.randomUUID();

        // Produkcja: LeaderboardService.nameOf zwraca prefiks UUID bez wpisu w usercache.
        List<Component> msgs = render(
                List.of(new TopRow(ghost, 42L)),
                SeasonCommandTopRenderTest::shortId);
        String board = renderedTop(msgs);

        assertTrue(board.contains("1. <white>" + shortId(ghost) + "</white>"), board);
    }

    @Test
    void mixedBoardResolvesKnownAndFallsBackForUnknown() {
        UUID known = UUID.randomUUID();
        UUID offline = UUID.randomUUID();

        List<Component> msgs = render(
                List.of(new TopRow(known, 900L), new TopRow(offline, 100L)),
                id -> id.equals(known) ? "Notch" : shortId(id));
        String board = renderedTop(msgs);
        assertTrue(board.contains("<white>Notch</white> — <yellow>900 pkt"), board);
        assertTrue(board.contains("<white>" + shortId(offline)
                + "</white> — <yellow>100 pkt"), board);
        assertEquals(2, board.split("\n").length - 1, "dwa wiersze TOP");
    }

    @Test
    void headerShowsDateRangeInsteadOfSeasonNumber() {
        List<Component> msgs = render(
                List.of(new TopRow(UUID.randomUUID(), 500L)),
                SeasonCommandTopRenderTest::shortId);

        String header = MM.serialize(msgs.get(0));
        assertTrue(header.contains("─── Sezon: 01.09.2026 – 27.10.2026 ───"), header);
        assertFalse(header.contains("#"), "zero numerków sezonu w UI");
        // StubPoints.currentSeason() == 3 nie może nigdzie wypłynąć
        for (Component c : msgs) {
            assertFalse(MM.serialize(c).contains("#3"), "numerek sezonu graczu-widoczny");
        }
    }

    @Test
    void expiredSeasonShowsAwaitingOperatorClose() {
        List<Component> msgs = renderAt(
                List.of(new TopRow(UUID.randomUUID(), 10L)), SeasonCommandTopRenderTest::shortId,
                System.currentTimeMillis() - 3 * 86_400_000L,
                "01.09.2026 – 27.10.2026");

        String status = MM.serialize(msgs.get(1));
        assertTrue(status.contains("sezon wygasł — czeka na zamknięcie"), status);
        assertFalse(status.contains("Koniec za"), status);
    }

    @Test
    void missingCalendarFallsBackToHeaderWithoutRange() {
        List<Component> msgs = renderAt(
                List.of(new TopRow(UUID.randomUUID(), 10L)), SeasonCommandTopRenderTest::shortId,
                System.currentTimeMillis() + 86_400_000L, null);

        String header = MM.serialize(msgs.get(0));
        assertTrue(header.contains("─── Sezon ───"), header);
        assertFalse(header.contains("#"), header);
    }

    /** Incydent V1: resolver rzuca — sekcje osobista/pozycja/TOP muszą przeżyć. */
    @Test
    void throwingResolverCannotSuppressAnySection() {
        UUID ghost = UUID.randomUUID();

        List<Component> msgs = render(
                List.of(new TopRow(ghost, 77L)),
                id -> { throw new IllegalStateException("symulowany awaryjny lookup"); });
        String board = renderedTop(msgs);

        // times(6 + rows) w render() już przeszedł = zero zjedzonych wiadomości.
        assertTrue(board.contains("1. <white>" + shortId(ghost) + "…</white>"), board);
    }

    /** Resolver zwraca null/pusty — pętla TOP nie może dostać NPE ani „null”. */
    @Test
    void nullOrBlankResolverFallsBackToTruncatedUuid() {
        UUID ghost = UUID.randomUUID();

        List<Component> msgs = render(
                List.of(new TopRow(ghost, 5L), new TopRow(UUID.randomUUID(), 4L)),
                id -> id.equals(ghost) ? null : "   ");
        String board = renderedTop(msgs);

        assertTrue(board.contains("<white>" + shortId(ghost) + "…</white>"), board);
        assertFalse(board.contains("null"), board);
    }

    /** Hipoteza V1: martwy etap rankOf nie połyka punktów ani TOP. */
    @Test
    void failedRankStageCannotSuppressPointsAndTop() {
        UUID ghost = UUID.randomUUID();
        SeasonPointService svc = mock(SeasonPointService.class);
        when(svc.pointsOf(org.mockito.ArgumentMatchers.any(UUID.class)))
                .thenReturn(CompletableFuture.completedFuture(100L));
        when(svc.rankOf(org.mockito.ArgumentMatchers.any(UUID.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("db down")));
        when(svc.top(10))
                .thenReturn(CompletableFuture.completedFuture(List.of(new TopRow(ghost, 77L))));
        when(svc.unlockedQuestSlots(100L)).thenReturn(8);
        when(svc.isCatchUpActive()).thenReturn(false);

        Player sender = mock(Player.class);
        when(sender.getUniqueId()).thenReturn(UUID.randomUUID());
        new SeasonCommand(svc, () -> System.currentTimeMillis() + 86_400_000L,
                () -> "01.09.2026 – 27.10.2026", MM,
                SeasonCommandTopRenderTest::shortId).show(sender);
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        // 3 nagłówek (etykieta, dni, „czym jest sezon”) + 2 punkty
        // + TOP-nagłówek + TOP-wiersz; sekcja pozycji znika gracefully
        // (exceptionally zamiast wysyłki) — to jest OK.
        verify(sender, times(7)).sendMessage(captor.capture());
        String all = captor.getAllValues().stream()
                .map(MM::serialize).reduce("", (a, b) -> a + b + '\n');
        assertTrue(all.contains("Twoje punkty: <bold><white>100</white>"), all);
        assertTrue(all.contains("TOP-1"), all);
    }

    // ── P1-3: ranking wysp pod TOP graczy ───────────────────────────────

    /**
     * Punkty są per gracz, więc wyspa = suma członków z próbki TOP. Właściciel
     * nazywa wyspę; sekcja pojawia się tylko po wpięciu resolvera (bez niego
     * dotychczasowe testy liczą 7 + n wierszy i nic się dla nich nie zmienia).
     */
    @Test
    void islandRankingSumsMembersPointsUnderPlayerTop() {
        UUID ala = UUID.randomUUID(), bartek = UUID.randomUUID(), celina = UUID.randomUUID();
        UUID islandAB = UUID.randomUUID(), islandC = UUID.randomUUID();
        List<TopRow> rows = List.of(new TopRow(ala, 300L), new TopRow(celina, 250L), new TopRow(bartek, 100L));
        Map<UUID, String> names = Map.of(ala, "Ala", bartek, "Bartek", celina, "Celina");
        Player sender = mock(Player.class);
        when(sender.getUniqueId()).thenReturn(UUID.randomUUID());

        new SeasonCommand(new StubPoints(rows), () -> System.currentTimeMillis() + 86_400_000L,
                () -> "Sezon", MM, names::get)
                .withIslandResolver(id -> {
                    if (id.equals(ala) || id.equals(bartek)) {
                        return Optional.of(new org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot(
                                islandAB, ala, null, null, null, 0L));
                    }
                    if (id.equals(celina)) {
                        return Optional.of(new org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot(
                                islandC, celina, null, null, null, 0L));
                    }
                    return Optional.empty();
                })
                .show(sender);

        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, times(7 + rows.size() + 1 + 2)).sendMessage(captor.capture());
        String rendered = renderedTop(captor.getAllValues());
        assertTrue(rendered.contains("TOP-2 wysp"), rendered);
        int ab = rendered.indexOf("Wyspa Ala</white> — <yellow>400 pkt</yellow> <dark_gray>(2 graczy)");
        int c = rendered.indexOf("Wyspa Celina</white> — <yellow>250 pkt</yellow> <dark_gray>(1 gracz)");
        assertTrue(ab >= 0 && c > ab, "wyspa Ali (400) przed wyspą Celiny (250):\n" + rendered);
    }
}
