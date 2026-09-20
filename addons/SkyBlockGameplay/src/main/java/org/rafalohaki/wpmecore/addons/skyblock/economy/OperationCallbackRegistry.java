package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rejestr callbacków operacji outboxu w locie.
 *
 * <p><b>A2-25.</b> Klucz to <b>para</b> {@code (operationId, playerId)}, nie sam
 * identyfikator operacji. Dwa identyfikatory są celowo wspólne dla całej wyspy —
 * {@code "season_reward:lotus:<sezon>:<wyspa>"} i {@code "milestone:<wyspa>:<tydzień>"}
 * — więc kluczowanie samym {@code operationId} sprawiało, że druga rejestracja
 * <b>nadpisywała</b> wpis pierwszego gracza: {@code findDanglingFor(B)} trafiał
 * w klucz A, a B odbierał nagrodę A.
 */
final class OperationCallbackRegistry {

    private record Key(@NotNull String operationId, @NotNull UUID playerId) { }

    private final Logger logger;
    private final Map<Key, Consumer<InventoryOutbox.Outcome>> callbacks =
            new ConcurrentHashMap<>();

    OperationCallbackRegistry(@NotNull Logger logger) {
        this.logger = logger;
    }

    void put(@NotNull String operationId, @NotNull UUID playerId,
             @NotNull Consumer<InventoryOutbox.Outcome> callback) {
        callbacks.put(new Key(operationId, playerId), callback);
    }

    boolean hasPendingFor(@NotNull UUID playerId) {
        return callbacks.keySet().stream()
                .anyMatch(key -> key.playerId().equals(playerId));
    }

    @NotNull Optional<String> findDanglingFor(@NotNull UUID playerId) {
        return callbacks.keySet().stream()
                .filter(key -> key.playerId().equals(playerId))
                .map(Key::operationId)
                .findFirst();
    }

    boolean complete(@NotNull String operationId, @NotNull UUID playerId,
                     @NotNull InventoryOutbox.Outcome outcome) {
        Consumer<InventoryOutbox.Outcome> callback =
                callbacks.remove(new Key(operationId, playerId));
        if (callback != null) {
            try {
                callback.accept(outcome);
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING,
                        "Inventory outbox completion callback failed for " + operationId,
                        failure);
            }
            return true;
        }
        return false;
    }

    void removeForPlayer(@NotNull UUID playerId) {
        callbacks.keySet().removeIf(key -> key.playerId().equals(playerId));
    }

    int count() {
        return callbacks.size();
    }
}
