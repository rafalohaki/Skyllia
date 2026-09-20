package org.rafalohaki.wpmecore.addons.skyblock.perks;

import org.bukkit.permissions.Permissible;
import org.jetbrains.annotations.NotNull;

/**
 * Perki rang na SkyBlocku czytane z uprawnień (rangi są sieciowe w LuckPerms,
 * uprawnienia per serwer — kontekst {@code server=skyblock}). Lekkie P2W bez PvP:
 * rozbudowa wyspy/konta, nie przewaga nad innymi graczami.
 *
 * <ul>
 *   <li>{@code skyblockgameplay.minions.extra.<n>} — n dodatkowych slotów minionków (1..10)</li>
 *   <li>{@code skyblockgameplay.sell.bonus.<pct>} — +pct % do sprzedaży różdżką (1..50)</li>
 *   <li>{@code skyblockgameplay.fly} — lot na własnej wyspie (/latanie; alias /fly
 *       tylko tam, gdzie nie ma AdminTools)</li>
 *   <li>{@code skyblockgameplay.daily.bonus.<pct>} — +pct % monet z codziennej nagrody
 *       (1..100; vip 10, svip 20, sponsor 30, legend 50 — przypisanie w LuckPerms)</li>
 *   <li>zniżki kuźni: {@code forge.yml → discounts} (ForgeConfig)</li>
 * </ul>
 * Węzły liczbowe: liczy się najwyższy przyznany, więc rangi wystarczy nadać jednym węzłem.
 */
public final class RankPerks {

    static final String MINION_EXTRA_PREFIX = "skyblockgameplay.minions.extra.";
    static final String SELL_BONUS_PREFIX = "skyblockgameplay.sell.bonus.";
    public static final String FLY = "skyblockgameplay.fly";
    static final String DAILY_BONUS_PREFIX = "skyblockgameplay.daily.bonus.";
    static final int MINION_EXTRA_MAX = 10;
    static final int SELL_BONUS_MAX = 50;
    static final int DAILY_BONUS_MAX = 100;

    private RankPerks() {
    }

    public static int minionExtraSlots(@NotNull Permissible who) {
        return highestNumbered(who, MINION_EXTRA_PREFIX, MINION_EXTRA_MAX);
    }

    public static int sellBonusPercent(@NotNull Permissible who) {
        return highestNumbered(who, SELL_BONUS_PREFIX, SELL_BONUS_MAX);
    }

    public static int dailyBonusPercent(@NotNull Permissible who) {
        return highestNumbered(who, DAILY_BONUS_PREFIX, DAILY_BONUS_MAX);
    }

    public static boolean canFly(@NotNull Permissible who) {
        return who.hasPermission(FLY);
    }

    // ponytail: skan malejący, max 50 zapytań o uprawnienie na wywołanie — wystarcza,
    // bo wywołania są per klik/sprzedaż, nie per tick.
    static int highestNumbered(@NotNull Permissible who, @NotNull String prefix, int max) {
        for (int n = max; n >= 1; n--) {
            if (who.hasPermission(prefix + n)) {
                return n;
            }
        }
        return 0;
    }
}
