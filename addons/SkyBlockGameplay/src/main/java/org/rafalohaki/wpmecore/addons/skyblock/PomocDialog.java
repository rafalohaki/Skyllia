package org.rafalohaki.wpmecore.addons.skyblock;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.rafalohaki.wpmecore.api.service.SchedulerService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Natywny dialog Papera dla {@code /pomoc} — lista tematów w dwóch kolumnach,
 * klik tematu otwiera szczegóły z przyciskiem powrotu. Wzorzec budowy:
 * {@code addons/kopacz/.../dialog/KopaczDialogs} (pilot dialogów 2026-09-11).
 *
 * <p>Dialog to warstwa wygody, nie jedyna droga: każde pokazanie ma fallback
 * na dotychczasowy tekst czatu ({@link SkyBlockCommands#POMOC}), więc klient
 * bez wsparcia albo błąd API zostawia gracza ze starą listą komend.
 *
 * <p>Bezpieczeństwo: żaden przycisk nie używa {@code commandTemplate} — tematy
 * i powrót idą przez {@code customClick} (logika w Javie), a „Zamknij” zamyka
 * dialog bez komendy. Nie ma tu niczego, co wstawiałoby tekst gracza do komendy.
 *
 * <p>Folia: {@code showDialog} jest per-gracz, więc wejście i każdy callback
 * hopują na wątek encji przez {@link SchedulerService#entity}. Treść jest
 * statyczna — żadnego SQL ani stanu świata.
 *
 * <p>Ikony {@code \\uE0xx} wymagają tagu {@code <font:wpme:icons>} — paczka
 * klienta nie nadpisuje fontu default (konwencja z {@link SkyBlockCommands#POMOC}).
 */
final class PomocDialog {

    /** Tematy w dwóch kolumnach — sześć tematów mieści się w trzech rzędach. */
    private static final int HELP_COLUMNS = 2;

    /** Jeden temat pomocy: tytuł na przycisku + linie szczegółów (MiniMessage). */
    record Topic(@NotNull String title, @NotNull String tooltip,
                 @NotNull List<String> lines) {
    }

    /**
     * Treść pomocy. Kolejność = kolejność przycisków; każdy temat kończy się
     * wskazówką, co kliknąć dalej, żeby gracz nie utknął w ślepym zaułku.
     */
    static final List<Topic> TOPICS = List.of(
            new Topic("Wyspa", "Tworzenie, teleport, członkowie",
                    List.of(
                            "<font:wpme:icons>\uE001</font> <yellow>/stworzwyspe</yellow> — menu tworzenia wyspy (classic, oneblock)",
                            "<font:wpme:icons>\uE001</font> <yellow>/is</yellow> — teleport na własną wyspę; <white>/is help</white> — wszystkie podkomendy",
                            "<font:wpme:icons>\uE018</font> <yellow>/is panel</yellow> — Centrum Wyspy: bank, członkowie, prestiż, tytuł",
                            "<font:wpme:icons>\uE001</font> <yellow>/is invite <nick></yellow> — zaproszenie członka; <white>/is kick</white> — wyrzucenie",
                            "<gray>Wyspa startuje na y=63; dom ustawia się sam przy pierwszym lądowaniu.</gray>")),
            new Topic("OneBlock i prestiż", "Fazy bloku, kamienie, zlew monet",
                    List.of(
                            "<font:wpme:icons>\uE019</font> <yellow>/wyspa oneblock info</yellow> — faza, postęp i kamienie milowe",
                            "<font:wpme:icons>\uE011</font> <yellow>/wyspa oneblock nagrody</yellow> — odbiór nagród za kamienie",
                            "<font:wpme:icons>\uE010</font> <yellow>/is panel</yellow> → Prestiż — zlew monet z banku wyspy za tytuł",
                            "<gray>Prestiż kosztuje coraz więcej (×1,6 za poziom) — to zlew dla bogatych wysp.</gray>")),
            new Topic("Monety i handel", "Sklep, aukcje, bank, wycena",
                    List.of(
                            "<font:wpme:icons>\uE002</font> <yellow>/sklep</yellow> — sprzedaż surowców i zakupy za monety",
                            "<font:wpme:icons>\uE017</font> <yellow>/ah</yellow> — dom aukcyjny: handel z innymi graczami (podatek 5%)",
                            "<font:wpme:icons>\uE010</font> <yellow>/bank</yellow> — wspólny bank wyspy; depozyt liczy się do rankingu",
                            "<font:wpme:icons>\uE002</font> <yellow>/wartosc</yellow> — cena przedmiotu trzymanego w ręce wg cennika",
                            "<gray>Monety z banku wyspy zasilają prestiż i ranking sezonu.</gray>")),
            new Topic("Zadania i sezon", "Dzienne zadania, punkty, karnet",
                    List.of(
                            "<font:wpme:icons>\uE00D</font> <yellow>/zadania</yellow> — dzienne zadania wyspy z nagrodami i punktami sezonu",
                            "<font:wpme:icons>\uE00E</font> <yellow>/sezon</yellow> — punkty sezonowe, questy i TOP-10 wysp",
                            "<font:wpme:icons>\uE011</font> <yellow>/przepustka</yellow> — karnet sezonowy: 75 poziomów nagród",
                            "<font:wpme:icons>\uE011</font> <yellow>/nagroda</yellow> — codzienna nagroda i seria logowań",
                            "<gray>15 zadań dnia wyspy w tygodniu = Złoty Lotos dla właściciela.</gray>")),
            new Topic("Kuźnia i pety", "Talizmany, ulepszenia, minionki",
                    List.of(
                            "<font:wpme:icons>\uE00A</font> <yellow>/kuznia</yellow> — talizmany, ulepszenia i pety za monety",
                            "<font:wpme:icons>\uE00D</font> <yellow>/pets</yellow> — twoje pety: aktywacja i odbiór surowców",
                            "<font:wpme:icons>\uE009</font> <yellow>/narzedzia</yellow> — sklep magicznych narzędzi (różdżki)",
                            "<gray>Minionki kupuje się w kuźni — przepis wymaga m.in. bloków z oneblocka.</gray>")),
            new Topic("Rozrywka", "Gierki, ryby, konkursy wędkarskie",
                    List.of(
                            "<font:wpme:icons>\uE007</font> <yellow>/gierki</yellow> — centrum gier: ruletka i skrzynki (tytuły, bez strat)",
                            "<font:wpme:icons>\uE01A</font> <yellow>Ryby</yellow> — konkurs wędkarski co 2 h (20 min); wędka u rybaka Barnaby na spawnie",
                            "<font:wpme:icons>\uE00D</font> <yellow>/emf shop</yellow> — skup złowionych ryb; <white>/emf journal</white> — dziennik gatunków",
                            "<gray>W konkursie wygrywa największa ryba — nagrody dla TOP-3.</gray>")),
            new Topic("Rangi i perki", "Co daje ranga, lot, sklep www",
                    List.of(
                            "<font:wpme:icons>\uE004</font> <yellow>/latanie</yellow> — lot na własnej wyspie (perk od rangi SVIP/EVIP)",
                            "<font:wpme:icons>\uE000</font> <yellow>Rangi sieciowe</yellow> — VIP/SVIP/EVIP działają na całej sieci; wyższa ranga zawiera perki niższej",
                            "<font:wpme:icons>\uE002</font> <yellow>Sklep:</yellow> <white>2b2t.pl/sklep</white> — rangi i bonusy za złotówki",
                            "<gray>VIP ⊃ weteran: kupując rangę dostajesz też perki progu stażu.</gray>")),
            new Topic("Kontakt i zasady", "Zgłoszenia, wiadomości, spawn",
                    List.of(
                            "<font:wpme:icons>\uE005</font> <yellow>/spawn</yellow> — powrót na plac spawnu",
                            "<font:wpme:icons>\uE000</font> <yellow>/msg <nick></yellow> — prywatna wiadomość; <white>/reply</white> — odpowiedź",
                            "<font:wpme:icons>\uE000</font> <yellow>/report <nick></yellow> — zgłoszenie gracza do administracji",
                            "<font:wpme:icons>\uE018</font> <yellow>/menu</yellow> — wszystkie okna gry w jednym miejscu",
                            "<gray>Bieżący cel widzisz na tablicy po prawej stronie ekranu.</gray>")));

    private final SchedulerService scheduler;
    private final MiniMessage miniMessage;
    private final Logger logger;

    PomocDialog(@NotNull SchedulerService scheduler, @NotNull MiniMessage miniMessage,
                @NotNull Logger logger) {
        this.scheduler = scheduler;
        this.miniMessage = miniMessage;
        this.logger = logger;
    }

    /** Lista tematów; klik tematu pokazuje szczegóły z przyciskiem powrotu. */
    void open(@NotNull Player player, @NotNull Runnable fallback) {
        onEntityThread(player, () -> show(player, this::listDialog, "dialog pomocy", fallback));
    }

    private @NotNull Dialog listDialog() {
        List<ActionButton> topics = new ArrayList<>(TOPICS.size());
        for (Topic topic : TOPICS) {
            topics.add(ActionButton.builder(Component.text(topic.title()))
                    .tooltip(Component.text(topic.tooltip()))
                    .action(DialogAction.customClick((view, audience) ->
                                    showDetail(audience, topic),
                            ClickCallback.Options.builder().build()))
                    .build());
        }
        ActionButton exit = ActionButton.builder(Component.text("Zamknij"))
                .action(closeAction())
                .build();
        DialogBase base = DialogBase.builder(Component.text("Pomoc SkyBlock", NamedTextColor.GOLD))
                .body(body(List.of(
                        "<gray>Wybierz temat, żeby zobaczyć komendy i opis.</gray>",
                        "<dark_gray>Tekstowa wersja na czacie: /pomoc tekst</dark_gray>")))
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        return Dialog.create(factory -> factory.empty().base(base)
                .type(DialogType.multiAction(topics, exit, HELP_COLUMNS)));
    }

    private @NotNull Dialog detailDialog(@NotNull Topic topic) {
        DialogBase base = DialogBase.builder(Component.text(topic.title(), NamedTextColor.GOLD))
                .body(body(topic.lines()))
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        ActionButton back = ActionButton.builder(Component.text("Wróć"))
                .action(DialogAction.customClick((view, audience) -> showList(audience),
                        ClickCallback.Options.builder().build()))
                .build();
        return Dialog.create(factory -> factory.empty().base(base)
                .type(DialogType.notice(back)));
    }

    /** Akcja wyjścia: zamknięcie dialogu bez żadnej komendy. */
    private static @NotNull DialogAction closeAction() {
        return DialogAction.customClick((view, audience) -> audience.closeDialog(),
                ClickCallback.Options.builder().build());
    }

    private @NotNull List<DialogBody> body(@NotNull List<String> lines) {
        List<DialogBody> body = new ArrayList<>(lines.size());
        for (String line : lines) {
            body.add(DialogBody.plainMessage(miniMessage.deserialize(line)));
        }
        return body;
    }

    // ----- Nawigacja i fail-safe -----

    /** Powrót ze szczegółów do listy tematów. Callback Papera dostaje
     *  publiczność, nie gracza, więc najpierw ją rozwiązujemy. */
    private void showList(@NotNull Audience audience) {
        if (!(audience instanceof Player player)) {
            return;
        }
        onEntityThread(player, () -> show(player, this::listDialog, "dialog pomocy",
                () -> player.sendMessage(miniMessage.deserialize(SkyBlockCommands.POMOC))));
    }

    private void showDetail(@NotNull Audience audience, @NotNull Topic topic) {
        if (!(audience instanceof Player player)) {
            return;
        }
        onEntityThread(player, () -> show(player, () -> detailDialog(topic),
                "szczegółów pomocy",
                () -> player.sendMessage(miniMessage.deserialize(
                        String.join("<newline>", topic.lines())))));
    }

    /** Show wyłącznie na wątku encji gracza — {@code showDialog} jest per-gracz. */
    private void onEntityThread(@NotNull Player player, @NotNull Runnable task) {
        scheduler.entity(player, task, null);
    }

    /**
     * Spróbuj pokazać dialog; gdy się nie uda (klient bez wsparcia, błąd API),
     * zaloguj WARNING i uruchom dotychczasową drogę, żeby gracz nie został bez
     * niczego. Wyjątek nie leci dalej — dialog jest warstwą wygody.
     */
    private void show(@NotNull Player player, @NotNull Supplier<Dialog> builder,
                      @NotNull String what, @NotNull Runnable fallback) {
        try {
            player.showDialog(builder.get());
        } catch (Throwable failure) {
            logger.log(Level.WARNING, "Nie udało się pokazać " + what + " dla "
                    + player.getName() + " — gracz dostaje wersję czatową.", failure);
            fallback.run();
        }
    }
}
