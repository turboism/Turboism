package dev.turboism.performance;

import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.ui.PanelView;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelViewChartTest {

    @Test
    void chartCarriesDeclarativeSeriesConfiguration() {
        final PanelView.Chart chart = PanelView.chart(
            "cpu",
            "CPU",
            PanelView.series("CPU %", 120, "%", "0.0")
        );
        assertEquals("cpu", chart.id());
        assertEquals("CPU", chart.title());
        assertEquals(1, chart.series().size());
        final PanelView.SeriesSpec spec = chart.series().get(0);
        assertEquals("CPU %", spec.name());
        assertEquals(120, spec.maxPoints());
        assertEquals("%", spec.unit());
        assertEquals("0.0", spec.format());
    }

    @Test
    void chartValidationRejectsBlankOrEmptyConfigurations() {
        assertThrows(IllegalArgumentException.class,
            () -> PanelView.chart("", "CPU", PanelView.series("CPU %", 120, "%", "0.0")));
        assertThrows(IllegalArgumentException.class,
            () -> PanelView.chart("cpu", " ", PanelView.series("CPU %", 120, "%", "0.0")));
        assertThrows(IllegalArgumentException.class, () -> PanelView.chart("cpu", "CPU"));
        assertThrows(IllegalArgumentException.class,
            () -> PanelView.chart(
                "cpu", "CPU",
                PanelView.series("CPU %", 120, "%", "0.0"),
                PanelView.series("CPU %", 120, "%", "0.0")
            ));
        assertThrows(IllegalArgumentException.class,
            () -> PanelView.series("CPU %", 1, "%", "0.0"));
        assertThrows(IllegalArgumentException.class,
            () -> PanelView.series("", 120, "%", "0.0"));
    }

    @Test
    void chartIsRenderableThroughPanelViewHierarchy() {
        final PanelView panel = PanelView.column(
            PanelView.chart("cpu", "CPU", PanelView.series("CPU %", 120, "%", "0.0")),
            PanelView.chart("fps", "FPS", PanelView.series("FPS", 120, "fps", "0.0"))
        );
        assertInstanceOf(PanelView.Column.class, panel);
        final List<PanelView> children = ((PanelView.Column) panel).children();
        assertEquals(2, children.size());
        assertTrue(children.get(0) instanceof PanelView.Chart);
    }

    @Test
    void permissionIdForPerformanceStatsIsPublished() {
        assertEquals("turboism.performance.stats.read", PermissionIds.TURBOISM_PERFORMANCE_STATS_READ);
    }

    @Test
    void pluginContextDefaultsToUnavailableService() {
        // An anonymous PluginContext that overrides nothing about performance
        // stats must fail closed through the SDK default.
        final PluginContext minimal = new PluginContext() {
            @Override public dev.turboism.sdk.plugin.PluginDescriptor descriptor() {
                return null;
            }
            @Override public dev.turboism.sdk.plugin.PluginLogger logger() {
                return null;
            }
            @Override public dev.turboism.sdk.plugin.PluginPaths paths() {
                return null;
            }
            @Override public dev.turboism.sdk.cubism.CubismFacade cubism() {
                return null;
            }
            @Override public List<dev.turboism.sdk.permission.PluginPermission> permissions() {
                return List.of();
            }
            @Override public dev.turboism.sdk.event.EventBus eventBus() {
                return null;
            }
            @Override public dev.turboism.sdk.action.ActionRegistry actions() {
                return null;
            }
            @Override public dev.turboism.sdk.menu.MenuRegistry menus() {
                return null;
            }
            @Override public dev.turboism.sdk.ui.UiScheduler uiScheduler() {
                return null;
            }
            @Override public dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
                return null;
            }
            @Override public dev.turboism.sdk.plugin.DisposableScope disposableScope() {
                return null;
            }
        };
        final PerformanceProbeService stats = minimal.performanceStats();
        assertThrows(UnsupportedOperationException.class, stats::snapshot);
    }
}
