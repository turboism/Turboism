package dev.turboism.plugin.perfstats;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Standalone Swing window drawing the same rolling series as the embedded
 * panel (task-manager style polyline charts), refreshed on the EDT by a 1s
 * timer. Data comes from the shared {@link ChartStore} fed by the sampling
 * consumer, so the window and the embedded panel show the same source.
 */
final class PerfStatsWindow {

    private static final int WINDOW_SIZE = 120;
    private static final Color[] SERIES_COLORS = {
        new Color(31, 119, 180),
        new Color(255, 127, 14),
        new Color(44, 160, 44),
        new Color(214, 39, 40),
        new Color(148, 103, 189),
        new Color(140, 86, 75)
    };

    private final ChartStore store;
    private final Map<String, String> labels;
    private final JFrame frame;
    private final Timer refresh;

    PerfStatsWindow(final String title, final Map<String, String> labels, final ChartStore store) {
        this.store = store;
        this.labels = Objects.requireNonNull(labels, "labels");
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        frame.setLayout(new GridLayout(0, 1));
        frame.add(new RowChart(ChartStore.KEY_CPU, label(ChartStore.KEY_CPU), "%"));
        frame.add(new RowChart(ChartStore.KEY_FPS, label(ChartStore.KEY_FPS), "fps"));
        frame.add(new RowChart(ChartStore.KEY_HEAP, label(ChartStore.KEY_HEAP), "MiB"));
        frame.add(new RowChart(ChartStore.KEY_NONHEAP, label(ChartStore.KEY_NONHEAP), "MiB"));
        frame.add(new RowChart(ChartStore.KEY_GC, label(ChartStore.KEY_GC), "ms"));
        frame.setSize(480, 414);
        refresh = new Timer(1000, ignored -> frame.repaint());
    }

    private String label(final String key) {
        return Objects.requireNonNull(labels.get(key), "missing row label for " + key);
    }

    void showAndFront() {
        frame.setVisible(true);
        frame.toFront();
    }

    void start() {
        refresh.start();
    }

    void dispose() {
        refresh.stop();
        frame.dispose();
    }

    boolean isVisible() {
        return frame.isVisible();
    }

    /** One polyline row: last {@value WINDOW_SIZE} points of one series. */
    private final class RowChart extends JComponent {

        private final String key;
        private final String label;
        private final String unit;

        private RowChart(final String key, final String label, final String unit) {
            this.key = key;
            this.label = label;
            this.unit = unit;
            setPreferredSize(new Dimension(460, 74));
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            super.paintComponent(graphics);
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                final int width = getWidth();
                final int height = getHeight();
                final List<Double> values = store.values(key);
                final int window = Math.min(WINDOW_SIZE, values.size());
                double max = 1.0;
                for (int i = values.size() - window; i < values.size(); i++) {
                    max = Math.max(max, values.get(i));
                }
                g.setColor(Color.DARK_GRAY);
                g.drawString(label, 4, 14);
                final int plotLeft = 70;
                final int plotRight = Math.max(plotLeft + 1, width - 8);
                final int plotTop = 18;
                final int plotBottom = Math.max(plotTop + 1, height - 8);
                g.setColor(new Color(220, 220, 220));
                g.drawLine(plotLeft, plotBottom, plotRight, plotBottom);
                if (window < 2) {
                    g.drawString("-", plotLeft + 4, (plotTop + plotBottom) / 2);
                    return;
                }
                g.setColor(SERIES_COLORS[Map.of(
                    ChartStore.KEY_CPU, 0, ChartStore.KEY_FPS, 1,
                    ChartStore.KEY_HEAP, 2, ChartStore.KEY_NONHEAP, 3,
                    ChartStore.KEY_GC, 5
                ).getOrDefault(key, 4)]);
                g.setStroke(new BasicStroke(1.8f));
                int previousX = -1;
                int previousY = -1;
                for (int index = 0; index < window; index++) {
                    final double value = values.get(values.size() - window + index);
                    final int x = plotLeft + (int) Math.round((index / (double) (window - 1)) * (plotRight - plotLeft));
                    final int y = plotBottom - (int) Math.round((value / max) * (plotBottom - plotTop));
                    if (previousX >= 0) {
                        g.drawLine(previousX, previousY, x, y);
                    }
                    previousX = x;
                    previousY = y;
                }
                final double last = values.get(values.size() - 1);
                g.drawString(new DecimalFormat("0.0").format(last) + " " + unit, plotLeft + 4, plotTop + 2);
            } finally {
                g.dispose();
            }
        }
    }
}
