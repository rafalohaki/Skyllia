package org.rafalohaki.wpmecore.addons.skyblock;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Rotacyjne, timowane eventy SkyBlocka — krótkie okna boostów, które wracają.
 *
 * <p>Rozkład jest <b>deterministyczny od epoki</b> z configu: cykl =
 * suma {@code duration-minutes + gap-minutes} wszystkich pozycji rotacji,
 * a pozycja w cyklu liczona jest z zegara, nie ze stanu w pamięci. Restart
 * serwera w środku eventu nie resetuje harmonogramu ani nie wypuszcza
 * powtórnego ogłoszenia — po wstaniu tick po prostu widzi aktywny event.
 *
 * <p>Eventy deklarują mnożniki zamiast dopinać konkretne systemy:
 * {@link #crystalLuckMultiplier()} czyta listener gameplay-drops (łączy się
 * ze szczęściem z prestiżu multiplikatywnie), a {@link #seasonPointsMultiplier()}
 * dostaje serwis punktów sezonowych jako dodatkowy składnik mnożący —
 * obok bonusu weekendowego i catch-up.
 *
 * <p>Ogłoszenia startu/końca idą raz na zmianę stanu, przez callback
 * podany przez korzeń (broadcast na czacie). Tick wywołuje się z
 * GlobalRegionScheduler — sama klasa nie rusza stanu Bukkita.
 */
public final class SkyBlockEventsService {

    /** Jeden event w rotacji, wczytany z configu. */
    public record EventDef(@NotNull String id,
                           @NotNull String name,
                           int durationMinutes,
                           int gapMinutes,
                           double crystalLuck,
                           double seasonPoints,
                           @NotNull String announceStart,
                           @NotNull String announceEnd) {
    }

    private final Logger logger;
    private final List<EventDef> rotation;
    private final long epochMillis;
    private final long cycleMillis;
    private final Consumer<String> announcer;

    /** Ostatnio ogłoszony aktywny event; null = cisza w eventach. */
    private String announcedId;

    private SkyBlockEventsService(@NotNull Logger logger, @NotNull List<EventDef> rotation,
                                  long epochMillis, long cycleMillis,
                                  @NotNull Consumer<String> announcer) {
        this.logger = logger;
        this.rotation = rotation;
        this.epochMillis = epochMillis;
        this.cycleMillis = cycleMillis;
        this.announcer = announcer;
    }

    /**
     * Sekcja {@code events} → serwis. Fail-closed: brak sekcji, {@code enabled: false},
     * pusta rotacja albo zła epoka zwracają {@code null} — wtyczka startuje bez eventów.
     */
    public static @Nullable SkyBlockEventsService load(@NotNull Logger logger,
                                                       @Nullable ConfigurationSection section,
                                                       @NotNull Consumer<String> announcer) {
        if (section == null || !section.getBoolean("enabled", false)) {
            return null;
        }
        List<?> raw = section.getList("rotation");
        if (raw == null || raw.isEmpty()) {
            logger.warning("events: rotacja jest pusta — eventy wyłączone");
            return null;
        }
        long epoch;
        try {
            epoch = Instant.parse(section.getString("epoch", "")).toEpochMilli();
        } catch (RuntimeException e) {
            logger.warning("events: epoch musi być ISO-8601 (np. 2026-09-20T12:00:00Z) — eventy wyłączone");
            return null;
        }
        List<EventDef> defs = new ArrayList<>();
        long cycle = 0L;
        for (Object entry : raw) {
            if (!(entry instanceof java.util.Map<?, ?> map)) {
                continue;
            }
            EventDef def = parseDef(map);
            if (def == null) {
                logger.warning("events: pomijam pozycję rotacji bez id/name/duration-minutes");
                continue;
            }
            cycle += Math.max(1, def.durationMinutes()) + Math.max(0, def.gapMinutes());
            defs.add(def);
        }
        if (defs.isEmpty() || cycle <= 0L) {
            logger.warning("events: żadna poprawna pozycja w rotacji — eventy wyłączone");
            return null;
        }
        return new SkyBlockEventsService(logger, List.copyOf(defs), epoch, cycle * 60_000L, announcer);
    }

    private static @Nullable EventDef parseDef(java.util.Map<?, ?> map) {
        Object id = map.get("id");
        Object name = map.get("name");
        if (id == null || name == null) {
            return null;
        }
        Object startMsg = map.get("announce-start");
        Object endMsg = map.get("announce-end");
        return new EventDef(String.valueOf(id), String.valueOf(name),
                intOf(map.get("duration-minutes"), 30),
                intOf(map.get("gap-minutes"), 0),
                doubleOf(map.get("crystal-luck"), 1.0),
                doubleOf(map.get("season-points"), 1.0),
                startMsg != null ? String.valueOf(startMsg)
                        : "<gold>Event <name> wystartował!</gold>",
                endMsg != null ? String.valueOf(endMsg)
                        : "<gray>Event <name> dobiegł końca.</gray>");
    }

    private static int intOf(Object v, int def) {
        return v instanceof Number n ? n.intValue() : def;
    }

    private static double doubleOf(Object v, double def) {
        return v instanceof Number n ? n.doubleValue() : def;
    }

    /** Aktywny event w danej chwili zegarowej; pusty, gdy cykl jest w przerwie. */
    public @NotNull Optional<EventDef> activeAt(long nowMillis) {
        long pos = Math.floorMod(nowMillis - epochMillis, cycleMillis);
        long cursor = 0L;
        for (EventDef def : rotation) {
            long durationMs = Math.max(1, def.durationMinutes()) * 60_000L;
            if (pos >= cursor && pos < cursor + durationMs) {
                return Optional.of(def);
            }
            cursor += durationMs + Math.max(0, def.gapMinutes()) * 60_000L;
        }
        return Optional.empty();
    }

    /** Aktywny event teraz. */
    public @NotNull Optional<EventDef> active() {
        return activeAt(System.currentTimeMillis());
    }

    /** Mnożnik szans na kryształy rozgrywki (1.0 gdy event nie daje bonusu). */
    public double crystalLuckMultiplier() {
        return active().map(EventDef::crystalLuck).filter(v -> v > 1.0).orElse(1.0);
    }

    /** Mnożnik punktów sezonowych (1.0 gdy event nie daje bonusu). */
    public double seasonPointsMultiplier() {
        return active().map(EventDef::seasonPoints).filter(v -> v > 1.0).orElse(1.0);
    }

    /**
     * Krok rozkładu — wywoływany z timera. Ogłasza start przy wejściu w nowy
     * event i koniec przy zejściu do pustki; powtórzone ticki w trakcie tego
     * samego eventu milczą.
     */
    public void tick() {
        Optional<EventDef> active = active();
        String id = active.map(EventDef::id).orElse(null);
        if (java.util.Objects.equals(id, announcedId)) {
            return;
        }
        // Ogłoś koniec poprzedniego przed startem nowego — przy back-to-back
        // eventach obie wiadomości pojawiają się w jednym ticku.
        for (EventDef def : rotation) {
            if (def.id().equals(announcedId)) {
                announce(def.announceEnd(), def);
            }
        }
        announcedId = id;
        if (active.isPresent()) {
            EventDef def = active.get();
            announce(def.announceStart(), def);
            try {
                logger.info("Event '" + def.id() + "' aktywny (kryształy ×"
                        + def.crystalLuck() + ", punkty sezonu ×" + def.seasonPoints() + ")");
            } catch (Exception ignored) {
                // logger nigdy nie może wysadzić ticka
            }
        }
    }

    private void announce(@NotNull String template, @NotNull EventDef def) {
        String text = template.replace("<name>", def.name());
        try {
            announcer.accept(text);
        } catch (Exception e) {
            logger.log(Level.FINE, "Announcer rzucił wyjątek dla eventu " + def.id(), e);
        }
    }
}
