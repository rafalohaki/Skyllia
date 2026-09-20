package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;

/**
 * Typowany adapter Skyllii (read-side). Jedyna powierzchnia, jaką widzą konsumenci
 * poza {@code skylliaintegration}. Implementacja {@code SkylliaIntegrationImpl} kompiluje
 * się do publicznego artefaktu {@code fr.euphyllia.skyllia:api} — wyłącznie fasada
 * statyczna {@link fr.euphyllia.skyllia.api.SkylliaAPI} i typy {@code api.skyblock.*}.
 * Zero zależności od klas wewnętrznych Skyllii.
 *
 * <p>Operacje mutujące (create/delete/transfer/member/access/warp/biome) dojdą w Planie 2b/3.
 */
public interface SkylliaIntegration {

    /** Wyspa gracza + jego rola (krótki TTL cache nad fasadą SkylliaAPI). */
    Optional<IslandView> islandOf(UUID player);

    /** Wyspa gracza, gdy lokacja leży wewnątrz regionu (cache + replikacja geometrii). */
    Optional<IslandView> islandAt(UUID player, Location location);

    /** Tylko id wyspy gracza (powiadomienia, bramki członkostwa). */
    Optional<UUID> cachedIslandIdOf(UUID player);

    /**
     * AUTHORITATIVE_ASYNC: właściciel wyspy po jej identyfikatorze.
     *
     * <p>Sięga do bazy Skyllii, więc nie wolno jej wołać z wątku tickowego —
     * implementacja to egzekwuje wyjątkiem, tak samo jak przy wypłacie.
     */
    Optional<IslandOwner> ownerOf(UUID islandId);

    /**
     * AUTHORITATIVE_ASYNC: czy wyspa nadal istnieje u Skyllii.
     *
     * <p>Fail-closed: przy jakimkolwiek błędzie odpowiada {@code true}. Wołający
     * (ProfileRecoveryService) na podstawie {@code false} kasuje profil całej wyspy,
     * więc „nie wiem” musi znaczyć „nie ruszaj”.
     */
    default boolean islandExists(UUID islandId) {
        return ownerOf(islandId).isPresent();
    }

    /** AUTHORITATIVE_ASYNC: czy gracz może wypłacić z banku wyspy. Decyduje przed commitem. */
    boolean authoritativeCanWithdraw(UUID player, UUID island);

    /** AUTHORITATIVE_ASYNC: rola gracza na wyspie (re-check przed każdą mutacją GUI). */
    default IslandRole authoritativeRole(UUID player, UUID islandId) {
        return IslandRole.UNKNOWN;
    }

    /** AUTHORITATIVE_ASYNC: czy gracz jest OWNER (do operacji destrukcyjnych). */
    default boolean authoritativeIsOwner(UUID player, UUID islandId) {
        return authoritativeRole(player, islandId) == IslandRole.OWNER;
    }

    /** AUTHORITATIVE_ASYNC: tworzy wyspę przez SkylliaAPI (bez rekurencyjnego dispatch). */
    default boolean createIsland(UUID playerId, String template, String playerName) {
        return false;
    }

    /** AUTHORITATIVE: członkowie wyspy. */
    default List<IslandMemberSnapshot> membersOf(UUID islandId) {
        return List.of();
    }

    /** AUTHORITATIVE: zbanowani członkowie. */
    default List<IslandMemberSnapshot> bannedMembersOf(UUID islandId) {
        return List.of();
    }

    /** AUTHORITATIVE: warpy wyspy. */
    default List<WarpSnapshot> warpsOf(UUID islandId) {
        return List.of();
    }

    /** AUTHORITATIVE: czy wyspa jest prywatna. */
    default boolean isPrivateIsland(UUID islandId) {
        return false;
    }

    /** AUTHORITATIVE: ustawia prywatność. */
    default boolean setPrivateIsland(UUID islandId, boolean isPrivate) {
        return false;
    }

    /**
     * Mnoży promień wyspy przez {@code factor} (perk prestiżu — mutacja trwała
     * w Skylli, przeżywa restart). {@code false}, gdy wyspy brak albo Skyllia
     * odrzuciła zmianę (np. limit maksymalnego rozmiaru).
     */
    default boolean multiplyIslandSize(UUID islandId, double factor) {
        return false;
    }

    /**
     * Dokłada {@code delta} slotów członków wyspy (perk prestiżu).
     * {@code false}, gdy wyspy brak.
     */
    default boolean addIslandMemberSlots(UUID islandId, int delta) {
        return false;
    }

    /** AUTHORITATIVE: czy ma warp visit. */
    default boolean hasVisit(UUID islandId) {
        return false;
    }

    /** AUTHORITATIVE: lista nazw biomów. */
    default List<String> biomeNames() {
        return List.of("PLAINS","DESERT","FOREST","SNOWY_PLAINS");
    }

    /** AUTHORITATIVE: nazwa biomu wyspy (centrum). */
    default String biomeOf(UUID islandId) {
        return "PLAINS";
    }

    /** AUTHORITATIVE: ustawia biom wyspy. */
    default boolean setBiome(UUID islandId, String biomeName, org.bukkit.World world) {
        return false;
    }

    /** AUTHORITATIVE: środek wyspy. */
    default Optional<Location> islandCenterLocation(UUID islandId, org.bukkit.World world) {
        return Optional.empty();
    }

    /** Cache + authoritative: odśwież island view cache. */
    default void invalidateIslandCache(UUID playerId) { }

    /**
     * Po SkylliaAPI.createIsland (które — w odróżnieniu od wbudowanego /is create —
     * nie tworzy warpów) zakłada warp „home” oraz spawn wyspy na podstawie
     * zweryfikowanego anchora powierzchni (blok powierzchni; warp ląduje 0.5 nad
     * nim, tak by HomeSubCommand i swoje +0.5Y stawiały stopy dokładnie na topie).
     */
    default boolean setupIslandHome(java.util.UUID playerId, Location surfaceAnchor) {
        return false;
    }

    /**
     * Twarde usunięcie świeżo utworzonej wyspy, której świat nie przeszedł
     * weryfikacji paste (pustka) — czyści wiersze Skyllii niezależnie od flagi
     * disable, żeby gracz mógł natychmiast spróbować utworzyć wyspę ponownie.
     */
    default boolean discardBrokenIsland(java.util.UUID playerId) {
        return false;
    }

    /** Zestaw capability rozstrzygnięty na starcie. */
    IslandCapabilities capabilities();

    /** Czy podany świat jest światem wyspowym Skyllii. */
    boolean isSkyblockWorld(String worldName);

    /**
     * SKY-2: czyści soft-deleted wyspę gracza (Skyllia zostawia wiersz islands,
     * PK = UUID właściciela — bez tego ponowny create pada na UNIQUE, a check
     * islandOf widzi nieistniejącą wyspę). Wywoływać przed check'iem "ma wyspę".
     * @return liczba usuniętych wierszy islands (0 = nic do czyszczenia, -1 = błąd)
     */
    default int purgeSoftDeletedIsland(java.util.UUID playerId) { return 0; }

    /** M1-A: wire IdentityService for conflict-gated bank/withdraw. No-op if not supported. */
    default void setIdentityService(org.rafalohaki.wpmecore.api.identity.IdentityService identity) { }
}
