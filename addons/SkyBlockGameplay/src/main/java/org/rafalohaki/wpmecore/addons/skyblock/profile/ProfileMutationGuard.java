package org.rafalohaki.wpmecore.addons.skyblock.profile;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * M1-D: leave/kick/delete/reset blocks new outbox/ledger mutations.
 * <p>
 * Any new mutation (ledger deposit/withdraw, inventory outbox grant/remove)
 * must check this guard first. If a blocking transition (DELETE/RESET/LEAVE/KICK
 * with result IS NULL) exists for the profile/player, the mutation is rejected
 * fail-closed. This prevents double-spend or orphan islands when a delete is
 * in progress.
 * <p>
 * The guard is checked both for the active profile (islandId) and for the player
 * across all profiles (to handle race where profileId not yet known).
 * <p>
 * Strażnik odpowiada wyłącznie obietnicą. Nie ma tu wariantu blokującego i nie
 * wolno go dorabiać: odpowiedź to zapytanie do tej samej puli SQL, na której
 * bywają wołający, więc {@code get()} na gorącej ścieżce zagładzał pulę i przy
 * dwóch wątkach potrafił nie doczekać nigdy.
 */
public final class ProfileMutationGuard {

    /**
     * Bezpiecznik na zawieszoną bazę, nie budżet czasu na gorącej ścieżce —
     * nikt na to nie czeka wątkiem. Dawne 600 ms było krótsze niż samo
     * {@code busy_timeout=5000} SQLite'a, więc zdrowe zapytanie pod
     * rywalizacją o plik regularnie „nie zdążyło" i fail-closed zjadał nagrodę.
     */
    public static final long VERIFY_TIMEOUT_SECONDS = 15L;

    private final ProfileTransitionDao transitionDao;

    public ProfileMutationGuard(@NotNull ProfileTransitionDao transitionDao) {
        this.transitionDao = transitionDao;
    }

    /**
     * Returns true if mutations are blocked for this profile/player.
     */
    public @NotNull CompletableFuture<Boolean> isBlocked(@NotNull UUID profileId, @NotNull UUID playerUuid) {
        return transitionDao.hasBlockingTransition(profileId, playerUuid)
                .orTimeout(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public @NotNull CompletableFuture<Boolean> isBlockedForPlayer(@NotNull UUID playerUuid) {
        return transitionDao.hasBlockingTransitionForPlayer(playerUuid)
                .orTimeout(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public static final class BlockedMutationException extends RuntimeException {
        public BlockedMutationException(String msg) { super(msg); }
    }
}
