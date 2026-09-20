package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.rafalohaki.wpmecore.addons.skyblock.economy.ConvergenceRetryPolicy;
import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;

import org.rafalohaki.wpmecore.addons.skyblock.shop.ServerShop;


import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionItem;
import org.rafalohaki.wpmecore.addons.skyblock.minions.MinionsConfig;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Inventories;

import org.rafalohaki.wpmecore.addons.skyblock.shared.QuantityMath;

import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.addons.skyblock.shared.ItemNames;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.economy.LedgerDao;
import org.rafalohaki.wpmecore.api.item.CustomItemService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Wykuwanie: zabranie składników i wydanie wyrobu.
 *
 * <p><b>To saga, nie jedna transakcja.</b> Outbox trzyma nadanie i usunięcie
 * rozłącznie — nadanie niesie ładunek przedmiotu, usunięcie niesie linie
 * składników — więc kuźnia zleca dwie trwałe operacje po kolei.
 *
 * <p>Kolejność: <b>najpierw składniki wraz z zapłatą, potem wyrób</b>. Odwrócona
 * 2026-08-17. Poprzednia (najpierw wyrób) opierała się na założeniu, że awaria
 * drugiego kroku jest przypadkowa i stratę bierze serwer — założenie fałszywe.
 * Między zwolnieniem dzierżawy pierwszego kroku a wzięciem jej przez drugi gracz
 * mógł przełożyć składniki do skrzyni; wtedy usunięcie nie znajdowało ich
 * w ekwipunku, padało, a gracz zostawał z wyrobem i składnikami — powtarzalnie.
 * To nie jednorazowa strata serwera, tylko produkcja wartości z niczego.
 *
 * <p>Przy tej kolejności najgorszy przypadek to „zapłacone, wyrób jeszcze nie
 * wydany". Gracz go nie wywoła, bo nadanie nie zależy od zawartości ekwipunku,
 * a brak miejsca kończy się {@code DEFERRED}, czyli trwałym długiem outboxa.
 *
 * <p>Drugi krok nie może wystartować z wnętrza callbacku pierwszego:
 * {@code operationCompleted} domyka callback, a dopiero potem zwalnia dzierżawę,
 * więc zagnieżdżone zlecenie dostałoby {@code BUSY}. Stąd przekazanie przez
 * scheduler encji gracza z ograniczoną liczbą prób.
 */
// Niefinalna wyłącznie dla testów: budowa realnego ItemStacka wymaga rejestru
// serwera, więc test przesłania resultStack i countOwned. Ten sam idiom co MinionMenu.
public class ForgeService {

    /** Ile razy próbować przejąć dzierżawę na drugi krok sagi, zanim odpuścimy. */

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage;
    private final InventoryOutbox outbox;
    private final ForgeConfig config;
    private final @Nullable CustomItemService customItems;
    private final ItemNames itemNames;
    /** Potrzebna do wyrobów typu minionek — stamtąd bierze się tekstura i tiery. */
    private final @Nullable MinionsConfig minions;

    public ForgeService(@NotNull JavaPlugin plugin, @NotNull MiniMessage miniMessage,
                 @NotNull InventoryOutbox outbox, @NotNull ForgeConfig config,
                 @Nullable CustomItemService customItems) {
        this(plugin, miniMessage, outbox, config, customItems, null);
    }

    public ForgeService(@NotNull JavaPlugin plugin, @NotNull MiniMessage miniMessage,
                 @NotNull InventoryOutbox outbox, @NotNull ForgeConfig config,
                 @Nullable CustomItemService customItems,
                 @Nullable MinionsConfig minions) {
        this.minions = minions;
        this.plugin = plugin;
        this.miniMessage = miniMessage;
        this.outbox = outbox;
        this.config = config;
        this.customItems = customItems;
        this.itemNames = new ItemNames(customItems);

        outbox.registerAuxiliaryHandler(InventoryOutbox.AUXILIARY_FORGE_PRODUCT,
                this::deliverProduct);
    }

    @NotNull ForgeConfig config() {
        return config;
    }

    /**
     * Nazwa składnika do lore menu — patrz {@link ItemNames}.
     */
    @NotNull String ingredientName(@NotNull ForgeConfig.ForgeIngredient ingredient) {
        if (ingredient.customItemId() != null) {
            return itemNames.customLabel(ingredient.customItemId());
        }
        Material material = ingredient.material();
        return material != null ? itemNames.vanillaLabel(material) : "?";
    }

    /**
     * Ile gracz ma każdego składnika receptury.
     *
     * <p>Vanilla liczy się wyłącznie ze stosów bez metadanych, więc customowy
     * przedmiot na bazie {@code BONE} nie zaliczy się na poczet wymaganych kości.
     */
    @NotNull Map<String, Integer> countOwned(@NotNull Player player,
                                             @NotNull ForgeConfig.ForgeRecipe recipe) {
        Map<String, Integer> owned = new LinkedHashMap<>();
        for (ForgeConfig.ForgeIngredient ingredient : recipe.ingredients()) {
            String key = ForgeRequirements.key(ingredient);
            owned.put(key, ingredient.customItemId() != null
                    ? ServerShop.countCustom(player.getInventory(),
                            ingredient.customItemId(), customItems)
                    : Inventories.countPlain(player.getInventory(), ingredient.material()));
        }
        return owned;
    }

    /**
     * Wykuwa recepturę. {@code afterSettled} biegnie dokładnie raz, na każdej
     * ścieżce — również tej, na której nic nie ruszyliśmy.
     */
    void craft(@NotNull Player player, @NotNull ForgeConfig.ForgeRecipe recipe,
               @NotNull Runnable afterSettled) {
        ItemStack result = resultStack(recipe);
        if (result == null) {
            actionBar(player, "<red>Ten wyrób jest chwilowo niedostępny.</red>");
            afterSettled.run();
            return;
        }

        int neededSlots = recipe.resultCommand() != null ? 0 : QuantityMath.requiredSlots(
                result.getMaxStackSize(), recipe.resultAmount());
        if (QuantityMath.countEmptySlots(player.getInventory()) < neededSlots) {
            actionBar(player, "<red>Zwolnij miejsce w ekwipunku (wymagane wolne sloty: "
                    + neededSlots + ").</red>");
            afterSettled.run();
            return;
        }

        if (!ForgeRequirements.satisfied(recipe, countOwned(player, recipe))) {
            actionBar(player, "<red>Brakuje surowców do wykucia.</red>");
            afterSettled.run();
            return;
        }

        /*
         * Obie synchroniczne przyczyny odmowy outboxa da się sprawdzić przed
         * zleceniem czegokolwiek, więc typowe „poprzednia operacja trwa" nie
         * kosztuje nawet zapisu do bazy.
         */
        if (!outbox.isEconomyReady(player.getUniqueId())) {
            actionBar(player, "<gray>Poprzednia operacja jeszcze trwa.</gray>");
            afterSettled.run();
            return;
        }

        String craftId = UUID.randomUUID().toString();
        /*
         * F17: cena schodzi przez config.costFor, a nie przez recipe.costMoney,
         * bo zniżka rangowa musi wejść w TO SAMO miejsce, w którym pieniądze
         * naprawdę schodzą z konta. Menu liczy ją tą samą metodą, więc cena
         * pokazana i cena pobrana nie mogą się rozjechać.
         */
        long price = config.costFor(player, recipe);
        outbox.beginRemoval(player, -price, "forge:take:" + craftId,
                "forge_take", ForgeRequirements.plainRemovals(recipe),
                ForgeRequirements.customRemovals(recipe),
                new InventoryOutbox.AuxiliaryMutation(
                        InventoryOutbox.AUXILIARY_FORGE_PRODUCT, recipe.id(),
                        recipe.resultAmount()),
                outcome -> onIngredientsTaken(player, recipe, price, craftId,
                        outcome, afterSettled));
    }

    private void onIngredientsTaken(Player player, ForgeConfig.ForgeRecipe recipe,
                                    long price, String craftId,
                                    InventoryOutbox.Outcome outcome, Runnable afterSettled) {
        switch (outcome) {
            case SUCCESS, DEFERRED -> {
                /*
                 * Składniki i zapłata są już trwale zaksięgowane, więc wyrób jest
                 * należny bezwarunkowo. DEFERRED po tej stronie znaczy tyle, że
                 * usunięcie domknie się później — dług i tak istnieje.
                 */
                celebrate(player);
                message(player,
                        "<dark_gray>[</dark_gray><gold><bold>KUŹNIA</bold></gold>"
                                + "<dark_gray>]</dark_gray> <gray>Wykuto</gray> <item> "
                                + "<gray>za</gray> <gold><price></gold><gray>.</gray>",
                        Placeholder.component("item", Ui.component(miniMessage, recipe.name())),
                        Placeholder.unparsed("price", Ui.money(price)));
                afterSettled.run();
            }
            case INSUFFICIENT -> {
                actionBar(player, "<red>Nie masz wystarczającej liczby monet.</red>");
                afterSettled.run();
            }
            case BUSY -> {
                actionBar(player, "<gray>Poprzednia operacja jeszcze trwa.</gray>");
                afterSettled.run();
            }
            case REJECTED, ERROR -> {
                message(player, "<red>Wykuwanie nie doszło do skutku. Nic nie zostało "
                        + "zużyte ani pobrane.</red>");
                afterSettled.run();
            }
        }
    }

    /**
     * Wydaje wyrób w ramach tej samej operacji, w której zeszły składniki i zapłata.
     *
     * <p>Wcześniej było to osobne zlecenie planowane z opóźnieniem, bo dzierżawa
     * pierwszego kroku bywała jeszcze trzymana. Między jednym a drugim istniała
     * sekunda, w której dług istniał wyłącznie w pamięci — awaria serwera w tym
     * oknie zabierała graczowi zapłatę i nie dawała nic w zamian. Teraz obietnica
     * jest zapisana razem z pobraniem, więc przeżywa restart i zostaje rozliczona
     * przy najbliższym wejściu gracza.
     *
     * <p>Zwrócenie {@code false} wstrzymuje operację zamiast ją zamykać, więc pełny
     * ekwipunek nie gubi wyrobu — dokłada się go, gdy zrobi się miejsce. Dlatego
     * dokładamy dopiero wtedy, gdy całość się mieści: częściowe dołożenie przy
     * ponowieniu wydałoby resztę drugi raz.
     */
    private boolean deliverProduct(@NotNull Player player,
                                   @NotNull LedgerDao.InventoryOperation operation) {
        String recipeId = operation.auxiliaryKey();
        ForgeConfig.ForgeRecipe recipe = recipeId == null ? null : config.recipes().get(recipeId);
        if (recipe == null) {
            plugin.getLogger().severe("Operacja " + operation.operationId()
                    + " należy wyrób receptury '" + recipeId + "', której nie ma w konfiguracji.");
            return false;
        }
        String command = recipe.resultCommand();
        if (command != null) {
            // Wyrób-komenda: dispatch na wątku globalnym (Folia). Komenda musi być
            // idempotentna, bo ponowiona operacja PENDING wywoła ją drugi raz —
            // dlatego minionki idą przez `result-item: minion:<typ>` i paragon
            // PDC outboxa, a tędy lecą tylko wyroby bez stanu.
            String resolved = command.replace("%player%", player.getName());
            org.bukkit.Bukkit.getGlobalRegionScheduler().run(plugin, task ->
                    org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), resolved));
            return true;
        }
        ItemStack result = resultStack(recipe);
        if (result == null) {
            plugin.getLogger().severe("Nie da się zbudować wyrobu receptury '" + recipeId
                    + "' dla operacji " + operation.operationId() + ".");
            return false;
        }
        /*
         * Wydanie przez paragon PDC: mutacja poboczna biegnie przy każdym
         * ponowieniu operacji PENDING, więc gołe addItem dokładałoby wyrób
         * przy każdym przebiegu. deliverForgeProduct liczy już wydane sztuki
         * (znaczone operationId) i dokłada tylko brakującą różnicę — pełny
         * ekwipunek zostawia operację oczekującą zamiast gubić wyrób.
         */
        return outbox.deliverTaggedProduct(player, operation.operationId(), result);
    }

    /** Seam: realna konstrukcja ItemStacka wymaga rejestru serwera. */
    @Nullable ItemStack resultStack(@NotNull ForgeConfig.ForgeRecipe recipe) {
        if (recipe.resultCommand() != null) {
            // Wyrób-komenda nie ma przedmiotu: ikona służy tylko do wyświetlenia w menu.
            if (recipe.iconCustomItemId() != null && customItems != null) {
                ItemStack custom = customItems.create(recipe.iconCustomItemId()).orElse(null);
                if (custom != null) {
                    return custom;
                }
            }
            return new ItemStack(recipe.icon() == null ? Material.PAPER : recipe.icon());
        }
        String minionType = recipe.resultMinionType();
        if (minionType != null) {
            /*
             * Zawsze tier 1: kuźnia sprzedaje wejście do systemu, a wyższe poziomy
             * kupuje się już w menu minionka za jego własny urobek.
             */
            MinionsConfig.TypeDef type = minions == null ? null : minions.type(minionType);
            return type == null ? null
                    : MinionItem.create(type, 1, false, null, 0L, "", miniMessage);
        }
        String customItemId = recipe.resultCustomItemId();
        if (customItemId != null) {
            if (customItems == null) {
                return null;
            }
            ItemStack stack = customItems.create(customItemId).orElse(null);
            if (stack != null) {
                stack.setAmount(recipe.resultAmount());
            }
            return stack;
        }
        Material material = recipe.resultMaterial();
        return material == null ? null : new ItemStack(material, recipe.resultAmount());
    }

    private void celebrate(Player player) {
        try {
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f);
            player.spawnParticle(Particle.CRIT, player.getLocation().add(0.0, 1.0, 0.0), 24);
        } catch (Throwable ignored) {
            // Efekty są ozdobą i nigdy nie mogą wywrócić transakcji.
        }
    }

    private void actionBar(Player player, String message, TagResolver... resolvers) {
        player.sendActionBar(Ui.component(miniMessage, message, resolvers));
    }

    private void message(Player player, String message, TagResolver... resolvers) {
        player.sendMessage(Ui.component(miniMessage, message, resolvers));
    }
}
