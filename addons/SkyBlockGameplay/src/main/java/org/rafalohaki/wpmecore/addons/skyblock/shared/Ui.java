package org.rafalohaki.wpmecore.addons.skyblock.shared;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.api.service.ClientAssetService;
import org.rafalohaki.wpmecore.api.service.MenuService;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public final class Ui {

    private static final ThreadLocal<NumberFormat> INTEGER = ThreadLocal.withInitial(
            () -> NumberFormat.getIntegerInstance(Locale.forLanguageTag("pl-PL")));

    private Ui() { }

    /**
     * Glify fontu {@code wpme:icons} z paczki klienta. Każdy klient ma paczkę
     * ({@code require-resource-pack=true}), więc tag jest bezpieczny też w czacie.
     * Bez paczki (testy, konsola) znak jest niewidoczny — nie zostaje tofu.
     */
    public static final String ICON_COINS = "<font:wpme:icons>\uE002</font>";
    public static final String ICON_LOTUS = "<font:wpme:icons>\uE01F</font>";

    /** Most do paczki klienta; {@code null} = czyste vanilla (testy, brak rdzenia). */
    private static volatile @Nullable ClientAssetService clientAssets;

    /** Wołane raz przy włączeniu SkyBlockGameplay. */
    public static void init(@Nullable ClientAssetService assets) {
        clientAssets = assets;
    }

    /**
     * Nakłada model z prefiksu {@code wpme:gui}; bez paczki przedmiot
     * pozostaje przy swoim materiale zapasowym.
     */
    public static @NotNull ItemStack modeled(@NotNull ItemStack base, @NotNull String guiModel) {
        ClientAssetService assets = clientAssets;
        if (assets == null) {
            return base;
        }
        try {
            return assets.withItemModel(base, "gui/" + guiModel);
        } catch (RuntimeException ex) {
            return base; // fail-open: menu działa też bez paczki
        }
    }

    public static void frame(@NotNull MenuService.Menu menu,
                      @NotNull MiniMessage miniMessage,
                      @NotNull Material accent) {
        menu.fill(modeled(item(Material.BLACK_STAINED_GLASS_PANE, miniMessage,
                "<dark_gray> </dark_gray>", List.of(), false), "frame"));
        ItemStack corner = item(accent, miniMessage,
                "<dark_gray> </dark_gray>", List.of(), false);
        int last = menu.size() - 1;
        for (int slot : new int[]{0, 8, last - 8, last}) {
            menu.decoration(slot, corner.clone());
        }
    }

    /** Model separatora {@code wpme:gui/separator}; bez paczki zostaje szyba zapasowa. */
    public static @NotNull ItemStack separator(@NotNull MiniMessage miniMessage) {
        return modeled(item(Material.GRAY_STAINED_GLASS_PANE, miniMessage,
                "<dark_gray> </dark_gray>", List.of(), false), "separator");
    }

    /**
     * Pozioma linia separatora w rzędzie {@code row} (kolumny wewnętrzne 1..7),
     * z pominięciem podanych slotów (np. przycisków stopki). Oddziela strefę
     * treści od strefy akcji; bez paczki pozostaje zwykła szyba zapasowa.
     */
    public static void separatorRow(@NotNull MenuService.Menu menu,
                              @NotNull MiniMessage miniMessage,
                              int row,
                              int... skipSlots) {
        ItemStack line = separator(miniMessage);
        for (int column = 1; column < 8; column++) {
            int slot = row * 9 + column;
            boolean skipped = false;
            for (int skip : skipSlots) {
                if (skip == slot) {
                    skipped = true;
                    break;
                }
            }
            if (!skipped) {
                menu.decoration(slot, line.clone());
            }
        }
    }

    /**
     * Wyróżnienie nagłówka / aktywnej zakładki modelem
     * {@code wpme:gui/frame_accent} (np. aktywny tor przepustki).
     * Bez paczki przedmiot zostaje przy materiale zapasowym.
     */
    public static @NotNull ItemStack accent(@NotNull MiniMessage miniMessage,
                                      @NotNull Material material,
                                      @NotNull String name,
                                      @NotNull List<String> lore) {
        return modeled(item(material, miniMessage, name, lore, true), "frame_accent");
    }

    public static @NotNull ItemStack closeButton(@NotNull MiniMessage miniMessage) {
        return modeled(item(Material.BARRIER, miniMessage,
                "<red><bold>Zamknij</bold></red>",
                List.of("<gray>Kliknij, aby zamknąć menu.</gray>"), false), "button_close");
    }

    public static @NotNull ItemStack backButton(@NotNull MiniMessage miniMessage) {
        return modeled(item(Material.ARROW, miniMessage,
                "<yellow><bold>Powrót</bold></yellow>",
                List.of("<gray>Wróć do poprzedniego ekranu.</gray>"), false), "button_back");
    }

    public static @NotNull ItemStack item(@NotNull Material material,
                                   @NotNull MiniMessage miniMessage,
                                   @NotNull String name,
                                   @NotNull List<String> lore,
                                   boolean glint,
                                   TagResolver... resolvers) {
        return decorate(new ItemStack(material), miniMessage, name, lore, glint, resolvers);
    }

    /**
     * Opisuje gotowy przedmiot, zamiast budować nowy z materiału.
     *
     * <p>Ikona receptury musi wyglądać jak wyrób, a wyrób jest przedmiotem
     * z CustomItems — jego wygląd niesie komponent {@code minecraft:item_model},
     * którego z samego {@link Material} nie da się odtworzyć. {@code editMeta}
     * edytuje kopię istniejącego meta, więc model przeżywa nadanie nazwy i opisu.
     */
    public static @NotNull ItemStack decorate(@NotNull ItemStack base,
                                   @NotNull MiniMessage miniMessage,
                                   @NotNull String name,
                                   @NotNull List<String> lore,
                                   boolean glint,
                                   TagResolver... resolvers) {
        ItemStack item = base.clone();
        item.editMeta(meta -> {
            meta.displayName(noItalic(miniMessage.deserialize(name, resolvers)));
            meta.lore(lore.stream()
                    .map(line -> noItalic(miniMessage.deserialize(line, resolvers)))
                    .toList());
            meta.setEnchantmentGlintOverride(glint);
        });
        return item;
    }

    public static @NotNull Component component(@NotNull MiniMessage miniMessage,
                                        @NotNull String input,
                                        TagResolver... resolvers) {
        return noItalic(miniMessage.deserialize(input, resolvers));
    }

    public static @NotNull String money(long amount) {
        long absolute = amount == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(amount);
        long lastTwo = absolute % 100L;
        long last = absolute % 10L;
        String unit = absolute == 1L
                ? "moneta"
                : last >= 2L && last <= 4L && (lastTwo < 12L || lastTwo > 14L)
                ? "monety"
                : "monet";
        return INTEGER.get().format(amount) + ' ' + unit;
    }
    /**
     * Ikona monet + sformatowana kwota do lore menu — złota linia gotowa
     * do wklejenia w lore; kolor nadaje price, nie wołający.
     */
    public static @NotNull String price(long amount) {
        return ICON_COINS + " <gold>" + money(amount) + "</gold>";
    }

    public static @NotNull Component noItalic(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }
}
