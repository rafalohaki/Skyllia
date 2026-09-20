package org.rafalohaki.wpmecore.addons.skyblock.hub;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.quests.DailyQuestService;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Przewodnik musi liczyc takze rozbicia centra OneBlocka — oneblock anuluje wlasny
 * BlockBreakEvent (MONITOR ignoreCancelled go nie widzi), wiec bez obserwatora gracz
 * glodowal na kroku „Rozbij drzewo na drewno" mimo ze klody wpadaja z bloka.
 */
@Tag("mockbukkit")
class SkyBlockProgressiveObjectiveServiceTest {

    @BeforeAll
    static void startRegistry() {
        org.mockbukkit.mockbukkit.MockBukkit.mock();
    }

    @AfterAll
    static void stopRegistry() {
        org.mockbukkit.mockbukkit.MockBukkit.unmock();
    }


    private SkyBlockProgressiveObjectiveService service;
    private Player player;
    private UUID playerId;
    private LedgerService ledger;
    private DailyQuestService quests;
    private PlayerInventory inventory;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        lenient().when(plugin.getServer()).thenReturn(server);
        playerId = UUID.randomUUID();
        lenient().when(server.getPlayer(playerId)).thenAnswer(i -> player);
        lenient().when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("objective-test"));

        ledger = mock(LedgerService.class);
        lenient().when(ledger.playerBalance(any())).thenReturn(0L);
        SkylliaIntegration skyllia = mock(SkylliaIntegration.class);
        IslandView island = mock(IslandView.class);
        lenient().when(island.islandId()).thenReturn(UUID.randomUUID());
        lenient().when(skyllia.islandOf(playerId)).thenReturn(Optional.of(island));
        quests = mock(DailyQuestService.class);

        service = new SkyBlockProgressiveObjectiveService(plugin, ledger, skyllia, quests,
                MiniMessage.miniMessage());

        player = mock(Player.class, RETURNS_DEEP_STUBS);
        lenient().when(player.getUniqueId()).thenReturn(playerId);
        inventory = mock(PlayerInventory.class, RETURNS_DEEP_STUBS);
        lenient().when(player.getInventory()).thenReturn(inventory);
        lenient().when(inventory.getContents()).thenReturn(new java.util.ArrayList<org.bukkit.inventory.ItemStack>().toArray(new org.bukkit.inventory.ItemStack[0]));
    }

    /**
     * F20: liczniki etapów 1-5 żyją tylko w pamięci procesu, więc po restarcie
     * serwera są zerowe. Świeży serwis = dokładnie ten stan. Weteran z żelaznym
     * kilofem i saldem z bazy nie może wtedy dostać polecenia „Zbuduj generator
     * bruku" — a dokładnie to widział przed tą falą.
     */
    @Test
    void veteranIsNotSentBackToTheTutorialAfterRestart() {
        givePickaxe(Material.IRON_PICKAXE);
        lenient().when(ledger.playerBalance(any())).thenReturn(25_000L);

        String objective = service.resolveObjective(playerId);

        assertFalse(objective.contains("generator"),
                "weteran po restarcie nie może dostać budowy generatora: " + objective);
        assertFalse(objective.contains("bruk"),
                "weteran po restarcie nie może dostać kopania bruku: " + objective);
    }

    /** Ta sama sytuacja, ale gracz naprawdę jest na starcie: samouczek musi zostać. */
    @Test
    void freshPlayerWithStarterPickaxeStillGetsTheGeneratorStep() {
        givePickaxe(Material.IRON_PICKAXE);
        lenient().when(ledger.playerBalance(any())).thenReturn(0L);

        assertTrue(service.resolveObjective(playerId).contains("generator"),
                "skrzynia startowa daje żelazny kilof — pierwsze polecenie to generator");
    }

    /**
     * F24: na wyspie OneBlock nie ma ani drzewa, ani wiadra lawy — schemat
     * {@code oneblock-overworld.json} to bedrock, blok trawy i powietrze, a
     * skrzyni startowej ten tryb nie dostaje. Przewodnik kazał mimo to „rozbić
     * drzewo” i „zbudować generator bruku (lawa + woda)”, czyli druga
     * równorzędna ścieżka startu dostawała instrukcje z pierwszej.
     */
    @Test
    void oneblockGuideNeverAsksForATreeOrALavaGenerator() {
        service.bindOneblockDetector(islandId -> true);
        lenient().when(ledger.playerBalance(any())).thenReturn(0L);

        String firstStep = service.resolveObjective(playerId);
        assertFalse(firstStep.contains("drzewo"),
                "na OneBlocku nie ma drzewa do rozbicia: " + firstStep);
        assertTrue(firstStep.contains("blok pod sobą"),
                "pierwszy krok OneBlocka to rozbicie bloku pod stopami: " + firstStep);

        givePickaxe(Material.IRON_PICKAXE);
        String generatorStep = service.resolveObjective(playerId);
        assertFalse(generatorStep.contains("lawa"),
                "OneBlock nie dostaje wiadra lawy ani nie potrzebuje generatora: "
                        + generatorStep);
    }

    /** Kontrola: bez wpięcia detektora ścieżka klasyczna zostaje bez zmian. */
    @Test
    void classicGuideKeepsTheTreeAndGeneratorWording() {
        lenient().when(ledger.playerBalance(any())).thenReturn(0L);
        assertTrue(service.resolveObjective(playerId).contains("drzewo"),
                "klasyk zaczyna od drzewa");

        givePickaxe(Material.IRON_PICKAXE);
        assertTrue(service.resolveObjective(playerId).contains("lawa + woda"),
                "klasyk buduje generator z lawy i wody");
    }

    private void givePickaxe(Material material) {
        org.bukkit.inventory.ItemStack pickaxe = mock(org.bukkit.inventory.ItemStack.class);
        lenient().when(pickaxe.getType()).thenReturn(material);
        lenient().when(inventory.getContents())
                .thenReturn(new org.bukkit.inventory.ItemStack[] { pickaxe });
    }

    /**
     * P0-1: etap oszczędności (saldo 500–5000) nie obiecuje spawnera — nie ma go
     * w żadnym sklepie — tylko Kuźnię z istniejącą komendą /kuznia.
     */
    @Test
    void savingsStagePointsToTheForgeInsteadOfASpawner() {
        lenient().when(ledger.playerBalance(any())).thenReturn(1_000L);
        lenient().when(quests.hasCompletedToday(any())).thenReturn(true);

        String objective = service.resolveObjective(playerId);

        assertTrue(objective.contains("Kuźni"),
                "etap oszczędności wskazuje Kuźnię: " + objective);
        assertTrue(objective.contains("/kuznia"),
                "etap oszczędności podaje istniejącą komendę: " + objective);
        assertFalse(objective.toLowerCase(java.util.Locale.ROOT).contains("spawner"),
                "przewodnik nie może obiecywać spawnera: " + objective);
    }

    @Test
    void centerLogsCountTowardChopStage() {
        assertTrue(service.resolveObjective(playerId).contains("Rozbij drzewo na drewno"),
                "start: przewodnik oczekuje drewna");
        for (int i = 0; i < 4; i++) {
            service.observeBreak(player, Material.OAK_LOG);
        }
        assertFalse(service.resolveObjective(playerId).contains("Rozbij drzewo na drewno"),
                "4 kłody (nawet z OneBlocka) zamykaja krok");
    }

    /**
     * P1-2 (2026-09-05): po 25k monet przewodnik urywał się na „Walcz o TOP sezonu”.
     * Bogaty gracz ma dostać kolejno: minionek → talizman → karnet → rozbudowę
     * (5 minionków na klasycznej, Kres na OneBlocku) → dopiero ranking.
     */
    @Test
    void richPlayerGetsMinionTalismanPassAndExpansionBeforeTheLeaderboard() {
        when(ledger.playerBalance(playerId)).thenReturn(30_000L);
        lenient().when(quests.hasCompletedToday(any())).thenReturn(true);
        java.util.concurrent.atomic.AtomicInteger minions = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicBoolean talisman = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean premium = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean kres = new java.util.concurrent.atomic.AtomicBoolean(false);
        service.bindMinionCounter(islandId -> minions.get());
        service.bindTalismanDetector(p -> talisman.get());
        service.bindPremiumDetector(id -> premium.get());
        service.bindOneblockFinished(id -> kres.get());

        assertTrue(service.resolveObjective(playerId).contains("minionka"), "0 minionków → kuźnia po minionka");
        minions.set(1);
        assertTrue(service.resolveObjective(playerId).contains("talizman"), "minionek jest → talizman");
        talisman.set(true);
        assertTrue(service.resolveObjective(playerId).contains("/przepustka"), "talizman jest → karnet");
        premium.set(true);
        String classic = service.resolveObjective(playerId);
        assertTrue(classic.contains("5 minionków") && classic.contains("1/5"), classic);
        minions.set(5);
        assertTrue(service.resolveObjective(playerId).contains("/sezon"), "wyspa klasyczna z 5 minionkami → ranking");

        service.bindOneblockDetector(id -> true);
        assertTrue(service.resolveObjective(playerId).contains("Kres"), "OneBlock przed Kresem → /oneblock");
        kres.set(true);
        assertTrue(service.resolveObjective(playerId).contains("/sezon"), "OneBlock w Kresie → ranking");
    }

    /**
     * P1-3 (2026-09-10): etap oszczędności obiecywał 5 000, a najtańsza płatna
     * receptura kuźni kosztuje 6 000 — gracz zbierał kwotę, za którą nie dało się
     * nic wykować. Próg i tekst mają iść z configu kuźni, a linia ma uprzedzić,
     * że poza monetami potrzebne są surowce.
     */
    @Test
    void forgeStepPromisesTheCheapestPaidRecipeAndItsMaterials() {
        lenient().when(ledger.playerBalance(any())).thenReturn(1_000L);
        lenient().when(quests.hasCompletedToday(any())).thenReturn(true);
        service.bindForgeEntryCost(() -> 6_000L);

        String objective = service.resolveObjective(playerId);

        assertTrue(plain(objective).contains("cel 6 000 monet"), objective);
        assertTrue(plain(objective).contains("+ surowce"), objective);
        assertFalse(plain(objective).contains("5 000"),
                "stary próg zniknął razem z ceną: " + objective);
    }

    /** Ten sam etap liczy bramkę z wpiętego progu, a nie z zaszytej liczby. */
    @Test
    void forgeStepGateFollowsTheBoundRecipePrice() {
        lenient().when(ledger.playerBalance(any())).thenReturn(19_000L);
        lenient().when(quests.hasCompletedToday(any())).thenReturn(true);
        service.bindForgeEntryCost(() -> 20_000L);

        assertTrue(plain(service.resolveObjective(playerId)).contains("cel 20 000 monet"),
                "19 000 < próg 20 000 — gracz wciąż zbiera na Kuźnię");
    }

    /**
     * P1-3: etap zadania dnia stał na jednorazowym „skończył kiedykolwiek quest”,
     * więc po pierwszym zrobionym zadaniu linia gasła na zawsze. Sygnałem jest
     * teraz doba (punkty kanału „quest” z dzisiaj) — linia wraca każdego dnia,
     * dopóki zadanie nie jest zrobione, i gaśnie po jego wykonaniu.
     */
    @Test
    void dailyQuestStepComesBackWhenTheDayStartsOver() {
        lenient().when(ledger.playerBalance(any())).thenReturn(1_000L);
        service.setSignalRefreshMillis(0L);
        java.util.concurrent.atomic.AtomicLong pointsToday = new java.util.concurrent.atomic.AtomicLong(25L);
        service.bindDailyQuestPoints(id -> java.util.concurrent.CompletableFuture.completedFuture(pointsToday.get()));

        assertFalse(service.resolveObjective(playerId).contains("zadanie dnia"),
                "zrobione zadanie dnia (25 pkt kanału quest) zamyka linię");

        pointsToday.set(0L); // nowa doba: kanał „quest" znów na zero
        assertTrue(service.resolveObjective(playerId).contains("zadanie dnia"),
                "nowy dzień bez punktów = zadanie dnia znów do zrobienia");
    }

    /** Kontrola: dzisiejsze zadanie zrobione → linia milczy i przewodnik idzie dalej. */
    @Test
    void dailyQuestStepDisappearsWhenTodayIsDone() {
        lenient().when(ledger.playerBalance(any())).thenReturn(1_000L);
        service.bindDailyQuestPoints(id -> java.util.concurrent.CompletableFuture.completedFuture(25L));

        String objective = service.resolveObjective(playerId);

        assertFalse(objective.contains("zadanie dnia"), objective);
        assertTrue(objective.contains("/kuznia"), "po zadaniu dnia wchodzi etap Kuźni: " + objective);
    }

    /** Bez wpięcia sygnału dnia zostaje licznik naszych zadań dziennych. */
    @Test
    void dailyQuestStepFallsBackToTheDailyQuestCounter() {
        lenient().when(ledger.playerBalance(any())).thenReturn(1_000L);
        lenient().when(quests.hasCompletedToday(any())).thenReturn(false);

        assertTrue(service.resolveObjective(playerId).contains("zadanie dnia"),
                "brak sygnału dnia = dawny licznik decyduje");
    }

    /**
     * P1-3: etap banku patrzył na portfel, więc gracz z 30 000 w plecaku dostawał
     * „Odłóż monety do banku” mimo pustego konta wyspy. Teraz decyduje DEPOZYT,
     * a po jego zebraniu etap puszcza dalej.
     */
    @Test
    void bankStepCountsTheIslandDepositNotTheWallet() {
        lenient().when(ledger.playerBalance(any())).thenReturn(30_000L);
        lenient().when(quests.hasCompletedToday(any())).thenReturn(true);
        service.bindForgeEntryCost(() -> 6_000L);
        service.setSignalRefreshMillis(0L);
        java.util.concurrent.atomic.AtomicLong deposit = new java.util.concurrent.atomic.AtomicLong(0L);
        service.bindIslandBankBalance(islandId ->
                java.util.concurrent.CompletableFuture.completedFuture(deposit.get()));

        assertTrue(service.resolveObjective(playerId).contains("Odłóż monety do banku"),
                "portfel pełny, bank pusty → monety nie są odłożone");

        deposit.set(25_000L);
        String afterDeposit = service.resolveObjective(playerId);
        assertFalse(afterDeposit.contains("Odłóż monety do banku"), afterDeposit);
        assertTrue(afterDeposit.contains("minionka"), "bank zaopatrzony → kolejny etap: " + afterDeposit);
    }

    /**
     * P1-3: wpłata do banku opróżnia portfel. Gdyby próg Kuźni i bramka samouczka
     * patrzyły tylko na portfel, gracz po zrobieniu dokładnie tego, o co prosi
     * etap banku, wracałby na „Zbieraj monety na Kuźnię” albo do samouczka.
     */
    @Test
    void depositingEverythingDoesNotSendThePlayerBack() {
        lenient().when(ledger.playerBalance(any())).thenReturn(0L);
        lenient().when(quests.hasCompletedToday(any())).thenReturn(true);
        service.bindForgeEntryCost(() -> 6_000L);
        service.bindIslandBankBalance(islandId ->
                java.util.concurrent.CompletableFuture.completedFuture(25_000L));

        String objective = service.resolveObjective(playerId);

        assertFalse(objective.contains("Zbieraj monety"), objective);
        assertFalse(objective.contains("Odłóż monety"), objective);
        assertFalse(objective.contains("generator"),
                "weteran po wpłacie nie wraca do samouczka: " + objective);
    }

    /**
     * P1-3: po samouczku tablica kończyła się na „Walcz o TOP sezonu” — gracz
     * nie wiedział, co konkretnie robić. Ogon to łańcuch: kolejne minionki/pety
     * do limitu miejsc → wymiana Złotych Lotosów na klucze w kantorze → punkty
     * sezonowe (bonusowe poziomy karnetu).
     */
    @Test
    void veteranTailIsAMinionKantorThenSeasonChain() {
        lenient().when(ledger.playerBalance(any())).thenReturn(30_000L);
        lenient().when(quests.hasCompletedToday(any())).thenReturn(true);
        service.bindForgeEntryCost(() -> 6_000L);
        service.bindIslandBankBalance(islandId ->
                java.util.concurrent.CompletableFuture.completedFuture(25_000L));
        java.util.concurrent.atomic.AtomicInteger minions = new java.util.concurrent.atomic.AtomicInteger(5);
        java.util.concurrent.atomic.AtomicBoolean lotuses = new java.util.concurrent.atomic.AtomicBoolean(false);
        service.bindMinionCounter(islandId -> minions.get());
        service.bindTalismanDetector(p -> true);
        service.bindPremiumDetector(id -> true);
        service.bindSlotLimit(playerId -> 8);
        service.bindLotusDetector(p -> lotuses.get());

        String minionStep = service.resolveObjective(playerId);
        assertTrue(minionStep.contains("Postaw kolejnego minionka"), minionStep);
        assertTrue(minionStep.contains("5/8"), "licznik idzie do realnego limitu: " + minionStep);

        minions.set(8);
        lotuses.set(true);
        String kantorStep = service.resolveObjective(playerId);
        assertTrue(kantorStep.contains("Złote Lotosy") && kantorStep.contains("/kantor"),
                "limit osiągnięty, Lotosy w plecaku → kantor: " + kantorStep);

        lotuses.set(false);
        String seasonStep = service.resolveObjective(playerId);
        assertTrue(seasonStep.contains("Zbieraj punkty sezonowe") && seasonStep.contains("/sezon"),
                seasonStep);
        assertTrue(seasonStep.contains("500 pkt"), "linia mówi, co dają punkty: " + seasonStep);
    }

    /** Migracja Eco: w ogonie weterana liczą się pety z kuźni, nie minionki wyspy. */
    @Test
    void veteranTailInEcoModeCountsPets() {
        lenient().when(ledger.playerBalance(any())).thenReturn(30_000L);
        lenient().when(quests.hasCompletedToday(any())).thenReturn(true);
        service.bindForgeEntryCost(() -> 6_000L);
        service.bindIslandBankBalance(islandId ->
                java.util.concurrent.CompletableFuture.completedFuture(25_000L));
        service.enablePetMode(id -> 3);
        service.bindTalismanDetector(p -> true);
        service.bindPremiumDetector(id -> true);
        service.bindSlotLimit(playerId -> 13);

        String objective = service.resolveObjective(playerId);

        assertTrue(objective.contains("Wykuj kolejnego peta"), objective);
        assertTrue(objective.contains("3/13"), objective);
    }

    /**
     * P1-3: nagrody rozdziału OneBlocka odbiera się ręcznie
     * ({@code /wyspa oneblock nagrody}), a przewodnik o nich milczał. Linia stoi
     * NAD ogonem weterana i gaśnie, gdy nie ma czego odbierać.
     */
    @Test
    void oneblockUnclaimedRewardsLineSitsAboveTheVeteranTail() {
        veteranOnTheTail();
        service.bindOneblockDetector(id -> true);
        service.bindOneblockFinished(id -> true);

        service.setSignalRefreshMillis(0L);
        String withoutRewards = service.resolveObjective(playerId);
        assertFalse(withoutRewards.contains("nieodebrane"),
                "bez zaległych nagród linia się nie pokazuje: " + withoutRewards);
        assertTrue(withoutRewards.contains("/sezon"),
                "ogon weterana zostaje bez zmian: " + withoutRewards);

        service.bindOneblockUnclaimedRewards(id ->
                java.util.concurrent.CompletableFuture.completedFuture(2));
        String withRewards = service.resolveObjective(playerId);

        assertTrue(withRewards.contains("nieodebrane nagrody rozdziału"),
                "zaległe nagrody rozdziału wchodzą nad ogon: " + withRewards);
        assertTrue(withRewards.contains("(2)"), "linia podaje liczbę sztuk: " + withRewards);
        assertTrue(withRewards.contains("/wyspa oneblock nagrody"),
                "linia wskazuje istniejącą komendę odbioru: " + withRewards);
    }

    /** Zero zaległości = dokładnie to, co przewodnik mówił przed tą zmianą. */
    @Test
    void unclaimedRewardsLineIsSilentAtZero() {
        veteranOnTheTail();
        service.bindOneblockDetector(id -> true);
        service.bindOneblockFinished(id -> true);
        service.bindOneblockUnclaimedRewards(id ->
                java.util.concurrent.CompletableFuture.completedFuture(0));

        String objective = service.resolveObjective(playerId);

        assertFalse(objective.contains("nieodebrane"), objective);
        assertTrue(objective.contains("/sezon"), objective);
    }

    /**
     * Wyspa klasyczna nie ma rozdziałów ani nagród do odbioru — nawet gdyby
     * źródło zwróciło niezerową liczbę, linia nie ma prawa się pokazać
     * (komenda {@code /wyspa oneblock nagrody} tam nie istnieje).
     */
    @Test
    void classicIslandNeverShowsUnclaimedRewards() {
        veteranOnTheTail();
        service.bindOneblockUnclaimedRewards(id ->
                java.util.concurrent.CompletableFuture.completedFuture(3));

        String objective = service.resolveObjective(playerId);

        assertFalse(objective.contains("nieodebrane"), objective);
    }

    /** Etapy 1–13 mają pierwszeństwo: zaległe nagrody nie przesłaniają karnetu. */
    @Test
    void unclaimedRewardsDoNotCoverStagesOneToThirteen() {
        veteranOnTheTail();
        service.bindOneblockDetector(id -> true);
        service.bindPremiumDetector(id -> false);
        service.bindOneblockUnclaimedRewards(id ->
                java.util.concurrent.CompletableFuture.completedFuture(3));

        String objective = service.resolveObjective(playerId);

        assertTrue(objective.contains("/przepustka"),
                "karnet (etap 12) jest ważniejszy niż zaległe nagrody: " + objective);
        assertFalse(objective.contains("nieodebrane"), objective);
    }

    private void veteranOnTheTail() {
        lenient().when(ledger.playerBalance(any())).thenReturn(30_000L);
        lenient().when(quests.hasCompletedToday(any())).thenReturn(true);
        service.bindForgeEntryCost(() -> 6_000L);
        service.bindIslandBankBalance(islandId ->
                java.util.concurrent.CompletableFuture.completedFuture(25_000L));
        service.bindMinionCounter(islandId -> 1);
        service.bindTalismanDetector(p -> true);
        service.bindPremiumDetector(id -> true);
    }

    /** Kwoty w linii idą przez {@code Ui.money}, czyli z twardą spacją grupowania. */
    private static String plain(String objective) {
        return objective.replace('\u00A0', ' ').replace('\u202F', ' ');
    }
}
