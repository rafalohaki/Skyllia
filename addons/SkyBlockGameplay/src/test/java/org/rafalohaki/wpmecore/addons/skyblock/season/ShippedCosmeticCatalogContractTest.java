package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.api.item.CustomItem;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wysyłany {@code cosmetics.yml} musi opisywać przedmioty, które naprawdę
 * istnieją, i pokrywać wszystkie kolekcje z katalogu modeli.
 *
 * <p>Przed tą pracą 40 części zbroi było zdefiniowanych w {@code models.yml},
 * wysłanych w paczce klienta i widocznych w {@code /modele}, ale nieosiągalnych
 * żadną drogą. Te testy pilnują, żeby konfiguracja nie rozjechała się z modelami
 * z powrotem.
 */
class ShippedCosmeticCatalogContractTest {

    private static final Path COSMETICS = Path.of("src/main/resources/cosmetics.yml");
    private static final Path MODELS = Path.of("../customitems/src/main/resources/models.yml");

    private record PresentItems(Set<String> ids) implements CustomItemService {
        @Override
        public @NotNull Collection<CustomItem> all() {
            return List.of();
        }

        @Override
        public @NotNull Optional<CustomItem> byId(@NotNull String id) {
            return ids.contains(id)
                    ? Optional.of(new CustomItem(id, Material.PAPER, null, List.of(), false,
                            Map.of()))
                    : Optional.empty();
        }

        @Override
        public @NotNull Optional<ItemStack> create(@NotNull String id) {
            return Optional.empty();
        }

        @Override
        public String idOf(ItemStack stack) {
            return null;
        }
    }

    private static YamlConfiguration load(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "brak wysyłanego zasobu " + path.toAbsolutePath());
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static YamlConfiguration models() throws IOException {
        return load(MODELS);
    }

    private static Set<String> shippedCustomItemIds() throws IOException {
        ConfigurationSection items = models().getConfigurationSection("items");
        assertNotNull(items, "models.yml nie ma sekcji items");
        return items.getKeys(false);
    }

    private static CosmeticCatalog shipped() throws IOException {
        return CosmeticCatalog.parse(load(COSMETICS),
                new PresentItems(shippedCustomItemIds()));
    }

    @Test
    void shippedCosmeticsParseAgainstShippedCustomItems() throws IOException {
        CosmeticCatalog catalog = shipped();

        assertTrue(catalog.enabled());
        assertFalse(catalog.collections().isEmpty());
    }

    /** Kolekcja w modelach bez wpisu tutaj byłaby znowu nieosiągalna. */
    @Test
    void everyArmourGroupInTheModelCatalogueHasACollection() throws IOException {
        ConfigurationSection groups = models().getConfigurationSection("catalog.groups");
        assertNotNull(groups, "models.yml nie ma katalogu grup");
        List<String> armourGroups = groups.getKeys(false).stream()
                .filter(id -> !id.startsWith("skyblock_"))
                .toList();

        CosmeticCatalog catalog = shipped();
        for (String group : armourGroups) {
            assertTrue(catalog.collections().containsKey(group),
                    "grupa '" + group + "' z models.yml nie ma kolekcji w cosmetics.yml");
        }
        assertEquals(armourGroups.size(), catalog.collections().size());
    }

    @Test
    void everyCollectionMatchesItsGroupPieceForPiece() throws IOException {
        ConfigurationSection groups = models().getConfigurationSection("catalog.groups");
        assertNotNull(groups);
        CosmeticCatalog catalog = shipped();

        for (CosmeticCatalog.Collection collection : catalog.collections().values()) {
            assertEquals(groups.getStringList(collection.id() + ".items"), collection.pieces(),
                    "kolekcja '" + collection.id() + "' rozjechała się z grupą w models.yml");
        }
    }

    /** Sezon bez kolekcji nic nie wydaje, więc każdy ma ją mieć. */
    @Test
    void everyCollectionIsReachableThroughSomeSeason() throws IOException {
        CosmeticCatalog catalog = shipped();

        for (String id : catalog.collections().keySet()) {
            assertTrue(catalog.seasonCollections().containsValue(id),
                    "kolekcja '" + id + "' nie jest przypisana do żadnego sezonu");
        }
    }

    @Test
    void noTwoSeasonsShareACollection() throws IOException {
        CosmeticCatalog catalog = shipped();

        assertEquals(catalog.seasonCollections().size(),
                Set.copyOf(catalog.seasonCollections().values()).size(),
                "kolekcja przypisana do dwóch sezonów przestaje być limitowana");
    }

    @Test
    void firstPlaceGetsAFullSetAndLowerPlacesGetAPrefixOfIt() throws IOException {
        CosmeticCatalog catalog = shipped();
        int season = catalog.seasonCollections().keySet().iterator().next();
        CosmeticCatalog.Collection collection = catalog.forSeason(season).orElseThrow();

        assertEquals(collection.pieces(), catalog.piecesFor(season, 1));
        assertTrue(collection.pieces().containsAll(catalog.piecesFor(season, 2)));
        assertTrue(catalog.piecesFor(season, 2).containsAll(catalog.piecesFor(season, 3)));
        // P2-4: rank 4 = próg sezonu — jedna część kolekcji; dalej (5+) nic.
        assertEquals(1, catalog.piecesFor(season, 4).size(), "próg sezonu daje jedną część kolekcji");
        assertTrue(collection.pieces().containsAll(catalog.piecesFor(season, 4)));
        assertTrue(catalog.piecesFor(season, 5).isEmpty(), "poza progiem i podium nie ma kosmetyki");
    }

    /**
     * Znaczniki trafiają do {@code ItemPolicyMarkers}, które waliduje je wzorcem
     * {@code [A-Z0-9][A-Z0-9_.-]{0,31}} i rzuca przy niezgodności. Tu sprawdzamy
     * sam kształt — realne oznaczenie przedmiotu wymaga rejestru serwera, więc
     * biegnie w {@code InventoryOutboxTest} pod profilem mockbukkit.
     */
    @Test
    void theProvenanceMarkersMatchThePolicyTokenPattern() throws IOException {
        CosmeticCatalog catalog = shipped();
        java.util.regex.Pattern token =
                java.util.regex.Pattern.compile("[A-Z0-9][A-Z0-9_.-]{0,31}");

        for (int season : catalog.seasonCollections().keySet()) {
            String edition = CosmeticCatalog.editionOf(season);
            assertTrue(token.matcher(edition).matches(), edition);
        }
        for (int rank : catalog.topRewards().keySet()) {
            String tier = CosmeticCatalog.tierOf(rank);
            assertTrue(token.matcher(tier).matches(), tier);
        }
    }
}
