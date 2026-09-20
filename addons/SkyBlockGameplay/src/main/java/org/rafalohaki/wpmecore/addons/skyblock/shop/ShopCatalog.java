package org.rafalohaki.wpmecore.addons.skyblock.shop;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cennik serwera: wykup (sell) dla SellChest, rozdzek sprzedazy i kuzni.
 *
 * <p>GUI sklepu przejelo zShop + zMenu, wiec warstwy prezentacyjnej tu nie ma:
 * zadnych slotow, ikon, nazw ani lore. Sekcja categories w configu to juz tylko
 * przestrzen nazw grupujaca wpisy; ceny czyta sie przez product i productByCustomItem.
 */
public record ShopCatalog(@NotNull Map<Material, Product> byMaterial,
                          @NotNull Map<String, Product> byCustomItem) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,40}");
    private static final long MAX_PRICE = 1_000_000_000L;

    public ShopCatalog {
        byMaterial = Map.copyOf(byMaterial);
        byCustomItem = Map.copyOf(byCustomItem);
    }

    public static @NotNull ShopCatalog load(@NotNull ConfigurationSection root) {
        ConfigurationSection categoriesSection = required(root, "categories");
        Map<Material, Product> productsByMaterial = new HashMap<>();
        Map<String, Product> productsByCustomItem = new HashMap<>();
        if (categoriesSection.getKeys(false).isEmpty()) {
            throw new IllegalArgumentException("shop.categories cannot be empty");
        }
        for (String categoryId : categoriesSection.getKeys(false)) {
            validId(categoryId, "shop category");
            ConfigurationSection category = required(categoriesSection, categoryId);
            ConfigurationSection items = required(category, "items");
            boolean anyProduct = false;
            for (String productId : items.getKeys(false)) {
                validId(productId, "shop product");
                ConfigurationSection item = required(items, productId);
                String customItem = item.getString("custom-item");
                String currencyItem = item.getString("currency-item");
                int priceItemCount = range(item.getInt("price-item-count", 0), 0, 64,
                        productId + ".price-item-count");
                String rawMaterial = item.getString("material");
                Material material;
                if (rawMaterial != null) {
                    material = material(rawMaterial, "shop product " + productId);
                } else if (customItem != null) {
                    material = Material.PAPER;
                } else {
                    throw new IllegalArgumentException("Missing material in shop product " + productId);
                }
                long buy = price(item.getLong("buy", 0L), productId + ".buy");
                long sell = price(item.getLong("sell", 0L), productId + ".sell");
                if (buy == 0L && sell == 0L && (currencyItem == null || priceItemCount <= 0)) {
                    throw new IllegalArgumentException(productId + " must be buyable or sellable");
                }
                if (buy > 0L && sell > buy) {
                    throw new IllegalArgumentException(productId + " sell price exceeds buy price");
                }
                Product product = new Product(productId, material, buy, sell,
                        customItem, currencyItem, priceItemCount);
                if (customItem != null) {
                    if (productsByCustomItem.putIfAbsent(customItem, product) != null) {
                        throw new IllegalArgumentException("Custom item appears twice in shop: " + customItem);
                    }
                } else {
                    if (productsByMaterial.putIfAbsent(material, product) != null) {
                        throw new IllegalArgumentException("Material appears twice in shop: " + material);
                    }
                }
                anyProduct = true;
            }
            if (!anyProduct) {
                throw new IllegalArgumentException("Empty shop category " + categoryId);
            }
        }
        validateGuardrails(root.getConfigurationSection("guardrails.recipes"), productsByMaterial);
        return new ShopCatalog(productsByMaterial, productsByCustomItem);
    }

    public @Nullable Product product(@NotNull Material material) {
        return byMaterial.get(material);
    }

    public @Nullable Product productByCustomItem(@NotNull String customItemId) {
        return byCustomItem.get(customItemId);
    }

    private static void validateGuardrails(@Nullable ConfigurationSection recipes,
                                           Map<Material, Product> products) {
        if (recipes == null) {
            return;
        }
        for (String id : recipes.getKeys(false)) {
            ConfigurationSection recipe = required(recipes, id);
            Material input = material(recipe.getString("input"), "guardrail " + id + " input");
            Material output = material(recipe.getString("output"), "guardrail " + id + " output");
            int inputCount = range(recipe.getInt("input-count", 1), 1, 64,
                    "guardrail input-count");
            int outputCount = range(recipe.getInt("output-count", 1), 1, 64,
                    "guardrail output-count");
            Product inputProduct = products.get(input);
            Product outputProduct = products.get(output);
            if (inputProduct == null || outputProduct == null || inputProduct.buy() == 0L
                    || outputProduct.sell() == 0L) {
                continue;
            }
            long cost = Math.multiplyExact(inputProduct.buy(), inputCount);
            long revenue = Math.multiplyExact(outputProduct.sell(), outputCount);
            if (revenue > cost) {
                throw new IllegalArgumentException("Crafting arbitrage in guardrail " + id
                        + ": buy cost " + cost + " < sell revenue " + revenue);
            }
        }
    }

    private static ConfigurationSection required(ConfigurationSection parent, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException("Missing configuration section " + path);
        }
        return section;
    }

    private static Material material(String raw, String path) {
        Material material = raw == null ? null : Material.matchMaterial(raw);
        if (material == null || material.isAir() || !material.isItem()) {
            throw new IllegalArgumentException("Invalid material in " + path + ": " + raw);
        }
        return material;
    }

    private static long price(long value, String path) {
        if (value < 0L || value > MAX_PRICE) {
            throw new IllegalArgumentException(path + " outside 0.." + MAX_PRICE);
        }
        return value;
    }

    private static int range(int value, int min, int max, String path) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(path + " outside " + min + ".." + max);
        }
        return value;
    }

    private static void validId(String value, String label) {
        if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + label + " id " + value);
        }
    }

    public record Product(@NotNull String id, @NotNull Material material,
                   long buy, long sell,
                   @Nullable String customItem,
                   @Nullable String currencyItem,
                   int priceItemCount) {

        public boolean isCustom() {
            return customItem != null;
        }

        public boolean isCurrencyItemPayment() {
            return currencyItem != null && priceItemCount > 0;
        }
    }
}
