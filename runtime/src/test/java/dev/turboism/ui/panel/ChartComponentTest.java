package dev.turboism.ui.panel;

import dev.turboism.sdk.ui.PanelView;

import org.junit.jupiter.api.Test;

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
}
