package org.rafalohaki.wpmecore.addons.skyblock;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.shared.FortuneYield;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F14: tempo zarobku z ROLNICTWA liczone z configu, nie z wyczucia.
 *
 * <p><b>Czego brakowało.</b> F7 zbiła OneBlocka i minionki, F8 generator —
 * a {@code gameplay-drops} nie miał bramki w ogóle. Wysyłana konfiguracja
 * dawała 0,8 % szansy na Perydot Urodzaju (600 coins) przy KAŻDYM zbiorze
 * dojrzałej uprawy, czyli 4,80 coins na zbiór — więcej niż sama pszenica —
 * bez żadnego ogranicznika tempa. Do tego najlepsza uprawa (netherowa
 * brodawka po 4) dawała przy Fortunie III 14,00 coins netto na zbiór. Razem
 * 50 760 coins/h przy tempie odniesienia, czyli <b>dwukrotność całego
 * budżetu ekonomii</b> z jednej ścieżki rozgrywki.
 *
 * <p><b>Model.</b> Przychód godziny rolnictwa to dla każdej uprawy:
 * <ol>
 *   <li>waniliowy drop ze zbioru po cenach skupu ze sklepu, przy poziomie
 *       Fortuny z {@code balance.fortune-level} — Fortuna działa na motyki
 *       ({@code #minecraft:enchantable/mining_loot} zawiera
 *       {@code #minecraft:hoes}), więc uprawy podlegają jej tak samo jak rudy,</li>
 *   <li>minus jedna sztuka na ponowne zasianie — zbiór, który zjada własną
 *       sadzonkę, nie jest przychodem,</li>
 *   <li>plus perydot z {@code gameplay-drops.farming.peridot}, ograniczony
 *       oknem {@code gameplay-drops.cooldown-seconds}.</li>
 * </ol>
 * Bramka bierze NAJLEPSZĄ uprawę, bo gracz wybiera jedną i sadzi ją na całej
 * farmie — średnia po uprawach mierzyłaby zachowanie, którego nikt nie ma.
 *
 * <p>Test nie dotyka klasy {@code Material} ani MockBukkita — to arytmetyka na
 * configu — więc biegnie w <b>domyślnej</b> bramce.
 */
class GameplayDropsEconomyBalanceTest {

    private static final Path CONFIG = Path.of("src/main/resources/config.yml");

    /** Zapas wyłącznie na arytmetykę zmiennoprzecinkową, nie na balans. */
    private static final double FP_SLACK = 1.0e-6;

    /**
     * Jeden składnik dropu ze zbioru: pozycja sklepowa, waniliowa liczba sztuk
     * (albo parametr {@code extra} przy formule dwumianowej) i kształt
     * {@code apply_bonus}.
     */
    private record Yield(String shopMaterial, double base, FortuneYield.Formula fortune) {}

    /**
     * Uprawa: co daje zbiór i ile sztuk wraca do ziemi.
     *
     * <p>Liczby są przepisane z waniliowych tabel łupów wersji 26.2
     * ({@code data/minecraft/loot_table/blocks/*.json}), nie z pamięci:
     * <ul>
     *   <li>{@code wheat} — 1 pszenica + {@code Bin(3+F, 4/7)} nasion,</li>
     *   <li>{@code beetroots} — 1 burak + {@code Bin(3+F, 4/7)} nasion,</li>
     *   <li>{@code carrots}/{@code potatoes} — 1 sztuka + {@code Bin(3+F, 4/7)}
     *       (ziemniak dokłada 2 % zatrutego, którego sklep nie skupuje),</li>
     *   <li>{@code nether_wart} — {@code U{2..4}} + {@code U{0..F}},</li>
     *   <li>{@code cocoa} — {@code set_count 3.0}, bez {@code apply_bonus}:
     *       jedyna uprawa, której Fortuna nie dotyka.</li>
     * </ul>
     */
    private record Crop(String id, List<Yield> yields, String replantMaterial, double replantCount) {}

    private static final List<Crop> CROPS = List.of(
            new Crop("wheat", List.of(
                    new Yield("WHEAT", 1.0, FortuneYield.Formula.NONE),
                    new Yield("WHEAT_SEEDS", 3.0, FortuneYield.Formula.BINOMIAL_BONUS)),
                    "WHEAT_SEEDS", 1.0),
            new Crop("beetroots", List.of(
                    new Yield("BEETROOT", 1.0, FortuneYield.Formula.NONE),
                    new Yield("BEETROOT_SEEDS", 3.0, FortuneYield.Formula.BINOMIAL_BONUS)),
                    "BEETROOT_SEEDS", 1.0),
            new Crop("carrots", List.of(
                    new Yield("CARROT", 1.0, FortuneYield.Formula.NONE),
                    new Yield("CARROT", 3.0, FortuneYield.Formula.BINOMIAL_BONUS)),
                    "CARROT", 1.0),
            new Crop("potatoes", List.of(
                    new Yield("POTATO", 1.0, FortuneYield.Formula.NONE),
                    new Yield("POTATO", 3.0, FortuneYield.Formula.BINOMIAL_BONUS)),
                    "POTATO", 1.0),
            new Crop("nether_wart", List.of(
                    new Yield("NETHER_WART", 3.0, FortuneYield.Formula.UNIFORM_BONUS)),
                    "NETHER_WART", 1.0),
            new Crop("cocoa", List.of(
                    new Yield("COCOA_BEANS", 3.0, FortuneYield.Formula.NONE)),
                    "COCOA_BEANS", 1.0));

    @Test
    void shippedFarmingStaysUnderTheConfiguredEarningCeiling() throws IOException {
        YamlConfiguration config = load(CONFIG);
        double actions = config.getDouble("balance.reference-actions-per-hour");
        int fortune = config.getInt("balance.fortune-level");
        double ceiling = farmingCeiling(config);
        assertTrue(actions > 0 && ceiling > 0,
                "config.yml:balance / gameplay-drops.balance ma niepoprawne wartości");

        double crystalIncome = peridotHourlyIncome(config);
        Map<String, Double> raw = perCropHourlyIncome(config, fortune);
        Map<String, Long> perCrop = new LinkedHashMap<>();
        raw.forEach((id, income) -> perCrop.put(id, Math.round(income)));

        String best = null;
        double bestIncome = 0.0;
        for (Map.Entry<String, Double> entry : raw.entrySet()) {
            if (entry.getValue() > bestIncome) {
                bestIncome = entry.getValue();
                best = entry.getKey();
            }
        }

        assertTrue(bestIncome <= ceiling + FP_SLACK, "rolnictwo przy Fortunie " + fortune
                + " daje najlepszą uprawą ('" + best + "') " + Math.round(bestIncome)
                + " coins/h przy " + Math.round(actions) + " zbiorach/h (w tym perydot "
                + Math.round(crystalIncome) + "), sufit z"
                + " config.yml:gameplay-drops.balance.max-coins-per-hour to " + Math.round(ceiling)
                + ". Pokrętła: ceny skupu w shop.categories.farming (wartość zbioru),"
                + " gameplay-drops.farming.peridot (kryształ) i gameplay-drops.cooldown-seconds"
                + " (tempo). Wszystkie uprawy (coins/h): " + perCrop);
    }

    /**
     * Coins/h z każdej uprawy przy danym poziomie Fortuny (w tym perydot,
     * niezależny od narzędzia). Model wspólny z
     * {@code GameplayDropsFortuneSpacePropertyTest} — jedno źródło tabeli,
     * żeby bramka i właściwości nie mogły się rozjechać.
     */
    static Map<String, Double> perCropHourlyIncome(YamlConfiguration config, int fortune) {
        double actions = config.getDouble("balance.reference-actions-per-hour");
        double crystalIncome = peridotHourlyIncome(config);
        Map<String, Integer> sellPrices = shopSellPrices(config);
        Map<String, Double> income = new LinkedHashMap<>();
        for (Crop crop : CROPS) {
            double perHarvest = 0.0;
            for (Yield yield : crop.yields()) {
                perHarvest += price(sellPrices, yield.shopMaterial())
                        * FortuneYield.amount(yield.fortune(), yield.base(), 1, fortune);
            }
            perHarvest -= price(sellPrices, crop.replantMaterial()) * crop.replantCount();
            income.put(crop.id(), perHarvest * actions + crystalIncome);
        }
        return income;
    }

    /**
     * Perydot: albo tyle, ile wylosuje szansa przy tempie odniesienia, albo
     * tyle, ile przepuści okno na chunk — co mniejsze. Ta sama arytmetyka
     * co przy krysztale generatora.
     */
    static double peridotHourlyIncome(YamlConfiguration config) {
        ConfigurationSection drops = section(config, "gameplay-drops");
        double actions = config.getDouble("balance.reference-actions-per-hour");
        double chance = drops.getDouble("farming.peridot") / 100.0;
        double windowSeconds = drops.getDouble("cooldown-seconds");
        double crystalCap = windowSeconds > 0 ? 3600.0 / windowSeconds : Double.POSITIVE_INFINITY;
        double crystalHits = Math.min(actions * chance, crystalCap);
        return crystalHits * shopCustomSellPrices(config)
                .getOrDefault(SkyBlockGameplayDropsListener.PERIDOT_ID, 0);
    }

    static double farmingCeiling(YamlConfiguration config) {
        return section(config, "gameplay-drops.balance").getDouble("max-coins-per-hour");
    }

    /**
     * Rolnictwo nie może bić rozdziału endgame'owego OneBlocka ani być gorsze od
     * generatora: farma to inwestycja w budowę, a nie progresja, więc jej miejsce
     * w drabince jest pośrodku. Ten warunek wiąże trzy sufity naraz, żeby żadna
     * następna fala nie podniosła jednego z nich w oderwaniu od pozostałych.
     */
    @Test
    void farmingSitsBetweenTheGeneratorAndTheTargetRate() throws IOException {
        YamlConfiguration config = load(CONFIG);
        double farming = section(config, "gameplay-drops.balance").getDouble("max-coins-per-hour");
        double generator = section(config, "generator.balance").getDouble("max-coins-per-hour");
        double target = config.getDouble("balance.target-coins-per-hour");
        assertTrue(generator > 0 && target > 0, "config.yml: brak sufitów do porównania");

        assertTrue(farming > generator, "sufit rolnictwa to " + Math.round(farming)
                + " coins/h, a generatora " + Math.round(generator) + ". Farmę trzeba zbudować,"
                + " a generator kosztuje wiadro lawy i działa od pierwszej minuty — ścieżka"
                + " droższa nie może płacić gorzej.");
        assertTrue(farming < target, "sufit rolnictwa to " + Math.round(farming)
                + " coins/h przy tempie docelowym " + Math.round(target)
                + " (config.yml:balance.target-coins-per-hour). Rolnictwo jest jednym z kilku"
                + " kanałów, a nie całym budżetem gracza.");
    }

    private static int price(Map<String, Integer> sellPrices, String material) {
        return sellPrices.getOrDefault(material, 0);
    }

    private static Map<String, Integer> shopSellPrices(YamlConfiguration shopYaml) {
        Map<String, Integer> prices = new LinkedHashMap<>();
        forEachShopItem(shopYaml, item -> {
            if (item.getString("custom-item") == null) {
                prices.put(item.getString("material", ""), item.getInt("sell", 0));
            }
        });
        return prices;
    }

    /** Kryształy siedzą w sklepie na {@code material: PAPER}; kluczem jest id customu. */
    private static Map<String, Integer> shopCustomSellPrices(YamlConfiguration shopYaml) {
        Map<String, Integer> prices = new LinkedHashMap<>();
        forEachShopItem(shopYaml, item -> {
            String customItem = item.getString("custom-item");
            if (customItem != null) {
                prices.put(customItem, item.getInt("sell", 0));
            }
        });
        return prices;
    }

    private static void forEachShopItem(YamlConfiguration shopYaml,
                                        Consumer<ConfigurationSection> visitor) {
        ConfigurationSection categories = section(shopYaml, "shop.categories");
        for (String categoryId : categories.getKeys(false)) {
            ConfigurationSection items = categories.getConfigurationSection(categoryId + ".items");
            if (items == null) {
                continue;
            }
            for (String itemId : items.getKeys(false)) {
                ConfigurationSection item = items.getConfigurationSection(itemId);
                if (item != null) {
                    visitor.accept(item);
                }
            }
        }
    }

    private static ConfigurationSection section(ConfigurationSection root, String path) {
        ConfigurationSection found = root.getConfigurationSection(path);
        assertNotNull(found, "brak sekcji " + path);
        return found;
    }

    private static YamlConfiguration load(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "brak wysyłanego zasobu " + path.toAbsolutePath());
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }
}
