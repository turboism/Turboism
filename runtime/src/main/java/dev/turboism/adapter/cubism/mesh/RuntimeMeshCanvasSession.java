package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshCanvasSession;
import dev.turboism.sdk.cubism.mesh.MeshEditor;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * Runtime-owned canvas point-and-edge session bound to one mesh-tool activation.
 *
 * <p>This is the host half of the capability: it installs the canvas overlay, routes the primary
 * button to anchor placement while forwarding every other gesture to the surrounding mesh editor,
 * keeps the overlay topmost and sized to its owner, revalidates the activation lease on every
 * entry point, and commits a plugin-computed layout inside the host's own Undo group. The geometry
 * itself — shapes, parameters, dialog and commit policy — belongs to the plugin.</p>
 */
final class RuntimeMeshCanvasSession implements MeshCanvasSession {
    private static final int MAX_POSITION_RETRIES = 20;
    private static final String ESCAPE_ACTION_KEY = "turboism:mesh-canvas-deactivate";
    private static final KeyStroke ESCAPE = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);

    private final Host host;
    private final MeshCanvasOverlayPanel overlay;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final List<Consumer<Point2>> anchorListeners = new CopyOnWriteArrayList<>();
    private volatile Preview preview;
    private Point2 anchor;
    private boolean draggingAnchor;
    private boolean installed;
    private boolean forwarding;
    private int positionRetries;
    private JComponent component;
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
                deactivate();
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

    RuntimeMeshCanvasSession(final Host host) {
        this.host = Objects.requireNonNull(host, "host");
        this.overlay = new MeshCanvasOverlayPanel(this::capturesInputAt);
    }

    /**
     * Fail-closed admission for the runtime canvas session: the declared capability plus the read
     * and write permissions the session actually exercises. Capability admission has already
     * happened upstream — {@code cubism.mesh.custom-tools} is required to register any mesh tool
     * and {@code cubism.mesh.point-and-edge-layout} to reach this session — so this gate adds the
     * per-surface authorization the capability declaration alone lacks.
     *
     * @param pointAndEdgeLayoutGranted whether the activating plugin declared the capability
     * @param permissions the activating plugin's permission checker
     * @throws IllegalStateException when the activating plugin never declared the capability
     * @throws dev.turboism.sdk.permission.CubismPermissionException when a required grant is missing
     */
    static void requireOwner(final boolean pointAndEdgeLayoutGranted, final PermissionChecker permissions) {
        Objects.requireNonNull(permissions, "permissions");
        if (!pointAndEdgeLayoutGranted) {
            throw new IllegalStateException("mesh canvas session requires the "
                    + RuntimeMeshToolRegistry.POINT_AND_EDGE_LAYOUT_CAPABILITY + " capability");
        }
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, "mesh.point-and-edge-layout.activate");
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "mesh.point-and-edge-layout.activate");
    }

    static RuntimeMeshCanvasSession production(
            final MeshEditor editor,
            final MeshToolCoordinator.ActivationLease lease,
            final Runnable deactivate,
            final MeshTool tool,
            final PermissionChecker permissions,
            final boolean pointAndEdgeLayoutGranted) {
        Objects.requireNonNull(editor, "editor");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(deactivate, "deactivate");
        lease.requireCurrent();
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(permissions, "permissions");
        requireOwner(pointAndEdgeLayoutGranted, permissions);
        if (!(lease.session() instanceof NativeMeshToolSession session)) {
            throw new IllegalStateException("mesh canvas session requires an exact native mesh-tool session");
        }
        final VerifiedMemberResolver resolver = session.resolver();
        final Object rawComponent = session.identity().component();
        if (resolver == null || !(rawComponent instanceof JComponent component)) {
            throw new IllegalStateException("mesh canvas session exact component or resolver is unavailable");
        }
        NativeMeshMirrorBridge.disarmNativePointAddPreview(resolver.hostClassLoader());
        return new RuntimeMeshCanvasSession(new Host() {
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
                permissions.check(
                        PermissionIds.TURBOISM_CUBISM_MODEL_READ, "mesh.point-and-edge-layout.default-anchor");
                return NativeMeshVertexProjector.defaultMeshCenter(resolver, session.identity());
            }

            @Override
            public Point2 unprojectToMesh(final float componentX, final float componentY) {
                lease.requireCurrent();
                permissions.check(
                        PermissionIds.TURBOISM_CUBISM_MODEL_READ, "mesh.point-and-edge-layout.unproject-anchor");
                return NativeMeshVertexProjector.unprojectToMesh(resolver, session.identity(), componentX, componentY);
            }

            @Override
            public List<Point2> projectPositions(final float[] meshPositions) {
                lease.requireCurrent();
                permissions.check(
                        PermissionIds.TURBOISM_CUBISM_MODEL_READ, "mesh.point-and-edge-layout.project-preview");
                return NativeMeshVertexProjector.projectPositions(resolver, session.identity(), meshPositions);
            }

            @Override
            public List<Integer> commitPlan(final float[] meshPositions, final int[] edgePairs) {
                lease.requireCurrent();
                permissions.check(
                        PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "mesh.point-and-edge-layout.commit-points-edges");
                try {
                    return NativeMeshMirrorBridge.addGeometricPointsAndEdges(
                            session.identity().mode(),
                            session.identity().editableMesh(),
                            session.identity().completePack(),
                            tool.label(),
                            meshPositions,
                            edgePairs);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("failed to commit mesh canvas points and edges", failure);
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
        if (!host.revalidate()) throw new IllegalStateException("mesh canvas session activation lease is stale");
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
                initializeAnchor();
                target.repaint();
            } catch (RuntimeException | Error failure) {
                cleanupOnEdt();
                throw failure;
            }
        });
    }

    private void initializeAnchor() {
        try {
            anchor = host.defaultAnchor();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            anchor = new Point2(0.0f, 0.0f);
        }
    }

    @Override
    public Point2 anchor() {
        requireCurrent();
        return anchor;
    }

    @Override
    public Registration observeAnchorMoved(final Consumer<Point2> listener) {
        Objects.requireNonNull(listener, "listener");
        requireCurrent();
        final Consumer<Point2> observed = nextAnchor -> {
            if (closed.get()) return;
            listener.accept(nextAnchor);
        };
        anchorListeners.add(observed);
        return () -> anchorListeners.remove(observed);
    }

    @Override
    public void showPreview(final List<Point2> vertices, final int[] edgePairs) {
        requireCurrent();
        final Preview next = preview(vertices, edgePairs);
        this.preview = next;
        if (SwingUtilities.isEventDispatchThread()) {
            refreshOverlayPreview();
        } else {
            EdtDispatch.post("mesh canvas preview", this::refreshOverlayPreview);
        }
    }

    @Override
    public void clearPreview() {
        requireCurrent();
        this.preview = null;
        if (SwingUtilities.isEventDispatchThread()) {
            overlay.clearPreview();
        } else {
            EdtDispatch.post("mesh canvas preview clear", overlay::clearPreview);
        }
    }

    @Override
    public List<Integer> commit(final List<Point2> vertices, final int[] edgePairs) {
        requireCurrent();
        final Preview layout = preview(vertices, edgePairs);
        return EdtDispatch.call("mesh canvas commit", () -> {
            requireCurrent();
            final List<Integer> added = host.commitPlan(flatten(layout.vertices()), layout.edgePairs());
            host.selectVertices(added);
            preview = null;
            overlay.clearPreview();
            if (component != null) component.repaint();
            return added;
        });
    }

    @Override
    public void deactivate() {
        if (closed.get()) return;
        preview = null;
        overlay.clearPreview();
        host.deactivate();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        anchorListeners.clear();
        runOnEdtEventually(this::cleanupOnEdt);
    }

    JComponent overlayForTests() {
        return overlay;
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
                for (Consumer<Point2> listener : anchorListeners) {
                    try {
                        listener.accept(nextAnchor);
                    } catch (Throwable ignored) {
                        FatalErrors.rethrowIfFatal(ignored);
                    }
                }
                refreshOverlayPreview();
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private void refreshOverlayPreview() {
        final Preview current = preview;
        if (!operational() || current == null || anchor == null) {
            overlay.clearPreview();
            return;
        }
        try {
            final List<Point2> vertices = current.vertices();
            final float[] combined = new float[2 + vertices.size() * 2];
            combined[0] = anchor.x();
            combined[1] = anchor.y();
            for (int index = 0; index < vertices.size(); index++) {
                final Point2 vertex = vertices.get(index);
                combined[2 + index * 2] = vertex.x();
                combined[3 + index * 2] = vertex.y();
            }
            final List<Point2> projected = host.projectPositions(combined);
            if (projected.isEmpty()) {
                overlay.clearPreview();
                return;
            }
            final Point2 centerScreen = projected.get(0);
            final List<Point2> verticesScreen =
                    projected.size() > 1 ? projected.subList(1, projected.size()) : List.of();
            overlay.updatePreview(centerScreen, verticesScreen, current.edgePairs());
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            overlay.clearPreview();
        }
    }

    private void schedulePreviewRefresh() {
        if (!operational() || preview == null) return;
        SwingUtilities.invokeLater(() -> {
            if (operational() && preview != null) {
                refreshOverlayPreview();
            }
        });
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
        if (closed.get()) throw new IllegalStateException("mesh canvas session is closed");
    }

    private void requireCurrent() {
        requireOpen();
        if (!host.revalidate()) throw new IllegalStateException("mesh canvas session activation lease is stale");
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
        escapePressed = false;
        navigationPressed = false;
        preview = null;
        anchorListeners.clear();
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

    private static Preview preview(final List<Point2> vertices, final int[] edgePairs) {
        final List<Point2> points = List.copyOf(Objects.requireNonNull(vertices, "vertices"));
        Objects.requireNonNull(edgePairs, "edgePairs");
        if ((edgePairs.length & 1) != 0) {
            throw new IllegalArgumentException("edge pairs must have even length");
        }
        final int[] edges = edgePairs.clone();
        for (int element : edges) {
            if (element < 0 || element >= points.size()) {
                throw new IllegalArgumentException("edge pair names a vertex outside the layout");
            }
        }
        return new Preview(points, edges);
    }

    private static float[] flatten(final List<Point2> vertices) {
        final float[] positions = new float[vertices.size() * 2];
        for (int index = 0; index < vertices.size(); index++) {
            final Point2 vertex = vertices.get(index);
            positions[index * 2] = vertex.x();
            positions[index * 2 + 1] = vertex.y();
        }
        return positions;
    }

    private static void runOnEdt(final Runnable action) {
        EdtDispatch.call("mesh canvas EDT install", () -> {
            action.run();
            return null;
        });
    }

    private static void runOnEdtEventually(final Runnable action) {
        EdtDispatch.runEventually("mesh canvas EDT cleanup", action);
    }

    /** One validated mesh-space preview layout, immutable except for the edge array copy it owns. */
    private record Preview(List<Point2> vertices, int[] edgePairs) {
        private Preview {
            vertices = List.copyOf(vertices);
            edgePairs = edgePairs.clone();
        }

        @Override
        public int[] edgePairs() {
            return edgePairs.clone();
        }
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
