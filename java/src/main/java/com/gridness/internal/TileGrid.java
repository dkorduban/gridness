package com.gridness.internal;

public final class TileGrid {

    private final int fieldWidth;
    private final int fieldHeight;
    private final int tileSize;
    private final int tileStride;
    private final int cols;
    private final int rows;
    private final int[] originX;
    private final int[] originY;

    public TileGrid(int fieldWidth, int fieldHeight, int tileSize, int tileStride) {
        this.fieldWidth = fieldWidth;
        this.fieldHeight = fieldHeight;
        this.tileSize = tileSize;
        this.tileStride = tileStride;

        // Cover the field: last tile origin may be capped so the tile fits.
        // cols/rows count tile origins along x/y axes.
        int colCount = computeAxisCount(fieldWidth, tileSize, tileStride);
        int rowCount = computeAxisCount(fieldHeight, tileSize, tileStride);
        this.cols = colCount;
        this.rows = rowCount;
        this.originX = new int[colCount];
        this.originY = new int[rowCount];
        for (int i = 0; i < colCount; i++) {
            int ox = i * tileStride;
            if (ox + tileSize > fieldWidth) ox = Math.max(0, fieldWidth - tileSize);
            originX[i] = ox;
        }
        for (int j = 0; j < rowCount; j++) {
            int oy = j * tileStride;
            if (oy + tileSize > fieldHeight) oy = Math.max(0, fieldHeight - tileSize);
            originY[j] = oy;
        }
    }

    private static int computeAxisCount(int field, int size, int stride) {
        if (field <= size) return 1;
        // Tile origins at 0, stride, 2*stride, ... The last tile is clipped so it ends at field.
        int n = (int) Math.ceil((double) (field - size) / stride) + 1;
        return Math.max(1, n);
    }

    public int cols() { return cols; }
    public int rows() { return rows; }
    public int tileCount() { return cols * rows; }
    public int tileSize() { return tileSize; }
    public int tileStride() { return tileStride; }

    public int originX(int col) { return originX[col]; }
    public int originY(int row) { return originY[row]; }

    public double centerX(int col) { return originX[col] + tileSize * 0.5; }
    public double centerY(int row) { return originY[row] + tileSize * 0.5; }

    public int tileIndex(int col, int row) { return row * cols + col; }

    /**
     * Mark in `dirty` every tile whose PADDED bbox contains the cell (x, y).
     * A tile reads cells in `[originX - pad, originX + tileSize + pad)` during
     * recompute, so any edit within that extended range can change its result.
     */
    public void markTilesContaining(int x, int y, int pad, boolean[] dirty) {
        if (x < 0 || x >= fieldWidth || y < 0 || y >= fieldHeight) return;
        // Tile c's padded bbox contains x iff originX[c] - pad <= x < originX[c] + tileSize + pad,
        // i.e. x - tileSize - pad < originX[c] <= x + pad.
        // colMinForX(x) returns smallest c with originX[c] + tileSize > x; we want
        // smallest c with originX[c] + tileSize + pad > x, i.e. colMinForX(x - pad).
        // colMaxForX(x) returns largest c with originX[c] <= x; we want largest c
        // with originX[c] <= x + pad, i.e. colMaxForX(x + pad).
        int colMin = Math.max(0, colMinForX(x - pad));
        int colMax = Math.min(cols - 1, colMaxForX(x + pad));
        int rowMin = Math.max(0, rowMinForY(y - pad));
        int rowMax = Math.min(rows - 1, rowMaxForY(y + pad));
        for (int r = rowMin; r <= rowMax; r++) {
            int base = r * cols;
            for (int c = colMin; c <= colMax; c++) {
                int t = base + c;
                dirty[t] = true;
            }
        }
    }

    public int colMinForXPad(int x, int pad) { return Math.max(0, colMinForX(x - pad)); }
    public int colMaxForXPad(int x, int pad) { return Math.min(cols - 1, colMaxForX(x + pad)); }
    public int rowMinForYPad(int y, int pad) { return Math.max(0, rowMinForY(y - pad)); }
    public int rowMaxForYPad(int y, int pad) { return Math.min(rows - 1, rowMaxForY(y + pad)); }

    private int colMinForX(int x) {
        // smallest col such that originX[col] + tileSize > x  =>  originX[col] > x - tileSize.
        // Use stride-based estimate, then refine.
        int est = (x - tileSize + 1) / tileStride;
        if (est < 0) est = 0;
        while (est < cols - 1 && originX[est] + tileSize <= x) est++;
        return est;
    }

    private int colMaxForX(int x) {
        // largest col such that originX[col] <= x.
        int est = x / tileStride;
        if (est >= cols) est = cols - 1;
        while (est > 0 && originX[est] > x) est--;
        while (est < cols - 1 && originX[est + 1] <= x) est++;
        return est;
    }

    private int rowMinForY(int y) {
        int est = (y - tileSize + 1) / tileStride;
        if (est < 0) est = 0;
        while (est < rows - 1 && originY[est] + tileSize <= y) est++;
        return est;
    }

    private int rowMaxForY(int y) {
        int est = y / tileStride;
        if (est >= rows) est = rows - 1;
        while (est > 0 && originY[est] > y) est--;
        while (est < rows - 1 && originY[est + 1] <= y) est++;
        return est;
    }
}
