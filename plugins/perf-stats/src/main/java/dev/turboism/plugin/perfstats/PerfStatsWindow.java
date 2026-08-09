package dev.turboism.plugin.perfstats;

import javax.swing.AbstractButton;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Standalone Swing window drawing the same rolling series as the embedded
 * panel (task-manager style polyline rows), refreshed on the EDT by a 1s
 * timer. Data comes from the shared {@link ChartStore} fed by the sampling
 * consumer, so the window and the embedded panel show the same source. Each
 * metric row carries the same {@code chart.*.title} text as the embedded
 * collapsible section; the row header is a borderless toggle button (click
 * or keyboard activation) that collapses/expands the chart independently,
 * and every row starts expanded. A collapsed row reclaims its vertical
 * space, and the chart itself never repeats the header title.
 */
final class PerfStatsWindow {

    private static final int WINDOW_SIZE = 120;

    /**
     * Same fixed palette as the runtime embedded charts
     * (runtime {@code ChartSeriesColors} chart-id palette); the two forms
     * must stay visually aligned, so keep both in sync.
     */
    private static final Color[] SERIES_COLORS = {
        new Color(31, 119, 180),
        new Color(255, 127, 14),
        new Color(44, 160, 44),
        new Color(214, 39, 40),
        new Color(148, 103, 189),
        new Color(140, 86, 75)
    };

    /**
     * Plot inset of the standalone row charts. The compact row style draws no
     * Y-axis labels, so the plot starts flush with the row content edge; keep
     * in sync with the runtime {@code ChartComponent.LEFT_MARGIN} so both
     * chart forms align with their row left edge.
     */
    static final int PLOT_LEFT_INSET = 8;
    // Mirrors the runtime ChartComponent band layout: the current value sits
    // in its own top-left text band and the plot starts below it, so a line
    // at the axis maximum cannot cross the text.
    static final int TOP_INSET = 2;
    static final int TEXT_BAND_GAP = 2;

    private final ChartStore store;
    private final Map<String, String> titles;
    private final JFrame frame;
    private final Timer refresh;

    PerfStatsWindow(
        final String title,
        final Map<String, String> titles,
        final String expandLabel,
        final String collapseLabel,
        final ChartStore store
    ) {
        this.store = store;
        this.titles = Objects.requireNonNull(titles, "titles");
        frame = new JFrame(title);
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        final JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        addRow(rows, ChartStore.KEY_CPU, "%", expandLabel, collapseLabel);
        addRow(rows, ChartStore.KEY_FPS, "fps", expandLabel, collapseLabel);
        addRow(rows, ChartStore.KEY_HEAP, "MiB", expandLabel, collapseLabel);
        addRow(rows, ChartStore.KEY_NONHEAP, "MiB", expandLabel, collapseLabel);
        addRow(rows, ChartStore.KEY_GC, "ms", expandLabel, collapseLabel);
        frame.setContentPane(rows);
        frame.setSize(480, 500);
        refresh = new Timer(1000, ignored -> frame.repaint());
    }

    private void addRow(
        final JPanel rows,
        final String key,
        final String unit,
        final String expandLabel,
        final String collapseLabel
    ) {
        rows.add(new MetricRow(
            title(key),
            expandLabel,
            collapseLabel,
            new RowChart(key, unit)
        ));
    }

    private String title(final String key) {
        return Objects.requireNonNull(titles.get(key), "missing row title for " + key);
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

    /**
     * One independently collapsible metric row: a borderless toggle-button
     * header (focusable and keyboard-activatable, with the localized
     * Expand/Collapse action as accessible text) plus the chart content.
     * Collapsing hides the content and pins the row's maximum size to its
     * (now smaller) preferred size so the freed vertical space is reclaimed
     * instead of being left as an empty block.
     */
    static final class MetricRow extends JPanel {

        private final String title;
        private final String expandLabel;
        private final String collapseLabel;
        private final JComponent content;
        private final JToggleButton header;

        MetricRow(
            final String title,
            final String expandLabel,
            final String collapseLabel,
            final JComponent content
        ) {
            this.title = Objects.requireNonNull(title, "title");
            this.expandLabel = Objects.requireNonNull(expandLabel, "expandLabel");
            this.collapseLabel = Objects.requireNonNull(collapseLabel, "collapseLabel");
            this.content = Objects.requireNonNull(content, "content");
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            header = new JToggleButton();
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            header.setBorderPainted(false);
            header.setContentAreaFilled(false);
            header.setFocusPainted(true);
            header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            header.addActionListener(ignored -> setExpanded(header.isSelected()));
            add(header);
            add(content);
            setExpanded(true);
        }

        boolean isExpanded() {
            return content.isVisible();
        }

        String headerText() {
            return header.getText();
        }

        /** The toggle button; activation (click, Space or Enter) collapses/expands the row. */
        AbstractButton header() {
            return header;
        }

        void setExpanded(final boolean expanded) {
            content.setVisible(expanded);
            final String action = expanded ? collapseLabel : expandLabel;
            header.setSelected(expanded);
            header.setText(title + "   " + action);
            header.getAccessibleContext().setAccessibleName(title + " " + action);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
            revalidate();
            repaint();
        }
    }

    /** One polyline row: last {@value WINDOW_SIZE} points of one series. */
    private final class RowChart extends JComponent {

        private final String key;
        private final String unit;

        private RowChart(final String key, final String unit) {
            this.key = key;
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
                final int plotLeft = PLOT_LEFT_INSET;
                final int plotRight = Math.max(plotLeft + 1, width - 8);
                final java.awt.FontMetrics metrics = g.getFontMetrics();
                final int valueBaseline = TOP_INSET + metrics.getAscent();
                final int plotTop = TOP_INSET + metrics.getHeight() + TEXT_BAND_GAP;
                final int plotBottom = Math.max(plotTop + 1, height - 8);
                g.setColor(new Color(220, 220, 220));
                g.drawLine(plotLeft, plotBottom, plotRight, plotBottom);
                if (values.isEmpty()) {
                    g.drawString("-", plotLeft + 4, valueBaseline);
                    return;
                }
                final Color seriesColor = SERIES_COLORS[Map.of(
                    ChartStore.KEY_CPU, 0, ChartStore.KEY_FPS, 1,
                    ChartStore.KEY_HEAP, 2, ChartStore.KEY_NONHEAP, 3,
                    ChartStore.KEY_GC, 5
                ).getOrDefault(key, 4)];
                if (window < 2) {
                    // Single sample: show the real current value once in the
                    // band, no polyline and no placeholder (it would overlap).
                    g.setColor(seriesColor);
                    g.drawString(new DecimalFormat("0.0").format(values.get(values.size() - 1))
                        + " " + unit, plotLeft + 4, valueBaseline);
                    return;
                }
                g.setColor(seriesColor);
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
                g.drawString(new DecimalFormat("0.0").format(last) + " " + unit, plotLeft + 4, valueBaseline);
            } finally {
                g.dispose();
            }
        }
    }
}
