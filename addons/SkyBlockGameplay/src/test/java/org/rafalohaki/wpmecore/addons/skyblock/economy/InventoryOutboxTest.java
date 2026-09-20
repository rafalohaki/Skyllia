package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.junit.jupiter.api.DisplayName;
import java.util.Set;

import org.rafalohaki.wpmecore.addons.skyblock.season.CosmeticCatalog;

import org.rafalohaki.wpmecore.addons.skyblock.shop.ServerShop;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Inventories;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.rafalohaki.wpmecore.api.item.ItemPolicyMarkers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("mockbukkit")
class InventoryOutboxTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private NamespacedKey receiptKey;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("SkyBlockOutboxTest");
        player = server.addPlayer();
        receiptKey = new NamespacedKey(plugin, "inventory_receipt");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void grantReceiptMakesRetryAfterCrashIdempotentThenBecomesAPlainItem() {
        String operationId = "shop:buy:receipt-test";
        ItemStack granted = new ItemStack(Material.COBBLESTONE, 16);
        granted.editMeta(meta -> meta.getPersistentDataContainer().set(
                receiptKey, PersistentDataType.STRING, operationId));
        LedgerDao.InventoryOperation operation = grant(
                operationId, granted.serializeAsBytes());

        assertTrue(InventoryReceiptOps.applyGrant(
                player.getInventory(), operation, receiptKey));
        assertEquals(16, countMaterial(Material.COBBLESTONE));

        // Simulates PENDING recovery after the tagged playerdata was saved but
        // before SQLite advanced to DELIVERED.
        assertTrue(InventoryReceiptOps.applyGrant(
                player.getInventory(), operation, receiptKey));
        assertEquals(16, countMaterial(Material.COBBLESTONE));

        InventoryReceiptOps.stripReceipt(
                player.getInventory(), operationId, receiptKey);
        assertEquals(16, Inventories.countPlain(
                player.getInventory(), Material.COBBLESTONE));
    }

    /**
     * Wydanie towaru z mutacji pobocznej (wyrób kuźni, zakup za tokeny) biegnie
     * przy każdym ponowieniu operacji PENDING — bez paragonu na dokładanym
     * stosie każde ponowienie dokładałoby drugi pełny wyrób (SKYBLOCK-1-2/2-2).
     */
    @Test
    void taggedProductDeliveryConvergesOnceAndRetriesAreNoOps() {
        ItemStack product = new ItemStack(Material.DIAMOND, 5);

        assertTrue(InventoryReceiptOps.applyTaggedDelivery(
                player.getInventory(), "forge:craft:tagged", receiptKey, product));
        assertEquals(5, countMaterial(Material.DIAMOND));
        assertEquals(5, InventoryReceiptOps.receiptAmount(
                player.getInventory(), "forge:craft:tagged", receiptKey));

        // Ponowienie tej samej operacji (crash przed COMPLETE) nie dokłada drugi raz.
        assertTrue(InventoryReceiptOps.applyTaggedDelivery(
                player.getInventory(), "forge:craft:tagged", receiptKey, product));
        assertEquals(5, countMaterial(Material.DIAMOND));

        // Paragon jest tymczasowy: po domknięciu towar wraca do zwykłego obiegu
        // (tagowany paragonem nie jest "plain", więc nie zejdzie w sklepie).
        InventoryReceiptOps.stripReceipt(
                player.getInventory(), "forge:craft:tagged", receiptKey);
        assertEquals(5, Inventories.countPlain(player.getInventory(), Material.DIAMOND));
    }

    @Test
    void removalPlanConvergesBothPreAndPostCrashSnapshotsWithoutDoubleRemoval() {
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 10));
        LedgerDao.InventoryOperation operation = removal(
                "shop:sell:removal-test", "minecraft:cobblestone", 4, 10);

        assertEquals(InventoryReceiptOps.RemovalResult.CONVERGED,
                InventoryReceiptOps.applyRemoval(player.getInventory(), operation, null));
        assertEquals(6, Inventories.countPlain(
                player.getInventory(), Material.COBBLESTONE));

        // Retry after removal happened but COMPLETE was not acknowledged.
        assertEquals(InventoryReceiptOps.RemovalResult.CONVERGED,
                InventoryReceiptOps.applyRemoval(player.getInventory(), operation, null));
        assertEquals(6, Inventories.countPlain(
                player.getInventory(), Material.COBBLESTONE));

        // Simulate a crash restoring the last pre-removal playerdata snapshot.
        player.getInventory().clear();
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 10));
        assertEquals(InventoryReceiptOps.RemovalResult.CONVERGED,
                InventoryReceiptOps.applyRemoval(player.getInventory(), operation, null));
        assertEquals(6, Inventories.countPlain(
                player.getInventory(), Material.COBBLESTONE));
    }

    @Test
    void removalRefusesToInventMissingItemsWhenSnapshotIsBelowExpectedPostState() {
        player.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 5));
        LedgerDao.InventoryOperation operation = removal(
                "shop:sell:anomaly-test", "minecraft:cobblestone", 4, 10);

        assertEquals(InventoryReceiptOps.RemovalResult.BELOW_TARGET,
                InventoryReceiptOps.applyRemoval(player.getInventory(), operation, null));
        assertEquals(5, Inventories.countPlain(
                player.getInventory(), Material.COBBLESTONE));
    }

    @Test
    void fullInventoryDefersGrantWithoutLeakingAPartialReceipt() {
        String operationId = "shop:buy:full-test";
        ItemStack granted = new ItemStack(Material.COBBLESTONE, 16);
        granted.editMeta(meta -> meta.getPersistentDataContainer().set(
                receiptKey, PersistentDataType.STRING, operationId));
        LedgerDao.InventoryOperation operation = grant(
                operationId, granted.serializeAsBytes());
        ItemStack[] full = player.getInventory().getStorageContents();
        for (int slot = 0; slot < full.length; slot++) {
            full[slot] = new ItemStack(Material.DIRT, 64);
        }
        player.getInventory().setStorageContents(full);

        assertFalse(InventoryReceiptOps.applyGrant(
                player.getInventory(), operation, receiptKey));
        assertEquals(0, countMaterial(Material.COBBLESTONE));
    }

    @Test
    void collectibleIsNeverCountedAsServerShopStock() {
        ItemStack card = ItemStack.of(Material.PAPER);
        ItemPolicyMarkers.markCollectible(card, "VIP", "2026-S1", 1, true);
        player.getInventory().addItem(card, ItemStack.of(Material.PAPER, 3));

        assertEquals(3, Inventories.countPlain(player.getInventory(), Material.PAPER));
        assertTrue(ItemPolicyMarkers.excludedFromPlayerCommerce(card));
    }

    /**
     * Ta sama własność co dla przedmiotów zwykłych, tylko po identyfikatorze
     * customowym: zbieżność do celu bezwzględnego jest jedynym mechanizmem
     * idempotencji, więc odtworzenie po awarii nie może zabrać drugi raz.
     */
    @Test
    void customRemovalConvergesAndNeverRemovesTwice() {
        player.getInventory().addItem(customStack("skyblock:crystal/amber", 10));
        LedgerDao.InventoryOperation operation = customRemoval(
                "forge:craft:amber", "skyblock:crystal/amber", 4, 10);

        assertEquals(InventoryReceiptOps.RemovalResult.CONVERGED,
                InventoryReceiptOps.applyRemoval(player.getInventory(), operation, null));
        assertEquals(6, countCustom("skyblock:crystal/amber"));

        // Ponowienie po awarii przed potwierdzeniem COMPLETE.
        assertEquals(InventoryReceiptOps.RemovalResult.CONVERGED,
                InventoryReceiptOps.applyRemoval(player.getInventory(), operation, null));
        assertEquals(6, countCustom("skyblock:crystal/amber"));

        // Odtworzenie playerdaty sprzed usunięcia.
        player.getInventory().clear();
        player.getInventory().addItem(customStack("skyblock:crystal/amber", 10));
        assertEquals(InventoryReceiptOps.RemovalResult.CONVERGED,
                InventoryReceiptOps.applyRemoval(player.getInventory(), operation, null));
        assertEquals(6, countCustom("skyblock:crystal/amber"));
    }

    /**
     * Wszystkie surowce SkyBlocka mają materiał bazowy PAPER, więc receptura
     * kuźni prowadzi do wielu linii o tym samym {@code material_key} w jednej
     * operacji. Bez identyfikatora w kluczu głównym byłyby nie do zapisania.
     */
    @Test
    void oneOperationConvergesSeveralCustomItemsSharingTheBaseMaterial() {
        player.getInventory().addItem(
                customStack("skyblock:crystal/citrine", 20),
                customStack("skyblock:crystal/opal", 20),
                new ItemStack(Material.PAPER, 20));
        LedgerDao.InventoryOperation operation = new LedgerDao.InventoryOperation(
                "forge:craft:lotus", player.getUniqueId(),
                LedgerDao.InventoryOperationType.REMOVE, "forge:craft:lotus",
                LedgerDao.InventoryStatus.PENDING, null, null, null, null,
                List.of(new LedgerDao.InventoryLine(
                                "minecraft:paper", "skyblock:crystal/citrine", 16, 20),
                        new LedgerDao.InventoryLine(
                                "minecraft:paper", "skyblock:crystal/opal", 16, 20),
                        new LedgerDao.InventoryLine("minecraft:paper", "", 5, 20)),
                1L, 1L);

        assertEquals(InventoryReceiptOps.RemovalResult.CONVERGED,
                InventoryReceiptOps.applyRemoval(player.getInventory(), operation, null));

        assertEquals(4, countCustom("skyblock:crystal/citrine"));
        assertEquals(4, countCustom("skyblock:crystal/opal"));
        assertEquals(15, Inventories.countPlain(player.getInventory(), Material.PAPER),
                "linia vanilla liczy tylko gołe kartki, nie oznaczone kryształy");
    }

    /**
     * Ścieżka vanilla odrzuca przedmioty niehandlowe przez {@code isPlain};
     * customowa dotąd nie, więc soulbound dawał się skonsumować.
     */
    @Test
    void aSoulboundCustomItemIsNeitherCountedNorConsumed() {
        ItemStack bound = customStack("skyblock:crystal/amber", 1);
        ItemPolicyMarkers.markCollectible(bound, "VIP", "2026-S1", 1, true);
        player.getInventory().addItem(bound, customStack("skyblock:crystal/amber", 4));

        assertEquals(4, countCustom("skyblock:crystal/amber"),
                "oznaczony egzemplarz nie jest zapasem handlowym");

        LedgerDao.InventoryOperation operation = customRemoval(
                "forge:craft:bound", "skyblock:crystal/amber", 4, 4);
        assertEquals(InventoryReceiptOps.RemovalResult.CONVERGED,
                InventoryReceiptOps.applyRemoval(player.getInventory(), operation, null));

        assertEquals(0, countCustom("skyblock:crystal/amber"));
        assertTrue(player.getInventory().containsAtLeast(bound, 1),
                "przedmiot przypisany do gracza musi zostać nietknięty");
    }

    /**
     * Kosmetyka sezonowa nosi znacznik prowieniencji i jest soulbound, więc musi
     * być niesprzedawalna i niezużywalna w kuźni. Ten test woła realny
     * {@code markCollectible} — wzorzec samych znaczników sprawdza
     * {@code ShippedCosmeticCatalogContractTest} w domyślnej bramce.
     */
    @Test
    void aSeasonalCosmeticIsStampedAndExcludedFromCommerce() {
        ItemStack piece = ItemStack.of(Material.NETHERITE_CHESTPLATE);
        ItemPolicyMarkers.markCollectible(piece,
                CosmeticCatalog.tierOf(1), CosmeticCatalog.editionOf(3), 1, true);

        assertTrue(ItemPolicyMarkers.excludedFromPlayerCommerce(piece));
        assertEquals("TOP1", CosmeticCatalog.tierOf(1));
        assertEquals("S3", CosmeticCatalog.editionOf(3));

        player.getInventory().addItem(piece);
        assertEquals(0, Inventories.countPlain(
                player.getInventory(), Material.NETHERITE_CHESTPLATE),
                "oznaczony egzemplarz nie jest zapasem handlowym");
    }

    private int countCustom(String customItemId) {
        return ServerShop.countCustom(player.getInventory(), customItemId, null);
    }

    /** Znakowany surowym kluczem PDC, czyli ścieżką bez rejestru CustomItems. */
    private ItemStack customStack(String customItemId, int amount) {
        ItemStack stack = new ItemStack(Material.PAPER, amount);
        stack.editMeta(meta -> meta.getPersistentDataContainer().set(
                new NamespacedKey("customitems", "item_id"),
                PersistentDataType.STRING, customItemId));
        return stack;
    }

    private LedgerDao.InventoryOperation customRemoval(
            String id, String customItemId, int remove, int baseline) {
        return new LedgerDao.InventoryOperation(id, player.getUniqueId(),
                LedgerDao.InventoryOperationType.REMOVE, id,
                LedgerDao.InventoryStatus.PENDING, null,
                null, null, null,
                List.of(new LedgerDao.InventoryLine(
                        "minecraft:paper", customItemId, remove, baseline)),
                1L, 1L);
    }

    private int countMaterial(Material material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private LedgerDao.InventoryOperation grant(String id, byte[] payload) {
        return new LedgerDao.InventoryOperation(id, player.getUniqueId(),
                LedgerDao.InventoryOperationType.GRANT, id,
                LedgerDao.InventoryStatus.PENDING, payload,
                null, null, null, List.of(), 1L, 1L);
    }

    private LedgerDao.InventoryOperation removal(
            String id, String material, int remove, int baseline) {
        return new LedgerDao.InventoryOperation(id, player.getUniqueId(),
                LedgerDao.InventoryOperationType.REMOVE, id,
                LedgerDao.InventoryStatus.PENDING, null,
                null, null, null,
                List.of(new LedgerDao.InventoryLine(material, "", remove, baseline)),
                1L, 1L);
    }

    // --- scalone z AuxiliaryDispatchTest (hasHandlerFor to metoda InventoryOutbox) ---


    @Test
    @DisplayName("An unclaimed mutation type must hold the operation, never pass silently")
    void unclaimedTypeIsNotSilentlyAccepted() {
        Set<String> registered = Set.of("WAND_USE");

        assertTrue(InventoryOutbox.hasHandlerFor(registered, null),
                "brak mutacji to nie problem — operacja idzie dalej");
        assertTrue(InventoryOutbox.hasHandlerFor(registered, "WAND_USE"));
        assertFalse(InventoryOutbox.hasHandlerFor(registered, "FORGE_PRODUCT"),
                "typ bez zgłoszonego odbiorcy musi wstrzymać operację, a nie zniknąć");
    }
}
