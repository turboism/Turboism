package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshBrush;
import dev.turboism.sdk.cubism.mesh.MeshEditor;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.ui.host.EdtDispatch;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/** Runtime-owned geometric mesh point-and-edge layout controller bound to one mesh-tool activation. */
final class RuntimeGeometricMeshLayout implements MeshBrush {
    static final String TOOL_ID = "geometric-mesh-layout";
    private static final int MAX_POSITION_RETRIES = 20;
    private static final String ESCAPE_ACTION_KEY = "turboism:geometric-mesh-layout-deactivate";
    private static final KeyStroke ESCAPE = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);

    private static volatile GeometricMeshDialog.Config sharedConfig = new GeometricMeshDialog.Config(
            GeometricMeshShape.Preset.CIRCLE,
            GeometricMeshShape.DEFAULT_RADIUS,
            GeometricMeshShape.DEFAULT_SIDES,
            GeometricMeshShape.DEFAULT_CONCENTRIC_RINGS,
            false,
            false);

    private final Host host;
    private final GeometricMeshOverlayPanel overlay;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int radiusPixels = 32;
    private GeometricMeshDialog.Config currentConfig = sharedConfig;
    private Point2 anchor;
    private boolean previewActive;
    private boolean draggingAnchor;
    private boolean installed;
    private boolean forwarding;
    private int positionRetries;
    private JComponent component;
    private GeometricMeshDialog dialog;
    private Object previousEscapeBinding;
    private Action previousEscapeAction;
    private KeyboardFocusManager keyboard;
    private boolean escapePressed;
    private boolean navigationPressed;
    private final KeyEventDispatcher keyboardDispatcher = this::dispatchKeyEvent;

    private final AbstractAction escapeAction = new AbstractAction() {
        @Override
        public void actionPerformed(final ActionEvent event) {
            if (!operational()) return;
            try {
                cancelAndDeactivate();
            } catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
            }
        }
    };

    private final MouseAdapter mouse = new MouseAdapter() {
        @Override
        public void mousePressed(final MouseEvent event) {
            if (event.getButton() == MouseEvent.BUTTON1 && !navigationPressed) {
                event.consume();
                primaryPress(event);
            } else {
                forward(event);
            }
        }

        @Override
        public void mouseReleased(final MouseEvent event) {
            if (event.getButton() == MouseEvent.BUTTON1 && draggingAnchor) {
                event.consume();
                draggingAnchor = false;
            } else {
                forward(event);
                schedulePreviewRefresh();
            }
        }

        @Override
        public void mouseClicked(final MouseEvent event) {
            if (event.getButton() == MouseEvent.BUTTON1 && !navigationPressed) {
                event.consume();
            } else {
                forward(event);
            }
        }

        @Override
        public void mouseEntered(final MouseEvent event) {
            forward(event);
        }

        @Override
        public void mouseExited(final MouseEvent event) {
            forward(event);
        }
    };

    private final MouseMotionListener motion = new MouseMotionListener() {
        @Override
        public void mouseDragged(final MouseEvent event) {
            if (draggingAnchor && !navigationPressed) {
                event.consume();
                primaryDrag(event);
            } else {
                forward(event);
                schedulePreviewRefresh();
            }
        }

        @Override
        public void mouseMoved(final MouseEvent event) {
            forward(event);
        }
    };

    private final MouseWheelListener wheel = event -> {
        forward(event);
        schedulePreviewRefresh();
    };

    private final ComponentAdapter bounds = new ComponentAdapter() {
        @Override
        public void componentMoved(final ComponentEvent event) {
            positionOverlay();
            refreshOverlayPreview();
        }

        @Override
        public void componentResized(final ComponentEvent event) {
            positionOverlay();
            refreshOverlayPreview();
        }
    };

    private final ContainerAdapter children = new ContainerAdapter() {
        @Override
        public void componentAdded(final ContainerEvent event) {
            keepTopmost();
        }
    };

    RuntimeGeometricMeshLayout(final Host host) {
        this.host = Objects.requireNonNull(host, "host");
        this.overlay = new GeometricMeshOverlayPanel(this::capturesInputAt);
    }

    static boolean matches(final MeshTool tool) {
        return tool != null && TOOL_ID.equals(tool.id());
    }

    static RuntimeGeometricMeshLayout production(
            final MeshEditor editor,
            final MeshToolCoordinator.ActivationLease lease,
            final Runnable deactivate,
            final MeshTool tool,
            final PermissionChecker permissions) {
        Objects.requireNonNull(editor, "editor");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(deactivate, "deactivate");
        lease.requireCurrent();
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(permissions, "permissions");
        if (!(lease.session() instanceof NativeMeshToolSession session)) {
            throw new IllegalStateException("geometric mesh layout requires an exact native mesh-tool session");
        }
        final VerifiedMemberResolver resolver = session.resolver();
        final Object rawComponent = session.identity().component();
        if (resolver == null || !(rawComponent instanceof JComponent component)) {
            throw new IllegalStateException("geometric mesh layout exact component or resolver is unavailable");
        }
        NativeMeshMirrorBridge.disarmNativePointAddPreview(resolver.hostClassLoader());
        return new RuntimeGeometricMeshLayout(new Host() {
            @Override
            public JComponent component() {
                lease.requireCurrent();
                return component;
            }

            @Override
            public boolean nativeControlAt(final int x, final int y) {
                lease.requireCurrent();
                return NativeMeshControls.contains(resolver, session.identity().modelingView(), x, y);
            }

            @Override
            public Point2 defaultAnchor() {
                lease.requireCurrent();
                permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, "mesh.geometric-layout.default-anchor");
                return NativeMeshVertexProjector.defaultMeshCenter(resolver, session.identity());
            }

            @Override
            public Point2 unprojectToMesh(final float componentX, final float componentY) {
                lease.requireCurrent();
                permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, "mesh.geometric-layout.unproject-anchor");
                return NativeMeshVertexProjector.unprojectToMesh(resolver, session.identity(), componentX, componentY);
            }

            @Override
            public List<Point2> projectPositions(final float[] meshPositions) {
                lease.requireCurrent();
                permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, "mesh.geometric-layout.project-preview");
                return NativeMeshVertexProjector.projectPositions(resolver, session.identity(), meshPositions);
            }

            @Override
            public List<Integer> commitPlan(final float[] meshPositions, final int[] edgePairs) {
                lease.requireCurrent();
                permissions.check(
                        PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "mesh.geometric-layout.commit-points-edges");
                try {
                    return NativeMeshMirrorBridge.addGeometricPointsAndEdges(
                            session.identity().mode(),
                            session.identity().editableMesh(),
                            session.identity().completePack(),
                            tool.label(),
                            meshPositions,
                            edgePairs);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("failed to commit geometric mesh points and edges", failure);
                }
            }

            @Override
            public void selectVertices(final List<Integer> indices) {
                lease.requireCurrent();
                if (indices != null && !indices.isEmpty()) {
                    editor.select(new VertexSelection(indices), SelectionMode.REPLACE);
                    NativeMeshMirrorBridge.markSelectionBoundingBoxDirty(
                            session.identity().mode(), session.identity().completePack());
                }
            }

            @Override
            public boolean revalidate() {
                return lease.revalidate();
            }

            @Override
            public void deactivate() {
                deactivate.run();
            }
        });
    }

    void install() {
        requireOpen();
        if (!host.revalidate()) throw new IllegalStateException("geometric-mesh-layout activation lease is stale");
        runOnEdt(() -> {
            if (installed) return;
            final JComponent target = Objects.requireNonNull(host.component(), "host.component()");
            component = target;
            final InputMap inputMap = overlay.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            final ActionMap actionMap = overlay.getActionMap();
            previousEscapeBinding = inputMap.get(ESCAPE);
            previousEscapeAction = actionMap.get(ESCAPE_ACTION_KEY);
            overlay.addMouseListener(mouse);
            overlay.addMouseMotionListener(motion);
            overlay.addMouseWheelListener(wheel);
            inputMap.put(ESCAPE, ESCAPE_ACTION_KEY);
            actionMap.put(ESCAPE_ACTION_KEY, escapeAction);
            target.addComponentListener(bounds);
            target.addContainerListener(children);
            installed = true;
            try {
                keyboard = KeyboardFocusManager.getCurrentKeyboardFocusManager();
                keyboard.addKeyEventDispatcher(keyboardDispatcher);
                target.add(overlay);
                keepTopmost();
                positionOverlay();
                initializeAnchorAndDialog();
                target.repaint();
            } catch (RuntimeException | Error failure) {
                cleanupOnEdt();
                throw failure;
            }
        });
    }

    private void initializeAnchorAndDialog() {
        try {
            anchor = host.defaultAnchor();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            anchor = new Point2(0.0f, 0.0f);
        }
        previewActive = true;
        dialog = new GeometricMeshDialog(currentConfig, anchor, new GeometricMeshDialog.Listener() {
            @Override
            public void onConfigChanged(final GeometricMeshDialog.Config config) {
                currentConfig = config;
                sharedConfig = config;
                if (previewActive) {
                    refreshOverlayPreview();
                }
            }

            @Override
            public void onConfirm(final GeometricMeshDialog.Config config) {
                currentConfig = config;
                sharedConfig = config;
                commitCurrentGeometry();
            }

            @Override
            public void onCancel() {
                cancelAndDeactivate();
            }
        });
        refreshOverlayPreview();
        dialog.show(component);
    }

    private void primaryPress(final MouseEvent event) {
        if (!operational()) return;
        draggingAnchor = true;
        updateAnchorFromComponentPoint(event.getX(), event.getY());
    }

    private void primaryDrag(final MouseEvent event) {
        if (!draggingAnchor) return;
        if (!operational()) {
            draggingAnchor = false;
            return;
        }
        updateAnchorFromComponentPoint(event.getX(), event.getY());
    }

    private void updateAnchorFromComponentPoint(final int x, final int y) {
        try {
            final Point2 nextAnchor = host.unprojectToMesh(x, y);
            if (nextAnchor != null && Float.isFinite(nextAnchor.x()) && Float.isFinite(nextAnchor.y())) {
                anchor = nextAnchor;
                previewActive = true;
                if (dialog != null) {
                    dialog.updateAnchor(anchor);
                    dialog.show(component);
                }
                refreshOverlayPreview();
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private void refreshOverlayPreview() {
        if (!operational() || !previewActive || anchor == null) {
            overlay.clearPreview();
            return;
        }
        try {
            final GeometricMeshShape.LayoutPlan plan = currentConfig.toLayoutPlan(anchor.x(), anchor.y());
            final float[] verticesMesh = plan.verticesXY();
            final float[] combined = new float[2 + verticesMesh.length];
            combined[0] = anchor.x();
            combined[1] = anchor.y();
            System.arraycopy(verticesMesh, 0, combined, 2, verticesMesh.length);
            final List<Point2> projected = host.projectPositions(combined);
            if (projected.isEmpty()) {
                overlay.clearPreview();
                return;
            }
            final Point2 centerScreen = projected.get(0);
            final List<Point2> verticesScreen =
                    projected.size() > 1 ? projected.subList(1, projected.size()) : List.of();
            overlay.updatePreview(centerScreen, verticesScreen, plan.edgePairs());
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            overlay.clearPreview();
        }
    }

    private void schedulePreviewRefresh() {
        if (!operational() || !previewActive) return;
        SwingUtilities.invokeLater(() -> {
            if (operational() && previewActive) {
                refreshOverlayPreview();
            }
        });
    }

    private void commitCurrentGeometry() {
        if (!operational() || anchor == null) return;
        try {
            final GeometricMeshShape.LayoutPlan plan = currentConfig.toLayoutPlan(anchor.x(), anchor.y());
            final List<Integer> addedIds = host.commitPlan(plan.verticesXY(), plan.edgePairs());
            host.selectVertices(addedIds);
            previewActive = false;
            overlay.clearPreview();
            if (dialog != null) {
                dialog.hide();
            }
            if (component != null) {
                component.repaint();
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private void cancelAndDeactivate() {
        draggingAnchor = false;
        previewActive = false;
        overlay.clearPreview();
        if (dialog != null) {
            dialog.hide();
        }
        host.deactivate();
    }

    @Override
    public int radiusPixels() {
        requireCurrent();
        return radiusPixels;
    }

    @Override
    public void setRadiusPixels(final int value) {
        requireCurrent();
        if (value < MIN_RADIUS_PIXELS || value > MAX_RADIUS_PIXELS) {
            throw new IllegalArgumentException("radiusPixels must be within [8, 128]");
        }
        this.radiusPixels = value;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        runOnEdtEventually(this::cleanupOnEdt);
    }

    JComponent overlayForTests() {
        return overlay;
    }

    JPanel dialogPanelForTests() {
        return dialog == null ? null : dialog.panelForTests();
    }

    Point2 anchorForTests() {
        return anchor;
    }

    private boolean dispatchKeyEvent(final KeyEvent event) {
        if ((event.getKeyCode() != KeyEvent.VK_ESCAPE && event.getKeyCode() != KeyEvent.VK_SPACE)
                || keyboard == null
                || component == null) return false;
        final Window window = SwingUtilities.getWindowAncestor(component);
        final Window focused = keyboard.getFocusedWindow();
        final boolean relevantWindow =
                window != null && (focused == window || (focused != null && focused.getOwner() == window));
        if (!relevantWindow || !overlay.isShowing() || !operational()) {
            navigationPressed = false;
            return false;
        }
        if (event.getKeyCode() == KeyEvent.VK_SPACE) {
            if (event.getID() == KeyEvent.KEY_PRESSED) navigationPressed = true;
            if (event.getID() == KeyEvent.KEY_RELEASED) navigationPressed = false;
            return false;
        }
        if (event.getID() == KeyEvent.KEY_PRESSED) {
            event.consume();
            escapePressed = true;
            return true;
        }
        if (event.getID() == KeyEvent.KEY_RELEASED && escapePressed) {
            event.consume();
            escapePressed = false;
            escapeAction.actionPerformed(null);
            return true;
        }
        return false;
    }

    private boolean operational() {
        if (closed.get() || !installed) return false;
        try {
            return host.revalidate();
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    private boolean capturesInputAt(final int x, final int y) {
        if (!operational()) return false;
        if (navigationPressed && !draggingAnchor) return false;
        try {
            return !host.nativeControlAt(x, y);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("geometric mesh layout is closed");
    }

    private void requireCurrent() {
        requireOpen();
        if (!host.revalidate()) throw new IllegalStateException("geometric-mesh-layout activation lease is stale");
    }

    private void positionOverlay() {
        if (!installed || component == null) return;
        if (component.getWidth() <= 0 || component.getHeight() <= 0) {
            if (positionRetries < MAX_POSITION_RETRIES) {
                positionRetries++;
                SwingUtilities.invokeLater(this::positionOverlay);
            }
            return;
        }
        keepTopmost();
        if (overlay.getX() == 0
                && overlay.getY() == 0
                && overlay.getWidth() == component.getWidth()
                && overlay.getHeight() == component.getHeight()) return;
        overlay.setBounds(0, 0, component.getWidth(), component.getHeight());
        overlay.repaint();
    }

    private void keepTopmost() {
        if (!installed || component == null || overlay.getParent() != component) return;
        if (component.getComponentZOrder(overlay) != 0) {
            component.setComponentZOrder(overlay, 0);
        }
    }

    private void forward(final MouseEvent event) {
        if (forwarding || component == null) return;
        final Component target = underlyingComponentAt(component, event.getX(), event.getY());
        if (target == null) return;
        final Point converted = SwingUtilities.convertPoint(overlay, event.getPoint(), target);
        final MouseEvent copy = event instanceof MouseWheelEvent wheelEvent
                ? new MouseWheelEvent(
                        target,
                        wheelEvent.getID(),
                        wheelEvent.getWhen(),
                        wheelEvent.getModifiersEx(),
                        converted.x,
                        converted.y,
                        wheelEvent.getXOnScreen(),
                        wheelEvent.getYOnScreen(),
                        wheelEvent.getClickCount(),
                        wheelEvent.isPopupTrigger(),
                        wheelEvent.getScrollType(),
                        wheelEvent.getScrollAmount(),
                        wheelEvent.getWheelRotation(),
                        wheelEvent.getPreciseWheelRotation())
                : new MouseEvent(
                        target,
                        event.getID(),
                        event.getWhen(),
                        event.getModifiersEx(),
                        converted.x,
                        converted.y,
                        event.getXOnScreen(),
                        event.getYOnScreen(),
                        event.getClickCount(),
                        event.isPopupTrigger(),
                        event.getButton());
        forwarding = true;
        try {
            target.dispatchEvent(copy);
        } finally {
            forwarding = false;
        }
    }

    private Component underlyingComponentAt(final Container container, final int x, final int y) {
        final int count = container.getComponentCount();
        for (int index = 0; index < count; index++) {
            final Component child = container.getComponent(index);
            if (child == overlay || !child.isVisible()) continue;
            final int childX = x - child.getX();
            final int childY = y - child.getY();
            if (!child.contains(childX, childY)) continue;
            if (child instanceof Container nested) {
                final Component deepest = underlyingComponentAt(nested, childX, childY);
                if (deepest != null) return deepest;
            }
            return child;
        }
        return container == overlay ? null : container;
    }

    private void cleanupOnEdt() {
        if (!installed) return;
        installed = false;
        draggingAnchor = false;
        previewActive = false;
        escapePressed = false;
        navigationPressed = false;
        overlay.clearPreview();
        overlay.removeMouseListener(mouse);
        overlay.removeMouseMotionListener(motion);
        overlay.removeMouseWheelListener(wheel);
        if (keyboard != null) {
            keyboard.removeKeyEventDispatcher(keyboardDispatcher);
            keyboard = null;
        }
        final InputMap inputMap = overlay.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        final ActionMap actionMap = overlay.getActionMap();
        if (previousEscapeBinding == null) inputMap.remove(ESCAPE);
        else inputMap.put(ESCAPE, previousEscapeBinding);
        if (previousEscapeAction == null) actionMap.remove(ESCAPE_ACTION_KEY);
        else actionMap.put(ESCAPE_ACTION_KEY, previousEscapeAction);
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
        }
        final JComponent target = component;
        component = null;
        if (target != null) {
            target.removeComponentListener(bounds);
            target.removeContainerListener(children);
            if (overlay.getParent() == target) {
                target.remove(overlay);
                target.repaint();
            }
        }
    }

    private static void runOnEdt(final Runnable action) {
        EdtDispatch.call("geometric-mesh-layout EDT install", () -> {
            action.run();
            return null;
        });
    }

    private static void runOnEdtEventually(final Runnable action) {
        EdtDispatch.runEventually("geometric-mesh-layout EDT cleanup", action);
    }

    interface Host {
        JComponent component();

        default boolean nativeControlAt(final int x, final int y) {
            return false;
        }

        Point2 defaultAnchor();

        Point2 unprojectToMesh(float componentX, float componentY);

        List<Point2> projectPositions(float[] meshPositions);

        List<Integer> commitPlan(float[] meshPositions, int[] edgePairs);

        default void selectVertices(final List<Integer> indices) {}

        boolean revalidate();

        void deactivate();
    }
}
