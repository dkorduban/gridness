package com.dkorduban.gridness.internal;

import java.util.Arrays;

/**
 * Global "is this cell connected to the field-boundary exterior through empty
 * space" bitmap. Maintained either by full recompute (BFS from every empty
 * field-boundary cell) or by incremental {@link #updateAfterEdit} after a
 * single-cell wall flip.
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
    /** Per-cell generation marker for verify-BFSes; avoids reset cost. */
    private final int[] visitMark;
    private int currentGen;
    /** Cells whose exterior status flipped during the most recent update. */
    private int[] changed;
    private int changedCount;

    public ExteriorBitmap(int width, int height) {
        this.width = width;
        this.height = height;
        this.data = new boolean[width * height];
        this.scratchQueue = new int[width * height];
        this.visitMark = new int[width * height];
        this.changed = new int[Math.min(width * height, 256)];
        this.changedCount = 0;
    }

    public int width() { return width; }
    public int height() { return height; }

    public boolean isExterior(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return true;
        return data[y * width + x];
    }

    public int changedCount() { return changedCount; }
    public int changedAt(int i) { return changed[i]; }

    /** Full recompute from scratch. Use after bulk wall replacement. */
    public void recompute(WallGrid walls) {
        Arrays.fill(data, false);
        changedCount = 0;
        int[] q = scratchQueue;
        int head = 0, tail = 0;
        int W = width, H = height;

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
     * Apply a single wall flip and incrementally repair the bitmap.
     * {@code walls} MUST already reflect the post-edit state when called.
     * Returns the number of cells whose exterior status flipped; their flat
     * (y*W + x) indices are exposed via {@link #changedAt(int)}.
     *
     * <p>Common case (edit deep inside a building or in open exterior): O(1)
     * to a handful of cells. Worst case (edit on an isthmus that disconnects
     * a large empty region): O(component size).
     */
    public int updateAfterEdit(WallGrid walls, int x, int y, boolean wasWall, boolean nowWall) {
        changedCount = 0;
        if (wasWall == nowWall) return 0;
        if (x < 0 || x >= width || y < 0 || y >= height) return 0;
        if (wasWall) handleWallRemoved(walls, x, y);
        else handleWallAdded(walls, x, y);
        return changedCount;
    }

    private void recordChanged(int idx) {
        if (changedCount == changed.length) {
            changed = Arrays.copyOf(changed, Math.min(changed.length * 2, width * height));
        }
        changed[changedCount++] = idx;
    }

    /** Wall just got removed at (x,y): cell is now empty. */
    private void handleWallRemoved(WallGrid walls, int x, int y) {
        int W = width, H = height;
        int idx = y * W + x;
        // Cell was a wall — it can't have been in data[]=true.
        boolean isExt = (x == 0 || x == W - 1 || y == 0 || y == H - 1);
        if (!isExt) {
            if ((x > 0 && data[idx - 1])
                    || (x < W - 1 && data[idx + 1])
                    || (y > 0 && data[idx - W])
                    || (y < H - 1 && data[idx + W])) {
                isExt = true;
            }
        }
        if (!isExt) return;

        int[] q = scratchQueue;
        int head = 0, tail = 0;
        data[idx] = true;
        recordChanged(idx);
        q[tail++] = idx;
        while (head < tail) {
            int cur = q[head++];
            int cx = cur % W;
            int cy = cur / W;
            if (cx > 0) {
                int n = cur - 1;
                if (!data[n] && !walls.get(cx - 1, cy)) { data[n] = true; recordChanged(n); q[tail++] = n; }
            }
            if (cx < W - 1) {
                int n = cur + 1;
                if (!data[n] && !walls.get(cx + 1, cy)) { data[n] = true; recordChanged(n); q[tail++] = n; }
            }
            if (cy > 0) {
                int n = cur - W;
                if (!data[n] && !walls.get(cx, cy - 1)) { data[n] = true; recordChanged(n); q[tail++] = n; }
            }
            if (cy < H - 1) {
                int n = cur + W;
                if (!data[n] && !walls.get(cx, cy + 1)) { data[n] = true; recordChanged(n); q[tail++] = n; }
            }
        }
    }

    /** Wall just got added at (x,y): cell is now solid. */
    private void handleWallAdded(WallGrid walls, int x, int y) {
        int W = width, H = height;
        int idx = y * W + x;
        if (!data[idx]) return;
        data[idx] = false;
        recordChanged(idx);
        // Fast path: if no walls in the 8-neighborhood of (x,y), the cell sits
        // in open exterior. Removing it cannot disconnect anything (4-conn
        // alternative paths exist via the surrounding exterior cells). Skip
        // the expensive verifyAnchor BFSes — critical for build scenarios
        // where many walls are added in open space.
        if (noWallsInNeighborhood(walls, x, y)) return;
        if (x > 0 && data[idx - 1]) verifyAnchor(walls, idx - 1);
        if (x < W - 1 && data[idx + 1]) verifyAnchor(walls, idx + 1);
        if (y > 0 && data[idx - W]) verifyAnchor(walls, idx - W);
        if (y < H - 1 && data[idx + W]) verifyAnchor(walls, idx + W);
    }

    private boolean noWallsInNeighborhood(WallGrid walls, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= height) continue;
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int xx = x + dx;
                if (xx < 0 || xx >= width) continue;
                if (walls.get(xx, yy)) return false;
            }
        }
        return true;
    }

    /**
     * BFS through still-exterior cells starting at startIdx. If we reach any
     * field-boundary cell, the component is still anchored — return without
     * touching data[]. Otherwise unmark the entire visited component.
     */
    private void verifyAnchor(WallGrid walls, int startIdx) {
        int W = width, H = height;
        int gen = ++currentGen;
        if (gen == Integer.MAX_VALUE) {
            Arrays.fill(visitMark, 0);
            currentGen = 1;
            gen = 1;
        }
        int[] q = scratchQueue;
        int head = 0, tail = 0;
        boolean anchored = false;

        visitMark[startIdx] = gen;
        q[tail++] = startIdx;

        while (head < tail) {
            int cur = q[head++];
            int cx = cur % W;
            int cy = cur / W;
            if (cx == 0 || cx == W - 1 || cy == 0 || cy == H - 1) {
                anchored = true;
                break;
            }
            if (cx > 0) {
                int n = cur - 1;
                if (data[n] && visitMark[n] != gen) { visitMark[n] = gen; q[tail++] = n; }
            }
            if (cx < W - 1) {
                int n = cur + 1;
                if (data[n] && visitMark[n] != gen) { visitMark[n] = gen; q[tail++] = n; }
            }
            if (cy > 0) {
                int n = cur - W;
                if (data[n] && visitMark[n] != gen) { visitMark[n] = gen; q[tail++] = n; }
            }
            if (cy < H - 1) {
                int n = cur + W;
                if (data[n] && visitMark[n] != gen) { visitMark[n] = gen; q[tail++] = n; }
            }
        }
        if (!anchored) {
            for (int i = 0; i < tail; i++) {
                int u = q[i];
                data[u] = false;
                recordChanged(u);
            }
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
