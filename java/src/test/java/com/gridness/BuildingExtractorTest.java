package com.gridness;

import com.gridness.internal.Building;
import com.gridness.internal.BuildingExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuildingExtractorTest {

    /** Helper: paint a filled rect (border + interior) as walls into a raster. */
    private static boolean[] singleRect(int W, int H, int x0, int y0, int w, int h) {
        boolean[] walls = new boolean[W * H];
        // Only the border becomes wall; interior is empty (this is a hollow building).
        for (int x = x0; x < x0 + w; x++) {
            walls[y0 * W + x] = true;
            walls[(y0 + h - 1) * W + x] = true;
        }
        for (int y = y0; y < y0 + h; y++) {
            walls[y * W + x0] = true;
            walls[y * W + x0 + w - 1] = true;
        }
        return walls;
    }

    @Test
    void singleHollowRectExtracts() {
        int W = 30, H = 30;
        boolean[] walls = singleRect(W, H, 5, 5, 10, 8);
        List<Building> bs = BuildingExtractor.extract(walls, null, W, H, 4);
        assertEquals(1, bs.size());
        Building b = bs.get(0);
        assertEquals(6, b.minX);
        assertEquals(6, b.minY);
        assertEquals(13, b.maxX);
        assertEquals(11, b.maxY);
        // Interior area = (w-2)*(h-2) = 8*6 = 48
        assertEquals(48, b.area);
        // Rectangularity = area / bbox-area = 48/48 = 1
        assertEquals(1.0, b.rectangularity, 1e-9);
        // Centroid of boundary pixels: with interior 8x6, boundary cells are perimeter of interior
        // Center should be near (9.5, 8.5) — middle of interior bbox.
        assertEquals(9.5, b.centroidX, 0.5);
        assertEquals(8.5, b.centroidY, 0.5);
    }

    @Test
    void multipleNonOverlappingRectsExtracted() {
        int W = 60, H = 30;
        boolean[] walls = singleRect(W, H, 2, 2, 10, 8);
        boolean[] w2 = singleRect(W, H, 30, 5, 8, 8);
        for (int i = 0; i < walls.length; i++) walls[i] = walls[i] || w2[i];
        List<Building> bs = BuildingExtractor.extract(walls, null, W, H, 4);
        assertEquals(2, bs.size());
    }

    @Test
    void emptyRasterReturnsEmpty() {
        boolean[] walls = new boolean[20 * 20];
        List<Building> bs = BuildingExtractor.extract(walls, null, 20, 20, 4);
        assertTrue(bs.isEmpty());
    }

    @Test
    void wallsOnlyReturnsEmpty() {
        boolean[] walls = new boolean[20 * 20];
        java.util.Arrays.fill(walls, true);
        List<Building> bs = BuildingExtractor.extract(walls, null, 20, 20, 4);
        assertTrue(bs.isEmpty());
    }
}
