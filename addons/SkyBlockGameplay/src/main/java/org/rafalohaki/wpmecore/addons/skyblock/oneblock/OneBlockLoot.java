package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Frozen milestone loot (D7): encoded once at roll time, decoded at claim time. */
public final class OneBlockLoot {

    public record Entry(@Nullable String item, @Nullable Material material, int amount) {}

    private OneBlockLoot() {}

    public static @NotNull String encode(@NotNull List<OneBlockContent.MilestoneReward> loot) {
        StringBuilder builder = new StringBuilder();
        for (OneBlockContent.MilestoneReward reward : loot) {
            if (!builder.isEmpty()) {
                builder.append(';');
            }
            if (reward.item() != null) {
                builder.append("item:").append(reward.item()).append(':').append(reward.amount());
            } else {
                builder.append("material:").append(reward.material()).append(':').append(reward.amount());
            }
        }
        return builder.toString();
    }

    /**
     * Parses {@code item:<id>:<ilosc>} / {@code material:<NAZWA>:<ilosc>} entries
     * separated by {@code ;}. Item ids may themselves contain colons, so the
     * amount is the segment after the <em>last</em> colon and the head is split
     * with a limit of 2 — a plain {@code split(":", 3)} would cut a namespaced
     * id in half and fail on the amount.
     *
     * <p>The input is a database column, so it may be older than the current
     * content file or hand-edited. Every malformed entry is skipped rather than
     * thrown: an unparsable amount or a material that no longer exists must not
     * take down the whole claim menu, and an {@link Entry} with neither an item
     * nor a material would silently become a chest at claim time.
     */
    public static @NotNull List<Entry> decode(@NotNull String encoded) {
        List<Entry> entries = new ArrayList<>();
        for (String raw : encoded.split(";")) {
            if (raw.isBlank()) {
                continue;
            }
            int amountStart = raw.lastIndexOf(':');
            if (amountStart < 0) {
                continue;
            }
            String[] parts = raw.substring(0, amountStart).split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            int amount;
            try {
                amount = Integer.parseInt(raw.substring(amountStart + 1));
            } catch (NumberFormatException malformed) {
                continue;
            }
            if (amount <= 0) {
                continue;
            }
            if ("item".equals(parts[0])) {
                entries.add(new Entry(parts[1], null, amount));
                continue;
            }
            Material material = Material.matchMaterial(parts[1]);
            if (material == null) {
                continue;
            }
            entries.add(new Entry(null, material, amount));
        }
        return entries;
    }
}
