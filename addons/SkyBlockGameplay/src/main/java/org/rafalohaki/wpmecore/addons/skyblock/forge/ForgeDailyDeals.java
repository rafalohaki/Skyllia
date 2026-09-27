package org.rafalohaki.wpmecore.addons.skyblock.forge;

import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * ECO-12: „Okazje dnia" kuźni — {@value #DEAL_COUNT} receptury po
 * −{@value #DEAL_PERCENT} % przez jedną dobę (UTC).
 *
 * <p>Wybór jest <b>deterministyczny od daty</b> (wzorzec SkyBlockEventsService:
 * epoka → seed): ten sam dzień daje ten sam komplet u każdego gracza i po
 * restarcie serwera, a rotacja popytu idzie po drabince płyt → klucze → zbroje
 * bez drukowania monet. Okazja to rabat na zlewie — nie otwiera drogi do
 * monetyzacji, bo każde wykucie i tak trzyma koszt powyżej zera.
 *
 * <p>Zestaw receptur nie zmienia się w trakcie dnia, więc liczba okoliczności
 * „pół dnia taniej" nie istnieje: kto przeoczy, ten czeka na jutrzejszy zestaw.
 */
public final class ForgeDailyDeals {

    /** Ile receptur trafia na promocję w dobie. */
    public static final int DEAL_COUNT = 3;
    /** Rabat okazji w procentach (na cenie po rabacie rangowym). */
    public static final int DEAL_PERCENT = 20;

    private ForgeDailyDeals() {
    }

    /**
     * Deterministyczny zestaw okazji dla doby {@code day} na liście receptur
     * {@code recipeIds}. Zwraca posortowany podzbiór wejścia (maks.
     * {@value #DEAL_COUNT} elementów). Katalog mniejszy niż liczba okazji daje
     * pusty zestaw — „wszystko dziś −20 %" byłoby przypadkową globalną zniżką,
     * a nie okazją; te same zasady co w promocji sklepowej: zlew się nie wyprzedaje.
     */
    public static @NotNull Set<String> dealsFor(@NotNull LocalDate day,
                                                @NotNull Iterable<String> recipeIds) {
        List<String> sorted = new ArrayList<>();
        for (String id : recipeIds) {
            sorted.add(id);
        }
        if (sorted.size() <= DEAL_COUNT) {
            return Set.of();
        }
        Collections.sort(sorted);
        /*
         * Ten sam seed = ten sam zestaw. epochDay rotuje zestaw co dobę;
         * salt oddziela naszą rotację od innej złożonej z tego samego
         * epochDay (np. kolejności eventów), żeby zmiana jednej nie pchała
         * drugiej.
         */
        Random random = new Random(day.toEpochDay() * 31L + 0x2B2L);
        Set<String> deals = new TreeSet<>();
        while (deals.size() < DEAL_COUNT) {
            deals.add(sorted.get(random.nextInt(sorted.size())));
        }
        return deals;
    }

    /** Czy receptura jest dziś okazją. */
    public static boolean isDealToday(@NotNull String recipeId,
                                      @NotNull Iterable<String> recipeIds) {
        return dealsFor(LocalDate.now(java.time.ZoneOffset.UTC), recipeIds).contains(recipeId);
    }
}
