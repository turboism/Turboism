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
import dev.turboism.sdk.ui.StatusNotification;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void embeddedPanelDeclaresFiveIndependentCollapsibleSections() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context());
        final EmbeddedPanelContribution contribution = fixture.panelContribution.get();
        assertNotNull(contribution);
        final PanelView.Column column = assertInstanceOf(PanelView.Column.class, contribution.content(),
            "the embedded panel must stay a column");
        assertEquals(5, column.children().size(), "one collapsible section per metric");
        final List<String> ids = List.of("cpu", "fps", "heap", "nonheap", "gc");
        for (int index = 0; index < column.children().size(); index++) {
            final PanelView.CollapsibleSection section =
                assertInstanceOf(PanelView.CollapsibleSection.class, column.children().get(index),
                    "each metric must be independently collapsible");
            assertTrue(section.expandedByDefault(), "every metric must start expanded");
            assertEquals(1, section.children().size(), "each section holds exactly one chart");
            final PanelView.Chart chart = assertInstanceOf(PanelView.Chart.class, section.children().get(0));
            assertEquals(ids.get(index), chart.id());
            assertEquals(section.title(), chart.title(),
                "the section border title and the chart title share the same source");
        }
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
        assertEquals("TXT[chart.cpu.title]", fixture.plugin.chartTitles().get(ChartStore.KEY_CPU),
            "standalone window CPU row title must match the embedded chart.cpu.title");
        assertEquals("TXT[chart.fps.title]", fixture.plugin.chartTitles().get(ChartStore.KEY_FPS),
            "standalone window FPS row title must match the embedded chart.fps.title");
        assertEquals("TXT[chart.gc.title]", fixture.plugin.chartTitles().get(ChartStore.KEY_GC),
            "standalone window GC row title must match the embedded chart.gc.title");
    }

    @Test
    void windowRowTitlesMatchTheEmbeddedSectionTitles() throws Exception {
        final Fixture fixture = new Fixture(prefixingLocalization());
        fixture.plugin.init(fixture.context());
        final EmbeddedPanelContribution contribution = fixture.panelContribution.get();
        final PanelView.Column column = assertInstanceOf(PanelView.Column.class, contribution.content());
        for (PanelView child : column.children()) {
            final PanelView.CollapsibleSection section = assertInstanceOf(PanelView.CollapsibleSection.class, child);
            final PanelView.Chart chart = assertInstanceOf(PanelView.Chart.class, section.children().get(0));
            assertEquals(chart.title(), fixture.plugin.chartTitles().get(chart.id()),
                "the standalone window row title must use the same chart.*.title copy as the embedded section");
        }
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

    @Test
    void enablePublishesInitialCpuPlaceholderAndSnapshotsUpdateItWithOneDecimal() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context());
        fixture.plugin.enable();

        assertEquals(1, fixture.statusNotifications.get(),
            "enable must publish the initial CPU placeholder");
        assertEquals("CPU --%", fixture.lastStatus.get().message());
        assertEquals(
            StatusNotification.Presentation.COMPACT_METRIC,
            fixture.lastStatus.get().presentation(),
            "CPU status must use the compact-metric presentation"
        );

        fixture.sample(new PerformanceSnapshot(1L, 12.34, 1024L, 512L, 60.0, 120L, 0L, 0L, 3L, 250L));
        assertEquals(2, fixture.statusNotifications.get(), "each snapshot must refresh the CPU status");
        assertEquals("CPU 12.3%", fixture.lastStatus.get().message(),
            "CPU must be formatted with one decimal using Locale.ROOT");
        assertEquals("perf-stats.cpu-status", fixture.lastStatus.get().id(),
            "CPU status must use one stable local ID");
        assertEquals(1, fixture.statusCloses.get(),
            "the first snapshot must replace (close) the initial placeholder registration");

        fixture.sample(new PerformanceSnapshot(2L, 5.0, 1024L, 512L, 60.0, 120L, 0L, 0L, 3L, 250L));
        assertEquals(2, fixture.statusCloses.get(),
            "each replacement must close the previous registration (scope stays bounded)");
        assertEquals("CPU 5.0%", fixture.lastStatus.get().message());
        assertEquals(1, fixture.statusOpen.get(), "exactly one CPU status registration may be live");

        fixture.plugin.disable();
        assertEquals(3, fixture.statusCloses.get(), "disable must close the live CPU status registration");
        assertEquals(0, fixture.statusOpen.get());
    }

    @Test
    void disableClosesCpuStatusAndLateSnapshotCannotReviveIt() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context());
        fixture.plugin.enable();
        final Consumer<PerformanceSnapshot> lateSampler = fixture.consumer.get();
        assertNotNull(lateSampler);

        fixture.plugin.disable();
        assertEquals(1, fixture.statusCloses.get(), "disable must close the CPU status registration");

        lateSampler.accept(new PerformanceSnapshot(9L, 55.5, 1L, 1L, 30.0, 60L, 0L, 0L, 1L, 10L));
        assertEquals(1, fixture.statusNotifications.get(),
            "a late callback after disable must not leave a label behind");
        assertEquals(1, fixture.statusCloses.get());
    }

    @Test
    void repeatedEnableDoesNotDuplicateCpuLabel() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.plugin.init(fixture.context());
        fixture.plugin.enable();
        fixture.plugin.enable();

        assertEquals(1, fixture.statusNotifications.get(), "repeated enable must not re-register the label");
        assertEquals("perf-stats.cpu-status", fixture.lastStatus.get().id());
    }

    @Test
    void cpuStatusPublishCannotDeadlockWhenEdtDisablesDuringNotify() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.edtBlockingNotify = true;
        fixture.plugin.init(fixture.context());
        fixture.plugin.enable();
        assertEquals(1, fixture.statusOpen.get(), "enable must leave the placeholder registration live");
        final Consumer<PerformanceSnapshot> sampler = fixture.consumer.get();
        assertNotNull(sampler);
        // Mimic the production CxStatusBarHostOperations: the notify call blocks
        // the sampling thread on the Swing EDT, and the EDT work calls disable().
        fixture.onNotifyStatusOnEdt = () -> fixture.plugin.disable();

        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> sampler.accept(new PerformanceSnapshot(
                1L, 12.3, 1024L, 512L, 60.0, 120L, 0L, 0L, 3L, 250L)))
                .get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(0, fixture.statusOpen.get(),
            "no CPU status registration may survive a disable issued from the EDT");
        assertTrue(fixture.statusCloses.get() >= 2,
            "placeholder and in-flight replacement must both be closed");
        sampler.accept(new PerformanceSnapshot(2L, 1.0, 1024L, 512L, 60.0, 120L, 0L, 0L, 3L, 250L));
        assertEquals(0, fixture.statusOpen.get(), "a late callback after disable must not revive the label");
        assertEquals(2, fixture.statusNotifications.get(), "no publish may happen after disable");
    }

    @Test
    void windowMetricRowsAreIndependentlyCollapsibleAndReclaimVerticalSpace() {
        // JPanel-only structural check: the JFrame itself cannot be created
        // in a headless test JVM, but the row component can.
        final javax.swing.JComponent chart = new javax.swing.JLabel("chart");
        chart.setPreferredSize(new java.awt.Dimension(460, 74));
        final PerfStatsWindow.MetricRow row = new PerfStatsWindow.MetricRow(
            "CPU %", "Expand", "Collapse", chart);
        assertTrue(row.isExpanded(), "every metric row must start expanded");
        assertTrue(row.headerText().contains("Collapse"),
            "an expanded row must advertise the collapse action");
        final int expandedHeight = row.getPreferredSize().height;

        row.setExpanded(false);
        assertFalse(row.isExpanded(), "collapse must hide the chart content");
        assertTrue(row.headerText().contains("Expand"),
            "a collapsed row must advertise the expand action");
        assertTrue(row.getPreferredSize().height < expandedHeight,
            "collapsing must reclaim the vertical space instead of leaving an empty block");

        row.setExpanded(true);
        assertTrue(row.isExpanded(), "the row must expand back");
        assertTrue(row.getPreferredSize().height >= expandedHeight,
            "re-expanding must restore the row height");
    }

    @Test
    void windowMetricRowHeaderIsKeyboardActivatableAndCarriesAccessibleActionText() {
        final javax.swing.JComponent chart = new javax.swing.JLabel("chart");
        final PerfStatsWindow.MetricRow row = new PerfStatsWindow.MetricRow(
            "CPU", "Expand", "Collapse", chart);
        final javax.swing.AbstractButton header = row.header();
        assertTrue(header.isFocusable(), "the row header must be reachable by keyboard");
        assertTrue(header.isSelected(), "an expanded row must be selected");
        assertEquals("CPU Collapse", header.getAccessibleContext().getAccessibleName(),
            "the accessible name must carry the localized collapse action");

        header.doClick();
        assertFalse(row.isExpanded(), "activating the header must collapse the row");
        assertEquals("CPU Expand", header.getAccessibleContext().getAccessibleName(),
            "the accessible name must switch to the localized expand action");

        header.doClick();
        assertTrue(row.isExpanded(), "activating the header again must expand the row");
    }

    @Test
    void windowRowChartPlotHasNoLeftGutter() {
        // Regression: the standalone row chart must stay flush with the row
        // content edge (no 70px axis gutter), aligned with the embedded
        // runtime ChartComponent.LEFT_MARGIN, and keep the same top value
        // band layout (no own title, like runtime showTitle=false) so a
        // max-reaching plot cannot cross the text. TOP_INSET/TEXT_BAND_GAP
        // mirror the runtime band inset and gap.
        assertTrue(PerfStatsWindow.PLOT_LEFT_INSET <= 8,
            "the standalone chart plot must start at the content edge (inset="
                + PerfStatsWindow.PLOT_LEFT_INSET + ")");
        assertEquals(2, PerfStatsWindow.TOP_INSET,
            "the value band must sit at the component top like runtime showTitle=false");
        assertEquals(2, PerfStatsWindow.TEXT_BAND_GAP,
            "the plot gap must mirror runtime ChartComponent.TEXT_BAND_GAP");
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
        if (view instanceof PanelView.CollapsibleSection section) {
            for (PanelView child : section.children()) {
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
        if (view instanceof PanelView.CollapsibleSection section) {
            for (PanelView child : section.children()) {
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
        final AtomicInteger statusNotifications = new AtomicInteger();
        final AtomicInteger statusCloses = new AtomicInteger();
        final AtomicInteger statusOpen = new AtomicInteger();
        final AtomicReference<StatusNotification> lastStatus = new AtomicReference<>();
        /** When true, fake notifyStatus blocks the caller on the EDT, like the real CX host operations. */
        boolean edtBlockingNotify;
        /** EDT work run inside a blocking notifyStatus (default no-op). */
        Runnable onNotifyStatusOnEdt = () -> { };
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
                    if (method.getName().equals("notifyStatus")) {
                        if (edtBlockingNotify) {
                            SwingUtilities.invokeAndWait(() -> onNotifyStatusOnEdt.run());
                        }
                        StatusNotification notification = (StatusNotification) args[0];
                        statusNotifications.incrementAndGet();
                        statusOpen.incrementAndGet();
                        lastStatus.set(notification);
                        return (Registration) () -> {
                            statusCloses.incrementAndGet();
                            statusOpen.decrementAndGet();
                        };
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
