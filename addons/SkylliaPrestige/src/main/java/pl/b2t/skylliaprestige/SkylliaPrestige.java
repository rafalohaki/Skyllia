package pl.b2t.skylliaprestige;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Addon Skylli: Prestiż Wyspy — poziomy kupowane z banku wyspy, perki
 * mechaniczne (sloty minionów, luck na kryształy, rozmiar, członkowie)
 * i tytuły kosmetyczne. Stan leży w IslandCustomDataQuery (dane per-wyspa
 * w bazie Skylli), więc migruje z wyspą i przeżywa restarty bez osobnej tabeli.
 */
public final class SkylliaPrestige extends JavaPlugin {

    private PrestigeService service;

    public PrestigeService prestige() {
        return service;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        service = new PrestigeService(this, loadSettings());
        getLogger().info("SkylliaPrestige enabled");
    }

    @Override
    public void onDisable() {
    }

    private PrestigeService.Settings loadSettings() {
        var c = getConfig();
        return new PrestigeService.Settings(
                c.getInt("prestige.max-level", 10),
                c.getLong("prestige.base-cost", 50000L),
                c.getDouble("prestige.cost-multiplier", 1.6),
                c.getStringList("prestige.title-per-level"),
                c.getInt("prestige.perks.minion-slots-every-levels", 2),
                c.getDouble("prestige.perks.crystal-luck-percent-per-level", 4.0),
                c.getDouble("prestige.perks.size-percent-per-level", 5.0),
                c.getInt("prestige.perks.extra-members-every-levels", 3));
    }

    public static List<String> defaultTitles() {
        return List.of("Nowicjusz", "Osadnik", "Budowniczy", "Mistrz", "Weteran",
                "Elita", "Mityczny", "Legendarny", "Nieśmiertelny", "Transcendentny");
    }
}
