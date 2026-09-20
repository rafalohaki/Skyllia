package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.shared.ItemNames;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.item.ItemPolicyMarkers;
import org.rafalohaki.wpmecore.api.service.MenuService;

import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P2#2: menu gracza {@code /kosmetyki} — podgląd posiadanej kosmetyki
 * sezonowej oraz zakładanie i zdejmowanie egzemplarzy.
 *
 * <p><b>Źródło prawdy o posiadaniu.</b> Rejestr posiadanych egzemplarzy nie
 * istnieje w bazie: outbox jest przejściowy (wiersze kasują się po doręczeniu),
 * a {@code wpme_sb_seasonal_claims} rejestruje roszczenie wyspy do podium
 * sezonu, nie egzemplarze gracza — kanał sklepowy ({@code grantSingle}) omija
 * ją całkowicie. Posiadaniem jest więc fizyczny, ostemplowany egzemplarz
 * ({@link ItemPolicyMarkers#isCollectible}) w ekwipunku gracza — dokładnie to,
 * co gracz może założyć tu i teraz. Menu skanuje ekwipunek na wątku encji.
 * Pełne uzasadnienie: {@code docs/spec/p2-2-kosmetyki-gracza.md}.
 *
 * <p><b>Noszenie.</b> Jedna część na slot ciała — mechanika pancerza wymusza
 * unikatowość slotu, a zamiana z powrotem do ekwipunku odbywa się w menu.
 * Operacje są idempotentne: ponowne założenie już noszonej części nic nie
 * zmienia, ponowne zdjęcie też. Zwykły pancerz w slocie blokuje założenie
 * dopóki gracz nie zwolni miejsca w ekwipunku — nic nigdzie nie ginie.
 */
public final class CosmeticPlayerMenu {

    private static final int SLOT_INFO = 4;
    /** Pierwszy slot siatki posiadanych części (rząd 1). */
    static final int FIRST_PIECE_SLOT = 9;
    /** Ostatni slot siatki posiadanych części (koniec rzędu 3) — 27 pozycji. */
    static final int LAST_PIECE_SLOT = 35;
    /** Rząd separadora między siatką a podglądem noszenia. */
    static final int SEPARATOR_ROW = 4;
    /** Podgląd noszonych części: HEAD, CHEST, LEGS, FEET. */
    static final int[] WORN_SLOTS = {46, 47, 48, 49};
    private static final int SLOT_CLOSE = 53;

    private static final EquipmentSlot[] BODY_ORDER = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final String[] BODY_LABELS = {"Hełm", "Napierśnik", "Spodnie", "Buty"};

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage miniMessage;
    private final CosmeticCatalog catalog;
    private final @Nullable CustomItemService customItems;
    /**
     * A-raw: gracz widzi nazwy części, nie identyfikatory CustomItems
     * ({@code mystical_test_helmet}).
     */
    private final ItemNames itemNames;
    /**
     * Opcjonalny resolver nazwy edycji (seasonId → nazwa+zakres lub null).
     * {@code null} = ścieżka legacy bez zmian.
     */
    @Nullable private final java.util.function.IntFunction<String> editionNameResolver;

    public CosmeticPlayerMenu(@NotNull org.bukkit.plugin.java.JavaPlugin plugin,
                              @NotNull MenuService menus,
                              @NotNull MiniMessage miniMessage,
                              @NotNull CosmeticCatalog catalog,
                              @Nullable CustomItemService customItems) {
        this(plugin, menus, miniMessage, catalog, customItems, null);
    }

    /**
     * Wariant edition-aware: {@code editionNameResolver} (nullable) rozwiązuje
     * seasonId z markera PDC na etykietę „nazwa (zakres)” bieżącej edycji;
     * {@code null} albo brak wyniku = legacy matematyka
     * {@link SeasonLabels#editionText} bez zmian (parowość bajtowa).
     */
    public CosmeticPlayerMenu(@NotNull org.bukkit.plugin.java.JavaPlugin plugin,
                              @NotNull MenuService menus,
                              @NotNull MiniMessage miniMessage,
                              @NotNull CosmeticCatalog catalog,
                              @Nullable CustomItemService customItems,
                              @Nullable java.util.function.IntFunction<String> editionNameResolver) {
        this.plugin = plugin;
        this.menus = menus;
        this.miniMessage = miniMessage;
        this.catalog = catalog;
        this.customItems = customItems;
        this.itemNames = new ItemNames(customItems);
        this.editionNameResolver = editionNameResolver;
    }

    /**
     * Fail-closed bramka otwarcia: katalog wyłączony albo bez serwisu
     * przedmiotów niestandardowych oznacza, że żaden egzemplarz nie mógł
     * zostać wydany — menu nie ma czego pokazać.
     */
    boolean available() {
        return catalog.enabled() && !catalog.collections().isEmpty() && customItems != null;
    }

    /** Otwiera menu na wątku encji gracza (konwencja {@code SeasonPassMenu.open}). */
    public void open(@NotNull Player player) {
        if (!available()) {
            player.sendMessage(Ui.component(miniMessage,
                    "<red>Kosmetyka sezonowa jest obecnie niedostępna.</red>"));
            return;
        }
        try {
            var scheduled = player.getScheduler().run(plugin,
                    ignored -> buildAndOpen(player), null);
            if (scheduled == null) {
                buildAndOpen(player);
            }
        } catch (RuntimeException rejected) {
            plugin.getLogger().fine("Kosmetyki: harmonogram otwarcia odrzucony: " + rejected);
            buildAndOpen(player);
        }
    }

    void buildAndOpen(@NotNull Player player) {
        MenuService.Menu menu = menus.ofRows(6, Ui.component(miniMessage,
                "<gold><bold>Kosmetyka Sezonowa</bold></gold>"));
        populate(menu, player);
        menu.open(player);
    }

    /** Buduje zawartość menu; package-private dla testów layoutu. */
    void populate(@NotNull MenuService.Menu menu, @NotNull Player player) {
        Ui.frame(menu, miniMessage, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        Map<String, CosmeticCatalog.Collection> pieces = pieceIndex();

        menu.decoration(SLOT_INFO, Ui.item(Material.BOOK, miniMessage,
                "<aqua><bold>Twoja kolekcja</bold></aqua>",
                List.of(
                        "<gray>Kliknij część, aby ją założyć.</gray>",
                        "<gray>Kliknij noszoną, aby ją zdjąć.</gray>",
                        "<gray>Egzemplarze są soulbound — nie sprzedasz ich.</gray>"),
                false));

        /*
         * Siatka posiadanych części: tylko egzemplarze z plecaka. Noszone
         * pokazuje osobny rząd podglądu, żeby stan „założone/zdejmowalne”
         * był jednoznaczny na pierwszy rzut oka.
         */
        int slot = FIRST_PIECE_SLOT;
        // A14: jedna migawka plecaka na całą pętlę — getStorageContents()
        // kopiuje tablicę, więc wywołanie per iteracja to 36 zbędnych kopii.
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length && slot <= LAST_PIECE_SLOT; i++) {
            ItemStack item = storage[i];
            String pieceId = catalogPieceOf(item, pieces);
            if (pieceId == null || isWornAlready(player, pieceId)) {
                continue;
            }
            final String id = pieceId;
            menu.set(slot, ownedIcon(item, pieces.get(pieceId), id),
                    (viewer, click) -> wear(viewer, id));
            slot++;
        }

        Ui.separatorRow(menu, miniMessage, SEPARATOR_ROW);

        // Podgląd noszenia: klikalny tylko wtedy, gdy siedzi tam nasza kosmetyka.
        for (int i = 0; i < BODY_ORDER.length; i++) {
            ItemStack equipped = player.getInventory().getItem(BODY_ORDER[i]);
            String pieceId = catalogPieceOf(equipped, pieces);
            if (pieceId != null) {
                final String id = pieceId;
                menu.set(WORN_SLOTS[i], wornIcon(equipped, pieces.get(pieceId), id),
                        (viewer, click) -> unequip(viewer, id));
            } else if (equipped != null && !equipped.isEmpty()) {
                menu.decoration(WORN_SLOTS[i], Ui.item(Material.IRON_BARS, miniMessage,
                        "<gray>" + BODY_LABELS[i] + ": zajęte</gray>",
                        List.of("<gray>Slot trzyma zwykły pancerz.</gray>",
                                "<gray>Zdejmij go ręcznie, aby założyć kosmetykę.</gray>"),
                        false));
            } else {
                menu.decoration(WORN_SLOTS[i], Ui.item(Material.GRAY_DYE, miniMessage,
                        "<dark_gray>" + BODY_LABELS[i] + ": pusto</dark_gray>",
                        List.of("<gray>Brak noszonej części.</gray>"), false));
            }
        }

        menu.close(SLOT_CLOSE, Ui.closeButton(miniMessage));
    }

    /** Zakłada część z ekwipunku na jej slot ciała. Idempotentne. */
    void wear(@NotNull Player player, @NotNull String pieceId) {
        EquipmentSlot bodySlot = bodySlotOf(pieceId);
        if (bodySlot == null) {
            send(player, "<red>Ta część nie ma slotu ciała — nie da się jej założyć.</red>");
            return;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack equipped = inv.getItem(bodySlot);
        if (matchesPiece(equipped, pieceId)) {
            return; // idempotencja: już noszone
        }
        int index = findStorageIndex(player, pieceId);
        if (index < 0) {
            send(player, "<red>Nie masz tej części w ekwipunku.</red>");
            return;
        }
        // Zwolnij slot ciała: poprzednik wraca do plecaka albo blokujemy operację.
        if (equipped != null && !equipped.isEmpty()
                && !inv.addItem(equipped).isEmpty()) {
            send(player, "<red>Brak miejsca w ekwipunku — zwolnij slot i spróbuj ponownie.</red>");
            return;
        }
        ItemStack piece = inv.getItem(index);
        inv.setItem(index, null);
        inv.setItem(bodySlot, piece);
        send(player, "<green>Założono: <white>" + itemNames.customLabel(pieceId) + "</white>.</green>");
        buildAndOpen(player);
    }

    /** Zdejmuje część ze slotu ciała z powrotem do ekwipunku. Idempotentne. */
    void unequip(@NotNull Player player, @NotNull String pieceId) {
        EquipmentSlot bodySlot = bodySlotOf(pieceId);
        if (bodySlot == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack equipped = inv.getItem(bodySlot);
        if (!matchesPiece(equipped, pieceId)) {
            return; // idempotencja: nic nie noszone
        }
        if (inv.addItem(equipped).isEmpty()) {
            inv.setItem(bodySlot, null);
            send(player, "<yellow>Zdjęto: <white>" + itemNames.customLabel(pieceId) + "</white>.</yellow>");
            buildAndOpen(player);
        } else {
            // Egzemplarz zostaje w slocie — nic nigdzie nie znika.
            send(player, "<red>Brak miejsca w ekwipunku — zwolnij slot i spróbuj ponownie.</red>");
        }
    }

    private @NotNull ItemStack ownedIcon(@NotNull ItemStack piece,
                                         @NotNull CosmeticCatalog.Collection collection,
                                         @NotNull String pieceId) {
        return provenanceIcon(piece, collection, pieceId, "Kliknij, aby założyć.");
    }

    private @NotNull ItemStack wornIcon(@NotNull ItemStack piece,
                                        @NotNull CosmeticCatalog.Collection collection,
                                        @NotNull String pieceId) {
        return provenanceIcon(piece, collection, pieceId, "Noszona. Kliknij, aby zdjąć.");
    }

    /**
     * Ikona na bazie prawdziwego egzemplarza ({@code Ui.decorate} zachowuje
     * komponent modelu z paczki), z proweniencją tier/edycja w opisie.
     *
     * <p>Widok gracza: edycja jako ZAKRES DAT (decyzja operatora 2026-08-25).
     * Wewnętrzny marker PDC „S&lt;n&gt;” na egzemplarzu pozostaje bez zmian —
     * podmieniamy wyłącznie render lore ({@link SeasonLabels}).
     */
    private @NotNull ItemStack provenanceIcon(@NotNull ItemStack piece,
                                              @NotNull CosmeticCatalog.Collection collection,
                                              @NotNull String pieceId,
                                              @NotNull String actionHint) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Kolekcja: " + collection.name() + "</gray>");
        lore.add("<gray>Część: <white>" + itemNames.customLabel(pieceId) + "</white></gray>");
        lore.add("<gray>Edycja: <white>" + editionLabel(piece) + "</white>, próg: <white>"
                + ItemPolicyMarkers.collectibleTier(piece).orElse("?") + "</white></gray>");
        lore.add("<dark_gray> </dark_gray>");
        lore.add("<yellow>" + actionHint + "</yellow>");
        return Ui.decorate(piece.clone(), miniMessage,
                "<white><bold>" + bodyLabelOf(pieceId) + "</bold></white>",
                List.copyOf(lore), true);
    }

    /**
     * Etykieta edycji dla gracza. Gdy resolver nazw edycji jest podpięty
     * i rozwiązuje seasonId, zwraca jego kompozycję „nazwa (zakres)”;
     * w przeciwnym razie legacy zakres dat np. „01.09 – 27.10.2026”.
     * „?” gdy egzemplarz bez markera PDC albo kalendarz sezonowy wyłączony.
     */
    private @NotNull String editionLabel(@NotNull ItemStack piece) {
        Integer seasonId = SeasonLabels.seasonNumberOf(
                ItemPolicyMarkers.collectibleEdition(piece).orElse(null));
        if (seasonId == null) {
            return "?";
        }
        if (editionNameResolver != null) {
            String named = editionNameResolver.apply(seasonId);
            if (named != null && !named.isBlank()) {
                return named;
            }
        }
        SeasonLabels.SeasonWindow window =
                SeasonLabels.window(plugin.getConfig().getConfigurationSection("season"));
        if (window == null) {
            return "?";
        }
        return SeasonLabels.editionText(seasonId, window.epochStartMillis(), window.lengthDays());
    }

    /** Indeks część -> kolekcja z całego katalogu (wszystkie sezony). */
    private @NotNull Map<String, CosmeticCatalog.Collection> pieceIndex() {
        Map<String, CosmeticCatalog.Collection> index = new LinkedHashMap<>();
        for (CosmeticCatalog.Collection collection : catalog.collections().values()) {
            for (String piece : collection.pieces()) {
                index.putIfAbsent(piece, collection);
            }
        }
        return index;
    }

    /**
     * Rozpoznaje egzemplarz katalogowy: musi być collectible z markerami
     * {@code wpme} i identyfikatorem CustomItems pasującym do katalogu —
     * dzięki temu inne collectibles (np. tokeny) nie trafiają do siatki.
     */
    private @Nullable String catalogPieceOf(@Nullable ItemStack item,
                                            @NotNull Map<String, CosmeticCatalog.Collection> pieces) {
        if (item == null || item.isEmpty() || !ItemPolicyMarkers.isCollectible(item)
                || customItems == null) {
            return null;
        }
        String id = customItems.idOf(item);
        return id != null && pieces.containsKey(id) ? id : null;
    }

    private boolean matchesPiece(@Nullable ItemStack item, @NotNull String pieceId) {
        if (item == null || item.isEmpty() || !ItemPolicyMarkers.isCollectible(item)
                || customItems == null) {
            return false;
        }
        return pieceId.equals(customItems.idOf(item));
    }

    private boolean isWornAlready(@NotNull Player player, @NotNull String pieceId) {
        EquipmentSlot bodySlot = bodySlotOf(pieceId);
        return bodySlot != null && matchesPiece(player.getInventory().getItem(bodySlot), pieceId);
    }

    private int findStorageIndex(@NotNull Player player, @NotNull String pieceId) {
        Map<String, CosmeticCatalog.Collection> pieces = pieceIndex();
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            if (pieceId.equals(catalogPieceOf(storage[i], pieces))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Slot ciała części wynika z konwencji identyfikatorów kolekcji
     * ({@code *_helmet}, {@code *_chestplate}, {@code *_leggings},
     * {@code *_boots}); kontrakt pilnuje
     * {@code ShippedCosmeticCatalogContractTest}.
     */
    static @Nullable EquipmentSlot bodySlotOf(@NotNull String pieceId) {
        if (pieceId.endsWith("_helmet")) {
            return EquipmentSlot.HEAD;
        }
        if (pieceId.endsWith("_chestplate")) {
            return EquipmentSlot.CHEST;
        }
        if (pieceId.endsWith("_leggings")) {
            return EquipmentSlot.LEGS;
        }
        if (pieceId.endsWith("_boots")) {
            return EquipmentSlot.FEET;
        }
        return null;
    }

    private @NotNull String bodyLabelOf(@NotNull String pieceId) {
        return switch (bodySlotOf(pieceId)) {
            case HEAD -> BODY_LABELS[0];
            case CHEST -> BODY_LABELS[1];
            case LEGS -> BODY_LABELS[2];
            case FEET -> BODY_LABELS[3];
            case null, default -> itemNames.customLabel(pieceId);
        };
    }

    private void send(@NotNull Player player, @NotNull String message) {
        player.sendMessage(Ui.component(miniMessage, message));
    }
}
