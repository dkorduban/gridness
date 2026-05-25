package com.gridness.viz;

import com.gridness.Gridness;
import com.gridness.GridnessParams;
import com.gridness.fixture.LayoutFixture;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless tool: load N fixtures fully, compute Java's gridness heatmap for
 * each, and dump it as a small text file so the Python comparison script can
 * tile it next to Python's own heatmap. Format:
 *
 * <pre>
 * # name=<name> field=HxW stride=S
 * &lt;ny&gt; &lt;nx&gt;
 * row0_v0 row0_v1 ...
 * row1_v0 row1_v1 ...
 * ...
 * </pre>
 *
 * <p>Run with: {@code gradle :dumpHeatmaps --args="<out-dir> <fixture-1> <fixture-2> ..."}
 */
public final class HeatmapDumper {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: HeatmapDumper <out-dir> <fixture-1> [<fixture-2> ...]");
            System.exit(2);
        }
        Path outDir = Paths.get(args[0]);
        Files.createDirectories(outDir);

        List<String> names = new ArrayList<>();
        for (int i = 1; i < args.length; i++) names.add(args[i]);

        Path fxDir = LayoutFixture.defaultDir();
        GridnessParams defaults = GridnessParams.defaults();

        for (String name : names) {
            LayoutFixture fx = LayoutFixture.load(fxDir, name);
            GridnessParams params = paramsFor(name, defaults);
            long t0 = System.nanoTime();
            Gridness g = new Gridness(fx.width, fx.height, params);
            g.loadFromField(fx.raster);
            double[][] heat = g.readRect(0, 0, fx.width, fx.height);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            Path out = outDir.resolve(name + ".heatmap.txt");
            writeHeatmap(out, name, fx.width, fx.height, params.sampleStride, heat);
            double mean = mean(heat);
            System.out.printf("  %-30s  field=%dx%d  stride=%d  mean=%.3f  %dms  -> %s%n",
                    name, fx.width, fx.height, params.sampleStride, mean, ms, out);
        }
    }

    private static GridnessParams paramsFor(String name, GridnessParams defaults) {
        // Mirror the Python script's per-fixture overrides so the two
        // implementations score the same fixture with the same params.
        if (name.equals("longhouses_22x60")) {
            return GridnessParams.builder().radius(60).build();
        }
        return defaults;
    }

    private static double mean(double[][] heat) {
        double sum = 0; int n = 0;
        for (double[] row : heat) for (double v : row) { sum += v; n++; }
        return n == 0 ? 0 : sum / n;
    }

    private static void writeHeatmap(Path out, String name, int W, int H, int stride,
                                     double[][] heat) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write(String.format("# name=%s field=%dx%d stride=%d%n", name, W, H, stride));
            int ny = heat.length;
            int nx = ny > 0 ? heat[0].length : 0;
            w.write(ny + " " + nx);
            w.newLine();
            StringBuilder sb = new StringBuilder(nx * 7);
            for (int j = 0; j < ny; j++) {
                sb.setLength(0);
                double[] row = heat[j];
                for (int i = 0; i < nx; i++) {
                    if (i > 0) sb.append(' ');
                    sb.append(String.format("%.5f", row[i]));
                }
                w.write(sb.toString());
                w.newLine();
            }
        }
    }
}
