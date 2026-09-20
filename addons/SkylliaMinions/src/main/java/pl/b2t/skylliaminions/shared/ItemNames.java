package pl.b2t.skylliaminions.shared;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.Optional;

/**
 * Nazwy przedmiotów pokazywane graczowi (lore menu, komunikaty) — MiniMessage,
 * gotowe do wklejenia w linię.
 *
 * <p>Customy biorą polską nazwę z CustomItems ({@code models.yml}); vanilla idzie
 * kluczem tłumaczenia ({@code <lang:...>}), więc klient renderuje nazwę we własnym
 * języku (PL: „Kłoda dębowa”). Surowy identyfikator ({@code OAK_LOG},
 * {@code citrine}) to ostateczny fallback, gdy nazwa jest nieosiągalna —
 * nigdy ścieżka główna. Ekran gracza „0/384 OAK_LOG” to regresja.
 */
public class ItemNames {

    private final @Nullable CustomItemService customItems;

    public ItemNames(@Nullable CustomItemService customItems) {
        this.customItems = customItems;
    }

    /** Polska nazwa customa; bez nazwy lub usługi — ostatni człon identyfikatora. */
    @NotNull public String customLabel(@NotNull String customItemId) {
        if (customItems != null) {
            // CustomItem.name jest @Nullable: map daje empty i spada do fallbacku.
            Optional<String> display = customItems.byId(customItemId)
                    .map(item -> item.name());
            if (display.isPresent()) {
                return display.get();
            }
        }
        return lastSegment(customItemId);
    }

    /** Vanilla jako tag tłumaczenia MiniMessage — klient lokalizuje sam. */
    @NotNull public String vanillaLabel(@NotNull Material material) {
        String key = translationKey(material);
        return key != null ? "<lang:" + key + ">" : material.name();
    }

    /** Seam: odczyt rejestru serwera nie istnieje w czystych testach jednostkowych. */
    @Nullable protected String translationKey(@NotNull Material material) {
        return material.translationKey();
    }

    private static @NotNull String lastSegment(@NotNull String customItemId) {
        int slash = customItemId.lastIndexOf('/');
        return slash >= 0 ? customItemId.substring(slash + 1) : customItemId;
    }
}
