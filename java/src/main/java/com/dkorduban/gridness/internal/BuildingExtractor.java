package com.dkorduban.gridness.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts buildings from a tile-local wall raster.
 *
 * Pipeline:
 *  1. BFS from boundary empty cells (4-connectivity) to flood the exterior.
 *  2. Non-wall, non-exterior cells are interior pixels.
 *  3. CCL (4-connectivity) on interior pixels -> one component per building.
 *  4. For each component: bbox, area, rectangularity (area/bbox area),
 *     boundary pixel list (interior cells adjacent to a wall or tile edge),
 *     centroid (mean x/y of boundary pixels — matches the Python convention).
 */
public final class BuildingExtractor {

    private static final int EXTERIOR = -1;
    private static final int UNVISITED = 0;
    // building ids start at 1

    private BuildingExtractor() { }

    /**
     * Extract buildings using {@code exteriorSeeds} to mark which empty cells
     * are globally exterior. Empty cells not reachable from those seeds
     * (through 4-connected empty neighbors) form buildings — including
     * components truncated by the tile-boundary cut, which lets huge buildings
     * be extracted as a piece per tile rather than disappearing.
     *
     * @param exteriorSeeds  same length as walls; true = this empty cell is
     *                       globally exterior (and seeds the exterior flood).
     *                       Pass null to fall back to "seed from any empty cell
     *                       on the rect border" (legacy behavior, useful for
     *                       BuildingExtractor unit tests on isolated rasters).
     */
    public static List<Building> extract(boolean[] walls, boolean[] exteriorSeeds,
                                          int W, int H, int minArea) {
        int[] label = new int[W * H];
        int[] queue = new int[W * H];
        int qHead = 0, qTail = 0;

        if (exteriorSeeds != null) {
            for (int i = 0; i < W * H; i++) {
                if (exteriorSeeds[i] && !walls[i] && label[i] == UNVISITED) {
                    label[i] = EXTERIOR;
                    queue[qTail++] = i;
                }
            }
        } else {
            // Legacy: seed from every empty cell on the rect border.
            for (int x = 0; x < W; x++) {
                if (!walls[x] && label[x] == UNVISITED) { label[x] = EXTERIOR; queue[qTail++] = x; }
                int idxBottom = (H - 1) * W + x;
                if (!walls[idxBottom] && label[idxBottom] == UNVISITED) {
                    label[idxBottom] = EXTERIOR; queue[qTail++] = idxBottom;
                }
            }
            for (int y = 0; y < H; y++) {
                int idxLeft = y * W;
                if (!walls[idxLeft] && label[idxLeft] == UNVISITED) {
                    label[idxLeft] = EXTERIOR; queue[qTail++] = idxLeft;
                }
                int idxRight = y * W + (W - 1);
                if (!walls[idxRight] && label[idxRight] == UNVISITED) {
                    label[idxRight] = EXTERIOR; queue[qTail++] = idxRight;
                }
            }
        }
        while (qHead < qTail) {
            int idx = queue[qHead++];
            int x = idx % W;
            int y = idx / W;
            if (x > 0) { int n = idx - 1;  if (!walls[n] && label[n] == UNVISITED) { label[n] = EXTERIOR; queue[qTail++] = n; } }
            if (x < W - 1) { int n = idx + 1; if (!walls[n] && label[n] == UNVISITED) { label[n] = EXTERIOR; queue[qTail++] = n; } }
            if (y > 0) { int n = idx - W; if (!walls[n] && label[n] == UNVISITED) { label[n] = EXTERIOR; queue[qTail++] = n; } }
            if (y < H - 1) { int n = idx + W; if (!walls[n] && label[n] == UNVISITED) { label[n] = EXTERIOR; queue[qTail++] = n; } }
        }

        // 2 + 3. CCL on interior pixels (4-connectivity).
        List<int[]> componentBBoxes = new ArrayList<>();
        List<Integer> componentAreas = new ArrayList<>();
        int nextLabel = 1;
        for (int y = 0; y < H; y++) {
            int rowBase = y * W;
            for (int x = 0; x < W; x++) {
                int idx = rowBase + x;
                if (walls[idx] || label[idx] != UNVISITED) continue;
                // Start a new component.
                int compLabel = nextLabel++;
                int minX = x, maxX = x, minY = y, maxY = y, area = 0;
                qHead = 0; qTail = 0;
                queue[qTail++] = idx;
                label[idx] = compLabel;
                while (qHead < qTail) {
                    int p = queue[qHead++];
                    int px = p % W;
                    int py = p / W;
                    area++;
                    if (px < minX) minX = px; if (px > maxX) maxX = px;
                    if (py < minY) minY = py; if (py > maxY) maxY = py;
                    if (px > 0) { int n = p - 1; if (!walls[n] && label[n] == UNVISITED) { label[n] = compLabel; queue[qTail++] = n; } }
                    if (px < W - 1) { int n = p + 1; if (!walls[n] && label[n] == UNVISITED) { label[n] = compLabel; queue[qTail++] = n; } }
                    if (py > 0) { int n = p - W; if (!walls[n] && label[n] == UNVISITED) { label[n] = compLabel; queue[qTail++] = n; } }
                    if (py < H - 1) { int n = p + W; if (!walls[n] && label[n] == UNVISITED) { label[n] = compLabel; queue[qTail++] = n; } }
                }
                componentBBoxes.add(new int[] { minX, minY, maxX, maxY });
                componentAreas.add(area);
            }
        }

        int nComponents = nextLabel - 1;
        if (nComponents == 0) return List.of();

        // 4. For each kept component, collect boundary pixels and compute centroid.
        int[] keepMap = new int[nComponents + 1]; // 1-indexed
        int kept = 0;
        for (int c = 1; c <= nComponents; c++) {
            if (componentAreas.get(c - 1) >= minArea) keepMap[c] = ++kept;
        }
        if (kept == 0) return List.of();

        int[][] boundaryX = new int[kept][];
        int[][] boundaryY = new int[kept][];
        int[] boundaryCount = new int[kept];

        // First pass: count boundary pixels per kept component.
        for (int y = 0; y < H; y++) {
            int rowBase = y * W;
            for (int x = 0; x < W; x++) {
                int idx = rowBase + x;
                int lab = label[idx];
                if (lab <= 0) continue;
                int kid = keepMap[lab];
                if (kid == 0) continue;
                if (isBoundary(walls, label, x, y, W, H)) boundaryCount[kid - 1]++;
            }
        }
        for (int k = 0; k < kept; k++) {
            boundaryX[k] = new int[boundaryCount[k]];
            boundaryY[k] = new int[boundaryCount[k]];
            boundaryCount[k] = 0;
        }
        for (int y = 0; y < H; y++) {
            int rowBase = y * W;
            for (int x = 0; x < W; x++) {
                int idx = rowBase + x;
                int lab = label[idx];
                if (lab <= 0) continue;
                int kid = keepMap[lab];
                if (kid == 0) continue;
                if (isBoundary(walls, label, x, y, W, H)) {
                    int k = kid - 1;
                    int c = boundaryCount[k]++;
                    boundaryX[k][c] = x;
                    boundaryY[k][c] = y;
                }
            }
        }

        List<Building> out = new ArrayList<>(kept);
        int outId = 0;
        for (int c = 1; c <= nComponents; c++) {
            int kid = keepMap[c];
            if (kid == 0) continue;
            int k = kid - 1;
            int n = boundaryCount[k];
            int[] bx = boundaryX[k];
            int[] by = boundaryY[k];
            int[] packed = new int[n * 2];
            double sumX = 0, sumY = 0;
            for (int i = 0; i < n; i++) {
                packed[2 * i] = bx[i];
                packed[2 * i + 1] = by[i];
                sumX += bx[i];
                sumY += by[i];
            }
            double cx = n == 0 ? 0 : sumX / n;
            double cy = n == 0 ? 0 : sumY / n;
            int[] bbox = componentBBoxes.get(c - 1);
            int minX = bbox[0], minY = bbox[1], maxX = bbox[2], maxY = bbox[3];
            int area = componentAreas.get(c - 1);
            int bboxArea = (maxX - minX + 1) * (maxY - minY + 1);
            double rect = bboxArea > 0 ? (double) area / bboxArea : 0.0;
            out.add(new Building(outId++, cx, cy, minX, minY, maxX, maxY, area, rect, packed));
        }
        return out;
    }

    private static boolean isBoundary(boolean[] walls, int[] label, int x, int y, int W, int H) {
        // Boundary = interior cell with at least one neighbor that is a wall OR exterior.
        if (x == 0 || y == 0 || x == W - 1 || y == H - 1) return true;
        int idx = y * W + x;
        if (walls[idx - 1] || label[idx - 1] == EXTERIOR) return true;
        if (walls[idx + 1] || label[idx + 1] == EXTERIOR) return true;
        if (walls[idx - W] || label[idx - W] == EXTERIOR) return true;
        if (walls[idx + W] || label[idx + W] == EXTERIOR) return true;
        return false;
    }
}
