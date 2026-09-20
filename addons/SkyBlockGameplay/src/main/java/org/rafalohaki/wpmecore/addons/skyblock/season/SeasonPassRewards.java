package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * M2.5: katalog nagród przepustki (pilot 28 poziomów, spec §5).
 *
 * <p>Zasady z roadmapy: tor darmowy daje umiarkowane monety i materiały,
 * tor premium nie konkuruje ekonomią (brak monet) — lotosy i kryształy
 * jako waluty kosmetyczne. Nagrody są deklaratywne: menu je wyświetla,
 * {@code SeasonPassMenu} dostarcza przez outbox/ledger.
 */
public final class SeasonPassRewards {

    /** Jedna pozycja nagrody: przedmiot vanilla lub custom (id CustomItems). */
    public record Reward(@NotNull String name, @NotNull Material material,
                         @Nullable String customItemId, int amount) {

        static Reward vanilla(@NotNull String name, @NotNull Material material, int amount) {
            return new Reward(name, material, null, amount);
        }

        static Reward custom(@NotNull String name, @NotNull Material fallback,
                             @NotNull String customItemId, int amount) {
            return new Reward(name, fallback, customItemId, amount);
        }
    }

    private SeasonPassRewards() { }

    /** Monety za poziom (tor darmowy): 200 + 40×poziom. */
    /** P2-1: powtarzalna nagroda za każdy poziom bonusowy (co 500 pkt po L28). */
    public static final long BONUS_FREE_MONEY = 1_500L;

    public static long freeMoney(int level) {
        if (SeasonPassService.isBonusLevel(level)) {
            return BONUS_FREE_MONEY;
        }
        return 200L + 40L * level;
    }

    /** Darmowa nagroda przedmiotowa co kilka poziomów; pusta dla pozostałych. */
    public static @NotNull Optional<Reward> freeItem(int level) {
        if (SeasonPassService.isBonusLevel(level)) {
            return Optional.of(Reward.custom("Srebrny Lotos", Material.PAPER,
                    "skyblock:token/silver_lotus", 1));
        }
        return switch (level) {
            case 5 -> Optional.of(Reward.vanilla("Żelazo x32", Material.IRON_INGOT, 32));
            case 10 -> Optional.of(Reward.vanilla("Złoto x24", Material.GOLD_INGOT, 24));
            case 15 -> Optional.of(Reward.vanilla("Diamenty x6", Material.DIAMOND, 6));
            case 20 -> Optional.of(Reward.vanilla("Perły x8", Material.ENDER_PEARL, 8));
            case 25 -> Optional.of(Reward.vanilla("Obsydian x16", Material.OBSIDIAN, 16));
            case 28 -> Optional.of(Reward.vanilla("Szmaragdy x32", Material.EMERALD, 32));
            default -> Optional.empty();
        };
    }

    /**
     * Nagrody premium za poziom: Srebrny Lotos co poziom, kryształ i Złoty
     * Lotos na czterech kamieniach milowych.
     *
     * <p><b>F27 (rekomendacja F24, wykonana).</b> Do 2026-09-01 tor premium
     * dawał <b>2 Złote Lotosy na KAŻDYM z 28 poziomów</b>, czyli 56 sztuk za
     * sezon, przy koszcie aktywacji 6 sztuk
     * ({@code config.yml: season.pass.premium-lotus-cost}). Bilans +50 to
     * osiem kolejnych sezonów premium za darmo — i pętla zyskowna wprost
     * w walucie kantoru: 6 → 56 to krotność <b>9,33× na sezon</b>, powtarzalna,
     * bo nagrody premium odbiera się wstecznie ({@code isEligible} patrzy
     * wyłącznie na {@code level <= playerLevel && premium}). Kuźnia liczy
     * Złoty Lotos po 1 225 836 coins-eq, więc pełny tor rozdawał 68,6 mln
     * coins-eq = 2 746 h gry za 294 h.
     *
     * <p>Po zmianie tor zwraca <b>4</b> Złote Lotosy, czyli ⅔ ceny: każdy
     * kolejny sezon wymaga dołożenia 2 z 8 tygodniowych kamieni milowych,
     * a tygodniowe wyzwanie 15 zadań zostaje adresatem. Miejsce nagrody „co
     * poziom” zajmuje <b>Srebrny Lotos ×2</b> — żeton z dwoma sprawdzonymi
     * zlewami ({@code forge.yml: gold_lotus_exchange} zjada 4 sztuki,
     * {@code shop.crystals.umber_key} pięć) i NIEBĘDĄCY walutą aktywacji, więc
     * bez zapętlenia. 56 Srebrnych za sezon to mniej niż połowa tego, co dają
     * same zadania dzienne (3/dobę × 56 = do 168).
     *
     * <p><b>Kolejność pozycji jest częścią klucza idempotencji</b>
     * ({@code season-pass:<sezon>:<tor>:L<poziom>:<uuid>:item<i>}), dlatego
     * Złoty Lotos dochodzi na KOŃCU listy: indeks 0 i 1 zachowują dotychczasowe
     * znaczenie, a nowy indeks 2 pojawia się tylko na kamieniach milowych.
     * Podmiana indeksu 0 (Złoty → Srebrny) jest bezpieczna wyłącznie na czystym
     * stanie claimów — czyli zaraz po {@code /sezon zamknij --confirm} albo
     * przed startem sezonu.
     */
    public static @NotNull List<Reward> premiumItems(int level) {
        List<Reward> rewards = new ArrayList<>();
        if (SeasonPassService.isBonusLevel(level)) {
            rewards.add(Reward.custom("Srebrny Lotos x2", Material.PAPER,
                    "skyblock:token/silver_lotus", 2));
            rewards.add(Reward.custom("Klucz Wolframowy", Material.PAPER,
                    "skyblock:key/tungsten_key", 1));
            return List.copyOf(rewards);
        }
        // Materiał to fallback na wypadek braku CustomItems; wszystkie żetony
        // SkyBlocka siedzą naprawdę na PAPER.
        rewards.add(Reward.custom("Srebrny Lotos x2", Material.PAPER,
                "skyblock:token/silver_lotus", 2));
        switch (level) {
            case 7 -> rewards.add(Reward.custom("Akwamaryn x2", Material.PRISMARINE_CRYSTALS,
                    "skyblock:crystal/aquamarine", 2));
            case 14 -> rewards.add(Reward.custom("Cytryn x2", Material.SUNFLOWER,
                    "skyblock:crystal/citrine", 2));
            case 21 -> rewards.add(Reward.custom("Opal x2", Material.PRISMARINE_SHARD,
                    "skyblock:crystal/opal", 2));
            case 28 -> rewards.add(Reward.custom("Oniks x3", Material.COAL,
                    "skyblock:crystal/onyx", 3));
            default -> { }
        }
        if (level == 7 || level == 14 || level == 21 || level == 28) {
            rewards.add(Reward.custom("Złoty Lotos", Material.SUNFLOWER,
                    "skyblock:token/gold_lotus", 1));
        }
        return List.copyOf(rewards);
    }
}
