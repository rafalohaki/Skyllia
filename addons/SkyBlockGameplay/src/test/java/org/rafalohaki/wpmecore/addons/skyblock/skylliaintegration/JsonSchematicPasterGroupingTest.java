package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.bukkit.Location;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FOLIA-THREADING regresja dekompozycji paste (SKY-1): grupowanie bloków
 * per-chunk wydzielone do {@link JsonSchematicPaster#groupByChunk} jako czysta
 * logika, żeby podział na chunki (klucz = {@code chunkKey(x>>4, z>>4)}),
 * pomijanie powietrza i zachowanie palety były testowalne bez żywego świata.
 *
 * <p>Sama aplikacja bloków (hop przez RegionScheduler na wątku właściciela
 * regionu) wymaga działającej Folii — statyki Bukkit nie są mockowalne bez
 * serwera; pokryta ręcznym repro na stagingu (CanvaSigma).
 */
class JsonSchematicPasterGroupingTest {

    @Test
    @DisplayName("schemat 18×1×1 dzieli się między dwa chunki, powietrze pominięte")
    void groupsBlocksPerChunkAndSkipsAir() {
        // 18 komórek wzdłuż X od anchora (100,64,200): chunk 6 dostaje x=100..111,
        // chunk 7 dostaje x=112..117. Paleta: -1 = powietrze (parzyste pozycje).
        JsonSchematicPaster.Vec3 origin = new JsonSchematicPaster.Vec3(0, 0, 0);
        JsonSchematicPaster.Size3 size = new JsonSchematicPaster.Size3(18, 1, 1);
        Location anchor = new Location(null, 100, 64, 200);
        int[] cells = new int[18];
        for (int i = 0; i < 18; i++) {
            cells[i] = (i % 2 == 0) ? -1 : i + 7;
        }

        Map<Long, List<int[]>> byChunk = JsonSchematicPaster.groupByChunk(
                cells, paletteIndex -> paletteIndex == -1, anchor, origin, size);

        assertEquals(2, byChunk.size(), "bloki mają trafić dokładnie do dwóch chunków");
        List<int[]> firstChunk = byChunk.get(JsonSchematicPaster.chunkKey(6, 12));
        List<int[]> secondChunk = byChunk.get(JsonSchematicPaster.chunkKey(7, 12));
        assertNotNull(firstChunk, "chunk (6,12) ma dostać x=101..111");
        assertNotNull(secondChunk, "chunk (7,12) ma dostać x=113..117");

        // Powietrze na parzystych x → nieparzyste zostają: 101..111 = 6 bloków,
        // 113..117 = 3 bloki.
        assertEquals(6, firstChunk.size());
        assertEquals(3, secondChunk.size());

        // Payload [x,y,z,paletteId]: współrzędne świata i indeks palety nienaruszone.
        int[] firstBlock = firstChunk.get(0);
        assertEquals(4, firstBlock.length);
        assertEquals(101, firstBlock[0]);
        assertEquals(64, firstBlock[1]);
        assertEquals(200, firstBlock[2]);
        assertEquals(8, firstBlock[3]); // i=1 → 1+7
        assertEquals(117, secondChunk.get(2)[0]);
        assertEquals(24, secondChunk.get(2)[3]); // i=17 → 17+7
    }

    @Test
    @DisplayName("ujemne współrzędne lądują w kluczu chunku ze przesunięciem arytmetycznym")
    void negativeCoordinatesGroupUnderArithmeticShiftKey() {
        JsonSchematicPaster.Vec3 origin = new JsonSchematicPaster.Vec3(0, 0, 0);
        JsonSchematicPaster.Size3 size = new JsonSchematicPaster.Size3(1, 2, 1);
        Location anchor = new Location(null, -5, 64, -5);
        int[] cells = new int[] {3, 4};

        Map<Long, List<int[]>> byChunk = JsonSchematicPaster.groupByChunk(
                cells, paletteIndex -> false, anchor, origin, size);

        assertEquals(1, byChunk.size(), "oba bloki w jednym chunku (-1,-1)");
        List<int[]> blocks = byChunk.get(JsonSchematicPaster.chunkKey(-1, -1));
        assertNotNull(blocks, "-5 >> 4 == -1 (przesunięcie arytmetyczne)");
        assertEquals(2, blocks.size());
        assertEquals(-5, blocks.get(0)[0]);
        assertEquals(64, blocks.get(0)[1]);
        // kolejność dekodowania y→z→x: druga komórka (dx=dz=1) idzie w górę o 1
        assertEquals(65, blocks.get(1)[1]);
        assertTrue(blocks.stream().allMatch(b -> b.length == 4));
    }
}
