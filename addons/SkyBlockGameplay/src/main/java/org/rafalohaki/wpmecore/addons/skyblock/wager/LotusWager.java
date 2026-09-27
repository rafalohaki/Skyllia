package org.rafalohaki.wpmecore.addons.skyblock.wager;

import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * ECO-13: Zakład Lotosowy — „podwój albo nic" na Srebrne Lotosy.
 *
 * <p>Parametry (decyzja z audytu ekonomii, EV 0,833 = spójne ze skrzynią
 * Underground): wpisowe {@link #STAKE} SL, wygrana {@link #PAYOUT} SL,
 * domowa marża 17 % idzie w zlew. Capy anty-tilt są twarde:
 * <b>jedna próba na dobę (UTC) na gracza</b> i zakaz gry, gdy gracz trzyma
 * przy sobie ponad {@link #MAX_SALDO} SL — nie odbieramy kasynu chętliwych
 * po bankructwie, ale stawka ponad zapas nie ma prawa przejść.
 *
 * <p>Klasa trzyma wyłącznie czystą logikę i klucz PDC — rareniowanie,
 * liczenie SL i wydawanie wygranej robi komenda (potrzebuje rejestru serwera
 * i outboxa), dzięki temu limity i dzień gry da się testować bez MockBukkit.
 */
public final class LotusWager {

    /** Wpisowe w Srebrnych Lotosach. */
    public static final int STAKE = 3;
    /** Wygrana w Srebrnych Lotosach (EV = 0,5 × 5 / 3 = 0,833). */
    public static final int PAYOUT = 5;
    /** Powyżej tyle SL przy sobie gracz nie może zagrać (capy anty-tilt). */
    public static final int MAX_SALDO = 20;

    /** Identyfikator CustomItems stawki — ten sam token, co w kantora kuźni. */
    public static final String SILVER_LOTUS_ID = "skyblock:token/silver_lotus";

    /** Klucz PDC gracza: dzień (UTC) ostatniej próby. */
    private final PersistentDataType<String, String> dayType = PersistentDataType.STRING;
    private final org.bukkit.NamespacedKey dayKey;

    public LotusWager(@NotNull Plugin plugin) {
        this.dayKey = new org.bukkit.NamespacedKey(plugin, "zaklad_day");
    }

    /** Dziś wg strefy nagrody (ta sama konwencja co codzienna nagroda). */
    public static @NotNull LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** Czy zakład był już rozegrany w dniu {@code day}. */
    public boolean playedOn(@NotNull PersistentDataContainer container, @NotNull LocalDate day) {
        return day.equals(lastPlayedDay(container));
    }

    /** Dzień ostatniej próby albo {@code null}, gdy gracz jeszcze nie grał. */
    public LocalDate lastPlayedDay(@NotNull PersistentDataContainer container) {
        String raw = container.get(dayKey, dayType);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /** Zapisuje dzień próby — wołane PO zabraniu stawki, PRZED rzutem. */
    public void markPlayed(@NotNull PersistentDataContainer container, @NotNull LocalDate day) {
        container.set(dayKey, dayType, day.toString());
    }

    /** Hook operatorski ({@code /sezon admin zaklad-reset}): zdejmuje blokadę dnia. */
    public void resetDay(@NotNull PersistentDataContainer container) {
        container.remove(dayKey);
    }

    /** Czy saldo SL blokuje grę (capy anty-tilt). */
    public static boolean saldoBlocksWager(int silverLotusCount) {
        return silverLotusCount > MAX_SALDO;
    }

    /** Czy gracz ma za mało SL na wpisowe. */
    public static boolean missingStake(int silverLotusCount) {
        return silverLotusCount < STAKE;
    }

    /** Deterministyczny operationId wygranej — no-op outboxu przy powtórce. */
    public static @NotNull String grantOperationId(@NotNull UUID player, @NotNull LocalDate day) {
        return "zaklad:" + player + ":" + day;
    }
}
