package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-API-01: tylko pakiet {@code skylliaintegration} wolno dotykać Skyllii — zarówno
 * typowanymi importami, jak i runtime-stringami. Reszta SkyBlockGameplay musi korzystać
 * z niezmienników Wpme ({@link IslandSnapshot} itd.).
 *
 * <p>Reguła 1 (typowane importy, ArchUnit) i Reguła 2 (source-scan napisów
 * {@code "fr.euphyllia.skyllia"}) są obie aktywne — po typowanym rewrice (M1-A0.2 Plan 2b)
 * żadne źródło poza {@code skylliaintegration} nie zawiera takich napisów. Używamy source-scanu,
 * a nie blanket-banu {@code ClassLoader.loadClass}, by nie false-positive'ować np.
 * VaultEconomyHook (wykrywanie opcjonalnego Vaulta).
 */
class ImportRuleTest {

    private static final String SKYBLOCK_MAIN =
            "addons/SkyBlockGameplay/src/main/java/org/rafalohaki/wpmecore/addons/skyblock";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.rafalohaki.wpmecore.addons.skyblock");

    /** Reguła 1: żadna klasa poza skylliaintegration nie może typowo zależeć od Skyllii. */
    @Test
    void onlySkylliaIntegrationMayDependOnSkyllia() {
        noClasses()
                .that().resideOutsideOfPackage("..skylliaintegration..")
                .should().dependOnClassesThat().resideInAnyPackage("fr.euphyllia.skyllia..")
                .because("R-API-01: Skyllia types may only be referenced inside skylliaintegration")
                .check(classes);
    }

    /**
     * Reguła 2: żadne źródło poza skylliaintegration nie zawiera napisu
     * {@code "fr.euphyllia.skyllia"} (refleksyjny dostęp do Skyllii).
     */
    @Test
    void noSkylliaStringReferencesOutsideIntegration() throws Exception {
        Path root = resolveModuleSourceRoot();
        List<String> violators = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/skylliaintegration/"))
                    .forEach(p -> {
                        try {
                            List<String> lines = Files.readAllLines(p);
                            for (int i = 0; i < lines.size(); i++) {
                                if (lines.get(i).contains("fr.euphyllia.skyllia")) {
                                    violators.add(p.getFileName() + ":" + (i + 1) + " -> " + lines.get(i).trim());
                                }
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        assertTrue(violators.isEmpty(),
                "R-API-01: fr.euphyllia.skyllia referenced outside skylliaintegration:\n"
                        + String.join("\n", violators));
    }

    /** Lokuje katalog źródeł main modułu skyblock niezależnie od cwd runnera. */
    private static Path resolveModuleSourceRoot() {
        Path[] candidates = {
                Paths.get(SKYBLOCK_MAIN),
                Paths.get("addons/SkyBlockGameplay/src/main/java/org/rafalohaki/wpmecore/addons/skyblock"),
                Paths.get("../SkyBlockGameplay/src/main/java/org/rafalohaki/wpmecore/addons/skyblock"),
                // Modul dziala tez z cwd runnera ustawionym na katalog addonu
                Paths.get("src/main/java/org/rafalohaki/wpmecore/addons/skyblock")
        };
        for (Path c : candidates) {
            if (Files.isDirectory(c)) {
                return c;
            }
        }
        throw new IllegalStateException("Cannot locate skyblock main source root; tried candidates under " + SKYBLOCK_MAIN);
    }
}
