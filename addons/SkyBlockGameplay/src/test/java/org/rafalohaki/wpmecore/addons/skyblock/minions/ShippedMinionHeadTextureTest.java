package org.rafalohaki.wpmecore.addons.skyblock.minions;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Wysyłane tekstury głów muszą być poprawne co do formatu.
 *
 * <p>Zepsuta tekstura nie wywala niczego: {@code applyHeadTexture} połyka
 * wyjątek, a gracz dostaje pustą głowę. Błąd wychodzi więc dopiero na serwerze
 * i to nie w logu, tylko w oczach gracza. Ten test jest jedynym miejscem, które
 * potrafi go złapać wcześniej — sprawdza, że wartość rozkodowuje się z base64
 * do oczekiwanego JSON-a i wskazuje na hasz tekstury Mojanga.
 *
 * <p>Nie sprawdza, czy hasz istnieje po stronie Mojanga — to wymagałoby sieci
 * w bramce. Sprawdza kształt, bo to właśnie kształt psuje się przy ręcznym
 * przepisywaniu.
 */
class ShippedMinionHeadTextureTest {

    private static final Path MINIONS = Path.of("src/main/resources/minions.yml");

    /** Hasz w adresie tekstury: same znaki szesnastkowe, sensownej długości. */
    private static final Pattern TEXTURE_URL = Pattern.compile(
            "\\{\"textures\":\\{\"SKIN\":\\{\"url\":"
                    + "\"http://textures\\.minecraft\\.net/texture/([0-9a-f]{48,64})\"}}}");

    @Test
    void everyShippedHeadTextureDecodesToAMojangSkinUrl() throws IOException {
        ConfigurationSection minions = minions();
        Set<String> seenHashes = new LinkedHashSet<>();
        int checked = 0;

        for (String typeId : minions.getKeys(false)) {
            ConfigurationSection type = minions.getConfigurationSection(typeId);
            assertNotNull(type, typeId);
            String encoded = type.getString("head-texture");
            if (encoded == null) {
                // Typ może zamiast tekstury deklarować head-material — to poprawne.
                assertNotNull(type.getString("head-material"),
                        typeId + ": brak i head-texture, i head-material");
                continue;
            }

            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException notBase64) {
                fail(typeId + ": head-texture nie jest poprawnym base64");
                return;
            }
            var matcher = TEXTURE_URL.matcher(decoded);
            assertTrue(matcher.matches(), typeId
                    + ": rozkodowana tekstura ma nieoczekiwany kształt: " + decoded);
            assertTrue(seenHashes.add(matcher.group(1)),
                    typeId + ": ta sama tekstura co inny typ — minionki byłyby nierozróżnialne");
            checked++;
        }
        assertTrue(checked > 0, "żaden typ nie deklaruje head-texture — test nic nie sprawdził");
    }

    private static ConfigurationSection minions() throws IOException {
        assertTrue(Files.isRegularFile(MINIONS), "brak " + MINIONS.toAbsolutePath());
        try (Reader reader = Files.newBufferedReader(MINIONS, StandardCharsets.UTF_8)) {
            ConfigurationSection section = YamlConfiguration.loadConfiguration(reader)
                    .getConfigurationSection("minions");
            assertNotNull(section, "minions.yml nie ma sekcji minions");
            return section;
        }
    }
}
