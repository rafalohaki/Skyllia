package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Jedna pozycja tablicy wyników.
 *
 * @param rank       miejsce, licząc od 1
 * @param playerId   właściciel wyspy (ranking sezonowy) albo gracz (hall of fame);
 *                   po nim bierzemy głowę
 * @param name       nick do wyświetlenia
 * @param score      wartość, po której sortujemy
 * @param detail     druga linia opisu, np. rozbicie wyniku
 */
record LeaderboardEntry(int rank, @NotNull UUID playerId, @NotNull String name,
                        long score, @NotNull String detail) {
}
