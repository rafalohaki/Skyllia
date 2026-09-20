package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.shared.ConfigSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * M2.5: katalog questów sezonowych wczytany z config.yml
 * (sekcja {@code season.quests}), spec {@code docs/spec/sezony-rankingi.md} §2.1 i §3.
 *
 * <p>Parsowanie jest fail-closed jak w {@link CosmeticCatalog}: zły
 * {@code schema-version}, nieznany typ zdarzenia, pusty identyfikator albo
 * cel, nieistniejący materiał ikony, niedodatni cel/punkty lub więcej questów
 * niż slotów odblokowań zatrzymuje start pluginu z dokładną ścieżką błędu —
 * brak cichego fallbacku do pustego katalogu (kontrakt {@link ConfigSchema},
 * nie degradującego {@code api.util.Config}).
 *
 * <p>Identyfikatory questów to klucze mapy YAML ({@code definitions.*}), więc
 * duplikaty są niemożliwe na poziomie parsera (snakeyaml zostawia ostatni
 * wpis); celowo pusty klucz jest odrzucany jawnie. Kolejność definicji =
 * kolejność odblokowywania progami punktowymi (spec §2.1).
 *
 * <p>Liczba questów UNIWERSALNYCH (bez {@code pack}) nie może przekroczyć
 * łącznej liczby slotów odblokowań
 * ({@link DefaultSeasonPointService#UNLOCK_THRESHOLDS} ×
 * {@link DefaultSeasonPointService#SLOTS_PER_TIER}); nadmiar byłby trwale
 * zablokowany — czyli obiecany w configu, a nigdy niedostępny.
 *
 * <p>C2: każda definicja może opcjonalnie deklarować {@code pack: <slug>}
 * (limit długości jak „quest-pack” w editions.yml, inaczej fail-closed).
 * {@link #activeQuests()} zwraca questa uniwersalne zawsze, a oznaczone
 * pakem tylko gdy slug zgadza się z aktywną edycją (dostawca z wiringu);
 * {@link #definitions()} pozostaje pełną listą.
 *
 * <p>Decyzja (edycje eventowe, 2026-09): questy pakietowe NIE zajmują slotów
 * odblokowań. Sloty progów punktowych mapują się wyłącznie na
 * {@link #universalQuests()} (kolejność definicji bez {@code pack} = kolejność
 * slotów), a questy aktywnego paka ({@link #activePackQuests()}) są osobną
 * listą „questów eventu”: dostępne od pierwszej sekundy edycji bez progu,
 * niewidoczne poza jej oknem. Dzięki temu event nie wypycha questów
 * uniwersalnych z 40 slotów ani nie ląduje na końcu listy za progiem 3000
 * pkt, którego przez dwa tygodnie eventu nikt by nie dobił. Fail-closed bez
 * zmian: limit slotów dalej rzuca, gdy uniwersalnych jest więcej niż slotów.
 */
public final class SeasonQuestCatalog {
    private static final String PREFIX = "config.yml:season.quests";
    /** Nazwa noszona w komunikatach; ścieżki wyszukiwania są relatywne do sekcji. */
    private static final ConfigSchema SCHEMA = new ConfigSchema(PREFIX);

    /** Limit długości slugu paka (klucz {@code pack} w season.quests). */
    private static final int MAX_PACK_LENGTH = EditionRegistry.MAX_QUEST_PACK_LENGTH;

    /** Pełny katalog w kolejności definicji (również questa z paków). */
    private final List<SeasonQuestService.Definition> definitions;
    /** Slug paka per quest id; brak wpisu = questa uniwersalne. */
    private final Map<String, String> packById;
    /** Questa bez {@code pack} w kolejności definicji = kolejność slotów odblokowań. */
    private final List<SeasonQuestService.Definition> universal;
    /** Questa per slug paka (kolejność definicji) — O(1) odczyt w handlerach zdarzeń. */
    private final Map<String, List<SeasonQuestService.Definition>> byPack;
    /**
     * Dostawca aktywnego paka (edycja wg czasu — wiring SkyBlockGameplay).
     * Volatile: podmiana rejestru edycji (C10) ma być widoczna bez restartu.
     */
    private volatile java.util.function.Supplier<String> activePackSupplier;

    public SeasonQuestCatalog(@NotNull List<SeasonQuestService.Definition> definitions,
                              @NotNull Map<String, String> packById) {
        this.definitions = List.copyOf(definitions);
        this.packById = Map.copyOf(packById);
        List<SeasonQuestService.Definition> universalList = new ArrayList<>();
        Map<String, List<SeasonQuestService.Definition>> packs = new LinkedHashMap<>();
        for (SeasonQuestService.Definition def : this.definitions) {
            String pack = this.packById.get(def.id());
            if (pack == null) {
                universalList.add(def);
            } else {
                packs.computeIfAbsent(pack, k -> new ArrayList<>()).add(def);
            }
        }
        this.universal = List.copyOf(universalList);
        Map<String, List<SeasonQuestService.Definition>> frozen = new LinkedHashMap<>();
        packs.forEach((pack, list) -> frozen.put(pack, List.copyOf(list)));
        this.byPack = Map.copyOf(frozen);
    }

    /**
     * Pełna lista definicji w kolejności z configu — BEZ filtra paków
     * (kontrakt B6: kolejność i rozmiar muszą być stabilne dla testów).
     */
    public @NotNull List<SeasonQuestService.Definition> definitions() {
        return definitions;
    }

    /**
     * Questa uniwersalne (bez {@code pack}) w kolejności definicji — to na
     * tę listę mapują się sloty progów odblokowań; rozmiar ≤ liczba slotów.
     */
    public @NotNull List<SeasonQuestService.Definition> universalQuests() {
        return universal;
    }

    /**
     * Questa aktywnego paka (dostawca z wiringu, czytany przy każdym
     * wywołaniu): pusta lista, gdy dostawcy brak, zwraca null albo nic nie
     * pasuje. Kolejność = kolejność definicji; bez mapowania na sloty —
     * odbiorca traktuje je jako zawsze odblokowane w oknie edycji.
     */
    public @NotNull List<SeasonQuestService.Definition> activePackQuests() {
        String activePack =
                activePackSupplier != null ? activePackSupplier.get() : null;
        return activePack == null ? List.of() : byPack.getOrDefault(activePack, List.of());
    }

    /**
     * Ustawia dostawcę aktywnego paka questów. Null → aktywne są wyłącznie
     * questa uniwersalne (bez klucza {@code pack}).
     */
    public void setActivePackSupplier(
            @Nullable java.util.function.Supplier<String> supplier) {
        this.activePackSupplier = supplier;
    }

    /**
     * Questa aktywne przy obecnym pakie: uniwersalne (bez {@code pack})
     * zawsze; oznaczone pakem tylko gdy slug zgadza się z dostawcą.
     * Null dostawca = wyłącznie uniwersalne. Widok łączony w kolejności
     * definicji (diagnostyka, testy); listener bierze rozdzielone
     * {@link #universalQuests()} i {@link #activePackQuests()}.
     */
    public @NotNull List<SeasonQuestService.Definition> activeQuests() {
        String activePack =
                activePackSupplier != null ? activePackSupplier.get() : null;
        return definitions.stream()
                .filter(def -> {
                    String pack = packById.get(def.id());
                    return pack == null || pack.equals(activePack);
                })
                .toList();
    }


    /**
     * Parsuje sekcję {@code season.quests}. Sekcja nieobecna, zły schema-version
     * albo jakakolwiek niespójna definicja → {@link IllegalArgumentException}.
     */
    public static @NotNull SeasonQuestCatalog load(@Nullable ConfigurationSection section) {
        if (section == null) {
            throw new IllegalArgumentException(PREFIX + ": brak wymaganej sekcji");
        }
        int schema = section.getInt("schema-version", -1);
        if (schema != 1) {
            throw SCHEMA.fail("schema-version", "oczekiwano 1, było " + schema);
        }
        int pointsDefault = section.getInt("points-default", -1);
        if (pointsDefault <= 0) {
            throw SCHEMA.fail("points-default", "musi być > 0");
        }
        ConfigurationSection definitions = SCHEMA.section(section, "definitions");

        Map<String, String> packById = new LinkedHashMap<>();
        List<SeasonQuestService.Definition> parsed = new ArrayList<>();
        for (String id : definitions.getKeys(false)) {
            if (id == null || id.isBlank()) {
                throw SCHEMA.fail("definitions.<puste-id>",
                        "identyfikator questa nie może być pusty");
            }
            parsed.add(parseDefinition(definitions, id, pointsDefault, packById));
        }
        if (parsed.isEmpty()) {
            throw SCHEMA.fail("definitions", "katalog bez questów");
        }
        int totalSlots = DefaultSeasonPointService.UNLOCK_THRESHOLDS.length
                * DefaultSeasonPointService.SLOTS_PER_TIER[0];
        // Limit slotów dotyczy tylko questów uniwersalnych — pakietowe żyją
        // poza mapowaniem slotów (zob. Javadoc klasy).
        long universalCount = parsed.stream().filter(def -> !packById.containsKey(def.id())).count();
        if (universalCount > totalSlots) {
            throw SCHEMA.fail("definitions", "questów uniwersalnych (" + universalCount
                    + ") nie może być więcej niż slotów odblokowań (" + totalSlots + ")");
        }
        return new SeasonQuestCatalog(parsed, packById);
    }

    private static @NotNull SeasonQuestService.Definition parseDefinition(
            @NotNull ConfigurationSection definitions, @NotNull String id,
            int pointsDefault, @NotNull Map<String, String> packById) {
        String path = "definitions." + id;
        ConfigurationSection section = SCHEMA.section(definitions, id);

        String rawType = SCHEMA.string(section, path, "type");
        final SeasonQuestService.Definition.Type type;
        try {
            type = SeasonQuestService.Definition.Type.valueOf(rawType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownType) {
            throw SCHEMA.fail(path + ".type", "nieznany typ '" + rawType + "'");
        }

        List<String> rawTargets = section.getStringList("targets");
        if (rawTargets.isEmpty()) {
            throw SCHEMA.fail(path + ".targets", "quest bez celów");
        }
        Set<String> targets = new LinkedHashSet<>();
        for (int index = 0; index < rawTargets.size(); index++) {
            String target = rawTargets.get(index);
            if (target == null || target.isBlank()) {
                throw SCHEMA.fail(path + ".targets[" + index + "]", "pusty cel");
            }
            targets.add(target.toUpperCase(Locale.ROOT));
        }

        int goal = section.getInt("goal", 0);
        if (goal < 1) {
            throw SCHEMA.fail(path + ".goal", "musi być >= 1");
        }
        int points = section.contains("points") ? section.getInt("points") : pointsDefault;
        if (points <= 0) {
            throw SCHEMA.fail(path + ".points", "musi być > 0");
        }

        String rawIcon = SCHEMA.string(section, path, "icon");
        var icon = SCHEMA.material(rawIcon, path + ".icon");

        boolean matureOnly = section.getBoolean("mature-only", false);
        List<String> lore = section.getStringList("lore");

        // Opcjonalny „pack”: absent/pusty = questa uniwersalny; dłuższy niż
        // limit → fail-closed (ta sama reguła co „quest-pack” w editions.yml).
        if (section.contains("pack")) {
            String pack = section.getString("pack");
            if (pack != null && !pack.isBlank()) {
                if (pack.length() > MAX_PACK_LENGTH) {
                    throw SCHEMA.fail(path + ".pack", "przekracza "
                            + MAX_PACK_LENGTH + " znaków (" + pack.length() + ")");
                }
                packById.put(id, pack);
            }
        }
        return new SeasonQuestService.Definition(id, type, Set.copyOf(targets),
                goal, points, matureOnly, icon,
                SCHEMA.string(section, path, "name"), List.copyOf(lore));
    }
}
