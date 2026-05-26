package com.dkorduban.gridness;

import com.dkorduban.gridness.internal.WallGrid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WallGridTest {

    @Test
    void setAndGet() {
        WallGrid g = new WallGrid(100, 80);
        assertFalse(g.get(50, 40));
        assertFalse(g.set(50, 40, true));
        assertTrue(g.get(50, 40));
        assertTrue(g.set(50, 40, false));
        assertFalse(g.get(50, 40));
    }

    @Test
    void wordBoundaries() {
        // exercise positions near the 64-bit word boundary
        WallGrid g = new WallGrid(200, 10);
        for (int x : new int[]{0, 1, 62, 63, 64, 65, 127, 128, 191, 199}) {
            assertFalse(g.set(x, 5, true), "expected previously empty at x=" + x);
            assertTrue(g.get(x, 5), "expected wall at x=" + x);
        }
    }

    @Test
    void outOfBoundsGetReturnsFalse() {
        WallGrid g = new WallGrid(10, 10);
        assertFalse(g.get(-1, 5));
        assertFalse(g.get(5, 100));
    }

    @Test
    void outOfBoundsSetThrows() {
        WallGrid g = new WallGrid(10, 10);
        assertThrows(IndexOutOfBoundsException.class, () -> g.set(-1, 5, true));
        assertThrows(IndexOutOfBoundsException.class, () -> g.set(5, 100, true));
    }

    @Test
    void copyRectClipsOutOfBounds() {
        WallGrid g = new WallGrid(10, 10);
        g.set(0, 0, true);
        g.set(9, 9, true);
        boolean[] buf = new boolean[12 * 12];
        g.copyRect(-1, -1, 12, 12, buf);
        // (0,0) in field is at (1,1) in buf
        assertTrue(buf[1 * 12 + 1]);
        assertTrue(buf[10 * 12 + 10]);
        // corner of buf is out-of-field -> false
        assertFalse(buf[0]);
        assertFalse(buf[11 * 12 + 11]);
    }
}
