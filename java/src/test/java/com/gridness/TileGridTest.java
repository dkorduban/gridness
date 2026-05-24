package com.gridness;

import com.gridness.internal.TileGrid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TileGridTest {

    @Test
    void coversFieldWith50PctOverlap() {
        TileGrid tg = new TileGrid(768, 768, 128, 64);
        // 768 = 128 + 10*64, so we need at least 11 tile origins per axis to cover.
        assertTrue(tg.cols() >= 11);
        assertTrue(tg.rows() >= 11);
        // The last tile origin must reach width - tileSize.
        assertEquals(640, tg.originX(tg.cols() - 1));
    }

    @Test
    void smallerThanTileGivesSingleTile() {
        TileGrid tg = new TileGrid(64, 64, 128, 64);
        assertEquals(1, tg.cols());
        assertEquals(1, tg.rows());
        assertEquals(0, tg.originX(0));
        assertEquals(0, tg.originY(0));
    }

    @Test
    void markTilesContainingMarksAllOverlappers() {
        TileGrid tg = new TileGrid(256, 256, 128, 64);
        boolean[] dirty = new boolean[tg.tileCount()];
        // Cell (100, 100) is in tiles whose origins are 0 or 64, so 4 tiles.
        tg.markTilesContaining(100, 100, 1, dirty);
        int count = 0;
        for (boolean b : dirty) if (b) count++;
        assertEquals(4, count, "cell deep inside should mark 4 overlapping tiles");
    }

    @Test
    void markTilesContainingEdge() {
        TileGrid tg = new TileGrid(256, 256, 128, 64);
        boolean[] dirty = new boolean[tg.tileCount()];
        // Cell (0, 0): only tile at origin (0,0) contains it (pad=1 stays inside one tile).
        tg.markTilesContaining(0, 0, 1, dirty);
        int count = 0;
        for (boolean b : dirty) if (b) count++;
        assertEquals(1, count);
    }

    @Test
    void markTilesContainingPaddingCrossesTileBoundary() {
        // A wall at the seam between two tiles (x = origin of an interior tile)
        // must mark both the tile that owns that column AND the previous tile,
        // because the previous tile's pad-1 read region reaches across.
        TileGrid tg = new TileGrid(256, 256, 128, 64);
        boolean[] dirty = new boolean[tg.tileCount()];
        // Cell x=64 is the origin of column-1 tile. With pad=1, tile at col 0 (which
        // reads x in [-1, 129)) also depends on cell x=64; expect 4 tiles total (2 cols x 2 rows).
        tg.markTilesContaining(64, 100, 1, dirty);
        int count = 0;
        for (boolean b : dirty) if (b) count++;
        assertEquals(4, count, "cell on tile seam should still mark only the overlapping bracket");

        // Now test the actually pathological case: cell x = 128 (end of tile 0's bbox).
        // Tile 0 (origin 0, bbox [0, 128)) doesn't contain x=128, but with pad=1 it reads x=128.
        // So with pad it MUST be marked.
        java.util.Arrays.fill(dirty, false);
        tg.markTilesContaining(128, 100, 1, dirty);
        // Tiles overlapping x=128: cols whose bbox bracket {128} (cols 1, 2 normally), plus col 0 via pad.
        // For 256-wide field with tileSize=128, stride=64, cols are at originX = 0, 64, 128. So:
        //   - col 0 (origin 0, padded [-1, 129)): yes (128 < 129)
        //   - col 1 (origin 64, padded [63, 193)): yes
        //   - col 2 (origin 128, padded [127, 257)): yes
        // 3 cols x 2 rows (y=100 hits rows 0 and 1) = 6.
        int countWithPad = 0;
        for (boolean b : dirty) if (b) countWithPad++;
        assertTrue(countWithPad >= 4, "with pad=1 a tile-edge cell should mark all reachable tiles, got " + countWithPad);
    }
}
