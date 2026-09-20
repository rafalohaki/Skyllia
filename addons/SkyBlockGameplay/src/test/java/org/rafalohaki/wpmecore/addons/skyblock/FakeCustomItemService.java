package org.rafalohaki.wpmecore.addons.skyblock;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItem;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Znane id: wszystkie skyblock: używane przez oneblock.yml. */
public final class FakeCustomItemService implements CustomItemService {

    public static final FakeCustomItemService SKYBLOCK = new FakeCustomItemService(List.of(
            "skyblock:crystal/citrine", "skyblock:crystal/onyx", "skyblock:crystal/opal",
            "skyblock:crystal/peridot", "skyblock:crystal/topaz",
            "skyblock:metal/raw_tungsten", "skyblock:metal/raw_umber",
            "skyblock:token/silver_lotus", "skyblock:token/gold_lotus",
            "skyblock:token/diamond_lotus", "skyblock:key/skeleton_key"));

    private final List<String> known;

    private FakeCustomItemService(List<String> known) {
        this.known = List.copyOf(known);
    }

    @Override
    public @NotNull Collection<CustomItem> all() {
        return List.of();
    }

    @Override
    public @NotNull Optional<CustomItem> byId(@NotNull String id) {
        return known.stream().filter(id::equalsIgnoreCase).findFirst()
                .map(FakeCustomItemService::stub);
    }

    /** Realny rekord API z domyślnymi wartościami — nie-null, by Optional był niepuste. */
    private static CustomItem stub(String id) {
        return new CustomItem(id, Material.PAPER, null, List.of(), false, Map.of());
    }

    @Override
    public @NotNull Optional<ItemStack> create(@NotNull String id) {
        return Optional.empty();
    }

    @Override
    public @Nullable String idOf(@Nullable ItemStack stack) {
        return null;
    }
}
