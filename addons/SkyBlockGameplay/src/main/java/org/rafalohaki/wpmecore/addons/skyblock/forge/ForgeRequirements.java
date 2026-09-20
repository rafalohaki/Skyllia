package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Arytmetyka składników kuźni, celowo bez zależności od żywego serwera.
 *
 * <p>Zliczanie ekwipunku wymaga Bukkita, a testy z MockBukkitem chodzą wyłącznie
 * pod osobnym profilem. Reguła „czy stać gracza" i podział składników na mapy dla
 * outboxa muszą być pilnowane przez domyślną bramkę, więc mieszkają tutaj.
 */
final class ForgeRequirements {

    private ForgeRequirements() {
    }

    /** Ile gracz ma danego składnika, gdy ma go za mało. */
    record Shortfall(@NotNull ForgeConfig.ForgeIngredient ingredient, int owned) {

        int missing() {
            return ingredient.amount() - owned;
        }
    }

    /** Klucz zliczania: identyfikator CustomItems z prefiksem albo nazwa Materiału. */
    static @NotNull String key(@NotNull ForgeConfig.ForgeIngredient ingredient) {
        String customItemId = ingredient.customItemId();
        return customItemId != null
                ? "custom:" + customItemId
                : Objects.requireNonNull(ingredient.material(),
                        "składnik bez identyfikatora i bez materiału").name();
    }

    /** Składniki, których gracz ma za mało — w kolejności z receptury. */
    static @NotNull List<Shortfall> shortfalls(@NotNull ForgeConfig.ForgeRecipe recipe,
                                               @NotNull Map<String, Integer> owned) {
        List<Shortfall> shortfalls = new ArrayList<>();
        for (ForgeConfig.ForgeIngredient ingredient : recipe.ingredients()) {
            int have = owned.getOrDefault(key(ingredient), 0);
            if (have < ingredient.amount()) {
                shortfalls.add(new Shortfall(ingredient, have));
            }
        }
        return shortfalls;
    }

    static boolean satisfied(@NotNull ForgeConfig.ForgeRecipe recipe,
                             @NotNull Map<String, Integer> owned) {
        return shortfalls(recipe, owned).isEmpty();
    }

    /**
     * Składniki zwykłe w formie, jakiej oczekuje {@code InventoryOutbox}.
     *
     * <p>Ta sama pozycja może wystąpić w recepturze wielokrotnie, więc ilości się
     * sumują — inaczej mapa po cichu zgubiłaby jedną z nich.
     */
    static @NotNull Map<org.bukkit.Material, Integer> plainRemovals(
            @NotNull ForgeConfig.ForgeRecipe recipe) {
        Map<org.bukkit.Material, Integer> removals = new LinkedHashMap<>();
        for (ForgeConfig.ForgeIngredient ingredient : recipe.ingredients()) {
            if (ingredient.material() != null) {
                removals.merge(ingredient.material(), ingredient.amount(), Integer::sum);
            }
        }
        return removals;
    }

    /** Składniki customowe w formie, jakiej oczekuje {@code InventoryOutbox}. */
    static @NotNull Map<String, Integer> customRemovals(
            @NotNull ForgeConfig.ForgeRecipe recipe) {
        Map<String, Integer> removals = new LinkedHashMap<>();
        for (ForgeConfig.ForgeIngredient ingredient : recipe.ingredients()) {
            if (ingredient.customItemId() != null) {
                removals.merge(ingredient.customItemId(), ingredient.amount(), Integer::sum);
            }
        }
        return removals;
    }

    /**
     * Receptura nie może zużywać własnego wyrobu.
     *
     * <p>SKYBLOCK-1-10: kolejność odwrócona 2026-08-17 — kuźnia najpierw ZABIRA
     * składniki w jednej sadze beginRemoval, a wyrób dokłada dopiero
     * deliverTaggedProduct (idempotentnie, znaczone operationId). Reguła nadal
     * słuszna: wyrób dodany jako mutacja poboczna nie może zawyżać baseline'u
     * liczonego przed usunięciem składników — inaczej zbieżność ponowień
     * zabierałaby świeżo wydany egzemplarz.
     */
    static boolean consumesItsOwnResult(@NotNull ForgeConfig.ForgeRecipe recipe) {
        for (ForgeConfig.ForgeIngredient ingredient : recipe.ingredients()) {
            if (recipe.resultCustomItemId() != null
                    && recipe.resultCustomItemId().equals(ingredient.customItemId())) {
                return true;
            }
            if (recipe.resultMaterial() != null
                    && recipe.resultMaterial() == ingredient.material()) {
                return true;
            }
        }
        return false;
    }
}
