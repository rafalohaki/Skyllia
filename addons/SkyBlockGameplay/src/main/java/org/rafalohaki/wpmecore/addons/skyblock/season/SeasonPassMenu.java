package org.rafalohaki.wpmecore.addons.skyblock.season;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.SkyBlockGameplay;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;
import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M2.5: menu przepustki sezonowej ({@code /sezon pass}, spec §5).
 *
 * <p>28 poziomów w jednym ekranie (sloty 9..36), przełącznik toru
 * FREE/PREMIUM, aktywacja premium za Złote Lotosy z ekwipunku.
 * Dane gracza (poziom, claimy) ładowane async, otwarcie na wątku
 * encji gracza — konwencja jak w {@code DailyQuestService}.
 *
 * <p><b>Etykiety edycji.</b> Tytuł zachowuje prefiks „Przepustka Sezonowa ”
 * + segment z {@code plugin.seasonLabel()} (kompatybilność e2e prefix-match);
 * opcjonalny dostawca {@code rangeDetail} dokłada drugą szarą linię zakresu
 * do ikony info. {@code null} = zachowanie legacy 1:1.
 */
public final class SeasonPassMenu {

    private static final int FIRST_LEVEL_SLOT = 9;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_TOGGLE = 48;
    private static final int SLOT_PREMIUM = 49;
    /** P2-1: kafelek poziomów bonusowych (po L28 co 500 pkt). */
    private static final int SLOT_BONUS = 47;
    /**
     * Ścieżka w {@code config.yml} do kosztu aktywacji toru premium.
     *
     * <p>F20: to liczba balansowa, nie techniczna. Sklep 2b2t.pl nie ma SKU na
     * Złoty Lotos ani na karnet (pełna lista {@code product_id} w migracjach
     * {@code 2b2t-2026}), więc cena niczego nie chroni — a jej strojenie nie
     * może wymagać przebudowy jara i podmiany pluginu.
     */
    public static final String PREMIUM_LOTUS_COST_PATH = "season.pass.premium-lotus-cost";
    /**
     * Domyślny koszt aktywacji toru premium w Złotych Lotosach z ekwipunku.
     *
     * <p>Rachunek F20 (sezon = 56 dni = 8 tygodni): tygodniowy kamień milowy
     * zadań daje 8 lotosów, Skrzynia Podziemi (4 %) ~1,3, Skrzynia Tytanów
     * (8 % × 2) ~0,8, kamienie milowe OneBlocka ~3,8 — ale tylko na wyspie
     * OneBlock i dopiero po 5000 bloków. Realnie: ~6-9 na wyspie klasycznej,
     * ~13 na OneBlocku. Sześć = sześć tygodniowych kamieni milowych, czyli
     * ¾ sezonu przy samych zadaniach. Poprzednie 32 to cztery sezony, więc tor
     * premium był nieosiągalny w sezonie, dla którego istnieje.
     */
    public static final int DEFAULT_PREMIUM_LOTUS_COST = 6;
    /** Górna granica sensownej ceny: jeden stack w ekwipunku gracza. */
    private static final int MAX_PREMIUM_LOTUS_COST = 64;

    private final SkyBlockGameplay plugin;
    private final MenuService menus;
    private final MiniMessage miniMessage;
    private final SeasonPassService pass;
    private final LedgerService ledger;
    private final InventoryOutbox outbox;
    private final CustomItemService customItems;
    /**
     * Opcjonalny szczegół zakresu edycji ({@code null} = tryb legacy).
     * Gdy dostępny, trafia jako druga szara linia do ikony info.
     */
    @Nullable private final java.util.function.Supplier<String> rangeDetail;
    /** Koszt aktywacji toru premium; z configu, walidowany przy starcie. */
    private final int premiumLotusCost;

    private final Map<UUID, Boolean> opening = new ConcurrentHashMap<>();

    public SeasonPassMenu(@NotNull SkyBlockGameplay plugin,
                          @NotNull MenuService menus,
                          @NotNull MiniMessage miniMessage,
                          @NotNull SeasonPassService pass,
                          @NotNull LedgerService ledger,
                          @NotNull InventoryOutbox outbox,
                          @NotNull CustomItemService customItems) {
        this(plugin, menus, miniMessage, pass, ledger, outbox, customItems,
                DEFAULT_PREMIUM_LOTUS_COST, null);
    }

    /**
     * Wariant edition-aware: nie-null {@code rangeDetail} dokłada szarą linię
     * zakresu do ikony info; tytuł bez zmian (prefiks + segment etykiety).
     */
    public SeasonPassMenu(@NotNull SkyBlockGameplay plugin,
                          @NotNull MenuService menus,
                          @NotNull MiniMessage miniMessage,
                          @NotNull SeasonPassService pass,
                          @NotNull LedgerService ledger,
                          @NotNull InventoryOutbox outbox,
                          @NotNull CustomItemService customItems,
                          int premiumLotusCost,
                          @Nullable java.util.function.Supplier<String> rangeDetail) {
        this.plugin = plugin;
        this.menus = menus;
        this.miniMessage = miniMessage;
        this.pass = pass;
        this.ledger = ledger;
        this.outbox = outbox;
        this.customItems = customItems;
        this.premiumLotusCost = premiumLotusCost;
        this.rangeDetail = rangeDetail;
    }

    /**
     * Odczyt ceny z konfiguracji z walidacją zakresu. Poza zakresem 1..64
     * wraca wartość domyślna z ostrzeżeniem — literówka w liczbie balansowej
     * nie może ani rozdać toru premium za darmo, ani zdjąć serwera.
     */
    public static int resolvePremiumLotusCost(
            @NotNull org.bukkit.configuration.Configuration config,
            @NotNull java.util.logging.Logger logger) {
        int cost = config.getInt(PREMIUM_LOTUS_COST_PATH, DEFAULT_PREMIUM_LOTUS_COST);
        if (cost < 1 || cost > MAX_PREMIUM_LOTUS_COST) {
            logger.warning(PREMIUM_LOTUS_COST_PATH + ": " + cost
                    + " poza zakresem 1.." + MAX_PREMIUM_LOTUS_COST
                    + "; używam domyślnych " + DEFAULT_PREMIUM_LOTUS_COST);
            return DEFAULT_PREMIUM_LOTUS_COST;
        }
        return cost;
    }

    /** Otwiera menu na torze darmowym (domyślne wejście). */
    public void open(@NotNull Player player) {
        open(player, SeasonPassService.Track.FREE);
    }

    public void open(@NotNull Player player, @NotNull SeasonPassService.Track track) {
        UUID uuid = player.getUniqueId();
        if (opening.putIfAbsent(uuid, true) != null) {
            return;
        }
        var levelFuture = pass.levelOf(uuid);
        var premiumFuture = pass.isPremium(uuid);
        var claimedFuture = pass.claimedLevels(uuid, track);
        // D5: nie wolno joinować DAO-future w tasku schedulera encji (blokowałoby
        // wątek ticka, gdy dane jeszcze nie są gotowe). Wzorzec jak w
        // DailyQuestService: czekaj przez allOf.whenComplete na wątku SQL-lane
        // (ciało trywialne: hop do schedulera encji), join dopiero tam, gdzie
        // futures są już zakończone. Guard podwójnego otwarcia bez zmian.
        CompletableFuture.allOf(levelFuture, premiumFuture, claimedFuture)
                .whenComplete((ignored, failure) -> {
                    try {
                        var scheduled = player.getScheduler().run(plugin, innerIgnored -> {
                            opening.remove(uuid);
                            if (failure != null) {
                                plugin.getLogger().warning("Przepustka: nie udało się wczytać stanu: "
                                        + failure.getMessage());
                                player.sendMessage(Ui.component(miniMessage,
                                        "<red>Nie udało się otworzyć przepustki. Spróbuj ponownie.</red>"));
                                return;
                            }
                            buildAndOpen(player, track,
                                    levelFuture.join(), premiumFuture.join(), claimedFuture.join());
                        }, () -> opening.remove(uuid));
                        if (scheduled == null) {
                            opening.remove(uuid);
                        }
                    } catch (RuntimeException rejected) {
                        opening.remove(uuid);
                        plugin.getLogger().fine("Przepustka: harmonogram otwarcia odrzucony: " + rejected);
                    }
                });
    }

    private void buildAndOpen(@NotNull Player player, @NotNull SeasonPassService.Track track,
                              int level, boolean premium, @NotNull Set<Integer> claimed) {
        String label = plugin.seasonLabel();
        MenuService.Menu menu = menus.ofRows(6, Ui.component(miniMessage,
                "<gold><bold>Przepustka Sezonowa</bold></gold>"
                        + (label == null ? "" : " <gold>" + label + "</gold>")));
        Ui.frame(menu, miniMessage, Material.LIGHT_BLUE_STAINED_GLASS_PANE);

        List<String> infoLore = new java.util.ArrayList<>();
        int shownLevel = Math.min(level, SeasonPassService.PILOT_LEVELS);
        int bonusLevels = Math.max(0, level - SeasonPassService.PILOT_LEVELS);
        infoLore.add("<gray>Twój poziom: <gold><bold>" + shownLevel + "</bold></gold> / <white>" + SeasonPassService.PILOT_LEVELS + "</white>"
                + (bonusLevels > 0 ? " <gold>+" + bonusLevels + " bonus</gold>" : ""));
        String range = rangeDetail == null ? null : rangeDetail.get();
        if (range != null && !range.isEmpty()) {
            infoLore.add("<gray>" + range + "</gray>");
        }
        infoLore.add("<dark_gray> </dark_gray>");
        infoLore.add("<gray>Każde <yellow>" + SeasonPassService.POINTS_PER_LEVEL
                + " punktów sezonowych</yellow> to jeden poziom wyżej.");
        infoLore.add("<gray>Rozbieg: <white>zadania sezonowe</white> — raz na sezon.");
        infoLore.add("<gray>Dalsze poziomy: <white>łowienie ryb</white>, bez limitu.");
        infoLore.add("<gray>Szczegóły i ranking: <yellow>/sezon</yellow>");
        infoLore.add("<dark_gray> </dark_gray>");
        infoLore.add("<gray>Tor darmowy: <white>monety i surowce</white>, dla każdego.");
        if (premium) {
            infoLore.add("<gray>Tor premium: <green><bold>AKTYWNY</bold></green> — odbierasz podwójne nagrody!");
        } else {
            infoLore.add("<gray>Tor premium: <red>nieaktywny</red>. Nagrody premium czekają");
            infoLore.add("<gray>na wszystkich poziomach — nic nie przepada.");
        }
        menu.decoration(SLOT_INFO, Ui.item(Material.ENCHANTED_BOOK, miniMessage,
                "<white></white> <gradient:#ffd700:#ff8c00><bold>Karnet Sezonowy</bold></gradient>",
                List.copyOf(infoLore),
                true));

        for (int i = 0; i < SeasonPassService.PILOT_LEVELS; i++) {
            int lvl = i + 1;
            menu.set(FIRST_LEVEL_SLOT + i, levelIcon(track, lvl, level, premium,
                    claimed.contains(lvl)), (viewer, click) -> claim(player, track, lvl));
        }

        menu.set(SLOT_BONUS, bonusIcon(level, claimed), (viewer, click) -> claimNextBonus(player, track, level, claimed));

        menu.set(SLOT_TOGGLE, toggleIcon(track), (viewer, click) ->
                open(player, track == SeasonPassService.Track.FREE
                        ? SeasonPassService.Track.PREMIUM : SeasonPassService.Track.FREE));

        if (premium) {
            menu.decoration(SLOT_PREMIUM, Ui.item(Material.NETHER_STAR, miniMessage,
                    "<gradient:#ffd700:#ff8c00><bold>Tor Premium: AKTYWNY</bold></gradient>",
                    List.of("<green>✔ Tor Premium jest odblokowany w tym sezonie.",
                            "<gray>Odbierasz nagrody z obu torów!</gray>"), true));
        } else {
            menu.set(SLOT_PREMIUM, premiumOfferIcon(), (viewer, click) -> activatePremium(player));
        }

        menu.close(53, Ui.closeButton(miniMessage));
        menu.open(player);
    }

    /** Najniższy nieodebrany poziom bonusowy w zasięgu gracza albo -1. */
    static int nextClaimableBonus(int playerLevel, @NotNull Set<Integer> claimed) {
        for (int lvl = SeasonPassService.PILOT_LEVELS + 1; lvl <= playerLevel; lvl++) {
            if (!claimed.contains(lvl)) {
                return lvl;
            }
        }
        return -1;
    }

    private @NotNull ItemStack bonusIcon(int playerLevel, @NotNull Set<Integer> claimed) {
        int unlocked = Math.max(0, playerLevel - SeasonPassService.PILOT_LEVELS);
        int next = nextClaimableBonus(playerLevel, claimed);
        List<String> lore = new java.util.ArrayList<>();
        lore.add("<gray>Po 28. poziomie każde <yellow>" + SeasonPassService.BONUS_POINTS_PER_LEVEL
                + " pkt</yellow> to kolejna nagroda.");
        lore.add("<gray>Tor darmowy: " + Ui.price(SeasonPassRewards.BONUS_FREE_MONEY)
                + " <gray>+ <white>" + Ui.ICON_LOTUS + " Srebrny Lotos</white>");
        lore.add("<gray>Tor premium: <white>" + Ui.ICON_LOTUS + " 2× Srebrny Lotos + Klucz Wolframowy</white>");
        lore.add("<dark_gray> </dark_gray>");
        lore.add("<gray>Odblokowane poziomy bonusowe: <white>" + unlocked + "</white>");
        if (next > 0) {
            lore.add("<green>▶ Kliknij, aby odebrać poziom " + next + "</green>");
        } else if (unlocked > 0) {
            lore.add("<dark_gray>Wszystko odebrane — zbieraj dalej.</dark_gray>");
        } else {
            lore.add("<dark_gray>Najpierw wbij 28. poziom.</dark_gray>");
        }
        return Ui.item(Material.NETHERITE_INGOT, miniMessage,
                "<white></white> <gradient:#f6c453:#ffffff><bold>Poziomy bonusowe</bold></gradient>",
                List.copyOf(lore), next > 0);
    }

    private void claimNextBonus(@NotNull Player player, @NotNull SeasonPassService.Track track,
                                int playerLevel, @NotNull Set<Integer> claimed) {
        int next = nextClaimableBonus(playerLevel, claimed);
        if (next < 0) {
            player.sendMessage(Ui.component(miniMessage,
                    "<gray>Brak poziomu bonusowego do odbioru — kolejny za każde "
                            + SeasonPassService.BONUS_POINTS_PER_LEVEL + " pkt po 28. poziomie.</gray>"));
            return;
        }
        claim(player, track, next);
    }

    private @NotNull ItemStack levelIcon(@NotNull SeasonPassService.Track track, int level,
                                         int playerLevel, boolean premium, boolean claimed) {
        boolean isMilestone = (level == 7 || level == 14 || level == 21 || level == 28);
        String trackLabel = track == SeasonPassService.Track.FREE ? "<yellow>Darmowy</yellow>"
                : (premium ? "<gold>Premium</gold>" : "<red>Premium (nieaktywny)</red>");
        List<String> lore = new java.util.ArrayList<>();
        lore.add("<gray>Tor: " + trackLabel);
        if (track == SeasonPassService.Track.FREE) {
            lore.add("<yellow>•</yellow> <gray>Nagroda: " + Ui.price(SeasonPassRewards.freeMoney(level))
                    + "</gray>");
            SeasonPassRewards.freeItem(level).ifPresent(r ->
                    lore.add("<aqua>•</aqua> <gray>Bonus: <white>" + r.name() + "</white></gray>"));
        } else {
            for (SeasonPassRewards.Reward r : SeasonPassRewards.premiumItems(level)) {
                lore.add("<gold>•</gold> <gray>Nagroda: <white>" + r.name() + "</white></gray>");
            }
        }
        lore.add("<dark_gray> </dark_gray>");
        Material material;
        String name;
        if (claimed) {
            material = Material.LIME_DYE;
            name = "<green><bold>✔ Poziom " + level + " — Odebrano</bold></green>";
        } else if (level <= playerLevel
                && (track == SeasonPassService.Track.FREE || premium)) {
            material = isMilestone ? Material.ENDER_CHEST : Material.CHEST;
            name = isMilestone
                    ? "<gold><bold>⭐ Poziom " + level + " — Kamień Milowy!</bold></gold>"
                    : "<yellow><bold>✦ Poziom " + level + " — Do Odbioru!</bold></yellow>";
            lore.add("<yellow>Kliknij, aby odebrać nagrodę!</yellow>");
        } else {
            material = isMilestone ? Material.CHEST : Material.GRAY_DYE;
            name = isMilestone
                    ? "<gray><bold>🔒 Poziom " + level + " (Kamień Milowy)</bold></gray>"
                    : "<dark_gray><bold>🔒 Poziom " + level + "</bold></dark_gray>";
            if (level > playerLevel) {
                lore.add("<gray>Wymaga: <white>"
                        + (level * SeasonPassService.POINTS_PER_LEVEL) + "</white> pkt. sezonowych</gray>");
            } else {
                lore.add("<red>Wymaga aktywnego toru premium.</red>");
            }
        }
        return Ui.item(material, miniMessage, name, List.copyOf(lore), (!claimed && level <= playerLevel) || isMilestone);
    }

    private @NotNull ItemStack toggleIcon(@NotNull SeasonPassService.Track track) {
        Material mat = track == SeasonPassService.Track.FREE ? Material.FEATHER : Material.NETHER_STAR;
        return Ui.accent(miniMessage, mat,
                "<white><bold>Tor: " + (track == SeasonPassService.Track.FREE ? "<yellow>DARMOWY</yellow>" : "<gold>PREMIUM</gold>")
                        + "</bold></white>",
                List.of("<gray>Aktualnie przeglądasz nagrody z tego toru.</gray>",
                        "<yellow>Kliknij, aby przełączyć na drugi tor.</yellow>"));
    }

    private @NotNull ItemStack premiumOfferIcon() {
        return Ui.item(Material.SUNFLOWER, miniMessage,
                "<gradient:#ffd700:#ff8c00><bold>⚜ Aktywuj Tor Premium</bold></gradient>",
                List.of(
                        "<gray>Otwiera drugi tor nagród na <white>wszystkich</white>",
                        "<gray>poziomach, także tych już zdobytych.",
                        "<dark_gray> </dark_gray>",
                        "<gray>Koszt: </gray>" + Ui.ICON_LOTUS + " <gold><bold>" + premiumLotusCost + "× Złoty Lotos</bold></gold>",
                        "<gray>Złoty Lotos zdobywasz m.in. z zadań tygodniowych,",
                        "<gray>skrzyń na spawnie oraz kuźni.</gray>",
                        "<dark_gray> </dark_gray>",
                        "<yellow>Kliknij, aby odblokować Tor Premium!</yellow>"), true);
    }

    /**
     * Brama UPRAWNIEŃ oddzielona od idempotencji (wzór
     * {@link SkyBlockTopRewardCoordinator#rejectClaim}): {@code pass.isEligible}
     * sprawdza poziom/premium PRZED jakąkolwiek wypłatą — klik w zablokowany
     * poziom (za wysoki albo tor PREMIUM bez premium) to no-op, zero monet,
     * zero przedmiotów. Dopiero po pozytywnej bramie płacimy.
     *
     * <p>Kolejność wg lekcji SKYBLOCK-1-6: najpierw idempotentne granty
     * (ledger txId UNIQUE, outbox operationId), dopiero na końcu claim jako
     * commit. Awaria w środku = gracz klika ponownie bez duplikatów.
     */
    // Package-private dla testu bramki uprawnień (SeasonPassMenuClaimGateTest).
    void claim(@NotNull Player player, @NotNull SeasonPassService.Track track, int level) {
        UUID uuid = player.getUniqueId();
        pass.isEligible(uuid, level, track)
                .thenCompose(eligible -> Boolean.TRUE.equals(eligible)
                        ? payAndCommit(player, track, level)
                        : CompletableFuture.completedFuture(Boolean.FALSE))
                .thenAccept(ok -> {
                    if (Boolean.TRUE.equals(ok)) {
                        runOnEntityThread(player, () -> {
                            player.sendMessage(Ui.component(miniMessage,
                                    "<green>Odebrano nagrodę z poziomu <white>" + level + "</white>!</green>"));
                            open(player, track);
                        });
                    }
                })
                .exceptionally(failure -> {
                    plugin.getLogger().warning("Przepustka: nie udało się wydać nagrody L" + level
                            + ": " + failure.getMessage());
                    runOnEntityThread(player, () -> {
                        player.sendMessage(Ui.component(miniMessage,
                                "<red>Nie udało się wydać nagrody. Spróbuj ponownie za chwilę.</red>"));
                        open(player, track);
                    });
                    return null;
                });
    }

    /**
     * Wypłata po pozytywnej bramie uprawnień. Zwraca wynik {@code pass.claim}
     * (true = właśnie odebrano). Gdy któryś grant nie zejdzie trwale, NIE
     * commitujemy claimu — gracz klika ponownie, a idempotentne granty się nie
     * dublują.
     */
    private @NotNull CompletableFuture<Boolean> payAndCommit(@NotNull Player player,
            @NotNull SeasonPassService.Track track, int level) {
        UUID uuid = player.getUniqueId();
        long money = track == SeasonPassService.Track.FREE ? SeasonPassRewards.freeMoney(level) : 0L;
        // SEZON-1: klucz idempotencji MUSI zawierać UUID odbierającego gracza —
        // bez niego pierwszy gracz na danym poziomie „zjadał” nagrody wszystkich
        // kolejnych (ledger: cichy no-op na duplikacie txId, outbox: cudzy wiersz).
        // Klucz pozostaje deterministyczny per gracz+poziom (retry po crashu
        // idempotentny), ~80 znaków mieści się w limicie 128.
        String txBase = "season-pass:" + pass.currentSeason() + ":" + track.name()
                + ":L" + level + ":" + uuid;
        List<SeasonPassRewards.Reward> rewards = track == SeasonPassService.Track.PREMIUM
                ? SeasonPassRewards.premiumItems(level)
                : SeasonPassRewards.freeItem(level).map(List::of).orElse(List.of());

        CompletableFuture<Void> grants = money > 0
                ? ledger.depositPlayer(uuid, money, txBase + ":money",
                        "Nagroda przepustki L" + level).thenApply(m -> null)
                : CompletableFuture.completedFuture(null);
        return grants
                .thenCompose(v -> deliverAll(player, txBase, rewards))
                .thenCompose(delivered -> Boolean.TRUE.equals(delivered)
                        ? pass.claim(uuid, level, track)
                        : CompletableFuture.completedFuture(Boolean.FALSE));
    }

    /**
     * Trwała dostawa przedmiotów nagrody przez outbox z deterministycznym
     * {@code operationId}: paragon zdejmowany przy COMPLETE, powtórka (drugi
     * klik, zrzucenie/zużycie sztuki) jest no-opem po kluczu operacji — inaczej
     * niż {@code deliverTaggedProduct}, gdzie usunięcie otagowanej sztuki
     * pozwalało dokładać w kółko.
     *
     * @return true gdy wszystkie granty zeszły trwale (SUCCESS/DEFERRED)
     */
    private @NotNull CompletableFuture<Boolean> deliverAll(@NotNull Player player,
            @NotNull String txBase, @NotNull List<SeasonPassRewards.Reward> rewards) {
        CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(Boolean.TRUE);
        for (int i = 0; i < rewards.size(); i++) {
            SeasonPassRewards.Reward reward = rewards.get(i);
            String operationId = txBase + ":item" + i;
            chain = chain.thenCompose(ok -> Boolean.TRUE.equals(ok)
                    ? grantOne(player, operationId, buildStack(reward))
                    : CompletableFuture.completedFuture(Boolean.FALSE));
        }
        return chain;
    }

    private @NotNull CompletableFuture<Boolean> grantOne(@NotNull Player player,
            @NotNull String operationId, @NotNull ItemStack stack) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        outbox.beginGrant(player, 0L, operationId, "season-pass-reward", stack,
                outcome -> done.complete(outcome == InventoryOutbox.Outcome.SUCCESS
                        || outcome == InventoryOutbox.Outcome.DEFERRED));
        return done;
    }

    private @NotNull ItemStack buildStack(@NotNull SeasonPassRewards.Reward reward) {
        ItemStack stack = reward.customItemId() != null
                ? customItems.create(reward.customItemId()).orElse(null)
                : null;
        if (stack == null) {
            stack = new ItemStack(reward.material(), reward.amount());
        } else {
            stack.setAmount(Math.max(1, reward.amount()));
        }
        return stack;
    }

    /** Zakup toru premium za Złote Lotosy — z menu albo z `/przepustka premium` (migracja Eco). */
    public void activatePremium(@NotNull Player player) {
        int held = countLotus(player);
        if (held < premiumLotusCost) {
            player.sendMessage(Ui.component(miniMessage,
                    "<red>Potrzebujesz <white>" + premiumLotusCost
                            + "x Złoty Lotos</white> (masz " + held + ").</red>"));
            return;
        }
        removeLotus(player, premiumLotusCost);
        pass.activatePremium(player.getUniqueId())
                .thenAccept(activated -> runOnEntityThread(player, () -> {
                    if (activated) {
                        player.sendMessage(Ui.component(miniMessage,
                                "<gold><bold>Tor premium aktywny!</bold></gold>"));
                        // Migracja Eco: premium karnetu żyje w EcoBattlepass — ustawiamy je komendą
                        // z konsoli na wątku globalnym (Folia). Pusty id = tylko nasz karnet.
                        String ecoPass = plugin.getConfig().getString("season.pass.eco-battlepass-id", "");
                        if (ecoPass != null && !ecoPass.isBlank()) {
                            String command = "ecobattlepass setpremium " + player.getName() + " " + ecoPass.trim() + " true";
                            org.bukkit.Bukkit.getGlobalRegionScheduler().run(plugin, task ->
                                    org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), command));
                        }
                    } else {
                        // Wyścig: premium już aktywne (idempotencja) — zwrot lotosów,
                        // żeby podwójne kliknięcie nie kosztowało podwójnie.
                        returnLotus(player, premiumLotusCost);
                    }
                    open(player, SeasonPassService.Track.PREMIUM);
                }))
                .exceptionally(error -> {
                    java.util.logging.Logger.getLogger(SeasonPassMenu.class.getName())
                            .warning("activatePremium failed: " + error);
                    runOnEntityThread(player, () -> {
                        returnLotus(player, premiumLotusCost);
                        player.sendMessage(Ui.component(miniMessage,
                                "<red>Aktywacja toru premium nieudana — lotosy zwrócone.</red>"));
                        open(player, SeasonPassService.Track.FREE);
                    });
                    return null;
                });
    }

    private int countLotus(@NotNull Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            if ("skyblock:token/gold_lotus".equals(customItems.idOf(item))) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private void removeLotus(@NotNull Player player, int amount) {
        int left = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.isEmpty()
                    || !"skyblock:token/gold_lotus".equals(customItems.idOf(item))) {
                continue;
            }
            int take = Math.min(left, item.getAmount());
            left -= take;
            if (take >= item.getAmount()) {
                player.getInventory().setItem(i, null);
            } else {
                item.setAmount(item.getAmount() - take);
            }
        }
    }

    private void returnLotus(@NotNull Player player, int amount) {
        customItems.create("skyblock:token/gold_lotus").ifPresent(stack -> {
            stack.setAmount(amount);
            outbox.deliverTaggedProduct(player,
                    "season-pass:premium-refund:" + System.currentTimeMillis(), stack);
        });
    }

    /**
     * A6 (Folia): kontynuacja po future z DAO biegnie na wątku zakończenia
     * (SQL-lane), a {@code sendMessage}/{@code openInventory} wolno wołać
     * wyłącznie na wątku encji gracza. Wzorzec jak w
     * {@code SeasonCloseConfirmMenu.runOnEntityThread}: gdy scheduler odrzuci
     * albo zwróci null (testy bez serwera), zadanie leci inline.
     */
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
}
