package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bramka strony ZLEWU (F17) — odpowiednik trzech bramek strony wpływów.
 *
 * <p>Do tej fali nikt nie liczył, ile naprawdę kosztuje rzecz z kuźni.
 * {@code MinionEconomyBalanceTest} bierze wyłącznie {@code cost-money}
 * receptury wejściowej, więc Płyta Wzmocniona wymagana przez minionka diamentu
 * (1,7 mln coins-eq) była dla niego niewidzialna — i to samo przeoczenie
 * przepuściło Klucz Wolframowy za 2 704 156 coins-eq (111 h gry) oraz
 * Napierśnik Otchłani za 5 918 872 (237 h) do konfiguracji produkcyjnej.
 *
 * <p>Ten test liczy <b>pełny łańcuch</b>: monety wszystkich kroków plus
 * składniki po cenach skupu ze sklepu, z podstawieniem podreceptur. Reguła
 * i sufit siedzą w {@code forge.yml:balance}, nie w kodzie.
 *
 * <p>Receptury na minionka są z tego sufitu wyłączone <b>celowo</b>: minionek
 * odblokowuje przychód, więc rządzi nim zwrot (pasmo 30–80 h z
 * {@code minions.yml:balance}), a nie cena. To dwie różne reguły dla dwóch
 * różnych rodzajów zakupu, obie zapisane w konfiguracji.
 */
class ForgeEconomyBalanceTest {

    private static final Path FORGE = Path.of("src/main/resources/forge.yml");
    private static final Path CONFIG = Path.of("src/main/resources/config.yml");

    static final String CUSTOM_PREFIX = "custom:";
    static final String MINION_PREFIX = "minion:";
    /** Migracja Eco: pety z kuźni (`result-command: ecopets give %player% <typ>`) dziedziczą ceny minionków. */
    static final String PET_COMMAND_PREFIX = "ecopets give ";

    /** Typ minionka/peta z receptury albo null, gdy to zwykły wyrób. */
    static String minionTypeOf(org.bukkit.configuration.ConfigurationSection recipes, String id) {
        String item = recipes.getString(id + ".result-item", "");
        if (item.startsWith(MINION_PREFIX)) {
            return item.substring(MINION_PREFIX.length());
        }
        String command = recipes.getString(id + ".result-command", "");
        if (command.startsWith(PET_COMMAND_PREFIX)) {
            String[] parts = command.trim().split("\\s+");
            return parts[parts.length - 1];
        }
        return null;
    }

    @Test
    void noSingleForgePurchaseCostsMoreThanTheDeclaredCeiling() throws IOException {
        YamlConfiguration forge = load(FORGE);
        ConfigurationSection balance = section(forge, "balance");
        long ceiling = balance.getLong("max-coins-eq-per-recipe", -1L);
        int ceilingHours = balance.getInt("max-hours-per-recipe", -1);
        assertTrue(ceiling > 0 && ceilingHours > 0,
                "forge.yml:balance ma niepoprawne wartości");

        long target = section(load(CONFIG), "balance")
                .getLong("target-coins-per-hour", -1L);
        assertTrue(target > 0, "config.yml:balance.target-coins-per-hour musi być > 0");
        assertEquals(ceiling, ceilingHours * target,
                "forge.yml:balance.max-coins-eq-per-recipe (" + ceiling + ") nie zgadza się"
                        + " z max-hours-per-recipe × config.yml:balance.target-coins-per-hour ("
                        + ceilingHours + " × " + target + "). Przelicz sufit po zmianie tempa.");

        Chains chains = new Chains(forge, shopSellPrices(load(CONFIG)));
        Map<String, Long> costs = new LinkedHashMap<>();
        for (String id : chains.recipes.getKeys(false)) {
            if (minionTypeOf(chains.recipes, id) != null) {
                continue;
            }
            costs.put(id, chains.of(id));
        }
        assertTrue(costs.size() >= 2, "za mało receptur do porównania: " + costs);

        for (Map.Entry<String, Long> entry : costs.entrySet()) {
            assertTrue(entry.getValue() <= ceiling, "receptura '" + entry.getKey()
                    + "' kosztuje w pełnym łańcuchu " + entry.getValue() + " coins-eq = "
                    + Math.round(entry.getValue() * 10.0 / target) / 10.0 + " h gry przy tempie "
                    + target + " coins/h, sufit z forge.yml:balance to " + ceiling
                    + " coins-eq (" + ceilingHours + " h). Pokrętła: cost-money tej receptury"
                    + " ORAZ każdego jej podskładnika (płyty siedzą w czterech wyrobach naraz)."
                    + " Wszystkie łańcuchy: " + costs);
        }
    }

    /**
     * Zniżki rangowe z wysyłanego pliku mieszczą się w pasmie decyzji właściciela
     * (10–15 %) i rosną wraz z rangą. Bez tego drugiego warunku legenda mogłaby
     * dostać mniej niż sponsor i nikt by tego nie zauważył.
     */
    @Test
    void shippedRankDiscountsStayInsideTheOwnersBand() throws IOException {
        Map<String, Integer> discounts = new LinkedHashMap<>();
        for (Map<?, ?> entry : load(FORGE).getMapList("discounts")) {
            discounts.put(String.valueOf(entry.get("permission")),
                    ((Number) entry.get("percent")).intValue());
        }

        int sponsor = discounts.getOrDefault("skyblockgameplay.forge.discount.sponsor", -1);
        int legend = discounts.getOrDefault("skyblockgameplay.forge.discount.legend", -1);
        assertTrue(sponsor >= 10 && sponsor <= 15,
                "zniżka sponsora poza pasmem 10–15 %: " + sponsor);
        assertTrue(legend >= 10 && legend <= 15,
                "zniżka legendy poza pasmem 10–15 %: " + legend);
        assertTrue(legend > sponsor, "legend (" + legend + " %) musi mieć większą zniżkę"
                + " niż sponsor (" + sponsor + " %) — legenda dziedziczy po sponsorze");
    }

    /** Pełne łańcuchy receptur liczone rekurencyjnie, z wykrywaniem cyklu. */
    static final class Chains {
        private final ConfigurationSection recipes;
        private final Map<String, Integer> sellByMaterial;
        private final Map<String, Integer> sellByCustomItem;
        private final Map<String, String> producerOf = new LinkedHashMap<>();
        private final Deque<String> visiting = new ArrayDeque<>();

        Chains(YamlConfiguration forge, ShopPrices prices) {
            this.recipes = section(forge, "recipes");
            this.sellByMaterial = prices.byMaterial();
            this.sellByCustomItem = prices.byCustomItem();
            for (String id : recipes.getKeys(false)) {
                producerOf.putIfAbsent(recipes.getString(id + ".result-item", ""), id);
            }
        }

        /** Monety wpisywane wprost w recepturę — bez składników. */
        long costMoney(String id) {
            return recipes.getLong(id + ".cost-money", 0L);
        }

        /** Czy którykolwiek składnik wnosi wartość (cena skupu albo podreceptura). */
        boolean hasValuedIngredient(String id) {
            for (Map<?, ?> ingredient : recipes.getMapList(id + ".required-items")) {
                if (valueOf(String.valueOf(ingredient.get("item")),
                        ((Number) ingredient.get("amount")).intValue()) > 0L) {
                    return true;
                }
            }
            return false;
        }

        /** Czy którykolwiek składnik jest wyceniony ceną skupu liścia (bez podreceptury) —
         *  warunek, przy którym skalowanie cen sklepu MUSI podnieść koszt łańcucha. */
        boolean hasPricedLeafIngredient(String id) {
            for (Map<?, ?> ingredient : recipes.getMapList(id + ".required-items")) {
                String itemKey = String.valueOf(ingredient.get("item"));
                if (producerOf.containsKey(itemKey)) {
                    continue;
                }
                Integer sell = itemKey.startsWith(CUSTOM_PREFIX)
                        ? sellByCustomItem.get(itemKey.substring(CUSTOM_PREFIX.length()))
                        : sellByMaterial.get(itemKey);
                if (sell != null && sell > 0) {
                    return true;
                }
            }
            return false;
        }

        long of(String id) {
            assertTrue(visiting.stream().noneMatch(id::equals),
                    "cykl w recepturach kuźni: " + visiting + " -> " + id);
            visiting.push(id);
            try {
                long total = recipes.getLong(id + ".cost-money", 0L);
                List<Map<?, ?>> ingredients = recipes.getMapList(id + ".required-items");
                for (Map<?, ?> ingredient : ingredients) {
                    total += valueOf(String.valueOf(ingredient.get("item")),
                            ((Number) ingredient.get("amount")).intValue());
                }
                return total;
            } finally {
                visiting.pop();
            }
        }

        /**
         * Wartość składnika. Jeśli sklep go nie skupuje, a kuźnia potrafi go
         * wykuć — liczymy koszt wykucia, zaokrąglony w górę do pełnych partii
         * (receptura bursztynu wydaje dwie sztuki, a kupić pół partii się nie da).
         */
        private long valueOf(String itemKey, int amount) {
            String producer = producerOf.get(itemKey);
            if (producer != null) {
                int perBatch = Math.max(1, recipes.getInt(producer + ".result-amount", 1));
                return of(producer) * Math.ceilDiv(amount, perBatch);
            }
            Integer sell = itemKey.startsWith(CUSTOM_PREFIX)
                    ? sellByCustomItem.get(itemKey.substring(CUSTOM_PREFIX.length()))
                    : sellByMaterial.get(itemKey);
            // Brak ceny skupu i brak receptury = składnik spoza ekonomii monetarnej
            // (netheryt, surowy wolfram). Wycena zero jest tu ZANIŻENIEM, więc
            // sufit i tak wiąże.
            return sell == null ? 0L : (long) sell * amount;
        }
    }

    record ShopPrices(Map<String, Integer> byMaterial,
                      Map<String, Integer> byCustomItem) {

        /** Wszystkie ceny skępu przemnożone przez czynnik — sonda do właściwości. */
        ShopPrices scaled(int factor) {
            Map<String, Integer> materials = new LinkedHashMap<>();
            byMaterial.forEach((key, price) -> materials.put(key, price * factor));
            Map<String, Integer> custom = new LinkedHashMap<>();
            byCustomItem.forEach((key, price) -> custom.put(key, price * factor));
            return new ShopPrices(materials, custom);
        }
    }

    static ShopPrices shopSellPrices(YamlConfiguration shopYaml) {
        Map<String, Integer> byMaterial = new LinkedHashMap<>();
        Map<String, Integer> byCustomItem = new LinkedHashMap<>();
        ConfigurationSection categories = section(shopYaml, "shop.categories");
        for (String categoryId : categories.getKeys(false)) {
            ConfigurationSection items =
                    categories.getConfigurationSection(categoryId + ".items");
            if (items == null) {
                continue;
            }
            for (String itemId : items.getKeys(false)) {
                ConfigurationSection item = items.getConfigurationSection(itemId);
                if (item == null) {
                    continue;
                }
                String customItem = item.getString("custom-item");
                if (customItem != null) {
                    byCustomItem.put(customItem, item.getInt("sell", 0));
                } else {
                    byMaterial.put(item.getString("material", ""), item.getInt("sell", 0));
                }
            }
        }
        return new ShopPrices(byMaterial, byCustomItem);
    }

    private static ConfigurationSection section(ConfigurationSection root, String path) {
        ConfigurationSection section = root.getConfigurationSection(path);
        assertNotNull(section, "brak sekcji " + path);
        return section;
    }

    private static YamlConfiguration load(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "brak wysyłanego zasobu " + path.toAbsolutePath());
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }
}
