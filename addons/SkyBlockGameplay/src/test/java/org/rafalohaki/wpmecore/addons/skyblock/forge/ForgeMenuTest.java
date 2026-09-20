package org.rafalohaki.wpmecore.addons.skyblock.forge;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForgeMenuTest {

    private static final ForgeConfig.ForgeRecipe RECIPE = new ForgeConfig.ForgeRecipe(
            "refined", "metals", "<white>Rafinowany Wolfram</white>",
            "skyblock:metal/refined_tungsten", null, null, 1, 25000L, null,
            List.of(new ForgeConfig.ForgeIngredient(null, Material.COAL, 8)));

    private static final ForgeConfig.ForgeCategory METALS = new ForgeConfig.ForgeCategory(
            "metals", 1, Material.IRON_INGOT, "<white>Metale</white>", List.of());

    private ForgeService forge;
    private Player player;

    @BeforeEach
    void setUp() {
        forge = mock(ForgeService.class);
        when(forge.config()).thenReturn(
                new ForgeConfig(Map.of("metals", METALS), Map.of("refined", RECIPE)));
        when(forge.countOwned(any(), any())).thenReturn(Map.of("COAL", 8));
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
    }

    private ForgeMenu menu() {
        return new ForgeMenu(mock(JavaPlugin.class), mock(MenuService.class),
                MiniMessage.miniMessage(), forge, null) {
            @Override
            ItemStack recipeIcon(Player viewer, ForgeConfig.ForgeRecipe recipe) {
                return mock(ItemStack.class);
            }

            @Override
            ItemStack categoryIcon(ForgeConfig.ForgeCategory category, boolean active) {
                return mock(ItemStack.class);
            }

            @Override
            ItemStack balanceIcon(Player viewer) {
                return mock(ItemStack.class);
            }
        };
    }

    /**
     * Ikona receptury musi być samym wyrobem.
     *
     * <p>Receptury kuźni i kantoru zwracają przedmioty z CustomItems, więc nie mają
     * ani {@code icon}, ani {@code result-material}. Wcześniejszy łańcuch fallbacków
     * kończył się na {@link Material#PAPER} i całe menu wyglądało jak stos kartek.
     * Wygląd wyrobu niesie komponent {@code item_model}, którego z samego materiału
     * nie da się odtworzyć — dlatego ikona musi pochodzić z gotowego przedmiotu.
     */
    @Test
    void recipeIconIsBuiltFromTheProductItselfNotFromAFallbackMaterial() {
        ItemStack product = mock(ItemStack.class);
        ItemStack icon = mock(ItemStack.class);
        when(product.clone()).thenReturn(icon);
        when(forge.resultStack(RECIPE)).thenReturn(product);

        ForgeMenu real = new ForgeMenu(mock(JavaPlugin.class), mock(MenuService.class),
                MiniMessage.miniMessage(), forge, null);

        assertSame(icon, real.recipeIcon(player, RECIPE),
                "ikona ma być wyrobem, inaczej gracz widzi kartkę zamiast przedmiotu");
        verify(icon).setAmount(1);
    }

    @Test
    void craftClickDelegatesToTheService() {
        menu().craftClick(RECIPE, "metals").onClick(player, ClickType.LEFT);

        verify(forge).craft(any(), any(), any());
    }

    /** Handler dostaje klikającego, nie gracza zapamiętanego przy budowie menu. */
    @Test
    void craftClickUsesTheViewerNotACapturedPlayer() {
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        when(other.isOnline()).thenReturn(true);

        menu().craftClick(RECIPE, "metals").onClick(other, ClickType.LEFT);

        ArgumentCaptor<Player> captured = ArgumentCaptor.forClass(Player.class);
        verify(forge).craft(captured.capture(), any(), any());
        assertSame(other, captured.getValue());
    }

    /** Siatka 9..44 mieści dokładnie tyle receptur, ile dopuszcza parser. */
    @Test
    void theRecipeGridMatchesTheConfigurationLimit() {
        assertEquals(9, ForgeMenu.FIRST_RECIPE_SLOT);
        assertEquals(44, ForgeMenu.LAST_RECIPE_SLOT);
        assertEquals(ForgeConfig.MAX_RECIPES_PER_CATEGORY,
                ForgeMenu.LAST_RECIPE_SLOT - ForgeMenu.FIRST_RECIPE_SLOT + 1);
    }

    /** Zakładki mieszczą się w górnym rzędzie, a przyciski w dolnym. */
    @Test
    void theFixedSlotsStayInsideASixRowMenu() {
        assertEquals(45, ForgeMenu.BALANCE_SLOT);
        assertEquals(49, ForgeMenu.CLOSE_SLOT);
        org.junit.jupiter.api.Assertions.assertTrue(
                ForgeMenu.CLOSE_SLOT < 54 && ForgeMenu.BALANCE_SLOT > ForgeMenu.LAST_RECIPE_SLOT);
    }
}
