package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import java.util.UUID;

/**
 * Właściciel wyspy w kierunku odwrotnym do reszty adaptera.
 *
 * <p>Cały {@link SkylliaIntegration} pyta „jaką wyspę ma ten gracz". Ranking
 * sezonowy liczy się jednak per wyspa i potrzebuje kierunku przeciwnego, żeby
 * pokazać czyjąś głowę i nick.
 *
 * @param playerId UUID Mojanga właściciela
 * @param name     ostatnia znana nazwa; Skyllia trzyma ją przy członku, więc
 *                 nick działa też dla graczy, których nie ma na serwerze
 */
public record IslandOwner(UUID playerId, String name) {
}
