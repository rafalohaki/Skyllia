package org.rafalohaki.wpmecore.addons.skyblock.shared;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.api.item.CustomItem;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lore i komunikaty nie pokazują surowych identyfikatorów, gdy nazwa jest
 * osiągalna: customy polską nazwą z CustomItems, vanilla kluczem tłumaczenia
 * (klient lokalizuje sam). Ekran gracza „0/384 OAK_LOG” i „0/1 citrine” — regresja.
 */
class ItemNamesTest {

    /** Rejestr serwera nie istnieje w czystych testach — klucz waniliowy na sztywno. */
    private static final class FixedKeyNames extends ItemNames {
        FixedKeyNames(CustomItemService customItems) {
            super(customItems);
        }

        @Override
        protected String translationKey(Material material) {
            return "block.minecraft.oak_log";
        }
    }

    /** Wariant bez rejestru: klucz nieosiągalny — wypada enum name. */
    private static final class NoKeyNames extends ItemNames {
        NoKeyNames() {
            super(null);
        }

        @Override
        protected String translationKey(Material material) {
            return null;
        }
    }

    @Test
    void vanillaUsesTranslationKeyAndParsesAsTranslatable() {
        String label = new FixedKeyNames(mock(CustomItemService.class))
                .vanillaLabel(Material.OAK_LOG);

        assertEquals("<lang:block.minecraft.oak_log>", label);
        Component parsed = MiniMessage.miniMessage().deserialize(label);
        assertInstanceOf(TranslatableComponent.class, parsed);
        assertEquals("block.minecraft.oak_log", ((TranslatableComponent) parsed).key());
    }

    @Test
    void vanillaWithoutRegistryFallsBackToEnumName() {
        assertEquals("OAK_LOG", new NoKeyNames().vanillaLabel(Material.OAK_LOG));
    }

    @Test
    void customUsesPolishDisplayName() {
        CustomItemService items = mock(CustomItemService.class);
        when(items.byId(any())).thenReturn(Optional.of(new CustomItem(
                "skyblock:crystal/citrine", Material.PAPER,
                "<gold><bold>Cytryn Anarchii</bold></gold>",
                List.of(), false, Map.of())));

        assertEquals("<gold><bold>Cytryn Anarchii</bold></gold>",
                new FixedKeyNames(items).customLabel("skyblock:crystal/citrine"));
    }

    @Test
    void customWithNullDisplayNameFallsBackToLastSegment() {
        CustomItemService items = mock(CustomItemService.class);
        when(items.byId(any())).thenReturn(Optional.of(new CustomItem(
                "skyblock:metal/raw_tungsten", Material.PAPER,
                null, List.of(), false, Map.of())));

        assertEquals("raw_tungsten", new FixedKeyNames(items).customLabel("skyblock:metal/raw_tungsten"));
    }

    @Test
    void unknownCustomFallsBackToLastSegment() {
        CustomItemService items = mock(CustomItemService.class);
        when(items.byId(any())).thenReturn(Optional.empty());

        assertEquals("citrine", new FixedKeyNames(items).customLabel("skyblock:crystal/citrine"));
    }

    @Test
    void missingServiceFallsBackToLastSegment() {
        assertEquals("raw_tungsten", new FixedKeyNames(null).customLabel("skyblock:metal/raw_tungsten"));
    }
}
