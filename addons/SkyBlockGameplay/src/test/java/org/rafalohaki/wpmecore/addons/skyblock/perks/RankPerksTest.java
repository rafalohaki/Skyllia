package org.rafalohaki.wpmecore.addons.skyblock.perks;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RankPerksTest {

    @Test
    void highestGrantedNumberedNodeWins() {
        Player vip = mock(Player.class);
        when(vip.hasPermission(anyString())).thenReturn(false);
        when(vip.hasPermission("skyblockgameplay.minions.extra.2")).thenReturn(true);
        when(vip.hasPermission("skyblockgameplay.minions.extra.1")).thenReturn(true);
        when(vip.hasPermission("skyblockgameplay.sell.bonus.10")).thenReturn(true);

        assertEquals(2, RankPerks.minionExtraSlots(vip));
        assertEquals(10, RankPerks.sellBonusPercent(vip));
        assertFalse(RankPerks.canFly(vip));

        Player nobody = mock(Player.class);
        when(nobody.hasPermission(anyString())).thenReturn(false);
        assertEquals(0, RankPerks.minionExtraSlots(nobody));
        assertEquals(0, RankPerks.sellBonusPercent(nobody));
    }

    @Test
    void onlyIslandMembersMayFly() {
        assertTrue(IslandFlyModule.isIslandMember(IslandRole.MEMBER));
        assertTrue(IslandFlyModule.isIslandMember(IslandRole.OWNER));
        assertFalse(IslandFlyModule.isIslandMember(IslandRole.VISITOR));
        assertFalse(IslandFlyModule.isIslandMember(IslandRole.BAN));
        assertFalse(IslandFlyModule.isIslandMember(IslandRole.UNKNOWN));
    }
}
