package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * SKY-1: dekoder formatu JSON Skylli (semantyka odtworzona z dekompilacji
 * InternalSchematicHook: RLE [id, run], kolejność y→z→x, world = anchor +
 * (cell - origin)). Paste całych wysp był void, bo SkylliaAPI.createIsland
 * nie wkleja schematów — te testy pilnują poprawności naszego dekodera.
 */
class JsonSchematicPasterTest {

    @Test
    void rleDecodesToExpectedLengthAndValues() {
        List<int[]> rle = List.of(new int[] {0, 3}, new int[] {1, 2}, new int[] {0, 1});
        int[] out = JsonSchematicPaster.decodeRle(rle, 6);
        assertArrayEquals(new int[] {0, 0, 0, 1, 1, 0}, out);
    }

    @Test
    void rleOverlongRunClampsToExpectedCount() {
        int[] out = JsonSchematicPaster.decodeRle(List.of(new int[] {5, 100}), 4);
        assertArrayEquals(new int[] {5, 5, 5, 5}, out);
    }

    @Test
    void worldPosFollowsYZXOrderAndOriginAnchor() {
        // schemat 2x2x2 (dx=dy=dz=2), origin (0,0,0), anchor (100, 64, 200)
        JsonSchematicPaster.Vec3 origin = new JsonSchematicPaster.Vec3(0, 0, 0);
        JsonSchematicPaster.Size3 size = new JsonSchematicPaster.Size3(2, 2, 2);
        org.bukkit.Location anchor = new org.bukkit.Location(null, 100.7, 64.2, 200.9);

        // index 0: y=0,z=0,x=0 → (100,64,200); index 1: y=0,z=0,x=1 → (101,64,200)
        assertArrayEquals(new int[] {100, 64, 200}, JsonSchematicPaster.worldPos(anchor, origin, size, 0));
        assertArrayEquals(new int[] {101, 64, 200}, JsonSchematicPaster.worldPos(anchor, origin, size, 1));
        // index 2: y=0,z=1,x=0; index 4: y=1,z=0,x=0 (warstwa y dopiero po dx*dz)
        assertArrayEquals(new int[] {100, 64, 201}, JsonSchematicPaster.worldPos(anchor, origin, size, 2));
        assertArrayEquals(new int[] {100, 65, 200}, JsonSchematicPaster.worldPos(anchor, origin, size, 4));
        // ostatni index 7: y=1,z=1,x=1
        assertArrayEquals(new int[] {101, 65, 201}, JsonSchematicPaster.worldPos(anchor, origin, size, 7));
    }

    @Test
    void originShiftsAnchorSoOriginCellLandsOnAnchor() {
        // oneblock Skylli po SKY-1: size 1x3x1, origin (0,1,0) = komórka GRASS
        // (bloki [bedrock, grass, air] w kolejności y). Kontrakt detektora
        // IslandFluidInitializer: grass dokładnie na Y centrum wyspy (64),
        // bedrock na 63 — origin na grassie gwarantuje to przy anchor=center.
        JsonSchematicPaster.Vec3 origin = new JsonSchematicPaster.Vec3(0, 1, 0);
        JsonSchematicPaster.Size3 size = new JsonSchematicPaster.Size3(1, 3, 1);
        org.bukkit.Location anchor = new org.bukkit.Location(null, 256.5, 64.0, -4864.5);
        assertArrayEquals(new int[] {256, 63, -4865}, JsonSchematicPaster.worldPos(anchor, origin, size, 0));
        assertArrayEquals(new int[] {256, 64, -4865}, JsonSchematicPaster.worldPos(anchor, origin, size, 1));
        assertArrayEquals(new int[] {256, 65, -4865}, JsonSchematicPaster.worldPos(anchor, origin, size, 2));
    }

    @Test
    void chunkKeyRoundTripsNegativeCoordinates() {
        long key = JsonSchematicPaster.chunkKey(-305, -625);
        assertEquals(-305, (int) (key >> 32));
        assertEquals(-625, (int) (long) key);
    }
}
