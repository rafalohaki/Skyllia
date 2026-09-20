package org.rafalohaki.wpmecore.addons.skyblock.economy;

import org.jetbrains.annotations.NotNull;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.math.RoundingMode;
import java.util.Locale;

/** Locale-stable exact and compact economy labels for chat/TAB snapshots. */
public final class CompactBalanceFormatter {

    private static final Locale POLISH = Locale.forLanguageTag("pl-PL");
    private static final ThreadLocal<DecimalFormat> COMPACT = ThreadLocal.withInitial(() -> {
        DecimalFormat format = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(POLISH));
        format.setGroupingUsed(false);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    });

    private CompactBalanceFormatter() {
    }

    public static @NotNull String exact(long value) {
        String raw = Long.toString(value);
        int start = raw.charAt(0) == '-' ? 1 : 0;
        StringBuilder formatted = new StringBuilder(raw.length() + raw.length() / 3);
        if (start == 1) {
            formatted.append('-');
        }
        int digits = raw.length() - start;
        for (int index = start; index < raw.length(); index++) {
            if (index > start && (digits - (index - start)) % 3 == 0) {
                formatted.append(' ');
            }
            formatted.append(raw.charAt(index));
        }
        return formatted.toString();
    }

    public static @NotNull String compact(long value) {
        long absolute = value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
        if (absolute < 1_000L) {
            return Long.toString(value);
        }
        if (absolute < 1_000_000L) {
            return scaled(value, 1_000D, " tys.");
        }
        if (absolute < 1_000_000_000L) {
            return scaled(value, 1_000_000D, " mln");
        }
        if (absolute < 1_000_000_000_000L) {
            return scaled(value, 1_000_000_000D, " mld");
        }
        return scaled(value, 1_000_000_000_000D, " bln");
    }

    private static String scaled(long value, double divisor, String suffix) {
        return COMPACT.get().format(value / divisor) + suffix;
    }
}
