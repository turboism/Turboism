package dev.turboism.ui.panel;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.host.EdtDispatch;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * Installs a runtime-owned board over the native modeling viewport.
 *
 * <p>The board is a Swing overlay added to the main frame's layered pane. It covers only the
 * largest visible JOGL {@code GLJPanel}, leaving the surrounding toolbars, status bar, and
 * palettes untouched. The overlay is hidden while the host reports an open document and
 * becomes visible again once the viewport is empty.</p>
 *
 * <p>This class is deliberately separate from the native dock palette installer: a viewport
 * panel never creates a palette, never enters the workspace dock tree, and never participates
 * in the Window menu.</p>
 */
final class ViewportPanelInstaller {

    private static final String APP_INSTANCE = "cubism.ui-panel.app-controller.instance";
    private static final String OPENGL_VIEWPORT_CLASS = "com.jogamp.opengl.awt.GLJPanel";
    private static final long REFRESH_MILLIS = 750L;
    private static final int INITIAL_REFRESH_MILLIS = 250;
    private static final int BOARD_VERTICAL_INSET = 16;
    private static final int BOARD_HORIZONTAL_INSET = 16;
    private static final Color FALLBACK_BACKGROUND = new Color(0x2b2b2b);
    private static final String CONTENT_UPDATE_OPERATION = "viewport panel content update";

    private ViewportPanelInstaller() {}

    /**
     * Installs one viewport panel.
     *
     * @param resolver verified panel resolver used to reach the main frame
     * @param descriptor the viewport contribution
     * @param documentOpen reports whether a document is open; while true the panel hides
     * @param contentBuilder builds the rendered content for the current descriptor
     * @return the installed panel handle
     */
    static EmbeddedPanelHostOperations.PanelHandle install(
            final VerifiedMemberResolver resolver,
            final EmbeddedPanelContributionDescriptor descriptor,
            final BooleanSupplier documentOpen,
            final Function<EmbeddedPanelContributionDescriptor, JComponent> contentBuilder) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(documentOpen, "documentOpen");
        Objects.requireNonNull(contentBuilder, "contentBuilder");

        final Window frame = selectMainWindow();
        if (!(frame instanceof RootPaneContainer rootPane)) {
            throw new IllegalStateException("Cubism main window is not ready for a viewport panel");
        }
        final Component viewport = findOpenGlViewport(frame);
        if (viewport == null || viewport.getParent() == null) {
            throw new IllegalStateException("Cubism OpenGL viewport is not ready");
        }
        final JLayeredPane layeredPane = rootPane.getRootPane().getLayeredPane();
        return install(
                frame,
                viewport,
                layeredPane,
                descriptor,
                () -> readDocumentOpen(resolver, documentOpen),
                contentBuilder);
    }

    static EmbeddedPanelHostOperations.PanelHandle install(
            final Container frame,
            final Component viewport,
            final JLayeredPane layeredPane,
            final EmbeddedPanelContributionDescriptor descriptor,
            final BooleanSupplier documentOpen,
            final Function<EmbeddedPanelContributionDescriptor, JComponent> contentBuilder) {
        final AtomicReference<EmbeddedPanelContributionDescriptor> currentDescriptor =
                new AtomicReference<>(descriptor);
        final AtomicBoolean closed = new AtomicBoolean();

        final JPanel contentHolder = new JPanel(new BorderLayout());
        contentHolder.setOpaque(false);
        final JPanel host = new JPanel(new BorderLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(viewportBackground());
            }
        };
        host.setName(descriptor.contributionId());
        host.setOpaque(true);
        host.setBorder(BorderFactory.createEmptyBorder(
                BOARD_VERTICAL_INSET, BOARD_HORIZONTAL_INSET, BOARD_VERTICAL_INSET, BOARD_HORIZONTAL_INSET));
        host.add(contentHolder, BorderLayout.CENTER);
        host.setVisible(false);

        final Runnable refreshContent = () -> {
            final JComponent rendered = contentBuilder.apply(currentDescriptor.get());
            if (rendered.getParent() != contentHolder) {
                contentHolder.removeAll();
                contentHolder.add(rendered, BorderLayout.CENTER);
            }
            contentHolder.revalidate();
            contentHolder.repaint();
        };
        refreshContent.run();
        layeredPane.add(host, JLayeredPane.PALETTE_LAYER);

        final Runnable refreshBounds = () -> {
            if (closed.get()) return;
            final Rectangle bounds = computeViewportBounds(viewport, layeredPane);
            if (bounds.width > 0 && bounds.height > 0 && !bounds.equals(host.getBounds())) {
                host.setBounds(bounds);
                host.validate();
            }
        };
        final Registration layoutBinding = OverlayLayoutBinding.attach(viewport.getParent(), refreshBounds);
        final Timer timer = new Timer((int) REFRESH_MILLIS, ignored -> {
            if (closed.get()) {
                return;
            }
            final boolean documentIsOpen = documentOpen.getAsBoolean();
            if (documentIsOpen || !viewport.isShowing() || !frame.isShowing() || !layeredPane.isShowing()) {
                if (host.isVisible()) {
                    host.setVisible(false);
                }
                return;
            }
            refreshBounds.run();
            if (!host.isVisible()) {
                host.setVisible(true);
                layeredPane.setLayer(host, JLayeredPane.PALETTE_LAYER);
            }
        });
        timer.setInitialDelay(INITIAL_REFRESH_MILLIS);
        timer.start();

        return new EmbeddedPanelHostOperations.PanelHandle() {
            private volatile boolean handleClosed;

            @Override
            public void activate() {
                SwingUtilities.invokeLater(() -> {
                    if (handleClosed) {
                        return;
                    }
                    host.setVisible(true);
                    host.requestFocusInWindow();
                });
            }

            @Override
            public void updateContent(final EmbeddedPanelContributionDescriptor updated) {
                Objects.requireNonNull(updated, "descriptor");
                EdtDispatch.call(CONTENT_UPDATE_OPERATION, () -> {
                    if (!handleClosed) {
                        currentDescriptor.set(updated);
                        refreshContent.run();
                    }
                    return null;
                });
            }

            @Override
            public void close() {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                handleClosed = true;
                VerifiedEmbeddedPanelHostOperations.runOnEdtLater(() -> {
                    timer.stop();
                    layoutBinding.close();
                    layeredPane.remove(host);
                    layeredPane.revalidate();
                    layeredPane.repaint();
                });
            }
        };
    }

    /**
     * Reads the host's open-document list. Unlike the focused-document projection, the full
     * document list stays non-empty while any document is open even if focus moves to a palette,
     * so the board cannot cover a live canvas after a title-bar or palette click.
     */
    private static boolean readDocumentOpen(final VerifiedMemberResolver resolver, final BooleanSupplier fallback) {
        try {
            final Object app = resolver.invokeStatic(APP_INSTANCE);
            return app != null && hasOpenDocuments(app);
        } catch (RuntimeException failure) {
            FatalErrors.rethrowIfFatal(failure);
            try {
                return fallback.getAsBoolean();
            } catch (RuntimeException fallbackFailure) {
                FatalErrors.rethrowIfFatal(fallbackFailure);
                return true;
            }
        }
    }

    private static boolean hasOpenDocuments(final Object app) {
        try {
            final Object pack =
                    MethodHandleCache.method(app.getClass(), "getCompletePack").invoke(app);
            if (pack == null) {
                throw new IllegalStateException("Cubism complete pack is unavailable");
            }
            final Object documents =
                    MethodHandleCache.method(pack.getClass(), "getAllDocs").invoke(pack);
            return documents instanceof java.util.List<?> list && !list.isEmpty();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cubism document list is unavailable", failure);
        }
    }

    private static Rectangle computeViewportBounds(final Component viewport, final Container target) {
        if (viewport instanceof JComponent jc) {
            final Rectangle visible = jc.getVisibleRect();
            if (visible.width > 0 && visible.height > 0) {
                return SwingUtilities.convertRectangle(viewport, visible, target);
            }
        }
        final Component parent = viewport.getParent();
        if (parent != null) {
            final Rectangle parentRect = new Rectangle(0, 0, parent.getWidth(), parent.getHeight());
            return SwingUtilities.convertRectangle(parent, parentRect, target);
        }
        return SwingUtilities.convertRectangle(
                viewport, new Rectangle(0, 0, viewport.getWidth(), viewport.getHeight()), target);
    }
    /** Visible editor frame containing the largest native OpenGL viewport. */
    private static Window selectMainWindow() {
        Window best = null;
        long bestArea = -1L;
        for (Window window : Window.getWindows()) {
            if (!(window instanceof java.awt.Frame) || !window.isShowing() || !window.isDisplayable()) {
                continue;
            }
            final Component viewport = findOpenGlViewport(window);
            if (viewport == null) {
                continue;
            }
            final long area = (long) viewport.getWidth() * viewport.getHeight();
            if (area > bestArea) {
                best = window;
                bestArea = area;
            }
        }
        return best;
    }

    private static Component findOpenGlViewport(final Container root) {
        Component best = null;
        long bestArea = -1L;
        for (Component component : root.getComponents()) {
            if (OPENGL_VIEWPORT_CLASS.equals(component.getClass().getName())
                    && component.isShowing()
                    && component.getWidth() > 0
                    && component.getHeight() > 0) {
                final long area = (long) component.getWidth() * component.getHeight();
                if (area > bestArea) {
                    best = component;
                    bestArea = area;
                }
            }
            if (component instanceof Container container) {
                final Component nested = findOpenGlViewport(container);
                if (nested != null) {
                    final long area = (long) nested.getWidth() * nested.getHeight();
                    if (area > bestArea) {
                        best = nested;
                        bestArea = area;
                    }
                }
            }
        }
        return best;
    }

    private static Color viewportBackground() {
        final Color themed = UIManager.getColor("Panel.background");
        return themed == null ? FALLBACK_BACKGROUND : themed;
    }
}
