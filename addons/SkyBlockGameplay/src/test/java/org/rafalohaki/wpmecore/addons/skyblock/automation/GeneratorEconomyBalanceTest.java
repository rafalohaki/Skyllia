package org.rafalohaki.wpmecore.addons.skyblock.automation;

import org.bukkit.Material;
import org.rafalohaki.wpmecore.addons.skyblock.shared.FortuneYield;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F8: tempo zarobku z generatora bruku liczone z configu, nie z wyczucia.
 *
 * <p>Wysyłany generator dawał przy tempie odniesienia (jedno formowanie na
 * sekundę) <b>57 044 coins/h</b>, czyli 2,3× tempo docelowe 25 000 coins/h i
 * 5,7× więcej niż naprawiony w F7 rozdział endgame'owy OneBlocka. Składały się
 * na to trzy rzeczy, z których żadna nie była policzona: zwykłe rudy o
 * waniliowych krotnościach (miedź 2–5 sztuk, lapis 4–9, redstone 4–5), Opal
 * Astralny doklejany do każdej rudy lapisu przez {@code gameplay-drops} oraz
 * kryształ z {@code generator.custom-drops}, który sklep skupuje po 500–800
 * coins i który sam dawał 14 400 coins/h.
 *
 * <p><b>Model.</b> Przychód godziny to trzy składniki liczone z configu po
 * cenach skupu ze sklepu:
 * <ol>
 *   <li>trafienia rudy — ograniczone przez {@code ores.ore-cooldown-seconds}
 *       (rzadkie dodatkowo przez {@code ores.rare-cooldown-seconds}),</li>
 *   <li>wypełniacz, czyli formowania, które rudą nie zostały,</li>
 *   <li>drop z {@code custom-drops} — ograniczony przez własne okno.</li>
 * </ol>
 *
 * <p><b>Dlaczego okno, a nie same wagi.</b> Przychód generatora to
 * (formowań/s) × (wartość formowania). Wagi sterują wyłącznie drugim
 * czynnikiem, a pierwszy zależy od tego, jak szybko ktoś kopie — audyt zmierzył
 * ~2 formowania/s, a ściana generatorów w jednym chunku obsługiwana przez
 * zespół wyspy mnoży to dalej. Sufit oparty na samych wagach trzymałby się
 * tylko przy dokładnie jednym formowaniu na sekundę. Dlatego liczby niżej są
 * limitami tempa na chunk, a wagi zostały nietknięte.
 *
 * <p>Test celowo nie potrzebuje MockBukkita: wszystko jest arytmetyką na
 * configu, a jedyne wywołania Bukkita to {@code Material.matchMaterial} i dwa
 * statyczne predykaty z listenera (żeby klasyfikacja rud w bramce nie mogła
 * rozjechać się z runtime'em). Dzięki temu biegnie w <b>domyślnej</b> bramce.
 */
class GeneratorEconomyBalanceTest {

    private static final Path CONFIG = Path.of("src/main/resources/config.yml");
    private static final Path ONEBLOCK = Path.of("src/main/resources/oneblock.yml");

    /** Przelicznik jednostek, nie pokrętło balansu. */
    private static final double SECONDS_PER_HOUR = 3600.0;

    /** Zapas wyłącznie na arytmetykę zmiennoprzecinkową, nie na balans. */
    private static final double FP_SLACK = 1.0e-6;

    /**
     * Co gracz naprawdę dostaje za rozbicie wygenerowanego bloku: pozycja
     * sklepowa i średnia liczba sztuk. Zawężona kopia
     * {@code OneBlockEconomyBalanceTest.VANILLA_DROPS} — generator ma własną,
     * krótką tabelę i nie potrzebuje reszty.
     *
     * <p>Rudy surowe wyceniamy po sztabce (piec jest darmowy i 1:1).
     * Krotności są waniliowe: miedź 2–5, lapis 4–9, redstone 4–5.
     *
     * <p>F14: doszedł kształt {@code apply_bonus} z waniliowej tabeli łupów,
     * bo bez niego bramka mierzyła gołe narzędzie zamiast sufitu — patrz
     * {@link FortuneYield}.
     */
    private record Drop(String shopMaterial, double amount, FortuneYield.Formula fortune) {}

    private static final Map<String, Drop> ORE_DROPS = Map.ofEntries(
            Map.entry("COBBLESTONE", new Drop("COBBLESTONE", 1.0, FortuneYield.Formula.NONE)),
            Map.entry("STONE", new Drop("COBBLESTONE", 1.0, FortuneYield.Formula.NONE)),
            Map.entry("COAL_ORE", new Drop("COAL", 1.0, FortuneYield.Formula.ORE_DROPS)),
            Map.entry("COPPER_ORE", new Drop("COPPER_INGOT", 3.5, FortuneYield.Formula.ORE_DROPS)),
            Map.entry("IRON_ORE", new Drop("IRON_INGOT", 1.0, FortuneYield.Formula.ORE_DROPS)),
            Map.entry("GOLD_ORE", new Drop("GOLD_INGOT", 1.0, FortuneYield.Formula.ORE_DROPS)),
            Map.entry("REDSTONE_ORE", new Drop("REDSTONE", 4.5, FortuneYield.Formula.UNIFORM_BONUS)),
            Map.entry("LAPIS_ORE", new Drop("LAPIS_LAZULI", 6.5, FortuneYield.Formula.ORE_DROPS)),
            Map.entry("DIAMOND_ORE", new Drop("DIAMOND", 1.0, FortuneYield.Formula.ORE_DROPS)),
            Map.entry("EMERALD_ORE", new Drop("EMERALD", 1.0, FortuneYield.Formula.ORE_DROPS)),
            // F27: ancient debris zamyka jedyną lukę w łańcuchu — bez niego
            // NETHERITE_INGOT nie ma na wyspie klasycznej żadnego źródła
            // (światy Skylli to VoidWorldGen), więc Płyta Wzmocniona, Klucz
            // Wolframowy, Napierśnik Otchłani, Złoty Lotos i minionek diamentu
            // były nieosiągalne. Wycena ZERO nie jest przeoczeniem: sklep nie
            // skupuje ancient debris, więc do sufitu tempa nie dokłada nic —
            // a że `isRareMaterial` liczy je do puli rud rzadkich, ROZCIEŃCZA
            // złoto/diament/szmaragd i tempo generatora spada (12 471 -> 12 408).
            // Fortuna nie działa: waniliowa tabela łupów ancient_debris nie ma
            // `apply_bonus` (sprawdzone w sigma-26.2.jar), stąd NONE.
            Map.entry("ANCIENT_DEBRIS", new Drop("ANCIENT_DEBRIS", 1.0, FortuneYield.Formula.NONE)));

    /**
     * Warstwa świata: własny zestaw dropów z {@code custom-drops}, własny
     * wypełniacz i informacja, czy w ogóle dochodzi do podmiany na rudę.
     * Nether jej nie ma — {@code onBlockForm} robi podmianę wyłącznie w
     * {@code World.Environment.NORMAL}.
     */
    record Layer(String id, String customSection, String fillerMaterial, boolean oreSubstitution) {}

    static final List<Layer> LAYERS = List.of(
            new Layer("overworld (Y > 0)", "overworld", "COBBLESTONE", true),
            new Layer("deepslate (Y <= 0)", "deepslate", "COBBLED_DEEPSLATE", true),
            new Layer("nether", "nether", "BASALT", false));

    @Test
    void shippedGeneratorStaysUnderTheConfiguredEarningCeiling() throws IOException {
        YamlConfiguration config = load(CONFIG);
        double forms = section(config, "generator.balance").getDouble("reference-forms-per-hour");
        double ceiling = generatorCeiling(config);
        assertTrue(forms > 0 && ceiling > 0, "config.yml:generator.balance ma niepoprawne wartości");
        int fortune = config.getInt("balance.fortune-level", 0);

        Map<String, LayerIncome> income = perLayerHourlyIncome(config, fortune);
        Map<String, Long> perLayer = new LinkedHashMap<>();
        income.forEach((layer, value) -> perLayer.put(layer, Math.round(value.total())));

        for (Layer layer : LAYERS) {
            LayerIncome value = income.get(layer.id());
            double total = value.total();
            assertTrue(total <= ceiling + FP_SLACK, "generator w warstwie '" + layer.id()
                    + "' przy Fortunie " + fortune + " daje "
                    + Math.round(total) + " coins/h przy " + Math.round(forms)
                    + " formowaniach/h (rudy " + Math.round(value.ore()) + ", wypełniacz "
                    + Math.round(value.filler()) + ", kryształ " + Math.round(value.custom())
                    + "), sufit z"
                    + " config.yml:generator.balance.max-coins-per-hour to " + Math.round(ceiling)
                    + ". Tempo trzymają okna ore-cooldown-seconds / rare-cooldown-seconds /"
                    + " custom-drops.cooldown-seconds — wagi i szanse sterują tylko składem."
                    + " Wszystkie warstwy (coins/h): " + perLayer);
        }
    }

    /** Sufit tempa z configu — jedno źródło dla bramki i właściwości. */
    static double generatorCeiling(YamlConfiguration config) {
        return section(config, "generator.balance").getDouble("max-coins-per-hour");
    }

    /**
     * Model przychodu każdej warstwy przy danym poziomie Fortuny. Wspólny
     * z {@code GeneratorFortuneSpacePropertyTest}, żeby przestrzeń
     * (poziom × warstwa) i punkt wysyłany liczyły się z tych samych tabel.
     */
    static Map<String, LayerIncome> perLayerHourlyIncome(YamlConfiguration config, int fortune) {
        ConfigurationSection generator = section(config, "generator");
        double forms = section(generator, "balance").getDouble("reference-forms-per-hour");
        Map<String, Integer> sellPrices = shopSellPrices(config);
        Map<String, Integer> customSellPrices = shopCustomSellPrices(config);

        ConfigurationSection ores = section(generator, "ores");
        ConfigurationSection rates = section(ores, "rates");
        double oreHitsCap = hitsPerHour(ores.getLong("ore-cooldown-seconds"));
        double rareHitsCap = hitsPerHour(ores.getLong("rare-cooldown-seconds"));

        // Wagi rozbite na wypełniacz / rudy zwykłe / rudy rzadkie — dokładnie
        // tak, jak dzieli je onBlockForm.
        double totalWeight = 0.0;
        double rareWeight = 0.0;
        double rareValue = 0.0;
        double commonWeight = 0.0;
        double commonValue = 0.0;
        for (String key : rates.getKeys(false)) {
            double weight = rates.getDouble(key);
            if (weight <= 0.0) {
                continue;
            }
            totalWeight += weight;
            // Ta sama normalizacja co w loadConfig() — inaczej mała litera w
            // configu przechodziłaby przez runtime, a wywracała bramkę.
            String name = key.toUpperCase(Locale.ROOT);
            Material material = Material.matchMaterial(name);
            assertNotNull(material, "generator.ores.rates: nieznany materiał '" + key + "'");
            if (CobblestoneGeneratorListener.isFillerMaterial(material)) {
                continue;
            }
            double value = oreValue(name, config, sellPrices, customSellPrices, fortune);
            if (CobblestoneGeneratorListener.isRareMaterial(material)) {
                rareWeight += weight;
                rareValue += weight * value;
            } else {
                commonWeight += weight;
                commonValue += weight * value;
            }
        }
        assertTrue(totalWeight > 0.0, "generator.ores.rates: suma wag = 0");

        double oreWeight = commonWeight + rareWeight;
        double oreHits = Math.min(forms * oreWeight / totalWeight, oreHitsCap);
        double rareAttempts = oreWeight > 0.0 ? oreHits * rareWeight / oreWeight : 0.0;
        double rareHits = Math.min(rareAttempts, rareHitsCap);
        double commonHits = oreHits - rareAttempts;
        double oreIncome = (commonWeight > 0.0 ? commonHits * commonValue / commonWeight : 0.0)
                + (rareWeight > 0.0 ? rareHits * rareValue / rareWeight : 0.0);

        ConfigurationSection customDrops = section(generator, "custom-drops");
        double customHitsCap = hitsPerHour(customDrops.getLong("cooldown-seconds"));

        Map<String, LayerIncome> income = new LinkedHashMap<>();
        for (Layer layer : LAYERS) {
            double ore = layer.oreSubstitution() ? oreIncome : 0.0;
            double hits = layer.oreSubstitution() ? oreHits : 0.0;
            // Formowanie, które nie zostało rudą, zostaje wypełniaczem —
            // i ten sklep skupuje albo nie (bruku deepslate nie skupuje).
            double filler = (forms - hits) * sellPrices.getOrDefault(layer.fillerMaterial(), 0);
            double custom = customDropIncome(customDrops, layer, forms, customHitsCap, customSellPrices);
            income.put(layer.id(), new LayerIncome(ore, filler, custom));
        }
        return income;
    }

    /** Składniki przychodu godziny jednej warstwy. */
    record LayerIncome(double ore, double filler, double custom) {
        double total() {
            return ore + filler + custom;
        }
    }

    /**
     * Generator jest dostępny od pierwszej minuty i za wiadro lawy, więc nie
     * może bić rozdziału endgame'owego OneBlocka, do którego trzeba przekopać
     * 6 000 bloków. Ten warunek wiąże oba sufity: podniesienie generatora bez
     * tknięcia OneBlocka zatrzyma się tutaj.
     */
    @Test
    void generatorCeilingStaysBelowTheEndgameOneBlockChapter() throws IOException {
        YamlConfiguration config = load(CONFIG);
        ConfigurationSection balance = section(config, "generator.balance");
        double forms = balance.getDouble("reference-forms-per-hour");
        double generatorCeiling = balance.getDouble("max-coins-per-hour");

        ConfigurationSection oneblockBalance = section(load(ONEBLOCK), "balance");
        double oneblockCeiling = oneblockBalance.getDouble("terminal-max-value-per-break") * forms;
        assertTrue(oneblockCeiling > 0, "oneblock.yml:balance.terminal-max-value-per-break = 0");

        assertTrue(generatorCeiling < oneblockCeiling, "sufit generatora to "
                + Math.round(generatorCeiling) + " coins/h, a sufit rozdziału endgame'owego"
                + " OneBlocka " + Math.round(oneblockCeiling) + " coins/h. Zawsze dostępne"
                + " źródło bez warunku wstępnego nie może płacić lepiej niż treść, do której"
                + " trzeba dojść przez 6 000 bloków progresji.");
    }

    /**
     * Wartość waniliowego dropu rudy po cenach skupu. Ruda lapisu dostaje
     * doklejony Opal Astralny: {@code SkyBlockGameplayDropsListener} losuje go
     * przy KAŻDYM rozbiciu lapisu, którego gracz sam nie postawił — a rudy
     * z generatora są właśnie takie.
     */
    private static double oreValue(String material, YamlConfiguration config,
                                   Map<String, Integer> sellPrices,
                                   Map<String, Integer> customSellPrices,
                                   int fortuneLevel) {
        Drop drop = ORE_DROPS.get(material);
        assertNotNull(drop, "generator.ores.rates: materiał '" + material + "' nie ma wyceny w"
                + " GeneratorEconomyBalanceTest.ORE_DROPS. Dopisz, co gracz za niego dostaje —"
                + " bez tego tabela generatora przechodzi przez bramkę niepoliczona.");
        double value = sellPrices.getOrDefault(drop.shopMaterial(), 0)
                * FortuneYield.amount(drop.fortune(), drop.amount(), 1, fortuneLevel);
        if (material.endsWith("LAPIS_ORE")) {
            value += config.getDouble("gameplay-drops.lapis.opal") / 100.0
                    * customSellPrices.getOrDefault(
                            "skyblock:crystal/opal", 0);
        }
        return value;
    }

    /**
     * Drop z {@code custom-drops}: albo tyle, ile wylosują szanse przy tempie
     * odniesienia, albo tyle, ile przepuści okno — co mniejsze. Skład jest
     * proporcjonalny do szans, więc pozycje bez ceny skupu (półprodukty kuźni)
     * zabierają sloty, ale nie dokładają przychodu.
     */
    private static double customDropIncome(ConfigurationSection customDrops, Layer layer,
                                           double forms, double hitsCap,
                                           Map<String, Integer> customSellPrices) {
        ConfigurationSection section = customDrops.getConfigurationSection(layer.customSection());
        if (section == null) {
            return 0.0;
        }
        double totalChance = 0.0;
        double weightedPrice = 0.0;
        for (String itemId : section.getKeys(false)) {
            double chance = section.getDouble(itemId);
            if (chance <= 0.0) {
                continue;
            }
            totalChance += chance;
            weightedPrice += chance * customSellPrices.getOrDefault(itemId, 0);
        }
        if (totalChance <= 0.0) {
            return 0.0;
        }
        double hits = Math.min(forms * totalChance / 100.0, hitsCap);
        return hits * weightedPrice / totalChance;
    }

    /** Okno w sekundach na trafienia w godzinie; 0 = brak okna. */
    private static double hitsPerHour(long windowSeconds) {
        return windowSeconds <= 0L ? Double.POSITIVE_INFINITY : SECONDS_PER_HOUR / windowSeconds;
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
