package org.rafalohaki.wpmecore.addons.skyblock.hub;

import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;


import org.rafalohaki.wpmecore.addons.skyblock.quests.DailyQuestService;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Kolejne kroki dla nowego gracza, liczone w pamięci i pokazywane w jednej
 * linii tablicy bocznej (%wpme_next_objective%):
 * 1. Załóż wyspę (/is)
 * 2. Zbierz drewno (0/4)
 * 3. Zrób drewniany kilof
 * 4. Wykop 3 bloki bruku
 * 5. Zrób kamienny kilof
 * 6. Zbuduj generator bruku, potem wykop 32 bruku
 * 7. Pierwsza sprzedaż (/sklep)
 * 8. Zadanie dnia (/zadania)
 * 9. Zbieraj monety na Kuźnię (/kuznia; próg = najtańsza płatna receptura)
 * 10. Odłóż monety do banku (/bank; liczy się DEPOZYT wyspy, nie portfel)
 * 11. Wykuj pierwszego minionka (/kuznia)
 * 12. Wykuj talizman (/kuznia)
 * 13. Aktywuj karnet premium (/przepustka)
 * 14. OneBlock: dokop się do rozdziału Kres (/oneblock); klasyczna: 5 minionków
 * 15. Weteran: kolejne minionki/pety do limitu → kantor (Złote Lotosy na klucze)
 *     → punkty sezonowe (/sezon)
 * <p>P1-2 (2026-09-05): etapy 11–14 stoją na trwałych źródłach (MinionService,
 * ekwipunek, wpme_sb_season_pass, OneBlockState), więc restart ich nie cofa —
 * w przeciwieństwie do liczników 1–5. Źródła wpina SkyBlockGameplay przez bind*.
 *
 * <p>P1-3 (2026-09-10, ślepe zaułki linii przewodnika): cztery bramki czytają
 * teraz to, co naprawdę obiecują, i każde źródło wchodzi przez własny {@code bind*}:
 * <ul>
 *   <li>etap 9 (Kuźnia) liczy próg z najtańszej PŁATNEJ receptury kuźni i mówi
 *       wprost o surowcach — sama cena nic nie wykuje;</li>
 *   <li>etap 8 (zadanie dnia) stoi na sygnale DOBY (punkty kanału „quest” z dzisiaj),
 *       więc linia wraca każdego dnia, dopóki zadanie nie jest zrobione — zamiast
 *       jednorazowego „skończył kiedykolwiek quest”, który gasił ją na zawsze;</li>
 *   <li>etap 10 (bank) patrzy na saldo KONTA WYSPY (depozyt), nie na portfel gracza —
 *       trzymanie monet w plecaku nie jest odkładaniem ich do banku;</li>
 *   <li>ogon po samouczku nie kończy się na „Walcz o TOP sezonu”: to łańcuch
 *       kolejny minionek/pet → wymiana Złotych Lotosów na klucze w kantorze →
 *       punkty sezonowe.</li>
 * </ul>
 * Wszystkie cztery źródła są odporne na brak wpięcia: bez bindingu obowiązuje
 * zachowanie sprzed zmiany (portfel, dawny detektor zadania dnia, próg 5 000,
 * brak linii o minionku), dzięki czemu przewodnik działa też w testach jednostkowych.
 *
 * <p>P1-3 (2026-09-10, nieodebrane nagrody OneBlocka): odbiór lootu rozdziału jest
 * ręczny ({@code /wyspa oneblock nagrody}), a przewodnik o nim milczał — gracz
 * kończył rozdział i nie wiedział, że nagrody czekają. Sygnał wchodzi przez
 * {@link #bindOneblockUnclaimedRewards} i pokazuje się jako osobna linia NAD
 * ogonem weterana (etapy 1–13 mają pierwszeństwo). Zero i brak wpięcia = brak linii.
 *
 * <p>P1-3: saldo banku wyspy i punkty dnia to zapytania JDBC. {@link #resolveObjective}
 * chodzi co 5 s na wątku regionu, więc czyta je przez {@link AsyncSignalCache}:
 * wartość z pamięci, odświeżenie w tle najwyżej raz na
 * {@link #SIGNAL_REFRESH_MILLIS}. „Majątek” gracza to {@code max(portfel, depozyt)} —
 * bez tego wpłata do banku opróżniała portfel i cofała przewodnik na samouczek
 * (bramka {@link #FIRST_SALE_COINS}) albo na zbieranie monet.
 *
 * <p>P0-1 (2026-09-05): krok 9 mówił wcześniej „Kup spawner potworów /sklep”,
 * ale spawnerów nie ma w żadnym sklepie (system spawnerów istnieje wyłącznie
 * jako projekt w tests2b2tmc/docs/skyblock-spawners.md). Bramka salda (< 5000)
 * już wcześniej robiła z tego etap oszczędnościowy — tekst dogoniono do
 * rzeczywistości i wskazano Kuźnię (/kuznia), która za te monety stoi.
 *
 * <p>F19: linia mówi CZYNNOŚĆ i KOMENDĘ, nigdy samą nazwę systemu. Komenda
 * podana w linii musi istnieć — krok 10 wskazywał wcześniej na `/is bank`,
 * a ta Skyllia nie ma podkomendy `bank` i odpowiadała „taka podkomenda nie
 * istnieje”. Bank wyspy to nasza komenda `/bank`.
 *
 * <p>F20: etapy 1-5 stoją na licznikach trzymanych w pamięci procesu. Po
 * restarcie serwera wracają na zero, a zero jest nieodróżnialne od „gracz
 * naprawdę nic jeszcze nie zrobił" — weteran z diamentowym kilofem i rozbudowaną
 * wyspą dostawał na tablicy „Zbuduj generator bruku". Samouczek jest więc
 * zamknięty progiem {@link #FIRST_SALE_COINS}, jedynym sygnałem w tej metodzie,
 * który restart przeżywa (salda wczytuje {@code LedgerService.init} z bazy).
 *
 * <p>F19: skrzynia startowa trybu klasycznego zawiera ŻELAZNY kilof, więc
 * gracz, który ją otworzy, przeskakuje kroki 2-5 i ląduje od razu na
 * generatorze. Dlatego krok 6 przy zerowym liczniku mówi „Zbuduj generator
 * bruku”, a nie „Wykop bruk 0/32” — bez tego pierwsza rzecz na tablicy jest
 * poleceniem bez instrukcji.
 */
public final class SkyBlockProgressiveObjectiveService implements Listener {

    /**
     * Próg „pierwsza sprzedaż" — etap 6 i zarazem trwała granica samouczka.
     *
     * <p>Kod już wcześniej uznawał gracza z tym saldem za dalej niż etap 6, więc
     * użycie tego samego progu do zamknięcia etapów 1-5 nie zmienia ścieżki
     * nowego gracza (ten startuje z saldem 0): usuwa wyłącznie cofanie weterana
     * do samouczka po restarcie.
     */
    private static final long FIRST_SALE_COINS = 500L;

    /**
     * Awaryjny próg Kuźni, gdy nikt nie wpiął ceny z configu kuźni (albo config
     * nie ma ani jednej płatnej receptury). To próg sprzed zmiany, więc bez
     * {@code bindForgeEntryCost} tablica mówi to samo co dotąd.
     */
    private static final long FALLBACK_FORGE_ENTRY_COST = 5_000L;

    /**
     * Depozyt, od którego etap banku uznaje wyspę za zaopatrzoną. Próg jest ten
     * sam co dawny próg portfela (25 000) — zmieniło się tylko ŹRÓDŁO: liczy się
     * konto wyspy, nie plecak gracza.
     */
    private static final long BANK_GOAL = 25_000L;

    /**
     * Ile milisekund ważna jest wartość sygnału pobieranego z bazy, zanim
     * przewodnik zapyta ponownie. Wydawca tablicy odświeża gracza co 5 s, więc
     * 10 s to najwyżej jedno zapytanie na gracza na dwie publikacje.
     */
    private static final long SIGNAL_REFRESH_MILLIS = 10_000L;

    private final JavaPlugin plugin;
    private final LedgerService ledger;
    private final SkylliaIntegration skyllia;
    private final DailyQuestService quests;
    private final MiniMessage miniMessage;

    /** Świeżość sygnałów z bazy; zmienialna pakietowo, żeby testy nie czekały na zegar. */
    private volatile long signalRefreshMillis = SIGNAL_REFRESH_MILLIS;

    /** Sygnały z bazy (saldo banku wyspy, punkty dnia, nagrody OneBlocka) — bez I/O, patrz AsyncSignalCache. */
    private final AsyncSignalCache<Long> bankDepositSignals = new AsyncSignalCache<>(() -> signalRefreshMillis);
    private final AsyncSignalCache<Long> dailyQuestSignals = new AsyncSignalCache<>(() -> signalRefreshMillis);
    private final AsyncSignalCache<Integer> oneblockRewardSignals =
            new AsyncSignalCache<>(() -> signalRefreshMillis);

    private final Map<UUID, AtomicInteger> minedLogs = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> craftedWoodPickaxe = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> minedCobblestone = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> craftedStonePickaxe = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> highestStage = new ConcurrentHashMap<>();

    private volatile SkyBlockPresentationPublisher publisher;

    /**
     * F24: czy wyspa jest OneBlockiem — ten sam detektor, który
     * {@code DailyQuestService} dostaje przez {@code bindOneblockDetector}.
     * Domyślnie „nie”, więc bez wpięcia zachowanie jest identyczne jak dotąd.
     *
     * <p>Bez tego samouczek (etapy 1-5) mówił do gracza OneBlocka rzeczami,
     * których w jego trybie nie ma: „Rozbij drzewo” (na wyspie OneBlock nie ma
     * drzewa — schemat {@code oneblock-overworld.json} to dosłownie bedrock,
     * blok trawy i powietrze) oraz „Zbuduj generator bruku (lawa + woda)”
     * (nie dostaje ani jednego wiadra i generator nie jest mu do niczego
     * potrzebny — kamień leci z centrum w rozdziale „Podziemia”).
     */
    private volatile java.util.function.Predicate<UUID> oneblockIslands = id -> false;

    /** Wpięcie detektora trybu wyspy (produkcja: {@code OneBlockService::isOneblock}). */
    private volatile java.util.function.ToIntFunction<UUID> minionCounter = islandId -> 0;
    private volatile java.util.function.Predicate<Player> talismanDetector = player -> false;
    private volatile java.util.function.Predicate<UUID> premiumDetector = playerId -> false;
    private volatile java.util.function.Predicate<UUID> oneblockFinished = islandId -> false;

    /** Liczba minionków na wyspie (MinionService#countForIsland). */
    /** Migracja Eco: licznik per-gracz: minionki w ekwipunku + postawione na wyspie. */
    private volatile boolean petMode;
    private volatile java.util.function.ToIntFunction<UUID> petCounter = playerId -> 0;

    /**
     * Próg wejścia do Kuźni: najtańsza receptura z {@code cost-money > 0}.
     * Domyślnie próg sprzed zmiany, więc bez {@code bindForgeEntryCost} tekst
     * etapu oszczędności zostaje taki, jaki był (poza formatowaniem kwoty).
     */
    private volatile java.util.function.LongSupplier forgeEntryCost = () -> FALLBACK_FORGE_ENTRY_COST;

    /**
     * Saldo konta WYSPY (depozyt w banku) — patrz {@link #bindIslandBankBalance}.
     * Źródło jest asynchroniczne (JDBC), więc czytamy je przez
     * {@link #bankDepositSignals}.
     */
    private volatile Function<UUID, CompletableFuture<Long>> islandBankBalance;

    /**
     * Ile punktów kanału „quest” gracz zdobył DZIŚ (UTC) — sygnał doby dla
     * zadania dnia; patrz {@link #bindDailyQuestPoints}.
     */
    private volatile Function<UUID, CompletableFuture<Long>> dailyQuestPoints;

    /**
     * Ile nagród rozdziału OneBlocka czeka na odbiór (0 = brak/albo nie OneBlock) —
     * sygnał do linii o zaległościach; patrz {@link #bindOneblockUnclaimedRewards}.
     */
    private volatile Function<UUID, CompletableFuture<Integer>> oneblockUnclaimedRewards;

    /**
     * Górna granica miejsc gracza: klasyk = sloty minionków wyspy (config + perki
     * rang), migracja Eco = liczba minionków gracza (ekwipunek + wyspa nie zna
     * slotów). Zero = brak wiedzy → etap weterana nie wypomina minionków.
     */
    private volatile java.util.function.ToIntFunction<UUID> slotLimit = playerId -> 0;

    /** Czy gracz nosi przy sobie Złote Lotosy, które ma gdzie wymienić (kantor). */
    private volatile java.util.function.Predicate<Player> lotusDetector = player -> false;

    public void enablePetMode(@NotNull java.util.function.ToIntFunction<UUID> petsOfPlayer) {
        this.petCounter = petsOfPlayer;
        this.petMode = true;
    }

    public void bindMinionCounter(@NotNull java.util.function.ToIntFunction<UUID> counter) {
        this.minionCounter = counter;
    }

    /**
     * Próg Kuźni (etap oszczędności): najtańsza receptura kuźni z
     * {@code cost-money > 0}. Produkcja liczy go z {@code ForgeConfig}, więc
     * obietnica na tablicy nie rozjeżdża się z ceną w menu.
     *
     * <p>Zero albo wartość ujemna (brak płatnych receptur) wraca do progu
     * awaryjnego — linia nigdy nie mówi „cel 0 monet”.
     */
    public void bindForgeEntryCost(@NotNull java.util.function.LongSupplier cheapestPaidRecipeCost) {
        this.forgeEntryCost = cheapestPaidRecipeCost;
    }

    /**
     * Saldo konta wyspy (depozyt w banku) po islandId —
     * produkcja: {@code LedgerService::authoritativeIslandBalance}.
     *
     * <p>Etap banku pyta o nie, bo trzymanie monet w plecaku nie jest odkładaniem
     * ich do banku. Źródło jest asynchroniczne, więc wartość czytamy z pamięci,
     * a odświeżenie zlecamy w tle (razem z progiem majątku w etapie Kuźni).
     * Bez wpięcia obowiązuje dawny sygnał (portfel gracza).
     */
    public void bindIslandBankBalance(@NotNull Function<UUID, CompletableFuture<Long>> islandBalance) {
        this.islandBankBalance = islandBalance;
    }

    /**
     * Sygnał DOBY dla zadania dnia: punkty kanału „quest” przyznane dziś
     * (produkcja: {@code seasonDailyPoints.pointsToday(playerId, "quest")} —
     * EcoQuests woła za zadanie dnia {@code /sezon admin punkty-dzien}).
     *
     * <p>Zero znaczy „zadanie dnia jeszcze czeka” i to jest cała różnica wobec
     * dawnego detektora Eco ({@code %ecoquests_quests_completed% >= 1}): ten
     * drugi był jednorazowy i gasił linię na zawsze, więc weteran nie widział na
     * tablicy nic do zrobienia. Bez wpięcia zostaje licznik naszych zadań
     * dziennych ({@code DailyQuestService#hasCompletedToday}).
     */
    public void bindDailyQuestPoints(@NotNull Function<UUID, CompletableFuture<Long>> pointsTodayInQuestChannel) {
        this.dailyQuestPoints = pointsTodayInQuestChannel;
    }

    /**
     * Zaległe nagrody rozdziału OneBlocka dla wyspy (produkcja:
     * {@code oneBlock.service().info(islandId)} → {@code unclaimedMilestones}).
     *
     * <p>Odbiór tych nagród jest ręczny ({@code /wyspa oneblock nagrody}), a
     * przewodnik o nim milczał — gracz kończył rozdział i nie wiedział, że loot
     * na niego czeka. Źródło jest asynchroniczne (liczy wiersze SQL), więc
     * wartość czytamy z pamięci. Zero i brak wpięcia znaczą to samo: brak linii,
     * czyli zachowanie sprzed zmiany.
     */
    public void bindOneblockUnclaimedRewards(
            @NotNull Function<UUID, CompletableFuture<Integer>> unclaimedMilestones) {
        this.oneblockUnclaimedRewards = unclaimedMilestones;
    }

    /**
     * Limit miejsc etapu weterana — ta sama liczba, którą liczy etap 14
     * ({@code N/M}). Produkcja: klasyk = {@code max-minions-per-island} + sloty
     * z perków rangi, migracja Eco = liczba typów minionków do wykucia w kuźni.
     * Zero (brak wpięcia) pomija krok z minionkiem.
     */
    public void bindSlotLimit(@NotNull java.util.function.ToIntFunction<UUID> limitForPlayer) {
        this.slotLimit = limitForPlayer;
    }

    /**
     * Czy gracz nosi Złote Lotosy, które ma gdzie wymienić — etap weterana
     * kieruje wtedy do kantoru ({@code /kantor}) zamiast na punkty sezonowe.
     * Komplet do wymiany (3 szt. na klucze) pilnuje samo menu kantoru; tutaj
     * wystarczy, że Lotosy leżą w plecaku.
     */
    public void bindLotusDetector(@NotNull java.util.function.Predicate<Player> detector) {
        this.lotusDetector = detector;
    }

    /** Odstęp odświeżania sygnałów z bazy; pakietowy, żeby testy nie czekały na zegar. */
    void setSignalRefreshMillis(long millis) {
        this.signalRefreshMillis = millis;
    }

    /** Czy gracz ma przy sobie dowolny talizman (TalismanListener#hasTalisman). */
    public void bindTalismanDetector(@NotNull java.util.function.Predicate<Player> detector) {
        this.talismanDetector = detector;
    }

    /** Czy gracz ma karnet premium bieżącego sezonu (SeasonPassService#isPremiumCached). */
    public void bindPremiumDetector(@NotNull java.util.function.Predicate<UUID> detector) {
        this.premiumDetector = detector;
    }

    /** Czy wyspa OneBlocka stoi w ostatnim rozdziale treści. */
    public void bindOneblockFinished(@NotNull java.util.function.Predicate<UUID> detector) {
        this.oneblockFinished = detector;
    }

    public void bindOneblockDetector(@NotNull java.util.function.Predicate<UUID> detector) {
        this.oneblockIslands = detector;
    }

    public SkyBlockProgressiveObjectiveService(@NotNull JavaPlugin plugin,
                                               @NotNull LedgerService ledger,
                                               @NotNull SkylliaIntegration skyllia,
                                               @NotNull DailyQuestService quests,
                                               @NotNull MiniMessage miniMessage) {
        this.plugin = plugin;
        this.ledger = ledger;
        this.skyllia = skyllia;
        this.quests = quests;
        this.miniMessage = miniMessage;
    }

    public void setPublisher(SkyBlockPresentationPublisher publisher) {
        this.publisher = publisher;
    }

    private void notifyUpdate(UUID playerId) {
        SkyBlockPresentationPublisher pub = this.publisher;
        if (pub != null) {
            pub.refresh(playerId);
        }
    }

    /**
     * Resolves the current human-readable objective for the player.
     * Evaluated purely in memory without blocking I/O.
     */
    public @NotNull String resolveObjective(@NotNull UUID playerId) {
        IslandView island = skyllia.islandOf(playerId).orElse(null);
        if (island == null) {
            return "<yellow>▶</yellow> <white>Załóż wyspę</white> <dark_gray>— wpisz</dark_gray> <yellow>/is</yellow>";
        }

        UUID islandId = island.islandId();
        Player player = plugin.getServer().getPlayer(playerId);
        long balance = ledger.playerBalance(playerId);
        // P1-3: bank wyspy to osobne konto (depozyt). „Majątek" gracza liczymy
        // jako max(portfel, depozyt): bez tego wpłata do banku opróżniała portfel
        // i cofała przewodnik na samouczek (F20) albo na zbieranie monet —
        // czyli gracz, który zrobił DOKŁADNIE to, o co prosił etap banku,
        // dostawał karę. Bez wpięcia źródła bank == portfel (zachowanie sprzed zmiany).
        long bank = islandDeposit(islandId, balance);
        long wealth = Math.max(balance, bank);
        boolean oneblock = oneblockIslands.test(islandId);

        // Etapy 1-5 (samouczek) tylko dla gracza, który nie ma jeszcze za sobą
        // pierwszej sprzedaży. Ich liczniki nie przeżywają restartu, więc bez
        // tej bramki weteran wracał na „Zbuduj generator bruku" — patrz F20.
        if (wealth < FIRST_SALE_COINS) {
            // Stage 1: Chop 4 logs
            int logs = minedLogs.computeIfAbsent(playerId, id -> new AtomicInteger(0)).get();
            boolean hasWoodPick = Boolean.TRUE.equals(craftedWoodPickaxe.get(playerId))
                    || hasAnyPickaxe(player);
            if (!hasWoodPick && logs < 4) {
                return oneblock
                        ? "<yellow>▶</yellow> <white>Rozbijaj blok pod sobą — wypadnie drewno</white> <gray>(" + logs + "/4)</gray>"
                        : "<yellow>▶</yellow> <white>Rozbij drzewo na drewno</white> <gray>(" + logs + "/4)</gray>";
            }

            // Stage 2: Craft wooden pickaxe
            if (!hasWoodPick) {
                checkStageAdvance(playerId, 2, "Pozyskanie drewna");
                return "<yellow>▶</yellow> <white>Zrób drewniany kilof</white> <gray>(stół rzemieślniczy)</gray>";
            }

            // Stage 3: Mine 3 cobble for stone tools
            int cobble = minedCobblestone.computeIfAbsent(playerId, id -> new AtomicInteger(0)).get();
            boolean hasStonePick = Boolean.TRUE.equals(craftedStonePickaxe.get(playerId))
                    || hasStoneOrBetterPickaxe(player);
            if (!hasStonePick && cobble < 3) {
                checkStageAdvance(playerId, 3, "Drewniany kilof");
                return oneblock
                        ? "<yellow>▶</yellow> <white>Kop dalej, aż wypadnie kamień</white> <gray>(" + cobble + "/3)</gray>"
                        : "<yellow>▶</yellow> <white>Wykop 3 bloki bruku</white> <gray>(" + cobble + "/3)</gray>";
            }

            // Stage 4: Craft stone pickaxe
            if (!hasStonePick) {
                checkStageAdvance(playerId, 4, "Wydobycie pierwszego kamienia");
                return "<yellow>▶</yellow> <white>Zrób kamienny kilof</white> <gray>(3 bruk + 2 patyki)</gray>";
            }

            // Stage 5: Generator stockpile (32 cobble/ores)
            if (cobble < 32) {
                checkStageAdvance(playerId, 5, "Kamienny kilof");
                // F24: „Wykop bruk z generatora (12/32)” mówiło czynność bez celu —
                // dokładnie to, na czym stanął gracz z sesji testowej („rozwalał
                // bloki, nie wiedząc o co chodzi”). Cel następnego etapu (sprzedaż)
                // wchodzi do linii TERAZ, a nie dopiero po 32 blokach.
                if (cobble == 0) {
                    return oneblock
                            ? "<yellow>▶</yellow> <white>Kop dalej — kamień leci z Twojego bloku</white>"
                            : "<yellow>▶</yellow> <white>Zbuduj generator bruku</white> <gray>(lawa + woda)</gray>";
                }
                return "<yellow>▶</yellow> <white>Wykop bruk na sprzedaż</white> <gray>(" + cobble + "/32)</gray>";
            }
        }

        // Stage 6: First shop trade
        if (wealth < FIRST_SALE_COINS) {
            checkStageAdvance(playerId, 6, "Generator bruku");
            return "<yellow>▶</yellow> <white>Sprzedaj surowce</white> <dark_gray>—</dark_gray> <yellow>/sklep</yellow>";
        }

        // Stage 7: zadanie dnia — sygnał DOBY, nie jednorazowy „kiedykolwiek
        // skończył quest". Dzięki temu linia wraca każdego dnia, dopóki zadanie
        // nie jest zrobione, i gaśnie w kilka sekund po jego wykonaniu.
        Function<UUID, CompletableFuture<Long>> questSignal = dailyQuestPoints;
        Long questPointsToday = questSignal == null ? null : dailyQuestSignals.value(playerId, questSignal);
        boolean dailyDone = questPointsToday != null
                ? questPointsToday > 0L
                : quests.hasCompletedToday(islandId);
        if (!dailyDone) {
            checkStageAdvance(playerId, 7, "Pierwszy zarobek");
            return "<yellow>▶</yellow> <white>Wykonaj zadanie dnia</white> <dark_gray>—</dark_gray> <yellow>/zadania</yellow>";
        }

        // Stage 8: oszczędności na Kuźnię (P0-1: spawnerów nie ma w sklepach).
        // P1-3: próg to najtańsza PŁATNA receptura kuźni (produkcja: ForgeConfig),
        // a linia mówi też o surowcach — sama cena nic nie wykuje. Bramka patrzy
        // na majątek (portfel + bank), żeby wpłata do banku nie cofała tu gracza.
        long forgeCost = forgeEntryCost();
        if (wealth < forgeCost) {
            checkStageAdvance(playerId, 8, "Misje dzienne");
            return "<yellow>▶</yellow> <white>Zbieraj monety na Kuźnię</white> <gray>(cel "
                    + Ui.money(forgeCost) + " + surowce)</gray> <dark_gray>—</dark_gray> <yellow>/kuznia</yellow>";
        }

        // Stage 9: bank wyspy. P1-3: liczy się DEPOZYT na koncie wyspy, nie
        // portfel — gracz z pełnym plecakiem i pustym bankiem nie odłożył nic,
        // a wcześniej ta bramka zaliczała się sama.
        if (bank < BANK_GOAL) {
            checkStageAdvance(playerId, 9, "Automatyzacja farmy");
            return "<yellow>▶</yellow> <white>Odłóż monety do banku</white> <dark_gray>—</dark_gray> <yellow>/bank</yellow>";
        }

        // Endgame — F24: ranking, o który tu chodzi, jest rankingiem PUNKTÓW
        // SEZONOWYCH (SeasonCommand#top na wpme_sb_season_points), a nie wysp:
        // Skyllia nie zna pojęcia poziomu ani wartości wyspy (dowód: F20 §3.1).
        // Linia mówiła więc o rywalizacji, której nie ma, i wskazywała komendę
        // pokazującą wyłącznie samą tabelę. /sezon pokazuje tę samą tabelę plus
        // to, czym się do niej dochodzi — i jest jedynym miejscem, w którym
        // tablica boczna w ogóle wspomina o sezonie.
        // P1-2 (audyt pętli gry 2026-09-05): po 25k tablica mówiła już tylko „Walcz o TOP
        // sezonu” i nowy gracz pytał „co dalej?”. Kolejne cele: minionek → talizman →
        // karnet → Kres (albo 5 minionków na wyspie klasycznej) → ogon weterana niżej.
        int minionCount = petMode ? petCounter.applyAsInt(playerId) : minionCounter.applyAsInt(islandId);
        if (minionCount < 1) {
            checkStageAdvance(playerId, 10, "Bogata wyspa");
            return petMode
                    ? "<yellow>▶</yellow> <white>Wykuj pierwszego minionka</white> <dark_gray>—</dark_gray> <yellow>/kuznia</yellow>"
                    : "<yellow>▶</yellow> <white>Wykuj pierwszego minionka</white> <dark_gray>—</dark_gray> <yellow>/kuznia</yellow>";
        }
        if (player == null || !talismanDetector.test(player)) {
            checkStageAdvance(playerId, 11, petMode ? "Pierwszy minionek" : "Pierwszy minionek");
            return "<yellow>▶</yellow> <white>Wykuj talizman</white> <gray>(kopanie, zbiory lub ryby)</gray> <dark_gray>—</dark_gray> <yellow>/kuznia</yellow>";
        }
        if (!premiumDetector.test(playerId)) {
            checkStageAdvance(playerId, 12, "Talizman");
            return "<yellow>▶</yellow> <white>Aktywuj karnet premium</white> <dark_gray>—</dark_gray> <yellow>/przepustka</yellow>";
        }
        if (oneblock) {
            if (!oneblockFinished.test(islandId)) {
                checkStageAdvance(playerId, 13, "Karnet premium");
                return "<yellow>▶</yellow> <white>Dokop się do rozdziału Kres</white> <dark_gray>—</dark_gray> <yellow>/oneblock</yellow>";
            }
        } else if (minionCount < (petMode ? 3 : 5)) {
            checkStageAdvance(playerId, 13, "Karnet premium");
            return petMode
                    ? "<yellow>▶</yellow> <white>Zbierz 3 minionki</white> <gray>(" + minionCount + "/3)</gray> <dark_gray>—</dark_gray> <yellow>/kuznia</yellow>"
                    : "<yellow>▶</yellow> <white>Rozbuduj wyspę do 5 minionków</white> <gray>(" + minionCount + "/5)</gray> <dark_gray>—</dark_gray> <yellow>/kuznia</yellow>";
        }
        // P1-3: ogon weterana. Do 2026-09-10 tablica kończyła się na „Walcz o TOP
        // sezonu” — czyli po całym łańcuchu celów gracz dostawał zero konkretu.
        // Teraz to łańcuch: kolejne minionki/pety do limitu miejsc → jedyny zlew
        // Złotych Lotosów (klucze w kantorze) → punkty sezonowe, które co 500 pkt
        // dają poziom bonusowy karnetu (SeasonPassService.BONUS_POINTS_PER_LEVEL).
        checkStageAdvance(playerId, 14, "Wyspa weterana");
        // Zaległe nagrody rozdziału OneBlocka stoją NAD ogonem weterana, ale ZA
        // etapami 1-13: gracz, który ma coś do odebrania, dostaje konkret, a
        // gracz w trakcie samouczka/karnetu — nie (linia nie przesłania tamtych).
        Integer unclaimed = oneblock ? unclaimedMilestones(islandId) : null;
        if (unclaimed != null && unclaimed > 0) {
            return "<yellow>▶</yellow> <white>Masz nieodebrane nagrody rozdziału</white> <gray>("
                    + unclaimed + ")</gray> <dark_gray>—</dark_gray> <yellow>/wyspa oneblock nagrody</yellow>";
        }
        int limit = slotLimit.applyAsInt(playerId);
        if (limit > 0 && minionCount < limit) {
            return petMode
                    ? "<yellow>▶</yellow> <white>Wykuj kolejnego minionka</white> <gray>(" + minionCount + "/"
                            + limit + ")</gray> <dark_gray>—</dark_gray> <yellow>/kuznia</yellow>"
                    : "<yellow>▶</yellow> <white>Postaw kolejnego minionka</white> <gray>(" + minionCount + "/"
                            + limit + ")</gray> <dark_gray>—</dark_gray> <yellow>/kuznia</yellow>";
        }
        if (player != null && lotusDetector.test(player)) {
            return "<gold>★</gold> <yellow>Wymień Złote Lotosy na klucze</yellow> <gray>(zlew weterana)</gray> <dark_gray>—</dark_gray> <yellow>/kantor</yellow>";
        }
        return "<gold>★</gold> <yellow>Zbieraj punkty sezonowe</yellow> <dark_gray>—</dark_gray> <yellow>/sezon</yellow> <gray>(bonusowe poziomy karnetu co 500 pkt)</gray>";
    }

    /** Próg Kuźni z wpiętego źródła; zero albo wartość ujemna wraca do progu awaryjnego. */
    private long forgeEntryCost() {
        long cost = forgeEntryCost.getAsLong();
        return cost > 0L ? cost : FALLBACK_FORGE_ENTRY_COST;
    }

    /**
     * Depozyt wyspy (konto WYSPY w księdze) — czytany z cache, więc bez I/O na
     * wątku regionu. Bez wpięcia źródła, albo zanim pierwszy odczyt dojdzie,
     * zwracamy portfel gracza: dokładnie to, na co patrzył przewodnik przed tą
     * zmianą, więc brak wiązania niczego nie psuje.
     */
    private long islandDeposit(UUID islandId, long walletBalance) {
        Function<UUID, CompletableFuture<Long>> source = islandBankBalance;
        if (source == null) {
            return walletBalance;
        }
        Long deposit = bankDepositSignals.value(islandId, source);
        return deposit != null ? deposit : walletBalance;
    }

    /**
     * Zaległe nagrody rozdziału OneBlocka — z cache, więc bez I/O na wątku
     * regionu. Bez wpięcia źródła albo zanim pierwszy odczyt dojdzie, zwracamy
     * {@code null} (brak linii): dokładnie to, co przewodnik mówił przed zmianą.
     */
    private @Nullable Integer unclaimedMilestones(UUID islandId) {
        Function<UUID, CompletableFuture<Integer>> source = oneblockUnclaimedRewards;
        return source == null ? null : oneblockRewardSignals.value(islandId, source);
    }

    private void checkStageAdvance(UUID playerId, int stage, String stageName) {
        Integer prev = highestStage.put(playerId, stage);
        if (prev != null && stage > prev) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.getScheduler().run(plugin, task -> {
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                    player.sendActionBar(miniMessage.deserialize(
                            "<green>✔ Ukończono etap:</green> <yellow>" + stageName + "</yellow>!"));
                }, null);
            }
            notifyUpdate(playerId);
        }
    }

    private boolean hasAnyPickaxe(Player player) {
        if (player == null) return false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType().name().endsWith("_PICKAXE")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasStoneOrBetterPickaxe(Player player) {
        if (player == null) return false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null) {
                Material t = item.getType();
                if (t == Material.STONE_PICKAXE || t == Material.IRON_PICKAXE
                        || t == Material.DIAMOND_PICKAXE || t == Material.NETHERITE_PICKAXE
                        || t == Material.GOLDEN_PICKAXE) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        observeBreak(event.getPlayer(), event.getBlock().getType());
    }

    /**
     * Licznik materialow dla przewodnika. Wywolane z nasluchu VANILLA (postawione
     * drzewa/kamien) ORAZ z obserwatora OneBlocka — rozbicie centra jest anulowane,
     * wiec MONITOR ignoreCancelled nigdy by go nie zobaczyl i przewodnik stalby
     * na wieki przy „Zdobadz 4 klody" (finding 2026-08-31).
     */
    public void observeBreak(@NotNull Player player, @NotNull Material type) {
        UUID id = player.getUniqueId();

        if (Tag.LOGS.isTagged(type)) {
            AtomicInteger count = minedLogs.computeIfAbsent(id, k -> new AtomicInteger(0));
            int val = count.incrementAndGet();
            if (val == 4) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                player.sendActionBar(miniMessage.deserialize(
                        "<green>✔ Zrobione:</green> <yellow>masz drewno (4/4)</yellow>"));
            }
            notifyUpdate(id);
        } else if (isCobbleOrStone(type)) {
            AtomicInteger count = minedCobblestone.computeIfAbsent(id, k -> new AtomicInteger(0));
            int val = count.incrementAndGet();
            if (val == 3) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                player.sendActionBar(miniMessage.deserialize(
                        "<green>✔ Zrobione:</green> <yellow>bruk na kamienny kilof (3/3)</yellow>"));
            } else if (val == 32) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                player.sendActionBar(miniMessage.deserialize(
                        "<green>✔ Zrobione:</green> <yellow>32 bruku z generatora</yellow>"));
            }
            notifyUpdate(id);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            ItemStack result = event.getRecipe().getResult();
            Material mat = result.getType();
            UUID id = player.getUniqueId();
            if (mat == Material.WOODEN_PICKAXE) {
                if (craftedWoodPickaxe.put(id, true) == null) {
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                    player.sendActionBar(miniMessage.deserialize(
                            "<green>✔ Zrobione:</green> <yellow>drewniany kilof</yellow>"));
                }
                notifyUpdate(id);
            } else if (mat == Material.STONE_PICKAXE) {
                if (craftedStonePickaxe.put(id, true) == null) {
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.5f);
                    player.sendActionBar(miniMessage.deserialize(
                            "<green>✔ Zrobione:</green> <yellow>kamienny kilof</yellow>"));
                }
                notifyUpdate(id);
            }
        }
    }

    private static boolean isCobbleOrStone(Material material) {
        return material == Material.COBBLESTONE
                || material == Material.STONE
                || material == Material.DEEPSLATE
                || material == Material.COBBLED_DEEPSLATE
                || material == Material.ANDESITE
                || material == Material.DIORITE
                || material == Material.GRANITE;
    }

    /**
     * Sygnał liczbowy, którego wartość siedzi w bazie (saldo banku wyspy, punkty
     * dnia, nieodebrane nagrody OneBlocka), a który przewodnik musi czytać bez
     * blokowania wątku regionu — {@link #resolveObjective} chodzi co 5 s na
     * wątku właściciela encji.
     *
     * <p>Wartość czytamy z pamięci, a odświeżenie zlecamy w tle najwyżej raz na
     * {@code ttlMillis} i najwyżej jedno naraz na klucz. Dopóki świeżej wartości
     * nie ma, zostaje ostatnia znana (brak = decyzję o fallbacku podejmuje
     * wywołujący). Nieudany odczyt też odnotowujemy, żeby awaria bazy nie
     * zamieniła się w gorącą pętlę zapytań.
     *
     * <p>Źródło MUSI zwracać {@link CompletableFuture} wykonane poza wątkiem
     * regionu (w praktyce DAO na {@code SqlService}).
     */
    private static final class AsyncSignalCache<T> {

        private final java.util.function.LongSupplier ttlMillis;
        private final Map<UUID, T> values = new ConcurrentHashMap<>();
        private final Map<UUID, Long> refreshedAt = new ConcurrentHashMap<>();
        private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();

        AsyncSignalCache(java.util.function.LongSupplier ttlMillis) {
            this.ttlMillis = ttlMillis;
        }

        /** Ostatnia znana wartość dla klucza albo {@code null}, gdy nic jeszcze nie przyszło. */
        @Nullable T value(@NotNull UUID key, @NotNull Function<UUID, CompletableFuture<T>> source) {
            long now = System.currentTimeMillis();
            Long last = refreshedAt.get(key);
            if ((last == null || now - last >= ttlMillis.getAsLong()) && refreshing.add(key)) {
                CompletableFuture<T> pending;
                try {
                    pending = source.apply(key);
                } catch (RuntimeException failure) {
                    pending = null;
                }
                if (pending == null) {
                    refreshing.remove(key);
                    refreshedAt.put(key, now);
                } else {
                    pending.whenComplete((fetched, failure) -> {
                        refreshing.remove(key);
                        refreshedAt.put(key, System.currentTimeMillis());
                        if (failure == null && fetched != null) {
                            values.put(key, fetched);
                        }
                    });
                }
            }
            return values.get(key);
        }
    }
}
