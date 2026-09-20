package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Flagi capability adaptera. Każda luka fasady SkylliaAPI = jawna flaga (§2.9):
 * jeśli operacja nie jest osiągalna na przypiętym JAR, capability = false i adapter
 * zwraca NOT_SUPPORTED / readiness false, zamiast refleksji po klasach wewnętrznych.
 */
public final class IslandCapabilities {

    public enum Flag {
        CREATE, DELETE, TRANSFER, MEMBER_MANAGE, ACCESS, WARP, BIOME,
        LIFECYCLE_GUARD, CACHE_ONLY_LOOKUP, AUTHORITATIVE_ROLE
    }

    private final Set<Flag> enabled;

    private IslandCapabilities(Set<Flag> enabled) {
        // defensywna kopia — niezmiennik
        this.enabled = EnumSet.copyOf(enabled);
    }

    public static IslandCapabilities allEnabled() {
        return new IslandCapabilities(EnumSet.allOf(Flag.class));
    }

    public static IslandCapabilities none() {
        return new IslandCapabilities(EnumSet.noneOf(Flag.class));
    }

    public static IslandCapabilities of(Flag... flags) {
        Set<Flag> s = EnumSet.noneOf(Flag.class);
        Collections.addAll(s, flags);
        return new IslandCapabilities(s);
    }

    public boolean supports(Flag flag) {
        return enabled.contains(flag);
    }
}
