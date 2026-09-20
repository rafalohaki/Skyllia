package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.SkyBlockSettings;
import org.rafalohaki.wpmecore.addons.skyblock.SkyBlockSettings.TopReward;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kontrakt C1 (runda C): nagrody TOP przeniesione do config.yml
 * ({@code season.top-rewards}) z fail-closed walidacją i byte-kompatybilnym
 * fallbackiem do dzisiejszych progów.
 *
 * <p>Kontrakty zamrożone:
 * <ul>
 *   <li>{@code loadTopRewards(null)} oraz brak/pusta lista → dokładnie
 *       trzy domyślne progi (1→3 Lotosy/50k, 2→2/30k, 3→1/15k),</li>
 *   <li>każdy zły wpis → {@link IllegalArgumentException} z komunikatem
 *       zawierającym prefiks ścieżki {@code season.top-rewards},</li>
 *   <li>koordynator: statyczny, czysty pomocnik {@code rewardForRank(lista, ranga)}
 *       (powierzchnia bez MockBukkit); ranga poza listą → null = tor odmowy
 *       „brak nagrody dla rangi”.</li>
 * </ul>
 */
class SeasonTopRewardsConfigTest {

    /** Dzisiejsze progi (enum RewardTier przed rundą C) — fallback musi być byte-równy. */
    private static final List<TopReward> LEGACY_DEFAULTS = List.of(
            new TopReward(1, 3, 50_000L),
            new TopReward(2, 2, 30_000L),
            new TopReward(3, 1, 15_000L));

    private static YamlConfiguration yaml(String raw) {
        return YamlConfiguration.loadConfiguration(new StringReader(raw));
    }

    /** Ładuje nagrody z sekcji „season” podanego YAML-a (kontrakt C1). */
    private static List<TopReward> load(String raw) {
        return SkyBlockSettings.loadTopRewards(yaml(raw).getConfigurationSection("season"));
    }

    @Test
    @DisplayName("null section → dokładnie trzy domyślne progi (byte-kompatybilne)")
    void nullSectionYieldsLegacyDefaults() {
        assertEquals(LEGACY_DEFAULTS, SkyBlockSettings.loadTopRewards(null));
    }

    @Test
    @DisplayName("brak klucza i pusta lista → te same domyślne progi")
    void missingKeyAndEmptyListFallBackToDefaults() {
        String withoutKey = """
                season:
                  enabled: true
                  length-days: 30
                """;
        assertEquals(LEGACY_DEFAULTS, load(withoutKey), "brak top-rewards → defaults");

        String emptyList = """
                season:
                  enabled: true
                  top-rewards: []
                """;
        assertEquals(LEGACY_DEFAULTS, load(emptyList), "pusta lista → defaults");
    }

    @Test
    @DisplayName("konfiguracja nadpisuje progi 1:1, kolejność zachowana")
    void configuredEntriesOverrideDefaultsVerbatim() {
        String raw = """
                season:
                  top-rewards:
                    - { rank: 1, lotus: 5, coins: 99999 }
                    - { rank: 2, lotus: 0, coins: 12345 }
                    - { rank: 7, lotus: 1, coins: 0 }
                """;
        assertEquals(List.of(
                        new TopReward(1, 5, 99_999L),
                        new TopReward(2, 0, 12_345L),
                        new TopReward(7, 1, 0L)),
                load(raw));
    }

    @Test
    @DisplayName("lotus=0 i coins=0 są legalne (dolna granica inkluzywna)")
    void zeroValuesAreLegal() {
        String raw = """
                season:
                  top-rewards:
                    - { rank: 1, lotus: 0, coins: 0 }
                """;
        assertEquals(List.of(new TopReward(1, 0, 0L)), load(raw));
    }

    @Test
    @DisplayName("malejąca/kolejność nierosnąca rang jest DOZWOLONA (kontrakt: ascending not required)")
    void nonAscendingRankOrderIsAccepted() {
        String raw = """
                season:
                  top-rewards:
                    - { rank: 3, lotus: 1, coins: 15000 }
                    - { rank: 1, lotus: 3, coins: 50000 }
                """;
        assertEquals(List.of(
                        new TopReward(3, 1, 15_000L),
                        new TopReward(1, 3, 50_000L)),
                load(raw));
    }

    @Test
    @DisplayName("nieznane dodatkowe klucze wpisu są ignorowane")
    void unknownExtraKeysAreIgnored() {
        String raw = """
                season:
                  top-rewards:
                    - { rank: 1, lotus: 3, coins: 50000, note: 'od operatora', icon: DIAMOND }
                """;
        assertEquals(List.of(new TopReward(1, 3, 50_000L)), load(raw));
    }

    @Test
    @DisplayName("ujemny lotus → fail-closed IllegalArgumentException ze ścieżką")
    void negativeLotusFailsClosed() {
        String raw = """
                season:
                  top-rewards:
                    - { rank: 1, lotus: -1, coins: 50000 }
                """;
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> load(raw));
        assertTrue(failure.getMessage().contains("season.top-rewards"),
                "komunikat musi wskazywać ścieżkę: " + failure.getMessage());
    }

    @Test
    @DisplayName("ujemne monety → fail-closed IllegalArgumentException ze ścieżką")
    void negativeCoinsFailsClosed() {
        String raw = """
                season:
                  top-rewards:
                    - { rank: 1, lotus: 3, coins: -5 }
                """;
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> load(raw));
        assertTrue(failure.getMessage().contains("season.top-rewards"),
                "komunikat musi wskazywać ścieżkę: " + failure.getMessage());
    }

    @Test
    @DisplayName("rank <= 0 → fail-closed IllegalArgumentException ze ścieżką")
    void nonPositiveRankFailsClosed() {
        String raw = """
                season:
                  top-rewards:
                    - { rank: 0, lotus: 3, coins: 50000 }
                """;
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> load(raw));
        assertTrue(failure.getMessage().contains("season.top-rewards"),
                "komunikat musi wskazywać ścieżkę: " + failure.getMessage());
    }

    @Test
    @DisplayName("nieliczbowy rank → fail-closed IllegalArgumentException ze ścieżką")
    void nonIntegerRankFailsClosed() {
        String raw = """
                season:
                  top-rewards:
                    - { rank: 'pierwsze', lotus: 3, coins: 50000 }
                """;
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> load(raw));
        assertTrue(failure.getMessage().contains("season.top-rewards"),
                "komunikat musi wskazywać ścieżkę: " + failure.getMessage());
    }

    @Test
    @DisplayName("zduplikowany rank → fail-closed IllegalArgumentException ze ścieżką")
    void duplicateRankFailsClosed() {
        String raw = """
                season:
                  top-rewards:
                    - { rank: 2, lotus: 2, coins: 30000 }
                    - { rank: 2, lotus: 1, coins: 1000 }
                """;
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> load(raw));
        assertTrue(failure.getMessage().contains("season.top-rewards"),
                "komunikat musi wskazywać ścieżkę: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("zduplikowan"),
                "duplikat ma być nazwany wprost: " + failure.getMessage());
    }

    // ------------------------------------------------------------------
    // season.title-rewards: tytuł wyspy za dokładne miejsce w rankingu
    // ------------------------------------------------------------------

    private static java.util.Map<Integer, String> loadTitles(String raw) {
        return SkyBlockSettings.loadTitleRewards(yaml(raw).getConfigurationSection("season"));
    }

    @Test
    @DisplayName("brak sekcji title-rewards → żadne miejsce nie daje tytułu")
    void missingTitleRewardsMeansNoTitles() {
        assertEquals(java.util.Map.of(), SkyBlockSettings.loadTitleRewards(null));
        assertEquals(java.util.Map.of(), loadTitles("""
                season:
                  enabled: true
                """));
        assertEquals(java.util.Map.of(), loadTitles("""
                season:
                  title-rewards: {}
                """));
    }

    @Test
    @DisplayName("title-rewards mapuje miejsce na tytuł, w tym 10 i 50 z paczki")
    void titleRewardsMapRanksToTitles() {
        assertEquals(java.util.Map.of(
                        10, "Wyspiarz",
                        50, "<gray>Weteran Pustki"),
                loadTitles("""
                        season:
                          title-rewards:
                            10: 'Wyspiarz'
                            50: '<gray>Weteran Pustki'
                        """));
    }

    @Test
    @DisplayName("zły klucz, złe miejsce albo za długi tytuł → fail-closed ze ścieżką")
    void malformedTitleRewardsFailClosed() {
        for (String raw : List.of("""
                season:
                  title-rewards:
                    'dziesiąte': 'Wyspiarz'
                """, """
                season:
                  title-rewards:
                    0: 'Wyspiarz'
                """, """
                season:
                  title-rewards:
                    10: '   '
                """, """
                season:
                  title-rewards:
                    10: '%s'
                """.formatted("x".repeat(65)))) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> loadTitles(raw), raw);
            assertTrue(failure.getMessage().contains("season.title-rewards"),
                    "komunikat musi wskazywać ścieżkę: " + failure.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Koordynator: czysta powierzchnia lookupu (bez MockBukkit)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DEFAULT_TOP_REWARDS koordynatora = dzisiejsze progi (byte-equal)")
    void coordinatorDefaultsMatchLegacyTiers() {
        // P2-4: podium bez zmian + rank 4 = próg sezonu (5000 monet, 1 lotos).
        assertEquals(LEGACY_DEFAULTS,
                SkyBlockTopRewardCoordinator.DEFAULT_TOP_REWARDS.subList(0, 3));
        assertEquals(new TopReward(SkyBlockTopRewardCoordinator.THRESHOLD_RANK, 1, 5_000L),
                SkyBlockTopRewardCoordinator.DEFAULT_TOP_REWARDS.get(3));
    }

    @Test
    @DisplayName("statyczny rewardForRank: trafienie zwraca skonfigurowaną nagrodę")
    void helperResolvesConfiguredRanks() {
        List<TopReward> rewards = List.of(
                new TopReward(1, 0, 100L),
                new TopReward(5, 2, 50L));

        TopReward first = SkyBlockTopRewardCoordinator.rewardForRank(rewards, 1);
        assertNotNull(first, "ranga 1 na liście → nagroda");
        assertEquals(new TopReward(1, 0, 100L), first);

        TopReward fifth = SkyBlockTopRewardCoordinator.rewardForRank(rewards, 5);
        assertNotNull(fifth, "ranga 5 na liście → nagroda");
        assertEquals(new TopReward(5, 2, 50L), fifth);
    }

    @Test
    @DisplayName("ranga poza listą → null = tor odmowy (brak nagrody dla rangi)")
    void unmappedRankRefusesWithNull() {
        List<TopReward> rewards = List.of(new TopReward(1, 3, 50_000L));

        assertNull(SkyBlockTopRewardCoordinator.rewardForRank(rewards, 2),
                "brak rangi 2 → brak nagrody (refusal path)");
        assertNull(SkyBlockTopRewardCoordinator.rewardForRank(rewards, 0),
                "ranga 0 nigdy nie mapuje się na nagrodę");
        assertNull(SkyBlockTopRewardCoordinator.rewardForRank(rewards, -1),
                "ranga ujemna nigdy nie mapuje się na nagrodę");
        assertTrue(SkyBlockTopRewardCoordinator.rewardForRank(List.of(), 1) == null,
                "pusta lista → każda ranga bez nagrody");
    }
}
