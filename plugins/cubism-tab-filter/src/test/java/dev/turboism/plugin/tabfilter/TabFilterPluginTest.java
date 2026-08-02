package dev.turboism.plugin.tabfilter;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TabFilterPluginTest {

    @Test
    void enableRegistersFilterBoxesForAllFourPaletteTabs() {
        RecordingPluginContext context = new RecordingPluginContext();
        TabFilterPlugin plugin = new TabFilterPlugin();

        plugin.init(context);
        plugin.enable();

        assertEquals(
            List.of(
                contribution("tab-filter.parameter", "PARAMETER", "tab-filter.placeholder.parameter"),
                contribution("tab-filter.deformer", "DEFORMER", "tab-filter.placeholder.deformer"),
                contribution("tab-filter.scene", "SCENE", "tab-filter.placeholder.scene"),
                contribution("tab-filter.log", "LOG", "tab-filter.placeholder.log")
            ),
            context.paletteFilterContributions()
        );
    }

    @Test
    void disableClosesAllFilterBoxRegistrations() {
        RecordingPluginContext context = new RecordingPluginContext();
        TabFilterPlugin plugin = new TabFilterPlugin();

        plugin.init(context);
        plugin.enable();
        plugin.disable();

        assertTrue(context.paletteFilterContributions().isEmpty());
        assertEquals(4, context.closedRegistrations);
    }

    private static PaletteFilterRegistry.PaletteFilterContribution contribution(
        String id,
        String paletteId,
        String placeholderKey
    ) {
        return new PaletteFilterRegistry.PaletteFilterContribution(id, paletteId, placeholderKey, 10);
    }

    /** Minimal plugin context recording palette filter contributions. */
    private static final class RecordingPluginContext implements PluginContext {

        private final RecordingPaletteFilterRegistry paletteFilter = new RecordingPaletteFilterRegistry();
        private int closedRegistrations;

        List<PaletteFilterRegistry.PaletteFilterContribution> paletteFilterContributions() {
            return paletteFilter.contributions;
        }

        @Override
        public PaletteFilterRegistry paletteFilter() {
            return paletteFilter;
        }

        @Override public PluginDescriptor descriptor() { return null; }
        @Override public PluginLogger logger() { return new PluginLogger() {
            @Override public void debug(String message) { }
            @Override public void info(String message) { }
            @Override public void warn(String message) { }
            @Override public void error(String message) { }
            @Override public void error(String message, Throwable throwable) { }
        }; }
        @Override public PluginPaths paths() { return null; }
        @Override public CubismFacade cubism() { return null; }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { return null; }
        @Override public ActionRegistry actions() { return null; }
        @Override public MenuRegistry menus() { return null; }
        @Override public UiHostCapabilityService uiHost() { return null; }
        @Override public dev.turboism.sdk.ui.UiScheduler uiScheduler() { return null; }
        @Override public DisposableScope disposableScope() { return new DisposableScope(); }
        @Override public DiagnosticReport diagnostics() { return null; }
        @Override public AppearanceService appearance() { return AppearanceService.unavailable(); }

        private final class RecordingPaletteFilterRegistry implements PaletteFilterRegistry {
            private final List<PaletteFilterContribution> contributions = new java.util.ArrayList<>();

            @Override
            public Registration contribute(final PaletteFilterContribution contribution) {
                contributions.add(contribution);
                return () -> {
                    contributions.remove(contribution);
                    closedRegistrations++;
                };
            }
        }
    }
}
