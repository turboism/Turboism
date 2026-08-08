package dev.turboism.ui.panel;

import dev.turboism.sdk.ui.PanelView;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Optional;

/**
 * Runtime-owned Swing rendering of a {@link PanelView.Chart}: scrolling line
 * chart (time on X, usage on Y) whose values are injected from
 * {@link ChartDataRegistry} by chart id, refreshed on the EDT by a 1s timer
 * without ever blocking the host UI thread. Series are matched by display
 * name against the published chart data, falling back to the same ordinal
 * when the declared name is localized; missing series render as empty.
 */
final class ChartComponent extends JComponent {

    private static final Color[] SERIES_COLORS = {
        new Color(31, 119, 180),
        new Color(255, 127, 14),
        new Color(44, 160, 44),
        new Color(214, 39, 40),
        new Color(148, 103, 189)
    };
    private static final int LEFT_MARGIN = 46;
    private static final int RIGHT_MARGIN = 12;
    private static final int TOP_MARGIN = 22;
    private static final int BOTTOM_MARGIN = 24;

    private final PanelView.Chart chart;
    private final Timer refresh;

    ChartComponent(final PanelView.Chart chart) {
        this.chart = chart;
        setPreferredSize(new Dimension(340, 190));
        refresh = new Timer(1000, ignored -> repaint());
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
            final int width = getWidth();
            final int height = getHeight();
            final int plotLeft = LEFT_MARGIN;
            final int plotRight = Math.max(plotLeft + 1, width - RIGHT_MARGIN);
            final int plotTop = TOP_MARGIN;
            final int plotBottom = Math.max(plotTop + 1, height - BOTTOM_MARGIN);
            g.setColor(getForeground());
            g.drawString(chart.title(), 6, 14);
            final ChartDataRegistry.ChartData data = ChartDataRegistry.find(chart.id()).orElse(null);
            if (data == null || data.series().isEmpty()) {
                g.drawString("no data", plotLeft + 8, (plotTop + plotBottom) / 2);
                return;
            }
            drawGrid(g, plotLeft, plotTop, plotRight, plotBottom, data);
            int colorIndex = 0;
            for (int index = 0; index < chart.series().size(); index++) {
                final PanelView.SeriesSpec spec = chart.series().get(index);
                final Optional<List<Double>> values = seriesValues(data, spec, index);
                if (values.isEmpty()) continue;
                g.setColor(SERIES_COLORS[colorIndex++ % SERIES_COLORS.length]);
                drawSeries(g, plotLeft, plotTop, plotRight, plotBottom, spec, values.get());
            }
        } finally {
            g.dispose();
        }
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

    private void drawGrid(
        final Graphics2D g,
        final int left,
        final int top,
        final int right,
        final int bottom,
        final ChartDataRegistry.ChartData data
    ) {
        double max = 1.0;
        for (int index = 0; index < chart.series().size(); index++) {
            final PanelView.SeriesSpec spec = chart.series().get(index);
            final Optional<List<Double>> values = seriesValues(data, spec, index);
            if (values.isEmpty()) continue;
            final int window = Math.min(spec.maxPoints(), values.get().size());
            for (int i = values.get().size() - window; i < values.get().size(); i++) {
                max = Math.max(max, values.get().get(i));
            }
        }
        g.setColor(new Color(220, 220, 220));
        g.setStroke(new BasicStroke(1f));
        final FontMetrics metrics = g.getFontMetrics();
        for (int row = 0; row <= 4; row++) {
            final double fraction = row / 4.0;
            final int y = bottom - (int) Math.round(fraction * (bottom - top));
            g.drawLine(left, y, right, y);
            final String label = formatAxisValue(max * fraction);
            g.drawString(label, left - metrics.stringWidth(label) - 4, y + 4);
        }
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
        g.setStroke(new BasicStroke(1.8f));
        int previousX = -1;
        int previousY = -1;
        for (int index = 0; index < window; index++) {
            final double value = values.get(values.size() - window + index);
            final int x = left + (int) Math.round((index / (double) Math.max(1, window - 1)) * (right - left));
            final int y = bottom - (int) Math.round((value / max) * (bottom - top));
            if (previousX >= 0) {
                g.drawLine(previousX, previousY, x, y);
            }
            previousX = x;
            previousY = y;
        }
        final String last = values.isEmpty()
            ? spec.name() + ": -"
            : spec.name() + ": " + formatSeriesValue(spec, values.get(values.size() - 1));
        g.drawString(last, left + 4, top + 2);
    }

    private String formatSeriesValue(final PanelView.SeriesSpec spec, final double value) {
        final String formatted = new DecimalFormat(spec.format()).format(value);
        return spec.unit().isBlank() ? formatted : formatted + " " + spec.unit();
    }

    private static String formatAxisValue(final double value) {
        return new DecimalFormat("0.#").format(value);
    }
}
