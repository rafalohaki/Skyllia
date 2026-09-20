package org.rafalohaki.wpmecore.addons.skyblock.migration;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Dry-run / migration report for M1-B backfill.
 * Covers disabled/locked, missing owners, multiple memberships, accounts without clear profile, and checksums.
 */
public record BackfillReport(
        long checksumBefore,
        long checksumAfter,
        @NotNull List<UUID> disabledOrLockedIslands,
        @NotNull List<UUID> missingOwners,
        @NotNull List<UUID> multipleMembershipPlayers,
        @NotNull List<String> accountsWithoutProfile,
        int islandsBackfilled,
        int accountsMigrated,
        int ambiguousAccounts,
        boolean checksumMatches) {

    public static @NotNull BackfillReport empty(long checksumBefore) {
        return new BackfillReport(
                checksumBefore, checksumBefore,
                List.of(), List.of(), List.of(), List.of(),
                0, 0, 0, true);
    }

    public boolean hasIssues() {
        return !disabledOrLockedIslands.isEmpty()
                || !missingOwners.isEmpty()
                || !multipleMembershipPlayers.isEmpty()
                || !accountsWithoutProfile.isEmpty()
                || ambiguousAccounts > 0;
    }

    @Override
    public @NotNull String toString() {
        return "BackfillReport{" +
                "checksumBefore=" + checksumBefore +
                ", checksumAfter=" + checksumAfter +
                ", checksumMatches=" + checksumMatches +
                ", disabledOrLocked=" + disabledOrLockedIslands.size() +
                ", missingOwners=" + missingOwners.size() +
                ", multipleMemberships=" + multipleMembershipPlayers.size() +
                ", accountsWithoutProfile=" + accountsWithoutProfile.size() +
                ", islandsBackfilled=" + islandsBackfilled +
                ", accountsMigrated=" + accountsMigrated +
                ", ambiguous=" + ambiguousAccounts +
                '}';
    }
}
