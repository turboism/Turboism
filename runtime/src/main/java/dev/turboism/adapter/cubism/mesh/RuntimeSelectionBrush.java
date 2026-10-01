package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.mesh.MeshBrush;
import dev.turboism.sdk.cubism.mesh.MeshEditor;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import dev.turboism.sdk.cubism.model.Point2;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/** Runtime-owned selection brush for one exact custom-tool activation lease. */
final class RuntimeSelectionBrush implements MeshBrush {
    private static final int MAX_POSITION_RETRIES = 20;
    private static final String ESCAPE_ACTION_KEY = "turboism:mesh-selection-brush-deactivate";
    private static final KeyStroke ESCAPE = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);

    private final Host host;
    private final BrushOverlayPanel overlay = new BrushOverlayPanel();
    private final BrushStrokeAccumulator stroke = new BrushStrokeAccumulator();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile int radiusPixels = 32;
    private SelectionMode lockedStrokeMode = SelectionMode.ADD;
    private boolean projectionFailureReported;
    private boolean strokeFailed;
    private int positionRetries;
    private boolean installed;
    private boolean forwarding;
    private int lastX;
    private int lastY;
    private JComponent component;
    private Object previousEscapeBinding;
    private Action previousEscapeAction;

    private final AbstractAction escapeAction = new AbstractAction() {
        @Override
        public void actionPerformed(final ActionEvent event) {
            if (!operational()) return;
            try {
                cancelStroke();
                host.deactivate();
            } catch (Throwable ignored) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
                // Swing host ingress must never observe plugin/runtime callback failure.
            }
        }
    };

    private final MouseAdapter mouse = new MouseAdapter() {
        @Override
        public void mousePressed(final MouseEvent event) {
            if (event.getButton() == MouseEvent.BUTTON1) {
                event.consume();
                primaryPress(event);
            } else {
                forward(event);
            }
        }

        @Override
        public void mouseReleased(final MouseEvent event) {
            if (event.getButton() == MouseEvent.BUTTON1) {
                event.consume();
                primaryRelease();
            } else {
                forward(event);
            }
        }

        @Override
        public void mouseClicked(final MouseEvent event) {
            if (event.getButton() == MouseEvent.BUTTON1) event.consume();
            else forward(event);
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
            if (stroke.active() || (event.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0) {
                event.consume();
                primaryDrag(event);
            } else {
                forward(event);
            }
        }

        @Override
        public void mouseMoved(final MouseEvent event) {
            forward(event);
        }
    };

    private final MouseWheelListener wheel = this::forward;

    private final ComponentAdapter bounds = new ComponentAdapter() {
        @Override
        public void componentMoved(final ComponentEvent event) {
            positionOverlay();
        }

        @Override
        public void componentResized(final ComponentEvent event) {
            positionOverlay();
        }
    };

    private final ContainerAdapter children = new ContainerAdapter() {
        @Override
        public void componentAdded(final ContainerEvent event) {
            keepTopmost();
        }
    };

    RuntimeSelectionBrush(final Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Resolves the stroke mode a contributing tool declares for the exact press-time modifiers.
     *
     * <p>The runtime keeps no modifier policy of its own. A tool that declares nothing returns the
     * additive framework default, which preserves the behaviour of tools written before this
     * contract existed.</p>
     */
    static SelectionMode strokeModeFor(
            final MeshTool tool, final boolean shiftDown, final boolean controlDown, final boolean altDown) {
        final SelectionMode mode =
                Objects.requireNonNull(tool, "tool").strokeSelectionMode(shiftDown, controlDown, altDown);
        return mode == null ? SelectionMode.ADD : mode;
    }

    static RuntimeSelectionBrush production(
            final MeshEditor editor,
            final MeshToolCoordinator.ActivationLease lease,
            final Runnable deactivate,
            final MeshTool tool,
            final dev.turboism.permissions.PermissionChecker permissions) {
        Objects.requireNonNull(editor, "editor");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(deactivate, "deactivate");
        lease.requireCurrent();
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(permissions, "permissions");
        if (!(lease.session() instanceof NativeMeshToolSession session)) {
            throw new IllegalStateException("selection brush requires an exact native mesh-tool session");
        }
        final VerifiedMemberResolver resolver = session.resolver();
        final Object rawComponent = session.identity().component();
        if (resolver == null || !(rawComponent instanceof JComponent component)) {
            throw new IllegalStateException("selection brush exact component or resolver is unavailable");
        }
        return new RuntimeSelectionBrush(new Host() {
            @Override
            public JComponent component() {
                lease.requireCurrent();
                return component;
            }

            @Override
            public List<Point2> projectedVertices() {
                lease.requireCurrent();
                permissions.check(
                        dev.turboism.sdk.permission.PermissionIds.TURBOISM_CUBISM_MODEL_READ,
                        "mesh.brush.vertex-positions");
                // The editable mesh's GL positions are the exact canvas-space vertex positions the
                // native mesh editor hit-tests and renders. The borrowed editor CModel is not a Core
                // CubismModel, so the Core evaluated join cannot supply them.
                return MeshVertexProjector.project(
                        glPositions(resolver, session.identity().editableMesh()),
                        pointCount(resolver, session.identity().editableMesh()),
                        (x, y) -> project(resolver, session.identity().camera(), x, y));
            }

            @Override
            public void commitSelection(final List<Integer> indices, final SelectionMode mode) {
                lease.requireCurrent();
                editor.select(new VertexSelection(indices), mode);
            }

            @Override
            public SelectionMode selectionMode(
                    final boolean shiftDown, final boolean controlDown, final boolean altDown) {
                return strokeModeFor(tool, shiftDown, controlDown, altDown);
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
        if (!host.revalidate()) throw new IllegalStateException("mesh-tool activation lease is stale");
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
                target.add(overlay);
                keepTopmost();
                positionOverlay();
                // Deliberately no `revalidate()` here: the modeling-view container has a layout
                // manager, and a layout pass would overwrite the overlay bounds the brush just set,
                // leaving a correctly registered overlay that can never receive input. The reviewed
                // legacy controller attaches its overlay the same way, without revalidating.
                target.repaint();
            } catch (RuntimeException | Error failure) {
                cleanupOnEdt();
                throw failure;
            }
        });
    }

    @Override
    public int radiusPixels() {
        requireCurrent();
        return radiusPixels;
    }

    @Override
    public void setRadiusPixels(final int radiusPixels) {
        requireCurrent();
        if (radiusPixels < MIN_RADIUS_PIXELS || radiusPixels > MAX_RADIUS_PIXELS) {
            throw new IllegalArgumentException("radiusPixels must be within [8, 128]");
        }
        this.radiusPixels = radiusPixels;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        runOnEdt(this::cleanupOnEdt);
    }

    JComponent overlayForTests() {
        return overlay;
    }

    boolean previewVisibleForTests() {
        return overlay.previewVisible();
    }

    private void primaryPress(final MouseEvent event) {
        if (!operational()) return;
        final int snapshotRadius = radiusPixels;
        lastX = event.getX();
        lastY = event.getY();
        // The stroke and its preview are started first and independently of the mesh data: the
        // overlay radius is measured in component pixels, so it needs no vertex positions. Starting
        // the preview only after a successful projection meant any projection failure cancelled the
        // whole stroke, so the operator saw no trail at all and no reason for it.
        stroke.press(snapshotRadius);
        projectionFailureReported = false;
        strokeFailed = false;
        overlay.begin(lastX, lastY, snapshotRadius);

        try {
            final SelectionMode snapshotMode = host.selectionMode(
                    (event.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0,
                    (event.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0,
                    (event.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0);
            lockedStrokeMode = snapshotMode == null ? SelectionMode.ADD : snapshotMode;
            final List<Point2> vertices = host.projectedVertices();
            stroke.addHits(BrushHitTest.indicesInCircle(vertices, lastX, lastY, snapshotRadius));
            traceInput("press", event);
        } catch (Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            strokeFailed = true;
            // Hit testing is best effort; the stroke keeps its preview and simply commits nothing.
            reportProjectionFailure("press", failure);
        }
    }

    private void primaryDrag(final MouseEvent event) {
        if (!stroke.active()) return;
        if (!operational()) {
            cancelStroke();
            return;
        }
        final int x = event.getX();
        final int y = event.getY();
        final int snapshotRadius = stroke.radiusPixels();
        // Extend the preview first for the same reason as the press: the trail must follow the
        // pointer even when the mesh data cannot be consulted.
        overlay.append(x, y);
        try {
            stroke.addHits(
                    BrushHitTest.indicesWithinStroke(host.projectedVertices(), lastX, lastY, x, y, snapshotRadius));
        } catch (Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            strokeFailed = true;
            reportProjectionFailure("drag", failure);
        }
        lastX = x;
        lastY = y;
        traceInput("drag", event);
    }

    private void traceInput(final String phase, final MouseEvent event) {
        if ("selection-brush".equals(System.getProperty("turboism.meshEditValidation.mode"))) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "selection-brush",
                    "input phase=" + phase + " x=" + event.getX() + " y=" + event.getY()
                            + " modifiers=" + event.getModifiersEx() + " radius=" + stroke.radiusPixels()
                            + " mode=" + lockedStrokeMode);
        }
    }

    /**
     * Reports one bounded projection failure per stroke.
     *
     * <p>Hit testing must never break the interaction, but a silent failure is what let an earlier
     * defect present as "the overlay does nothing". Only the phase, the exception class, and a
     * bounded message are disclosed, and at most once per stroke.</p>
     */
    private void reportProjectionFailure(final String phase, final Throwable failure) {
        if (projectionFailureReported) return;
        projectionFailureReported = true;
        try {
            final String detail = failure.getMessage();
            dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                    "selection-brush",
                    "phase=" + phase + " stroke rejected failure="
                            + failure.getClass().getSimpleName() + " detail="
                            + (detail == null || detail.isBlank()
                                    ? "none"
                                    : detail.length() <= 160 ? detail : detail.substring(0, 160)));
        } catch (Throwable ignored) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
            // Diagnostics must never affect input handling.
        }
    }

    private void primaryRelease() {
        final List<Integer> hits = stroke.release();
        if ("selection-brush".equals(System.getProperty("turboism.meshEditValidation.mode"))) {
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "selection-brush",
                    "input phase=release hitCount=" + hits.size() + " mode=" + lockedStrokeMode + " failed="
                            + strokeFailed);
        }
        try {
            if (!strokeFailed && !hits.isEmpty() && operational()) host.commitSelection(hits, lockedStrokeMode);
        } catch (Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            reportProjectionFailure("commit", failure);
            // Selection failure is contained at the runtime-owned host input boundary.
        } finally {
            overlay.clearTrail();
            strokeFailed = false;
        }
    }

    private void cancelStroke() {
        stroke.cancel();
        overlay.clearTrail();
    }

    private boolean operational() {
        if (closed.get() || !installed) return false;
        try {
            return host.revalidate();
        } catch (Throwable ignored) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("mesh brush is closed");
    }

    private void requireCurrent() {
        requireOpen();
        if (!host.revalidate()) throw new IllegalStateException("mesh-tool activation lease is stale");
    }

    private void positionOverlay() {
        if (!installed || component == null) return;
        if (component.getWidth() <= 0 || component.getHeight() <= 0) {
            // The component can still be unlaid-out when the tool is activated; retry a bounded
            // number of times on the event queue instead of leaving a zero-sized overlay behind.
            if (positionRetries < MAX_POSITION_RETRIES) {
                positionRetries++;
                SwingUtilities.invokeLater(this::positionOverlay);
            }
            return;
        }
        keepTopmost();
        // The overlay also constrains native layout requests synchronously. Avoid unnecessary
        // component events when the view size has not changed.
        if (overlay.getX() == 0
                && overlay.getY() == 0
                && overlay.getWidth() == component.getWidth()
                && overlay.getHeight() == component.getHeight()) return;
        overlay.setBounds(0, 0, component.getWidth(), component.getHeight());
        overlay.repaint();
        reportOverlayGeometry("position");
    }

    /**
     * Records the overlay's real placement.
     *
     * <p>Whether the brush receives pointer input depends on placement, not on activation: a
     * zero-sized overlay, an overlay that is not showing, or a heavyweight sibling that paints and
     * takes input above it all produce the same user-visible symptom as "the tool does nothing".
     * {@code atCentre} names the component that would actually receive a mouse press at the
     * overlay's centre, and {@code lightweight} says whether that component is a Swing (lightweight)
     * component, because a heavyweight AWT canvas there removes both the preview and the input.</p>
     */
    private void reportOverlayGeometry(final String phase) {
        try {
            final java.awt.Container parent = overlay.getParent();
            final java.awt.Component atCentre = component == null
                    ? null
                    : component.getComponentAt(
                            Math.max(0, component.getWidth() / 2), Math.max(0, component.getHeight() / 2));
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "selection-brush",
                    "overlay " + phase
                            + " bounds=" + overlay.getWidth() + "x" + overlay.getHeight()
                            + " showing=" + overlay.isShowing()
                            + " visible=" + overlay.isVisible()
                            + " z=" + (parent == null ? "-1" : parent.getComponentZOrder(overlay))
                            + " onTop=" + (overlay == atCentre)
                            + " component="
                            + (component == null
                                    ? "none"
                                    : component.getClass().getName() + "@" + component.getWidth() + "x"
                                            + component.getHeight())
                            + " atCentre="
                            + (atCentre == null ? "none" : atCentre.getClass().getName())
                            + " lightweight="
                            + (atCentre == null ? "unknown" : String.valueOf(atCentre.isLightweight())));
        } catch (Throwable ignored) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(ignored);
            // Diagnostics must never affect input handling.
        }
    }

    private void keepTopmost() {
        if (!installed || component == null || overlay.getParent() != component) return;
        if (component.getComponentZOrder(overlay) != 0) component.setComponentZOrder(overlay, 0);
    }

    private void cleanupOnEdt() {
        stroke.cancel();
        overlay.clearTrail();
        overlay.removeMouseListener(mouse);
        overlay.removeMouseMotionListener(motion);
        overlay.removeMouseWheelListener(wheel);
        final InputMap inputMap = overlay.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        if (Objects.equals(inputMap.get(ESCAPE), ESCAPE_ACTION_KEY)) {
            if (previousEscapeBinding == null) inputMap.remove(ESCAPE);
            else inputMap.put(ESCAPE, previousEscapeBinding);
        }
        final ActionMap actionMap = overlay.getActionMap();
        if (actionMap.get(ESCAPE_ACTION_KEY) == escapeAction) {
            if (previousEscapeAction == null) actionMap.remove(ESCAPE_ACTION_KEY);
            else actionMap.put(ESCAPE_ACTION_KEY, previousEscapeAction);
        }
        final JComponent target = component;
        installed = false;
        component = null;
        if (target != null) {
            target.removeComponentListener(bounds);
            target.removeContainerListener(children);
        }
        if (overlay.getParent() != null) {
            overlay.getParent().remove(overlay);
            if (target != null) {
                target.revalidate();
                target.repaint();
            }
        }
    }

    private void forward(final MouseWheelEvent event) {
        if (forwarding || component == null) return;
        forwarding = true;
        try {
            final Point point = SwingUtilities.convertPoint(overlay, event.getPoint(), component);
            event.consume();
            component.dispatchEvent(new MouseWheelEvent(
                    component,
                    event.getID(),
                    event.getWhen(),
                    event.getModifiersEx(),
                    point.x,
                    point.y,
                    event.getClickCount(),
                    event.isPopupTrigger(),
                    event.getScrollType(),
                    event.getScrollAmount(),
                    event.getWheelRotation()));
        } finally {
            forwarding = false;
        }
    }

    private void forward(final MouseEvent event) {
        if (forwarding || component == null) return;
        forwarding = true;
        try {
            final Point point = SwingUtilities.convertPoint(overlay, event.getPoint(), component);
            event.consume();
            component.dispatchEvent(new MouseEvent(
                    component,
                    event.getID(),
                    event.getWhen(),
                    event.getModifiersEx(),
                    point.x,
                    point.y,
                    event.getClickCount(),
                    event.isPopupTrigger(),
                    event.getButton()));
        } finally {
            forwarding = false;
        }
    }

    private static float[] glPositions(final VerifiedMemberResolver resolver, final Object editableMesh) {
        final Object value = resolver.invoke(MeshToolSessionSelectorContract.EDITABLE_MESH_GL_POSITIONS, editableMesh);
        if (!(value instanceof float[] positions) || (positions.length & 1) != 0) {
            throw new IllegalStateException("native editable-mesh vertex positions are unavailable");
        }
        return positions;
    }

    private static int pointCount(final VerifiedMemberResolver resolver, final Object editableMesh) {
        final Object value = resolver.invoke(MeshToolSessionSelectorContract.EDITABLE_MESH_POINT_COUNT, editableMesh);
        if (!(value instanceof Number number) || number.intValue() < 0) {
            throw new IllegalStateException("native editable-mesh point count is unavailable");
        }
        return number.intValue();
    }

    private static Point2 project(
            final VerifiedMemberResolver resolver, final Object camera, final float x, final float y) {
        final Object documentPoint = resolver.construct(MeshToolSessionSelectorContract.VECTOR_CREATE, x, y);
        final Object componentPoint =
                resolver.invoke(MeshToolSessionSelectorContract.CAMERA_DOCUMENT_TO_COMPONENT, camera, documentPoint);
        final Object rawX = resolver.invoke(MeshToolSessionSelectorContract.VECTOR_X, componentPoint);
        final Object rawY = resolver.invoke(MeshToolSessionSelectorContract.VECTOR_Y, componentPoint);
        if (!(rawX instanceof Number componentX) || !(rawY instanceof Number componentY)) {
            throw new IllegalStateException("camera projection coordinates are unavailable");
        }
        return new Point2(componentX.floatValue(), componentY.floatValue());
    }

    private static void runOnEdt(final Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while updating the selection brush UI", failure);
        } catch (InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Selection brush UI update failed", cause);
        }
    }

    interface Host {
        JComponent component();

        List<Point2> projectedVertices();

        void commitSelection(List<Integer> indices, SelectionMode mode);

        SelectionMode selectionMode(boolean shiftDown, boolean controlDown, boolean altDown);

        boolean revalidate();

        void deactivate();
    }
}
