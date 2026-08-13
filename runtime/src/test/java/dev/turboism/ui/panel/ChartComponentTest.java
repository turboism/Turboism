package dev.turboism.ui.panel;

import dev.turboism.sdk.ui.PanelView;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChartComponentTest {

    @Test
    void resolvesLocalizedDisplayNameByOrdinal() {
        final ChartDataRegistry.ChartData data = new ChartDataRegistry.ChartData(List.of(
            new ChartDataRegistry.ChartSeriesData("CPU %", List.of(1.0)),
            new ChartDataRegistry.ChartSeriesData("FPS", List.of(2.0, 3.0))
        ));
        // published series keep stable English names; the declared display
        // name is localized and therefore differs
        final PanelView.SeriesSpec spec = PanelView.series("视口渲染 FPS", 120, "fps", "0.0");
        assertEquals(List.of(2.0, 3.0),
            ChartComponent.seriesValues(data, spec, 1).orElseThrow(),
            "a localized display name must bind the same-ordinal published series");
    }

    @Test
    void prefersExactNameMatchOverOrdinal() {
        final ChartDataRegistry.ChartData data = new ChartDataRegistry.ChartData(List.of(
            new ChartDataRegistry.ChartSeriesData("CPU %", List.of(1.0)),
            new ChartDataRegistry.ChartSeriesData("FPS", List.of(2.0))
        ));
        final PanelView.SeriesSpec spec = PanelView.series("FPS", 120, "fps", "0.0");
        assertEquals(List.of(2.0),
            ChartComponent.seriesValues(data, spec, 0).orElseThrow(),
            "an exact display-name match must win even at a different ordinal");
    }

    @Test
    void returnsEmptyWhenNeitherNameNorOrdinalMatches() {
        final ChartDataRegistry.ChartData data = new ChartDataRegistry.ChartData(List.of(
            new ChartDataRegistry.ChartSeriesData("CPU %", List.of(1.0))
        ));
        final PanelView.SeriesSpec spec = PanelView.series("Unknown", 120, "", "0.0");
        final Optional<List<Double>> values = ChartComponent.seriesValues(data, spec, 3);
        assertTrue(values.isEmpty(), "out-of-range ordinal with no name match must stay empty");
    }

    @Test
    void usesTheCompactRowStyleAndShowsItsTitleByDefault() {
        final ChartComponent component = new ChartComponent(PanelView.chart(
            "cpu", "CPU", PanelView.series("CPU %", 120, "%", "0.0")));
        assertEquals(new Dimension(340, 74), component.getPreferredSize(),
            "embedded charts must use the compact single-row height");
        assertTrue(component.showsTitle(), "bare charts keep their own title line");
    }

    @Test
    void canSuppressItsTitleForASingleChartSection() {
        final ChartComponent component = new ChartComponent(PanelView.chart(
            "fps", "Viewport Render FPS", PanelView.series("FPS", 120, "fps", "0.0")),
            false);
        assertEquals(new Dimension(340, 74), component.getPreferredSize());
        assertTrue(!component.showsTitle(), "single-chart sections must defer the title to the border");
    }

    @Test
    void compactRowPlotStartsAtTheContentEdgeWithoutALeftGutter() {
        // Regression: the compact row style draws no Y-axis labels, so the
        // plot must start flush with the left content edge instead of a wide
        // axis gutter; the baseline's leftmost pixel proves where the plot
        // actually begins.
        final String chartId = "left-inset-regression";
        ChartDataRegistry.publish(chartId, new ChartDataRegistry.ChartData(List.of(
            new ChartDataRegistry.ChartSeriesData("CPU %", List.of(1.0, 2.0))
        )));
        try {
            final ChartComponent component = new ChartComponent(PanelView.chart(
                chartId, "CPU", PanelView.series("CPU %", 120, "%", "0.0")), false);
            component.setSize(340, 74);
            final java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(340, 74, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            final java.awt.Graphics2D graphics = image.createGraphics();
            try {
                component.paint(graphics);
            } finally {
                graphics.dispose();
            }
            final int baselineRow = 74 - 8; // plotBottom = height - BOTTOM_MARGIN
            int leftmost = 340;
            for (int x = 0; x < 340; x++) {
                if ((image.getRGB(x, baselineRow) & 0xFF000000) != 0) {
                    leftmost = x;
                    break;
                }
            }
            assertTrue(leftmost <= 16,
                "plot must start at the content edge (found baseline at x=" + leftmost + ")");
        } finally {
            ChartDataRegistry.unpublish(chartId);
        }
    }

    @Test
    void currentValueSitsInItsOwnTopLeftBandAndMaxReachingPlotStaysBelow() {
        // Publish a series whose last value hits the axis maximum so the
        // polyline endpoint touches plotTop; the current value must still sit
        // in its own text band at the very top-left of the component,
        // separated from the plot by an empty row gap. A draw-order trick
        // would not produce that gap.
        final String chartId = "value-band-regression";
        ChartDataRegistry.publish(chartId, new ChartDataRegistry.ChartData(List.of(
            new ChartDataRegistry.ChartSeriesData("CPU %", List.of(0.0, 1.0)))));
        try {
            final ChartComponent component = new ChartComponent(PanelView.chart(
                chartId, "CPU", PanelView.series("CPU %", 120, "%", "0.0")), false);
            final java.awt.image.BufferedImage image = paintToImage(component);
            final List<int[]> blocks = nonEmptyRowBlocks(image);
            assertEquals(2, blocks.size(),
                "expected exactly two painted blocks: top-left value text and plot (blocks="
                    + describe(blocks) + ")");
            final int[] text = blocks.get(0); // {minY, maxY, minX, maxX}
            final int[] plot = blocks.get(1);
            final int fontHeight = component.getFontMetrics(FONT).getHeight();
            assertTrue(text[1] - text[0] + 1 <= fontHeight + 2,
                "the top block must be a single text line, not a plot");
            assertTrue(text[2] <= 40,
                "the value text must be left-aligned near the plot edge (minX=" + text[2]
                    + "; a centered value would start near x=150)");
            assertTrue(text[0] <= 6,
                "the value band must start near the component top, not 18px down (minY=" + text[0] + ")");
            assertTrue(plot[0] - text[1] >= 2,
                "at least one empty row must separate the value text from the max-reaching plot");
        } finally {
            ChartDataRegistry.unpublish(chartId);
        }
    }

    @Test
    void ownTitleAndTopLeftValueBandDoNotOverlap() {
        // Generic title mode: the drawn title line, the value band and the
        // plot must each stay in their own row region without overlapping.
        final String chartId = "title-band-regression";
        ChartDataRegistry.publish(chartId, new ChartDataRegistry.ChartData(List.of(
            new ChartDataRegistry.ChartSeriesData("CPU %", List.of(0.0, 1.0)))));
        try {
            final ChartComponent component = new ChartComponent(PanelView.chart(
                chartId, "CPU", PanelView.series("CPU %", 120, "%", "0.0")), true);
            final java.awt.image.BufferedImage image = paintToImage(component);
            final List<int[]> blocks = nonEmptyRowBlocks(image);
            assertEquals(3, blocks.size(),
                "expected three painted blocks: title, value text and plot (blocks="
                    + describe(blocks) + ")");
            final int fontHeight = component.getFontMetrics(FONT).getHeight();
            assertTrue(blocks.get(0)[1] - blocks.get(0)[0] + 1 <= fontHeight + 2,
                "the title must be a single text line");
            assertTrue(blocks.get(1)[1] - blocks.get(1)[0] + 1 <= fontHeight + 2,
                "the value band must be a single text line");
            assertTrue(blocks.get(1)[0] - blocks.get(0)[1] >= 1,
                "the value band must not overlap the drawn title");
            assertTrue(blocks.get(2)[0] - blocks.get(1)[1] >= 2,
                "the max-reaching plot must stay below the value band");
        } finally {
            ChartDataRegistry.unpublish(chartId);
        }
    }

    @Test
    void singleSampleShowsOnlyTheFormattedCurrentValue() {
        // One sample: the real formatted value must be painted once in the
        // band with no polyline; the placeholder branch is gone, so no "-"
        // can overlap it at the same coordinates.
        final String chartId = "single-sample-regression";
        ChartDataRegistry.publish(chartId, new ChartDataRegistry.ChartData(List.of(
            new ChartDataRegistry.ChartSeriesData("CPU %", List.of(2.5)))));
        try {
            final ChartComponent component = new ChartComponent(PanelView.chart(
                chartId, "CPU", PanelView.series("CPU %", 120, "%", "0.0")), false);
            final java.awt.image.BufferedImage image = paintToImage(component);
            final List<int[]> blocks = nonEmptyRowBlocks(image);
            assertEquals(2, blocks.size(),
                "a single sample must paint the value text plus the one-row baseline, "
                    + "no polyline and no duplicate placeholder (blocks=" + describe(blocks) + ")");
            final int[] text = blocks.get(0);
            final int[] baseline = blocks.get(1);
            final int fontHeight = component.getFontMetrics(FONT).getHeight();
            assertTrue(text[1] - text[0] + 1 <= fontHeight + 2,
                "the value block must be one text line");
            assertTrue(text[2] <= 40, "the value must be left-aligned (minX=" + text[2] + ")");
            assertTrue(text[3] - text[2] + 1 >= 30,
                "the block must be the formatted value text, not a short placeholder (width="
                    + (text[3] - text[2] + 1) + ")");
            assertEquals(66, baseline[0],
                "the only other block must be the single baseline row at the bottom");
            assertEquals(66, baseline[1],
                "no polyline may be drawn for a single sample");
        } finally {
            ChartDataRegistry.unpublish(chartId);
        }
    }

    @Test
    void zeroSamplesShowOnlyThePlaceholder() {
        // No sampled values: exactly one placeholder text in the band, and
        // nothing else (no value, no polyline, no overlap).
        final String chartId = "zero-sample-regression";
        ChartDataRegistry.publish(chartId, new ChartDataRegistry.ChartData(List.of(
            new ChartDataRegistry.ChartSeriesData("CPU %", List.of()))));
        try {
            final ChartComponent component = new ChartComponent(PanelView.chart(
                chartId, "CPU", PanelView.series("CPU %", 120, "%", "0.0")), false);
            final java.awt.image.BufferedImage image = paintToImage(component);
            final List<int[]> blocks = nonEmptyRowBlocks(image);
            assertEquals(1, blocks.size(),
                "zero samples must paint exactly one placeholder block, "
                    + "nothing else (blocks=" + describe(blocks) + ")");
            final int[] text = blocks.get(0);
            final int fontHeight = component.getFontMetrics(FONT).getHeight();
            assertTrue(text[1] - text[0] + 1 <= fontHeight + 2,
                "the placeholder must be a single text line");
            assertTrue(text[0] <= 6, "the placeholder must sit in the top band (minY=" + text[0] + ")");
            assertTrue(text[2] <= 40, "the placeholder must be left-aligned (minX=" + text[2] + ")");
        } finally {
            ChartDataRegistry.unpublish(chartId);
        }
    }

    private static String describe(final List<int[]> blocks) {
        final StringBuilder builder = new StringBuilder();
        for (int[] block : blocks) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append("y").append(block[0]).append("-").append(block[1])
                .append(" x").append(block[2]).append("-").append(block[3]);
        }
        return builder.toString();
    }

    private static final java.awt.Font FONT =
        new java.awt.Font(java.awt.Font.DIALOG, java.awt.Font.PLAIN, 11);

    private static java.awt.image.BufferedImage paintToImage(final ChartComponent component) {
        component.setSize(340, 74);
        component.setFont(FONT);
        final java.awt.image.BufferedImage image =
            new java.awt.image.BufferedImage(340, 74, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        final java.awt.Graphics2D graphics = image.createGraphics();
        try {
            graphics.setFont(FONT);
            component.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** Contiguous non-empty row blocks, each {minY, maxY, minX, maxX}. */
    private static List<int[]> nonEmptyRowBlocks(final java.awt.image.BufferedImage image) {
        final List<int[]> blocks = new java.util.ArrayList<>();
        boolean inBlock = false;
        int start = 0;
        int minX = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            int rowMinX = image.getWidth();
            boolean nonEmpty = false;
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFF000000) != 0) {
                    nonEmpty = true;
                    rowMinX = Math.min(rowMinX, x);
                }
            }
            if (nonEmpty && !inBlock) {
                inBlock = true;
                start = y;
                minX = rowMinX;
            } else if (nonEmpty && inBlock) {
                minX = Math.min(minX, rowMinX);
            } else if (!nonEmpty && inBlock) {
                blocks.add(new int[] {start, y - 1, minX, blockMaxX(image, start, y - 1)});
                inBlock = false;
            }
        }
        if (inBlock) {
            blocks.add(new int[] {start, image.getHeight() - 1, minX,
                blockMaxX(image, start, image.getHeight() - 1)});
        }
        return blocks;
    }

    private static int blockMaxX(final java.awt.image.BufferedImage image, final int minY, final int maxY) {
        int maxX = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFF000000) != 0) {
                    maxX = Math.max(maxX, x);
                }
            }
        }
        return maxX;
    }

}
