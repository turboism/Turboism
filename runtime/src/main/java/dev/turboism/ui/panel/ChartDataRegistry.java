package dev.turboism.ui.panel;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime chart-value registry. The performance sampling service publishes the
 * rolling series values for its canonical chart ids here; embedded-panel
 * {@code PanelView.Chart} rendering resolves live values by chart id. Series
 * values are immutable snapshots, so publishing is safe from any thread.
 */
public final class ChartDataRegistry {

    private static final ConcurrentHashMap<String, ChartData> DATA = new ConcurrentHashMap<>();

    private ChartDataRegistry() { }

    /**
     * Replaces the values currently published for a chart id. Callable from any thread; the
     * snapshot is immutable, so a renderer reading concurrently sees either the old or the new
     * series, never a partial one.
     *
     * @param chartId canonical chart id a {@code PanelView.Chart} node refers to
     * @param data the new immutable snapshot for that id
     * @throws NullPointerException if either argument is {@code null}
     */
    public static void publish(final String chartId, final ChartData data) {
        DATA.put(Objects.requireNonNull(chartId, "chartId"), Objects.requireNonNull(data, "data"));
    }

    /**
     * Drops any snapshot published for a chart id, so subsequent lookups report the chart as
     * having no live data. No-op when nothing was published.
     *
     * @param chartId the chart id to clear
     */
    public static void unpublish(final String chartId) {
        DATA.remove(chartId);
    }

    /**
     * @param chartId the chart id a panel node refers to
     * @return the snapshot most recently published for that id, or empty when the producing
     *     service is not running or has unpublished it
     */
    public static Optional<ChartData> find(final String chartId) {
        return Optional.ofNullable(DATA.get(chartId));
    }

    /** One series of rolling values in display units. */
    public record ChartSeriesData(String name, List<Double> values) {
        public ChartSeriesData {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    /** Immutable snapshot of all series published for one chart id. */
    public record ChartData(List<ChartSeriesData> series) {
        public ChartData {
            series = List.copyOf(Objects.requireNonNull(series, "series"));
        }
    }
}
