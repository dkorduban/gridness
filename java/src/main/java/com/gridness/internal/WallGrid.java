package com.gridness.internal;

import java.util.Arrays;

public final class WallGrid {

    private final int width;
    private final int height;
    private final long[] bits;
    private final int wordsPerRow;

    public WallGrid(int width, int height) {
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("width and height must be positive");
        this.width = width;
        this.height = height;
        this.wordsPerRow = (width + 63) >>> 6;
        this.bits = new long[wordsPerRow * height];
    }

    public int width() { return width; }
    public int height() { return height; }

    public boolean get(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) return false;
        int idx = y * wordsPerRow + (x >>> 6);
        return (bits[idx] & (1L << (x & 63))) != 0L;
    }

    /** Returns previous value. */
    public boolean set(int x, int y, boolean value) {
        if (x < 0 || x >= width || y < 0 || y >= height)
            throw new IndexOutOfBoundsException("(" + x + "," + y + ") out of " + width + "x" + height);
        int idx = y * wordsPerRow + (x >>> 6);
        long mask = 1L << (x & 63);
        long word = bits[idx];
        boolean prev = (word & mask) != 0L;
        if (value) bits[idx] = word | mask;
        else bits[idx] = word & ~mask;
        return prev;
    }

    public void clear() { Arrays.fill(bits, 0L); }

    public void clearTo(boolean[][] field) {
        if (field.length != height)
            throw new IllegalArgumentException("expected " + height + " rows, got " + field.length);
        clear();
        for (int y = 0; y < height; y++) {
            boolean[] row = field[y];
            if (row.length != width)
                throw new IllegalArgumentException("row " + y + " expected " + width + " cols, got " + row.length);
            int base = y * wordsPerRow;
            for (int x = 0; x < width; x++) {
                if (row[x]) bits[base + (x >>> 6)] |= 1L << (x & 63);
            }
        }
    }

    /**
     * Copy a rectangular sub-region of walls into a row-major boolean[] of size rectH*rectW.
     * Out-of-bounds cells are treated as false (no wall).
     */
    public void copyRect(int x0, int y0, int rectW, int rectH, boolean[] out) {
        if (out.length != rectW * rectH)
            throw new IllegalArgumentException("out length mismatch");
        for (int dy = 0; dy < rectH; dy++) {
            int gy = y0 + dy;
            int rowOff = dy * rectW;
            if (gy < 0 || gy >= height) {
                Arrays.fill(out, rowOff, rowOff + rectW, false);
                continue;
            }
            int wordBase = gy * wordsPerRow;
            for (int dx = 0; dx < rectW; dx++) {
                int gx = x0 + dx;
                if (gx < 0 || gx >= width) { out[rowOff + dx] = false; continue; }
                long word = bits[wordBase + (gx >>> 6)];
                out[rowOff + dx] = (word & (1L << (gx & 63))) != 0L;
            }
        }
    }
}
