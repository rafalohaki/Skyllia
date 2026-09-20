package org.rafalohaki.wpmecore.addons.skyblock.profile;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Profile-isolated wallet service.
 * Replaces global player:<uuid> ledger for SkyBlock wallets/banks when profile isolation is active.
 * Each (profile_id, player_uuid) is an independent domain — balances do not leak across profiles or modes.
 */
public final class ProfileAccountService {

    private final ProfileAccountDao dao;
    private final long startingBalance;
    private volatile ProfileMutationGuard mutationGuard;

    public ProfileAccountService(@NotNull ProfileAccountDao dao, long startingBalance) {
        this.dao = dao;
        this.startingBalance = startingBalance;
    }

    public void setMutationGuard(@NotNull ProfileMutationGuard guard) {
        this.mutationGuard = guard;
    }

    /**
     * Bramka strażnika mutacji przed zapisem salda — bez blokowania wątku.
     *
     * <p>Dawniej było tu {@code get(600 ms)}. Odpowiedź strażnika to zapytanie
     * do tej samej puli SQL co sama mutacja, więc czekanie na nią wątkiem
     * puli zagładzało pulę, a fail-closed po limicie czasu odrzucał operację,
     * której nikt nie blokował.
     *
     * <p>Polityka bez zmian: gdy naprawdę nie da się zweryfikować, mutacja nie
     * idzie. Zmienia się tylko sposób czekania i treść powodu.
     */
    private <T> @NotNull CompletableFuture<T> guarded(
            @NotNull UUID profileId, @NotNull UUID playerUuid,
            @NotNull Supplier<CompletableFuture<T>> mutation) {
        ProfileMutationGuard guard = this.mutationGuard;
        if (guard == null) {
            return mutation.get();
        }
        return guard.isBlocked(profileId, playerUuid)
                .handle((blocked, failure) -> {
                    if (failure != null) {
                        throw new ProfileMutationGuard.BlockedMutationException(
                                "Nie udało się zweryfikować strażnika mutacji dla profilu " + profileId
                                        + " gracza " + playerUuid + " — wstrzymuję fail-closed: "
                                        + rootCause(failure));
                    }
                    if (Boolean.TRUE.equals(blocked)) {
                        throw new ProfileMutationGuard.BlockedMutationException(
                                "Mutacje wstrzymane dla profilu " + profileId + " gracza " + playerUuid
                                        + " — trwa opuszczanie/wyrzucenie/usunięcie/reset wyspy");
                    }
                    return (Void) null;
                })
                .thenCompose(ignored -> mutation.get());
    }

    /** Powód po ludzku: {@code CompletionException} owija właściwy wyjątek. */
    static @NotNull String rootCause(@NotNull Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        return cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    public @NotNull CompletableFuture<Long> balance(@NotNull UUID profileId, @NotNull UUID playerUuid) {
        return dao.balance(profileId, playerUuid);
    }

    public @NotNull CompletableFuture<Long> ensure(@NotNull UUID profileId, @NotNull UUID playerUuid) {
        return dao.ensureAccount(profileId, playerUuid, startingBalance);
    }

    public @NotNull CompletableFuture<ProfileAccountDao.ProfileMutation> deposit(
            @NotNull UUID profileId, @NotNull UUID playerUuid, long amount,
            @NotNull String transactionId, @NotNull String reason) {
        return guarded(profileId, playerUuid, () ->
                dao.mutate(profileId, playerUuid, startingBalance, amount, transactionId, reason));
    }

    public @NotNull CompletableFuture<ProfileAccountDao.ProfileMutation> withdraw(
            @NotNull UUID profileId, @NotNull UUID playerUuid, long amount,
            @NotNull String transactionId, @NotNull String reason) {
        return guarded(profileId, playerUuid, () ->
                dao.mutate(profileId, playerUuid, startingBalance, Math.negateExact(amount),
                        transactionId, reason));
    }

    // Legacy bridge: profileId == islandId for wallets that are tied to active island
    public @NotNull CompletableFuture<ProfileAccountDao.ProfileMutation> mutate(
            @NotNull UUID profileId, @NotNull UUID playerUuid, long delta,
            @NotNull String transactionId, @NotNull String reason) {
        return guarded(profileId, playerUuid, () ->
                dao.mutate(profileId, playerUuid, startingBalance, delta, transactionId, reason));
    }
}
