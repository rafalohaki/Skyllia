/**
 * Jedyny pakiet SkyBlockGameplay znający typy Skyllii. Moduły ekonomiczne, progresji
 * i GUI zależą wyłącznie od niezmienników Wpme stąd: {@link IslandSnapshot},
 * {@link IslandMemberSnapshot}, {@link IslandRole}, {@link IslandBounds},
 * {@link IslandCapabilities}, {@link LifecycleState}.
 *
 * <p>Reguła R-API-01 egzekwowana testem {@code ImportRuleTest} (ArchUnit + source-scan):
 * żadna klasa poza tym pakietem nie może typowo zależeć od {@code fr.euphyllia.skyllia..}
 * ani odwoływać się do niej refleksyjnie.
 */
package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;
