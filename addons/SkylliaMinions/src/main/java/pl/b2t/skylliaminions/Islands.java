package pl.b2t.skylliaminions;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/** Cienki adapter nad SkylliaAPI — zamiast SkylliaIntegration z WpmeCore. */
public final class Islands {

    private Islands() {
    }

    public static @Nullable Island islandOf(@NotNull UUID playerId) {
        return SkylliaAPI.getIslandByPlayerId(playerId);
    }

    public static @Nullable Island islandAt(@NotNull Location location) {
        return SkylliaAPI.getIslandByChunk(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    /** Rola gracza na wyspie; UNKNOWN gdy nie jest członkiem. */
    public static @NotNull RoleType roleOf(@NotNull Island island, @NotNull UUID playerId) {
        for (Players member : island.getMembers()) {
            if (member.getMojangId().equals(playerId)) {
                return member.getRoleType();
            }
        }
        return RoleType.VISITOR;
    }

    /** Wpme canWithdraw(): tylko właściciel/zastępca mogą ruszać walutę wyspy. */
    public static boolean canWithdraw(@NotNull Island island, @NotNull UUID playerId) {
        RoleType role = roleOf(island, playerId);
        return role == RoleType.OWNER || role == RoleType.CO_OWNER;
    }

    public static @NotNull Optional<UUID> islandIdOf(@NotNull UUID playerId) {
        Island island = islandOf(playerId);
        return island == null ? Optional.empty() : Optional.of(island.getId());
    }
}
