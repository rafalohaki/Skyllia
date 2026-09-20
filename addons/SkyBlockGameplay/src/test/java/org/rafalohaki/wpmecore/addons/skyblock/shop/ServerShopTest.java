package org.rafalohaki.wpmecore.addons.skyblock.shop;

import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerService;


import org.rafalohaki.wpmecore.addons.skyblock.shared.Inventories;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.rafalohaki.wpmecore.api.item.CustomItem;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("mockbukkit")
class ServerShopTest {

    private ServerMock server;
    private PlayerMock player;
    private MiniMessage miniMessage;
    private LedgerService ledger;
    private InventoryOutbox outbox;
    private MockCustomItemService customItemService;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        miniMessage = MiniMessage.miniMessage();
        ledger = mock(LedgerService.class);
        outbox = mock(InventoryOutbox.class);
        customItemService = new MockCustomItemService();
        when(outbox.isEconomyReady(any())).thenReturn(true);

        // Register default items in custom item mock
        customItemService.register("skyblock:crystal/citrine", Material.PAPER,
                "<gold><bold>Cytryn Anarchii</bold></gold>", List.of("<gray>Kryształ ze stoniarek</gray>"));
        customItemService.register("skyblock:crystal/aquamarine", Material.PAPER,
                "<aqua><bold>Akwamaryn Głębin</bold></aqua>", List.of("<gray>Kryształ z oceanu</gray>"));
        customItemService.register("skyblock:token/silver_lotus", Material.PAPER,
                "<gray><bold>Srebrny Lotos</bold></gray>", List.of("<gray>Żeton z zadań</gray>"));
        customItemService.register("skyblock:key/umber_key", Material.PAPER,
                "<gold><bold>Klucz Umbry</bold></gold>", List.of("<gray>Klucz do Skrzyni Podziemi</gray>"));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("ShopCatalog loads custom items and currency items correctly")
    void testShopCatalogLoadCustomItemsAndCurrencies() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("shop.categories.rare.items.citrine.material", "PAPER");
        config.set("shop.categories.rare.items.citrine.custom-item", "skyblock:crystal/citrine");
        config.set("shop.categories.rare.items.citrine.buy", 2500);
        config.set("shop.categories.rare.items.citrine.sell", 500);

        config.set("shop.categories.rare.items.umber_key.material", "PAPER");
        config.set("shop.categories.rare.items.umber_key.custom-item", "skyblock:key/umber_key");
        config.set("shop.categories.rare.items.umber_key.currency-item", "skyblock:token/silver_lotus");
        config.set("shop.categories.rare.items.umber_key.price-item-count", 5);
        config.set("shop.categories.rare.items.umber_key.buy", 0);
        config.set("shop.categories.rare.items.umber_key.sell", 0);

        ShopCatalog catalog = ShopCatalog.load(config.getConfigurationSection("shop"));
        assertEquals(2, catalog.byCustomItem().size());
        assertEquals(0, catalog.byMaterial().size());

        ShopCatalog.Product citrine = catalog.productByCustomItem("skyblock:crystal/citrine");
        assertNotNull(citrine);
        assertEquals("citrine", citrine.id());
        assertEquals("skyblock:crystal/citrine", citrine.customItem());
        assertNull(citrine.currencyItem());
        assertEquals(0, citrine.priceItemCount());
        assertEquals(2500L, citrine.buy());
        assertEquals(500L, citrine.sell());
        assertTrue(citrine.isCustom());
        assertFalse(citrine.isCurrencyItemPayment());

        ShopCatalog.Product key = catalog.productByCustomItem("skyblock:key/umber_key");
        assertNotNull(key);
        assertEquals("umber_key", key.id());
        assertEquals("skyblock:key/umber_key", key.customItem());
        assertEquals("skyblock:token/silver_lotus", key.currencyItem());
        assertEquals(5, key.priceItemCount());
        assertEquals(0L, key.buy());
        assertEquals(0L, key.sell());
        assertTrue(key.isCustom());
        assertTrue(key.isCurrencyItemPayment());
    }

    @Test
    @DisplayName("ShopCatalog rejects duplicate custom items and invalid price configurations")
    void testShopCatalogRejectsInvalidEntries() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("categories.test.items.c1.custom-item", "skyblock:crystal/citrine");
        config.set("categories.test.items.c1.buy", 100);
        config.set("categories.test.items.c2.custom-item", "skyblock:crystal/citrine"); // duplicate custom-item
        config.set("categories.test.items.c2.buy", 200);

        assertThrows(IllegalArgumentException.class, () -> ShopCatalog.load(config));

        YamlConfiguration configZeroPrice = new YamlConfiguration();
        configZeroPrice.set("categories.test.items.c1.custom-item", "skyblock:crystal/citrine");
        configZeroPrice.set("categories.test.items.c1.buy", 0);
        configZeroPrice.set("categories.test.items.c1.sell", 0); // neither buyable nor sellable nor currency

        assertThrows(IllegalArgumentException.class, () -> ShopCatalog.load(configZeroPrice));
    }

    @Test
    void serverShopExposesNoGui() {
        assertTrue(java.util.Arrays.stream(ServerShop.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .noneMatch(name -> name.equals("open") || name.equals("openCategory") || name.equals("openBuy")));
    }

    // ---- Helper Mock CustomItemService ----

    static class MockCustomItemService implements CustomItemService {
        private final Map<String, CustomItem> definitions = new HashMap<>();
        private final NamespacedKey idKey = new NamespacedKey("customitems", "item_id");

        void register(String id, Material material, String name, List<String> lore) {
            definitions.put(id.toLowerCase(), new CustomItem(id, material, name, lore, false, Map.of()));
        }

        @Override
        public @NotNull Collection<CustomItem> all() {
            return definitions.values();
        }

        @Override
        public @NotNull Optional<CustomItem> byId(@NotNull String id) {
            return Optional.ofNullable(definitions.get(id.toLowerCase()));
        }

        @Override
        public @NotNull Optional<ItemStack> create(@NotNull String id) {
            CustomItem item = definitions.get(id.toLowerCase());
            if (item == null) {
                return Optional.empty();
            }
            ItemStack stack = new ItemStack(item.material());
            stack.editMeta(meta -> {
                meta.displayName(MiniMessage.miniMessage().deserialize(item.name()));
                meta.lore(item.lore().stream().map(MiniMessage.miniMessage()::deserialize).toList());
                meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, item.id());
            });
            return Optional.of(stack);
        }

        @Override
        public String idOf(ItemStack stack) {
            if (stack == null || !stack.hasItemMeta()) {
                return null;
            }
            return stack.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        }
    }

}
