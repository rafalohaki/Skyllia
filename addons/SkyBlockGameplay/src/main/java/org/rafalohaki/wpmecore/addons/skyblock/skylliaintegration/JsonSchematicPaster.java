package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Wkleja schematy wysp w formacie JSON Skylli (schematics/*.json, plugin=Internal).
 *
 * <p><b>Dlaczego to istnieje (SKY-1):</b> {@code SkylliaAPI.createIsland} tworzy
 * rekord wyspy i przydziela region, ale <em>NIE wkleja schematu</em> — paste robi
 * wyłącznie własny flow komendy {@code /is create} Skylli (decompiled:
 * {@code CreateSubCommand.pasteAllSchematics}). WpmeCore tworzy wyspy przez API,
 * więc każda wyspa (produkcja i lab) była void — gracze teleportowani w przepaść.
 *
 * <p><b>Semantyka formatu</b> (odtworzona z {@code InternalSchematicHook}):
 * <ul>
 *   <li>{@code blocks} to RLE: pary {@code [paletteIndex, runLength]}, suma = dx*dy*dz,</li>
 *   <li>kolejność dekodowania: {@code for y → for z → for x} (x najszybsze),</li>
 *   <li>pozycja świata: {@code anchor + (cell - origin)} — komórka {@code origin}
 *       ląduje dokładnie w anchorze (centrum wyspy),</li>
 *   <li>paleta: pełne stan-bloki ("minecraft:chest[facing=east,...]") przez
 *       {@link Bukkit#createBlockData(String)}; air pomijany</li>
 * </ul>
 *
 * <p><b>Chunki:</b> paste idzie przez {@link World#getChunkAtAsync(int, int)} —
 * na Folii to niezawodna droga (ładuje chunk); edycje każdego chunku trafiają
 * następnie na wątek jego właściciela przez {@code RegionScheduler} (jawny hop —
 * wątek kończący future ładowania nie jest gwarantowany), planowanie na region
 * schedulerze bez ładowania odkładałoby paste do czasu wejścia gracza, który
 * w międzyczasie spada w przepaść.
 *
 * <p><b>BlockEntities</b> (starter chest z itemami base64-NBT) są pomijane:
 * blok skrzyni stawia paleta, a zawartość napełnia już
 * {@code IslandFluidInitializer.initializeStarterChestIfPresent}.
 */
public final class JsonSchematicPaster {

    private static final Gson GSON = new Gson();

    /** DTO zgodne polami z formatem JSON Skylli (Gson mapuje po nazwach). */
    record Vec3(int x, int y, int z) { }
    record Size3(int dx, int dy, int dz) { }
    record Schematic(int version, Vec3 origin, Size3 size,
                     List<String> palette, List<int[]> blocks) { }

    /** Dekoduje RLE do listy indeksów palety (długość dx*dy*dz). */
    static int[] decodeRle(@NotNull List<int[]> blocks, int expectedCount) {
        int[] out = new int[expectedCount];
        int i = 0;
        for (int[] pair : blocks) {
            int paletteId = pair[0];
            int run = pair[1];
            for (int r = 0; r < run && i < expectedCount; r++) {
                out[i++] = paletteId;
            }
        }
        return out;
    }

    /**
     * Współrzędne świata dla indeksu liniowego (kolejność y→z→x jak w
     * InternalSchematicHook) względem anchora.
     */
    static int[] worldPos(@NotNull Location anchor, @NotNull Vec3 origin,
                          @NotNull Size3 size, int index) {
        int x = index % size.dx();
        int rest = index / size.dx();
        int z = rest % size.dz();
        int y = rest / size.dz();
        return new int[] {
                anchor.getBlockX() + x - origin.x(),
                anchor.getBlockY() + y - origin.y(),
                anchor.getBlockZ() + z - origin.z() };
    }

    /**
     * Wkleja schemat do świata. Grupuje bloki per-chunk i ładuje chunki
     * asynchronicznie; bloki ustawiane bez fizyki.
     *
     * @return future kończące się po ustawieniu wszystkich bloków (wyjątkowo
     *         po błędzie odczytu/parsowania — pojedynczy zepsuty chunk nie
     *         wywala całości)
     */
    public static @NotNull CompletableFuture<Void> paste(@NotNull Plugin plugin,
                                                         @NotNull World world,
                                                         @NotNull Location anchor,
                                                         @NotNull Path schematicFile) {
        final Schematic schematic;
        try (Reader reader = Files.newBufferedReader(schematicFile, StandardCharsets.UTF_8)) {
            schematic = GSON.fromJson(reader, Schematic.class);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new IOException(
                    "Nie można odczytać schematu " + schematicFile + ": " + e.getMessage(), e));
        }
        if (schematic == null || schematic.palette() == null || schematic.blocks() == null
                || schematic.size() == null || schematic.origin() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Niepoprawny schemat (brakujące pola): " + schematicFile));
        }
        int total = schematic.size().dx() * schematic.size().dy() * schematic.size().dz();
        int[] cells = decodeRle(schematic.blocks(), total);

        // paleta → BlockData raz
        List<BlockData> palette = new ArrayList<>(schematic.palette().size());
        for (String entry : schematic.palette()) {
            palette.add(Bukkit.createBlockData(entry));
        }

        // grupuj niepowietrzne bloki per-chunk (czysta logika — patrz testy)
        Map<Long, List<int[]>> byChunk = groupByChunk(cells,
                paletteIndex -> palette.get(paletteIndex).getMaterial().isAir(),
                anchor, schematic.origin(), schematic.size());

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = byChunk.entrySet().stream()
                .map(entry -> {
                    int cx = (int) (entry.getKey() >> 32);
                    int cz = (int) (long) entry.getKey();
                    List<int[]> blocks = entry.getValue();
                    CompletableFuture<Void> applied = new CompletableFuture<>();
                    world.getChunkAtAsync(cx, cz).whenComplete((chunk, loadError) -> {
                        if (loadError != null || chunk == null) {
                            plugin.getLogger().warning("Paste chunk " + cx + "," + cz
                                    + " nieudany: " + (loadError == null ? "brak chunku" : loadError.getMessage()));
                            applied.complete(null); // kontener awarii pojedynczego chunku
                            return;
                        }
                        // FOLIA-THREADING: edycje bloków wyłącznie na wątku
                        // właściciela regionu — wątek kończący future z
                        // getChunkAtAsync zależy od implementacji, więc jawny hop
                        // przez RegionScheduler gwarantuje legalne setBlockData.
                        Bukkit.getRegionScheduler().run(plugin, world, cx, cz, task -> {
                            try {
                                for (int[] b : blocks) {
                                    chunk.getBlock(b[0] & 15, b[1], b[2] & 15)
                                            .setBlockData(palette.get(b[3]), false);
                                }
                                applied.complete(null);
                            } catch (RuntimeException ex) {
                                plugin.getLogger().warning("Paste chunk " + cx + "," + cz
                                        + " nieudany: " + ex.getMessage());
                                applied.complete(null); // jak wyżej: nie wywalam całości
                            }
                        });
                    });
                    return applied;
                })
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    /**
     * Grupuje komórki schematu per-chunk (czysta logika bez świata — regresja
     * {@code JsonSchematicPasterGroupingTest}): pomija powietrze wg predykatu
     * palety i mapuje każdą komórkę na {@code [x,y,z,paletteId]} pod kluczem
     * chunku ({@link #chunkKey(int, int)}).
     */
    static @NotNull Map<Long, List<int[]>> groupByChunk(@NotNull int[] cells,
                                                        @NotNull java.util.function.IntPredicate isAir,
                                                        @NotNull Location anchor,
                                                        @NotNull Vec3 origin,
                                                        @NotNull Size3 size) {
        Map<Long, List<int[]>> byChunk = new HashMap<>();
        for (int i = 0; i < cells.length; i++) {
            if (isAir.test(cells[i])) {
                continue;
            }
            int[] pos = worldPos(anchor, origin, size, i);
            long key = chunkKey(pos[0] >> 4, pos[2] >> 4);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(
                    new int[] { pos[0], pos[1], pos[2], cells[i] });
        }
        return byChunk;
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private JsonSchematicPaster() { }
}
