package dev.turboism.plugin.perfstats;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.performance.PerformanceSnapshot;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.menu.MenuRegistry;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerfStatsPluginLifecycleTest {

    @Test
    void enableBeforeInitFailsClosed() {
        assertThrows(IllegalStateException.class, new PerfStatsPlugin()::enable);
    }

    @Test
    void enableStartsSamplingAndDisableStopsIt() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context());
        fixture.plugin.enable();
        assertEquals(1, fixture.samples.get(), "enable must start one sampling registration");
        assertNotNull(fixture.samplingRegistration.get());
        assertTrue(fixture.panels.get() > 0, "embedded panel must be contributed at init");

        fixture.sample(new PerformanceSnapshot(1L, 10.0, 1024L, 512L, 60.0, 120L, 0L, 0L, 3L, 250L));
        assertEquals(List.of(10.0), fixture.store().values(ChartStore.KEY_CPU));
        fixture.sample(new PerformanceSnapshot(2L, 10.0, 1024L, 512L, 60.0, 120L, 0L, 0L, 4L, 300L));
        assertEquals(List.of(0.0, 50.0), fixture.store().values(ChartStore.KEY_GC),
            "GC Pause series must carry per-window deltas of the cumulative pause counter");

        fixture.plugin.disable();
        assertEquals(1, fixture.closed.get(), "disable must close the sampling registration");
        assertEquals(0, fixture.samples.get());
    }

    @Test
    void shutdownAfterDisableDoesNotDoubleClose() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context());
        fixture.plugin.enable();
        fixture.plugin.disable();
        fixture.plugin.shutdown();
        assertEquals(1, fixture.closed.get());
    }

    @Test
    void shutdownClosesActiveSampling() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context());
        fixture.plugin.enable();
        fixture.plugin.shutdown();
        assertEquals(1, fixture.closed.get());
    }

    @Test
    void chartStoreKeepsRollingWindow() {
        final ChartStore store = new ChartStore(3);
        for (int i = 0; i < 5; i++) {
            store.append(new PerformanceSnapshot(i, i, 1L, 1L, i, i, 0L, 0L, i, i));
        }
        assertEquals(List.of(2.0, 3.0, 4.0), store.values(ChartStore.KEY_CPU));
        assertEquals(List.of(2.0, 3.0, 4.0), store.values(ChartStore.KEY_FPS));
        assertEquals(3, store.values(ChartStore.KEY_HEAP).size());
        assertEquals(3, store.values(ChartStore.KEY_FRAMES).size());
        assertEquals(List.of(1.0, 1.0, 1.0), store.values(ChartStore.KEY_GC),
            "GC Pause must roll with the same window as the other series");
    }

    @Test
    void embeddedPanelDeclaresGcPauseChart() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context());
        final EmbeddedPanelContribution contribution = fixture.panelContribution.get();
        assertNotNull(contribution, "init must contribute the embedded panel");
        assertTrue(containsChartId(contribution.content(), "gc"),
            "embedded panel must declare the GC Pause chart with id 'gc'");
    }

    @Test
    void embeddedPanelConsumesCustomLocalizationTexts() throws Exception {
        final Fixture fixture = new Fixture(prefixingLocalization());
        fixture.plugin.init(fixture.context());
        final EmbeddedPanelContribution contribution = fixture.panelContribution.get();
        assertEquals("TXT[panel.title]", contribution.title(),
            "panel.title must be resolved through the plugin localization");
        final PanelView.Chart fps = findChart(contribution.content(), "fps");
        assertNotNull(fps, "embedded panel must declare the fps chart");
        assertEquals("TXT[chart.fps.title]", fps.title(),
            "chart.fps.title must be resolved through the plugin localization");
        assertEquals("TXT[series.fps]", fps.series().get(0).name(),
            "series.fps must be resolved through the plugin localization");
        assertEquals("TXT[series.cpu]", fixture.plugin.rowLabels().get(ChartStore.KEY_CPU),
            "standalone window row labels must resolve the same series keys");
        assertEquals("TXT[series.fps]", fixture.plugin.rowLabels().get(ChartStore.KEY_FPS),
            "standalone window FPS row label must resolve series.fps");
    }

    @Test
    void embeddedPanelFallsBackToEnglishWhenLocalizationUnavailable() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context());
        final EmbeddedPanelContribution contribution = fixture.panelContribution.get();
        assertEquals("Performance", contribution.title(),
            "panel title must fall back to English without a localization service");
        final PanelView.Chart fps = findChart(contribution.content(), "fps");
        assertNotNull(fps);
        assertEquals("Viewport Render FPS", fps.title());
        assertEquals("Viewport Render FPS", fps.series().get(0).name());
    }

    @Test
    void fallsBackToEnglishWhenLocalizationServiceThrows() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.localizationThrows = true;
        fixture.plugin.init(fixture.context());
        final EmbeddedPanelContribution contribution = fixture.panelContribution.get();
        assertEquals("Performance", contribution.title(),
            "an unavailable localization service must fall back instead of throwing");
        assertNotNull(findChart(contribution.content(), "fps"));
    }

    @Test
    void enableRegistersWindowActionAndMenuWithoutAutoOpeningTheWindow() throws Exception {
        final Fixture fixture = new Fixture(prefixingLocalization());
        fixture.plugin.init(fixture.context());
        fixture.plugin.enable();

        assertEquals(1, fixture.actions.get(), "enable must register exactly one action");
        assertEquals(1, fixture.menus.get(), "enable must contribute exactly one menu item");
        assertNotNull(fixture.action.get(), "the window action must be registered");
        assertNotNull(fixture.menuContribution.get(), "the menu contribution must be registered");

        SwingUtilities.invokeAndWait(() -> { });
        assertNull(fixture.window(), "enable must not open or schedule the standalone window");
    }

    @Test
    void windowMenuContributionUsesReservedTurboismRootAndLocalizedLabel() throws Exception {
        final Fixture fixture = new Fixture(prefixingLocalization());
        fixture.plugin.init(fixture.context());
        fixture.plugin.enable();

        final MenuRegistry.MenuContribution menu = fixture.menuContribution.get();
        assertNotNull(menu);
        assertEquals("Turboism/TXT[menu.performance-monitor]", menu.menuPath(),
            "menu path must be the reserved Turboism root plus the localized label");
        assertEquals("perf-stats.window.show", menu.actionId(),
            "the menu item must invoke the registered window action");
        assertEquals("TXT[menu.performance-monitor]", fixture.action.get().label(),
            "the action label must resolve the same localized key");
    }

    @Test
    void windowActionFailsGracefullyInHeadlessEnvironment() throws Exception {
        Assumptions.assumeTrue(GraphicsEnvironment.isHeadless(), "requires a headless JVM");
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context());
        fixture.plugin.enable();

        final ActionRegistry.Action action = fixture.action.get();
        assertNotNull(action);
        assertDoesNotThrow(() -> action.handler().accept(new ActionRegistry.ActionContext() { }));
        SwingUtilities.invokeAndWait(() -> { });
        assertNull(fixture.window(), "headless invocation must fail gracefully without a window");
    }

    @Test
    void scopeCloseClosesSamplingAndContributionRegistrations() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context());
        fixture.plugin.enable();
        fixture.scope.close();

        assertEquals(1, fixture.closed.get(), "sampling registration must close through the scope");
        assertEquals(1, fixture.actionCloses.get(), "action registration must close through the scope");
        assertEquals(1, fixture.menuCloses.get(), "menu contribution must close through the scope");
        assertEquals(0, fixture.samples.get());
    }

    private static PluginLocalization prefixingLocalization() {
        return new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }

            @Override
            public String text(final String key) {
                return "TXT[" + key + "]";
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return text(key);
            }

            @Override
            public boolean contains(final String key) {
                return true;
            }
        };
    }

    private static PanelView.Chart findChart(final PanelView view, final String id) {
        if (view instanceof PanelView.Chart chart) {
            return id.equals(chart.id()) ? chart : null;
        }
        if (view instanceof PanelView.Column column) {
            for (PanelView child : column.children()) {
                final PanelView.Chart found = findChart(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        if (view instanceof PanelView.Row row) {
            for (PanelView child : row.children()) {
                final PanelView.Chart found = findChart(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean containsChartId(final PanelView view, final String id) {
        if (view instanceof PanelView.Chart chart) {
            return id.equals(chart.id());
        }
        if (view instanceof PanelView.Column column) {
            for (PanelView child : column.children()) {
                if (containsChartId(child, id)) {
                    return true;
                }
            }
        }
        if (view instanceof PanelView.Row row) {
            for (PanelView child : row.children()) {
                if (containsChartId(child, id)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class Fixture {
        final PerfStatsPlugin plugin = new PerfStatsPlugin();
        final PluginLocalization localization;
        boolean localizationThrows;
        final AtomicInteger samples = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final AtomicInteger panels = new AtomicInteger();
        final AtomicReference<EmbeddedPanelContribution> panelContribution = new AtomicReference<>();
        final AtomicReference<Registration> samplingRegistration = new AtomicReference<>();
        final AtomicReference<Consumer<PerformanceSnapshot>> consumer = new AtomicReference<>();
        final AtomicInteger actions = new AtomicInteger();
        final AtomicInteger actionCloses = new AtomicInteger();
        final AtomicReference<ActionRegistry.Action> action = new AtomicReference<>();
        final AtomicInteger menus = new AtomicInteger();
        final AtomicInteger menuCloses = new AtomicInteger();
        final AtomicReference<MenuRegistry.MenuContribution> menuContribution = new AtomicReference<>();
        final DisposableScope scope = new DisposableScope();

        Fixture() {
            this(null);
        }

        Fixture(final PluginLocalization localization) {
            this.localization = localization;
        }

        void sample(final PerformanceSnapshot snapshot) {
            final Consumer<PerformanceSnapshot> active = consumer.get();
            if (active != null) {
                active.accept(snapshot);
            }
        }

        ChartStore store() throws Exception {
            final java.lang.reflect.Field field = PerfStatsPlugin.class.getDeclaredField("store");
            field.setAccessible(true);
            return (ChartStore) field.get(plugin);
        }

        PerfStatsWindow window() throws Exception {
            final java.lang.reflect.Field field = PerfStatsPlugin.class.getDeclaredField("window");
            field.setAccessible(true);
            final AtomicReference<?> reference = (AtomicReference<?>) field.get(plugin);
            return (PerfStatsWindow) reference.get();
        }

        PluginContext context() {
            final PerformanceProbeService stats = new PerformanceProbeService() {
                @Override
                public PerformanceSnapshot snapshot() {
                    return PerformanceSnapshot.unavailable(0L);
                }

                @Override
                public Registration sample(
                    final Duration interval,
                    final Consumer<PerformanceSnapshot> requested
                ) {
                    samples.incrementAndGet();
                    consumer.set(requested);
                    final Registration registration = () -> {
                        closed.incrementAndGet();
                        samples.decrementAndGet();
                        consumer.set(null);
                    };
                    samplingRegistration.set(registration);
                    return registration;
                }
            };
            final UiHostCapabilityService uiHost = (UiHostCapabilityService) Proxy.newProxyInstance(
                UiHostCapabilityService.class.getClassLoader(),
                new Class<?>[] { UiHostCapabilityService.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("contributeEmbeddedPanel")) {
                        panels.incrementAndGet();
                        panelContribution.set((EmbeddedPanelContribution) args[0]);
                        return (Registration) () -> panels.decrementAndGet();
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
            );
            return (PluginContext) Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[] { PluginContext.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("performanceStats")) {
                        return stats;
                    }
                    if (method.getName().equals("uiHost")) {
                        return uiHost;
                    }
                    if (method.getName().equals("disposableScope")) {
                        return scope;
                    }

                    if (method.getName().equals("actions")) {
                        return (ActionRegistry) (id, registered) -> {
                            actions.incrementAndGet();
                            action.set(registered);
                            return () -> actionCloses.incrementAndGet();
                        };
                    }
                    if (method.getName().equals("menus")) {
                        return (MenuRegistry) contribution -> {
                            menus.incrementAndGet();
                            menuContribution.set(contribution);
                            return () -> menuCloses.incrementAndGet();
                        };
                    }
                    if (method.getName().equals("logger")) {
                        return (dev.turboism.sdk.plugin.PluginLogger) Proxy.newProxyInstance(
                            dev.turboism.sdk.plugin.PluginLogger.class.getClassLoader(),
                            new Class<?>[] { dev.turboism.sdk.plugin.PluginLogger.class },
                            (ignored, ignoredMethod, ignoredArgs) -> null
                        );
                    }
                    if (method.getName().equals("localization")) {
                        if (localizationThrows) {
                            throw new UnsupportedOperationException("localization service is not available");
                        }
                        return localization;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
            );
        }
    }
}
