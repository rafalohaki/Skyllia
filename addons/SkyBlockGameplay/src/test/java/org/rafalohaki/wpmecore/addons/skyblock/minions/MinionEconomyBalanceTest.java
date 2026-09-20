package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Żaden typ minionka nie może zwracać się wyraźnie szybciej niż pozostałe.
 *
 * <p>Ten test powstał, bo minionek diamentu był policzony zupełnie inaczej niż
 * reszta: pełna ścieżka ulepszeń kosztowała 235 tys. i zwracała się w niecałe
 * pięć godzin, podczas gdy bruk potrzebował na to ponad sześciuset. Przy takim
 * rozjeździe wybór minionka przestaje być decyzją o surowcu, a staje się
 * jedynym poprawnym ruchem — i cała reszta systemu jest martwa.
 *
 * <p>Reguła: pełna inwestycja w minionka (receptura w kuźni plus wszystkie
 * ulepszenia) ma się zwracać w podobnym czasie dla każdego typu, licząc po
 * cenach skupu ze sklepu. Zmiana interwału, ceny skupu albo kosztu bez
 * przeliczenia pozostałych zatrzyma budowanie właśnie tutaj.
 *
 * <p><b>MINION-3 (2026-08-30).</b> Do tej pory {@code topIncomePerHour} liczyło
 * <i>wyłącznie surowiec podstawowy</i>. Słowa {@code bonus-drop} i
 * {@code bonus-chance} nie występowały w tym pliku, więc test mierzył nie tę
 * wielkość, której pilnował: świecił zielono (48–50 h dla każdego typu) przy
 * realnym rozrzucie zwrotów 3,1 h (bruk) ÷ 49,8 h (dąb) — szesnastokrotnym.
 * Kryształ bonusowy jest teraz częścią przychodu, a widełki i dopuszczalny
 * udział bonusu wyjechały z kodu do {@code minions.yml:balance}.
 *
 * <p><b>MINION-1.</b> Dwa dodatkowe warunki domykają pętlę „minionek napędza
 * sam siebie”: każdy {@code bonus-drop} musi mieć cenę skupu (inaczej nie da
 * się go w ogóle policzyć) i żaden nie może być paliwem minionków.
 *
 * <p><b>FUEL-1 (F27, 2026-09-01).</b> Słowo {@code fuels} nie występowało dotąd
 * w tym pliku poza listą customów do MINION-1, więc paliwo — mnożnik przychodu
 * ×1,10…×1,50 — nie wchodziło do zwrotu ani do pętli. Kosztowało to dwa realne
 * przecieki: {@code minion_coal} produkował COAL, a przez {@code compact-mappings}
 * także COAL_BLOCK, czyli DWA z czterech paliw (MINION-1 tą stroną nie patrzyła),
 * a wiadro lawy za 400 monet dawało na minionku diamentu T5 przyrost 539 964
 * monet — pętla monety→paliwo→monety o krotności 1350×. Dwa warunki niżej
 * zamykają obie drogi.
 */
class MinionEconomyBalanceTest {

    private static final Path MINIONS = Path.of("src/main/resources/minions.yml");
    private static final Path FORGE = Path.of("src/main/resources/forge.yml");
    private static final Path CONFIG = Path.of("src/main/resources/config.yml");

    /** Zapas wyłącznie na arytmetykę zmiennoprzecinkową, nie na balans. */
    private static final double FP_SLACK = 1.0e-6;

    @Test
    void everyShippedMinionPaysBackInAComparableTime() throws IOException {
        YamlConfiguration minionsYaml = load(MINIONS);
        ConfigurationSection balance = section(minionsYaml, "balance");
        double minPayback = balance.getDouble("payback-hours-min");
        double maxPayback = balance.getDouble("payback-hours-max");
        double maxBonusShare = balance.getDouble("bonus-share-max");
        assertTrue(minPayback > 0 && maxPayback > minPayback && maxBonusShare > 0,
                "minions.yml:balance ma niepoprawne wartości");

        ShopPrices shop = shopSellPrices(load(CONFIG));
        Map<String, Long> entryCosts = forgeEntryCosts();
        Set<String> fuelIds = customFuelIds(minionsYaml);
        ConfigurationSection minions = section(minionsYaml, "minions");

        Map<String, Double> paybacks = new LinkedHashMap<>();
        for (String typeId : minions.getKeys(false)) {
            ConfigurationSection type = section(minions, typeId);

            Material primary = Material.matchMaterial(type.getString("primary-item", ""));
            assertNotNull(primary, typeId + ": nieznany primary-item");
            Integer sell = shop.byMaterial().get(primary);
            assertNotNull(sell, typeId + ": surowiec " + primary
                    + " nie ma ceny skupu w sklepie, więc zwrotu nie da się policzyć");

            Long entry = entryCosts.get(typeId);
            assertNotNull(entry, typeId + ": brak receptury w kuźni");

            long investment = entry;
            double topIncomePerHour = 0.0;
            double topBonusPerHour = 0.0;
            for (int tier = 1; tier <= 5; tier++) {
                ConfigurationSection tierSection = section(type, "tiers." + tier);
                investment += tierSection.getConfigurationSection("upgrade-cost")
                        .getLong("money", 0L);
                double interval = tierSection.getDouble("interval");
                int amount = type.getInt("primary-amount", 1);
                double cyclesPerHour = 3600.0 / interval;
                topIncomePerHour = sell * amount * cyclesPerHour;

                // MINION-2/3: kryształ bonusowy jest przychodem, nie dekoracją.
                // Jeden cykl dokłada najwyżej jedną sztukę (MinionService:203).
                String bonusDrop = tierSection.getString("bonus-drop");
                topBonusPerHour = 0.0;
                if (bonusDrop != null) {
                    Integer bonusSell = shop.byCustomItem().get(bonusDrop);
                    assertNotNull(bonusSell, typeId + " T" + tier + ": bonus-drop '" + bonusDrop
                            + "' nie ma ceny skupu w sklepie, więc przychód minionka jest"
                            + " niemierzalny — dokładnie tak amber wjechał na dąb (MINION-1).");
                    assertFalse(fuelIds.contains(bonusDrop), typeId + " T" + tier + ": bonus-drop '"
                            + bonusDrop + "' jest paliwem minionków (minions.yml:fuels)."
                            + " Minionek nie może produkować własnego paliwa (MINION-1).");
                    topBonusPerHour = bonusSell
                            * (tierSection.getDouble("bonus-chance") / 100.0) * cyclesPerHour;
                }
            }

            double bonusShare = topBonusPerHour / topIncomePerHour;
            assertTrue(bonusShare <= maxBonusShare + FP_SLACK, "minionek '" + typeId
                    + "': kryształ bonusowy to " + Math.round(bonusShare * 100)
                    + " % przychodu na T5, sufit z minions.yml:balance.bonus-share-max to "
                    + Math.round(maxBonusShare * 100) + " %. Przelicz bonus-chance ze wzoru"
                    + " podanego przy tym kluczu.");

            paybacks.put(typeId, investment / (topIncomePerHour + topBonusPerHour));
        }

        assertTrue(paybacks.size() >= 2, "za mało typów, by porównywać: " + paybacks);
        for (Map.Entry<String, Double> entry : paybacks.entrySet()) {
            double hours = entry.getValue();
            assertTrue(hours >= minPayback && hours <= maxPayback,
                    "minionek '" + entry.getKey() + "' zwraca się w " + Math.round(hours)
                            + " h, poza pasmem " + (int) minPayback + "–"
                            + (int) maxPayback + " h. Wszystkie zwroty: " + rounded(paybacks));
        }
    }

    /**
     * FUEL-1: paliwo nie może pochodzić od minionka ani zwracać się szybciej,
     * niż wolno zwracać się samemu minionkowi.
     *
     * <p>Warunek drugi jest <b>jednostronny</b> — sprawdzamy wyłącznie dolny
     * próg {@code payback-hours-min}. Paliwo na tanim minionku jest czystą
     * stratą (bruk: 648 coins/h przyrostu przy koszcie 11 667 coins/h) i tak
     * ma zostać; „nie zwraca się nigdy” nie jest nadużyciem, więc górnego pasma
     * tu nie stosujemy.
     *
     * <p>Cenę paliwa liczymy <b>zaniżoną</b>: dla paliwa customowego samo
     * {@code cost-money} receptury (bez składników), dla waniliowego mniejsza
     * z cen sklepu. Zaniżenie działa na korzyść bramki — im tańsze paliwo
     * przyjmiemy, tym łatwiej złamać próg, więc test nie przepuści nic, co by
     * przeszło przy cenie prawdziwej.
     */
    @Test
    void fuelNeitherComesFromAMinionNorBeatsThePaybackFloor() throws IOException {
        YamlConfiguration minionsYaml = load(MINIONS);
        double minPayback = section(minionsYaml, "balance").getDouble("payback-hours-min");
        assertTrue(minPayback > 0, "minions.yml:balance.payback-hours-min musi być > 0");

        ShopPrices shop = shopSellPrices(load(CONFIG));
        Map<String, Long> entryCosts = forgeEntryCosts();
        Map<String, Long> forgeUnitCosts = forgeUnitCostsByResult();
        Map<Material, Integer> buyPrices = shopBuyPrices(load(CONFIG));
        ConfigurationSection fuels = section(minionsYaml, "fuels");
        ConfigurationSection minions = section(minionsYaml, "minions");
        ConfigurationSection compact = minionsYaml.getConfigurationSection("compact-mappings");

        // 1. Żaden minionek nie produkuje paliwa — ani wprost, ani kompaktorem.
        Set<Material> fuelMaterials = new LinkedHashSet<>();
        for (String fuelId : fuels.getKeys(false)) {
            String item = fuels.getString(fuelId + ".item");
            if (item != null) {
                Material material = Material.matchMaterial(item);
                assertNotNull(material, "fuels." + fuelId + ": nieznany materiał '" + item + "'");
                fuelMaterials.add(material);
            }
        }
        for (String typeId : minions.getKeys(false)) {
            Material primary = Material.matchMaterial(
                    section(minions, typeId).getString("primary-item", ""));
            assertNotNull(primary, typeId + ": nieznany primary-item");
            assertFalse(fuelMaterials.contains(primary), "minionek '" + typeId
                    + "' produkuje " + primary + ", który jest paliwem (minions.yml:fuels)."
                    + " Minionek nie może napędzać sam siebie (MINION-1/FUEL-1).");
            String compacted = compact == null ? null : compact.getString(primary.name());
            Material compactedMaterial =
                    compacted == null ? null : Material.matchMaterial(compacted);
            if (compactedMaterial != null) {
                assertFalse(fuelMaterials.contains(compactedMaterial), "minionek '" + typeId
                        + "' produkuje " + primary + ", a kompaktor zbija to w "
                        + compactedMaterial + ", który jest paliwem. Ta droga omija MINION-1"
                        + " — dokładnie tędy minionek węgla napędzał całą wyspę.");
            }
        }

        // 2. Stale podpięte paliwo nie może zejść z żadnym zwrotem pod próg.
        for (String fuelId : fuels.getKeys(false)) {
            ConfigurationSection fuel = section(fuels, fuelId);
            double multiplier = fuel.getDouble("speed-multiplier", 1.0);
            double durationHours = fuel.getLong("duration-seconds", 0L) / 3600.0;
            assertTrue(multiplier > 1.0 && durationHours > 0.0,
                    "fuels." + fuelId + ": paliwo bez efektu albo bez czasu trwania");

            long price = fuelFloorPrice(fuel, forgeUnitCosts, buyPrices, shop, fuelId);
            double costPerHour = price / durationHours;

            for (String typeId : minions.getKeys(false)) {
                ConfigurationSection type = section(minions, typeId);
                double income = topTierIncomePerHour(type, shop);
                double net = multiplier * income - costPerHour;
                if (net <= 0.0) {
                    continue;   // paliwo jest tu czystą stratą — nie ma czego badać
                }
                long investment = entryCosts.getOrDefault(typeId, 0L);
                for (int tier = 1; tier <= 5; tier++) {
                    investment += section(type, "tiers." + tier)
                            .getConfigurationSection("upgrade-cost").getLong("money", 0L);
                }
                double payback = investment / net;
                assertTrue(payback >= minPayback - FP_SLACK, "minionek '" + typeId
                        + "' ze stale podpiętym paliwem '" + fuelId + "' (cena przyjęta "
                        + price + " coins-eq/szt., " + Math.round(durationHours) + " h, ×"
                        + multiplier + ") zwraca się w " + Math.round(payback * 10) / 10.0
                        + " h, poniżej progu " + minPayback + " h z minions.yml:balance."
                        + " Paliwo jest mnożnikiem przychodu, więc podlega temu samemu"
                        + " pasmu co sam minionek — podnieś jego cenę albo obetnij"
                        + " speed-multiplier/duration-seconds.");
            }
        }
    }

    /** Przychód T5 po cenach skupu, razem z kryształem bonusowym. */
    private static double topTierIncomePerHour(ConfigurationSection type, ShopPrices shop) {
        ConfigurationSection tier = section(type, "tiers.5");
        double cyclesPerHour = 3600.0 / tier.getDouble("interval");
        Material primary = Material.matchMaterial(type.getString("primary-item", ""));
        double income = shop.byMaterial().getOrDefault(primary, 0)
                * type.getInt("primary-amount", 1) * cyclesPerHour;
        String bonusDrop = tier.getString("bonus-drop");
        if (bonusDrop != null) {
            income += shop.byCustomItem().getOrDefault(bonusDrop, 0)
                    * (tier.getDouble("bonus-chance") / 100.0) * cyclesPerHour;
        }
        return income;
    }

    /**
     * Zaniżona cena jednej sztuki paliwa. Customowe paliwo wycenia receptura
     * kuźni (samo {@code cost-money} na sztukę), waniliowe — mniejsza z cen
     * sklepu. Paliwo, którego nie widzi żadne z tych źródeł, jest błędem
     * konfiguracji: jego kosztu nie da się policzyć, więc i pętli nie da się
     * wykluczyć (tak amber wjechał na dąb — MINION-1).
     */
    private static long fuelFloorPrice(ConfigurationSection fuel, Map<String, Long> forgeUnitCosts,
                                       Map<Material, Integer> buyPrices, ShopPrices shop,
                                       String fuelId) {
        String customItem = fuel.getString("custom-item");
        if (customItem != null) {
            Long forged = forgeUnitCosts.get("custom:" + customItem);
            Integer sold = shop.byCustomItem().get(customItem);
            long price = forged == null ? Long.MAX_VALUE : forged;
            if (sold != null && sold > 0) {
                price = Math.min(price, sold);
            }
            assertTrue(price != Long.MAX_VALUE, "fuels." + fuelId + ": paliwo '" + customItem
                    + "' nie ma ani receptury w kuźni, ani ceny w sklepie — jego koszt jest"
                    + " dla bramki niewidzialny, więc pętla monety→paliwo→monety pozostaje"
                    + " nierozstrzygnięta.");
            return price;
        }
        Material material = Material.matchMaterial(fuel.getString("item", ""));
        assertNotNull(material, "fuels." + fuelId + ": nieznany materiał");
        Integer buy = buyPrices.get(material);
        Integer sell = shop.byMaterial().get(material);
        long price = Long.MAX_VALUE;
        if (buy != null && buy > 0) {
            price = buy;
        }
        if (sell != null && sell > 0) {
            price = Math.min(price, sell);
        }
        assertTrue(price != Long.MAX_VALUE, "fuels." + fuelId + ": paliwo " + material
                + " nie ma w sklepie żadnej ceny, więc jego koszt jest niepoliczalny.");
        return price;
    }

    /** Koszt monetarny jednej sztuki wyrobu kuźni — bez składników, celowo zaniżony. */
    private static Map<String, Long> forgeUnitCostsByResult() throws IOException {
        Map<String, Long> costs = new LinkedHashMap<>();
        ConfigurationSection recipes = section(load(FORGE), "recipes");
        for (String id : recipes.getKeys(false)) {
            ConfigurationSection recipe = section(recipes, id);
            int batch = Math.max(1, recipe.getInt("result-amount", 1));
            costs.put(recipe.getString("result-item", ""),
                    recipe.getLong("cost-money", 0L) / batch);
        }
        return costs;
    }

    private static Map<Material, Integer> shopBuyPrices(YamlConfiguration shopYaml) {
        Map<Material, Integer> prices = new LinkedHashMap<>();
        ConfigurationSection categories = section(shopYaml, "shop.categories");
        for (String categoryId : categories.getKeys(false)) {
            ConfigurationSection items = categories.getConfigurationSection(categoryId + ".items");
            if (items == null) {
                continue;
            }
            for (String itemId : items.getKeys(false)) {
                ConfigurationSection item = items.getConfigurationSection(itemId);
                if (item == null || item.getString("custom-item") != null) {
                    continue;
                }
                Material material = Material.matchMaterial(item.getString("material", ""));
                if (material != null) {
                    prices.put(material, item.getInt("buy", 0));
                }
            }
        }
        return prices;
    }

    /** Typ minionka z receptury: `result-item: minion:<typ>` albo pet `result-command: ecopets give %player% <typ>`. */
    private static String minionTypeOf(ConfigurationSection recipe) {
        String item = recipe.getString("result-item", "");
        if (item.startsWith("minion:")) {
            return item.substring("minion:".length());
        }
        String command = recipe.getString("result-command", "");
        if (command.startsWith("ecopets give ")) {
            String[] parts = command.trim().split("\\s+");
            return parts[parts.length - 1];
        }
        return null;
    }

    private static Map<String, Long> forgeEntryCosts() throws IOException {
        Map<String, Long> costs = new LinkedHashMap<>();
        ConfigurationSection recipes = section(load(FORGE), "recipes");
        for (String id : recipes.getKeys(false)) {
            ConfigurationSection recipe = section(recipes, id);
            // Migracja Eco: cena wejścia = receptura peta (`ecopets give %player% <typ>`) albo
            // dawna receptura przedmiotu minionka — ta sama tabela kosztów.
            String type = minionTypeOf(recipe);
            if (type != null) {
                costs.put(type, recipe.getLong("cost-money", 0L));
            }
        }
        return costs;
    }

    /** Identyfikatory CustomItems użyte jako paliwo — patrz {@code minions.yml:fuels}. */
    private static Set<String> customFuelIds(YamlConfiguration minionsYaml) {
        Set<String> ids = new LinkedHashSet<>();
        ConfigurationSection fuels = section(minionsYaml, "fuels");
        for (String id : fuels.getKeys(false)) {
            String customItem = fuels.getString(id + ".custom-item");
            if (customItem != null) {
                ids.add(customItem);
            }
        }
        return ids;
    }

    /**
     * Jedno przejście po sklepie: waniliowe pozycje trafiają do mapy po
     * materiale, kryształy (wszystkie na {@code material: PAPER}) po id customu.
     */
    private record ShopPrices(Map<Material, Integer> byMaterial, Map<String, Integer> byCustomItem) {}

    private static ShopPrices shopSellPrices(YamlConfiguration shopYaml) {
        Map<Material, Integer> byMaterial = new LinkedHashMap<>();
        Map<String, Integer> byCustomItem = new LinkedHashMap<>();
        ConfigurationSection categories = section(shopYaml, "shop.categories");
        for (String categoryId : categories.getKeys(false)) {
            ConfigurationSection items = categories.getConfigurationSection(categoryId + ".items");
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
                    continue;
                }
                Material material = Material.matchMaterial(item.getString("material", ""));
                if (material != null) {
                    byMaterial.put(material, item.getInt("sell", 0));
                }
            }
        }
        return new ShopPrices(byMaterial, byCustomItem);
    }

    private static Map<String, Long> rounded(Map<String, Double> paybacks) {
        Map<String, Long> readable = new LinkedHashMap<>();
        paybacks.forEach((id, hours) -> readable.put(id, Math.round(hours)));
        return readable;
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
