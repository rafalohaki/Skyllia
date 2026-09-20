package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;


import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Menu Kuźni Mistrzowskiej: zakładki kategorii i siatka receptur.
 *
 * <p>Menu buduje się przy każdym otwarciu, bo lore zależy od tego, co gracz ma
 * w ekwipunku w tej chwili. Otwieranie z wnętrza handlera jest bezpieczne:
 * {@code MenuListener} odracza każdy handler do następnego ticku encji gracza,
 * właśnie dlatego, że Bukkit zabrania otwierania inwentarza w trakcie
 * {@code InventoryClickEvent}.
 */
// Niefinalna wyłącznie dla testów: budowa realnego ItemStacka wymaga rejestru
// serwera, więc test przesłania metody budujące ikony. Ten sam idiom co MinionMenu.
public class ForgeMenu {

    static final int FIRST_RECIPE_SLOT = 9;
    static final int LAST_RECIPE_SLOT = 44;
    static final int BALANCE_SLOT = 45;
    static final int CLOSE_SLOT = 49;

    private final JavaPlugin plugin;
    private final MenuService menus;
    private final MiniMessage miniMessage;
    private final ForgeService forge;
    private final @Nullable LedgerService ledger;

    public ForgeMenu(@NotNull JavaPlugin plugin, @NotNull MenuService menus,
              @NotNull MiniMessage miniMessage, @NotNull ForgeService forge,
              @Nullable LedgerService ledger) {
        this.plugin = plugin;
        this.menus = menus;
        this.miniMessage = miniMessage;
        this.forge = forge;
        this.ledger = ledger;
    }

    public void open(@NotNull Player player) {
        List<ForgeConfig.ForgeCategory> visible = forge.config().visibleCategories();
        if (visible.isEmpty()) {
            player.sendActionBar(Ui.component(miniMessage,
                    "<red>Kuźnia nie ma żadnych receptur.</red>"));
            return;
        }
        open(player, visible.getFirst().id());
    }

    public void open(@NotNull Player player, @NotNull String categoryId) {
        List<ForgeConfig.ForgeCategory> visible = forge.config().visibleCategories();
        if (visible.isEmpty()) {
            player.sendActionBar(Ui.component(miniMessage,
                    "<red>Kuźnia nie ma żadnych receptur.</red>"));
            return;
        }
        ForgeConfig.ForgeCategory active = visible.stream()
                .filter(category -> category.id().equals(categoryId))
                .findFirst()
                .orElse(visible.getFirst());

        boolean isTokens = active.id().equals("tokens");
        net.kyori.adventure.text.Component title = isTokens
                ? Ui.component(miniMessage, "<yellow><bold>Kantor Wymiany Lotosów</bold></yellow>")
                : Ui.component(miniMessage, "<gold><bold>Kuźnia Mistrzowska</bold></gold>");
        MenuService.Menu menu = menus.ofRows(6, title);
        Ui.frame(menu, miniMessage, isTokens ? Material.YELLOW_STAINED_GLASS_PANE : Material.ORANGE_STAINED_GLASS_PANE);

        for (ForgeConfig.ForgeCategory category : visible) {
            boolean isActive = category.id().equals(active.id());
            menu.set(category.slot(), categoryIcon(category, isActive),
                    (viewer, click) -> open(viewer, category.id()));
        }

        List<ForgeConfig.ForgeRecipe> recipes = forge.config().recipesOf(active.id());
        for (int index = 0; index < recipes.size()
                && FIRST_RECIPE_SLOT + index <= LAST_RECIPE_SLOT; index++) {
            ForgeConfig.ForgeRecipe recipe = recipes.get(index);
            menu.set(FIRST_RECIPE_SLOT + index, recipeIcon(player, recipe),
                    craftClick(recipe, active.id()));
        }

        menu.decoration(BALANCE_SLOT, balanceIcon(player));
        menu.close(CLOSE_SLOT, Ui.closeButton(miniMessage));
        // Separator: linia między siatką receptur a strefą salda i zamknięcia.
        Ui.separatorRow(menu, miniMessage, 5, BALANCE_SLOT, CLOSE_SLOT);
        menu.open(player);
    }

    /**
     * Klik wykuwa i po rozstrzygnięciu odświeża menu — wtedy widać nowe saldo i
     * nowy stan „posiadasz / brakuje". Handler dostaje {@code viewer}, nie gracza
     * zapamiętanego przy budowie menu.
     */
    @NotNull MenuService.ClickHandler craftClick(@NotNull ForgeConfig.ForgeRecipe recipe,
                                                 @NotNull String categoryId) {
        return (viewer, click) -> forge.craft(viewer, recipe, () -> {
            if (viewer.isOnline()) {
                open(viewer, categoryId);
            }
        });
    }

    /** Seam: budowa ItemStacka wymaga rejestru serwera. */
    @NotNull ItemStack recipeIcon(@NotNull Player player,
                                  @NotNull ForgeConfig.ForgeRecipe recipe) {
        Map<String, Integer> owned = forge.countOwned(player, recipe);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Wymagane surowce:</gray>");
        for (ForgeConfig.ForgeIngredient ingredient : recipe.ingredients()) {
            int have = owned.getOrDefault(ForgeRequirements.key(ingredient), 0);
            boolean enough = have >= ingredient.amount();
            lore.add((enough ? "<green>✔ " : "<red>✖ ")
                    + have + "/" + ingredient.amount() + " "
                    + describe(ingredient) + (enough ? "</green>" : "</red>"));
        }
        lore.add("<dark_gray> </dark_gray>");
        /*
         * F17: ta sama metoda, którą płaci ForgeService — inaczej menu i kasa
         * rozjechałyby się dokładnie u tych graczy, którym na tym zależy.
         */
        int discount = forge.config().discountPercent(player);
        long price = forge.config().costFor(player, recipe);
        if (discount > 0) {
            lore.add("<gray>Koszt wykucia:</gray> " + Ui.price(price)
                    + " <dark_gray><st>" + Ui.price(recipe.costMoney()) + "</st></dark_gray>");
            lore.add("<green>Zniżka rangowa: -" + discount + " %</green>");
        } else {
            lore.add("<gray>Koszt wykucia:</gray> " + Ui.price(price));
        }
        if (recipe.resultAmount() > 1) {
            lore.add("<gray>Otrzymujesz:</gray> <white>" + recipe.resultAmount() + " szt.</white>");
        }
        lore.add("<dark_gray> </dark_gray>");
        boolean satisfied = ForgeRequirements.satisfied(recipe, owned);
        lore.add(satisfied
                ? "<green><bold>KLIKNIJ, ABY WYKUĆ</bold></green>"
                : "<red>Brak wymaganych surowców</red>");

        /*
         * Ikoną receptury jest sam wyrób. Receptury kuźni i kantoru zwracają
         * przedmioty z CustomItems (result-item: "custom:..."), które nie mają
         * ani icon, ani result-material — poprzedni łańcuch fallbacków lądował
         * więc na PAPER i całe menu wyglądało jak stos kartek. Wygląd wyrobu
         * niesie komponent item_model, którego z Material nie da się odtworzyć.
         */
        ItemStack product = forge.resultStack(recipe);
        if (product != null) {
            ItemStack icon = Ui.decorate(product, miniMessage, recipe.name(),
                    List.copyOf(lore), satisfied);
            // Krotność jest już w opisie; stos ikony trzymamy na 1, żeby
            // result-amount ponad rozmiar stosu nie renderował się dziwnie.
            // Ui.decorate zwraca kopię, więc to nie dotyka wyrobu.
            icon.setAmount(1);
            return icon;
        }
        Material icon = recipe.icon() != null ? recipe.icon()
                : recipe.resultMaterial() != null ? recipe.resultMaterial() : Material.PAPER;
        return Ui.item(icon, miniMessage, recipe.name(), List.copyOf(lore), satisfied);
    }

    /** Seam: jw. */
    @NotNull ItemStack categoryIcon(@NotNull ForgeConfig.ForgeCategory category, boolean active) {
        List<String> lore = new ArrayList<>(category.lore());
        lore.add("<dark_gray> </dark_gray>");
        lore.add(active
                ? "<green>▶ Wybrana zakładka</green>"
                : "<gray>Kliknij, aby otworzyć</gray>");
        return Ui.item(category.icon(), miniMessage, category.name(), List.copyOf(lore), active);
    }

    /** Seam: jw. */
    @NotNull ItemStack balanceIcon(@NotNull Player player) {
        String balance = ledger == null
                ? "<gray>niedostępne</gray>"
                : Ui.price(ledger.playerBalance(player.getUniqueId()));
        return Ui.item(Material.GOLD_NUGGET, miniMessage,
                "<gold><bold>Twój portfel</bold></gold>",
                List.of(balance, "<gray>Bank wyspy znajdziesz w</gray> <white>/bank</white>"),
                false);
    }

    /**
     * Nazwa pozycji w lore — deleguje do serwisu, który zna polskie nazwy
     * CustomItems i klucze tłumaczeń vanilli (klient lokalizuje sam).
     */
    private String describe(ForgeConfig.ForgeIngredient ingredient) {
        return forge.ingredientName(ingredient);
    }
}
