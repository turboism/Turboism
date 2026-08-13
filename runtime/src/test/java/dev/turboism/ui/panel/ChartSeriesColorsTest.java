package dev.turboism.ui.panel;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChartSeriesColorsTest {

    @Test
    void firstSeriesUsesTheFixedChartIdPalette() {
        assertEquals(new Color(31, 119, 180), ChartSeriesColors.colorFor("cpu", 0));
        assertEquals(new Color(255, 127, 14), ChartSeriesColors.colorFor("fps", 0));
        assertEquals(new Color(44, 160, 44), ChartSeriesColors.colorFor("heap", 0));
        assertEquals(new Color(214, 39, 40), ChartSeriesColors.colorFor("nonheap", 0));
        assertEquals(new Color(140, 86, 75), ChartSeriesColors.colorFor("gc", 0));
    }

    @Test
    void unknownChartIdFallsBackToTheSafeDefault() {
        assertEquals(new Color(148, 103, 189), ChartSeriesColors.colorFor("unknown-id", 0));
        assertEquals(new Color(148, 103, 189), ChartSeriesColors.colorFor("", 0));
    }

    @Test
    void extraSeriesStillDrawWithTheOrdinalFallbackPalette() {
        // Multi-series charts keep drawing: the second series cycles the
        // ordinal palette regardless of the chart id.
        assertEquals(new Color(255, 127, 14), ChartSeriesColors.colorFor("gc", 1));
        assertEquals(new Color(255, 127, 14), ChartSeriesColors.colorFor("unknown-id", 1));
        assertEquals(new Color(44, 160, 44), ChartSeriesColors.colorFor("cpu", 2));
    }
}
