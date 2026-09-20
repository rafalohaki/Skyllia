package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kontrakt publicznego artefaktu {@code fr.euphyllia.skyllia:api}: {@link SkylliaAPI}
 * jest statyczną fasadą z metodami wyspy, których używa {@link SkylliaBootstrap} i
 * {@link SkylliaIntegrationImpl}.
 *
 * <p>Po porzuceniu forka Skyllia nie probujemy już klas wewnętrznych (getInterneAPI,
 * menedżery, cache) — typowany adapter opiera się wyłącznie na tym publicznym API,
 * więc to ono jest tu weryfikowane.
 */
class SkylliaContractTest {

    @Test
    void skylliaApiExposesStaticIslandFacade() {
        String jarPath = SkylliaAPI.class.getProtectionDomain().getCodeSource().getLocation().getPath();


        assertNotNull(jarPath, "SkylliaAPI must come from a pinned jar on the classpath");
        assertTrue(staticMethod("getIslandByPlayerId", UUID.class) != null,
                "SkylliaAPI.getIslandByPlayerId(UUID) missing");
        assertTrue(staticMethod("getIslandByIslandId", UUID.class) != null,
                "SkylliaAPI.getIslandByIslandId(UUID) missing");
        assertTrue(staticMethod("isWorldSkyblock", String.class) != null,
                "SkylliaAPI.isWorldSkyblock(String) missing");
    }

    private static Method staticMethod(String name, Class<?>... params) {
        return Arrays.stream(SkylliaAPI.class.getMethods())
                .filter(m -> m.getName().equals(name)
                        && Arrays.equals(m.getParameterTypes(), params)
                        && Modifier.isStatic(m.getModifiers()))
                .findFirst()
                .orElse(null);
    }

    // --- scalone z SkylliaApiOnClasspathTest (oba probowaly te same statyczne metody SkylliaAPI) ---




    @Test
    void createIslandMethodExists() {
        assertTrue(
                Arrays.stream(SkylliaAPI.class.getMethods())
                        .anyMatch(m -> "createIsland".equals(m.getName())),
                "SkylliaAPI.createIsland(...) required for the CREATE capability");
    }

    // --- scalone z PaperPluginDescriptorTest (join-classpath to czesc tego samego kontraktu Skyllii) ---


    @Test
    void skylliaMustBeJoinedToClasspath() throws Exception {
        String yml = readPaperPluginYml();
        int skyllia = yml.indexOf("Skyllia:");
        assertTrue(skyllia >= 0, "paper-plugin.yml musi deklarować zależność Skyllia");
        // blok po "Skyllia:" aż do następnej zależności (WpmeCore/Vault) lub EOF
        String block = yml.substring(skyllia);
        assertTrue(block.contains("join-classpath: true"),
                "Skyllia musi mieć join-classpath: true — typowany adapter importuje klasy Skyllii. "
                        + "Zobacz commit 76ab054 (runtime bug).");
    }

    private static String readPaperPluginYml() throws Exception {
        Path[] candidates = {
                Paths.get("addons/skyblock/src/main/resources/paper-plugin.yml"),
                Paths.get("../skyblock/src/main/resources/paper-plugin.yml"),
                Paths.get("src/main/resources/paper-plugin.yml")
        };
        for (Path c : candidates) {
            if (Files.isReadable(c)) {
                return Files.readString(c);
            }
        }
        throw new IllegalStateException("Cannot locate paper-plugin.yml");
    }
}
