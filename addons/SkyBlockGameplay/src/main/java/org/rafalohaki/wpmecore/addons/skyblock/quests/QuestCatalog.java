package org.rafalohaki.wpmecore.addons.skyblock.quests;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public record QuestCatalog(@NotNull ZoneId zone,
                    int activeCount,
                    @NotNull List<Definition> definitions) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,40}");

    public QuestCatalog {
        definitions = List.copyOf(definitions);
    }

    public static @NotNull QuestCatalog load(@NotNull ConfigurationSection root) {
        ZoneId zone;
        try {
            zone = ZoneId.of(root.getString("timezone", "Europe/Warsaw"));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid quests.timezone", failure);
        }
        ConfigurationSection definitionsSection = root.getConfigurationSection("definitions");
        if (definitionsSection == null) {
            throw new IllegalArgumentException("Missing quests.definitions");
        }
        List<Definition> definitions = new ArrayList<>();
        for (String id : definitionsSection.getKeys(false)) {
            if (!ID.matcher(id).matches()) {
                throw new IllegalArgumentException("Invalid quest id " + id);
            }
            ConfigurationSection section = definitionsSection.getConfigurationSection(id);
            if (section == null) {
                throw new IllegalArgumentException("Invalid quest section " + id);
            }
            Type type;
            try {
                type = Type.valueOf(section.getString("type", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid quest type for " + id, failure);
            }
            Mode mode;
            try {
                mode = Mode.valueOf(section.getString("mode", "ALL").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid quest mode for " + id, failure);
            }
            List<String> configuredTargets = section.getStringList("targets");
            if (configuredTargets.isEmpty()) {
                throw new IllegalArgumentException("Quest " + id + " requires targets");
            }
            Set<String> targets = new HashSet<>();
            configuredTargets.forEach(value -> targets.add(value.toUpperCase(Locale.ROOT)));
            long goal = positive(section.getLong("goal"), "quest " + id + " goal");
            long reward = positive(section.getLong("reward"), "quest " + id + " reward");
            Material icon = Material.matchMaterial(section.getString("icon", "PAPER"));
            if (icon == null || icon == Material.AIR || icon == Material.CAVE_AIR || icon == Material.VOID_AIR || icon.isLegacy()) {
                throw new IllegalArgumentException("Invalid quest icon for " + id);
            }
            List<String> rawRewardItems = section.getStringList("reward-items");
            List<RewardItem> rewardItems = new ArrayList<>();
            for (String raw : rawRewardItems) {
                if (raw != null && !raw.isBlank()) {
                    rewardItems.add(RewardItem.parse(raw));
                }
            }
            definitions.add(new Definition(id, type, targets, goal, reward, icon,
                    section.getString("name", id), section.getStringList("lore"),
                    section.getBoolean("mature-only", false), rewardItems, mode));
        }
        int activeCount = root.getInt("active-per-day", 3);
        if (definitions.size() < 3 || activeCount < 1 || activeCount > 5
                || activeCount > definitions.size()) {
            throw new IllegalArgumentException("quests.active-per-day outside catalog size");
        }
        return new QuestCatalog(zone, activeCount, definitions);
    }

    public @NotNull LocalDate today() {
        return LocalDate.now(zone);
    }

    public @NotNull String period(@NotNull LocalDate day) {
        return day.toString();
    }

    @NotNull String weekKey(@NotNull LocalDate day) {
        int week = day.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        int year = day.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR);
        return String.format(Locale.ROOT, "%04d-W%02d", year, week);
    }

    @NotNull LocalDate startOfWeek(@NotNull LocalDate day) {
        return day.with(java.time.DayOfWeek.MONDAY);
    }

    @NotNull LocalDate endOfWeek(@NotNull LocalDate day) {
        return day.with(java.time.DayOfWeek.SUNDAY);
    }

    java.util.Optional<Definition> definition(@NotNull String id) {
        for (Definition def : definitions) {
            if (def.id().equalsIgnoreCase(id)) {
                return java.util.Optional.of(def);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Dzisiejszy zestaw dla trybu wyspy: pula = definicje ALL plus przypisane do
     * trybu; rotacja deterministyczna po puli (ten sam zestaw dla wszystkich wysp
     * danego trybu w danym dniu). Pula mniejsza niz active-per-day zwracana w calosci.
     */
    public @NotNull List<Definition> active(@NotNull LocalDate day, boolean oneblockIsland) {
        Mode wanted = oneblockIsland ? Mode.ONEBLOCK : Mode.CLASSIC;
        List<Definition> pool = new ArrayList<>();
        for (Definition d : definitions) {
            if (d.mode() == Mode.ALL || d.mode() == wanted) {
                pool.add(d);
            }
        }
        if (pool.isEmpty()) {
            return List.of();
        }
        int count = Math.min(activeCount, pool.size());
        int start = Math.floorMod(day.toEpochDay(), pool.size());
        List<Definition> picked = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            picked.add(pool.get((start + offset) % pool.size()));
        }
        return List.copyOf(picked);
    }

    public @NotNull List<Definition> active(@NotNull LocalDate day) {
        int start = Math.floorMod(day.toEpochDay(), definitions.size());
        List<Definition> active = new ArrayList<>(activeCount);
        for (int offset = 0; offset < activeCount; offset++) {
            active.add(definitions.get((start + offset) % definitions.size()));
        }
        return List.copyOf(active);
    }

    private static long positive(long value, String path) {
        if (value <= 0L || value > 1_000_000_000L) {
            throw new IllegalArgumentException(path + " outside 1..1000000000");
        }
        return value;
    }

    enum Type { BREAK, PLACE, KILL, FISH, CRAFT, ONEBLOCK }

    /** pula przypisania zadania: na jakim trybie wyspy może wejść w rotację */
    enum Mode { ALL, CLASSIC, ONEBLOCK }

    record RewardItem(@NotNull String itemId, int amount) {
        RewardItem {
            if (itemId.isBlank()) {
                throw new IllegalArgumentException("Reward item ID cannot be blank");
            }
            if (amount <= 0 || amount > 6400) {
                throw new IllegalArgumentException("Reward item amount outside 1..6400");
            }
        }

        static @NotNull RewardItem parse(@NotNull String raw) {
            String trimmed = raw.trim();
            int lastColon = trimmed.lastIndexOf(':');
            if (lastColon > 0) {
                String suffix = trimmed.substring(lastColon + 1);
                try {
                    int count = Integer.parseInt(suffix);
                    if (count > 0) {
                        return new RewardItem(trimmed.substring(0, lastColon), count);
                    }
                } catch (NumberFormatException ignored) {
                    // Not an amount suffix, treat whole string as ID
                }
            }
            return new RewardItem(trimmed, 1);
        }
    }

    record Definition(@NotNull String id,
                      @NotNull Type type,
                      @NotNull Set<String> targets,
                      long goal,
                      long reward,
                      @NotNull Material icon,
                      @NotNull String name,
                      @NotNull List<String> lore,
                      boolean matureOnly,
                      @NotNull List<RewardItem> rewardItems,
                      @NotNull Mode mode) {
        Definition {
            targets = Set.copyOf(targets);
            lore = List.copyOf(lore);
            rewardItems = List.copyOf(rewardItems);
        }

        Definition(@NotNull String id,
                   @NotNull Type type,
                   @NotNull Set<String> targets,
                   long goal,
                   long reward,
                   @NotNull Material icon,
                   @NotNull String name,
                   @NotNull List<String> lore,
                   boolean matureOnly) {
            this(id, type, targets, goal, reward, icon, name, lore, matureOnly, List.of(),
                    Mode.ALL);
        }

        Definition(@NotNull String id,
                   @NotNull Type type,
                   @NotNull Set<String> targets,
                   long goal,
                   long reward,
                   @NotNull Material icon,
                   @NotNull String name,
                   @NotNull List<String> lore,
                   boolean matureOnly,
                   @NotNull List<RewardItem> rewardItems) {
            this(id, type, targets, goal, reward, icon, name, lore, matureOnly, rewardItems,
                    Mode.ALL);
        }

        boolean matches(@NotNull String target) {
            return targets.contains("ANY") || targets.contains(target.toUpperCase(Locale.ROOT));
        }
    }
}
