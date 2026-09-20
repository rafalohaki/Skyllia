package org.rafalohaki.wpmecore.addons.skyblock;

import org.rafalohaki.wpmecore.addons.skyblock.quests.QuestCatalog;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("mockbukkit")
class SkyBlockSettingsTest {

    @BeforeAll
    static void startBukkitRegistry() {
        MockBukkit.mock();
    }

    @AfterAll
    static void stopBukkitRegistry() {
        MockBukkit.unmock();
    }

    @Test
    void defaultConfigIsCompleteAndPassesEconomicGuardrails() {
        SkyBlockSettings settings = SkyBlockSettings.load(defaultConfig());

        assertEquals(100L, settings.startingBalance());
        assertEquals(6, defaultConfig().getConfigurationSection("shop.categories").getKeys(false).size());
        assertEquals(84, settings.shop().byMaterial().size()); // +wędka, +kulki gliny (2026-09-05), +grass_block, +clay (P2-2 domknięte 2026-09-07), +4 ryby (d0e6d40d)
        assertEquals(9, settings.shop().byCustomItem().size());
        assertEquals(3, settings.quests().activeCount());
        assertEquals(3, settings.bankAmounts().size());
        // F24: doszedł kafelek „Sezon i karnet” (slot 29) — sezon nie miał
        // w Centrum gry żadnego wejścia.
        assertEquals(3, settings.externalActions().size());
        assertEquals("world", settings.hub().spawn().world());
        // Spawn hubu przeniesiony 2026-08-19 na (27.5, 99.0, 1.5), mapa BlueApple
        // Autumn. Uwaga operacyjna: te współrzędne żyją w TRZECH miejscach —
        // tutaj, w Skyllia config.toml [settings.spawn] i w world/level.dat
        // Data.spawn.pos — bo Skyllia nadpisuje pozycję przy wejściu gracza bez
        // wyspy. Zmiana samego config.yml daje objaw „log pokazuje dobrą
        // pozycję, a gracz ląduje gdzie indziej".
        assertEquals(27.5D, settings.hub().spawn().x());
        assertEquals(99.0D, settings.hub().spawn().y());
        assertEquals(1.5D, settings.hub().spawn().z());
        assertEquals(90.0F, settings.hub().spawn().yaw());
        // Hologramy huba wycofane 2026-08-31 do FancyHolograms — hub ich już nie parsuje.
        assertEquals(5, settings.hub().jumpPads().getFirst().blocks().size());
        // Skocznia przestrojona przy przeprowadzce spawnu: velocity.x = -2.0.
        assertEquals(-2.0D, settings.hub().jumpPads().getFirst().velocityX());
        assertEquals(false, settings.hub().openMenuOnFirstJoin());
        assertEquals(35, settings.gameplayRetentionDays());
        assertEquals("team", settings.externalActions().get(1).id());
        assertEquals(15000L, settings.automation().chunkerPrice());
        assertEquals(25000L, settings.automation().sellChestPrice());
        assertEquals("1 moneta", Ui.money(1L));
        assertEquals("2 monety", Ui.money(2L));
        assertEquals("12 monet", Ui.money(12L));
        assertEquals("24 monety", Ui.money(24L));
    }

    @Test
    void seasonRewardsStayClosedUntilTheOperatorOpensThem() {
        YamlConfiguration withoutSection = defaultConfig();
        withoutSection.set("seasons", null);
        org.junit.jupiter.api.Assertions.assertFalse(
                SkyBlockSettings.load(withoutSection).seasons().claimsOpen(),
                "brak sekcji musi znaczyć 'zamknięte', nie 'otwarte'");

        org.junit.jupiter.api.Assertions.assertFalse(
                SkyBlockSettings.load(defaultConfig()).seasons().claimsOpen(),
                "wysyłana konfiguracja nie może pozwalać na odbiór nagród w trwającym sezonie");
    }

    @Test
    void dailyRewardDefaultsAreShippedAndFailClosedOnBadValues() {
        SkyBlockSettings.DailyRewardSettings daily = SkyBlockSettings.load(defaultConfig()).dailyReward();
        assertEquals(true, daily.enabled());
        assertEquals(250L, daily.baseCoins());
        assertEquals(50L, daily.perStreakDay());
        assertEquals(7, daily.streakCap());
        assertEquals("skyblock:token/silver_lotus", daily.lotusItem());

        YamlConfiguration negative = defaultConfig();
        negative.set("daily-reward.base-coins", -1);
        assertThrows(IllegalArgumentException.class, () -> SkyBlockSettings.load(negative));
        YamlConfiguration badItem = defaultConfig();
        badItem.set("daily-reward.custom-item", "not an id");
        assertThrows(IllegalArgumentException.class, () -> SkyBlockSettings.load(badItem));
    }

    @Test
    void rejectsCommandInjectionInMenuActions() {
        YamlConfiguration config = defaultConfig();
        config.set("menu.external-actions.island.command", "is; stop");

        assertThrows(IllegalArgumentException.class, () -> SkyBlockSettings.load(config));
    }

    @Test
    void rejectsOutOfBoundsAndReservedMenuSlots() {
        YamlConfiguration outOfBounds = defaultConfig();
        outOfBounds.set("menu.external-actions.island.slot", 45);
        assertThrows(IllegalArgumentException.class, () -> SkyBlockSettings.load(outOfBounds));

        YamlConfiguration walletCollision = defaultConfig();
        walletCollision.set("menu.external-actions.island.slot", 4);
        assertThrows(IllegalArgumentException.class, () -> SkyBlockSettings.load(walletCollision));

        YamlConfiguration bankAllCollision = defaultConfig();
        bankAllCollision.set("bank.amounts", List.of(1L, 2L, 3L, 4L, 5L));
        assertThrows(IllegalArgumentException.class,
                () -> SkyBlockSettings.load(bankAllCollision));

        YamlConfiguration unsafeRetention = defaultConfig();
        unsafeRetention.set("storage.gameplay-retention-days", 1);
        assertThrows(IllegalArgumentException.class,
                () -> SkyBlockSettings.load(unsafeRetention));

        YamlConfiguration unsafeHubWorld = defaultConfig();
        unsafeHubWorld.set("hub.spawn.world", "../../world");
        assertThrows(IllegalArgumentException.class,
                () -> SkyBlockSettings.load(unsafeHubWorld));

        YamlConfiguration nonPlatePad = defaultConfig();
        nonPlatePad.set("hub.jump-pads.arrival-strip.plate-material", "ANDESITE");
        assertThrows(IllegalArgumentException.class,
                () -> SkyBlockSettings.load(nonPlatePad));

        YamlConfiguration automationSlotCollision = defaultConfig();
        automationSlotCollision.set("menu.external-actions.island.slot", 22);
        assertThrows(IllegalArgumentException.class, () -> SkyBlockSettings.load(automationSlotCollision));

        YamlConfiguration invalidChunkerPrice = defaultConfig();
        invalidChunkerPrice.set("automation.chunker.price", 0L);
        assertThrows(IllegalArgumentException.class, () -> SkyBlockSettings.load(invalidChunkerPrice));

        YamlConfiguration invalidSellChestPrice = defaultConfig();
        invalidSellChestPrice.set("automation.sell-chest.price", -500L);
        assertThrows(IllegalArgumentException.class, () -> SkyBlockSettings.load(invalidSellChestPrice));
    }

    @Test
    void rejectsCraftingLoopThatCreatesMoney() {
        YamlConfiguration config = defaultConfig();
        config.set("shop.categories.building.items.oak_planks.sell", 10L);

        assertThrows(IllegalArgumentException.class, () -> SkyBlockSettings.load(config));
    }

    @Test
    void dailyRotationIsDeterministicAndAdvancesWithoutStreakState() {
        QuestCatalog quests = SkyBlockSettings.load(defaultConfig()).quests();
        LocalDate day = LocalDate.of(2026, 7, 23);

        assertEquals(quests.active(day), quests.active(day));
        assertNotEquals(quests.active(day), quests.active(day.plusDays(1)));
        assertEquals("2026-07-23", quests.period(day));
    }

    private static YamlConfiguration defaultConfig() {
        InputStream stream = SkyBlockSettingsTest.class.getClassLoader()
                .getResourceAsStream("config.yml");
        if (stream == null) {
            throw new IllegalStateException("missing config.yml test resource");
        }
        return YamlConfiguration.loadConfiguration(
                new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
}
