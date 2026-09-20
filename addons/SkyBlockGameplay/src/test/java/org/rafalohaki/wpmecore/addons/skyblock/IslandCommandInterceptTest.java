package org.rafalohaki.wpmecore.addons.skyblock;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.objenesis.ObjenesisStd;
import org.rafalohaki.wpmecore.addons.skyblock.island.IslandCenterMenu;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandRole;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandSnapshot;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.IslandView;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Routing komend wyspowych przed dotarciem do Skyllii:
 * <ul>
 *   <li>gołe {@code /is}: bez wyspy kreator trybów, z wyspą szybki teleport
 *       ({@code is home} przez hop na wątek encji — Centrum przeniesione na
 *       {@code /is panel});</li>
 *   <li>{@code /is panel} (dokładnie dwa tokeny): Centrum Wyspy z wyspą,
 *       kreator bez niej.</li>
 * </ul>
 *
 * <p>The "no arguments" half of the bare rule is load-bearing rather than
 * cosmetic: {@code Player#performCommand} fires {@code PlayerCommandPreprocessEvent}
 * too, and the picker itself runs {@code is create <type>} plus the post-create
 * teleport runs {@code is home}. Matching commands that carry arguments would
 * make those re-enter these handlers forever.
 *
 * <p>Część behawioralna: prawdziwy {@code onIslandCommand} na egzemplarzu
 * {@link SkyBlockGameplay} wyciętym bez konstruktora (Objenesis — konstruktor
 * {@link JavaPlugin} wymaga działającego Paper), z polami wstrzykniętymi
 * refleksją. Czysty Mockito dla współpracowników, bez MockBukkit.
 */
class IslandCommandInterceptTest {

    private SkyBlockGameplay gameplay;
    private SkylliaIntegration skyllia;
    private SkyBlockMenus mainMenus;
    private IslandCenterMenu centerMenu;

    @BeforeEach
    void setUp() throws Exception {
        gameplay = new ObjenesisStd().newInstance(SkyBlockGameplay.class);
        skyllia = mock(SkylliaIntegration.class);
        mainMenus = mock(SkyBlockMenus.class);
        centerMenu = mock(IslandCenterMenu.class);
        inject("skyllia", skyllia);
        inject("mainMenu", mainMenus);
        inject("islandCenter", centerMenu);
        inject("miniMessage", MiniMessage.miniMessage());
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field field = SkyBlockGameplay.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(gameplay, value);
    }

    private Player playerWithIsland(boolean present) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(skyllia.islandOf(player.getUniqueId())).thenReturn(present ? Optional.of(fakeView()) : Optional.empty());
        return player;
    }

    /** /is (gołe) z wyspą: ma teleportować (is home), NIE otwierać Centrum. */
    @Test
    @DisplayName("gołe /is z wyspą: cancel + hop na wątek encji → performCommand(\"is home\")")
    void bareIslandWithIslandTeleportsHomeInsteadOfOpeningCenter() {
        Player player = playerWithIsland(true);
        when(player.isOnline()).thenReturn(true);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        AtomicReference<Consumer<ScheduledTask>> hop = new AtomicReference<>();
        when(entityScheduler.run(any(), any(), any())).thenAnswer(inv -> {
            hop.set(inv.getArgument(1));
            return mock(ScheduledTask.class);
        });
        when(player.getScheduler()).thenReturn(entityScheduler);
        PlayerCommandPreprocessEvent event = interceptEvent("/is", player);

        gameplay.onIslandCommand(event);

        verify(event).setCancelled(true);
        verify(centerMenu, never()).open(any());
        verifyNoInteractions(mainMenus);
        assertNotNull(hop.get(), "teleport ma być planowany przez scheduler encji gracza");
        hop.get().accept(null);
        verify(player).performCommand("is home");
    }

    /** /is panel z wyspą: anulowane i otwiera Centrum Wyspy. */
    @Test
    @DisplayName("/is panel z wyspą: cancel + otwiera Centrum Wyspy")
    void panelWithIslandOpensCenterAndCancels() {
        Player player = playerWithIsland(true);
        PlayerCommandPreprocessEvent event = interceptEvent("/is panel", player);

        gameplay.onIslandCommand(event);

        verify(event).setCancelled(true);
        verify(centerMenu).open(player);
        verify(mainMenus, never()).openIslandCreationPicker(any());
    }

    /** /is panel bez wyspy: mirror gołego /is — kreator trybów. */
    @Test
    @DisplayName("/is panel bez wyspy: cancel + kreator (mirror gołego /is)")
    void panelWithoutIslandOpensCreationPicker() {
        // brak IdentityService (getServer() nullem → identityStatus zwraca CLEAR)
        // → ścieżka identycznościowa przepuszcza do kreatora, w miejscu, bez hopu
        Player player = playerWithIsland(false);
        PlayerCommandPreprocessEvent event = interceptEvent("/is panel", player);

        gameplay.onIslandCommand(event);

        verify(event).setCancelled(true);
        verify(mainMenus).openIslandCreationPicker(player);
        verify(centerMenu, never()).open(any());
    }

    /** Warianty nie-dokładne mają przejść nietknięte do Skyllii. */
    @Test
    @DisplayName("/is panels i /is panel x: przechodzą nietknięte")
    void nonExactPanelFormsPassThroughUntouched() {
        for (String message : new String[]{"/is panels", "/is panel x", "/is panel x y"}) {
            Player player = playerWithIsland(false);
            PlayerCommandPreprocessEvent event = interceptEvent(message, player);

            gameplay.onIslandCommand(event);

            verify(event, never()).setCancelled(anyBoolean());
            verifyNoInteractions(centerMenu, mainMenus);
            verify(player, never()).performCommand(anyString());
        }
    }

    private PlayerCommandPreprocessEvent interceptEvent(String message, Player player) {
        PlayerCommandPreprocessEvent event = mock(PlayerCommandPreprocessEvent.class);
        when(event.getMessage()).thenReturn(message);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }

    private static IslandView fakeView() {
        return new IslandView(new IslandSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                null, null, null, null, 0L), IslandRole.OWNER);
    }

    @Test
    void matchesEveryAliasSkylliaRegisters() {
        assertTrue(SkyBlockGameplay.isBareIslandCommand("/is"));
        assertTrue(SkyBlockGameplay.isBareIslandCommand("/skyllia"));
        assertTrue(SkyBlockGameplay.isBareIslandCommand("/ob"));
    }

    @Test
    void isCaseInsensitiveAndIgnoresSurroundingWhitespace() {
        assertTrue(SkyBlockGameplay.isBareIslandCommand("/IS"));
        assertTrue(SkyBlockGameplay.isBareIslandCommand("/Is   "));
    }

    @Test
    void leavesAnyCommandWithArgumentsAlone() {
        assertFalse(SkyBlockGameplay.isBareIslandCommand("/is create starter"));
        assertFalse(SkyBlockGameplay.isBareIslandCommand("/is create oneblock"));
        assertFalse(SkyBlockGameplay.isBareIslandCommand("/is home"));
        assertFalse(SkyBlockGameplay.isBareIslandCommand("/is  create  oneblock"));
    }

    @Test
    void ignoresUnrelatedCommands() {
        assertFalse(SkyBlockGameplay.isBareIslandCommand("/island"));
        assertFalse(SkyBlockGameplay.isBareIslandCommand("/issue"));
        assertFalse(SkyBlockGameplay.isBareIslandCommand("/sklep"));
        assertFalse(SkyBlockGameplay.isBareIslandCommand("/obsidian"));
    }

    @Test
    void ignoresChatAndMalformedInput() {
        assertFalse(SkyBlockGameplay.isBareIslandCommand("is"));
        assertFalse(SkyBlockGameplay.isBareIslandCommand("czesc, /is jest fajne"));
        assertFalse(SkyBlockGameplay.isBareIslandCommand("/"));
        assertFalse(SkyBlockGameplay.isBareIslandCommand(""));
    }

    @Test
    @DisplayName("matcher /is panel: wszystkie aliasy, dokładnie dwa tokeny")
    void matchesPanelOnEveryAliasExactlyTwoTokens() {
        assertTrue(SkyBlockGameplay.isPanelCommand("/is panel"));
        assertTrue(SkyBlockGameplay.isPanelCommand("/skyllia panel"));
        assertTrue(SkyBlockGameplay.isPanelCommand("/ob panel"));
        assertTrue(SkyBlockGameplay.isPanelCommand("/IS Panel "));
        assertTrue(SkyBlockGameplay.isPanelCommand("/ob   panel"));
    }

    @Test
    @DisplayName("matcher /is panel: formy niedokładne i obce nie pasują")
    void panelMatcherLeavesOtherFormsAlone() {
        assertFalse(SkyBlockGameplay.isPanelCommand("/is panels"));
        assertFalse(SkyBlockGameplay.isPanelCommand("/is panel x"));
        assertFalse(SkyBlockGameplay.isPanelCommand("/is panel x y"));
        assertFalse(SkyBlockGameplay.isPanelCommand("/panel"));
        assertFalse(SkyBlockGameplay.isPanelCommand("/island panel"));
        assertFalse(SkyBlockGameplay.isPanelCommand("/is home"));
    }

    // --- F19: podkomendy, których ta Skyllia nie ma ---

    /**
     * Skyllia 3.0-163 nie ma w jarze ani {@code HelpSubCommand}, ani dodatku
     * bank, a na nieznaną podkomendę odpowiada „Taka podkomenda nie istnieje.
     * Użyj /is help." — czyli odsyła do siebie samej. Oba wejścia muszą trafiać
     * w nasz przechwytywacz, inaczej zgubiony gracz kręci się w kółko.
     */
    @Test
    @DisplayName("matcher /is help i /is bank: wszystkie aliasy wyspy")
    void matchesHelpAndBankOnEveryAlias() {
        assertTrue(SkyBlockGameplay.isIslandSubCommand("/is help", "help", "pomoc"));
        assertTrue(SkyBlockGameplay.isIslandSubCommand("/skyllia POMOC", "help", "pomoc"));
        assertTrue(SkyBlockGameplay.isIslandSubCommand("/ob   help  ", "help", "pomoc"));
        assertTrue(SkyBlockGameplay.isIslandSubCommand("/is bank", "bank"));
        assertTrue(SkyBlockGameplay.isIslandSubCommand("/IS Bank", "bank"));
    }

    @Test
    @DisplayName("matcher /is help i /is bank: obce formy zostają Skyllii")
    void helpAndBankMatcherLeavesOtherFormsAlone() {
        assertFalse(SkyBlockGameplay.isIslandSubCommand("/is helper", "help", "pomoc"));
        assertFalse(SkyBlockGameplay.isIslandSubCommand("/is help me", "help", "pomoc"));
        assertFalse(SkyBlockGameplay.isIslandSubCommand("/help", "help", "pomoc"));
        assertFalse(SkyBlockGameplay.isIslandSubCommand("/island help", "help", "pomoc"));
        assertFalse(SkyBlockGameplay.isIslandSubCommand("/is bank wplac", "bank"));
        assertFalse(SkyBlockGameplay.isIslandSubCommand("bank", "bank"));
        assertFalse(SkyBlockGameplay.isIslandSubCommand("", "bank"));
    }

    // --- F16: konflikt nicku sprawdzany bez blokowania wątku regionu ---

    /**
     * Odtwarza awarię z produkcji w jej najczystszej postaci: IdentityService
     * odpowiada <b>prawdę</b> — „nick czysty" — tylko po 900 ms, czyli dłużej niż
     * dawny limit {@code get(500 ms)}. Stara wersja zgłaszała wtedy fail-closed i
     * mówiła graczowi o konflikcie nicku, którego nie było.
     */
    @Test
    @DisplayName("F16: spóźniona odpowiedź IdentityService nie zjada komendy — kreator i tak się otwiera")
    void slowIdentityCheckStillOpensThePicker() throws Exception {
        installIdentityService(uuid -> CompletableFuture.supplyAsync(() -> Boolean.FALSE,
                CompletableFuture.delayedExecutor(900L, TimeUnit.MILLISECONDS)));
        Player player = playerWithIsland(false);
        runEntitySchedulerInline(player);
        CountDownLatch opened = new CountDownLatch(1);
        doAnswer(invocation -> {
            opened.countDown();
            return null;
        }).when(mainMenus).openIslandCreationPicker(player);

        gameplay.onIslandCommand(interceptEvent("/is panel", player));

        assertTrue(opened.await(10L, TimeUnit.SECONDS),
                "kreator ma się otworzyć mimo spóźnionej odpowiedzi IdentityService");
    }

    /** Fail-closed bez zmian: prawdziwy konflikt nadal wstrzymuje operację. */
    @Test
    @DisplayName("F16: wykryty konflikt nicku blokuje kreator i mówi wprost o konflikcie")
    void detectedConflictBlocksThePicker() throws Exception {
        installIdentityService(uuid -> CompletableFuture.completedFuture(Boolean.TRUE));
        Player player = playerWithIsland(false);

        gameplay.onIslandCommand(interceptEvent("/is panel", player));

        verify(mainMenus, never()).openIslandCreationPicker(any());
        assertTrue(lastMessageOf(player).contains("konflikt nicku"),
                "gracz ma usłyszeć o konflikcie nicku");
    }

    /**
     * Druga połowa rozdzielenia komunikatów: nieudana weryfikacja też jest
     * fail-closed, ale nie wolno jej udawać wykrytego konfliktu — dawniej obie
     * sytuacje mówiły graczowi dokładnie to samo.
     */
    @Test
    @DisplayName("F16: nieudana weryfikacja blokuje, ale własnym komunikatem")
    void unverifiedIdentityBlocksWithItsOwnMessage() throws Exception {
        installIdentityService(uuid -> CompletableFuture.failedFuture(new IllegalStateException("baza padła")));
        Player player = playerWithIsland(false);

        gameplay.onIslandCommand(interceptEvent("/is panel", player));

        verify(mainMenus, never()).openIslandCreationPicker(any());
        String said = lastMessageOf(player);
        assertTrue(said.contains("zweryfikować tożsamości"),
                "nieudana weryfikacja ma się przedstawiać jako taka, było: " + said);
        assertFalse(said.contains("konflikt nicku"),
                "„nie zweryfikowano” nie może udawać wykrytego konfliktu");
    }

    /** Podstawia IdentityService pod {@code getServer().getServicesManager()} instancji z Objenesis. */
    private void installIdentityService(Function<UUID, CompletableFuture<Boolean>> answer) throws Exception {
        org.rafalohaki.wpmecore.api.identity.IdentityService identity =
                mock(org.rafalohaki.wpmecore.api.identity.IdentityService.class);
        when(identity.isConflicted(any())).thenAnswer(invocation -> answer.apply(invocation.getArgument(0)));
        ServicesManager services = mock(ServicesManager.class);
        when(services.load(org.rafalohaki.wpmecore.api.identity.IdentityService.class)).thenReturn(identity);
        Server server = mock(Server.class);
        when(server.getServicesManager()).thenReturn(services);
        injectPluginField("server", server);
        injectPluginField("logger", Logger.getLogger("IslandCommandInterceptTest"));
    }

    /** Pola siedzą w JavaPlugin, nie w SkyBlockGameplay — stąd osobny wstrzykiwacz. */
    private void injectPluginField(String fieldName, Object value) throws Exception {
        Field field = JavaPlugin.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(gameplay, value);
    }

    /** Scheduler encji, który wykonuje zadanie od razu — kontynuacja ma dokąd wrócić. */
    private void runEntitySchedulerInline(Player player) {
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        when(entityScheduler.run(any(), any(), any())).thenAnswer(invocation -> {
            Consumer<ScheduledTask> task = invocation.getArgument(1);
            task.accept(null);
            return mock(ScheduledTask.class);
        });
        when(player.getScheduler()).thenReturn(entityScheduler);
    }

    private String lastMessageOf(Player player) {
        ArgumentCaptor<Component> said = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(said.capture());
        return PlainTextComponentSerializer.plainText().serialize(said.getValue());
    }
}
