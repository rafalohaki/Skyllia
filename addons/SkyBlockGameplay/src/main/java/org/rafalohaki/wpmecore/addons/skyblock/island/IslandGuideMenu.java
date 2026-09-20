package org.rafalohaki.wpmecore.addons.skyblock.island;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.addons.skyblock.shared.Ui;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.util.List;

public final class IslandGuideMenu {

    private final MenuService menus;
    private final MiniMessage mm;

    public IslandGuideMenu(@NotNull MenuService menus, @NotNull MiniMessage mm) {
        this.menus = menus;
        this.mm = mm;
    }

    public void open(@NotNull Player player) {
        MenuService.Menu menu = menus.ofRows(4, Ui.component(mm, "<gold><bold>Przewodnik — pierwsze kroki</bold></gold>"));
        Ui.frame(menu, mm, Material.BOOK);
        menu.decoration(4, Ui.item(Material.OAK_SIGN, mm, "<yellow><bold>Jak wpisać komendę</bold></yellow>",
                List.of("<gray>Naciśnij klawisz <white>T</white>, wpisz komendę</gray>",
                        "<gray>i naciśnij <white>Enter</white>. Komendy zaczynają się</gray>",
                        "<gray>od ukośnika, na przykład <white>/menu</white>.</gray>"), false));
        menu.decoration(10, Ui.item(Material.GRASS_BLOCK, mm, "<green>1. Załóż wyspę</green>",
                List.of("<gray>Wpisz <white>/is</white> i wybierz tryb.</gray>",
                        "<gray>Klasyczny: mała wyspa i skrzynia ze sprzętem.</gray>",
                        "<gray>OneBlock: jeden blok, który sam się odnawia.</gray>"), false));
        menu.decoration(12, Ui.item(Material.IRON_PICKAXE, mm, "<yellow>2. Zdobądź surowce</yellow>",
                List.of("<gray>Na klasycznej wyspie zrób generator:</gray>",
                        "<gray>rowek na trzy bloki, lawa z jednej strony,</gray>",
                        "<gray>woda z drugiej. W środku powstaje bruk.</gray>",
                        "<gray>Na OneBlocku po prostu rozbijaj swój blok.</gray>"), false));
        menu.decoration(14, Ui.item(Material.EMERALD, mm, "<green>3. Zamień surowce na monety</green>",
                List.of("<gray>Wpisz <white>/sklep</white> — tam sprzedasz to,</gray>",
                        "<gray>co wykopałeś, i kupisz lepszy sprzęt.</gray>",
                        "<gray>Monety trzymasz w <white>/bank</white>.</gray>"), false));
        menu.decoration(16, Ui.item(Material.WRITABLE_BOOK, mm, "<aqua>4. Zadania na dziś</aqua>",
                List.of("<gray>Wpisz <white>/zadania</white> — trzy cele na dobę.</gray>",
                        "<gray>Nagroda idzie na wspólne konto wyspy.</gray>",
                        "<gray>Dzień przerwy niczego nie kasuje.</gray>"), false));
        menu.decoration(22, Ui.item(Material.PLAYER_HEAD, mm, "<aqua>5. Zaproś znajomych</aqua>",
                List.of("<gray>Wpisz <white>/is invite nick</white>.</gray>",
                        "<gray>Zaproszony wpisuje u siebie <white>/is accept</white>.</gray>"), false));
        menu.decoration(24, Ui.item(Material.COMPASS, mm, "<gold>Gdzie to wszystko jest</gold>",
                List.of("<gray><white>/menu</white> — sklep, zadania, bank, narzędzia.</gray>",
                        "<gray><white>/is</white> — wraca na Twoją wyspę.</gray>",
                        "<gray><white>/spawn</white> — plac startowy z przewodnikami.</gray>",
                        "<gray><white>/is help</white> — ta sama ściągawka na czacie.</gray>"), false));
        menu.close(31, Ui.closeButton(mm));
        menu.open(player);
    }
}
