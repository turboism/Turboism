package dev.turboism.plugin.clipmaskviewer;

import dev.turboism.plugin.clipmaskviewer.ui.ClipMaskViewerWindow;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.CollapsibleSectionContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 剪贴蒙版检查器（clipmask-viewer）官方插件。
 *
 * <p>enable 时注册：打开查看器窗口的 action、Turboism tab 注入分区按钮、Turboism 菜单条目；
 * 全部注册句柄入 disposableScope。窗口按需创建（Swing EDT），headless 下安全跳过。</p>
 */
public final class ClipMaskViewerPlugin implements TurboismPlugin {

    static final String OPEN_VIEWER_ACTION_ID = "clipmask-viewer.open.viewer";
    static final String SECTION_ID = "clipmask-viewer.section";
    static final String BUTTON_ID = "clipmask-viewer.open";
    static final String TURBOISM_PANEL_ID = "turboism.panel.main";
    static final String MENU_ROOT = "Turboism";
    static final int SECTION_ORDER = 100;

    private final UiAccess ui;
    private final Object lifecycleLock = new Object();
    private final AtomicReference<WindowView> window = new AtomicReference<>();
    private PluginContext context;
    private PluginLocalization localization;
    private PluginLogger logger;
    private boolean initialized;
    private boolean enabled;
    private long generation;

    public ClipMaskViewerPlugin() {
        this(new SwingUiAccess());
    }

    ClipMaskViewerPlugin(final UiAccess ui) {
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.localization = context.localization();
        this.logger = context.logger();
        synchronized (lifecycleLock) {
            initialized = true;
        }
        context.disposableScope().register(this::disposeWindow);
        context.logger().info("ClipMaskViewerPlugin initialized");
    }

    @Override
    public void enable() {
        synchronized (lifecycleLock) {
            if (!initialized) {
                throw new IllegalStateException("ClipMaskViewerPlugin must be initialized before enable.");
            }
            enabled = true;
            generation++;
        }
        try {
            registerAction();
            context.disposableScope().register(contributeSection());
            context.disposableScope().register(contributeMenu());
        } catch (RuntimeException failure) {
            closeScopeQuietly();
            throw failure;
        }
        logger.info(
            "ClipMaskViewerPlugin enabled: open-viewer action, Turboism tab section and menu enrolled in disposable scope"
        );
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
            generation++;
        }
        disposeWindow();
    }

    private void registerAction() {
        final Registration registration = context.actions().register(
            OPEN_VIEWER_ACTION_ID,
            new ActionRegistry.Action() {
                @Override
                public String id() {
                    return OPEN_VIEWER_ACTION_ID;
                }

                @Override
                public String label() {
                    return localization.text("button.open");
                }

                @Override
                public Consumer<ActionRegistry.ActionContext> handler() {
                    return ignored -> openViewer();
                }
            }
        );
        context.disposableScope().register(registration);
    }

    private Registration contributeSection() {
        final PanelView content = PanelView.column(
            PanelView.button(BUTTON_ID, localization.text("button.open"), OPEN_VIEWER_ACTION_ID)
        );
        return context.uiHost().contributeCollapsibleSection(new CollapsibleSectionContribution(
            EmbeddedPanelId.of(TURBOISM_PANEL_ID),
            SECTION_ID,
            localization.text("section.title"),
            SECTION_ORDER,
            true,
            content
        ));
    }

    private Registration contributeMenu() {
        return context.menus().contribute(new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return MENU_ROOT + "/" + localization.text("menu.label");
            }

            @Override
            public String actionId() {
                return OPEN_VIEWER_ACTION_ID;
            }

            @Override
            public int order() {
                return SECTION_ORDER;
            }
        });
    }

    /** action 入口：打开或前置查看器窗口（EDT 调度；headless 下安全跳过）。 */
    private void openViewer() {
        final long openGeneration;
        synchronized (lifecycleLock) {
            if (!enabled) {
                return;
            }
            openGeneration = generation;
        }
        if (ui.isHeadless()) {
            logger.warn("Clip Mask Viewer cannot open because the JVM is headless");
            return;
        }
        ui.invokeLater(() -> showWindow(openGeneration));
    }

    private void showWindow(final long openGeneration) {
        final WindowView existing = window.get();
        if (existing != null) {
            existing.showAndFront();
            existing.refresh();
            return;
        }
        final WindowView[] created = new WindowView[1];
        created[0] = ui.create(localization, context, () -> window.compareAndSet(created[0], null));
        final WindowView view = created[0];
        synchronized (lifecycleLock) {
            if (!enabled || generation != openGeneration || !window.compareAndSet(null, view)) {
                view.dispose();
                return;
            }
        }
        view.showAndFront();
        view.refresh();
    }

    private void disposeWindow() {
        final Runnable dispose = () -> {
            final WindowView view = window.getAndSet(null);
            if (view != null) {
                view.dispose();
            }
        };
        try {
            ui.invokeAndWait(dispose);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.warn("Clip Mask Viewer EDT disposal interrupted");
        } catch (InvocationTargetException | RuntimeException exception) {
            logger.warn("Clip Mask Viewer EDT disposal failed safely");
        }
    }

    private void closeScopeQuietly() {
        try {
            context.disposableScope().close();
        } catch (Exception closeFailure) {
            logger.warn("ClipMaskViewerPlugin enable rollback close failed: " + closeFailure.getMessage());
        }
    }

    public interface UiAccess {
        boolean isHeadless();

        void invokeLater(Runnable action);

        void invokeAndWait(Runnable action) throws InterruptedException, InvocationTargetException;

        WindowView create(PluginLocalization localization, PluginContext context, Runnable onClosed);
    }

    public interface WindowView {
        void showAndFront();

        void refresh();

        void dispose();
    }

    private static final class SwingUiAccess implements UiAccess {
        @Override
        public boolean isHeadless() {
            return GraphicsEnvironment.isHeadless();
        }

        @Override
        public void invokeLater(final Runnable action) {
            SwingUtilities.invokeLater(action);
        }

        @Override
        public void invokeAndWait(final Runnable action)
            throws InterruptedException, InvocationTargetException {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
            } else {
                SwingUtilities.invokeAndWait(action);
            }
        }

        @Override
        public WindowView create(
            final PluginLocalization localization,
            final PluginContext context,
            final Runnable onClosed
        ) {
            return new ClipMaskViewerWindow(localization, context, onClosed);
        }
    }
}
