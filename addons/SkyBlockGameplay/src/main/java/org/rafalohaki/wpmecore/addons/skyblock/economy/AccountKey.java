package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public record AccountKey(@NotNull Type type, @NotNull UUID id) {

    public enum Type {
        PLAYER("player"),
        ISLAND("island");

        private final String prefix;

        Type(String prefix) {
            this.prefix = prefix;
        }
    }

    public static @NotNull AccountKey player(@NotNull UUID id) {
        return new AccountKey(Type.PLAYER, id);
    }

    public static @NotNull AccountKey island(@NotNull UUID id) {
        return new AccountKey(Type.ISLAND, id);
    }

    @Override
    public @NotNull String toString() {
        return type.prefix + ':' + id;
    }
}
