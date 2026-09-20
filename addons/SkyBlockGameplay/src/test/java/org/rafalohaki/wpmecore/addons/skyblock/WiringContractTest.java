package org.rafalohaki.wpmecore.addons.skyblock;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dwa niezmienniki wiązania, których nie pilnuje żaden inny test — i które
 * <b>zostały złamane</b> przy przebudowie struktury 2026-08-17.
 *
 * <p>Obie awarie miały ten sam kształt: kod kompilował się, wtyczka wstawała,
 * 865 testów świeciło na zielono, a funkcje po prostu przestawały działać.
 * Nic ich nie łapało, bo dotyczyły <i>rejestracji</i> i <i>napisów</i>, a nie
 * logiki. Ten test czyta źródła, bo tylko na tym poziomie te dwie rzeczy widać.
 */
class WiringContractTest {

    private static final Path MAIN = Path.of("src/main/java/org/rafalohaki/wpmecore/addons/skyblock");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("nie udało się wczytać " + path, failure);
        }
    }

    /**
     * Każda klasa z handlerami zdarzeń musi być gdzieś rejestrowana.
     *
     * <p>Przy przenoszeniu wiązania do modułów funkcji zgubiłem rejestrację
     * {@code SkyBlockHub} i {@code WandService} — trzynaście handlerów przestało
     * działać w ciszy: ochrona spawnu, skocznie, respawn i cała obsługa różdżek.
     */
    @Test
    void everyEventHandlerClassIsRegistered() throws IOException {
        List<Path> sources = sources();
        // 1) zbierz typy faktycznie rejestrowane: rozwiąż argument registerEvents
        //    na deklarację w tym samym pliku, bo nazwa zmiennej rzadko równa się
        //    nazwie klasy (registerEvents(wands, ...) to WandService)
        Set<String> registeredTypes = new LinkedHashSet<>();
        Pattern call = Pattern.compile("registerEvents\\(\\s*(?:this\\.)?(\\w+)");
        for (Path source : sources) {
            String code = read(source);
            Matcher calls = call.matcher(code);
            while (calls.find()) {
                String argument = calls.group(1);
                if ("this".equals(argument)) {
                    registeredTypes.add(source.getFileName().toString().replace(".java", ""));
                    continue;
                }
                if ("new".equals(argument)) {
                    // rejestracja bywa pisana pełną nazwą z kropkami —
                    // bierzemy ostatni człon, inaczej test dałby fałszywy alarm
                    Matcher constructed = Pattern.compile(
                            "registerEvents\\(\\s*new\\s+(?:[\\w.]+\\.)?(\\w+)").matcher(code);
                    while (constructed.find()) {
                        registeredTypes.add(constructed.group(1));
                    }
                    continue;
                }
                Matcher declaration = Pattern.compile(
                        "(?:private |final |public |protected |static )*(\\w+)\\s+"
                                + Pattern.quote(argument) + "\\s*[;=,)]").matcher(code);
                if (declaration.find()) {
                    registeredTypes.add(declaration.group(1));
                }
            }
        }

        Set<String> unregistered = new LinkedHashSet<>();
        for (Path source : sources) {
            String code = read(source);
            if (!code.contains("@EventHandler")) {
                continue;
            }
            String name = source.getFileName().toString().replace(".java", "");
            if (!registeredTypes.contains(name)) {
                unregistered.add(name);
            }
        }

        assertTrue(unregistered.isEmpty(),
                "klasy z @EventHandler, których nikt nie rejestruje — ich handlery są martwe: "
                        + unregistered);
    }

    /**
     * Nazwy i aliasy komend to napisy widziane przez gracza, a nie kod.
     *
     * <p>Mechaniczne podstawienie {@code plugin.} przy wydzielaniu komend weszło
     * także w literały: {@code Commands.literal("plugin.bank()")}. Komenda
     * {@code /bank} zniknęła, trzy aliasy zamieniły się w bełkot, a nic tego nie
     * zauważyło, bo napis kompiluje się tak samo dobrze jak każdy inny.
     */
    @Test
    void commandNamesAreWordsNotCodeFragments() throws IOException {
        Pattern literal = Pattern.compile("\"([^\"]*)\"");
        List<String> broken = new ArrayList<>();
        for (Path source : sources()) {
            String code = stripCommentsAndChars(read(source));
            if (!code.contains("Commands.literal(") && !code.contains("List.of(\"")) {
                continue;
            }
            Matcher matcher = literal.matcher(code);
            while (matcher.find()) {
                String value = matcher.group(1);
                if (value.contains("plugin.") || value.matches(".*\\w+\\(\\).*")) {
                    broken.add(source.getFileName() + ": \"" + value + '"');
                }
            }
        }
        assertTrue(broken.isEmpty(),
                "napisy wyglądające na wklejony kod zamiast nazw dla gracza: " + broken);
    }

    /**
     * Skaner literałów paruje cudzysłowy od początku pliku, więc JEDEN niesparowany
     * {@code "} poza kodem przesuwa parowanie w całej reszcie i test zgłasza kod,
     * którego nie ma. Źródła takich cudzysłowów: polskie komentarze („cytat"),
     * literały znakowe {@code '"'} i apostrofy w prozie. Wycinamy je prawdziwym
     * skanerem stanowym — regex tego nie potrafi, bo {@code //} występuje też
     * wewnątrz {@code "https://…"}.
     */
    private static String stripCommentsAndChars(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        int n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            if (c == '/' && i + 1 < n && code.charAt(i + 1) == '/') {
                while (i < n && code.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && code.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(code.charAt(i) == '*' && code.charAt(i + 1) == '/')) i++;
                i = Math.min(n, i + 2);
                out.append(' ');
            } else if (c == '\'') {
                i++; // literał znakowy: '"' i '\\'' nie mogą zaburzyć parowania
                while (i < n && code.charAt(i) != '\'') {
                    if (code.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(n, i + 1);
                out.append(" ' ' ");
            } else if (c == '"' && i + 2 < n && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"') {
                // text block — treść nie jest nazwą komendy, a jego cudzysłowy rozwaliłyby parowanie
                i += 3;
                while (i + 2 < n && !(code.charAt(i) == '"' && code.charAt(i + 1) == '"'
                        && code.charAt(i + 2) == '"')) {
                    if (code.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(n, i + 3);
                out.append(" \"\" ");
            } else if (c == '"') {
                out.append(c);
                i++;
                while (i < n && code.charAt(i) != '"') {
                    if (code.charAt(i) == '\\') {
                        i++; // escape — kolejny znak nie zamyka literału
                        if (i < n) i++;
                        continue;
                    }
                    out.append(code.charAt(i));
                    i++;
                }
                if (i < n) out.append('"');
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String decapitalize(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
