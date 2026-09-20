package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Wspólne narzędzia testów edycji sezonowych: parsowanie YAML i DAO-nic-nie-robi. */
final class SeasonEditionFixtures {

    private SeasonEditionFixtures() {
    }

    /**
     * Ładuje rejestr z sekcji {@code editions} podanego YAML-a (kontrakt B1:
     * {@code load} dostaje samą sekcję, precedens SeasonSchedule.load).
     */
    static EditionRegistry loadRegistry(String editionsSectionYaml) {
        YamlConfiguration yaml = yaml(editionsSectionYaml);
        org.bukkit.configuration.ConfigurationSection node =
                yaml.getConfigurationSection("editions");
        return EditionRegistry.load(node != null ? node : yaml);
    }

    /**
     * Parsuje YAML ścisłym trybem ({@code loadFromString}); błąd składni →
     * {@link IllegalStateException}. Wspólny zamiast kopii w każdym teście.
     */
    static YamlConfiguration yaml(String text) {
        try {
            YamlConfiguration parsed = new YamlConfiguration();
            parsed.loadFromString(text);
            return parsed;
        } catch (InvalidConfigurationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** DAO-nic-nie-robi dla testów, które nie dotykają toru aktywacji. */
    enum NoopDao implements SeasonEditionDao {
        INSTANCE;

        @Override
        public @NotNull CompletableFuture<Map<Integer, SeasonEditionDao.Row>> latestPerSeason() {
            return CompletableFuture.completedFuture(Map.of());
        }

        @Override
        public @NotNull CompletableFuture<Void> recordActivation(
                int seasonId, @NotNull Edition edition, long nowMillis) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public @NotNull CompletableFuture<Integer> stampClosed(
                @Nullable Connection tx, int seasonId, long closedAtMillis) {
            return CompletableFuture.completedFuture(0);
        }
    }
}
