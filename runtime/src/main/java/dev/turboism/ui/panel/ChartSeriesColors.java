package dev.turboism.ui.panel;

import java.awt.Color;
import java.util.Map;

/**
 * Fixed chart-id palette shared by the embedded runtime chart rendering.
 * The five canonical performance chart ids map to the same colors the
 * standalone {@code PerfStatsWindow} rows use, so both UI forms stay
 * visually aligned by construction. Unknown chart ids fall back to a safe
 * default; additional series beyond the first keep drawing with the
 * ordinal fallback palette.
 */
final class ChartSeriesColors {

    private static final Color CPU = new Color(31, 119, 180);
    private static final Color FPS = new Color(255, 127, 14);
    private static final Color HEAP = new Color(44, 160, 44);
    private static final Color NONHEAP = new Color(214, 39, 40);
    private static final Color GC = new Color(140, 86, 75);
    private static final Color SAFE_DEFAULT = new Color(148, 103, 189);

    private static final Color[] ORDINAL = {
        CPU, FPS, HEAP, NONHEAP, SAFE_DEFAULT, GC
    };

    private static final Map<String, Color> BY_CHART_ID = Map.of(
        "cpu", CPU,
        "fps", FPS,
        "heap", HEAP,
        "nonheap", NONHEAP,
        "gc", GC
    );

    private ChartSeriesColors() {
    }

    /**
     * Color for one series of one chart: the first series uses the fixed
     * chart-id color (unknown ids stay on the safe default), extra series
     * use the ordinal fallback palette so multi-series charts still draw.
     */
    static Color colorFor(final String chartId, final int seriesIndex) {
        if (seriesIndex <= 0) {
            return BY_CHART_ID.getOrDefault(chartId, SAFE_DEFAULT);
        }
        return ORDINAL[seriesIndex % ORDINAL.length];
    }
}
