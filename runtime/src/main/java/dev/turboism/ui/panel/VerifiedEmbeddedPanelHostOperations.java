package dev.turboism.ui.panel;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact-version Cubism embedded-panel operations restricted to verified aliases. */
public final class VerifiedEmbeddedPanelHostOperations implements EmbeddedPanelHostOperations {

    private static final String APP_INSTANCE = "cubism.ui-panel.app-controller.instance";
    private static final String APP_MAIN_FRAME = "cubism.ui-panel.app-controller.main-frame";
    private static final String APP_REPAINT = "cubism.ui-panel.app-controller.repaint";
    private static final String MAIN_FRAME_DOCK_MANAGER = "cubism.ui-panel.main-frame.dock-manager";
    private static final String DOCK_PALETTE_MANAGER = "cubism.ui-panel.dock.palette-manager";
    private static final String DOCK_SET_PALETTE_VISIBLE = "cubism.ui-panel.dock.set-palette-visible";
    private static final String DOCK_UPDATE_WINDOW_MENU = "cubism.ui-panel.dock.update-window-menu";
    private static final String PALETTE_MANAGER_GET = "cubism.ui-panel.palette-manager.get";
    private static final String PALETTE_MANAGER_ADD = "cubism.ui-panel.palette-manager.add";
    private static final String PALETTE_MANAGER_CLOSE = "cubism.ui-panel.palette-manager.close";
    private static final String PALETTE_MANAGER_CURRENT_WORKSPACE =
        "cubism.ui-panel.palette-manager.current-workspace";
    private static final String WORKSPACE_ACTIVATE = "cubism.ui-panel.workspace.activate";
    private static final String PALETTE_ID_CREATE = "cubism.ui-panel.palette-id.create";
    private static final String PALETTE_CREATE = "cubism.ui-panel.palette.create";
    private static final String PALETTE_SET_PANEL = "cubism.ui-panel.palette.set-panel";
    private static final String SWING_CONTAINER_CREATE = "cubism.ui-panel.swing-container.create";
    private static final String CONTENT_PENDING = "Content is not available yet.";

    private final VerifiedMemberResolver resolver;

    public VerifiedEmbeddedPanelHostOperations(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public PanelHandle addPanel(final EmbeddedPanelContributionDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return onEdt(() -> install(descriptor));
    }

    @Override
    public Registration onRebuild(final Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        // No speculative native hook is installed. A future verified lifecycle callback may invoke this.
        return () -> { };
    }

    private PanelHandle install(final EmbeddedPanelContributionDescriptor descriptor) {
        final NativeDock dock = resolveDock();
        final String nativeId = "turboism:" + descriptor.pluginId() + ":" + descriptor.contributionId();
        final Object paletteId = resolver.construct(PALETTE_ID_CREATE, nativeId);
        if (resolver.invoke(PALETTE_MANAGER_GET, dock.paletteManager(), paletteId) != null) {
            resolver.invoke(PALETTE_MANAGER_CLOSE, dock.paletteManager(), paletteId);
        }

        final Object palette = resolver.construct(PALETTE_CREATE, paletteId, descriptor.title());
        final Object content = resolver.construct(
            SWING_CONTAINER_CREATE,
            placeholder(descriptor.title(), nativeId)
        );
        resolver.invoke(PALETTE_SET_PANEL, palette, content, 340, 300);
        resolver.invoke(PALETTE_MANAGER_ADD, dock.paletteManager(), palette);
        resolver.invoke(DOCK_SET_PALETTE_VISIBLE, dock.dockManager(), palette, true);
        refresh(dock);

        final AtomicBoolean closed = new AtomicBoolean();
        return new PanelHandle() {
            @Override
            public void activate() {
                if (closed.get()) {
                    throw new IllegalStateException("embedded panel is closed");
                }
                runOnEdtLater(() -> {
                    if (closed.get()) {
                        return;
                    }
                    final Object workspace = resolver.invoke(
                        PALETTE_MANAGER_CURRENT_WORKSPACE,
                        dock.paletteManager()
                    );
                    if (workspace == null) {
                        throw new IllegalStateException("Cubism current workspace is unavailable");
                    }
                    resolver.invoke(WORKSPACE_ACTIVATE, workspace, palette);
                    resolver.invoke(DOCK_SET_PALETTE_VISIBLE, dock.dockManager(), palette, true);
                    refresh(dock);
                });
            }

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                onEdt(() -> {
                    closePanel(
                        () -> resolver.invoke(
                            DOCK_SET_PALETTE_VISIBLE,
                            dock.dockManager(),
                            palette,
                            false
                        ),
                        () -> resolver.invoke(
                            PALETTE_MANAGER_CLOSE,
                            dock.paletteManager(),
                            paletteId
                        ),
                        () -> refresh(dock)
                    );
                    return null;
                });
            }
        };
    }

    private NativeDock resolveDock() {
        final Object app = resolver.invokeStatic(APP_INSTANCE);
        final Object mainFrame = resolver.invoke(APP_MAIN_FRAME, app);
        if (mainFrame == null) {
            throw new IllegalStateException("Cubism main frame is not ready");
        }
        final Object dockManager = resolver.invoke(MAIN_FRAME_DOCK_MANAGER, mainFrame);
        if (dockManager == null) {
            throw new IllegalStateException("Cubism dock manager is not ready");
        }
        final Object paletteManager = resolver.invoke(DOCK_PALETTE_MANAGER, dockManager);
        if (paletteManager == null) {
            throw new IllegalStateException("Cubism palette manager is not ready");
        }
        return new NativeDock(app, dockManager, paletteManager);
    }

    private void refresh(final NativeDock dock) {
        resolver.invoke(DOCK_UPDATE_WINDOW_MENU, dock.dockManager());
        resolver.invoke(APP_REPAINT, dock.app());
    }

    private static JPanel placeholder(final String title, final String nativeId) {
        final JPanel panel = new JPanel(new BorderLayout());
        panel.setName(nativeId);
        panel.add(new JLabel(title, SwingConstants.CENTER), BorderLayout.CENTER);
        panel.add(new JLabel(CONTENT_PENDING, SwingConstants.CENTER), BorderLayout.SOUTH);
        return panel;
    }

    static void runOnEdtLater(final Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        if (SwingUtilities.isEventDispatchThread()) {
            operation.run();
            return;
        }
        SwingUtilities.invokeLater(operation);
    }

    static void closePanel(
        final Runnable hide,
        final Runnable close,
        final Runnable refresh
    ) {
        final Runnable[] operations = {
            Objects.requireNonNull(hide, "hide"),
            Objects.requireNonNull(close, "close"),
            Objects.requireNonNull(refresh, "refresh")
        };
        RuntimeException first = null;
        for (Runnable operation : operations) {
            try {
                operation.run();
            } catch (RuntimeException failure) {
                if (first == null) {
                    first = failure;
                } else {
                    first.addSuppressed(failure);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static <T> T onEdt(final Operation<T> operation) {
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.run();
        }
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result[0] = operation.run();
                } catch (Throwable throwable) {
                    failure[0] = throwable;
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedded-panel EDT operation was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("embedded-panel EDT operation failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new IllegalStateException("embedded-panel EDT operation failed", failure[0]);
        }
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    private record NativeDock(Object app, Object dockManager, Object paletteManager) {
        private NativeDock {
            Objects.requireNonNull(app, "app");
            Objects.requireNonNull(dockManager, "dockManager");
            Objects.requireNonNull(paletteManager, "paletteManager");
        }
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run();
    }
}
