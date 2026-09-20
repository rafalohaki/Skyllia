package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PASS-1 (F27): tor premium nie może drukować własnej waluty aktywacji.
 *
 * <p><b>Jaką klasę błędu zamyka.</b> {@code premiumItems} dawało 2 Złote
 * Lotosy na każdym z 28 poziomów — 56 sztuk za sezon przy koszcie aktywacji 6
 * ({@code config.yml: season.pass.premium-lotus-cost}). To pętla zyskowna
 * wprost w walucie kantoru, o krotności 9,33× na sezon i powtarzalna, bo
 * nagrody premium odbiera się wstecznie. Żadna bramka jej nie widziała, bo
 * zawartość nagród żyła w kodzie, a bramki balansowe czytają wyłącznie configi.
 *
 * <p>Warunek jest ostry ({@code <}), nie nieostry: zwrot równy cenie znaczy
 * „premium na zawsze po jednym opłaceniu”, czyli tę samą wadę wolniej.
 * Test celowo nie potrzebuje MockBukkita — {@code premiumItems} jest czystą
 * funkcją, a jedyne, co dochodzi z zewnątrz, to jedna liczba z configu.
 */
class SeasonPassEconomyBalanceTest {

    private static final Path CONFIG = Path.of("src/main/resources/config.yml");
    private static final String ACTIVATION_TOKEN = "skyblock:token/gold_lotus";

    @Test
    void thePremiumTrackNeverRepaysItsOwnActivationPrice() throws IOException {
        ConfigurationSection pass = section(load(CONFIG), "season.pass");
        int cost = pass.getInt("premium-lotus-cost", -1);
        assertTrue(cost > 0, "config.yml: season.pass.premium-lotus-cost musi być > 0");

        int returned = 0;
        for (int level = 1; level <= SeasonPassService.PILOT_LEVELS; level++) {
            for (SeasonPassRewards.Reward reward : SeasonPassRewards.premiumItems(level)) {
                if (ACTIVATION_TOKEN.equals(reward.customItemId())) {
                    returned += reward.amount();
                }
            }
        }

        assertTrue(returned < cost, "pełny tor premium zwraca " + returned + " × "
                + ACTIVATION_TOKEN + " przy cenie aktywacji " + cost + " szt. Bilans "
                + (returned - cost) + " znaczy, że tor finansuje sam siebie na "
                + (cost == 0 ? "∞" : String.valueOf(returned / cost))
                + " kolejnych sezonów, a tygodniowy kamień milowy (15 zadań = 1 lotos)"
                + " traci adresata. Pokrętła: SeasonPassRewards.premiumItems albo"
                + " config.yml: season.pass.premium-lotus-cost.");
    }

    /** Żaden poziom premium nie może być pusty — za to się płaci. */
    @Test
    void everyPremiumLevelHandsSomethingOver() {
        for (int level = 1; level <= SeasonPassService.PILOT_LEVELS; level++) {
            assertTrue(!SeasonPassRewards.premiumItems(level).isEmpty(),
                    "poziom premium " + level + " nie daje nic");
        }
    }

    private static ConfigurationSection section(ConfigurationSection root, String path) {
        ConfigurationSection section = root.getConfigurationSection(path);
        assertNotNull(section, "brak sekcji " + path);
        return section;
    }

    private static YamlConfiguration load(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), "brak wysyłanego zasobu " + path.toAbsolutePath());
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }
}
