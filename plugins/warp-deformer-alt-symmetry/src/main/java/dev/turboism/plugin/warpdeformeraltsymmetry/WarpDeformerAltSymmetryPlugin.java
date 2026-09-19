package dev.turboism.plugin.warpdeformeraltsymmetry;

import dev.turboism.plugin.warpdeformeraltsymmetry.service.AltAxisMirrorPlanner;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Extends the native bounding-box Alt symmetric semantics to Warp Deformer
 * control points.
 *
 * <p>Evidence from the exact-host validation probe (host-validation jobs
 * {@code b83d397d} PASS, {@code 54a01dbf}) established that native viewport
 * drags never route through the {@code DeformerHooks} grid-replace family,
 * while the SDK {@code replaceGrid} write path round-trips, fires the hook
 * family and joins native Undo/Redo. Disassembly of the reviewed 5.3.03 host
 * further located the native drag-tick dispatcher
 * ({@code temporaryHandler.a.b}), which Turboism now instruments with the
 * {@code WarpAltMirror} bridge: while that hook is active the mirroring happens
 * inside the native drag tick itself, giving a live mirrored preview and a
 * single undo entry. This plugin's AWT-level diff path remains armed as a
 * fail-closed fallback for hosts where the hook is not admitted:</p>
 *
 * <ol>
 *   <li>Mouse press with Alt held: snapshot every Warp Deformer grid of the
 *       active model. Alt alone selects the vertical grid axis, Alt+Shift the
 *       horizontal grid axis (v1 product decision).</li>
 *   <li>Mouse release: diff each grid. Every moved point's axis counterpart
 *       receives the displacement with the mirrored component negated, and the
 *       corrected grid is committed through {@link WarpDeformer#replaceGrid}.</li>
 * </ol>
 *
 * <p>The diff-based ingress needs no canvas-to-grid projection and is a no-op
 * whenever the gesture did not move Warp Deformer control points (other tools,
 * other object kinds, plain view panning).</p>
 */
public final class WarpDeformerAltSymmetryPlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;
    private AWTEventListener listener;
    private int armedAxis;

    private final AtomicBoolean applying = new AtomicBoolean(false);

    /** One Warp Deformer grid captured on mouse press. */
    private record GridSnapshot(String id, int rows, int columns, List<Point2> points) {
    }

    private record ArmedGesture(AltAxisMirrorPlanner.Axis axis, List<GridSnapshot> snapshots) {
    }

    private volatile ArmedGesture armed;

    @Override
    public void init(final PluginContext pluginContext) {
        this.context = pluginContext;
        this.logger = pluginContext.logger();
        this.listener = this::onAwtEvent;
    }

    @Override
    public void enable() {
        // Participate so the reviewed native point-write hook mirrors live with a
        // single undo entry. The mirror axis is toggled by keyboard (the native
        // editor consumes Alt+drag before control-point drags can start, so the
        // axis cannot be held-modifier driven): Ctrl+Alt+V vertical, Ctrl+Alt+H
        // horizontal, Ctrl+Alt+O off. The AWT-level diff fallback stays armed for
        // the press path when the hook is not active.
        try {
            context.disposableScope().register(context.warpAltMirrorParticipation().participate());
        } catch (RuntimeException | Error unsupported) {
            logger.warn("warpAltMirrorParticipation unavailable: "
                + unsupported.getClass().getSimpleName());
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(
            listener, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);
        contributeStripButton();
        logger.info("Warp deformer Alt axis-symmetry installed:"
            + " Ctrl+Alt+V vertical mirror, Ctrl+Alt+H horizontal, Ctrl+Alt+O off");
    }

    private static final String AXIS_BUTTON_ID = "warp-deformer-alt-symmetry.axis";

    /** Contributes the mirror-axis state buttons into the canvas-top GL strip. */
    private void contributeStripButton() {
        try {
            final dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry registry =
                context.viewContextMenu();
            final Map<Integer, BufferedImage> icons = new java.util.LinkedHashMap<>();
            icons.put(1, stateIcon("Vertical.png"));
            icons.put(2, stateIcon("Horizon.png"));
            icons.put(0, stateIcon("Off.png"));
            registry.contributeStateButtons(
                new dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry
                    .StateButtonContribution(
                    AXIS_BUTTON_ID, icons, armedAxis,
                    state -> applyArmedAxis(state)));
            logger.info("Warp deformer Alt axis-symmetry strip buttons contributed");
        } catch (RuntimeException | Error unsupported) {
            logger.warn("viewContextMenu unavailable: "
                + unsupported.getClass().getSimpleName());
        }
    }

    private BufferedImage stateIcon(final String name) {
        try (final var stream = getClass().getResourceAsStream(
            "/META-INF/turboism/icons/" + name)) {
            if (stream == null) {
                logger.warn("mirror icon missing: " + name);
                return new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
            }
            return javax.imageio.ImageIO.read(stream);
        } catch (java.io.IOException failure) {
            logger.warn("mirror icon load failed: " + name);
            return new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
        }
    }

    private void applyArmedAxis(final int axis) {
        armedAxis = axis;
        try {
            context.warpAltMirrorParticipation().setArmedAxis(axis);
            context.viewContextMenu().selectState(AXIS_BUTTON_ID, axis);
        } catch (RuntimeException | Error unsupported) {
            logger.warn("armed-axis publish failed: " + unsupported.getClass().getSimpleName());
            return;
        }
        showArmedHint(axis);
        logger.info("WARP_ALT_AXIS armed="
            + (axis == 1 ? "vertical" : axis == 2 ? "horizontal" : "off"));
    }

    /**
     * Shows/clears the bottom status hint that mirrors the armed axis, in the
     * style of the update-check hint. Armed states keep a resident compact
     * metric label; disarming clears it.
     */
    private void showArmedHint(final int axis) {
        try {
            final var notification = new dev.turboism.sdk.ui.StatusNotification(
                "warp-deformer-alt-symmetry.hint",
                "INFO",
                switch (axis) {
                    case 1 -> context.localization()
                        .text("warp-alt-symmetry.hint.vertical");
                    case 2 -> context.localization()
                        .text("warp-alt-symmetry.hint.horizontal");
                    default -> "";
                },
                dev.turboism.sdk.ui.StatusNotification.Presentation.COMPACT_METRIC);
            context.uiHost().notifyStatus(notification);
        } catch (RuntimeException | Error unsupported) {
            logger.warn("notifyStatus unavailable: " + unsupported.getClass().getSimpleName());
        }
    }

    private String axisText(final int axis) {
        return switch (axis) {
            case 1 -> context.localization().text("warp-alt-symmetry.axis.vertical");
            case 2 -> context.localization().text("warp-alt-symmetry.axis.horizontal");
            default -> context.localization().text("warp-alt-symmetry.axis.off");
        };
    }

    @Override
    public void disable() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
        armed = null;
        logger.info("Warp deformer Alt axis-symmetry listener removed");
    }

    @Override
    public void shutdown() {
        disable();
    }

    private void onAwtEvent(final AWTEvent event) {
        if (event instanceof final java.awt.event.KeyEvent keyEvent
            && event.getID() == java.awt.event.KeyEvent.KEY_PRESSED
            && keyEvent.isControlDown()
            && keyEvent.isAltDown()
            && !keyEvent.isShiftDown()) {
            handleToggle(keyEvent);
            return;
        }
        if (!(event instanceof final MouseEvent mouseEvent)) {
            return;
        }
        try {
            if (mouseEvent.getID() == MouseEvent.MOUSE_PRESSED) {
                onPress(mouseEvent);
            } else if (mouseEvent.getID() == MouseEvent.MOUSE_RELEASED) {
                onRelease(mouseEvent);
            }
        } catch (RuntimeException | Error failure) {
            armed = null;
            logger.warn("WARP_ALT_MIRROR gesture handling failed: "
                + failure.getClass().getSimpleName());
        }
    }

    private void onPress(final MouseEvent event) {
        final boolean alt = (event.getModifiersEx() & MouseEvent.ALT_DOWN_MASK) != 0;
        final boolean shift = (event.getModifiersEx() & MouseEvent.SHIFT_DOWN_MASK) != 0;
        if (event.getButton() != MouseEvent.BUTTON1 || !alt) {
            return;
        }
        if (nativeMirrorActive()) {
            logger.info("WARP_ALT_FB press alt=" + alt + " shift=" + shift
                + " -> deferred to native hook");
            return; // the reviewed native drag-tick hook owns the mirroring.
        }
        logger.info("WARP_ALT_FB press alt=" + alt + " shift=" + shift
            + " -> fallback armed (native inactive)");
        final AltAxisMirrorPlanner.Axis axis =
            (event.getModifiersEx() & MouseEvent.SHIFT_DOWN_MASK) != 0
                ? AltAxisMirrorPlanner.Axis.HORIZONTAL
                : AltAxisMirrorPlanner.Axis.VERTICAL;
        final List<GridSnapshot> snapshots = snapshotWarps();
        armed = snapshots.isEmpty() ? null : new ArmedGesture(axis, snapshots);
    }

    private void onRelease(final MouseEvent event) {
        if (event.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        final ArmedGesture gesture = armed;
        armed = null;
        if (gesture == null) {
            return;
        }
        if (!applying.compareAndSet(false, true)) {
            return;
        }
        try {
            applyMirror(gesture);
        } catch (RuntimeException | Error failure) {
            logger.warn("WARP_ALT_FB release failed: " + failure.getClass().getSimpleName());
            throw failure;
        } finally {
            applying.set(false);
        }
    }

    private void handleToggle(final java.awt.event.KeyEvent keyEvent) {
        final int axis;
        switch (keyEvent.getKeyCode()) {
            case java.awt.event.KeyEvent.VK_V -> axis = 1;
            case java.awt.event.KeyEvent.VK_H -> axis = 2;
            case java.awt.event.KeyEvent.VK_O -> axis = 0;
            default -> {
                return;
            }
        }
        keyEvent.consume();
        applyArmedAxis(axis);
    }

    private boolean nativeMirrorActive() {
        try {
            return context.warpAltMirrorParticipation().nativeMirrorActive();
        } catch (RuntimeException | Error unsupported) {
            return false;
        }
    }

    private List<GridSnapshot> snapshotWarps() {
        final CubismModel model = context.cubism().model().active();
        final List<GridSnapshot> snapshots = new ArrayList<>();
        for (WarpDeformer warp : model.warpDeformers().all()) {
            final WarpGrid grid = warp.grid();
            if (!grid.controlPoints().isEmpty()) {
                snapshots.add(new GridSnapshot(
                    warp.id().value(), grid.rows(), grid.columns(),
                    List.copyOf(grid.controlPoints())));
            }
        }
        return snapshots;
    }

    private void applyMirror(final ArmedGesture gesture) {
        final CubismModel model = context.cubism().model().active();
        boolean anyApplied = false;
        for (WarpDeformer warp : model.warpDeformers().all()) {
            final GridSnapshot snapshot = findSnapshot(gesture.snapshots(), warp.id().value());
            if (snapshot == null) {
                continue;
            }
            final WarpGrid after = warp.grid();
            if (after.rows() != snapshot.rows()
                || after.columns() != snapshot.columns()
                || after.controlPoints().size() != snapshot.points().size()) {
                continue;
            }
            final Map<Integer, Point2> assignments = AltAxisMirrorPlanner.planMirror(
                snapshot.rows(), snapshot.columns(),
                snapshot.points(), after.controlPoints(), gesture.axis());
            if (assignments.isEmpty()) {
                logger.info("WARP_ALT_FB release deformer=" + warp.id().value()
                    + " axis=" + gesture.axis() + " -> no counterpart motion to apply");
                continue;
            }
            WarpGrid mirrored = after;
            for (final Map.Entry<Integer, Point2> entry : assignments.entrySet()) {
                mirrored = mirrored.withControlPoint(
                    entry.getKey(), entry.getValue().x(), entry.getValue().y());
            }
            warp.replaceGrid(mirrored);
            anyApplied = true;
            logger.info("WARP_ALT_MIRROR applied deformer=" + warp.id().value()
                + " axis=" + gesture.axis()
                + " mirrored=" + assignments.size());
        }
        if (anyApplied) {
            logger.info("WARP_ALT_MIRROR committed; undo needs two steps for v1");
        }
    }

    private static GridSnapshot findSnapshot(
        final List<GridSnapshot> snapshots, final String id
    ) {
        for (GridSnapshot snapshot : snapshots) {
            if (snapshot.id().equals(id)) {
                return snapshot;
            }
        }
        return null;
    }
}
