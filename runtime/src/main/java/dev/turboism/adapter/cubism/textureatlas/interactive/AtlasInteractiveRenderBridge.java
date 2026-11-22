package dev.turboism.adapter.cubism.textureatlas.interactive;

import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.Timer;

/**
 * Coordinates interactive Texture Atlas Editor canvas rendering, drag UI coalescing, and camera
 * zoom/pan elisions across reviewed Cubism releases.
 *
 * <p>Woven host classes consult only JDK functional interfaces published in
 * {@link System#getProperties()} slots so host bytecode carries zero direct Turboism references
 * and restores cleanly when closed.</p>
 */
public final class AtlasInteractiveRenderBridge implements AutoCloseable {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = AtlasInteractiveRenderTarget.ENABLE_PROPERTY;

    /** Slot property for overlay shape rendering ({@code Predicate<Object[]>}). */
    public static final String OVERLAY_RENDER_PROPERTY = "turboism.atlas-interactive.overlay-render";

    /** Slot property for eliding redundant 500 ms trailing canvas repaints ({@code Predicate<Object>}). */
    public static final String ELIDE_TRAILING_REPAINT_PROPERTY =
            "turboism.atlas-interactive.elide-trailing-repaint";

    /** Slot property for viewport-clipping background atlas draws ({@code BiPredicate<Object, Object>}). */
    public static final String CLIP_BG_PROPERTY = "turboism.atlas-interactive.clip-bg";

    /** Slot property for entering drag-sync coalescing ({@code Predicate<Object>}). */
    public static final String ENTER_DRAG_SYNC_PROPERTY = "turboism.atlas-interactive.enter-drag-sync";

    /** Slot property for exiting drag-sync coalescing ({@code Predicate<Object>}). */
    public static final String EXIT_DRAG_SYNC_PROPERTY = "turboism.atlas-interactive.exit-drag-sync";

    /** Slot property querying whether active drag suppresses transform round-trips ({@code Predicate<Object>}). */
    public static final String SKIP_DRAG_TRANSFORM_PROPERTY =
            "turboism.atlas-interactive.skip-drag-transform";

    /** Slot property querying whether active drag suppresses deferred icon refreshes ({@code Predicate<Object>}). */
    public static final String SKIP_DRAG_ICON_PROPERTY = "turboism.atlas-interactive.skip-drag-icon";

    /** Slot property querying whether active drag skips full table rebuilds ({@code Predicate<Object>}). */
    public static final String SKIP_DRAG_TABLE_PROPERTY = "turboism.atlas-interactive.skip-drag-table";

    /** Slot property throttling/deduplicating inspector text updates ({@code BiPredicate<Object, Object>}). */
    public static final String APPLY_TEXT_PROPERTY = "turboism.atlas-interactive.apply-text";

    /** Slot property gating wheel zoom Robot warps ({@code Predicate<Object[]>}). */
    public static final String WHEEL_ZERO_ROBOT_PROPERTY = "turboism.atlas-interactive.wheel-zero-robot";

    /** Slot property deferring zoom updates on non-showing linked tabs ({@code Predicate<Object[]>}). */
    public static final String DEFER_LINKED_ZOOM_PROPERTY = "turboism.atlas-interactive.defer-linked-zoom";

    /** Slot property targeting viewport canvas repaints during scroll pan ({@code Predicate<Object>}). */
    public static final String REPAINT_VIEWPORT_ONLY_PROPERTY = "turboism.atlas-interactive.repaint-viewport-only";

    /** Slot property deferring pan updates on non-showing linked tabs ({@code Predicate<Object[]>}). */
    public static final String DEFER_LINKED_PAN_PROPERTY = "turboism.atlas-interactive.defer-linked-pan";

    /** Slot property exposing diagnostic counters ({@code Supplier<Map<String, Long>>}). */
    public static final String STATS_PROPERTY = "turboism.atlas-interactive.stats";

    private static final int OVERLAY_ARG_COUNT = 4;
    private static final int WHEEL_ARG_COUNT = 4;
    private static final int LINKED_ZOOM_ARG_COUNT = 3;
    private static final int LINKED_PAN_ARG_COUNT = 2;

    private static final long DRAG_TEXT_MIN_INTERVAL_NANOS = 50_000_000L;
    private static final long ACTIVE_WHEEL_ZOOM_WINDOW_NANOS = 150_000_000L;
    private static final int WHEEL_SETTLE_DELAY_MS = 140;
    private static final double ZOOM_HALF_DIVISOR = 2.0d;
    private static final double MIN_ZOOM_SCALE = 1.0e-6d;
    private static final int AXIS_ALIGNED_TRANSFORM_MASK =
            AffineTransform.TYPE_GENERAL_TRANSFORM | AffineTransform.TYPE_GENERAL_ROTATION | AffineTransform.TYPE_QUADRANT_ROTATION;

    private static final String INSTALLED_TWICE_MESSAGE = "atlas interactive render bridge already installed";
    private static final String SLOTS_OCCUPIED_MESSAGE = "atlas interactive render bridge slots occupied";

    private final AtlasInteractiveRenderHostAccess host;
    private final AtlasInteractiveRenderSpriteCache spriteCache = new AtlasInteractiveRenderSpriteCache();
    private final AtomicBoolean active = new AtomicBoolean();
    private final Object statsLock = new Object();
    private final Object cameraLock = new Object();
    private final ThreadLocal<Deque<DragFrame>> dragStack = ThreadLocal.withInitial(ArrayDeque::new);
    private final Map<Object, PendingCameraSync> pendingCameras =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Predicate<Object[]> overlayRenderCallback = this::renderOverlay;
    private final Predicate<Object> elideTrailingRepaintCallback = this::shouldElideTrailingRepaint;
    private final BiPredicate<Object, Object> clipBackgroundCallback = this::clipBackgroundDraw;
    private final Predicate<Object> enterDragSyncCallback = this::enterDragSync;
    private final Predicate<Object> exitDragSyncCallback = this::exitDragSync;
    private final Predicate<Object> skipDragTransformCallback = this::shouldSkipDragTransform;
    private final Predicate<Object> skipDragIconCallback = this::shouldSkipDragIcon;
    private final Predicate<Object> skipDragTableCallback = this::shouldSkipDragTable;
    private final BiPredicate<Object, Object> applyTextElideCallback = this::shouldElideTextUpdate;
    private final Predicate<Object[]> wheelRobotElideCallback = this::shouldElideWheelRobot;
    private final Predicate<Object[]> deferLinkedZoomCallback = this::shouldDeferLinkedZoom;
    private final Predicate<Object> repaintViewportOnlyCallback = this::repaintViewportOnly;
    private final Predicate<Object[]> deferLinkedPanCallback = this::shouldDeferLinkedPan;
    private final Supplier<Map<String, Long>> statsSupplier = this::snapshot;

    private final AtlasInteractiveRenderSpriteCache.RenderMetrics spriteMetrics =
            new AtlasInteractiveRenderSpriteCache.RenderMetrics();

    private Properties installedProperties;
    private volatile long lastWheelZoomNanos;
    private volatile long lastDragTextUpdateNanos;
    private WeakReference<JComponent> lastZoomCanvas = new WeakReference<>(null);
    private Timer zoomSettleTimer;

    private long overlayCalls;
    private long overlayFallbacks;
    private long trailingRepaintsElided;
    private long backgroundDrawsClipped;
    private long backgroundDrawsSkipped;
    private long dragTableSyncsSkipped;
    private long dragTransformsSkipped;
    private long dragIconsSkipped;
    private long dragTextUpdatesElided;
    private long robotWarpsElided;
    private long linkedZoomsDeferred;
    private long linkedPansDeferred;
    private long linkedFlushes;
    private long viewportRepaintsTargeted;
    private long failures;

    /**
     * Resolves required host handles for the admitted Cubism build.
     *
     * @param loader admitted host class loader
     * @param target reviewed build contract
     * @throws ReflectiveOperationException when any host member is absent
     */
    public AtlasInteractiveRenderBridge(final ClassLoader loader, final AtlasInteractiveRenderTarget target)
            throws ReflectiveOperationException {
        this.host = new AtlasInteractiveRenderHostAccess(
                Objects.requireNonNull(loader, "loader"), Objects.requireNonNull(target, "target"));
    }

    /** Publishes all slot callbacks into {@link System#getProperties()}. */
    public synchronized void install() {
        if (active.get()) {
            throw new IllegalStateException(INSTALLED_TWICE_MESSAGE);
        }
        final Properties properties = System.getProperties();
        if (anySlotOccupied(properties)) {
            throw new IllegalStateException(SLOTS_OCCUPIED_MESSAGE);
        }
        properties.put(OVERLAY_RENDER_PROPERTY, overlayRenderCallback);
        properties.put(ELIDE_TRAILING_REPAINT_PROPERTY, elideTrailingRepaintCallback);
        properties.put(CLIP_BG_PROPERTY, clipBackgroundCallback);
        properties.put(ENTER_DRAG_SYNC_PROPERTY, enterDragSyncCallback);
        properties.put(EXIT_DRAG_SYNC_PROPERTY, exitDragSyncCallback);
        properties.put(SKIP_DRAG_TRANSFORM_PROPERTY, skipDragTransformCallback);
        properties.put(SKIP_DRAG_ICON_PROPERTY, skipDragIconCallback);
        properties.put(SKIP_DRAG_TABLE_PROPERTY, skipDragTableCallback);
        properties.put(APPLY_TEXT_PROPERTY, applyTextElideCallback);
        properties.put(WHEEL_ZERO_ROBOT_PROPERTY, wheelRobotElideCallback);
        properties.put(DEFER_LINKED_ZOOM_PROPERTY, deferLinkedZoomCallback);
        properties.put(REPAINT_VIEWPORT_ONLY_PROPERTY, repaintViewportOnlyCallback);
        properties.put(DEFER_LINKED_PAN_PROPERTY, deferLinkedPanCallback);
        properties.put(STATS_PROPERTY, statsSupplier);
        installedProperties = properties;
        active.set(true);
    }

    @Override
    public synchronized void close() {
        active.set(false);
        if (installedProperties != null) {
            removeIfSame(installedProperties, OVERLAY_RENDER_PROPERTY, overlayRenderCallback);
            removeIfSame(installedProperties, ELIDE_TRAILING_REPAINT_PROPERTY, elideTrailingRepaintCallback);
            removeIfSame(installedProperties, CLIP_BG_PROPERTY, clipBackgroundCallback);
            removeIfSame(installedProperties, ENTER_DRAG_SYNC_PROPERTY, enterDragSyncCallback);
            removeIfSame(installedProperties, EXIT_DRAG_SYNC_PROPERTY, exitDragSyncCallback);
            removeIfSame(installedProperties, SKIP_DRAG_TRANSFORM_PROPERTY, skipDragTransformCallback);
            removeIfSame(installedProperties, SKIP_DRAG_ICON_PROPERTY, skipDragIconCallback);
            removeIfSame(installedProperties, SKIP_DRAG_TABLE_PROPERTY, skipDragTableCallback);
            removeIfSame(installedProperties, APPLY_TEXT_PROPERTY, applyTextElideCallback);
            removeIfSame(installedProperties, WHEEL_ZERO_ROBOT_PROPERTY, wheelRobotElideCallback);
            removeIfSame(installedProperties, DEFER_LINKED_ZOOM_PROPERTY, deferLinkedZoomCallback);
            removeIfSame(installedProperties, REPAINT_VIEWPORT_ONLY_PROPERTY, repaintViewportOnlyCallback);
            removeIfSame(installedProperties, DEFER_LINKED_PAN_PROPERTY, deferLinkedPanCallback);
            removeIfSame(installedProperties, STATS_PROPERTY, statsSupplier);
            installedProperties = null;
        }
        synchronized (cameraLock) {
            if (zoomSettleTimer != null) {
                zoomSettleTimer.stop();
                zoomSettleTimer = null;
            }
            lastZoomCanvas = new WeakReference<>(null);
        }
        pendingCameras.clear();
        spriteCache.clear();
        dragStack.remove();
    }

    /** Returns an immutable snapshot of bridge telemetry counters. */
    public Map<String, Long> snapshot() {
        synchronized (statsLock) {
            final Map<String, Long> map = new LinkedHashMap<>();
            map.put("overlayCalls", overlayCalls);
            map.put("overlaySpriteHits", spriteMetrics.spriteHits);
            map.put("overlaySpriteBuilds", spriteMetrics.spriteBuilds);
            map.put("overlayZoomScaledHits", spriteMetrics.zoomScaledHits);
            map.put("overlayDirectDrawn", spriteMetrics.directDrawn);
            map.put("overlayFrustumCulled", spriteMetrics.frustumCulled);
            map.put("overlayFallbacks", overlayFallbacks);
            map.put("trailingRepaintsElided", trailingRepaintsElided);
            map.put("backgroundDrawsClipped", backgroundDrawsClipped);
            map.put("backgroundDrawsSkipped", backgroundDrawsSkipped);
            map.put("dragTableSyncsSkipped", dragTableSyncsSkipped);
            map.put("dragTransformsSkipped", dragTransformsSkipped);
            map.put("dragIconsSkipped", dragIconsSkipped);
            map.put("dragTextUpdatesElided", dragTextUpdatesElided);
            map.put("robotWarpsElided", robotWarpsElided);
            map.put("linkedZoomsDeferred", linkedZoomsDeferred);
            map.put("linkedPansDeferred", linkedPansDeferred);
            map.put("linkedFlushes", linkedFlushes);
            map.put("viewportRepaintsTargeted", viewportRepaintsTargeted);
            map.put("failures", failures);
            return Collections.unmodifiableMap(map);
        }
    }

    private boolean isEnabled() {
        if (!active.get()) {
            return false;
        }
        final String override = System.getProperty(ENABLE_PROPERTY);
        return override == null || Boolean.parseBoolean(override);
    }

    private boolean renderOverlay(final Object[] args) {
        if (!isEnabled() || args == null || args.length != OVERLAY_ARG_COUNT) {
            return false;
        }
        if (!(args[0] != null
                && args[1] instanceof Graphics2D g2d
                && args[2] instanceof AffineTransform savedTx
                && args[3] instanceof Boolean isIconPass)) {
            return false;
        }
        try {
            final ArrayList<?> shapes = host.drawDataShapes(args[0]);
            if (shapes == null || shapes.isEmpty()) {
                return false;
            }
            final Color fill = host.fillColor(args[0]);
            final Color stroke = host.strokeColor(args[0]);
            final boolean wheelActive =
                    (System.nanoTime() - lastWheelZoomNanos) <= ACTIVE_WHEEL_ZOOM_WINDOW_NANOS;
            synchronized (statsLock) {
                overlayCalls++;
            }
            final boolean handled = spriteCache.renderOverlay(
                    g2d, shapes, savedTx, fill, stroke, isIconPass, wheelActive, spriteMetrics);
            if (!handled) {
                synchronized (statsLock) {
                    overlayFallbacks++;
                }
            }
            return handled;
        } catch (final Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            synchronized (statsLock) {
                failures++;
            }
            return false;
        }
    }

    private boolean shouldElideTrailingRepaint(final Object canvas) {
        if (!isEnabled()) {
            return false;
        }
        synchronized (statsLock) {
            trailingRepaintsElided++;
        }
        return true;
    }

    private boolean clipBackgroundDraw(final Object graphicsObj, final Object imageObj) {
        if (!isEnabled()
                || !(graphicsObj instanceof Graphics2D g2d)
                || !(imageObj instanceof BufferedImage img)) {
            return false;
        }
        try {
            final AffineTransform tx = g2d.getTransform();
            if ((tx.getType() & AXIS_ALIGNED_TRANSFORM_MASK) != 0) {
                return false;
            }
            final Rectangle clip = g2d.getClipBounds();
            if (clip == null) {
                return false;
            }
            final int imgW = img.getWidth();
            final int imgH = img.getHeight();
            final int sx1 = Math.max(0, clip.x - 1);
            final int sy1 = Math.max(0, clip.y - 1);
            final int sx2 = Math.min(imgW, clip.x + clip.width + 1);
            final int sy2 = Math.min(imgH, clip.y + clip.height + 1);
            if (sx2 <= sx1 || sy2 <= sy1) {
                synchronized (statsLock) {
                    backgroundDrawsSkipped++;
                }
                return true;
            }
            if (sx1 == 0 && sy1 == 0 && sx2 == imgW && sy2 == imgH) {
                return false;
            }
            g2d.drawImage(img, sx1, sy1, sx2, sy2, sx1, sy1, sx2, sy2, null);
            synchronized (statsLock) {
                backgroundDrawsClipped++;
            }
            return true;
        } catch (final Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            synchronized (statsLock) {
                failures++;
            }
            return false;
        }
    }

    private boolean enterDragSync(final Object layerUpdateEvent) {
        final boolean enabled = isEnabled();
        final boolean dragging = enabled && layerUpdateEvent != null && host.isEventDragging(layerUpdateEvent);
        boolean allowTextThisPass = true;
        if (dragging) {
            final long now = System.nanoTime();
            if (now - lastDragTextUpdateNanos >= DRAG_TEXT_MIN_INTERVAL_NANOS) {
                lastDragTextUpdateNanos = now;
                allowTextThisPass = true;
            } else {
                allowTextThisPass = false;
            }
        } else {
            lastDragTextUpdateNanos = 0L;
        }
        dragStack.get().push(new DragFrame(dragging, allowTextThisPass));
        return dragging;
    }

    private boolean exitDragSync(final Object ignored) {
        final Deque<DragFrame> stack = dragStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        return true;
    }

    private boolean shouldSkipDragTransform(final Object ignored) {
        if (!isEnabled()) {
            return false;
        }
        final DragFrame frame = dragStack.get().peek();
        if (frame != null && frame.dragging) {
            synchronized (statsLock) {
                dragTransformsSkipped++;
            }
            return true;
        }
        return false;
    }

    private boolean shouldSkipDragIcon(final Object ignored) {
        if (!isEnabled()) {
            return false;
        }
        final DragFrame frame = dragStack.get().peek();
        if (frame != null && frame.dragging) {
            synchronized (statsLock) {
                dragIconsSkipped++;
            }
            return true;
        }
        return false;
    }

    private boolean shouldSkipDragTable(final Object ignored) {
        if (!isEnabled()) {
            return false;
        }
        final DragFrame frame = dragStack.get().peek();
        if (frame != null && frame.dragging) {
            synchronized (statsLock) {
                dragTableSyncsSkipped++;
            }
            return true;
        }
        return false;
    }

    private boolean shouldElideTextUpdate(final Object textFieldObj, final Object textObj) {
        if (!isEnabled() || !(textFieldObj instanceof JTextField field) || !(textObj instanceof String newText)) {
            return false;
        }
        final DragFrame frame = dragStack.get().peek();
        if (frame != null && frame.dragging && !frame.allowTextThisPass) {
            synchronized (statsLock) {
                dragTextUpdatesElided++;
            }
            return true;
        }
        if (newText.equals(field.getText())) {
            synchronized (statsLock) {
                dragTextUpdatesElided++;
            }
            return true;
        }
        return false;
    }

    private boolean shouldElideWheelRobot(final Object[] args) {
        if (!isEnabled() || args == null || args.length != WHEEL_ARG_COUNT) {
            return false;
        }
        if (!(args[1] instanceof Integer dxBox
                && args[2] instanceof Integer dyBox
                && args[3] instanceof Double ratioBox)) {
            return false;
        }
        lastWheelZoomNanos = System.nanoTime();
        scheduleWheelSettleRepaint(args[0]);

        final int dx = dxBox;
        final int dy = dyBox;
        final double ratio = ratioBox;
        final int moveDx = (int) ((dx * ratio) / ZOOM_HALF_DIVISOR);
        final int moveDy = (int) ((dy * ratio) / ZOOM_HALF_DIVISOR);
        if ((dx == 0 && dy == 0) || (moveDx == 0 && moveDy == 0)) {
            synchronized (statsLock) {
                robotWarpsElided++;
            }
            return true;
        }
        return false;
    }

    private void scheduleWheelSettleRepaint(final Object cameraPanel) {
        if (cameraPanel == null) {
            return;
        }
        final JComponent canvas = host.cameraViewportCanvas(cameraPanel);
        if (canvas == null || !canvas.isShowing()) {
            return;
        }
        synchronized (cameraLock) {
            lastZoomCanvas = new WeakReference<>(canvas);
            if (zoomSettleTimer == null) {
                zoomSettleTimer = new Timer(WHEEL_SETTLE_DELAY_MS, ignored -> {
                    final JComponent target;
                    synchronized (cameraLock) {
                        target = lastZoomCanvas.get();
                    }
                    if (target != null && target.isShowing()) {
                        target.repaint();
                    }
                });
                zoomSettleTimer.setRepeats(false);
            }
            zoomSettleTimer.restart();
        }
    }

    private boolean shouldDeferLinkedZoom(final Object[] args) {
        if (!isEnabled() || args == null || args.length != LINKED_ZOOM_ARG_COUNT || args[0] == null) {
            return false;
        }
        if (!(args[1] instanceof Point anchor && args[2] instanceof Double zoom)) {
            return false;
        }
        final Object linkedCamera = args[0];
        final JScrollPane scrollPane = host.cameraScrollPane(linkedCamera);
        if (scrollPane == null || scrollPane.isShowing()) {
            return false;
        }
        final PendingCameraSync pending = acquirePending(linkedCamera, scrollPane);
        pending.hasZoom = true;
        pending.zoomAnchor = new Point(anchor);
        pending.zoomLevel = zoom >= MIN_ZOOM_SCALE ? zoom : pending.zoomLevel;
        pending.hasPan = false;
        synchronized (statsLock) {
            linkedZoomsDeferred++;
        }
        return true;
    }

    private boolean repaintViewportOnly(final Object cameraPanel) {
        if (!isEnabled() || cameraPanel == null) {
            return false;
        }
        final JComponent canvas = host.cameraViewportCanvas(cameraPanel);
        if (canvas == null) {
            return false;
        }
        canvas.repaint();
        synchronized (statsLock) {
            viewportRepaintsTargeted++;
        }
        return true;
    }

    private boolean shouldDeferLinkedPan(final Object[] args) {
        if (!isEnabled() || args == null || args.length != LINKED_PAN_ARG_COUNT || args[0] == null) {
            return false;
        }
        if (!(args[1] instanceof Point viewPos)) {
            return false;
        }
        final Object linkedCamera = args[0];
        final JScrollPane scrollPane = host.cameraScrollPane(linkedCamera);
        if (scrollPane == null || scrollPane.isShowing()) {
            return false;
        }
        final PendingCameraSync pending = acquirePending(linkedCamera, scrollPane);
        pending.hasPan = true;
        pending.panPosition = new Point(viewPos);
        synchronized (statsLock) {
            linkedPansDeferred++;
        }
        return true;
    }

    private PendingCameraSync acquirePending(final Object linkedCamera, final JScrollPane scrollPane) {
        PendingCameraSync pending = pendingCameras.get(linkedCamera);
        if (pending == null) {
            pending = new PendingCameraSync();
            pendingCameras.put(linkedCamera, pending);
            final WeakReference<Object> cameraRef = new WeakReference<>(linkedCamera);
            final WeakReference<JScrollPane> paneRef = new WeakReference<>(scrollPane);
            final PendingCameraSync pendingRef = pending;
            final HierarchyListener listener = event -> {
                if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) {
                    return;
                }
                final JScrollPane pane = paneRef.get();
                final Object cam = cameraRef.get();
                if (pane != null && cam != null && pane.isShowing()) {
                    flushPendingCameraSync(cam, pane, pendingRef);
                }
            };
            scrollPane.addHierarchyListener(listener);
        }
        return pending;
    }

    private void flushPendingCameraSync(
            final Object linkedCamera, final JScrollPane scrollPane, final PendingCameraSync pending) {
        if (!pending.hasZoom && !pending.hasPan) {
            return;
        }
        final boolean hadZoom = pending.hasZoom;
        final Point anchor = pending.zoomAnchor;
        final double zoom = pending.zoomLevel;
        final boolean hadPan = pending.hasPan;
        final Point panPos = pending.panPosition;
        pending.hasZoom = false;
        pending.hasPan = false;

        try {
            if (hadZoom && anchor != null && zoom >= MIN_ZOOM_SCALE) {
                host.applyCameraZoom(linkedCamera, anchor, zoom);
            }
            if (hadPan && panPos != null) {
                final JViewport viewport = scrollPane.getViewport();
                if (viewport != null) {
                    viewport.setViewPosition(panPos);
                }
                scrollPane.repaint();
            }
            synchronized (statsLock) {
                linkedFlushes++;
            }
        } catch (final Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            synchronized (statsLock) {
                failures++;
            }
        }
    }

    private static boolean anySlotOccupied(final Properties properties) {
        return properties.containsKey(OVERLAY_RENDER_PROPERTY)
                || properties.containsKey(ELIDE_TRAILING_REPAINT_PROPERTY)
                || properties.containsKey(CLIP_BG_PROPERTY)
                || properties.containsKey(ENTER_DRAG_SYNC_PROPERTY)
                || properties.containsKey(EXIT_DRAG_SYNC_PROPERTY)
                || properties.containsKey(SKIP_DRAG_TRANSFORM_PROPERTY)
                || properties.containsKey(SKIP_DRAG_ICON_PROPERTY)
                || properties.containsKey(SKIP_DRAG_TABLE_PROPERTY)
                || properties.containsKey(APPLY_TEXT_PROPERTY)
                || properties.containsKey(WHEEL_ZERO_ROBOT_PROPERTY)
                || properties.containsKey(DEFER_LINKED_ZOOM_PROPERTY)
                || properties.containsKey(REPAINT_VIEWPORT_ONLY_PROPERTY)
                || properties.containsKey(DEFER_LINKED_PAN_PROPERTY)
                || properties.containsKey(STATS_PROPERTY);
    }

    private static void removeIfSame(final Properties properties, final String key, final Object expected) {
        if (properties.get(key) == expected) {
            properties.remove(key);
        }
    }

    private record DragFrame(boolean dragging, boolean allowTextThisPass) {}

    private static final class PendingCameraSync {
        boolean hasZoom;
        Point zoomAnchor;
        double zoomLevel = 1.0d;
        boolean hasPan;
        Point panPosition;
    }
}
