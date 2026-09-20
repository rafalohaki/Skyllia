package org.rafalohaki.wpmecore.addons.skyblock.oneblock;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Immutable content loaded from oneblock.yml: settings plus an ordered list of
 * chapters. Pure model — no Bukkit IO, no plugin state.
 */
public final class OneBlockContent {

    public record Settings(int checkpointEvery, int mobWarningTicks, boolean broadcastPhases) {
        /** Kontrakt testów/fixtures: brak flagi = broadcast włączony (domyślna polityka serwera). */
        public Settings(int checkpointEvery, int mobWarningTicks) {
            this(checkpointEvery, mobWarningTicks, true);
        }
    }

    public record WeightedMaterial(@NotNull Material material, double weight) {}

    public record WeightedEntity(@NotNull EntityType entityType, double weight) {}

    /** Custom resource rolled next to the platform block (D2). */
    public record Bonus(@NotNull String itemId, double chancePercent, int pity) {}

    public record Mobs(double spawnChancePercent, @NotNull List<WeightedEntity> table) {}

    /** Exactly one of {@code item}/{@code material} is non-null. */
    public record MilestoneReward(@Nullable String item, @Nullable Material material,
                                  int amount, double weight) {}

    public record Milestone(@NotNull String name, int rolls,
                            @NotNull List<MilestoneReward> rewards) {}

    public record Chapter(@NotNull String id, @NotNull String name, @NotNull String subtitle,
                          @NotNull Material icon, int blocksRequired,
                          @NotNull List<WeightedMaterial> blocks,
                          @NotNull Map<Integer, Material> guaranteed,
                          @NotNull Bonus bonus, @NotNull Mobs mobs,
                          @NotNull Map<Integer, Milestone> milestones,
                          @NotNull String biome) {
        /** Rozdział bez własnego biomu (stałe tło nieba jak dotąd). */
        public Chapter(@NotNull String id, @NotNull String name, @NotNull String subtitle,
                       @NotNull Material icon, int blocksRequired,
                       @NotNull List<WeightedMaterial> blocks,
                       @NotNull Map<Integer, Material> guaranteed,
                       @NotNull Bonus bonus, @NotNull Mobs mobs,
                       @NotNull Map<Integer, Milestone> milestones) {
            this(id, name, subtitle, icon, blocksRequired, blocks, guaranteed, bonus, mobs,
                    milestones, "");
        }
    }

    private final Settings settings;
    private final List<Chapter> chapters;
    private final Map<String, Chapter> byId;
    private final List<String> phaseIds;

    public OneBlockContent(@NotNull Settings settings, @NotNull List<Chapter> chapters) {
        this.settings = settings;
        this.chapters = List.copyOf(chapters);
        Map<String, Chapter> ids = new HashMap<>();
        List<String> ordered = new ArrayList<>();
        for (Chapter chapter : this.chapters) {
            ids.put(chapter.id(), chapter);
            ordered.add(chapter.id());
        }
        this.byId = ids;
        this.phaseIds = List.copyOf(ordered);
    }

    public @NotNull Settings settings() {
        return settings;
    }

    public @NotNull List<Chapter> chapters() {
        return chapters;
    }

    /** Ordered chapter ids — the migration backfill maps legacy phase 1..N onto these. */
    public @NotNull List<String> phaseIds() {
        return phaseIds;
    }

    /**
     * ONEBLOCK-4: nieznane albo puste id klampuje na <b>pierwszy</b> rozdział.
     *
     * <p>Poprzednia wersja klampowała na ostatni („mirroring legacy getPhase()”),
     * czyli na rozdział <i>najbogatszy</i> i bez następnika. Wystarczyła zmiana
     * albo literówka w {@code id:} przy sezonowym rebalansie {@code oneblock.yml},
     * żeby po restarcie każda wyspa w tej fazie wpadła do endgame'u — awans
     * masowy, nieodwracalny bez ręcznego UPDATE. Kierunek jest teraz fail-closed
     * i zgodny z {@code OneBlockDao.legacyPhaseNumber}, który klampuje na 1.
     */
    public @NotNull Chapter chapter(@Nullable String phaseId) {
        Chapter chapter = phaseId == null ? null : byId.get(phaseId);
        return chapter != null ? chapter : chapters.getFirst();
    }

    public boolean hasNext(@Nullable String phaseId) {
        int index = phaseIds.indexOf(phaseId);
        return index >= 0 && index < chapters.size() - 1;
    }

    /** Nieznane id → pierwszy rozdział (ONEBLOCK-4); ostatni znany → on sam. */
    public @NotNull String nextChapterId(@Nullable String phaseId) {
        int index = phaseIds.indexOf(phaseId);
        if (index < 0) {
            return chapters.getFirst().id();
        }
        return phaseIds.get(Math.min(index + 1, phaseIds.size() - 1));
    }

    /** ONEBLOCK-4: {@code loadAll} czyta phase_id z bazy bez walidacji — tu jest. */
    public boolean isKnownPhase(@Nullable String phaseId) {
        return phaseId != null && byId.containsKey(phaseId);
    }

    public static @NotNull Material pickBlock(@NotNull Chapter chapter, @NotNull Random random) {
        return chapter.blocks().get(weightedIndex(chapter.blocks().stream()
                .mapToDouble(WeightedMaterial::weight).toArray(), random))
                .material();
    }

    public static @Nullable EntityType pickMob(@NotNull Mobs mobs, @NotNull Random random) {
        if (mobs.table().isEmpty()) {
            return null;
        }
        return mobs.table().get(weightedIndex(mobs.table().stream()
                .mapToDouble(WeightedEntity::weight).toArray(), random)).entityType();
    }

    public static @NotNull MilestoneReward pickReward(@NotNull Milestone milestone,
                                                      @NotNull Random random) {
        return milestone.rewards().get(weightedIndex(milestone.rewards().stream()
                .mapToDouble(MilestoneReward::weight).toArray(), random));
    }

    /** Rolls with replacement (spec §4): the same entry may win twice, amounts sum. */
    public static @NotNull List<MilestoneReward> rollMilestoneLoot(@NotNull Milestone milestone,
                                                                   @NotNull Random random) {
        LinkedHashMap<String, MilestoneReward> aggregated = new LinkedHashMap<>();
        for (int roll = 0; roll < milestone.rolls(); roll++) {
            MilestoneReward reward = pickReward(milestone, random);
            String key = reward.item() != null ? "i:" + reward.item() : "m:" + reward.material();
            MilestoneReward existing = aggregated.get(key);
            if (existing == null) {
                aggregated.put(key, reward);
            } else {
                aggregated.put(key, new MilestoneReward(existing.item(), existing.material(),
                        existing.amount() + reward.amount(), existing.weight()));
            }
        }
        return List.copyOf(aggregated.values());
    }

    private static int weightedIndex(double[] weights, @NotNull Random random) {
        double total = 0.0;
        for (double weight : weights) {
            total += weight;
        }
        double roll = random.nextDouble() * total;
        double cumulative = 0.0;
        for (int index = 0; index < weights.length; index++) {
            cumulative += weights[index];
            if (roll < cumulative) {
                return index;
            }
        }
        return weights.length - 1;
    }
}
