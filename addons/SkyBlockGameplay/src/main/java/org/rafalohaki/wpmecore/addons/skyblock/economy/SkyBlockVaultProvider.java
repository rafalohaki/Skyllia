package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Optional;

/** Synchronous Vault facade over the SQL ledger; cached reads are tick-safe. */
public final class SkyBlockVaultProvider {

    private static final String ASYNC_REQUIRED =
            "Mutacje ekonomii muszą być wykonywane poza wątkiem regionu Folia";
    private static final String ECONOMY_BUSY =
            "Ekonomia gracza odzyskuje poprzednią operację lub jest zajęta";
    private final LedgerService ledger;
    private final PlayerOperationCoordinator coordinator;
    private final InventoryOutbox outbox;

    public SkyBlockVaultProvider(@NotNull LedgerService ledger,
                          @NotNull PlayerOperationCoordinator coordinator,
                          @NotNull InventoryOutbox outbox) {
        this.ledger = ledger;
        this.coordinator = coordinator;
        this.outbox = outbox;
    }

    boolean isEnabled() { return true; }
    @NotNull String getName() { return "WpmeSkyBlock"; }
    boolean hasBankSupport() { return false; }
    int fractionalDigits() { return 0; }
    @NotNull String currencyNamePlural() { return "monet"; }
    @NotNull String currencyNameSingular() { return "moneta"; }
    @NotNull String format(double amount) { return String.format(Locale.forLanguageTag("pl-PL"), "%.0f monet", amount); }

    boolean hasAccount(@NotNull OfflinePlayer player) {
        return ledger.hasPlayerAccount(player.getUniqueId());
    }

    boolean hasAccount(@NotNull String name) {
        OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(name);
        return player != null && hasAccount(player);
    }

    boolean hasAccount(@NotNull OfflinePlayer player, @NotNull String world) { return hasAccount(player); }
    boolean hasAccount(@NotNull String name, @NotNull String world) { return hasAccount(name); }

    boolean createPlayerAccount(@NotNull OfflinePlayer player) {
        if (hasAccount(player)) {
            return false;
        }
        if (mutationOnTickThread(player)) {
            // The join listener creates accounts asynchronously. Vault's
            // synchronous boolean cannot truthfully report success before the
            // durable write finishes, so tick-thread callers fail closed.
            Optional<PlayerOperationCoordinator.Lease> lease = acquire(player.getUniqueId());
            if (lease.isEmpty()) {
                return false;
            }
            try {
                ledger.ensurePlayer(player.getUniqueId())
                        .whenComplete((ignored, failure) -> coordinator.release(lease.get()));
            } catch (RuntimeException rejected) {
                coordinator.release(lease.get());
            }
            return false;
        }
        Optional<PlayerOperationCoordinator.Lease> lease = acquire(player.getUniqueId());
        if (lease.isEmpty()) {
            return false;
        }
        try {
            // D4-fix: limit zamiast bezgranicznego join() — wątek Vault nie może
            // wisieć wiecznie na egzekutorze SQL.
            return ledger.ensurePlayer(player.getUniqueId()).get(10L, TimeUnit.SECONDS).applied();
        } catch (ExecutionException | TimeoutException failure) {
            return false;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            coordinator.release(lease.get());
        }
    }

    boolean createPlayerAccount(@NotNull String name) {
        OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(name);
        return player != null && createPlayerAccount(player);
    }

    boolean createPlayerAccount(@NotNull OfflinePlayer player, @NotNull String world) {
        return createPlayerAccount(player);
    }

    boolean createPlayerAccount(@NotNull String name, @NotNull String world) {
        return createPlayerAccount(name);
    }

    double getBalance(@NotNull OfflinePlayer player) {
        return ledger.playerBalance(player.getUniqueId());
    }

    double getBalance(@NotNull String name) {
        OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(name);
        return player == null ? 0.0 : getBalance(player);
    }

    double getBalance(@NotNull OfflinePlayer player, @NotNull String world) { return getBalance(player); }
    double getBalance(@NotNull String name, @NotNull String world) { return getBalance(name); }

    boolean has(@NotNull OfflinePlayer player, double amount) {
        long minor = minorUnits(amount);
        return minor >= 0L && ledger.playerBalance(player.getUniqueId()) >= minor;
    }

    boolean has(@NotNull String name, double amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(name);
        return player != null && has(player, amount);
    }

    boolean has(@NotNull OfflinePlayer player, @NotNull String world, double amount) {
        return has(player, amount);
    }

    boolean has(@NotNull String name, @NotNull String world, double amount) { return has(name, amount); }

    @NotNull Response withdrawPlayer(@NotNull OfflinePlayer player, double amount) {
        long minor = minorUnits(amount);
        long before = ledger.playerBalance(player.getUniqueId());
        if (minor < 0L) {
            return Response.fail(before, "Kwota musi być nieujemną liczbą całkowitą");
        }
        if (mutationOnTickThread(player)) {
            return Response.fail(before, ASYNC_REQUIRED);
        }
        Optional<PlayerOperationCoordinator.Lease> lease = acquire(player.getUniqueId());
        if (lease.isEmpty()) {
            return Response.fail(before, ECONOMY_BUSY);
        }
        try {
            LedgerDao.Mutation result = ledger.withdrawPlayer(player.getUniqueId(), minor,
                    "vault:withdraw:" + UUID.randomUUID(), "vault_withdraw").get(10L, TimeUnit.SECONDS);
            return result.applied()
                    ? Response.ok(minor, result.balance())
                    : Response.fail(result.balance(), result.insufficient()
                            ? "Brak wystarczających środków" : "Transakcja odrzucona");
        } catch (ExecutionException | TimeoutException failure) {
            return Response.fail(before, "Błąd trwałego zapisu transakcji");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return Response.fail(before, "Błąd trwałego zapisu transakcji");
        } finally {
            coordinator.release(lease.get());
        }
    }

    @NotNull Response withdrawPlayer(@NotNull String name, double amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(name);
        return player == null ? Response.fail(0L, "Nieznany gracz") : withdrawPlayer(player, amount);
    }

    @NotNull Response withdrawPlayer(@NotNull OfflinePlayer player, @NotNull String world, double amount) {
        return withdrawPlayer(player, amount);
    }

    @NotNull Response withdrawPlayer(@NotNull String name, @NotNull String world, double amount) {
        return withdrawPlayer(name, amount);
    }

    @NotNull Response depositPlayer(@NotNull OfflinePlayer player, double amount) {
        long minor = minorUnits(amount);
        long before = ledger.playerBalance(player.getUniqueId());
        if (minor < 0L) {
            return Response.fail(before, "Kwota musi być nieujemną liczbą całkowitą");
        }
        Optional<PlayerOperationCoordinator.Lease> lease = acquire(player.getUniqueId());
        if (lease.isEmpty()) {
            return Response.fail(before, ECONOMY_BUSY);
        }
        if (mutationOnTickThread(player)) {
            // Migracja Eco: efekty libreforge (give_money w nagrodach EcoQuests/EcoBattlepass)
            // i CrazyAuctions wołają Vault z wątku regionu. Depozyt nie może odpaść na saldzie,
            // więc idzie asynchronicznie, a fasada odpowiada optymistycznie.
            // ponytail: brak zwrotu przy błędzie SQL — tylko WARN w logu; wypłaty dalej wymagają
            // wątku poza tickiem (nie da się optymistycznie potwierdzić, że stać).
            String txId = "vault:deposit:" + UUID.randomUUID();
            try {
                ledger.depositPlayer(player.getUniqueId(), minor, txId, "vault_deposit")
                        .whenComplete((result, failure) -> {
                            coordinator.release(lease.get());
                            if (failure != null || result == null || !result.applied()) {
                                java.util.logging.Logger.getLogger(SkyBlockVaultProvider.class.getName())
                                        .warning("Depozyt Vault z wątku regionu nie zapisał się: " + txId
                                                + " gracz=" + player.getUniqueId() + " kwota=" + minor
                                                + (failure != null ? " " + failure : ""));
                            }
                        });
            } catch (RuntimeException rejected) {
                coordinator.release(lease.get());
                return Response.fail(before, "Błąd trwałego zapisu transakcji");
            }
            return Response.ok(minor, before + minor);
        }
        try {
            LedgerDao.Mutation result = ledger.depositPlayer(player.getUniqueId(), minor,
                    "vault:deposit:" + UUID.randomUUID(), "vault_deposit").get(10L, TimeUnit.SECONDS);
            return result.applied()
                    ? Response.ok(minor, result.balance())
                    : Response.fail(result.balance(), "Transakcja odrzucona");
        } catch (ExecutionException | TimeoutException failure) {
            return Response.fail(before, "Błąd trwałego zapisu transakcji");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return Response.fail(before, "Błąd trwałego zapisu transakcji");
        } finally {
            coordinator.release(lease.get());
        }
    }

    @NotNull Response depositPlayer(@NotNull String name, double amount) {
        OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(name);
        return player == null ? Response.fail(0L, "Nieznany gracz") : depositPlayer(player, amount);
    }

    @NotNull Response depositPlayer(@NotNull OfflinePlayer player, @NotNull String world, double amount) {
        return depositPlayer(player, amount);
    }

    @NotNull Response depositPlayer(@NotNull String name, @NotNull String world, double amount) {
        return depositPlayer(name, amount);
    }

    @NotNull Response createBank(String name, OfflinePlayer player) { return Response.notImplemented(); }
    @NotNull Response createBank(String name, String player) { return Response.notImplemented(); }
    @NotNull Response deleteBank(String name) { return Response.notImplemented(); }
    @NotNull Response bankBalance(String name) { return Response.notImplemented(); }
    @NotNull Response bankHas(String name, double amount) { return Response.notImplemented(); }
    @NotNull Response bankWithdraw(String name, double amount) { return Response.notImplemented(); }
    @NotNull Response bankDeposit(String name, double amount) { return Response.notImplemented(); }
    @NotNull Response isBankOwner(String name, OfflinePlayer player) { return Response.notImplemented(); }
    @NotNull Response isBankOwner(String name, String player) { return Response.notImplemented(); }
    @NotNull Response isBankMember(String name, OfflinePlayer player) { return Response.notImplemented(); }
    @NotNull Response isBankMember(String name, String player) { return Response.notImplemented(); }
    @NotNull List<String> getBanks() { return List.of(); }

    private @NotNull Optional<PlayerOperationCoordinator.Lease> acquire(UUID playerId) {
        if (!outbox.isEconomyReady(playerId)) {
            return Optional.empty();
        }
        return coordinator.tryAcquire(playerId, false);
    }

    private static boolean mutationOnTickThread(@NotNull OfflinePlayer player) {
        Player online = player.getPlayer();
        // Never block any tick thread on the synchronous Vault facade. The global tick
        // thread and the player's own region thread are both tick threads; an offline
        // player has no region, so only the global check applies then. (Bukkit
        // isPrimaryThread() is intentionally avoided — it is not a Folia contract.)
        return Bukkit.isGlobalTickThread()
                || (online != null && Bukkit.isOwnedByCurrentRegion(online));
    }

    private static long minorUnits(double amount) {
        if (!Double.isFinite(amount) || amount < 0.0 || amount != Math.rint(amount)
                || amount > LedgerDao.MAX_BALANCE) {
            return -1L;
        }
        return (long) amount;
    }

    enum ResponseType { SUCCESS, FAILURE, NOT_IMPLEMENTED }

    record Response(double amount, double balance, @NotNull ResponseType type,
                    @Nullable String errorMessage) {
        static @NotNull Response ok(long amount, long balance) {
            return new Response(amount, balance, ResponseType.SUCCESS, null);
        }

        static @NotNull Response fail(long balance, @NotNull String message) {
            return new Response(0, balance, ResponseType.FAILURE, message);
        }

        static @NotNull Response notImplemented() {
            return new Response(0, 0, ResponseType.NOT_IMPLEMENTED,
                    "Banki Vault nie są używane; wspólny bank wyspy jest w /bank");
        }
    }
}
