package org.rafalohaki.wpmecore.addons.skyblock.shared;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.rafalohaki.wpmecore.api.item.ItemPolicyMarkers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("mockbukkit")
class QuantityMathTest {

    private ServerMock server;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Nested
    @DisplayName("requiredSlots tests")
    class RequiredSlotsTests {
        @Test
        void returnsZeroForZeroOrNegativeQuantity() {
            assertEquals(0, QuantityMath.requiredSlots(64, 0));
            assertEquals(0, QuantityMath.requiredSlots(64, -1));
            assertEquals(0, QuantityMath.requiredSlots(64, -100));
            assertEquals(0, QuantityMath.requiredSlots(16, 0));
            assertEquals(0, QuantityMath.requiredSlots(1, 0));
        }

        @Test
        void throwsOnZeroOrNegativeMaxStackSize() {
            assertThrows(IllegalArgumentException.class, () -> QuantityMath.requiredSlots(0, 10));
            assertThrows(IllegalArgumentException.class, () -> QuantityMath.requiredSlots(-1, 10));
        }

        @Test
        void calculatesSlotsForStandardStack64() {
            assertEquals(1, QuantityMath.requiredSlots(64, 1));
            assertEquals(1, QuantityMath.requiredSlots(64, 64));
            assertEquals(2, QuantityMath.requiredSlots(64, 65));
            assertEquals(2, QuantityMath.requiredSlots(64, 128));
            assertEquals(3, QuantityMath.requiredSlots(64, 129));
            assertEquals(36, QuantityMath.requiredSlots(64, 64 * 36));
            assertEquals(37, QuantityMath.requiredSlots(64, 64 * 36 + 1));
        }

        @Test
        void calculatesSlotsForSmallStack16() {
            assertEquals(1, QuantityMath.requiredSlots(16, 1));
            assertEquals(1, QuantityMath.requiredSlots(16, 16));
            assertEquals(2, QuantityMath.requiredSlots(16, 17));
            assertEquals(2, QuantityMath.requiredSlots(16, 32));
            assertEquals(3, QuantityMath.requiredSlots(16, 33));
        }

        @Test
        void calculatesSlotsForUnstackable1() {
            assertEquals(1, QuantityMath.requiredSlots(1, 1));
            assertEquals(2, QuantityMath.requiredSlots(1, 2));
            assertEquals(16, QuantityMath.requiredSlots(1, 16));
            assertEquals(36, QuantityMath.requiredSlots(1, 36));
        }

        @Test
        void handlesLargeQuantitiesWithoutOverflow() {
            assertEquals((int) ((Integer.MAX_VALUE + 63L) / 64), QuantityMath.requiredSlots(64, Integer.MAX_VALUE));
        }
    }

    @Nested
    @DisplayName("maxAffordable tests")
    class MaxAffordableTests {
        @Test
        void returnsZeroForZeroOrNegativePrice() {
            assertEquals(0, QuantityMath.maxAffordable(0, 1000));
            assertEquals(0, QuantityMath.maxAffordable(-10, 1000));
        }

        @Test
        void returnsZeroForZeroOrNegativeBalance() {
            assertEquals(0, QuantityMath.maxAffordable(100, 0));
            assertEquals(0, QuantityMath.maxAffordable(100, -500));
        }

        @Test
        void calculatesAffordabilityCorrectly() {
            assertEquals(10, QuantityMath.maxAffordable(10, 100));
            assertEquals(10, QuantityMath.maxAffordable(10, 109));
            assertEquals(0, QuantityMath.maxAffordable(10, 9));
            assertEquals(1, QuantityMath.maxAffordable(100, 100));
            assertEquals(1, QuantityMath.maxAffordable(100, 199));
        }

        @Test
        void handlesLargeBalanceAndCapsAtIntegerMax() {
            assertEquals(Integer.MAX_VALUE, QuantityMath.maxAffordable(1, Long.MAX_VALUE));
            assertEquals(Integer.MAX_VALUE, QuantityMath.maxAffordable(1, (long) Integer.MAX_VALUE + 100L));
            assertEquals(1, QuantityMath.maxAffordable(Long.MAX_VALUE, Long.MAX_VALUE));
            assertEquals(0, QuantityMath.maxAffordable(Long.MAX_VALUE, Long.MAX_VALUE - 1));
        }
    }

    @Nested
    @DisplayName("totalPrice tests")
    class TotalPriceTests {
        @Test
        void returnsZeroWhenPriceOrQuantityIsZero() {
            assertEquals(0L, QuantityMath.totalPrice(0L, 100));
            assertEquals(0L, QuantityMath.totalPrice(100L, 0));
            assertEquals(0L, QuantityMath.totalPrice(0L, 0));
        }

        @Test
        void calculatesCorrectTotalPrice() {
            assertEquals(500L, QuantityMath.totalPrice(100L, 5));
            assertEquals(6400L, QuantityMath.totalPrice(100L, 64));
            assertEquals(100000000000L, QuantityMath.totalPrice(1000000000L, 100));
        }

        @Test
        void throwsOnNegativeArguments() {
            assertThrows(IllegalArgumentException.class, () -> QuantityMath.totalPrice(-1L, 10));
            assertThrows(IllegalArgumentException.class, () -> QuantityMath.totalPrice(10L, -1));
        }

        @Test
        void throwsArithmeticExceptionOnOverflow() {
            assertThrows(ArithmeticException.class, () -> QuantityMath.totalPrice(Long.MAX_VALUE, 2));
            assertThrows(ArithmeticException.class, () -> QuantityMath.totalPrice(Long.MAX_VALUE / 2 + 1, 2));
        }
    }

    @Nested
    @DisplayName("clamp tests")
    class ClampTests {
        @Test
        void clampsValuesWithinBounds() {
            assertEquals(5, QuantityMath.clamp(5, 1, 10));
            assertEquals(1, QuantityMath.clamp(0, 1, 10));
            assertEquals(1, QuantityMath.clamp(-5, 1, 10));
            assertEquals(10, QuantityMath.clamp(10, 1, 10));
            assertEquals(10, QuantityMath.clamp(15, 1, 10));
        }

        @Test
        void throwsWhenMinGreaterThanMax() {
            assertThrows(IllegalArgumentException.class, () -> QuantityMath.clamp(5, 10, 1));
        }
    }

    @Nested
    @DisplayName("maxBuyable tests")
    class MaxBuyableTests {
        @Test
        void limitsByCapacityWhenAffordableIsHigher() {
            assertEquals(10, QuantityMath.maxBuyable(10, 1000L, 10L));
        }

        @Test
        void limitsByAffordabilityWhenCapacityIsHigher() {
            assertEquals(5, QuantityMath.maxBuyable(100, 50L, 10L));
        }

        @Test
        void returnsZeroWhenCapacityOrBalanceOrPriceIsZero() {
            assertEquals(0, QuantityMath.maxBuyable(0, 1000L, 10L));
            assertEquals(0, QuantityMath.maxBuyable(10, 0L, 10L));
            assertEquals(0, QuantityMath.maxBuyable(10, 1000L, 0L));
            assertEquals(0, QuantityMath.maxBuyable(-5, 1000L, 10L));
        }
    }

    @Nested
    @DisplayName("isPlain tests")
    class IsPlainTests {
        @Test
        void detectsPlainItemStacks() {
            assertTrue(QuantityMath.isPlain(new ItemStack(Material.STONE, 1)));
            assertTrue(QuantityMath.isPlain(new ItemStack(Material.STONE, 64)));
            assertTrue(QuantityMath.isPlain(new ItemStack(Material.DIAMOND, 10)));
            assertTrue(QuantityMath.isPlain(new ItemStack(Material.TOTEM_OF_UNDYING, 1)));
        }

        @Test
        void rejectsNullAndAir() {
            assertFalse(QuantityMath.isPlain(null));
            assertFalse(QuantityMath.isPlain(new ItemStack(Material.AIR)));
        }

        @Test
        void rejectsCustomMeta() {
            ItemStack named = new ItemStack(Material.STONE, 1);
            named.editMeta(meta -> meta.displayName(Component.text("Custom Stone")));
            assertFalse(QuantityMath.isPlain(named));

            ItemStack loreItem = new ItemStack(Material.STONE, 1);
            loreItem.editMeta(meta -> meta.lore(java.util.List.of(Component.text("Lore line"))));
            assertFalse(QuantityMath.isPlain(loreItem));
        }

        @Test
        void rejectsCollectibleOrSoulboundItems() {
            ItemStack collectible = new ItemStack(Material.STONE, 1);
            ItemPolicyMarkers.markCollectible(collectible, "TIER1", "EDITION1", 1, false);
            assertFalse(QuantityMath.isPlain(collectible));

            ItemStack soulbound = new ItemStack(Material.STONE, 1);
            ItemPolicyMarkers.markCollectible(soulbound, "TIER1", "EDITION1", 1, true);
            assertFalse(QuantityMath.isPlain(soulbound));
        }
    }

    @Nested
    @DisplayName("calculateCapacity & countPlain tests")
    class CapacityAndCountTests {
        @Test
        void returnsZeroForNullOrAir() {
            assertEquals(0, QuantityMath.calculateCapacity((ItemStack[]) null, Material.STONE));
            assertEquals(0, QuantityMath.calculateCapacity(new ItemStack[36], null));
            assertEquals(0, QuantityMath.calculateCapacity(new ItemStack[36], Material.AIR));
            assertEquals(0, QuantityMath.countPlain((ItemStack[]) null, Material.STONE));
            assertEquals(0, QuantityMath.countPlain(new ItemStack[36], null));
            assertEquals(0, QuantityMath.countPlain(new ItemStack[36], Material.AIR));

            assertEquals(0, QuantityMath.calculateCapacity((PlayerInventory) null, Material.STONE));
            assertEquals(0, QuantityMath.countPlain((PlayerInventory) null, Material.STONE));
        }

        @Test
        void emptyInventoryCapacityForStandard64Stack() {
            ItemStack[] contents = new ItemStack[36];
            assertEquals(36 * 64, QuantityMath.calculateCapacity(contents, Material.STONE));
            assertEquals(0, QuantityMath.countPlain(contents, Material.STONE));
        }

        @Test
        void emptyInventoryCapacityForSmall16Stack() {
            ItemStack[] contents = new ItemStack[36];
            assertEquals(36 * 16, QuantityMath.calculateCapacity(contents, Material.ENDER_PEARL));
            assertEquals(0, QuantityMath.countPlain(contents, Material.ENDER_PEARL));
        }

        @Test
        void emptyInventoryCapacityForUnstackable1() {
            ItemStack[] contents = new ItemStack[36];
            assertEquals(36, QuantityMath.calculateCapacity(contents, Material.TOTEM_OF_UNDYING));
            assertEquals(0, QuantityMath.countPlain(contents, Material.TOTEM_OF_UNDYING));
        }

        @Test
        void completelyFullInventoryCapacity() {
            ItemStack[] contents = new ItemStack[36];
            for (int i = 0; i < contents.length; i++) {
                contents[i] = new ItemStack(Material.STONE, 64);
            }
            assertEquals(0, QuantityMath.calculateCapacity(contents, Material.STONE));
            assertEquals(36 * 64, QuantityMath.countPlain(contents, Material.STONE));

            // Completely full with another material
            assertEquals(0, QuantityMath.calculateCapacity(contents, Material.DIAMOND));
            assertEquals(0, QuantityMath.countPlain(contents, Material.DIAMOND));
        }

        @Test
        void partiallyFilledInventoryCapacityWithUnfullStacks() {
            ItemStack[] contents = new ItemStack[36];
            // slot 0: 20 plain stone (space = 44)
            contents[0] = new ItemStack(Material.STONE, 20);
            // slot 1: 64 plain stone (space = 0)
            contents[1] = new ItemStack(Material.STONE, 64);
            // slot 2: 64 diamond (space = 0 for stone)
            contents[2] = new ItemStack(Material.DIAMOND, 64);
            // slot 3: custom stone (space = 0 because not plain)
            ItemStack customStone = new ItemStack(Material.STONE, 10);
            customStone.editMeta(meta -> meta.displayName(Component.text("Magic Stone")));
            contents[3] = customStone;
            // remaining 32 slots empty (space = 32 * 64 = 2048)

            // Total stone capacity = 44 + 0 + 0 + 0 + (32 * 64) = 2092
            assertEquals(44 + (32 * 64), QuantityMath.calculateCapacity(contents, Material.STONE));
            // Plain stone count = 20 + 64 = 84 (custom stone excluded)
            assertEquals(84, QuantityMath.countPlain(contents, Material.STONE));
        }

        @Test
        void partiallyFilledInventoryForEnderPearls() {
            ItemStack[] contents = new ItemStack[36];
            // slot 0: 6 plain ender pearls (space = 16 - 6 = 10)
            contents[0] = new ItemStack(Material.ENDER_PEARL, 6);
            // slot 1: 16 plain ender pearls (space = 0)
            contents[1] = new ItemStack(Material.ENDER_PEARL, 16);
            // remaining 34 empty slots (space = 34 * 16 = 544)

            assertEquals(10 + (34 * 16), QuantityMath.calculateCapacity(contents, Material.ENDER_PEARL));
            assertEquals(22, QuantityMath.countPlain(contents, Material.ENDER_PEARL));
        }

        @Test
        void capacityUsingPlayerInventory() {
            player.getInventory().clear();
            player.getInventory().setItem(0, new ItemStack(Material.STONE, 30));
            player.getInventory().setItem(1, new ItemStack(Material.DIRT, 64));

            // 34 empty slots * 64 + (64 - 30) = 2176 + 34 = 2210
            assertEquals(2210, QuantityMath.calculateCapacity(player.getInventory(), Material.STONE));
            assertEquals(30, QuantityMath.countPlain(player.getInventory(), Material.STONE));
        }
    }
}
