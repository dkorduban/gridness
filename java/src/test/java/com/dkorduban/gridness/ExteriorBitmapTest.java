package com.dkorduban.gridness;

import com.dkorduban.gridness.internal.ExteriorBitmap;
import com.dkorduban.gridness.internal.WallGrid;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ExteriorBitmapTest {

    private static WallGrid makeWalls(int W, int H, boolean[][] field) {
        WallGrid g = new WallGrid(W, H);
        g.clearTo(field);
        return g;
    }

    private static boolean[] dataOf(ExteriorBitmap e) {
        int W = e.width(), H = e.height();
        boolean[] out = new boolean[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                out[y * W + x] = e.isExterior(x, y);
        return out;
    }

    private static boolean[][] hollowRect(int W, int H, int x0, int y0, int w, int h) {
        boolean[][] f = new boolean[H][W];
        for (int x = x0; x < x0 + w; x++) { f[y0][x] = true; f[y0 + h - 1][x] = true; }
        for (int y = y0; y < y0 + h; y++) { f[y][x0] = true; f[y][x0 + w - 1] = true; }
        return f;
    }

    @Test
    void wallRemovalOpensEnclosure() {
        int W = 50, H = 50;
        WallGrid w = makeWalls(W, H, hollowRect(W, H, 15, 15, 20, 20));
        ExteriorBitmap e = new ExteriorBitmap(W, H);
        e.recompute(w);
        assertFalse(e.isExterior(20, 20), "interior cell should not be exterior");
        // Punch hole through the left wall.
        w.set(15, 20, false);
        int n = e.updateAfterEdit(w, 15, 20, true, false);
        assertTrue(n > 0);
        assertTrue(e.isExterior(20, 20), "interior should now be flooded");
    }

    @Test
    void wallAdditionDisconnectsInterior() {
        int W = 50, H = 50;
        boolean[][] f = hollowRect(W, H, 15, 15, 20, 20);
        f[20][15] = false; // hole in left wall
        WallGrid w = makeWalls(W, H, f);
        ExteriorBitmap e = new ExteriorBitmap(W, H);
        e.recompute(w);
        assertTrue(e.isExterior(20, 20), "interior should be reachable through the hole");
        // Plug the hole.
        w.set(15, 20, true);
        e.updateAfterEdit(w, 15, 20, false, true);
        assertFalse(e.isExterior(20, 20), "interior should be disconnected");
    }

    @Test
    void incrementalRemovalMatchesFullRecompute() {
        int W = 80, H = 60;
        WallGrid w = makeWalls(W, H, hollowRect(W, H, 20, 15, 30, 25));
        ExteriorBitmap incr = new ExteriorBitmap(W, H);
        incr.recompute(w);
        w.set(20, 20, false);
        incr.updateAfterEdit(w, 20, 20, true, false);

        ExteriorBitmap full = new ExteriorBitmap(W, H);
        full.recompute(w);
        assertArrayEquals(dataOf(full), dataOf(incr));
    }

    @Test
    void incrementalAdditionMatchesFullRecompute() {
        int W = 80, H = 60;
        boolean[][] f = hollowRect(W, H, 20, 15, 30, 25);
        f[20][20] = false; // hole
        WallGrid w = makeWalls(W, H, f);
        ExteriorBitmap incr = new ExteriorBitmap(W, H);
        incr.recompute(w);
        w.set(20, 20, true);
        incr.updateAfterEdit(w, 20, 20, false, true);

        ExteriorBitmap full = new ExteriorBitmap(W, H);
        full.recompute(w);
        assertArrayEquals(dataOf(full), dataOf(incr));
    }

    @Test
    void noOpEditChangesNothing() {
        int W = 40, H = 40;
        WallGrid w = makeWalls(W, H, hollowRect(W, H, 10, 10, 15, 15));
        ExteriorBitmap e = new ExteriorBitmap(W, H);
        e.recompute(w);
        boolean[] before = dataOf(e);

        // Add wall where one already exists.
        int n1 = e.updateAfterEdit(w, 10, 12, true, true);
        assertEquals(0, n1);
        // Remove wall where there isn't one.
        int n2 = e.updateAfterEdit(w, 20, 20, false, false);
        assertEquals(0, n2);

        assertArrayEquals(before, dataOf(e));
    }

    @Test
    void removeInteriorWallNoFloodWhenStillEnclosed() {
        // Hollow 20x20 with a wall cell at (16, 16) inside (just a stray pixel).
        int W = 50, H = 50;
        boolean[][] f = hollowRect(W, H, 10, 10, 20, 20);
        f[16][16] = true;
        WallGrid w = makeWalls(W, H, f);
        ExteriorBitmap e = new ExteriorBitmap(W, H);
        e.recompute(w);
        assertFalse(e.isExterior(16, 16));
        assertFalse(e.isExterior(15, 16), "interior cell adjacent to stray is not exterior");
        // Remove the stray. Interior is still enclosed — no cell should flip.
        w.set(16, 16, false);
        int n = e.updateAfterEdit(w, 16, 16, true, false);
        assertEquals(0, n, "no cells should flip — interior remains enclosed");
        assertFalse(e.isExterior(16, 16));
    }

    @Test
    void addWallInOpenExteriorOnlyFlipsOneCell() {
        // Big open field, just one wall added in the middle.
        int W = 50, H = 50;
        WallGrid w = makeWalls(W, H, new boolean[H][W]);
        ExteriorBitmap e = new ExteriorBitmap(W, H);
        e.recompute(w);
        assertTrue(e.isExterior(25, 25));
        w.set(25, 25, true);
        int n = e.updateAfterEdit(w, 25, 25, false, true);
        assertEquals(1, n, "only the edited cell should flip; 4 neighbors trivially anchored");
    }

    @Test
    void manyRandomEditsMatchFullRecompute() {
        int W = 100, H = 80;
        boolean[][] field = new boolean[H][W];
        Random r = new Random(13);
        // Seed with some walls.
        for (int i = 0; i < 800; i++) field[r.nextInt(H)][r.nextInt(W)] = true;
        WallGrid w = makeWalls(W, H, field);
        ExteriorBitmap incr = new ExteriorBitmap(W, H);
        incr.recompute(w);

        for (int i = 0; i < 500; i++) {
            int x = r.nextInt(W), y = r.nextInt(H);
            boolean prev = w.get(x, y);
            boolean next = !prev;
            w.set(x, y, next);
            incr.updateAfterEdit(w, x, y, prev, next);
            if (i % 47 == 0) {
                ExteriorBitmap full = new ExteriorBitmap(W, H);
                full.recompute(w);
                assertArrayEquals(dataOf(full), dataOf(incr),
                        "mismatch after edit " + i + " at (" + x + "," + y + ")");
            }
        }

        ExteriorBitmap full = new ExteriorBitmap(W, H);
        full.recompute(w);
        assertArrayEquals(dataOf(full), dataOf(incr));
    }

    @Test
    void disconnectionUnmarksFormerlyExteriorCells() {
        // Open strip 50x4 wall-free with H=4. Add a vertical wall mid-way to split.
        // Actually use a setup where disconnection clearly happens:
        // Pocket on right side connected only through a 1-cell wide bridge.
        int W = 40, H = 20;
        boolean[][] f = new boolean[H][W];
        // Vertical walls at x=20 except a single gap at y=10.
        for (int y = 0; y < H; y++) f[y][20] = true;
        f[10][20] = false;
        WallGrid w = makeWalls(W, H, f);
        ExteriorBitmap e = new ExteriorBitmap(W, H);
        e.recompute(w);
        assertTrue(e.isExterior(0, 0));
        assertTrue(e.isExterior(W - 1, 0), "right side reachable through gap");
        // Plug the gap.
        w.set(20, 10, true);
        int n = e.updateAfterEdit(w, 20, 10, false, true);
        // Both sides are anchored at top/bottom of the field, so we still expect
        // 0 unmarks (since each side still touches the field boundary).
        assertEquals(1, n, "left and right sides still touch field boundary; only the gap cell flips");
        assertTrue(e.isExterior(0, 0));
        assertTrue(e.isExterior(W - 1, 0));
        assertFalse(e.isExterior(20, 10));
    }

    @Test
    void disconnectionWithoutAnchorUnmarksWholeComponent() {
        // Make a chamber attached to outside only by a 1-cell wide door.
        // Chamber: 10x10 box at (10,5) with door at (10,10).
        int W = 50, H = 30;
        boolean[][] f = hollowRect(W, H, 10, 5, 12, 12);
        f[10][10] = false; // door
        WallGrid w = makeWalls(W, H, f);
        ExteriorBitmap e = new ExteriorBitmap(W, H);
        e.recompute(w);
        assertTrue(e.isExterior(15, 10), "chamber interior reachable through door");
        // Plug the door.
        w.set(10, 10, true);
        int n = e.updateAfterEdit(w, 10, 10, false, true);
        assertTrue(n > 1, "should unmark the chamber's interior cells; got " + n);
        assertFalse(e.isExterior(15, 10), "chamber interior should now be unmarked");
        // External cells outside the chamber should still be exterior.
        assertTrue(e.isExterior(0, 0));
        assertTrue(e.isExterior(40, 25));
    }
}
