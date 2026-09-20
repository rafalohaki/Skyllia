package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract probe M1-A0:
 * <ul>
 *   <li>{@link #recordPinnedJarSurface()} — zawsze działa: rejestruje powierzchnię
 *       przypiętego JAR (classpath) i weryfikuje obecność klas-kluczy. Wejście dla Planu 2.</li>
 *   <li>{@link #compareWithActiveJarResolvesSection26_1()} — porównanie z aktywnym
 *       {@code a9a70f6}; SKIP (assumeTrue), gdy aktywny JAR nie jest podmontowany,
 *       bo porównanie §26.1 wymaga obu artefaktów. Decyzja §26.1: ADR-0006 (pin 9b1574d).</li>
 * </ul>
 * Wynik: {@code target/skyllia-symbol-probe.json}.
 */
class SkylliaSymbolProbeTest {

    /** Aktywny JAR (a9a70f6) — ścieżka nadpisywalna przez system property. */
    private static final String ACTIVE_JAR = System.getProperty(
            "skyliacmp.activeJar",
            "/var/lib/pufferpanel/servers/fec9dc3b/plugins/Skyllia-3.0-wpme-a9a70f6-all.jar");

    private static final List<String> KEY_CLASSES = List.of(
            "fr.euphyllia.skyllia.api.SkylliaAPI",
            "fr.euphyllia.skyllia.api.skyblock.Island",
            "fr.euphyllia.skyllia.api.skyblock.Players",
            "fr.euphyllia.skyllia.api.coordinate.RegionCoordinate",
            "fr.euphyllia.skyllia.api.database.IslandMemberQuery",
            "fr.euphyllia.skyllia.api.event.SkyblockDeleteEvent");

    /** Classloader, który załadował SkylliaAPI — pewny do ładowania innych typów Skyllii. */
    private static final ClassLoader SKYLLIA_LOADER = SkylliaAPI.class.getClassLoader();

    @Test
    void recordPinnedJarSurface() throws Exception {
        Path candidateJar = classpathSkylliaJar();
        assertNotNull(candidateJar, "Skyllia must be on the classpath (provided dep)");

        Set<String> candClasses = skylliaClasses(candidateJar);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("pinned_9b1574d", candidateJar.toString());
        report.put("totalSkylliaClasses", candClasses.size());
        report.put("discoveredManagers", Map.of(
                "SkyblockManager", discoverSimple(candClasses, "SkyblockManager"),
                "SkyblockCache", discoverSimple(candClasses, "SkyblockCache"),
                "IslandMemberQuery", discoverSimple(candClasses, "IslandMemberQuery")));
        report.put("keyClassesPresentOnPinnedJar", keyClassPresence(candClasses));
        report.put("pinnedMethodSurface", methodSurface());

        Path out = Path.of("target/skyllia-symbol-probe.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, toJson(report), StandardCharsets.UTF_8);

        // Wszystkie klasy-klucze muszą być obecne na przypiętym JAR.
        for (String c : KEY_CLASSES) {
            assertTrue(candClasses.contains(c.replace('.', '/') + ".class"),
                    "Pinned Skyllia JAR missing key class: " + c);
        }
    }

    @Test
    void compareWithActiveJarResolvesSection26_1() throws Exception {
        Path activeJar = Path.of(ACTIVE_JAR);
        Assumptions.assumeTrue(Files.isReadable(activeJar),
                "§26.1 comparison requires the active a9a70f6 jar; pass -Dskyliacmp.activeJar=<path> "
                        + "or mount it. Decision already recorded in ADR-0006 (pin 9b1574d).");

        Set<String> cand = skylliaClasses(classpathSkylliaJar());
        Set<String> act = skylliaClasses(activeJar);

        for (String c : KEY_CLASSES) {
            String entry = c.replace('.', '/') + ".class";
            assertTrue(cand.contains(entry) && act.contains(entry),
                    "§26.1: key class presence differs between 9b1574d and a9a70f6: " + c);
        }
    }

    private static Map<String, Boolean> keyClassPresence(Set<String> entries) {
        Map<String, Boolean> presence = new LinkedHashMap<>();
        for (String c : KEY_CLASSES) {
            presence.put(c, entries.contains(c.replace('.', '/') + ".class"));
        }
        return presence;
    }

    private static Set<String> skylliaClasses(Path jar) throws IOException {
        Set<String> out = new TreeSet<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            jf.stream()
                    .map(e -> e.getName())
                    .filter(n -> n.startsWith("fr/euphyllia/skyllia/") && n.endsWith(".class"))
                    .forEach(out::add);
        }
        return out;
    }

    private static String discoverSimple(Set<String> entries, String simpleName) {
        return entries.stream()
                .filter(n -> n.endsWith("/" + simpleName + ".class"))
                .map(n -> n.replace('/', '.').replace(".class", ""))
                .findFirst().orElse("ABSENT");
    }

    private static Map<String, Object> methodSurface() {
        Map<String, Object> surface = new LinkedHashMap<>();
        checkMethods(surface, "fr.euphyllia.skyllia.api.SkylliaAPI", List.of(
                "getIslandByPlayerId", "getIslandByIslandId", "getIslandByOwner",
                "createIsland", "isWorldSkyblock", "getFlagRegistry",
                "getFlagModuleManager", "getBiomesImpl", "getTrustService"));
        checkMethods(surface, "fr.euphyllia.skyllia.api.skyblock.Island", List.of(
                "getId", "getOwner", "getMember", "getRegionCoordinate", "getSize",
                "isDisable", "isInside", "updateMember", "removeMember", "addWarps",
                "getWarps", "getWarpByName"));
        return surface;
    }

    private static void checkMethods(Map<String, Object> out, String fqcn, List<String> methods) {
        Map<String, Boolean> presence = new LinkedHashMap<>();
        try {
            // loadClass = bez <clinit> (SkylliaAPI ma inicjalizator wymagający runtime).
            Class<?> c = SKYLLIA_LOADER.loadClass(fqcn);
            for (String m : methods) {
                presence.put(m, Arrays.stream(c.getMethods()).anyMatch(x -> x.getName().equals(m))
                        || Arrays.stream(c.getDeclaredMethods()).anyMatch(x -> x.getName().equals(m)));
            }
            out.put(fqcn, presence);
        } catch (Throwable t) {
            out.put(fqcn, "load-error: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private static Path classpathSkylliaJar() {
        // Literał klasy = sprawdzone ładowanie (patrz SkylliaApiOnClasspathTest); brak <clinit>.
        String loc = SkylliaAPI.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        return (loc != null && loc.endsWith(".jar")) ? Path.of(loc) : null;
    }

    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder();
        writeMap(sb, m, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map<?, ?> mm) {
            writeMap(sb, (Map<String, Object>) mm, indent);
        } else if (v instanceof Iterable<?> it) {
            sb.append("[");
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(", ");
                writeValue(sb, o, indent);
                first = false;
            }
            sb.append("]");
        } else if (v instanceof String s) {
            sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        } else if (v == null) {
            sb.append("null");
        } else {
            sb.append(String.valueOf(v));
        }
    }

    private static void writeMap(StringBuilder sb, Map<String, Object> m, int indent) {
        String pad = "  ".repeat(indent);
        sb.append("{\n");
        int i = 0;
        for (var e : m.entrySet()) {
            sb.append(pad).append("  \"").append(e.getKey()).append("\": ");
            writeValue(sb, e.getValue(), indent + 1);
            if (++i < m.size()) sb.append(",");
            sb.append('\n');
        }
        sb.append(pad).append("}");
    }
}
