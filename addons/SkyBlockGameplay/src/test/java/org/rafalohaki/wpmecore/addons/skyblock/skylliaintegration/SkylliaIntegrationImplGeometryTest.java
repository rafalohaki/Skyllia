package org.rafalohaki.wpmecore.addons.skyblock.skylliaintegration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Geometria regionu + mapowanie roli — deterministyczna logika przeniesiona ze SkylliaBridge.
 * Region (0,0), rozmiar 100: halfSize=49.5, środek (256,256), minX/Z=206, maxX/Z=305.
 */
class SkylliaIntegrationImplGeometryTest {

    @Test
    void centerAndBoundaryInside() {
        assertTrue(SkylliaIntegrationImpl.insideRegionBounds(0, 0, 100.0, 256, 256)); // środek
        assertTrue(SkylliaIntegrationImpl.insideRegionBounds(0, 0, 100.0, 207, 207)); // wewnątrz
        assertTrue(SkylliaIntegrationImpl.insideRegionBounds(0, 0, 100.0, 206, 305)); // granica minX/maxX
    }

    @Test
    void outerBoundsRejected() {
        assertFalse(SkylliaIntegrationImpl.insideRegionBounds(0, 0, 100.0, 205, 256)); // poniżej minX
        assertFalse(SkylliaIntegrationImpl.insideRegionBounds(0, 0, 100.0, 306, 256)); // powyżej maxX
        assertFalse(SkylliaIntegrationImpl.insideRegionBounds(1, 0, 100.0, 256, 256)); // inny region
    }

    @Test
    void nonPositiveOrInvalidSizeRejected() {
        assertFalse(SkylliaIntegrationImpl.insideRegionBounds(0, 0, 0.0, 256, 256));
        assertFalse(SkylliaIntegrationImpl.insideRegionBounds(0, 0, -1.0, 256, 256));
        assertFalse(SkylliaIntegrationImpl.insideRegionBounds(0, 0, Double.NaN, 256, 256));
        assertFalse(SkylliaIntegrationImpl.insideRegionBounds(0, 0, Double.POSITIVE_INFINITY, 256, 256));
    }

    @Test
    void roleMappingCoversKnownValues() {
        assertEquals(IslandRole.OWNER, SkylliaIntegrationImpl.mapRole("OWNER"));
        assertEquals(IslandRole.CO_OWNER, SkylliaIntegrationImpl.mapRole("CO_OWNER"));
        assertEquals(IslandRole.MEMBER, SkylliaIntegrationImpl.mapRole("MEMBER"));
        assertEquals(IslandRole.VISITOR, SkylliaIntegrationImpl.mapRole("VISITOR"));
        assertEquals(IslandRole.BAN, SkylliaIntegrationImpl.mapRole("BAN"));
        assertEquals(IslandRole.BAN, SkylliaIntegrationImpl.mapRole("BANNED"));
        assertEquals(IslandRole.UNKNOWN, SkylliaIntegrationImpl.mapRole("GARBAGE"));
    }
}
