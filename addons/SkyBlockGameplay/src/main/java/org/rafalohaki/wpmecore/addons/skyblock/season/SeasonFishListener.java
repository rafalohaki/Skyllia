package org.rafalohaki.wpmecore.addons.skyblock.season;

import com.oheers.fish.api.events.EMFFishCaughtEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

/**
 * M2.5/P1-1: punkty sezonowe za ryby z EvenMoreFish. Rzadkość → punkty
 * z config.yml (sekcja season.fish-points, parsowana fail-closed przez
 * {@link SeasonFishPoints}), a dobowa pula kanału {@value #CHANNEL}
 * z capem pilnowana przez {@link SeasonDailyPointsService}. Idempotencja
 * po operationId (uuid ryby + timestamp) siedzi w tabeli dziennej.
 *
 * <p>Questy FISH (sq_fish_1) liczy SeasonQuestService z vanilla PlayerFishEvent —
 * ten listener DODAJE punkty za wartość złowionej ryby, nie dubli liczników.
 *
 * <p>Rejestracja przez {@link #registerSafely} jest odporna na niezgodne buildy
 * EvenMoreFish: brak klasy {@code EMFFishCaughtEvent} nie wywala startu pluginu,
 * tylko wyłącza punkty za ryby z jednym wpisem SEVERE.
 */
public final class SeasonFishListener implements Listener {

    /** Kanał dobowego capu dla punktów z wędki (season.daily-points-caps.fish). */
    public static final String CHANNEL = "fish";

    private final SeasonDailyPointsService dailyPoints;
    private final SeasonFishPoints fishPoints;

    public SeasonFishListener(@NotNull SeasonDailyPointsService dailyPoints,
                              @NotNull SeasonFishPoints fishPoints) {
        this.dailyPoints = dailyPoints;
        this.fishPoints = fishPoints;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEmfFishCaught(@NotNull EMFFishCaughtEvent event) {
        var fish = event.getFish();
        if (fish == null || fish.getRarity() == null) return;
        Integer pts = fishPoints.pointsFor(fish.getRarity().getId());
        if (pts == null) return;
        var player = event.getPlayer();
        if (player == null) return;
        String operationId = "emf-fish:" + player.getUniqueId() + ":" + System.currentTimeMillis();
        // Cap dnia rozstrzyga serwis: 0 = limit wyczerpany albo powtórka —
        // wtedy nic się nie dzieje (cichy no-op, bez wpisu SEVERE).
        dailyPoints.awardDaily(player.getUniqueId(), CHANNEL, pts, operationId);
    }

    /**
     * Rejestruje listener punktów za ryby, izolując błędy niezgodnego API
     * EvenMoreFish ({@link Throwable}, np. {@code NoClassDefFoundError}
     * dla {@code EMFFishCaughtEvent}) od reszty startu pluginu.
     *
     * @return true gdy nasłuch zarejestrowany i aktywny
     */
    public static boolean registerSafely(@NotNull org.bukkit.plugin.Plugin plugin,
                                         @NotNull SeasonDailyPointsService dailyPoints,
                                         @NotNull SeasonFishPoints fishPoints) {
        try {
            plugin.getServer().getPluginManager().registerEvents(
                    new SeasonFishListener(dailyPoints, fishPoints), plugin);
            plugin.getLogger().info("Sezonowe punkty za ryby: nasłuch aktywny");
            return true;
        } catch (Throwable t) {
            // Jednolinijkowy SEVERE: powód bez znaków nowej linii (logi jednowierszowe).
            String reason = String.valueOf(t).replaceAll("[\\r\\n]+", " ");
            plugin.getLogger().severe(
                    "Sezonowe punkty za ryby WYŁĄCZONE (niezgodne API EvenMoreFish): "
                            + reason);
            return false;
        }
    }
}
