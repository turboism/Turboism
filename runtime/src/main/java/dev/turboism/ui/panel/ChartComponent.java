package dev.turboism.ui.panel;

import dev.turboism.sdk.ui.PanelView;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Optional;

/**
 * Runtime-owned Swing rendering of a {@link PanelView.Chart}: a compact
 * single-row time-series chart (task-manager style) whose values are injected
 * from {@link ChartDataRegistry} by chart id, refreshed on the EDT by a 1s
 * timer without ever blocking the host UI thread. The row keeps the same
 * visual language as the standalone performance window: label line, baseline,
 * current value and one polyline per series, about 74px tall. Series colors
 * come from the fixed {@link ChartSeriesColors} chart-id palette; series are
 * matched by display name against the published chart data, falling back to
 * the same ordinal when the declared name is localized; missing series render
 * as empty. When {@code showTitle} is false the chart defers its label to an
 * enclosing border title (for example a single-chart collapsible section) and
 * only draws the value line.
 */
final class ChartComponent extends JComponent {

    // Compact row style draws no Y-axis labels, so no left gutter is needed;
    // keep the plot flush with the row content edge (same inset as the right).
    private static final int LEFT_MARGIN = 8;
    private static final int RIGHT_MARGIN = 8;
    private static final int BOTTOM_MARGIN = 8;
    private static final int ROW_HEIGHT = 74;
    // Gap between the current-value text band and the plot area; the plot
    // starts below the band so a line at the axis maximum can never cross
    // the text.
    private static final int TEXT_BAND_GAP = 2;

    private final PanelView.Chart chart;
    private final boolean showTitle;
    private final Timer refresh;

    ChartComponent(final PanelView.Chart chart) {
        this(chart, true);
    }

    ChartComponent(final PanelView.Chart chart, final boolean showTitle) {
        this.chart = chart;
        this.showTitle = showTitle;
        setPreferredSize(new Dimension(340, ROW_HEIGHT));
        refresh = new Timer(1000, ignored -> repaint());
    }

    /** Test/structural check: whether this chart paints its own title line. */
    boolean showsTitle() {
        return showTitle;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        refresh.start();
    }

    @Override
    public void removeNotify() {
        refresh.stop();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
        super.paintComponent(graphics);
        final Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            final java.awt.FontMetrics metrics = g.getFontMetrics();
            final int width = getWidth();
            final int height = getHeight();
            final int plotLeft = LEFT_MARGIN;
            final int plotRight = Math.max(plotLeft + 1, width - RIGHT_MARGIN);
            // The current value sits in a text band at the very top of the
            // component (perf mode), or directly below the own title line
            // (generic mode); the plot starts below the band so a max-reaching
            // line can never cross the text.
            final int bandInset = 2;
            final int bandOffset = showTitle ? metrics.getHeight() + TEXT_BAND_GAP : 0;
            final int valueBaseline = bandInset + bandOffset + metrics.getAscent();
            final int plotTop = bandInset + bandOffset + metrics.getHeight() + TEXT_BAND_GAP;
            final int plotBottom = Math.max(plotTop + 1, height - BOTTOM_MARGIN);
            g.setColor(getForeground());
            if (showTitle) {
                g.drawString(chart.title(), 4, bandInset + metrics.getAscent());
            }
            final ChartDataRegistry.ChartData data = ChartDataRegistry.find(chart.id()).orElse(null);
            if (data == null || !hasAnyValues(data)) {
                g.drawString("no data", plotLeft + 4, valueBaseline);
                return;
            }
            g.setColor(new Color(220, 220, 220));
            g.setStroke(new BasicStroke(1f));
            g.drawLine(plotLeft, plotBottom, plotRight, plotBottom);
            boolean drewSeries = false;
            for (int index = 0; index < chart.series().size(); index++) {
                final PanelView.SeriesSpec spec = chart.series().get(index);
                final Optional<List<Double>> values = seriesValues(data, spec, index);
                if (values.isEmpty()) continue;
                g.setColor(ChartSeriesColors.colorFor(chart.id(), index));
                drawSeries(g, plotLeft, plotTop, plotRight, plotBottom, spec, values.get());
                drewSeries = true;
            }
            if (drewSeries) {
                g.setColor(ChartSeriesColors.colorFor(chart.id(), 0));
                final List<Double> first = seriesValues(
                    data, chart.series().get(0), 0).orElse(List.of());
                if (!first.isEmpty()) {
                    g.drawString(formatSeriesValue(chart.series().get(0), first.get(first.size() - 1)),
                        plotLeft + 4, valueBaseline);
                }
            }
        } finally {
            g.dispose();
        }
    }

    /** Whether any published series carries at least one sampled value. */
    private static boolean hasAnyValues(final ChartDataRegistry.ChartData data) {
        for (ChartDataRegistry.ChartSeriesData series : data.series()) {
            if (!series.values().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Values for one declared series: exact display-name match first (the
     * published series keep stable English names), then the same ordinal in
     * the published data so localized display names still resolve.
     */
    static Optional<List<Double>> seriesValues(
        final ChartDataRegistry.ChartData data,
        final PanelView.SeriesSpec spec,
        final int index
    ) {
        for (ChartDataRegistry.ChartSeriesData series : data.series()) {
            if (series.name().equals(spec.name())) {
                return Optional.of(series.values());
            }
        }
        if (index < data.series().size()) {
            return Optional.of(data.series().get(index).values());
        }
        return Optional.empty();
    }

    private void drawSeries(
        final Graphics2D g,
        final int left,
        final int top,
        final int right,
        final int bottom,
        final PanelView.SeriesSpec spec,
        final List<Double> values
    ) {
        double max = 1.0;
        final int window = Math.min(spec.maxPoints(), values.size());
        for (int i = values.size() - window; i < values.size(); i++) {
            max = Math.max(max, values.get(i));
        }
        // Fewer than two samples: no polyline yet; the current value is drawn
        // once by the caller, so nothing must be painted here (a placeholder
        // would overlap the value text).
        if (window < 2) {
            return;
        }
        g.setStroke(new BasicStroke(1.8f));
        int previousX = -1;
        int previousY = -1;
        for (int index = 0; index < window; index++) {
            final double value = values.get(values.size() - window + index);
            final int x = left + (int) Math.round((index / (double) (window - 1)) * (right - left));
            final int y = bottom - (int) Math.round((value / max) * (bottom - top));
            if (previousX >= 0) {
                g.drawLine(previousX, previousY, x, y);
            }
            previousX = x;
            previousY = y;
        }
    }

    private String formatSeriesValue(final PanelView.SeriesSpec spec, final double value) {
        final String formatted = new DecimalFormat(spec.format()).format(value);
        return spec.unit().isBlank() ? formatted : formatted + " " + spec.unit();
    }
}
