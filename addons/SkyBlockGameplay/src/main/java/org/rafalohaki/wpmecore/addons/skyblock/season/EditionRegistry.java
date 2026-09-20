package org.rafalohaki.wpmecore.addons.skyblock.season;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Rejestr nazwanych edycji sezonów z editions.yml (sekcja {@code editions}).
 *
 * <p>Fail-closed jak {@link SeasonSchedule}:
 * zły slug, duplikat, nieznany typ, odwrócony zakres albo nachodzące
 * edycje tego samego typu rzucają {@link IllegalArgumentException}
 * (wywoławca przy enable loguje SEVERE i zostaje przy legacy math).
 * Pusta lista przy {@code enabled:true} to ostrzeżenie + rejestr wyłączony.
 *
 * <p>Reguła rozstrzygania: półotwarte zawieranie; gdy moment trafia
 * jednocześnie w EVENT i REGULAR — wygrywa EVENT. Brak kandydata → null,
 * a wywoławca wraca do arytmetyki {@link SeasonSchedule#label()}
 * (zachowanie byte-stabilne względem dziś).
 */
public final class EditionRegistry {

    /**
     * Oczekiwane przypadki testowe (B6): displayName zawierający '&lt;', '&amp;',
     * '\n', '\r', znak sterujący albo dłuższy niż 48 znaków → IllegalArgumentException
     * (fail-closed przeciw wstrzyknięciu MiniMessage, decyzja A1 2026-08-25);
     * duplikat slugu → IllegalArgumentException; nachodzące REGULAR↔REGULAR
     * oraz EVENT↔EVENT → IllegalArgumentException; EVENT↔REGULAR nachodzące → OK.
     */
    static final int MAX_DISPLAY_NAME_LENGTH = 48;

    private static final String SLUG_PATTERN = "^[a-z0-9-]{2,64}$";

    /** Limit długości slugu quest-paka (klucz „quest-pack” w editions.yml). */
    static final int MAX_QUEST_PACK_LENGTH = 64;

    /** Format daty etykiety: sztywne kropki, Locale neutralny (jak SeasonSchedule). */
    private static final DateTimeFormatter LABEL_DAY =
            DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT);

    private static final Logger LOG =
            Logger.getLogger(EditionRegistry.class.getName());

    private final boolean enabled;
    /** Posortowane rosnąco po starcie. */
    private final List<Edition> editions;
    private final Map<String, Edition> bySlug;
    /** Opcjonalny slug quest-paka per edycja (brak klucza = questa uniwersalne). */
    private final Map<String, String> questPacks;

    private EditionRegistry(boolean enabled, List<ParsedEdition> parsed) {
        this.enabled = enabled;
        this.editions = parsed.stream().map(ParsedEdition::edition).toList();
        Map<String, Edition> index = new HashMap<>();
        Map<String, String> packs = new HashMap<>();
        for (ParsedEdition item : parsed) {
            index.put(item.edition().slug(), item.edition());
            if (item.questPack() != null) {
                packs.put(item.edition().slug(), item.questPack());
            }
        }
        this.bySlug = Map.copyOf(index);
        this.questPacks = Map.copyOf(packs);
    }

    /** Rejestr pusty/wyłączony — wywoławcy wracają do legacy math. */
    public static @NotNull EditionRegistry empty() {
        return new EditionRegistry(false, List.of());
    }

    /**
     * Parsuje sekcję {@code editions}. Sekcja nieobecna lub {@code enabled:false}
     * → {@link #empty()}. Błędy danych → IllegalArgumentException (PL komunikat).
     * Włączony rejestr bez ani jednej edycji → ostrzeżenie + rejestr wyłączony.
     */
    public static @NotNull EditionRegistry load(@Nullable ConfigurationSection root) {
        if (root == null || !root.getBoolean("enabled", false)) {
            return empty();
        }
        // Lista edycji to YAML-owa lista map (slug/display-name/type/start/end
        // oraz opcjonalny quest-pack).
        List<Map<?, ?>> entries = root.getMapList("list");
        if (entries.isEmpty()) {
            LOG.warning("editions.yml: enabled:true, ale lista edycji jest pusta "
                    + "— edycje nazwane wyłączone (legacy math)");
            return empty();
        }
        List<ParsedEdition> loaded = new ArrayList<>();
        for (Map<?, ?> entry : entries) {
            loaded.add(parseEdition(entry));
        }
        java.util.Set<String> seenSlugs = new java.util.HashSet<>();
        for (ParsedEdition item : loaded) {
            if (!seenSlugs.add(item.edition().slug())) {
                throw new IllegalArgumentException("editions: zduplikowany slug '"
                        + item.edition().slug() + "'");
            }
        }
        validateNoOverlap(loaded.stream().map(ParsedEdition::edition)
                .collect(java.util.stream.Collectors.toList()));
        loaded.sort(Comparator.comparingLong(
                item -> item.edition().startInclusiveMillis()));
        return new EditionRegistry(true, loaded);
    }

    private static @NotNull ParsedEdition parseEdition(Map<?, ?> entry) {
        Object rawSlugValue = entry.get("slug");
        String rawSlug = rawSlugValue == null ? "" : String.valueOf(rawSlugValue);
        if (!rawSlug.matches(SLUG_PATTERN)) {
            throw new IllegalArgumentException("editions: slug '" + rawSlug
                    + "' musi pasować do ^[a-z0-9-]{2,64}$");
        }
        String displayName = text(entry.get("display-name"));
        validateDisplayName(rawSlug, displayName);

        String rawType = text(entry.get("type"));
        final Edition.Type type;
        try {
            type = Edition.Type.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("editions." + rawSlug
                    + ": type musi być REGULAR albo EVENT, było '" + rawType + "'", failure);
        }

        LocalDate start = parseDate(rawSlug, "start", text(entry.get("start")));
        LocalDate end = parseDate(rawSlug, "end", text(entry.get("end")));
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("editions." + rawSlug
                    + ": start (" + start + ") musi być <= end (" + end + ")");
        }
        long startMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        // Dzień końcowy jest WŁĄCZNIE → granica to północ dnia następnego (UTC).
        long endExclusiveMillis = end.plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        Edition edition = new Edition(rawSlug, displayName, type,
                startMillis, endExclusiveMillis);
        return new ParsedEdition(edition, parseQuestPack(rawSlug, entry.get("quest-pack")));
    }

    /**
     * Opcjonalny klucz {@code quest-pack}: dowolny string do
     * {@value #MAX_QUEST_PACK_LENGTH} znaków (dłuższy → fail-closed); brak
     * klucza albo null → questa uniwersalne (null).
     */
    private static @Nullable String parseQuestPack(String slug, @Nullable Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String pack = String.valueOf(rawValue);
        if (pack.length() > MAX_QUEST_PACK_LENGTH) {
            throw new IllegalArgumentException("editions." + slug
                    + ": quest-pack przekracza " + MAX_QUEST_PACK_LENGTH
                    + " znaków (" + pack.length() + ")");
        }
        return pack;
    }

    private static @NotNull String text(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Fail-closed walidacja display-name przed wstrzyknięciem MiniMessage
     * (nazwa ląduje w szablonach operatora): zakaz '&lt;' i '&amp;' (tagi/escapy),
     * '\n'/'\r' i znaków sterujących (łamania layoutu), limit 48 znaków.
     */
    private static void validateDisplayName(String slug, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("editions." + slug
                    + ": display-name nie może być pusty");
        }
        for (int i = 0; i < displayName.length(); i++) {
            char c = displayName.charAt(i);
            if (c == '<' || c == '&' || c == '\n' || c == '\r'
                    || Character.isISOControl(c)) {
                throw new IllegalArgumentException("editions." + slug
                        + ": display-name zawiera zabroniony znak "
                        + String.format("U+%04X", (int) c)
                        + " (ochrona przed wstrzyknięciem MiniMessage)");
            }
        }
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("editions." + slug
                    + ": display-name przekracza " + MAX_DISPLAY_NAME_LENGTH
                    + " znaków (" + displayName.length() + ")");
        }
    }

    private static @NotNull LocalDate parseDate(String slug, String field, String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException | NullPointerException failure) {
            throw new IllegalArgumentException("editions." + slug + ": " + field
                    + " musi być datą ISO YYYY-MM-DD, było '" + raw + "'", failure);
        }
    }

    /**
     * Nachodzenie &gt;0 dni w obrębie typu jest błędem danych (dwie „ta sama”
     * edycje naraz); EVENT może swobodnie nakrywać REGULAR — wtedy wygrywa.
     */
    private static void validateNoOverlap(List<Edition> loaded) {
        for (int i = 0; i < loaded.size(); i++) {
            Edition a = loaded.get(i);
            for (int j = i + 1; j < loaded.size(); j++) {
                Edition b = loaded.get(j);
                if (a.type() != b.type()) {
                    continue;
                }
                boolean overlaps = a.startInclusiveMillis() < b.endExclusiveMillis()
                        && b.startInclusiveMillis() < a.endExclusiveMillis();
                if (overlaps) {
                    throw new IllegalArgumentException("editions: edycje typu " + a.type()
                            + " nachodzą na siebie: '" + a.slug() + "' oraz '" + b.slug() + "'");
                }
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Edycja obejmująca dany moment; gdy kandydatów wielu — EVENT bije REGULAR
     * (spośród tego samego typu nie może być dwóch po walidacji).
     */
    public @Nullable Edition currentAt(long nowMillis) {
        Edition regular = null;
        Edition event = null;
        for (Edition edition : editions) {
            if (!edition.contains(nowMillis)) {
                continue;
            }
            if (edition.type() == Edition.Type.EVENT) {
                event = later(edition, event);
            } else {
                regular = later(edition, regular);
            }
        }
        return event != null ? event : regular;
    }

    private static @NotNull Edition later(@NotNull Edition candidate, @Nullable Edition current) {
        if (current == null
                || candidate.startInclusiveMillis() >= current.startInclusiveMillis()) {
            return candidate;
        }
        return current;
    }

    /**
     * Najbliższa edycja startująca PO danym momencie (po sortowaniu po starcie),
     * lub null, gdy nic już nie nadchodzi.
     */
    public @Nullable Edition nextAfter(long nowMillis) {
        for (Edition edition : editions) {
            if (edition.startInclusiveMillis() > nowMillis) {
                return edition;
            }
        }
        return null;
    }

    /**
     * Wszystkie edycje startujące PO danym momencie, w kolejności startów
     * (rejestr trzyma listę posortowaną). W odróżnieniu od łańcucha
     * {@code nextAfter(poprzedni.koniec - 1)} obejmuje też okna ZAGNIEŻDŻONE
     * wewnątrz dłuższych zakresów (EVENT wewnątrz REGULAR) — łanie
     * end→nextAfter takie okna pomijało na zawsze, bo jego start wyprzedza
     * koniec obejmującej edycji REGULAR.
     */
    public @NotNull List<Edition> upcomingFrom(long nowMillis) {
        List<Edition> result = new ArrayList<>();
        for (Edition edition : editions) {
            if (edition.startInclusiveMillis() > nowMillis) {
                result.add(edition);
            }
        }
        return List.copyOf(result);
    }

    public @Nullable Edition bySlug(@NotNull String slug) {
        return bySlug.get(slug);
    }

    /**
     * Slug quest-paka edycji aktywnej w danym momencie; brak aktywnej edycji,
     * edycja bez klucza albo rejestr wyłączony → null (questy uniwersalne).
     * EVENT bije REGULAR według tej samej reguły co {@link #currentAt(long)}.
     */
    public @Nullable String questPackFor(long nowMillis) {
        Edition current = currentAt(nowMillis);
        return current == null ? null : questPacks.get(current.slug());
    }

    /** Slug quest-paka po slugu edycji; nieznany slug / brak klucza → null. */
    public @Nullable String questPackBySlug(@NotNull String slug) {
        return questPacks.get(slug);
    }

    /** Wewnętrzny wynik parsowania jednej pozycji listy editions.yml. */
    private record ParsedEdition(@NotNull Edition edition, @Nullable String questPack) { }

    /**
     * Publiczna etykieta dla danego momentu: display-name aktywnej edycji
     * albo null (wywoławca wraca do legacy math / pustego nagłówka).
     */
    public @Nullable String labelAt(long nowMillis) {
        Edition current = currentAt(nowMillis);
        return current != null ? current.displayName() : null;
    }

    /**
     * Zakres dat edycji „dd.MM.yyyy – dd.MM.yyyy” (format i separator jak
     * w {@code SeasonSchedule.label()}, dzień końcowy włącznie).
     */
    public @NotNull String rangeDetail(@NotNull Edition e) {
        LocalDate start = Instant.ofEpochMilli(e.startInclusiveMillis())
                .atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate end = Instant.ofEpochMilli(e.endExclusiveMillis())
                .atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        return LABEL_DAY.format(start) + " – " + LABEL_DAY.format(end);
    }
}
