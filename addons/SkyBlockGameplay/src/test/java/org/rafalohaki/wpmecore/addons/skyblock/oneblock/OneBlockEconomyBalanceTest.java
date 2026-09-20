package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.shared.FortuneYield;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONEBLOCK-1: tempo zarobku z OneBlocka liczone z configu, nie z wyczucia.
 *
 * <p>Wysyłany rozdział {@code kres} miał w tabeli bloki surowców
 * ({@code DIAMOND_BLOCK 10}, {@code EMERALD_BLOCK 10}, {@code GOLD_BLOCK 10}
 * przy sumie wag 100) i <b>nie awansuje</b> — {@code hasNext} jest dla niego
 * fałszem, więc gracz zostaje w nim do końca sezonu. Wartość oczekiwana po
 * cenach skupu ze sklepu wynosiła <b>443 coins na jedno rozbicie</b>, czyli
 * ~1,6 mln coins/h pod autoklikerem, przy „bogatej wyspie” zdefiniowanej na
 * 25 000 coins. Nikt tego nie mierzył, bo nie było czym.
 *
 * <p><b>Model.</b> Wartość rozbicia to średnia ważona wartości waniliowego
 * dropu bloku (mapa {@link #VANILLA_DROPS}) po cenach skupu z
 * {@code config.yml}, plus kryształ bonusowy z uwzględnieniem licznika pity.
 * Materiał spoza mapy wywraca test — fail-closed: lepiej dopisać jedną linię
 * do wyceny niż wpuścić do tabeli surowiec, którego nikt nie policzył.
 *
 * <p><b>Dwa różne sufity, bo to dwa różne byty.</b> Rozdział terminalny (bez
 * następnika) jest kranem bez dna, więc liczy się jego <i>tempo</i>. Rozdział
 * skończony awansuje sam po {@code blocks-required} i nie da się w nim zostać,
 * więc liczy się jego <i>suma</i>. Oba pokrętła są w
 * {@code oneblock.yml:balance}.
 *
 * <p>Test celowo nie dotyka klasy {@code Material}: {@code isBlock()} i spółka
 * potrzebują żywego rejestru serwera, przez co {@code OneBlockContentLoaderTest}
 * siedzi w wykluczeniach MockBukkita. Tutaj wszystko jest arytmetyką na
 * napisach, więc test biegnie w domyślnej bramce — a to jedyne miejsce, w
 * którym taki strażnik ma sens.
 */
class OneBlockEconomyBalanceTest {

    private static final Path ONEBLOCK = Path.of("src/main/resources/oneblock.yml");
    private static final Path CONFIG = Path.of("src/main/resources/config.yml");

    /**
     * Co gracz naprawdę dostaje za rozbicie bloku: pozycja sklepowa i średnia
     * liczba sztuk. {@code null} jako pozycja = sklep tego nie skupuje.
     *
     * <p>Rudy surowe (raw iron/copper/gold) wyceniamy po sztabce, bo piec jest
     * darmowy i 1:1. Wielokrotności są waniliowe: lapis 4–9, redstone 4–5,
     * miedź 2–5, złoto z netheru 2–6 bryłek (9 bryłek = sztabka).
     *
     * <p>F14: rudy dostały kształt {@code apply_bonus} z waniliowej tabeli
     * łupów. {@code OneBlockService.payOutBreak} oddaje to, co zwróci
     * {@code block.getDrops(tool, player)}, więc Fortuna działa tu tak samo
     * jak przy zwykłym kopaniu — a bramka jej dotąd nie widziała. Bloki
     * surowca i kamień pozostają na {@link FortuneYield.Formula#NONE}: Fortuna
     * nie dotyka bloków, które dropią same siebie.
     */
    private record Drop(String shopMaterial, double amount, FortuneYield.Formula fortune) {
        Drop(String shopMaterial, double amount) {
            this(shopMaterial, amount, FortuneYield.Formula.NONE);
        }
    }

    private static final Map<String, Drop> VANILLA_DROPS = vanillaDrops();

    private static Map<String, Drop> vanillaDrops() {
        Map<String, Drop> drops = new HashMap<>();
        // --- bez wartości w sklepie -----------------------------------------
        for (String worthless : List.of(
                "SNOW_BLOCK", "PACKED_ICE", "BLUE_ICE", "SPRUCE_LOG", "ACACIA_LOG",
                "JUNGLE_LOG", "MANGROVE_LOG", "BAMBOO_BLOCK", "MOSS_BLOCK", "MUD",
                "SANDSTONE", "RED_SAND", "TERRACOTTA", "NETHERRACK", "BASALT", "MAGMA_BLOCK",
                "GLOWSTONE", "ANCIENT_DEBRIS", "NETHERITE_BLOCK", "END_STONE",
                "END_STONE_BRICKS", "PURPUR_BLOCK", "PURPUR_PILLAR")) {
            drops.put(worthless, new Drop(null, 0.0));
        }
        // --- bloki, które dropią same siebie albo oczywisty odpowiednik ------
        drops.put("CLAY", new Drop("CLAY_BALL", 4.0));
        drops.put("GRASS_BLOCK", new Drop("DIRT", 1.0));
        drops.put("DIRT", new Drop("DIRT", 1.0));
        drops.put("OAK_LOG", new Drop("OAK_LOG", 1.0));
        drops.put("COBBLESTONE", new Drop("COBBLESTONE", 1.0));
        drops.put("STONE", new Drop("COBBLESTONE", 1.0));
        drops.put("GRAVEL", new Drop("GRAVEL", 1.0));
        drops.put("SAND", new Drop("SAND", 1.0));
        drops.put("SOUL_SAND", new Drop("SOUL_SAND", 1.0));
        drops.put("OBSIDIAN", new Drop("OBSIDIAN", 1.0));
        // --- rudy ------------------------------------------------------------
        putOre(drops, "COAL_ORE", "COAL", 1.0);
        putOre(drops, "IRON_ORE", "IRON_INGOT", 1.0);
        putOre(drops, "COPPER_ORE", "COPPER_INGOT", 3.5);
        putOre(drops, "GOLD_ORE", "GOLD_INGOT", 1.0);
        putOre(drops, "LAPIS_ORE", "LAPIS_LAZULI", 6.5);
        putOre(drops, "REDSTONE_ORE", "REDSTONE", 4.5, FortuneYield.Formula.UNIFORM_BONUS);
        putOre(drops, "DIAMOND_ORE", "DIAMOND", 1.0);
        putOre(drops, "EMERALD_ORE", "EMERALD", 1.0);
        drops.put("NETHER_QUARTZ_ORE", new Drop("QUARTZ", 1.0, FortuneYield.Formula.ORE_DROPS));
        drops.put("NETHER_GOLD_ORE", new Drop("GOLD_INGOT", 4.0 / 9.0, FortuneYield.Formula.ORE_DROPS));
        // --- bloki surowców: dokładnie to, co wysadziło rozdział `kres` ------
        drops.put("COAL_BLOCK", new Drop("COAL", 9.0));
        drops.put("IRON_BLOCK", new Drop("IRON_INGOT", 9.0));
        drops.put("RAW_IRON_BLOCK", new Drop("IRON_INGOT", 9.0));
        drops.put("COPPER_BLOCK", new Drop("COPPER_INGOT", 9.0));
        drops.put("RAW_COPPER_BLOCK", new Drop("COPPER_INGOT", 9.0));
        drops.put("GOLD_BLOCK", new Drop("GOLD_INGOT", 9.0));
        drops.put("RAW_GOLD_BLOCK", new Drop("GOLD_INGOT", 9.0));
        drops.put("LAPIS_BLOCK", new Drop("LAPIS_LAZULI", 9.0));
        drops.put("REDSTONE_BLOCK", new Drop("REDSTONE", 9.0));
        drops.put("DIAMOND_BLOCK", new Drop("DIAMOND", 9.0));
        drops.put("EMERALD_BLOCK", new Drop("EMERALD", 9.0));
        return Map.copyOf(drops);
    }

    /** Wariant deepslate jest tą samą rudą — dopisujemy oba naraz. */
    private static void putOre(Map<String, Drop> drops, String ore, String shopMaterial, double amount) {
        putOre(drops, ore, shopMaterial, amount, FortuneYield.Formula.ORE_DROPS);
    }

    private static void putOre(Map<String, Drop> drops, String ore, String shopMaterial,
                               double amount, FortuneYield.Formula fortune) {
        drops.put(ore, new Drop(shopMaterial, amount, fortune));
        drops.put("DEEPSLATE_" + ore, new Drop(shopMaterial, amount, fortune));
    }

    @Test
    void shippedChaptersStayUnderTheConfiguredEarningCeiling() throws IOException {
        YamlConfiguration oneblock = load(ONEBLOCK);
        ConfigurationSection balance = oneblock.getConfigurationSection("balance");
        assertNotNull(balance, "oneblock.yml: brak sekcji balance");
        double maxPerBreak = balance.getDouble("terminal-max-value-per-break");
        double maxChapterTotal = balance.getDouble("finite-max-chapter-value");
        assertTrue(maxPerBreak > 0 && maxChapterTotal > 0,
                "oneblock.yml:balance ma niepoprawne wartości");

        YamlConfiguration config = load(CONFIG);
        int fortune = config.getInt("balance.fortune-level", 0);

        List<Map<?, ?>> phases = oneblock.getMapList("phases");
        assertTrue(phases.size() >= 2, "oczekiwano wielu rozdziałów, było " + phases.size());

        Map<String, Double> perBreak = perBreakValues(oneblock, config, fortune);

        // Rozdział bez następnika = ostatni na liście (OneBlockContent.hasNext).
        String terminalId = String.valueOf(phases.getLast().get("id"));
        double terminal = perBreak.get(terminalId);
        assertTrue(terminal <= maxPerBreak, "rozdział terminalny '" + terminalId
                + "' przy Fortunie " + fortune + " daje "
                + round2(terminal) + " coins oczekiwane na rozbicie (≈"
                + Math.round(terminal * 3600) + " coins/h przy 1 rozbiciu/s), sufit z"
                + " oneblock.yml:balance.terminal-max-value-per-break to " + maxPerBreak
                + ". Ten rozdział nie awansuje, więc to trwałe tempo przychodu serwera."
                + " Wszystkie rozdziały (coins/rozbicie): " + rounded(perBreak));

        for (Map<?, ?> phase : phases) {
            String id = String.valueOf(phase.get("id"));
            if (id.equals(terminalId)) {
                continue;
            }
            int blocksRequired = ((Number) phase.get("blocks-required")).intValue();
            double total = perBreak.get(id) * blocksRequired;
            assertTrue(total <= maxChapterTotal, "rozdział skończony '" + id
                    + "' przy Fortunie " + fortune + " wypłaca łącznie "
                    + Math.round(total) + " coins (" + blocksRequired + " bloków × "
                    + round2(perBreak.get(id)) + "), sufit z"
                    + " oneblock.yml:balance.finite-max-chapter-value to " + Math.round(maxChapterTotal)
                    + ". Rozdział skończony to treść jednorazowa — liczy się suma, nie tempo.");
        }
    }

    /**
     * Wartość oczekiwana rozbicia każdego rozdziału przy danym poziomie
     * Fortuny. Model wspólny z {@code OneBlockFortuneSpacePropertyTest}.
     */
    static Map<String, Double> perBreakValues(YamlConfiguration oneblock, YamlConfiguration config,
                                              int fortuneLevel) {
        Map<String, Integer> sellPrices = shopSellPrices(config);
        Map<String, Integer> customSellPrices = shopCustomSellPrices(config);
        Map<String, Double> perBreak = new LinkedHashMap<>();
        for (Map<?, ?> phase : oneblock.getMapList("phases")) {
            String id = String.valueOf(phase.get("id"));
            perBreak.put(id, expectedValuePerBreak(id, phase, sellPrices, customSellPrices, fortuneLevel));
        }
        return perBreak;
    }

    private static double expectedValuePerBreak(String chapterId, Map<?, ?> phase,
                                                Map<String, Integer> sellPrices,
                                                Map<String, Integer> customSellPrices,
                                                int fortuneLevel) {
        Object blocksRaw = phase.get("blocks");
        assertTrue(blocksRaw instanceof Map, chapterId + ": brak tabeli blocks");
        Map<?, ?> blocks = (Map<?, ?>) blocksRaw;

        double totalWeight = 0.0;
        double weighted = 0.0;
        for (Map.Entry<?, ?> entry : blocks.entrySet()) {
            String material = String.valueOf(entry.getKey());
            double weight = ((Number) entry.getValue()).doubleValue();
            Drop drop = VANILLA_DROPS.get(material);
            assertNotNull(drop, chapterId + ": materiał '" + material + "' nie ma wyceny w"
                    + " OneBlockEconomyBalanceTest.VANILLA_DROPS. Dopisz, co gracz za niego"
                    + " dostaje (albo null, jeśli sklep tego nie skupuje) — bez tego tabela"
                    + " łupów przechodzi przez bramkę niepoliczona.");
            totalWeight += weight;
            weighted += weight * value(drop, sellPrices, fortuneLevel);
        }
        assertTrue(totalWeight > 0.0, chapterId + ": suma wag = 0");

        return weighted / totalWeight + bonusValuePerBreak(chapterId, phase, customSellPrices);
    }

    private static double value(Drop drop, Map<String, Integer> sellPrices, int fortuneLevel) {
        if (drop.shopMaterial() == null) {
            return 0.0;
        }
        Integer sell = sellPrices.get(drop.shopMaterial());
        return sell == null ? 0.0
                : sell * FortuneYield.amount(drop.fortune(), drop.amount(), 1, fortuneLevel);
    }

    /**
     * Kryształ bonusowy z licznikiem pity ({@code OneBlockService.rollBonus}).
     *
     * <p>Pity wymusza drop, gdy seria pudeł osiągnie {@code pity}, więc drop
     * przychodzi najpóźniej przy rozbiciu {@code pity + 1}. Oczekiwana liczba
     * rozbić na jeden kryształ to suma prawdopodobieństw „jeszcze nie było”:
     * {@code Σ(k=0..pity) (1-p)^k}.
     */
    private static double bonusValuePerBreak(String chapterId, Map<?, ?> phase,
                                             Map<String, Integer> customSellPrices) {
        Object bonusRaw = phase.get("bonus");
        if (!(bonusRaw instanceof Map<?, ?> bonus)) {
            return 0.0;
        }
        String itemId = String.valueOf(bonus.get("item"));
        Integer sell = customSellPrices.get(itemId);
        if (sell == null || sell <= 0) {
            // Półprodukty kuźni (raw_tungsten, raw_umber) nie mają ceny skupu.
            return 0.0;
        }
        double chance = ((Number) bonus.get("chance")).doubleValue() / 100.0;
        int pity = ((Number) bonus.get("pity")).intValue();
        assertTrue(chance > 0.0 && pity > 0, chapterId + ": bonus ma niepoprawne chance/pity");

        double breaksPerDrop = 0.0;
        double miss = 1.0;
        for (int k = 0; k <= pity; k++) {
            breaksPerDrop += miss;
            miss *= (1.0 - chance);
        }
        return sell / breaksPerDrop;
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
        ConfigurationSection categories = shopYaml.getConfigurationSection("shop.categories");
        assertNotNull(categories, "config.yml: brak sekcji shop.categories");
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

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static Map<String, Double> rounded(Map<String, Double> values) {
        Map<String, Double> readable = new LinkedHashMap<>();
        values.forEach((id, value) -> readable.put(id, round2(value)));
        return readable;
    }

    private static YamlConfiguration load(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "brak wysyłanego zasobu " + path.toAbsolutePath());
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }
}
