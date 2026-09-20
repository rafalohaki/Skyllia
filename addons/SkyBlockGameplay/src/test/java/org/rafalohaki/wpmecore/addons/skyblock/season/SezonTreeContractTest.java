package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kontrakt kształtu drzewa Brigadier komend sezonowych w {@code SkyBlockCommands}.
 *
 * <p>Kształt drzewa nie jest widoczny bez uruchomienia serwera, więc test czyta
 * źródło jako tekst (jak {@code WiringContractTest}). Dzieci korzenia /sezon
 * liczone są dokładnie raz <b>wewnątrz bloku rejestracji sezonu</b>, korzenie
 * sezonowe (/sezon, /przepustka, /menu) — dokładnie raz w całym pliku; literał
 * „admin” występuje też jako dziecko /nagrody i /skyblock, dlatego licznik
 * globalny byłby fałszywym alarmem.
 */
class SezonTreeContractTest {

    /** Wszystkie oczekiwane literały komend sezonowych (jako Commands.literal("…")). */
    private static final List<String> EXPECTED_LITERALS = List.of(
            "sezon", "zamknij", "--confirm", "--force", "pass", "przepustka", "menu", "admin");

    /** Bezpośrednie dzieci /sezon — każdy dokładnie raz w bloku sezonu. */
    private static final List<String> EXPECTED_SEZON_CHILDREN = List.of(
            "zamknij", "--confirm", "--force", "pass", "admin");

    /** Korzenie komend sezonowych — każda rejestracja dokładnie raz w pliku. */
    private static final List<String> EXPECTED_ROOTS = List.of(
            "sezon", "przepustka", "menu");

    /** Bramka administracyjna wymagana na „zamknij” i „admin”. */
    private static final String ADMIN_PERMISSION = "skyblockgameplay.admin";

    private static final String LITERAL_PREFIX = "Commands.literal(\"";

    private static final Path[] SOURCE_CANDIDATES = {
            Path.of("src/main/java/org/rafalohaki/wpmecore/addons/skyblock/SkyBlockCommands.java"),
            Path.of("addons/skyblock/src/main/java/org/rafalohaki/wpmecore/addons/skyblock/SkyBlockCommands.java"),
            Path.of("../skyblock/src/main/java/org/rafalohaki/wpmecore/addons/skyblock/SkyBlockCommands.java"),
    };

    @Test
    void sezonTreeShapeIsPinned() throws IOException {
        String src = readSkyBlockCommandsSource();
        Map<String, Integer> mismatches = new LinkedHashMap<>();

        // Każdy oczekiwany literał musi istnieć w źródle jako Commands.literal("…").
        for (String literal : EXPECTED_LITERALS) {
            if (countLiteral(src, literal) == 0) {
                mismatches.put(literal + " (plik)", 0);
            }
        }

        // Dzieci /sezon: dokładnie raz w bloku rejestracji sezonu.
        String sezonBlock = sezonBlock(src);
        for (String child : EXPECTED_SEZON_CHILDREN) {
            int count = countLiteral(sezonBlock, child);
            if (count != 1) {
                mismatches.put(child + " (blok /sezon)", count);
            }
        }

        // Korzenie: dokładnie jedna rejestracja registrar.register(...) w pliku.
        for (String root : EXPECTED_ROOTS) {
            int count = countOccurrences(src, "registrar.register(" + LITERAL_PREFIX + root + "\")");
            if (count != 1) {
                mismatches.put(root + " (korzeń)", count);
            }
        }

        // Bramki uprawnień admin na „zamknij” i „admin”.
        for (String gated : List.of("zamknij", "admin")) {
            String head = headBeforeExecutes(sezonBlock, gated);
            if (!head.contains("hasPermission(\"" + ADMIN_PERMISSION + "\")")) {
                mismatches.put(gated + " (bramka " + ADMIN_PERMISSION + ")", -1);
            }
        }

        assertTrue(mismatches.isEmpty(), () -> diffMessage(mismatches));
    }

    /** Blok tekstu od literału „sezon” do początku następnej rejestracji. */
    private static String sezonBlock(String src) {
        int start = requireIndex(src, LITERAL_PREFIX + "sezon\")", "korzeń /sezon");
        int end = src.indexOf("registrar.register(", start);
        return src.substring(start, end > start ? end : src.length());
    }

    /** Fragment między Commands.literal(name) a najbliższym .executes( — miejsce bramki requires. */
    private static String headBeforeExecutes(String block, String name) {
        int at = requireIndex(block, LITERAL_PREFIX + name + "\")", "dziecko /" + name);
        int exec = block.indexOf(".executes(", at);
        return block.substring(at, exec > at ? exec : block.length());
    }

    private static int countLiteral(String src, String literal) {
        return countOccurrences(src, LITERAL_PREFIX + literal + "\")");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }

    private static int requireIndex(String haystack, String needle, String what) {
        int at = haystack.indexOf(needle);
        if (at < 0) {
            throw new IllegalStateException("nie znaleziono " + what + " (" + needle + ") w źródle SkyBlockCommands");
        }
        return at;
    }

    private static String readSkyBlockCommandsSource() throws IOException {
        for (Path candidate : SOURCE_CANDIDATES) {
            if (Files.isReadable(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("nie udało się zlokalizować SkyBlockCommands.java względem katalogu modułu");
    }

    /** Precyzyjny komunikat różnicy: oczekiwano vs znaleziono dla każdego rozjazdu. */
    private static String diffMessage(Map<String, Integer> mismatches) {
        List<String> lines = new ArrayList<>();
        lines.add("Drzewo komend sezonowych odbiega od kontraktu:");
        mismatches.forEach((what, found) -> {
            if (found == 0) {
                lines.add("  - brak literału: " + what);
            } else if (found < 0) {
                lines.add("  - brak bramki uprawnienia: " + what);
            } else {
                lines.add("  - oczekiwano dokładnie 1 raz, znaleziono " + found + ": " + what);
            }
        });
        lines.add("Oczekiwane literały: " + EXPECTED_LITERALS);
        return String.join(System.lineSeparator(), lines);
    }
}
