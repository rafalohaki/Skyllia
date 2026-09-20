package org.rafalohaki.wpmecore.addons.skyblock.season;

/**
 * Nazwana edycja sezonu z editions.yml — publiczna etykieta zastępująca
 * gołe „S{n}” w komunikatach i GUI, gdy edycje są włączone.
 *
 * <p>Okres jest półotwarty: {@code [startInclusiveMillis, endExclusiveMillis)}.
 * Loader zamienia dzień końcowy z YML (włącznie) na północ dnia następnego
 * w UTC, więc {@link #contains(long)} nigdy nie traci ostatniego dnia.
 */
public record Edition(String slug, String displayName, Type type,
                      long startInclusiveMillis, long endExclusiveMillis) {

    /** REGULAR = kalendarzowe pory roku; EVENT = edycje szkolne/świąteczne. */
    public enum Type { REGULAR, EVENT }

    /**
     * Czy moment {@code nowMillis} mieści się w półotwarty okres edycji.
     */
    public boolean contains(long nowMillis) {
        return nowMillis >= startInclusiveMillis && nowMillis < endExclusiveMillis;
    }
}
