package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.ProfileStateService;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SKY-2 regresja: {@link SkylliaIntegration#purgeSoftDeletedIsland(UUID)} zwraca -1,
 * gdy czyszczenie soft-deleted wyspy padnie na SQLException. Coordinator MUSI wtedy
 * przerwać create z wyraźnym błędem — wcześniej wynik był ignorowany i gracz
 * dostawał mylące "Masz juz aktywna wyspe" mimo braku wyspy.
 *
 * <p>Czysty Mockito bez MockBukkit: domyślny profil buildu usuwa MockBukkit
 * z classpath testów (mockbukkit-compat odpala je osobno), więc klasa nie może
 * referencjonować jego typów nawet w polach.
 */
class IslandCreationCoordinatorPurgeFailureTest {

    private SkylliaIntegration skyllia;
    private ProfileStateService profiles;
    private IslandCreationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("PurgeTest"));
        skyllia = mock(SkylliaIntegration.class);
        profiles = mock(ProfileStateService.class);
        coordinator = new IslandCreationCoordinator(plugin, skyllia, profiles,
                new IslandModeFlags(true, false, false));
    }

    /** Purge -1 → FAILURE z odrębnym komunikatem, bez fallthrough do "Masz juz aktywna wyspe". */
    @Test
    void purgeFailureSurfacesDistinctFailureInsteadOfAlreadyHasIsland() throws Exception {
        when(skyllia.purgeSoftDeletedIsland(any(UUID.class))).thenReturn(-1);
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);

        IslandCreationCoordinator.CreateResult result =
                coordinator.create(player, "classic").get(5, TimeUnit.SECONDS);

        assertEquals(IslandCreationCoordinator.CreateResult.Kind.FAILURE, result.kind());
        assertFalse(result.message().isBlank());
        assertNotEquals(IslandCreationCoordinator.CreateResult.alreadyHasIsland().message(),
                result.message(),
                "purge failure nie może przejść na komunikat aktywnej wyspy");
        // Wczesny powrót: check islandOf nie powinien w ogóle zajść po nieudanym purge.
        verify(skyllia, never()).islandOf(any(UUID.class));
        // Żadna operacja nie została zarejestrowana (brak zombie stanu CREATING).
        assertNull(coordinator.operationOf(playerId));
    }

    /** Purge OK (0) nie blokuje przepływu: dalsze gate'i działają jak wcześniej. */
    @Test
    void purgeSuccessKeepsExistingFlowGates() throws Exception {
        when(skyllia.purgeSoftDeletedIsland(any(UUID.class))).thenReturn(0);
        UUID islandId = UUID.randomUUID();

        // Aktywna wyspa po udanym purge → nadal ALREADY_HAS_ISLAND.
        when(skyllia.islandOf(any(UUID.class))).thenReturn(Optional.of(
                new org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView(
                        new org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot(
                                islandId, null, null, null, null, 0L),
                        org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole.OWNER)));
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        assertEquals(IslandCreationCoordinator.CreateResult.Kind.ALREADY_HAS_ISLAND,
                coordinator.create(player, "classic").get(5, TimeUnit.SECONDS).kind());
    }
}
