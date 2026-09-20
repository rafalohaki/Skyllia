package pl.b2t.skylliaprestige;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.database.IslandCustomDataQuery;
import fr.euphyllia.skyllia.api.skyblock.Island;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Prestiż wyspy przechowywany w IslandCustomDataQuery — bez własnej tabeli,
 * dane migrują razem z wyspą i są spójne z bazą Skylli.
 */
public final class PrestigeService {

    public record Settings(int maxLevel, long baseCost, double costMultiplier,
                           @NotNull List<String> titles,
                           int minionSlotsEveryLevels,
                           double crystalLuckPercentPerLevel,
                           double sizeStepPercentPerLevel,
                           int extraMembersEveryLevels) {

        public long costFor(int level) {
            if (level <= 0) return baseCost;
            double raw = baseCost * Math.pow(costMultiplier, level);
            if (!Double.isFinite(raw) || raw >= Long.MAX_VALUE) return Long.MAX_VALUE;
            return Math.max(baseCost, Math.round(raw));
        }

        public int extraMinionSlots(int level) {
            return minionSlotsEveryLevels > 0 ? Math.max(0, level) / minionSlotsEveryLevels : 0;
        }

        public double crystalLuckMultiplier(int level) {
            return 1.0 + Math.max(0, level) * (crystalLuckPercentPerLevel / 100.0);
        }

        /** Mnożnik rozmiaru wyspy (1.0 = bazowy rozmiar z islands.toml). */
        public double sizeMultiplier(int level) {
            return 1.0 + Math.max(0, level) * (sizeStepPercentPerLevel / 100.0);
        }

        public int extraMembers(int level) {
            return extraMembersEveryLevels > 0 ? Math.max(0, level) / extraMembersEveryLevels : 0;
        }

        public @Nullable String titleFor(int level) {
            return level >= 1 && level <= titles.size() ? titles.get(level - 1) : null;
        }
    }

    public record State(int level, long spentMinor, long nextCost, boolean maxed,
                        @Nullable String title) {
    }

    public enum Status {PURCHASED, ALREADY_PURCHASED, NO_FUNDS, MAX_LEVEL}

    public record PurchaseResult(@NotNull Status status, int level, long cost) {
    }

    private final Settings settings;
    private final NamespacedKey key;
    private final ConcurrentMap<UUID, Integer> levels = new ConcurrentHashMap<>();

    public PrestigeService(@NotNull SkylliaPrestige plugin, @NotNull Settings settings) {
        this.settings = settings;
        this.key = new NamespacedKey(plugin, "prestige");
    }

    public @NotNull Settings settings() {
        return settings;
    }

    /** Poziom prestiżu wyspy — custom data Skylli (synchroniczny odczyt z cache'u Skylli). */
    public int levelOf(@NotNull Island island) {
        Integer cached = levels.get(island.getId());
        if (cached != null) return cached;
        int level = store().getOrDefault(key, island, "level", PersistentDataType.INTEGER, 0);
        levels.put(island.getId(), level);
        return level;
    }

    /** Spędzone monety — tylko do statystyk/menu. */
    public long spentOf(@NotNull Island island) {
        return store().getOrDefault(key, island, "spent", PersistentDataType.LONG, 0L);
    }

    public @NotNull State state(@NotNull Island island) {
        int level = levelOf(island);
        long spent = spentOf(island);
        boolean maxed = level >= settings.maxLevel();
        return new State(level, spent, maxed ? 0L : settings.costFor(level), maxed,
                settings.titleFor(level));
    }

    /**
     * Zapisuje poziom strażony poziomem wyjściowym — powtórka jest no-opem.
     * Wywołuj dopiero po udanym debecie z banku wyspy.
     */
    public boolean recordLevel(@NotNull Island island, int fromLevel, int toLevel, long costMinor) {
        if (levelOf(island) != fromLevel) return false;
        if (!store().set(key, island, "level", PersistentDataType.INTEGER, toLevel)) return false;
        store().set(key, island, "spent", PersistentDataType.LONG, spentOf(island) + costMinor);
        levels.put(island.getId(), toLevel);
        applyPerks(island, toLevel);
        return true;
    }

    /**
     * Aplikuje mechaniczne perki poziomu na samą wyspę — rozmiar i limit
     * członków lecą przez API wyspy, więc przeżywają restart. Rozmiar rośnie
     * procentowo od aktualnego (kumulatywnie per poziom), członkowie
     * doliczani są tylko przy przekroczeniu progu {@code extraMembersEveryLevels}.
     */
    private void applyPerks(@NotNull Island island, int level) {
        try {
            if (settings.sizeStepPercentPerLevel > 0) {
                island.setSize(island.getSize() * (1.0 + settings.sizeStepPercentPerLevel / 100.0));
            }
        } catch (Exception ignored) {
            // rozmiar to bonus — brak wsparcia nie może wysadzić zakupu
        }
        try {
            int step = settings.extraMembersEveryLevels;
            if (step > 0 && level % step == 0 && level > 0) {
                island.setMaxMembers(island.getMaxMembers() + 1);
            }
        } catch (Exception ignored) {
        }
    }

    public int minionSlotsFor(@NotNull UUID islandId) {
        Integer level = levels.get(islandId);
        return settings.extraMinionSlots(level == null ? 0 : level);
    }

    public double crystalLuckMultiplierFor(@NotNull UUID islandId) {
        Integer level = levels.get(islandId);
        return settings.crystalLuckMultiplier(level == null ? 0 : level);
    }

    private IslandCustomDataQuery store() {
        return SkylliaAPI.getIslandCustomDataQuery();
    }
}
