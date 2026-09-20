package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.rafalohaki.wpmecore.addons.skyblock.economy.ConvergenceRetryPolicy;
import org.rafalohaki.wpmecore.addons.skyblock.economy.InventoryOutbox;


import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.item.CustomItemService;
import org.rafalohaki.wpmecore.api.item.ItemPolicyMarkers;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Wydawanie kosmetyki kolekcjonerskiej.
 *
 * <p>Każdy egzemplarz jest stemplowany prowieniencją — próg i edycja — i jest
 * soulbound, więc bramka handlowa nie pozwoli go sprzedać ani zużyć w kuźni.
 * To celowe: kosmetyka ma zostać dowodem zdobycia, a nie towarem.
 *
 * <p>Wydanie idzie przez {@link InventoryOutbox}, więc przeżywa awarię serwera
 * i pełny ekwipunek. Nagroda sezonowa dodawała dotąd przedmiot wprost do
 * ekwipunku, co gubiło ją przy awarii między zapisem w księdze a dodaniem.
 *
 * <p>Ta sama droga obsługuje sprzedaż w sklepie: płatność woła komendę
 * administracyjną, która trafia tutaj z własnym progiem i edycją.
 */
public class CosmeticService {

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage;
    private final InventoryOutbox outbox;
    private final CosmeticCatalog catalog;
    private final @Nullable CustomItemService customItems;

    public CosmeticService(@NotNull JavaPlugin plugin, @NotNull MiniMessage miniMessage,
                    @NotNull InventoryOutbox outbox, @NotNull CosmeticCatalog catalog,
                    @Nullable CustomItemService customItems) {
        this.plugin = plugin;
        this.miniMessage = miniMessage;
        this.outbox = outbox;
        this.catalog = catalog;
        this.customItems = customItems;
    }

    @NotNull CosmeticCatalog catalog() {
        return catalog;
    }

    /**
     * Wydaje części należne za miejsce w rankingu sezonu.
     *
     * <p>Outbox trzyma jedną dzierżawę na gracza, więc części idą <b>po kolei</b>,
     * każda dopiero po rozstrzygnięciu poprzedniej. {@code afterSettled} biegnie
     * raz, po ostatniej.
     */
    public void grantSeasonReward(@NotNull Player player, int seasonId, int rank,
                           @NotNull Runnable afterSettled) {
        List<String> pieces = catalog.piecesFor(seasonId, rank);
        if (pieces.isEmpty()) {
            afterSettled.run();
            return;
        }
        CosmeticCatalog.Collection collection = catalog.forSeason(seasonId).orElseThrow();
        grantSequentially(player, new ArrayDeque<>(pieces), collection,
                CosmeticCatalog.tierOf(rank), CosmeticCatalog.editionOf(seasonId),
                new AtomicBoolean(), afterSettled);
    }

    /**
     * Wydaje jedną część spoza rankingu — kanał sklepowy.
     *
     * @param tier    znacznik progu, np. {@code SKLEP}; musi pasować do wzorca
     *                {@code ItemPolicyMarkers}, inaczej oznaczenie rzuci wyjątkiem
     * @param edition znacznik edycji, np. {@code S3}
     */
    public void grantSingle(@NotNull Player player, @NotNull CosmeticCatalog.Collection collection,
                     @NotNull String pieceId, @NotNull String tier, @NotNull String edition,
                     @NotNull Runnable afterSettled) {
        Deque<String> single = new ArrayDeque<>();
        single.add(pieceId);
        grantSequentially(player, single, collection, tier, edition,
                new AtomicBoolean(), afterSettled);
    }

    private void grantSequentially(Player player, Deque<String> remaining,
                                   CosmeticCatalog.Collection collection,
                                   String tier, String edition, AtomicBoolean failureNotified,
                                   Runnable afterSettled) {
        String pieceId = remaining.poll();
        if (pieceId == null) {
            afterSettled.run();
            return;
        }
        ItemStack stamped = stamp(collection, pieceId, tier, edition);
        if (stamped == null) {
            warn("Nie udało się zbudować kosmetyki '" + pieceId + "' z kolekcji '"
                    + collection.id() + "' (próg " + tier + ", edycja " + edition
                    + "); pomijam tę część", player.getUniqueId(), null);
            notifyFailureOnce(player, failureNotified);
            grantSequentially(player, remaining, collection, tier, edition,
                    failureNotified, afterSettled);
            return;
        }
        // Delta zero: kosmetyka nagrodowa nic nie kosztuje, a nadanie nie może uznać konta.
        outbox.beginGrant(player, 0L, "cosmetic:" + UUID.randomUUID(), "cosmetic_grant",
                stamped, outcome -> {
                    report(player, collection, pieceId, outcome, failureNotified);
                    /*
                     * Kolejna część dopiero z następnego ticku encji: callback
                     * poprzedniej biegnie jeszcze przy trzymanej dzierżawie, więc
                     * zagnieżdżone nadanie dostałoby BUSY.
                     */
                    scheduleNext(player, remaining, collection, tier, edition,
                            failureNotified, afterSettled);
                });
    }

    private void scheduleNext(Player player, Deque<String> remaining,
                              CosmeticCatalog.Collection collection,
                              String tier, String edition, AtomicBoolean failureNotified,
                              Runnable afterSettled) {
        if (remaining.isEmpty()) {
            afterSettled.run();
            return;
        }
        try {
            var scheduled = player.getScheduler().runDelayed(plugin,
                    ignored -> grantSequentially(player, remaining, collection, tier,
                            edition, failureNotified, afterSettled),
                    () -> reportUndelivered(player, remaining, collection,
                            failureNotified, afterSettled),
                    ConvergenceRetryPolicy.FAST_DELAY_TICKS);
            if (scheduled == null) {
                reportUndelivered(player, remaining, collection, failureNotified,
                        afterSettled);
            }
        } catch (RuntimeException rejected) {
            warn("Nie udało się zaplanować kolejnej części z kolekcji '" + collection.id()
                    + "' (" + remaining + "); do ręcznego wydania",
                    player.getUniqueId(), rejected);
            notifyFailureOnce(player, failureNotified);
            afterSettled.run();
        }
    }

    /** Zamyka łańcuch, którego reszty nie da się już wydać — gracz dostaje jedną informację. */
    private void reportUndelivered(Player player, Deque<String> remaining,
                                   CosmeticCatalog.Collection collection,
                                   AtomicBoolean failureNotified,
                                   Runnable afterSettled) {
        warn("Nie wydano części kolekcji '" + collection.id() + "' (" + remaining
                + "); do ręcznego wydania", player.getUniqueId(), null);
        notifyFailureOnce(player, failureNotified);
        afterSettled.run();
    }

    /** Seam: budowa ItemStacka wymaga rejestru serwera. */
    @Nullable ItemStack stamp(@NotNull CosmeticCatalog.Collection collection,
                              @NotNull String pieceId, @NotNull String tier,
                              @NotNull String edition) {
        if (customItems == null) {
            return null;
        }
        ItemStack stack = customItems.create(pieceId).orElse(null);
        if (stack == null) {
            return null;
        }
        ItemPolicyMarkers.markCollectible(stack, tier, edition,
                collection.contentVersion(), true);
        return stack;
    }

    private void report(Player player, CosmeticCatalog.Collection collection, String pieceId,
                        InventoryOutbox.Outcome outcome, AtomicBoolean failureNotified) {
        switch (outcome) {
            case SUCCESS -> player.sendMessage(Ui.component(miniMessage,
                    "<dark_gray>[</dark_gray><light_purple><bold>KOLEKCJA</bold></light_purple>"
                            + "<dark_gray>]</dark_gray> <gray>Zdobyto część kolekcji</gray> "
                            + "<collection><gray>.</gray>",
                    Placeholder.component("collection",
                            Ui.component(miniMessage, collection.name()))));
            case DEFERRED -> player.sendMessage(Ui.component(miniMessage,
                    "<yellow>Część kolekcji jest zapisana. Zwolnij miejsce i wejdź ponownie, "
                            + "aby ją odebrać.</yellow>"));
            case BUSY, INSUFFICIENT, REJECTED, ERROR -> {
                warn("Nie wydano kosmetyki '" + pieceId + "' z kolekcji '"
                        + collection.id() + "' (" + outcome + "); do ręcznego wydania",
                        player.getUniqueId(), null);
                notifyFailureOnce(player, failureNotified);
            }
        }
    }

    /**
     * Ujednolicone ostrzeżenie o nieudanym wydaniu kosmetyki — zawsze WARNING
     * z kontekstem kolekcji i identyfikatorem gracza.
     */
    private void warn(@NotNull String context, @NotNull UUID playerId,
                      @Nullable Throwable err) {
        String message = "[kosmetyka sezonowa] " + context + "; gracz " + playerId;
        if (err != null) {
            plugin.getLogger().log(Level.WARNING, message, err);
        } else {
            plugin.getLogger().warning(message);
        }
    }

    /** Krótka czerwona linia dla gracza — najwyżej raz na jedno wydanie. */
    private void notifyFailureOnce(@NotNull Player player, @NotNull AtomicBoolean notified) {
        if (!player.isOnline() || !notified.compareAndSet(false, true)) {
            return;
        }
        player.sendMessage(Ui.component(miniMessage,
                "<red>Części kolekcji nie udało się wydać — zgłoś się do administracji.</red>"));
    }
}
