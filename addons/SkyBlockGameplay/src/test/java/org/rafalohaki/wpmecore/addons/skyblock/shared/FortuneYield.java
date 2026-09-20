package org.rafalohaki.wpmecore.addons.skyblock.shared;

/**
 * F14: mnożniki Fortuny, wspólne dla wszystkich bramek balansowych.
 *
 * <p><b>Po co to w ogóle jest.</b> Do F13 każda z trzech bramek
 * ({@code GeneratorEconomyBalanceTest}, {@code OneBlockEconomyBalanceTest},
 * {@code MinionEconomyBalanceTest}) liczyła waniliowy drop <b>bez Fortuny</b> —
 * czyli mierzyła przypadek bazowy, a nie sufit. Kilof z Fortuną III mnoży drop
 * rudy 2,20×, więc generator i rozdział endgame'owy OneBlocka przekraczały
 * własne sufity o 56 % i 25 %, świecąc przy tym na zielono. Ta klasa jest
 * jednym miejscem, w którym te wzory żyją, żeby nie mogły się rozjechać między
 * bramkami — bo rozjazd między bramkami to dokładnie ten błąd, przez który
 * cytryn z generatora przetrwał dwie fale audytu.
 *
 * <p><b>Skąd wzory.</b> Z waniliowych tabel łupów wydobytych z jara serwera
 * ({@code de964636/cache/mojang_26.2.jar} → {@code META-INF/versions/26.2/} →
 * {@code data/minecraft/loot_table/blocks/*.json}), nie z pamięci. Poziom III
 * jest twardym sufitem: {@code data/minecraft/enchantment/fortune.json} ma
 * {@code max_level: 3}, a {@code config/canvas-server.yml} ma
 * {@code enchant-command.uncap-max-level: false}. Fortuna działa też na motyki
 * ({@code #minecraft:enchantable/mining_loot} zawiera {@code #minecraft:hoes}),
 * więc obejmuje uprawy.
 */
public final class FortuneYield {

    private FortuneYield() {
    }

    /** Prawdopodobieństwo z {@code binomial_with_bonus_count} w tabelach upraw. */
    public static final double CROP_BINOMIAL_PROBABILITY = 0.5714286;

    /**
     * Twardy sufit poziomu Fortuny na tej wersji serwera:
     * {@code data/minecraft/enchantment/fortune.json -> max_level: 3} przy
     * {@code enchant-command.uncap-max-level: false} w {@code canvas-server.yml}.
     * Bramki balansowe i właściwości kwantyfikują po {@code 0..ten poziom},
     * niezależnie od tego, co akurat stoi w {@code balance.fortune-level} —
     * obniżenie configu nie może ściszyć przestrzeni sprawdzenia.
     */
    public static final int HARD_MAX_LEVEL = 3;

    /** Kształt funkcji {@code apply_bonus} w waniliowej tabeli łupów. */
    public enum Formula {
        /** Brak {@code apply_bonus} — Fortuna nic nie zmienia (bruk, obsydian, kakao). */
        NONE,
        /** {@code minecraft:ore_drops} — mnożnik {@code max(1, U{0..poziom+1})}. */
        ORE_DROPS,
        /** {@code minecraft:uniform_bonus_count} — dodaje {@code U{0..mnożnik×poziom}}. */
        UNIFORM_BONUS,
        /** {@code minecraft:binomial_with_bonus_count} — liczba sztuk to {@code Bin(extra+poziom, p)}. */
        BINOMIAL_BONUS
    }

    /**
     * Oczekiwana liczba sztuk przy danym poziomie Fortuny.
     *
     * @param formula kształt {@code apply_bonus} z tabeli łupów
     * @param base    dla {@link Formula#NONE}/{@link Formula#ORE_DROPS}/
     *                {@link Formula#UNIFORM_BONUS} — waniliowe {@code set_count}
     *                (średnia); dla {@link Formula#BINOMIAL_BONUS} — parametr
     *                {@code extra}
     * @param bonusMultiplier parametr {@code bonusMultiplier}; używany wyłącznie
     *                        przez {@link Formula#UNIFORM_BONUS}
     * @param level   poziom Fortuny (0 = gołe narzędzie)
     */
    public static double amount(Formula formula, double base, int bonusMultiplier, int level) {
        if (level < 0) {
            throw new IllegalArgumentException("poziom Fortuny nie może być ujemny: " + level);
        }
        return switch (formula) {
            case NONE -> base;
            case ORE_DROPS -> base * oreMultiplier(level);
            case UNIFORM_BONUS -> base + bonusMultiplier * level / 2.0;
            case BINOMIAL_BONUS -> (base + level) * CROP_BINOMIAL_PROBABILITY;
        };
    }

    /**
     * Oczekiwany mnożnik {@code minecraft:ore_drops}: losujemy liczbę całkowitą
     * z {@code [0, poziom+1]} i bierzemy {@code max(1, wynik)}. Dla poziomu 0
     * daje dokładnie 1,0; dla III — {@code (1+1+2+3+4)/5 = 2,2}.
     */
    public static double oreMultiplier(int level) {
        double sum = 0.0;
        for (int roll = 0; roll <= level + 1; roll++) {
            sum += Math.max(1, roll);
        }
        return sum / (level + 2);
    }
}
