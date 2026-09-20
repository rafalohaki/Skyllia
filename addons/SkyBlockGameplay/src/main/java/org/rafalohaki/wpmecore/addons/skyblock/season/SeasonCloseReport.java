package org.rafalohaki.wpmecore.addons.skyblock.season;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Wspólny raport zamknięcia sezonu dla CLI ({@code /sezon zamknij}) i GUI
 * (ekran potwierdzenia) — JEDEN formater, dwa wywoławcze.
 *
 * <p>Treść wiernie przeniesiona z ciała
 * {@code SkyBlockCommands.executeSeasonClose} (kolory i sformułowania bez
 * zmian); zakres dat nowego sezonu przychodzi jako {@code nextLabel} — gdy
 * {@code null}, raport kończy się neutralnym „Nowy sezon aktywny.”.
 */
public final class SeasonCloseReport {

    private SeasonCloseReport() { }

    /**
     * Renderuje wynik próby zamknięcia do czatu operatora. Format zgodny z
     * CLI: odmowa/dry-run na żółto, sukces na zielono, błąd obsługuje wołający.
     */
    public static void send(@NotNull CommandSender sender,
                            @NotNull SeasonEndService.CloseOutcome outcome,
                            @Nullable String nextLabel) {
        send(sender, outcome, nextLabel, false);
    }

    /**
     * Wariant z podpowiedzią dry-run: wynik nie niesie flagi {@code confirm},
     * a CLI dopisuje „(dry-run)” dokładnie przy wołaniu bez potwierdzenia.
     * GUI (Podgląd zamknięcia) przekazuje {@code dryRun=true}; zwykłe GUI
     * i CLI z {@code --confirm} — {@code false}.
     */
    public static void send(@NotNull CommandSender sender,
                            @NotNull SeasonEndService.CloseOutcome outcome,
                            @Nullable String nextLabel,
                            boolean dryRun) {
        if (!outcome.closed()) {
            sender.sendMessage(Component.text(
                    "Zamknięcie odrzucone" + (dryRun ? " (dry-run)" : "") + ": "
                            + outcome.refusalReason(),
                    net.kyori.adventure.text.format.NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text(
                "Sezon zamknięty. Wiersze rankingu zarchiwizowane: " + outcome.archivedRows()
                        + "; nagrodzone teraz: " + outcome.rewardedNow()
                        + "; rozliczone wcześniej: " + outcome.alreadySettled()
                        + "; odroczone (offline): " + outcome.deferredOffline() + ".",
                net.kyori.adventure.text.format.NamedTextColor.GREEN));
        // Etykieta nowego sezonu: GUI podaje nazwę edycji, CLI zakres dat —
        // treść linii decyduje wołający, format jest wspólny.
        sender.sendMessage(Component.text(nextLabel != null
                ? "Nowy aktywny sezon: " + nextLabel + "."
                : "Nowy sezon aktywny.", net.kyori.adventure.text.format.NamedTextColor.GREEN));
    }

}
