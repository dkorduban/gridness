package com.dkorduban.gridness.viz;

import com.dkorduban.gridness.Gridness;
import com.dkorduban.gridness.GridnessParams;
import com.dkorduban.gridness.fixture.LayoutFixture;
import com.dkorduban.gridness.sim.BuildSim;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Live visualizer: a sim thread builds (then dismantles) a fixture city while
 * publishing a snapshot of the walls bitmap and gridness heatmap; a Swing
 * timer repaints at ~30 Hz. Stats panel shows per-tick latency mean / p50 /
 * p95 / p99 / max / min from a 1024-sample rolling window.
 *
 * <p>JDK-only — uses {@code java.awt} + {@code javax.swing} (already on the
 * classpath, no external dependencies).
 *
 * <p>Run with:
 * <pre>gradle :viewer [-PfixtureName=city_768] [-Pmode=build|dismantle|cycle]</pre>
 */
public final class GridnessViewer extends JFrame {

    private static final int CELL_PX = 1;          // 1 source-pixel : 1 screen-pixel
    private static final int REPAINT_HZ = 30;
    private static final int STATS_WINDOW = 1024;

    private final WallPanel wallPanel;
    private final HeatmapPanel heatmapPanel;
    private final StatsPanel statsPanel;
    private final SimThread simThread;

    private GridnessViewer(LayoutFixture fx, String modeArg) {
        super("gridness viewer  —  " + fx.name + "  (" + fx.width + "x" + fx.height + ")");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        wallPanel = new WallPanel(fx.width, fx.height);
        heatmapPanel = new HeatmapPanel(fx.width, fx.height);
        statsPanel = new StatsPanel();

        JPanel viz = new JPanel(new GridLayout(1, 2, 8, 0));
        viz.add(wrapWithLabel("walls", wallPanel));
        viz.add(wrapWithLabel("gridness heatmap", heatmapPanel));

        setLayout(new BorderLayout(0, 8));
        add(viz, BorderLayout.CENTER);
        add(statsPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(null);

        simThread = new SimThread(fx, parseMode(modeArg), "cycle".equalsIgnoreCase(modeArg),
                wallPanel, heatmapPanel, statsPanel);

        Timer timer = new Timer(1000 / REPAINT_HZ, e -> {
            wallPanel.repaint();
            heatmapPanel.repaint();
            statsPanel.refresh();
        });
        timer.start();
    }

    private static JPanel wrapWithLabel(String label, JComponent c) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        JLabel l = new JLabel(label, SwingConstants.CENTER);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
        p.add(l, BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private static BuildSim.Mode parseMode(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("cycle") || s.equalsIgnoreCase("build"))
            return BuildSim.Mode.BUILD;
        if (s.equalsIgnoreCase("dismantle")) return BuildSim.Mode.DISMANTLE;
        return BuildSim.Mode.BUILD;
    }

    public static void main(String[] args) throws Exception {
        String fixtureName = "city_768";
        String mode = "cycle";
        String snapshotPath = null;
        int snapshotTicks = 600;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--snapshot" -> snapshotPath = args[++i];
                case "--ticks" -> snapshotTicks = Integer.parseInt(args[++i]);
                case "--mode" -> mode = args[++i];
                case "--fixture" -> fixtureName = args[++i];
                default -> {
                    if (!args[i].startsWith("--")) fixtureName = args[i];
                }
            }
        }
        LayoutFixture fx = LayoutFixture.load(LayoutFixture.defaultDir(), fixtureName);
        if (snapshotPath != null) {
            renderSnapshot(fx, parseMode(mode), snapshotTicks, java.nio.file.Path.of(snapshotPath));
            return;
        }
        final String modeFinal = mode;
        SwingUtilities.invokeLater(() -> {
            GridnessViewer v = new GridnessViewer(fx, modeFinal);
            v.setVisible(true);
            v.simThread.start();
        });
    }

    /** Headless: run N ticks, then write a composed PNG of walls + heatmap + stats. */
    private static void renderSnapshot(LayoutFixture fx, BuildSim.Mode mode, int ticks,
                                       java.nio.file.Path outPath) throws Exception {
        Gridness g = new Gridness(fx.width, fx.height, GridnessParams.defaults());
        BuildSim sim = new BuildSim(g, fx, mode, 3, 10, 3, 1234L);
        LatencyStats perTick = new LatencyStats(STATS_WINDOW);
        LatencyStats perCell = new LatencyStats(STATS_WINDOW);
        for (int i = 0; i < ticks; i++) {
            long t0 = System.nanoTime();
            int n = sim.tick();
            g.valueAt(fx.width / 2, fx.height / 2);
            long elapsed = System.nanoTime() - t0;
            perTick.record(elapsed);
            if (n > 0) perCell.record(elapsed / n);
        }

        int W = fx.width, H = fx.height;
        int pad = 8;
        int statsH = 140;
        BufferedImage composed = new BufferedImage(W * 2 + pad * 3, H + pad * 2 + statsH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = composed.createGraphics();
        g2.setColor(new Color(20, 20, 24));
        g2.fillRect(0, 0, composed.getWidth(), composed.getHeight());

        // Walls
        int[] wpix = new int[W * H];
        for (int y = 0; y < H; y++) {
            int base = y * W;
            for (int x = 0; x < W; x++) wpix[base + x] = g.isWall(x, y) ? 0x202028 : 0xeeeeee;
        }
        BufferedImage wallsImg = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        wallsImg.setRGB(0, 0, W, H, wpix, 0, W);
        g2.drawImage(wallsImg, pad, pad, null);

        // Heatmap
        double[][] heat = g.readRect(0, 0, W, H);
        int ny = heat.length, nx = ny > 0 ? heat[0].length : 0;
        BufferedImage heatImg = new BufferedImage(nx, ny, BufferedImage.TYPE_INT_RGB);
        int[] hpix = new int[nx * ny];
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) hpix[j * nx + i] = colormap(heat[j][i]);
        }
        heatImg.setRGB(0, 0, nx, ny, hpix, 0, nx);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(heatImg, W + 2 * pad, pad, W, H, null);

        // Stats
        g2.setColor(new Color(240, 240, 240));
        g2.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 18));
        LatencyStats.Snapshot st = perTick.snapshot();
        LatencyStats.Snapshot sc = perCell.snapshot();
        int yText = H + pad + 28;
        int xText = pad + 4;
        g2.drawString(String.format(
                "fixture=%s  field=%dx%d  mode=%s  ticks=%d  workers/building=10±3 (active=3)",
                fx.name, W, H, mode, st.totalSamples()), xText, yText);
        g2.drawString(String.format(
                "per tick (ms): mean=%.2f  min=%.2f  p05=%.2f  p50=%.2f  p95=%.2f  p99=%.2f  max=%.2f",
                st.meanNs() / 1e6, st.minNs() / 1e6, st.p05Ns() / 1e6, st.p50Ns() / 1e6,
                st.p95Ns() / 1e6, st.p99Ns() / 1e6, st.maxNs() / 1e6), xText, yText + 30);
        g2.drawString(String.format(
                "per cell (ms): mean=%.3f min=%.3f p05=%.3f p50=%.3f p95=%.3f p99=%.3f max=%.3f",
                sc.meanNs() / 1e6, sc.minNs() / 1e6, sc.p05Ns() / 1e6, sc.p50Ns() / 1e6,
                sc.p95Ns() / 1e6, sc.p99Ns() / 1e6, sc.maxNs() / 1e6), xText, yText + 60);
        g2.dispose();
        javax.imageio.ImageIO.write(composed, "png", outPath.toFile());
        System.out.println("wrote " + outPath + " (" + composed.getWidth() + "x" + composed.getHeight() + ")");
    }

    // ============================== sim thread ==============================

    private static final class SimThread extends Thread {
        private final LayoutFixture fx;
        private final BuildSim.Mode initialMode;
        private final boolean cycle;
        private final WallPanel wallPanel;
        private final HeatmapPanel heatmapPanel;
        private final StatsPanel statsPanel;
        private final Gridness g;
        private final LatencyStats latencyPerTick = new LatencyStats(STATS_WINDOW);
        private final LatencyStats latencyPerCell = new LatencyStats(STATS_WINDOW);

        SimThread(LayoutFixture fx, BuildSim.Mode mode, boolean cycle,
                  WallPanel wallPanel, HeatmapPanel heatmapPanel, StatsPanel statsPanel) {
            super("gridness-sim");
            setDaemon(true);
            this.fx = fx;
            this.initialMode = mode;
            this.cycle = cycle;
            this.wallPanel = wallPanel;
            this.heatmapPanel = heatmapPanel;
            this.statsPanel = statsPanel;
            this.g = new Gridness(fx.width, fx.height, GridnessParams.defaults());
            statsPanel.bind(latencyPerTick, latencyPerCell, g);
        }

        @Override
        public void run() {
            BuildSim.Mode mode = initialMode;
            BuildSim sim = new BuildSim(g, fx, mode, 3, 10, 3, 1234L);
            long lastModeFlip = System.nanoTime();
            long modeFlipPeriodNs = 30_000_000_000L;  // 30s per direction

            while (!Thread.currentThread().isInterrupted()) {
                long t0 = System.nanoTime();
                int n = sim.tick();
                g.valueAt(fx.width / 2, fx.height / 2);  // force ensureClean
                long elapsed = System.nanoTime() - t0;
                latencyPerTick.record(elapsed);
                if (n > 0) latencyPerCell.record(elapsed / n);

                // Snapshot field + heatmap into the display buffers. Read on the
                // sim thread (the only thread that touches Gridness) and publish
                // atomically for the EDT.
                wallPanel.publish(g);
                heatmapPanel.publish(g);

                // Throttle so the viewer can keep up visually; doing 1000s of
                // ticks per second gives no human-visible difference.
                long target = 1_000_000_000L / 60;  // 60 ticks/sec budget
                long sleepNs = target - elapsed;
                if (sleepNs > 0) {
                    try { Thread.sleep(sleepNs / 1_000_000, (int) (sleepNs % 1_000_000)); }
                    catch (InterruptedException ignored) { return; }
                }

                if (cycle && System.nanoTime() - lastModeFlip > modeFlipPeriodNs) {
                    mode = (mode == BuildSim.Mode.BUILD) ? BuildSim.Mode.DISMANTLE : BuildSim.Mode.BUILD;
                    sim = new BuildSim(g, fx, mode, 3, 10, 3, 1234L);
                    lastModeFlip = System.nanoTime();
                }
            }
        }
    }

    // ============================== panels ==============================

    /** Renders the walls boolean grid as a 1bpp BufferedImage. */
    private static final class WallPanel extends JComponent {
        private final int W, H;
        private final AtomicReference<BufferedImage> img = new AtomicReference<>();
        private BufferedImage scratch;

        WallPanel(int W, int H) {
            this.W = W; this.H = H;
            setPreferredSize(new Dimension(W * CELL_PX, H * CELL_PX));
            setBackground(Color.WHITE);
            scratch = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        }

        void publish(Gridness g) {
            BufferedImage local = scratch;
            int[] pix = ((DataBufferInt) local.getRaster().getDataBuffer()).getData();
            for (int y = 0; y < H; y++) {
                int base = y * W;
                for (int x = 0; x < W; x++) {
                    pix[base + x] = g.isWall(x, y) ? 0x000000 : 0xFFFFFF;
                }
            }
            // Double-buffer: publish current, get the previous one for next write.
            BufferedImage prev = img.getAndSet(local);
            scratch = prev != null ? prev : new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        }

        @Override
        protected void paintComponent(Graphics gr) {
            BufferedImage current = img.get();
            if (current == null) {
                gr.setColor(Color.WHITE);
                gr.fillRect(0, 0, getWidth(), getHeight());
                return;
            }
            gr.drawImage(current, 0, 0, getWidth(), getHeight(), null);
        }
    }

    /** Renders the heatmap with a turbo-ish colormap, upscaled to the panel. */
    private static final class HeatmapPanel extends JComponent {
        private final int W, H;
        private final AtomicReference<BufferedImage> img = new AtomicReference<>();
        private BufferedImage scratch;
        private final int sampleNx, sampleNy;

        HeatmapPanel(int W, int H) {
            this.W = W; this.H = H;
            this.sampleNx = (W + GridnessParams.defaults().sampleStride - 1) / GridnessParams.defaults().sampleStride;
            this.sampleNy = (H + GridnessParams.defaults().sampleStride - 1) / GridnessParams.defaults().sampleStride;
            setPreferredSize(new Dimension(W * CELL_PX, H * CELL_PX));
            setBackground(Color.BLACK);
            scratch = new BufferedImage(sampleNx, sampleNy, BufferedImage.TYPE_INT_RGB);
        }

        void publish(Gridness g) {
            double[][] grid = g.readRect(0, 0, W, H);
            BufferedImage local = scratch;
            int[] pix = ((DataBufferInt) local.getRaster().getDataBuffer()).getData();
            int rows = Math.min(grid.length, sampleNy);
            int cols = rows > 0 ? Math.min(grid[0].length, sampleNx) : 0;
            for (int j = 0; j < rows; j++) {
                int base = j * sampleNx;
                double[] row = grid[j];
                for (int i = 0; i < cols; i++) {
                    pix[base + i] = colormap(row[i]);
                }
            }
            BufferedImage prev = img.getAndSet(local);
            scratch = prev != null ? prev : new BufferedImage(sampleNx, sampleNy, BufferedImage.TYPE_INT_RGB);
        }

        @Override
        protected void paintComponent(Graphics gr) {
            BufferedImage current = img.get();
            if (current == null) {
                gr.setColor(Color.DARK_GRAY);
                gr.fillRect(0, 0, getWidth(), getHeight());
                return;
            }
            Graphics2D g2 = (Graphics2D) gr;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(current, 0, 0, getWidth(), getHeight(), null);
        }
    }

    /** Map [0,1] -> turbo-ish RGB int. Black -> blue -> cyan -> green -> yellow -> red. */
    private static int colormap(double v) {
        if (Double.isNaN(v)) return 0x000000;
        if (v < 0) v = 0; else if (v > 1) v = 1;
        // 5-stop linear gradient.
        // 0.00: dark navy        0x0a1a40
        // 0.25: cyan              0x00b4d8
        // 0.50: green             0x90e000
        // 0.75: yellow            0xffd400
        // 1.00: red               0xc81010
        double[] stops = {0.0, 0.25, 0.5, 0.75, 1.0};
        int[] colors = {0x0a1a40, 0x00b4d8, 0x90e000, 0xffd400, 0xc81010};
        int seg = 0;
        while (seg < stops.length - 2 && v > stops[seg + 1]) seg++;
        double t = (v - stops[seg]) / (stops[seg + 1] - stops[seg]);
        return lerpRgb(colors[seg], colors[seg + 1], t);
    }

    private static int lerpRgb(int a, int b, double t) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        int r = (int) Math.round(ar + (br - ar) * t);
        int g = (int) Math.round(ag + (bg - ag) * t);
        int bl = (int) Math.round(ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    // ============================== stats panel ==============================

    private static final class StatsPanel extends JPanel {
        private LatencyStats perTick;
        private LatencyStats perCell;
        private Gridness g;
        private final JLabel header = new JLabel("waiting for ticks...");
        private final JLabel detailTick = new JLabel(" ");
        private final JLabel detailCell = new JLabel(" ");
        private final JLabel sim = new JLabel(" ");

        StatsPanel() {
            setLayout(new GridLayout(4, 1));
            setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
            Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 13);
            header.setFont(mono.deriveFont(Font.BOLD));
            detailTick.setFont(mono);
            detailCell.setFont(mono);
            sim.setFont(mono);
            add(header); add(detailTick); add(detailCell); add(sim);
        }

        void bind(LatencyStats perTick, LatencyStats perCell, Gridness g) {
            this.perTick = perTick;
            this.perCell = perCell;
            this.g = g;
        }

        void refresh() {
            if (perTick == null) return;
            LatencyStats.Snapshot st = perTick.snapshot();
            LatencyStats.Snapshot sc = perCell.snapshot();
            if (st.windowSize() == 0) return;
            header.setText(String.format("ticks=%d   window=%d   workers/building=10±3 (active=3)",
                    st.totalSamples(), st.windowSize()));
            detailTick.setText(String.format(
                    "per tick   (ms): mean=%.2f  min=%.2f  p05=%.2f  p50=%.2f  p95=%.2f  p99=%.2f  max=%.2f",
                    st.meanNs() / 1e6,
                    st.minNs() / 1e6, st.p05Ns() / 1e6, st.p50Ns() / 1e6,
                    st.p95Ns() / 1e6, st.p99Ns() / 1e6, st.maxNs() / 1e6));
            detailCell.setText(String.format(
                    "per cell   (ms): mean=%.3f min=%.3f p05=%.3f p50=%.3f p95=%.3f p99=%.3f max=%.3f",
                    sc.meanNs() / 1e6,
                    sc.minNs() / 1e6, sc.p05Ns() / 1e6, sc.p50Ns() / 1e6,
                    sc.p95Ns() / 1e6, sc.p99Ns() / 1e6, sc.maxNs() / 1e6));
            sim.setText(String.format(
                    "field=%dx%d  tiles=%d  samples=%d  defaults: tile=32 stride=32 R=30 sampleStride=8",
                    g.width(), g.height(), g.tileCount(), g.sampleCount()));
        }
    }
}
