package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.rafalohaki.wpmecore.addons.skyblock.shared.ConfigSchema;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Receptury Kuźni Mistrzowskiej wczytane z {@code forge.yml}.
 *
 * <p>Parsowanie jest fail-closed: nieznany materiał albo nieznany identyfikator
 * z CustomItems wywala start pluginu, a nie pierwsze kliknięcie gracza. Ten sam
 * rodzaj błędu przepuścił kiedyś bursztyn w {@code minions.yml} — paliwo
 * wskazywało identyfikator, którego nikt nie zdefiniował.
 */
public record ForgeConfig(@NotNull Map<String, ForgeCategory> categories,
                          @NotNull Map<String, ForgeRecipe> recipes,
                          @NotNull List<ForgeDiscount> discounts) {

    private static final ConfigSchema SCHEMA = new ConfigSchema("forge.yml");

    /** Bez zniżek rangowych — kuźnia liczy wtedy cenę z pliku każdemu. */
    public ForgeConfig(@NotNull Map<String, ForgeCategory> categories,
                       @NotNull Map<String, ForgeRecipe> recipes) {
        this(categories, recipes, List.of());
    }

    /**
     * Górna granica zniżki. Literówka „100” zrobiłaby z kuźni rozdawnictwo,
     * a 50 % to i tak dwukrotnie więcej niż pasmo 10–15 %, w którym operuje
     * decyzja właściciela.
     */
    static final int MAX_DISCOUNT_PERCENT = 50;

    /** Maksimum receptur na kategorię — siatka slotów 9..44 bez paginacji. */
    static final int MAX_RECIPES_PER_CATEGORY = 36;

    /** Największy stos w waniliowym Minecrafcie; sprawdzenie bez rejestru serwera. */
    static final int MAX_RESULT_AMOUNT = 64;

    private static final String CUSTOM_PREFIX = "custom:";
    private static final String MINION_PREFIX = "minion:";

    public record ForgeCategory(@NotNull String id, int slot, @NotNull Material icon,
                                @NotNull String name, @NotNull List<String> lore) { }

    /**
     * Dokładnie jedno z {@code resultCustomItemId} / {@code resultMaterial} /
     * {@code resultMinionType} jest niepuste.
     *
     * <p>Minionek jest osobnym rodzajem wyrobu, bo jego przedmiot to głowa
     * z teksturą i danymi trwałymi, których nie da się zapisać ani jako
     * materiał, ani jako identyfikator CustomItems.
     */
    public record ForgeRecipe(@NotNull String id, @NotNull String category, @NotNull String name,
                              @Nullable String resultCustomItemId,
                              @Nullable Material resultMaterial,
                              @Nullable String resultMinionType,
                              int resultAmount, long costMoney,
                              @Nullable Material icon,
                              @NotNull List<ForgeIngredient> ingredients,
                              @Nullable String resultCommand) {
        /** Receptura z wyrobem-przedmiotem (bez komendy) — stary kształt, używany przez testy. */
        public ForgeRecipe(@NotNull String id, @NotNull String category, @NotNull String name,
                           @Nullable String resultCustomItemId, @Nullable Material resultMaterial,
                           @Nullable String resultMinionType, int resultAmount, long costMoney,
                           @Nullable Material icon, @NotNull List<ForgeIngredient> ingredients) {
            this(id, category, name, resultCustomItemId, resultMaterial, resultMinionType,
                    resultAmount, costMoney, icon, ingredients, null);
        }
    }

    /** Dokładnie jedno z {@code customItemId} / {@code material} jest niepuste. */
    public record ForgeIngredient(@Nullable String customItemId, @Nullable Material material,
                                  int amount) { }

    /** Zniżka rangowa: uprawnienie i procent zdejmowany z {@code cost-money}. */
    public record ForgeDiscount(@NotNull String permission, int percent) { }

    /**
     * Procent zniżki dla tego gracza; 0, gdy nie ma żadnego z uprawnień.
     *
     * <p>Lista jest posortowana malejąco po procencie, więc wygrywa
     * najkorzystniejsze trafienie — {@code legend} dziedziczy po
     * {@code sponsorze} i ma zapłacić stawkę legendy, nie sponsora.
     *
     * <p>Kontekst serwera obsługuje LuckPerms: węzeł nadany z
     * {@code server=skyblock} jest widoczny dla {@code hasPermission} wyłącznie
     * na tym trybie, więc kod nie musi o niego pytać.
     */
    public int discountPercent(@NotNull Permissible who) {
        for (ForgeDiscount discount : discounts) {
            if (who.hasPermission(discount.permission())) {
                return discount.percent();
            }
        }
        return 0;
    }

    /**
     * Cena wykucia dla tego gracza po zniżce rangowej.
     *
     * <p>Dzielenie całkowite obcina w dół <b>zniżkę</b>, nie cenę, więc gracz
     * nigdy nie płaci mniej, niż wynika z zadeklarowanego procentu.
     */
    public long costFor(@NotNull Permissible who, @NotNull ForgeRecipe recipe) {
        int percent = discountPercent(who);
        return percent == 0
                ? recipe.costMoney()
                : recipe.costMoney() - recipe.costMoney() * percent / 100L;
    }

    /** Kategorie mające choć jedną recepturę, w kolejności slotu zakładki. */
    public @NotNull List<ForgeCategory> visibleCategories() {
        List<ForgeCategory> visible = new ArrayList<>();
        for (ForgeCategory category : categories.values()) {
            if (!recipesOf(category.id()).isEmpty()) {
                visible.add(category);
            }
        }
        visible.sort(Comparator.comparingInt(ForgeCategory::slot));
        return visible;
    }

    /** Receptury kategorii w kolejności deklaracji w pliku. */
    public @NotNull List<ForgeRecipe> recipesOf(@NotNull String categoryId) {
        List<ForgeRecipe> matching = new ArrayList<>();
        for (ForgeRecipe recipe : recipes.values()) {
            if (recipe.category().equals(categoryId)) {
                matching.add(recipe);
            }
        }
        return matching;
    }

    public static @NotNull ForgeConfig load(@NotNull JavaPlugin plugin,
                                            @Nullable CustomItemService customItems,
                                            @NotNull Set<String> minionTypes) {
        File file = new File(plugin.getDataFolder(), "forge.yml");
        if (!file.isFile()) {
            plugin.saveResource("forge.yml", false);
        }
        return parse(YamlConfiguration.loadConfiguration(file), customItems, minionTypes);
    }

    /** Bez znanych typów minionków — receptura na minionka wtedy nie przejdzie. */
    static @NotNull ForgeConfig parse(@NotNull ConfigurationSection root,
                                      @Nullable CustomItemService customItems) {
        return parse(root, customItems, Set.of());
    }

    static @NotNull ForgeConfig parse(@NotNull ConfigurationSection root,
                                      @Nullable CustomItemService customItems,
                                      @NotNull Set<String> minionTypes) {
        int schema = root.getInt("schema-version", -1);
        if (schema != 1) {
            throw SCHEMA.fail("schema-version", "oczekiwano 1, było " + schema);
        }

        Map<String, ForgeCategory> categories = new LinkedHashMap<>();
        Map<Integer, String> usedSlots = new LinkedHashMap<>();
        ConfigurationSection categoriesSection = SCHEMA.section(root, "categories");
        for (String id : categoriesSection.getKeys(false)) {
            String path = "categories." + id;
            ConfigurationSection section = SCHEMA.section(root, path);
            int slot = section.getInt("slot", -1);
            if (slot < 0 || slot > 8) {
                throw SCHEMA.fail(path + ".slot", "poza zakresem [0, 8]");
            }
            String previous = usedSlots.putIfAbsent(slot, id);
            if (previous != null) {
                throw SCHEMA.fail(path + ".slot", "slot jest już zajęty przez '" + previous + "'");
            }
            categories.put(id, new ForgeCategory(id, slot,
                    SCHEMA.material(section.getString("icon", ""), path + ".icon"),
                    SCHEMA.string(section, path, "name"),
                    List.copyOf(section.getStringList("lore"))));
        }

        Map<String, ForgeRecipe> recipes = new LinkedHashMap<>();
        Map<String, Integer> perCategory = new LinkedHashMap<>();
        ConfigurationSection recipesSection = root.getConfigurationSection("recipes");
        if (recipesSection != null) {
            for (String id : recipesSection.getKeys(false)) {
                recipes.put(id, parseRecipe(root, id, categories, perCategory, customItems, minionTypes));
            }
        }

        return new ForgeConfig(Map.copyOf(categories), Map.copyOf(recipes), parseDiscounts(root));
    }

    /**
     * Zniżki rangowe z sekcji {@code discounts} (opcjonalnej). Sortowane
     * malejąco po procencie, bo {@link #discountPercent} bierze pierwsze
     * trafienie.
     */
    private static List<ForgeDiscount> parseDiscounts(@NotNull ConfigurationSection root) {
        List<Map<?, ?>> raw = root.getMapList("discounts");
        List<ForgeDiscount> discounts = new ArrayList<>();
        for (int index = 0; index < raw.size(); index++) {
            String path = "discounts[" + index + "]";
            Object permission = raw.get(index).get("permission");
            if (permission == null || String.valueOf(permission).isBlank()) {
                throw SCHEMA.fail(path + ".permission", "brak wymaganej wartości");
            }
            Object rawPercent = raw.get(index).get("percent");
            int percent = rawPercent instanceof Number number ? number.intValue() : 0;
            if (percent <= 0 || percent > MAX_DISCOUNT_PERCENT) {
                throw SCHEMA.fail(path + ".percent", "procent musi być w zakresie 1.."
                        + MAX_DISCOUNT_PERCENT + ", było " + percent);
            }
            discounts.add(new ForgeDiscount(String.valueOf(permission), percent));
        }
        discounts.sort(Comparator.comparingInt(ForgeDiscount::percent).reversed());
        return List.copyOf(discounts);
    }

    private static ForgeRecipe parseRecipe(ConfigurationSection root, String id,
                                           Map<String, ForgeCategory> categories,
                                           Map<String, Integer> perCategory,
                                           @Nullable CustomItemService customItems,
                                           @NotNull Set<String> minionTypes) {
        String path = "recipes." + id;
        ConfigurationSection section = SCHEMA.section(root, path);

        String category = SCHEMA.string(section, path, "category");
        if (!categories.containsKey(category)) {
            throw SCHEMA.fail(path + ".category", "nieznana kategoria '" + category + "'");
        }
        if (perCategory.merge(category, 1, Integer::sum) > MAX_RECIPES_PER_CATEGORY) {
            throw SCHEMA.fail(path + ".category", "kategoria '" + category + "' może mieć najwyżej "
                    + MAX_RECIPES_PER_CATEGORY + " receptur");
        }

        // Migracja Eco: wyrób może być komendą konsoli (`result-command`, np. pety z EcoPets:
        // `ecopets give %player% cobblestone`) — wtedy `result-item` nie jest wymagany.
        String resultCommand = section.getString("result-command");
        if (resultCommand != null && resultCommand.isBlank()) {
            resultCommand = null;
        }
        String resultCustomId = null;
        String resultMinionType = null;
        Material resultMaterial = null;
        if (resultCommand == null) {
            String resultKey = SCHEMA.string(section, path, "result-item");
            resultCustomId = customIdOf(resultKey);
            resultMinionType = minionTypeOf(resultKey);
            if (resultCustomId != null) {
                SCHEMA.requireKnownCustomItem(customItems, resultCustomId, path + ".result-item");
            } else if (resultMinionType != null) {
                if (!minionTypes.contains(resultMinionType)) {
                    throw SCHEMA.fail(path + ".result-item",
                            "nieznany typ minionka '" + resultMinionType + "'");
                }
            } else {
                resultMaterial = SCHEMA.material(resultKey, path + ".result-item");
            }
        }

        int resultAmount = section.getInt("result-amount", resultCommand != null ? 1 : 0);
        if (resultAmount <= 0) {
            throw SCHEMA.fail(path + ".result-amount", "musi być > 0");
        }
        /*
         * Minionki nie mogą wychodzić stosem: dwa świeże egzemplarze tego samego
         * typu mają identyczne dane, więc zlałyby się w jeden stos, a postawienie
         * zużywa całą pozycję ekwipunku. Gracz straciłby resztę stosu.
         */
        if (resultMinionType != null && resultAmount != 1) {
            throw SCHEMA.fail(path + ".result-amount", "receptura na minionka musi wydawać dokładnie 1");
        }
        /*
         * setAmount() powyżej maksimum stosu nie przycina i nie rzuca — tworzy stos
         * ponadwymiarowy, który dopiero addItem rozdziela. Górny limit trzymamy
         * tutaj, bo Material.getMaxStackSize() sięga rejestru serwera i nie da się
         * go wywołać w teście jednostkowym parsera.
         */
        if (resultAmount > MAX_RESULT_AMOUNT) {
            throw SCHEMA.fail(path + ".result-amount", "nie może przekraczać " + MAX_RESULT_AMOUNT);
        }
        /*
         * Ikona jest opcjonalna. Bez niej menu rysuje wynik będący materiałem,
         * a dla wyrobów bez materiału — kartkę papieru. Minionki i customy
         * wyglądałyby wtedy identycznie, więc mogą sobie ikonę wskazać.
         */
        String iconKey = section.getString("icon");
        Material icon = iconKey == null || iconKey.isBlank()
                ? null : SCHEMA.material(iconKey, path + ".icon");

        long costMoney = section.getLong("cost-money", -1L);
        if (costMoney < 0L) {
            throw SCHEMA.fail(path + ".cost-money", "musi być >= 0");
        }

        List<Map<?, ?>> rawIngredients = section.getMapList("required-items");
        if (rawIngredients.isEmpty()) {
            throw SCHEMA.fail(path + ".required-items", "brak wymaganych składników");
        }
        List<ForgeIngredient> ingredients = new ArrayList<>();
        for (int index = 0; index < rawIngredients.size(); index++) {
            ingredients.add(parseIngredient(rawIngredients.get(index),
                    path + ".required-items[" + index + "]", customItems));
        }

        ForgeRecipe recipe = new ForgeRecipe(id, category, SCHEMA.string(section, path, "name"),
                resultCustomId, resultMaterial, resultMinionType, resultAmount, costMoney,
                icon, List.copyOf(ingredients), resultCommand);
        if (ForgeRequirements.consumesItsOwnResult(recipe)) {
            throw SCHEMA.fail(path, "receptura nie może zużywać własnego wyrobu — kuźnia wydaje go "
                    + "przed zabraniem składników, więc świeży egzemplarz zostałby zabrany");
        }
        return recipe;
    }

    private static ForgeIngredient parseIngredient(Map<?, ?> raw, String path,
                                                   @Nullable CustomItemService customItems) {
        Object itemValue = raw.get("item");
        if (itemValue == null || String.valueOf(itemValue).isBlank()) {
            throw SCHEMA.fail(path + ".item", "brak wymaganej wartości");
        }
        Object amountValue = raw.get("amount");
        int amount = amountValue instanceof Number number ? number.intValue() : 0;
        if (amount <= 0) {
            throw SCHEMA.fail(path + ".amount", "musi być > 0");
        }
        String itemKey = String.valueOf(itemValue);
        String customId = customIdOf(itemKey);
        if (customId != null) {
            SCHEMA.requireKnownCustomItem(customItems, customId, path + ".item");
            return new ForgeIngredient(customId, null, amount);
        }
        return new ForgeIngredient(null, SCHEMA.material(itemKey, path + ".item"), amount);
    }

    private static @Nullable String customIdOf(@NotNull String itemKey) {
        return itemKey.startsWith(CUSTOM_PREFIX)
                ? itemKey.substring(CUSTOM_PREFIX.length())
                : null;
    }

    private static @Nullable String minionTypeOf(@NotNull String itemKey) {
        return itemKey.startsWith(MINION_PREFIX)
                ? itemKey.substring(MINION_PREFIX.length()) : null;
    }
}
