package dev.turboism.plugin.perfstats;

import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.AbstractButton;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.Timer;
import javax.swing.WindowConstants;

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
 *
 * <p>The window hides on close, so the refresh timer follows visibility: it runs only while the
 * window is on screen and stops when the window is hidden, minimized away or iconified. A hidden
 * window therefore costs no repaint work, and reopening it restarts the timer.</p>
 */
final class PerfStatsWindow {

    private static final int WINDOW_SIZE = 120;
    private static final int WINDOW_WIDTH = 480;
    private static final int WINDOW_HEIGHT = 260;
    private static final int REFRESH_INTERVAL_MILLIS = 1000;
    private static final int ROW_PREFERRED_WIDTH = 460;
    private static final int ROW_PREFERRED_HEIGHT = 74;
    private static final int PLOT_RIGHT_INSET = 8;
    private static final int PLOT_BOTTOM_INSET = 8;
    private static final int VALUE_TEXT_LEFT_OFFSET = 4;
    private static final int SERIES_LABEL_GAP = 12;
    private static final double MIN_AXIS_MAX = 1.0;
    private static final float CURVE_STROKE_WIDTH = 1.8f;
    private static final int MIN_POLYLINE_SAMPLES = 2;
    private static final String VALUE_FORMAT = "0.0";
    private static final String UNIT_PERCENT = "%";
    private static final String UNIT_MEBIBYTES = "MiB";
    private static final String NO_DATA_PLACEHOLDER = "-";
    private static final String DEFAULT_HEAP_LABEL = "Heap";
    private static final String DEFAULT_NONHEAP_LABEL = "Non-Heap";

    /**
     * Same fixed palette as the runtime embedded charts
     * (runtime {@code ChartSeriesColors} chart-id palette); the two forms
     * must stay visually aligned, so keep both in sync.
     */
    private static final Color CPU_COLOR = new Color(31, 119, 180);

    private static final Color HEAP_COLOR = new Color(44, 160, 44);
    private static final Color NONHEAP_COLOR = new Color(214, 39, 40);
    private static final Color BASELINE_COLOR = new Color(220, 220, 220);

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
    private final Map<String, String> seriesLabels;
    private final JFrame frame;
    private final Timer refresh;
    /** Set by {@link #start()} or {@link #showAndFront()}; gates the visibility-driven timer. */
    private boolean started;

    PerfStatsWindow(
            final String title,
            final Map<String, String> titles,
            final String expandLabel,
            final String collapseLabel,
            final ChartStore store) {
        this(
                title,
                titles,
                Map.of(ChartStore.KEY_HEAP, DEFAULT_HEAP_LABEL, ChartStore.KEY_NONHEAP, DEFAULT_NONHEAP_LABEL),
                expandLabel,
                collapseLabel,
                store);
    }

    PerfStatsWindow(
            final String title,
            final Map<String, String> titles,
            final Map<String, String> seriesLabels,
            final String expandLabel,
            final String collapseLabel,
            final ChartStore store) {
        this.store = Objects.requireNonNull(store, "store");
        this.titles = Objects.requireNonNull(titles, "titles");
        this.seriesLabels = Objects.requireNonNull(seriesLabels, "seriesLabels");
        frame = TurboismWindowFactory.frame(title);
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        final JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        addRow(
                rows,
                ChartStore.KEY_CPU,
                List.of(new RowSeries(ChartStore.KEY_CPU, "", UNIT_PERCENT, CPU_COLOR)),
                expandLabel,
                collapseLabel);
        addRow(
                rows,
                ChartStore.KEY_MEMORY,
                List.of(
                        new RowSeries(
                                ChartStore.KEY_HEAP,
                                seriesLabel(ChartStore.KEY_HEAP, DEFAULT_HEAP_LABEL),
                                UNIT_MEBIBYTES,
                                HEAP_COLOR),
                        new RowSeries(
                                ChartStore.KEY_NONHEAP,
                                seriesLabel(ChartStore.KEY_NONHEAP, DEFAULT_NONHEAP_LABEL),
                                UNIT_MEBIBYTES,
                                NONHEAP_COLOR)),
                expandLabel,
                collapseLabel);
        frame.setContentPane(rows);
        frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        refresh = new Timer(REFRESH_INTERVAL_MILLIS, ignored -> frame.repaint());
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(final ComponentEvent event) {
                syncRefresh();
            }

            @Override
            public void componentHidden(final ComponentEvent event) {
                syncRefresh();
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent event) {
                // HIDE_ON_CLOSE hides the frame after this callback, so stop here instead of
                // relying on the later component-hidden event.
                refresh.stop();
            }

            @Override
            public void windowDeiconified(final WindowEvent event) {
                syncRefresh();
            }

            @Override
            public void windowIconified(final WindowEvent event) {
                syncRefresh();
            }
        });
    }

    private void addRow(
            final JPanel rows,
            final String titleKey,
            final List<RowSeries> series,
            final String expandLabel,
            final String collapseLabel) {
        rows.add(new MetricRow(title(titleKey), expandLabel, collapseLabel, new RowChart(store, series)));
    }

    private String title(final String key) {
        return Objects.requireNonNull(titles.get(key), "missing row title for " + key);
    }

    private String seriesLabel(final String key, final String fallback) {
        return seriesLabels.getOrDefault(key, fallback);
    }

    /** Creates a standalone memory row chart for headless rendering checks. */
    static JComponent memoryRowChart(final ChartStore store, final String heapLabel, final String nonHeapLabel) {
        return new RowChart(
                Objects.requireNonNull(store, "store"),
                List.of(
                        new RowSeries(ChartStore.KEY_HEAP, heapLabel, UNIT_MEBIBYTES, HEAP_COLOR),
                        new RowSeries(ChartStore.KEY_NONHEAP, nonHeapLabel, UNIT_MEBIBYTES, NONHEAP_COLOR)));
    }

    void showAndFront() {
        started = true;
        frame.setVisible(true);
        frame.toFront();
        syncRefresh();
    }

    void start() {
        started = true;
        syncRefresh();
    }

    void dispose() {
        started = false;
        refresh.stop();
        frame.dispose();
    }

    boolean isVisible() {
        return frame.isVisible();
    }

    /** True while the periodic repaint timer is scheduled. */
    boolean isRefreshing() {
        return refresh.isRunning();
    }

    /**
     * Aligns the refresh timer with the window state.
     *
     * <p>The timer must run only while the window can show a repaint, so a hidden or iconified
     * window stops paying for a 1 Hz repaint of invisible content.</p>
     */
    private void syncRefresh() {
        final boolean active = started && frame.isVisible() && !isIconified();
        if (active == refresh.isRunning()) return;
        if (active) refresh.start();
        else refresh.stop();
    }

    private boolean isIconified() {
        return (frame.getExtendedState() & java.awt.Frame.ICONIFIED) != 0;
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

        MetricRow(final String title, final String expandLabel, final String collapseLabel, final JComponent content) {
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

    private record RowSeries(String key, String label, String unit, Color color) {}

    /** One polyline row: last {@value WINDOW_SIZE} points of one or more series on a shared axis. */
    private static final class RowChart extends JComponent {

        private final ChartStore store;
        private final List<RowSeries> series;

        private RowChart(final ChartStore store, final List<RowSeries> series) {
            this.store = store;
            this.series = List.copyOf(series);
            setPreferredSize(new Dimension(ROW_PREFERRED_WIDTH, ROW_PREFERRED_HEIGHT));
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            super.paintComponent(graphics);
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                final int width = getWidth();
                final int height = getHeight();
                final int plotLeft = PLOT_LEFT_INSET;
                final int plotRight = Math.max(plotLeft + 1, width - PLOT_RIGHT_INSET);
                final FontMetrics metrics = g.getFontMetrics();
                final int valueBaseline = TOP_INSET + metrics.getAscent();
                final int plotTop = TOP_INSET + metrics.getHeight() + TEXT_BAND_GAP;
                final int plotBottom = Math.max(plotTop + 1, height - PLOT_BOTTOM_INSET);
                g.setColor(BASELINE_COLOR);
                g.drawLine(plotLeft, plotBottom, plotRight, plotBottom);

                final double sharedMax = computeSharedMax();
                boolean hasValues = false;
                for (RowSeries spec : series) {
                    final List<Double> values = store.values(spec.key());
                    if (!values.isEmpty()) {
                        hasValues = true;
                        drawCurve(g, plotLeft, plotTop, plotRight, plotBottom, spec.color(), values, sharedMax);
                    }
                }
                if (!hasValues) {
                    g.drawString(NO_DATA_PLACEHOLDER, plotLeft + VALUE_TEXT_LEFT_OFFSET, valueBaseline);
                    return;
                }
                drawValues(g, metrics, plotLeft + VALUE_TEXT_LEFT_OFFSET, valueBaseline);
            } finally {
                g.dispose();
            }
        }

        private double computeSharedMax() {
            double max = MIN_AXIS_MAX;
            for (RowSeries spec : series) {
                final List<Double> values = store.values(spec.key());
                final int window = Math.min(WINDOW_SIZE, values.size());
                for (int i = values.size() - window; i < values.size(); i++) {
                    max = Math.max(max, values.get(i));
                }
            }
            return max;
        }

        private void drawCurve(
                final Graphics2D g,
                final int plotLeft,
                final int plotTop,
                final int plotRight,
                final int plotBottom,
                final Color color,
                final List<Double> values,
                final double max) {
            final int window = Math.min(WINDOW_SIZE, values.size());
            if (window < MIN_POLYLINE_SAMPLES) {
                return;
            }
            g.setColor(color);
            g.setStroke(new BasicStroke(CURVE_STROKE_WIDTH));
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
        }

        private void drawValues(final Graphics2D g, final FontMetrics metrics, final int startX, final int baseline) {
            final boolean multiSeries = series.size() > 1;
            final DecimalFormat formatter = new DecimalFormat(VALUE_FORMAT);
            int textX = startX;
            for (RowSeries spec : series) {
                final List<Double> values = store.values(spec.key());
                if (values.isEmpty()) continue;
                final String formatted = formatter.format(values.get(values.size() - 1)) + " " + spec.unit();
                final String text = multiSeries ? spec.label() + ": " + formatted : formatted;
                g.setColor(spec.color());
                g.drawString(text, textX, baseline);
                if (!multiSeries) return;
                textX += metrics.stringWidth(text) + SERIES_LABEL_GAP;
            }
        }
    }
}
