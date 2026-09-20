package org.rafalohaki.wpmecore.addons.skyblock;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kontrakt treści dialogu {@code /pomoc}: każdy temat musi być kompletny
 * (tytuł, podpowiedź, niepuste linie), tytuły unikalne (są etykietami
 * przycisków), a każda linia z ikoną musi nieść tag fontu — surowy znak PUA
 * renderuje się u klienta jako puste miejsce (konwencja z POMOC).
 *
 * <p>Czysty JUnit — {@code PomocDialog.Topic} to record bez dotykania Bukkita,
 * więc test nie potrzebuje MockBukkita ani profilu.
 */
class PomocDialogContentTest {

    @Test
    void everyTopicHasTitleTooltipAndLines() {
        for (PomocDialog.Topic topic : PomocDialog.TOPICS) {
            assertFalse(topic.title().isBlank(), "temat bez tytułu");
            assertFalse(topic.tooltip().isBlank(), "temat bez podpowiedzi: " + topic.title());
            assertFalse(topic.lines().isEmpty(), "temat bez linii: " + topic.title());
            for (String line : topic.lines()) {
                assertFalse(line.isBlank(), "pusta linia w temacie " + topic.title());
            }
        }
    }

    @Test
    void topicTitlesAreUnique() {
        Set<String> titles = new HashSet<>();
        for (PomocDialog.Topic topic : PomocDialog.TOPICS) {
            assertTrue(titles.add(topic.title()),
                    "zduplikowany tytuł przycisku: " + topic.title());
        }
    }

    @Test
    void iconGlyphsAlwaysCarryFontTag() {
        for (PomocDialog.Topic topic : PomocDialog.TOPICS) {
            for (String line : topic.lines()) {
                int idx = 0;
                while ((idx = indexOfPua(line, idx)) >= 0) {
                    String before = line.substring(0, idx);
                    assertTrue(before.endsWith("<font:wpme:icons>"),
                            "glif PUA bez tagu fontu w temacie " + topic.title()
                                    + ": " + line);
                    idx++;
                }
            }
        }
    }

    @Test
    void helpCoversEveryCommandAdvertisedInChatVersion() {
        // Tekstowa wersja (POMOC) i dialog muszą reklamować ten sam zestaw
        // komend — inaczej dialog „gubi" komendę, którą obiecuje czat.
        List<String> advertised = List.of("/is", "/spawn", "/menu", "/sklep",
                "/ah", "/zadania", "/nagroda", "/sezon", "/przepustka",
                "/kuznia", "/bank", "/narzedzia", "/latanie",
                "/gierki", "/emf shop", "/wyspa oneblock");
        String allLines = PomocDialog.TOPICS.stream()
                .flatMap(t -> t.lines().stream())
                .reduce("", (a, b) -> a + "\n" + b);
        for (String command : advertised) {
            assertTrue(allLines.contains(command),
                    "dialog pomocy nie wspomina komendy z wersji czatowej: " + command);
        }
    }

    @Test
    void topicCountFitsTwoColumnGrid() {
        // multiAction z 2 kolumnami: powyżej ~8 tematów lista zaczyna się
        // przewijać i traci czytelność, dla której powstał.
        assertTrue(PomocDialog.TOPICS.size() >= 4 && PomocDialog.TOPICS.size() <= 8,
                "tematów: " + PomocDialog.TOPICS.size());
        assertEquals(0, PomocDialog.TOPICS.size() % 2,
                "nieparzysta liczba tematów zostawia pusty przycisk w siatce 2-kolumnowej");
    }

    private static int indexOfPua(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\uE000' && c <= '\uF8FF') {
                return i;
            }
        }
        return -1;
    }
}
