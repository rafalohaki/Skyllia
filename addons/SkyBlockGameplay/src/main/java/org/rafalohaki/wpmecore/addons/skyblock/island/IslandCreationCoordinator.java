package org.rafalohaki.wpmecore.addons.skyblock.island;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.rafalohaki.wpmecore.addons.skyblock.islandprofile.ProfileStateService;
import org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.SkylliaIntegration;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class IslandCreationCoordinator {

    private final JavaPlugin plugin;
    private final SkylliaIntegration skyllia;
    private final ProfileStateService profiles;
    private final IslandModeFlags flags;
    /** Okno janitora operacji terminalnych (sekundy) — wstrzykiwane dla testów. */
    private final long reapDelaySeconds;
    private final Map<UUID, CreationOperation> operations = new ConcurrentHashMap<>();
    /** SKY-1: rejestracja magicznego bloku OneBlock po udanym paste (coordinator → OneBlockModule). */
    private volatile java.util.function.BiConsumer<UUID, org.bukkit.Location> oneBlockRegistrar;

    public IslandCreationCoordinator(@NotNull JavaPlugin plugin,
                                     @NotNull SkylliaIntegration skyllia,
                                     @NotNull ProfileStateService profiles,
                                     @NotNull IslandModeFlags flags) {
        this(plugin, skyllia, profiles, flags, 60);
    }

    /** Wariant z wstrzykniętym oknem janitora (sekundy) — regresja IslandCreationJanitorTest. */
    IslandCreationCoordinator(@NotNull JavaPlugin plugin,
                              @NotNull SkylliaIntegration skyllia,
                              @NotNull ProfileStateService profiles,
                              @NotNull IslandModeFlags flags,
                              long reapDelaySeconds) {
        this.plugin = plugin;
        this.skyllia = skyllia;
        this.profiles = profiles;
        this.flags = flags;
        this.reapDelaySeconds = reapDelaySeconds;
    }

    public record CreationOperation(
            @NotNull String operationId,
            @NotNull IslandMode mode,
            @NotNull IslandCreationState state,
            long createdAt
    ) { }

    public @NotNull IslandCreationState stateOf(@NotNull UUID playerId) {
        CreationOperation op = operations.get(playerId);
        if (op != null) return op.state();
        if (skyllia.islandOf(playerId).isPresent()) return IslandCreationState.READY;
        return IslandCreationState.IDLE;
    }

    public @Nullable CreationOperation operationOf(@NotNull UUID playerId) {
        return operations.get(playerId);
    }

    public @NotNull CompletableFuture<CreateResult> create(@NotNull Player player, @NotNull String rawType) {
        UUID uuid = player.getUniqueId();
        String normalized = rawType == null ? "" : rawType.toLowerCase(Locale.ROOT).trim();
        IslandMode mode = IslandMode.parse(normalized);
        if (mode == null) {
            if ("starter".equals(normalized)) mode = IslandMode.CLASSIC;
        }
        if (mode == null) {
            return CompletableFuture.completedFuture(CreateResult.unknownType(normalized));
        }
        final IslandMode finalMode = mode;
        // Parallel clicks create one operation (atomic check)
        CreationOperation existing = operations.get(uuid);
        if (existing != null && existing.state() != IslandCreationState.FAILED && existing.state() != IslandCreationState.READY) {
            return CompletableFuture.completedFuture(CreateResult.alreadyCreating(existing));
        }
        // SKY-2: soft-deleted wyspa Skylli blokuje check islandOf i UNIQUE(island_id)
        // przy re-create — czyszczenie musi biec PRZED check'iem "ma wyspę".
        // -1 = purge nieudany (SQLException): przerywamy z wyraźnym błędem,
        // bo inaczej gracz dostawał mylące "Masz juz aktywna wyspe".
        if (skyllia.purgeSoftDeletedIsland(uuid) < 0) {
            plugin.getLogger().warning("SKY-2: purge soft-deleted wyspy nie powiodl sie, create przerwany player=" + uuid);
            return CompletableFuture.completedFuture(CreateResult.failure(
                    "Czyszczenie poprzedniej wyspy nie powiodlo sie. Sprobuj ponownie za chwile."));
        }
        if (skyllia.islandOf(uuid).isPresent()) {
            return CompletableFuture.completedFuture(CreateResult.alreadyHasIsland());
        }
        if (!flags.isEnabled(finalMode)) {
            return CompletableFuture.completedFuture(CreateResult.modeDisabled(finalMode));
        }
        String operationId = "island-create:" + uuid + ":" + System.currentTimeMillis() + ":" + UUID.randomUUID().toString().substring(0, 8);
        CreationOperation op = new CreationOperation(operationId, finalMode, IslandCreationState.CREATING, System.currentTimeMillis());
        CreationOperation raced = operations.putIfAbsent(uuid, op);
        if (raced != null && raced.state() != IslandCreationState.FAILED && raced.state() != IslandCreationState.READY) {
            return CompletableFuture.completedFuture(CreateResult.alreadyCreating(raced));
        }
        if (raced != null) {
            operations.put(uuid, op);
        }
        plugin.getLogger().info("Island create started operationId=" + operationId + " player=" + uuid + " mode=" + finalMode.id());
        return CompletableFuture.supplyAsync(() -> {
            updateState(uuid, IslandCreationState.CREATING);
            return null;
        }).thenCompose(ignored -> doCreate(player, finalMode, operationId));
    }

    private CompletableFuture<CreateResult> doCreate(Player player, IslandMode mode, String operationId) {
        UUID uuid = player.getUniqueId();
        final org.bukkit.Location[] anchorHolder = new org.bukkit.Location[]{null};
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (skyllia.islandOf(uuid).isPresent()) {
                    updateState(uuid, IslandCreationState.FAILED);
                    return CreateResult.alreadyHasIsland();
                }
                if (!flags.isEnabled(mode)) {
                    updateState(uuid, IslandCreationState.FAILED);
                    return CreateResult.modeDisabled(mode);
                }
                boolean created;
                try {
                    created = skyllia.createIsland(uuid, mode.skylliaTemplate(), player.getName());
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Skyllia createIsland failed operationId=" + operationId, e);
                    updateState(uuid, IslandCreationState.FAILED);
                    return CreateResult.failure("Skyllia create failed: " + e.getMessage());
                }
                if (!created) {
                    updateState(uuid, IslandCreationState.FAILED);
                    if (skyllia.islandOf(uuid).isPresent()) {
                        updateState(uuid, IslandCreationState.READY);
                        return CreateResult.success(mode, operationId);
                    }
                    return CreateResult.failure("Utworzenie wyspy nie powiodlo sie.");
                }
                updateState(uuid, IslandCreationState.INITIALIZING);
                var view = skyllia.islandOf(uuid);
                if (view.isEmpty()) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    view = skyllia.islandOf(uuid);
                }
                if (view.isPresent()) {
                    UUID islandId = view.get().islandId();
                    try {
                        Boolean ok = profiles.tryCreateIsland(uuid, islandId, mode.id().toUpperCase(Locale.ROOT))
                                .get(15, java.util.concurrent.TimeUnit.SECONDS);
                        if (Boolean.FALSE.equals(ok)) {
                            plugin.getLogger().warning("Profile guard rejected island " + islandId + " for " + uuid);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING, "Profile insert failed for " + islandId, e);
                    }
                    // SKY-1: paste schematów wyspy. SkylliaAPI.createIsland przydziela
                    // region, ale NIE wkleja schematu (to robi tylko własny /is create
                    // Skylli) — bez tego każda wyspa jest void (produkcja i lab).
                    anchorHolder[0] = pasteSchematics(uuid, islandId, mode);
                }
                boolean pasteEnabled = plugin.getConfig().getBoolean("island.paste.enabled", true);
                if (pasteEnabled && anchorHolder[0] == null) {
                    // Produkcja 2026-08-28: paste potrafi zejść po cichu (zero warningów,
                    // chunki puste) — SUCCESS + „is home” kończył się teleportem w pustkę
                    // i śmiercią gracza. Twarda bramka: bez zweryfikowanego świata nie ma
                    // sukcesu; pół-wyspa (region + wiersze SQL bez bloków) jest usuwana,
                    // więc gracz może od razu spróbować ponownie.
                    plugin.getLogger().severe("Island create " + operationId + ": świat wyspy nie zweryfikowany (paste/sygnatura) — wycofuję tworzenie");
                    skyllia.discardBrokenIsland(uuid);
                    skyllia.invalidateIslandCache(uuid);
                    updateState(uuid, IslandCreationState.FAILED);
                    return CreateResult.failure(
                            "Tworzenie wyspy nie powiodlo sie (swiat nie zostal zbudowany). Sprobuj ponownie za chwile.");
                }
                if (anchorHolder[0] != null) {
                    // SkylliaAPI.createIsland (w odróżnieniu od wbudowanego /is create)
                    // nie tworzy warpów home/spawn — bez nich /is home spada na fallback
                    // „centr regionu Y=64 +0.5”, czyli stopy W blok (top na Y+1) i gracz
                    // jest wypychany obok wyspy. Reprodukujemy to, co robi natywny create:
                    // warp home + spawn na 0.5 nad powierzchnią (HomeSubCommand doda swoje
                    // +0.5 i stopy staną dokładnie na topie bloku).
                    skyllia.setupIslandHome(uuid, anchorHolder[0]);
                }
                try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                updateState(uuid, IslandCreationState.READY);
                skyllia.invalidateIslandCache(uuid);
                return CreateResult.success(mode, operationId, anchorHolder[0]);
            } catch (Exception e) {
                updateState(uuid, IslandCreationState.FAILED);
                return CreateResult.failure(e.getMessage());
            }
        });
    }

    /**
     * SKY-1: wkleja szablony wyspy we wszystkich światach skyblock. Szablony
     * w formacie JSON Skylli (plugin=Internal), czytane z folderu danych Skylli.
     * Mapowanie tryb/świat → plik: config {@code island.paste.templates}
     * z sensownymi domyślnymi wartościami poniżej.
     *
     * Zwraca zweryfikowany anchor w świecie NORMALnym (kandydat na teleport
     * po utworzeniu): dla OneBlock tylko gdy sygnatura grass-on-bedrock
     * fizycznie stoi w świecie, dla pozostałych trybów — centrum z Skylli.
     * {@code null} oznacza „nie mamy zaufanego punktu wyspy”.
     */
    public void setOneBlockRegistrar(@NotNull java.util.function.BiConsumer<UUID, org.bukkit.Location> registrar) {
        this.oneBlockRegistrar = registrar;
    }

    private @Nullable org.bukkit.Location pasteSchematics(@NotNull UUID playerId, @NotNull UUID islandId, @NotNull IslandMode mode) {
        if (!plugin.getConfig().getBoolean("island.paste.enabled", true)) {
            return null;
        }
        org.bukkit.plugin.Plugin skylliaPlugin = org.bukkit.Bukkit.getPluginManager().getPlugin("Skyllia");
        if (skylliaPlugin == null) {
            plugin.getLogger().warning("Paste pominięty: plugin Skyllia niedostępny");
            return null;
        }
        java.nio.file.Path base = skylliaPlugin.getDataFolder().toPath();
        java.util.concurrent.atomic.AtomicReference<org.bukkit.Location> verified =
                new java.util.concurrent.atomic.AtomicReference<>();
        for (org.bukkit.World world : org.bukkit.Bukkit.getWorlds()) {
            if (!skyllia.isSkyblockWorld(world.getName())) {
                continue;
            }
            String rel = plugin.getConfig().getString(
                    "island.paste.templates." + mode.id() + "." + world.getName(),
                    defaultTemplate(mode, world));
            if (rel == null || rel.isBlank()) {
                continue;
            }
            java.nio.file.Path file = base.resolve(rel);
            if (!java.nio.file.Files.exists(file)) {
                plugin.getLogger().warning("Paste: brak szablonu " + file + " (świat " + world.getName() + ")");
                continue;
            }
            skyllia.islandCenterLocation(islandId, world).ifPresentOrElse(anchor -> {
                try {
                    plugin.getLogger().info("Paste start: " + rel + " -> " + world.getName()
                            + " anchor=(" + anchor.getBlockX() + "," + anchor.getBlockY() + "," + anchor.getBlockZ() + ")");
                    CompletableFuture<Void> pasted =
                            org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration.JsonSchematicPaster
                                    .paste(plugin, world, anchor, file);
                    pasted.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    // FOLIA-THREADING: weryfikacja bloków wyłącznie na wątku
                    // właściciela regionu anchora.
                    CompletableFuture<org.bukkit.Location> checked = new CompletableFuture<>();
                    org.bukkit.Bukkit.getRegionScheduler().run(plugin, world,
                            anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4, task -> {
                                try {
                                    checked.complete(verifyPastedWorld(world, islandId, mode, anchor, rel));
                                } catch (Throwable t) {
                                    checked.completeExceptionally(t);
                                }
                            });
                    org.bukkit.Location surface = checked.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (surface != null && world.getEnvironment() == org.bukkit.World.Environment.NORMAL) {
                        verified.set(surface);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING,
                            "Paste schematu " + rel + " dla " + playerId + " w " + world.getName(), e);
                }
            }, () -> plugin.getLogger().warning(
                    "Paste: brak centrum wyspy " + islandId + " w świecie " + world.getName()));
        }
        return verified.get();
    }

    /**
     * Weryfikacja na wątku regionu; zwraca anchor POWIERZCHNI (blok, na którym
     * staną stopy = Y bloku powierzchni) albo {@code null}, gdy świata nie ma.
     * Dla OneBlock — twarda sygnatura grass-on-bedrock w centrum (jej brak =
     * pustka = tworzenie musi się nie powieść zamiast teleportować gracza
     * w void); rejestracja OneBlocka wyłącznie po pozytywnej sygnaturze.
     * Dla pozostałych trybów — pomiar realnego wierzchołka terenu w centrum.
     */
    private @Nullable org.bukkit.Location verifyPastedWorld(@NotNull org.bukkit.World world,
                                                            @NotNull UUID islandId,
                                                            @NotNull IslandMode mode,
                                                            @NotNull org.bukkit.Location anchor,
                                                            @NotNull String templateRel) {
        if (world.getEnvironment() != org.bukkit.World.Environment.NORMAL) {
            return anchor;
        }
        int cx = anchor.getBlockX();
        int cy = anchor.getBlockY();
        int cz = anchor.getBlockZ();
        if (mode != IslandMode.ONEBLOCK) {
            int topY = world.getHighestBlockYAt(cx, cz);
            if (topY < world.getMinHeight()) {
                plugin.getLogger().severe("Paste " + templateRel + ": centrum wyspy " + islandId
                        + " (" + cx + "," + cz + ") jest puste — tworzenie zostanie wycofane");
                return null;
            }
            plugin.getLogger().info("Paste: powierzchnia wyspy " + islandId + " w " + world.getName()
                    + " na Y=" + topY);
            return new org.bukkit.Location(world, cx, topY, cz);
        }
        // Sygnatura może stać na wysokości centrum (cy) albo — po korekcie
        // szablonu do konwencji Skyllii (powierzchnia na 63: warp trzyma INT-y,
        // a HomeSubCommand dodaje tylko +0.5Y) — o jeden blok niżej (cy-1).
        for (int gy : new int[]{cy, cy - 1}) {
            if (world.getBlockAt(cx, gy, cz).getType() == org.bukkit.Material.GRASS_BLOCK
                    && world.getBlockAt(cx, gy - 1, cz).getType() == org.bukkit.Material.BEDROCK) {
                // SKY-1: sygnatura fizycznie w świecie — rejestracja magicznego bloku
                // jest deterministyczna (detektor IslandFluidInitializer przegrywa
                // wyścig: jego okno retry ~1,3 s vs paste 12500 bloków).
                if (oneBlockRegistrar != null) {
                    oneBlockRegistrar.accept(islandId, new org.bukkit.Location(world, cx, gy, cz));
                    plugin.getLogger().info("OneBlock zarejestrowany w centrum (" + cx + "," + gy + "," + cz
                            + ") wyspy " + islandId);
                }
                return new org.bukkit.Location(world, cx, gy, cz);
            }
        }
        plugin.getLogger().severe("Paste OneBlock: brak sygnatury grass-on-bedrock w centrum "
                + islandId + " (" + cx + "," + cy + "/" + (cy - 1) + "," + cz + ") — szablon " + templateRel
                + " nie wkleił się; tworzenie zostanie wycofane (wyspa usunięta)");
        return null;
    }

    private static @NotNull String defaultTemplate(@NotNull IslandMode mode, @NotNull org.bukkit.World world) {
        if (mode == org.rafalohaki.wpmecore.addons.skyblock.island.IslandMode.ONEBLOCK
                && world.getEnvironment() == org.bukkit.World.Environment.NORMAL) {
            return "schematics/oneblock-overworld.json";
        }
        return switch (world.getEnvironment()) {
            case NETHER -> "schematics/starter-nether.json";
            case THE_END -> "schematics/starter-end.json";
            default -> "schematics/starter-overworld.json";
        };
    }

    private void updateState(UUID playerId, IslandCreationState newState) {
        operations.computeIfPresent(playerId, (k, old) -> new CreationOperation(old.operationId(), old.mode(), newState, old.createdAt()));
        if (newState == IslandCreationState.READY || newState == IslandCreationState.FAILED) {
            CompletableFuture.delayedExecutor(reapDelaySeconds, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                CreationOperation cur = operations.get(playerId);
                // Janitor: sprzątamy każdą operację terminalną bez wyspy — także
                // READY (wyspa usunięta po udanym create; wcześniej READY był
                // nieśmiertelny i blokował potwierdzenie do restartu).
                // CREATING/INITIALIZING pozostają chronione — żywa operacja.
                if (cur != null && (cur.state() == IslandCreationState.READY
                        || cur.state() == IslandCreationState.FAILED)
                        && skyllia.islandOf(playerId).isEmpty()) {
                    operations.remove(playerId, cur);
                }
            });
        }
    }

    /**
     * Usuwa martwą operację terminalną gracza: FAILED zawsze, READY tylko gdy
     * wyspa faktycznie nie istnieje (usunięta po udanym create). Wołane ze
     * ścieżki purge/usunięcia wyspy ({@code openPicker}/{@code create}), żeby
     * wpis nie przetrwał do okna janitora i nie renderował martwego ekranu
     * „Tworzenie już w toku”. CREATING/INITIALIZING pozostają chronione.
     */
    public void clearFailed(@NotNull UUID playerId) {
        CreationOperation op = operations.get(playerId);
        if (op == null) return;
        boolean stale = op.state() == IslandCreationState.FAILED
                || (op.state() == IslandCreationState.READY
                        && skyllia.islandOf(playerId).isEmpty());
        if (stale) {
            operations.remove(playerId);
        }
    }

    public static final class CreateResult {
        public enum Kind { SUCCESS, ALREADY_HAS_ISLAND, MODE_DISABLED, UNKNOWN_TYPE, ALREADY_CREATING, FAILURE }
        private final Kind kind;
        private final IslandMode mode;
        private final String operationId;
        private final String message;
        private final CreationOperation existingOp;
        /** Zweryfikowany anchor powierzchni wyspy (cel teleportu po create); null gdy brak. */
        private final org.bukkit.Location anchor;

        private CreateResult(Kind kind, IslandMode mode, String operationId, String message, CreationOperation existingOp) {
            this(kind, mode, operationId, message, existingOp, null);
        }

        private CreateResult(Kind kind, IslandMode mode, String operationId, String message, CreationOperation existingOp,
                             org.bukkit.Location anchor) {
            this.kind = kind;
            this.mode = mode;
            this.operationId = operationId;
            this.message = message;
            this.existingOp = existingOp;
            this.anchor = anchor;
        }
        public static CreateResult success(IslandMode mode, String opId) {
            return success(mode, opId, null);
        }
        public static CreateResult success(IslandMode mode, String opId, org.bukkit.Location anchor) {
            return new CreateResult(Kind.SUCCESS, mode, opId, "Wyspa utworzona: " + mode.id(), null, anchor);
        }
        public static CreateResult alreadyHasIsland() {
            return new CreateResult(Kind.ALREADY_HAS_ISLAND, null, null, "Masz juz aktywna wyspe. Mozesz miec tylko jedna.", null);
        }
        public static CreateResult modeDisabled(IslandMode mode) {
            return new CreateResult(Kind.MODE_DISABLED, mode, null, "Tryb " + mode.id() + " jest w konserwacji.", null);
        }
        public static CreateResult unknownType(String raw) {
            return new CreateResult(Kind.UNKNOWN_TYPE, null, null, "Nieznany typ wyspy: " + raw, null);
        }
        public static CreateResult alreadyCreating(CreationOperation op) {
            return new CreateResult(Kind.ALREADY_CREATING, op.mode(), op.operationId(), "Tworzenie juz w toku: " + op.state(), op);
        }
        public static CreateResult failure(String msg) {
            return new CreateResult(Kind.FAILURE, null, null, msg, null);
        }
        public Kind kind() { return kind; }
        public @Nullable IslandMode mode() { return mode; }
        public @Nullable String operationId() { return operationId; }
        public @NotNull String message() { return message; }
        public @Nullable CreationOperation existingOp() { return existingOp; }
        public @Nullable org.bukkit.Location anchor() { return anchor; }
        public boolean isSuccess() { return kind == Kind.SUCCESS; }
    }
}
