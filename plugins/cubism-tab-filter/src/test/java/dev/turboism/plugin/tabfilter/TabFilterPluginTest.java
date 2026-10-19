package dev.turboism.plugin.tabfilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.test.plugin.FakePluginContext;
import java.util.List;
import org.junit.jupiter.api.Test;

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
                        contribution("tab-filter.log", "LOG", "tab-filter.placeholder.log")),
                context.paletteFilterContributions());
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

    @Test
    void repeatedEnableIsIdempotentAndDoesNotDuplicateRegistrations() {
        RecordingPluginContext context = new RecordingPluginContext();
        TabFilterPlugin plugin = new TabFilterPlugin();

        plugin.init(context);
        plugin.enable();
        plugin.enable();

        assertEquals(4, context.paletteFilterContributions().size());
        assertEquals(0, context.closedRegistrations);

        plugin.disable();
        assertEquals(4, context.closedRegistrations);
    }

    @Test
    void partialEnableFailureRollsBackEarlierRegistrations() {
        RecordingPluginContext context = new RecordingPluginContext();
        context.paletteFilter.failOnThirdContribution = true;
        TabFilterPlugin plugin = new TabFilterPlugin();

        plugin.init(context);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, plugin::enable);

        assertTrue(context.paletteFilterContributions().isEmpty(), "earlier contributions must be rolled back");
        assertEquals(
                2, context.closedRegistrations, "the 2 successful contributions before the failure must be closed");
    }

    @Test
    void reEnableAfterDisableReRegistersCleanly() {
        RecordingPluginContext context = new RecordingPluginContext();
        TabFilterPlugin plugin = new TabFilterPlugin();

        plugin.init(context);
        plugin.enable();
        plugin.disable();
        assertEquals(0, context.paletteFilterContributions().size());

        plugin.enable();
        assertEquals(4, context.paletteFilterContributions().size());

        plugin.shutdown();
        assertEquals(0, context.paletteFilterContributions().size());
        assertEquals(8, context.closedRegistrations);
    }

    private static PaletteFilterRegistry.PaletteFilterContribution contribution(
            String id, String paletteId, String placeholderKey) {
        return new PaletteFilterRegistry.PaletteFilterContribution(id, paletteId, placeholderKey, 10);
    }

    /** Minimal plugin context recording palette filter contributions. */
    private static final class RecordingPluginContext extends FakePluginContext {

        private final RecordingPaletteFilterRegistry paletteFilter = new RecordingPaletteFilterRegistry();
        private int closedRegistrations;

        List<PaletteFilterRegistry.PaletteFilterContribution> paletteFilterContributions() {
            return paletteFilter.contributions;
        }

        public PaletteFilterRegistry paletteFilter() {
            return paletteFilter;
        }

        public AppearanceService appearance() {
            return AppearanceService.unavailable();
        }

        private final class RecordingPaletteFilterRegistry implements PaletteFilterRegistry {
            private final List<PaletteFilterContribution> contributions = new java.util.ArrayList<>();
            boolean failOnThirdContribution;

            @Override
            public Registration contribute(final PaletteFilterContribution contribution) {
                if (failOnThirdContribution && contributions.size() == 2) {
                    throw new IllegalStateException("fixture failure on 3rd contribution");
                }
                contributions.add(contribution);
                return () -> {
                    contributions.remove(contribution);
                    closedRegistrations++;
                };
            }
        }

        @Override
        public dev.turboism.sdk.plugin.PluginServiceDirectory services() {
            return dev.turboism.sdk.plugin.PluginServices.builder()
                    .supply(dev.turboism.sdk.ui.filter.PaletteFilterRegistry.class, this::paletteFilter)
                    .fallback(dev.turboism.sdk.plugin.PluginServices.of(this))
                    .build();
        }
    }
}
