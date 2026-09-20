package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;


import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kuźnia to jedna trwała operacja: składniki i zapłata schodzą, a należny wyrób
 * jedzie z nimi jako mutacja poboczna tego samego zapisu.
 *
 * <p>Wcześniej były to dwie operacje i sekunda między nimi, w której dług istniał
 * wyłącznie w pamięci. Kolejność była wtedy własnością bezpieczeństwa — składniki
 * musiały schodzić pierwsze, żeby gracz nie mógł wywołać awarii i wyprodukować
 * wartości z niczego. Teraz nie ma czego ustawiać w kolejności, bo nie ma dwóch
 * kroków.
 */
class ForgeServiceTest {

    private static final ForgeConfig.ForgeRecipe RECIPE = new ForgeConfig.ForgeRecipe(
            "refined", "metals", "<white>Rafinowany Wolfram</white>",
            "skyblock:metal/refined_tungsten", null, null, 1, 25000L, null,
            List.of(new ForgeConfig.ForgeIngredient("skyblock:metal/raw_tungsten", null, 4),
                    new ForgeConfig.ForgeIngredient(null, Material.COAL, 8)));

    private JavaPlugin plugin;
    private InventoryOutbox outbox;
    private Player player;
    private Map<String, Integer> owned;
    private int settled;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("forge-test"));
        outbox = mock(InventoryOutbox.class);
        player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getName()).thenReturn("Tester");
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        owned = Map.of("custom:skyblock:metal/raw_tungsten", 4, "COAL", 8);
        settled = 0;
    }

    /** Seam na ItemStacki — realna konstrukcja wymaga rejestru serwera. */
    private ForgeService service() {
        ForgeConfig config = new ForgeConfig(Map.of(), Map.of("refined", RECIPE));
        return new ForgeService(plugin, MiniMessage.miniMessage(), outbox, config, null) {
            @Override
            ItemStack resultStack(ForgeConfig.ForgeRecipe recipe) {
                ItemStack stack = mock(ItemStack.class);
                when(stack.getMaxStackSize()).thenReturn(64);
                return stack;
            }

            @Override
            Map<String, Integer> countOwned(Player viewer, ForgeConfig.ForgeRecipe recipe) {
                return owned;
            }
        };
    }

    private void removalAnswers(InventoryOutbox.Outcome outcome) {
        doAnswer(invocation -> {
            Consumer<InventoryOutbox.Outcome> callback = invocation.getArgument(7);
            callback.accept(outcome);
            return null;
        }).when(outbox).beginRemoval(any(), anyLong(), anyString(), anyString(),
                any(), any(), any(), any());
    }

    @Test
    void doesNotTouchAnythingWhenIngredientsAreMissing() {
        owned = Map.of("custom:skyblock:metal/raw_tungsten", 4, "COAL", 3);
        when(outbox.isEconomyReady(any())).thenReturn(true);

        service().craft(player, RECIPE, () -> settled++);

        verify(outbox, never()).beginRemoval(any(), anyLong(), anyString(), anyString(),
                any(), any(), any(), any());
        assertEquals(1, settled);
    }

    @Test
    void doesNotTouchAnythingWhenTheOutboxIsNotReady() {
        when(outbox.isEconomyReady(any())).thenReturn(false);

        service().craft(player, RECIPE, () -> settled++);

        verify(outbox, never()).beginRemoval(any(), anyLong(), anyString(), anyString(),
                any(), any(), any(), any());
        assertEquals(1, settled);
    }

    @Test
    void chargesTheFullCostOnTheRemovalSideOfTheSaga() {
        when(outbox.isEconomyReady(any())).thenReturn(true);

        service().craft(player, RECIPE, () -> settled++);

        verify(outbox).beginRemoval(eq(player), eq(-25000L),
                org.mockito.ArgumentMatchers.startsWith("forge:take:"),
                eq("forge_take"), any(), any(), any(), any());
    }

    @Test
    void aFailedRemovalStillSettlesTheCallback() {
        when(outbox.isEconomyReady(any())).thenReturn(true);
        removalAnswers(InventoryOutbox.Outcome.INSUFFICIENT);

        service().craft(player, RECIPE, () -> settled++);

        assertEquals(1, settled);
    }

    @Test
    void aRejectedRemovalStillSettlesTheCallback() {
        when(outbox.isEconomyReady(any())).thenReturn(true);
        removalAnswers(InventoryOutbox.Outcome.REJECTED);

        service().craft(player, RECIPE, () -> settled++);

        assertEquals(1, settled);
    }

    /**
     * Wyrób jest należnością zapisaną razem z pobraniem, a nie osobnym zleceniem.
     *
     * <p>Wcześniej kuźnia była sagą: składniki i zapłata schodziły jedną operacją, a wyrób
     * szedł drugą, planowaną z opóźnieniem. Między nimi istniała sekunda, w której dług
     * istniał wyłącznie w pamięci — awaria serwera w tym oknie zabierała graczowi zapłatę
     * i nie dawała nic w zamian. Obietnica jedzie teraz jako mutacja poboczna tej samej
     * operacji, więc przeżywa restart.
     */
    @Test
    void theProductRidesAlongTheRemovalInsteadOfASecondOperation() {
        when(outbox.isEconomyReady(any())).thenReturn(true);
        removalAnswers(InventoryOutbox.Outcome.SUCCESS);

        service().craft(player, RECIPE, () -> settled++);


        ArgumentCaptor<InventoryOutbox.AuxiliaryMutation> mutation =
                ArgumentCaptor.forClass(InventoryOutbox.AuxiliaryMutation.class);
        verify(outbox).beginRemoval(eq(player), eq(-RECIPE.costMoney()),
                org.mockito.ArgumentMatchers.startsWith("forge:take:"), eq("forge_take"),
                any(), any(), mutation.capture(), any());

        assertEquals(InventoryOutbox.AUXILIARY_FORGE_PRODUCT, mutation.getValue().type());
        assertEquals(RECIPE.id(), mutation.getValue().key(),
                "klucz musi wskazywać recepturę, bo z niej odtwarzamy wyrób po restarcie");
        assertEquals(1, settled);
    }

    @Test
    void splitsTheRecipeIntoTheTwoRemovalMaps() {
        assertEquals(Map.of(Material.COAL, 8), ForgeRequirements.plainRemovals(RECIPE));
        assertEquals(Map.of("skyblock:metal/raw_tungsten", 4),
                ForgeRequirements.customRemovals(RECIPE));
    }
}
