package com.gridness.internal;

import java.util.Arrays;

/**
 * Global "is this cell connected to the field-boundary exterior through empty
 * space" bitmap. One flood-fill (4-connectivity) from every empty cell on the
 * outer rim of the field marks the reachable region.
 *
 * <p>Used by per-tile building extraction to distinguish "an empty cell that's
 * actually globally outside" from "an empty cell that's just at the tile's
 * artificial read-boundary cut". The latter case occurs inside huge buildings
 * — without this distinction the per-tile flood treats the megabuilding's
 * interior as exterior and the building disappears.
 */
public final class ExteriorBitmap {

    private final int width;
    private final int height;
    private final boolean[] data;
    /** Reusable BFS queue scratch (sized to field area). */
    private final int[] scratchQueue;

    public ExteriorBitmap(int width, int height) {
        this.width = width;
        this.height = height;
        this.data = new boolean[width * height];
        this.scratchQueue = new int[width * height];
    }

    public int width() { return width; }
    public int height() { return height; }

    public boolean isExterior(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return true;
        return data[y * width + x];
    }

    /** Recompute the bitmap from the current wall state. */
    public void recompute(WallGrid walls) {
        Arrays.fill(data, false);
        int[] q = scratchQueue;
        int head = 0, tail = 0;
        int W = width, H = height;

        // Seed from every empty cell on the four outer rims.
        for (int x = 0; x < W; x++) {
            int top = x;
            if (!walls.get(x, 0) && !data[top]) { data[top] = true; q[tail++] = top; }
            int bot = (H - 1) * W + x;
            if (!walls.get(x, H - 1) && !data[bot]) { data[bot] = true; q[tail++] = bot; }
        }
        for (int y = 1; y < H - 1; y++) {
            int left = y * W;
            if (!walls.get(0, y) && !data[left]) { data[left] = true; q[tail++] = left; }
            int right = y * W + (W - 1);
            if (!walls.get(W - 1, y) && !data[right]) { data[right] = true; q[tail++] = right; }
        }

        while (head < tail) {
            int idx = q[head++];
            int x = idx % W;
            int y = idx / W;
            if (x > 0) { int n = idx - 1;     if (!data[n] && !walls.get(x - 1, y)) { data[n] = true; q[tail++] = n; } }
            if (x < W - 1) { int n = idx + 1; if (!data[n] && !walls.get(x + 1, y)) { data[n] = true; q[tail++] = n; } }
            if (y > 0) { int n = idx - W;     if (!data[n] && !walls.get(x, y - 1)) { data[n] = true; q[tail++] = n; } }
            if (y < H - 1) { int n = idx + W; if (!data[n] && !walls.get(x, y + 1)) { data[n] = true; q[tail++] = n; } }
        }
    }

    /**
     * Copy a rectangular sub-region of the bitmap into a row-major boolean[] of
     * size rectW*rectH. Cells outside the field are treated as exterior=true
     * (because they trivially connect to "outside the field").
     */
    public void copyRect(int x0, int y0, int rectW, int rectH, boolean[] out) {
        if (out.length != rectW * rectH)
            throw new IllegalArgumentException("out length mismatch");
        for (int dy = 0; dy < rectH; dy++) {
            int gy = y0 + dy;
            int rowOff = dy * rectW;
            if (gy < 0 || gy >= height) {
                Arrays.fill(out, rowOff, rowOff + rectW, true);
                continue;
            }
            int base = gy * width;
            for (int dx = 0; dx < rectW; dx++) {
                int gx = x0 + dx;
                if (gx < 0 || gx >= width) { out[rowOff + dx] = true; continue; }
                out[rowOff + dx] = data[base + gx];
            }
        }
    }
}
