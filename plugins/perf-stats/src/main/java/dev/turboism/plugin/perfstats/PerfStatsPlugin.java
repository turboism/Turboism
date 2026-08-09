package dev.turboism.plugin.perfstats;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.performance.PerformanceSnapshot;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.menu.MenuRegistry;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Performance Statistics plugin: live CPU / FPS / JVM memory charts in an
 * embedded panel (via {@code PanelView.Chart}, values injected by the runtime)
 * and a standalone Swing window opened on demand from the Turboism top-level
 * menu (Performance Monitor). Enable starts the sampling registration, which
 * mounts the FPS counting hook and the bytecode instrumentation, and registers
 * the window action and menu item; it never opens the window itself. Disable
 * closes the sampling registration (stopping sampling, restoring the
 * instrumented host bytecode, and verifying the restoration) and disposes the
 * window. The runtime closes the plugin DisposableScope after disable or
 * shutdown, which unregisters the action and the menu contribution.
 */
public final class PerfStatsPlugin implements TurboismPlugin {

    static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(1);
    static final int WINDOW_POINTS = 120;

    private static final String PANEL_ID = "perf-stats.chart";
    private static final String PANEL_TITLE = "Performance";
    private static final String WINDOW_TITLE = "Performance Statistics";
    private static final String CHART_TITLE_FPS = "Viewport Render FPS";
    private static final String SERIES_FPS = "Viewport Render FPS";
    private static final String SERIES_CPU = "CPU %";
    private static final String SERIES_HEAP = "JVM Heap";
    private static final String SERIES_NONHEAP = "JVM Non-Heap";
    private static final String SERIES_GC = "GC Pause";
    private static final String PANEL_PLACEMENT = "side";
    private static final int PANEL_PRIORITY = 50;

    private static final String WINDOW_ACTION_ID = "perf-stats.window.show";
    private static final String WINDOW_ACTION_LABEL = "Performance Monitor";
    private static final String MENU_ITEM_KEY = "menu.performance-monitor";
    private static final String MENU_ROOT = "Turboism";
    private static final int MENU_ORDER = 20;

    private static final String WINDOW_EXPAND_KEY = "window.expand";
    private static final String WINDOW_EXPAND_FALLBACK = "Expand";
    private static final String WINDOW_COLLAPSE_KEY = "window.collapse";
    private static final String WINDOW_COLLAPSE_FALLBACK = "Collapse";

    private static final String CPU_STATUS_ID = "perf-stats.cpu-status";
    private static final String CPU_STATUS_KEY = "status.cpu.label";
    private static final String CPU_STATUS_FALLBACK = "CPU";
    private static final String CPU_STATUS_SEVERITY = "INFO";
    private static final String CPU_STATUS_PLACEHOLDER = "--%";

    private final Object lifecycleLock = new Object();
    private final AtomicReference<PerfStatsWindow> window = new AtomicReference<>();
    private final ChartStore store = new ChartStore(WINDOW_POINTS * 2);
    private PluginContext context;
    private Registration sampling;
    private Registration cpuStatus;
    private boolean initialized;
    private boolean enabled;

    public PerfStatsPlugin() {
    }

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        synchronized (lifecycleLock) {
            initialized = true;
        }
        final Registration panel = context.uiHost().contributeEmbeddedPanel(embeddedPanel());
        context.disposableScope().register(panel);
        context.disposableScope().register(this::stopSampling);
        context.disposableScope().register(this::disposeWindow);
        context.logger().info("Performance Statistics initialized");
    }

    private EmbeddedPanelContribution embeddedPanel() {
        return new EmbeddedPanelContribution(
            PANEL_ID,
            text("panel.title", PANEL_TITLE),
            PANEL_PLACEMENT,
            PANEL_PRIORITY,
            PanelView.column(
                PanelView.collapsibleSection(
                    text("chart.cpu.title", "CPU"),
                    true,
                    PanelView.chart(
                        "cpu",
                        text("chart.cpu.title", "CPU"),
                        PanelView.series(text("series.cpu", SERIES_CPU), WINDOW_POINTS, "%", "0.0")
                    )
                ),
                PanelView.collapsibleSection(
                    text("chart.fps.title", CHART_TITLE_FPS),
                    true,
                    PanelView.chart(
                        "fps",
                        text("chart.fps.title", CHART_TITLE_FPS),
                        PanelView.series(text("series.fps", SERIES_FPS), WINDOW_POINTS, "fps", "0.0")
                    )
                ),
                PanelView.collapsibleSection(
                    text("chart.heap.title", SERIES_HEAP),
                    true,
                    PanelView.chart(
                        "heap",
                        text("chart.heap.title", SERIES_HEAP),
                        PanelView.series(text("series.heap", SERIES_HEAP), WINDOW_POINTS, "MiB", "0.0")
                    )
                ),
                PanelView.collapsibleSection(
                    text("chart.nonheap.title", SERIES_NONHEAP),
                    true,
                    PanelView.chart(
                        "nonheap",
                        text("chart.nonheap.title", SERIES_NONHEAP),
                        PanelView.series(text("series.nonheap", SERIES_NONHEAP), WINDOW_POINTS, "MiB", "0.0")
                    )
                ),
                PanelView.collapsibleSection(
                    text("chart.gc.title", SERIES_GC),
                    true,
                    PanelView.chart(
                        "gc",
                        text("chart.gc.title", SERIES_GC),
                        PanelView.series(text("series.gc", SERIES_GC), WINDOW_POINTS, "ms", "0.0")
                    )
                )
            )
        );
    }

    /**
     * Localized text for one catalog key with an explicit English fallback when
     * the localization service is missing or unusable; never throws.
     */
    private String text(final String key, final String fallback) {
        try {
            final PluginLocalization localization = context.localization();
            if (localization == null) {
                return fallback;
            }
            final String value = localization.text(key);
            return value == null || value.isBlank() ? fallback : value;
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }


    /** Row titles for the standalone window: the same chart.*.title copy as the embedded sections. */
    Map<String, String> chartTitles() {
        return Map.of(
            ChartStore.KEY_CPU, text("chart.cpu.title", "CPU"),
            ChartStore.KEY_FPS, text("chart.fps.title", CHART_TITLE_FPS),
            ChartStore.KEY_HEAP, text("chart.heap.title", SERIES_HEAP),
            ChartStore.KEY_NONHEAP, text("chart.nonheap.title", SERIES_NONHEAP),
            ChartStore.KEY_GC, text("chart.gc.title", SERIES_GC)
        );
    }

    @Override
    public void enable() {
        synchronized (lifecycleLock) {
            if (!initialized) {
                throw new IllegalStateException("Performance Statistics must be initialized before enable.");
            }
            if (enabled) {
                return;
            }
        }
        final PerformanceProbeService stats = context.performanceStats();
        sampling = stats.sample(SAMPLE_INTERVAL, this::onSnapshot);
        synchronized (lifecycleLock) {
            enabled = true;
        }
        // Publish the initial placeholder before the first real sample arrives;
        // the runtime routes COMPACT_METRIC through the verified status adapter
        // (reviewed exact 5.2.03 or 5.3.02) when available and degrades to
        // transient state otherwise.
        publishCpuStatus(cpuStatusMessage(CPU_STATUS_PLACEHOLDER));
        final Registration action = context.actions().register(WINDOW_ACTION_ID, windowAction());
        context.disposableScope().register(action);
        final Registration menu = context.menus().contribute(windowMenuContribution());
        context.disposableScope().register(menu);
        context.logger().info("Performance Statistics enabled");
    }

    @Override
    public void disable() {
        deactivate();
    }

    @Override
    public void shutdown() {
        deactivate();
    }

    private void deactivate() {
        synchronized (lifecycleLock) {
            if (!initialized) {
                return;
            }
            enabled = false;
        }
        stopSampling();
        disposeWindow();
    }

    /**
     * Idempotent teardown. The sampling/cpuStatus handles are captured and
     * cleared atomically under the lifecycle lock; the external close calls run
     * outside the lock because a real host registration close blocks the
     * calling thread on the Swing EDT (invokeAndWait) and must never be issued
     * while the EDT could be waiting for the lifecycle lock (disable/shutdown
     * called from the EDT).
     */
    private void stopSampling() {
        final Registration active;
        final Registration status;
        synchronized (lifecycleLock) {
            active = sampling;
            sampling = null;
            status = cpuStatus;
            cpuStatus = null;
        }
        if (active != null) {
            active.close();
        }
        if (status != null) {
            status.close();
        }
    }

    private void disposeWindow() {
        final PerfStatsWindow active = window.getAndSet(null);
        if (active != null) {
            SwingUtilities.invokeLater(active::dispose);
        }
    }

    private void onSnapshot(final PerformanceSnapshot snapshot) {
        store.append(snapshot);
        publishCpuStatus(cpuStatusMessage(String.format(Locale.ROOT, "%.1f%%", snapshot.cpuPercent())));
    }

    /**
     * Two-phase compact CPU status publish: the lifecycle lock is held only for
     * the enabled check and the handle swap. The host notification and any
     * registration close run outside the lock because
     * {@code CxStatusBarHostOperations} blocks the calling thread on the Swing
     * EDT (invokeAndWait) and the EDT must be free to call disable()/cleanup.
     * If the plugin is disabled while the notification is in flight, the fresh
     * registration is closed again so no label survives disable. The host keeps
     * one widget per ID and identity-checks stale closes, so closing a replaced
     * registration never removes a newer widget and the plugin DisposableScope
     * stays bounded instead of growing with every one-second sample.
     */
    private void publishCpuStatus(final String message) {
        synchronized (lifecycleLock) {
            if (!enabled) {
                return;
            }
        }
        final Registration next = context.uiHost().notifyStatus(new StatusNotification(
            CPU_STATUS_ID,
            CPU_STATUS_SEVERITY,
            message,
            StatusNotification.Presentation.COMPACT_METRIC
        ));
        final Registration previous;
        final boolean canceled;
        synchronized (lifecycleLock) {
            if (enabled) {
                previous = cpuStatus;
                cpuStatus = next;
                canceled = false;
            } else {
                // Disabled while the notification was in flight: drop the fresh
                // registration instead of installing a label that outlives disable.
                previous = null;
                cpuStatus = null;
                canceled = true;
            }
        }
        if (canceled) {
            next.close();
            return;
        }
        if (previous != null) {
            previous.close();
        }
    }

    private String cpuStatusMessage(final String value) {
        return text(CPU_STATUS_KEY, CPU_STATUS_FALLBACK) + " " + value;
    }

    private void openWindow() {
        if (!enabled) {
            return;
        }
        final PerfStatsWindow existing = window.get();
        if (existing != null) {
            existing.showAndFront();
            return;
        }
        final PerfStatsWindow created = new PerfStatsWindow(
            text("window.title", WINDOW_TITLE),
            chartTitles(),
            text(WINDOW_EXPAND_KEY, WINDOW_EXPAND_FALLBACK),
            text(WINDOW_COLLAPSE_KEY, WINDOW_COLLAPSE_FALLBACK),
            store
        );
        if (window.compareAndSet(null, created)) {
            created.start();
            created.showAndFront();
        } else {
            created.dispose();
        }
    }

    private ActionRegistry.Action windowAction() {
        return new ActionRegistry.Action() {
            @Override
            public String id() {
                return WINDOW_ACTION_ID;
            }

            @Override
            public String label() {
                return text(MENU_ITEM_KEY, WINDOW_ACTION_LABEL);
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return ignored -> showWindow();
            }
        };
    }

    private MenuRegistry.MenuContribution windowMenuContribution() {
        return new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return MENU_ROOT + "/" + text(MENU_ITEM_KEY, WINDOW_ACTION_LABEL);
            }

            @Override
            public String actionId() {
                return WINDOW_ACTION_ID;
            }

            @Override
            public int order() {
                return MENU_ORDER;
            }
        };
    }

    private void showWindow() {
        if (isHeadless()) {
            context.logger().warn("Performance Statistics window cannot open because the JVM is headless");
            return;
        }
        SwingUtilities.invokeLater(this::openWindow);
    }

    private static boolean isHeadless() {
        return GraphicsEnvironment.isHeadless();
    }
}
