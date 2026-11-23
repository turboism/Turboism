package dev.turboism.adapter.cubism.optimization.drag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.EventQueue;
import java.awt.Image;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behavioral unit tests for AWT drag coalescing, drag-scoped UI deferral, and same-cursor elision. */
final class EditDragResponsivenessTest {

    private String priorEnable;
    private String priorValidation;
    private RecordingHostBindings host;
    private FifoEventQueue queue;
    private AtomicLong nowNanos;
    private EditDragResponsivenessBridge bridge;

    @BeforeEach
    void setUp() {
        priorEnable = System.getProperty(EditDragResponsivenessBridge.ENABLE_PROPERTY);
        priorValidation = System.getProperty(EditDragResponsivenessBridge.VALIDATION_PROPERTY);
        System.setProperty(EditDragResponsivenessBridge.ENABLE_PROPERTY, "true");
        host = new RecordingHostBindings();
        queue = new FifoEventQueue();
        nowNanos = new AtomicLong(1_000_000L);
        bridge = new EditDragResponsivenessBridge(host, () -> queue, nowNanos::get);
        bridge.install(true);
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) {
            bridge.close();
        }
        restoreProperty(EditDragResponsivenessBridge.ENABLE_PROPERTY, priorEnable);
        restoreProperty(EditDragResponsivenessBridge.VALIDATION_PROPERTY, priorValidation);
        for (final String key : new String[] {
            EditDragResponsivenessBridge.PRESS_PROPERTY,
            EditDragResponsivenessBridge.DRAG_PROPERTY,
            EditDragResponsivenessBridge.RELEASE_BEGIN_PROPERTY,
            EditDragResponsivenessBridge.RELEASE_END_PROPERTY,
            EditDragResponsivenessBridge.DEFER_INSPECTOR_PROPERTY,
            EditDragResponsivenessBridge.DEFER_INSPECTOR_APPLY_PROPERTY,
            EditDragResponsivenessBridge.DEFER_DEFORMER_SELECTOR_PROPERTY,
            EditDragResponsivenessBridge.DEFER_DEFORMER_PALETTE_PROPERTY,
            EditDragResponsivenessBridge.CURSOR_PROPERTY,
            EditDragResponsivenessBridge.STATS_PROPERTY
        }) {
            assertNull(System.getProperties().get(key), key);
        }
    }

    @Test
    void coalescesOnlyContiguousMatchingMouseDraggedAndPreservesReleaseOrdering() throws Exception {
        final Object canvasListener = new Object();
        final JPanel canvas = new JPanel();
        final JPanel otherComponent = new JPanel();
        host.canvasListener = canvasListener;
        host.openDragSession = true;

        final MouseEvent drag1 = dragEvent(canvas, 10, 10, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
        final MouseEvent drag2 = dragEvent(canvas, 20, 20, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
        final MouseEvent drag3 = dragEvent(canvas, 30, 30, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
        final MouseEvent release = new MouseEvent(
                canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, 30, 30, 1, false, MouseEvent.BUTTON1);
        final MouseEvent afterReleaseDrag = dragEvent(canvas, 40, 40, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);

        queue.postEvent(drag2);
        queue.postEvent(drag3);
        queue.postEvent(release);
        queue.postEvent(afterReleaseDrag);

        assertTrue(bridge.shouldCoalesceDrag(canvasListener, drag1));
        assertEquals(drag2, queue.getNextEvent());
        assertTrue(bridge.shouldCoalesceDrag(canvasListener, drag2));
        assertEquals(drag3, queue.getNextEvent());
        // The next queued event is MOUSE_RELEASED, so drag3 must run rather than crossing release.
        assertFalse(bridge.shouldCoalesceDrag(canvasListener, drag3));
        assertEquals(release, queue.getNextEvent());
        assertEquals(afterReleaseDrag, queue.getNextEvent());

        // Different modifiers, keys, or components block coalescing cleanly.
        final MouseEvent shiftedDrag = dragEvent(
                canvas, 50, 50, InputEvent.BUTTON1_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, MouseEvent.BUTTON1);
        queue.postEvent(shiftedDrag);
        assertFalse(bridge.shouldCoalesceDrag(canvasListener, drag3));
        assertEquals(shiftedDrag, queue.getNextEvent());

        queue.postEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_A, 'a'));
        queue.postEvent(drag2);
        assertFalse(bridge.shouldCoalesceDrag(canvasListener, drag1));
        drainQueue(queue);

        queue.postEvent(dragEvent(otherComponent, 60, 60, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1));
        assertFalse(bridge.shouldCoalesceDrag(canvasListener, drag1));
        drainQueue(queue);

        // Missing initial press session lets the host synthesize mousePressed instead of skipping.
        host.openDragSession = false;
        queue.postEvent(drag2);
        assertFalse(bridge.shouldCoalesceDrag(canvasListener, drag1));
    }

    @Test
    void defersInspectorAndDeformerTreeDuringCanvasDragAndFlushesOnceOnRelease() {
        final Object canvasListener = new Object();
        final Object sliderListener = new Object();
        host.canvasListener = canvasListener;

        final Object inspectorCtrl = new Object();
        final Object inspectorPanel = new Object();
        final Object selectorFactory = new Object();
        final Object deformerCtrl = new Object();

        // Non-drag edits execute immediately.
        assertFalse(bridge.shouldDeferInspector(inspectorCtrl));
        assertFalse(bridge.shouldDeferDeformerPalette(deformerCtrl, 0));

        // Non-canvas widget drags do not activate canvas drag suppression.
        final JPanel sliderComp = new JPanel();
        final MouseEvent sliderPress =
                new MouseEvent(sliderComp, MouseEvent.MOUSE_PRESSED, 1L, InputEvent.BUTTON1_DOWN_MASK, 0, 0, 1, false);
        bridge.onMousePressed(sliderListener, sliderPress);
        assertFalse(bridge.isCanvasDragging());
        assertFalse(bridge.shouldDeferInspector(inspectorCtrl));

        // Canvas drag suppresses redundant updates and coalesces deformer palette flags.
        final JPanel canvasComp = new JPanel();
        final MouseEvent press = new MouseEvent(
                canvasComp, MouseEvent.MOUSE_PRESSED, 2L, InputEvent.BUTTON1_DOWN_MASK, 10, 10, 1, false);
        final MouseEvent drag = dragEvent(canvasComp, 25, 25, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
        final MouseEvent release = new MouseEvent(canvasComp, MouseEvent.MOUSE_RELEASED, 3L, 0, 25, 25, 1, false);

        bridge.onMousePressed(canvasListener, press);
        assertTrue(bridge.isCanvasDragging());
        bridge.shouldCoalesceDrag(canvasListener, drag);

        assertTrue(bridge.shouldDeferInspector(inspectorCtrl));
        assertTrue(bridge.shouldDeferInspector(inspectorCtrl));
        assertTrue(bridge.shouldDeferInspectorApply(inspectorPanel));
        assertTrue(bridge.shouldDeferDeformerSelector(selectorFactory));
        assertTrue(bridge.shouldDeferDeformerPalette(deformerCtrl, 1));
        assertTrue(bridge.shouldDeferDeformerPalette(deformerCtrl, 2));
        assertEquals(0, host.inspectorCtrlUpdates.size());
        assertEquals(0, host.deformerUpdates.size());

        // Release clears dragging BEFORE body runs, then flushes remaining deferred updates once.
        bridge.onMouseReleasedBegin(canvasListener, release);
        assertFalse(bridge.isCanvasDragging());
        bridge.onMouseReleasedEnd(canvasListener, release);

        assertEquals(List.of("ctrl:0"), host.inspectorCtrlUpdates);
        assertEquals(List.of(), host.inspectorPanelUpdates);
        assertEquals(List.of(), host.selectorUpdates);
        assertEquals(List.of("sync=true,skip=false"), host.deformerUpdates);

        // If release handler itself updates inspector, flush does not duplicate it.
        bridge.onMousePressed(canvasListener, press);
        assertTrue(bridge.shouldDeferInspector(inspectorCtrl));
        assertTrue(bridge.shouldDeferDeformerSelector(selectorFactory));
        bridge.onMouseReleasedBegin(canvasListener, release);
        assertFalse(bridge.shouldDeferInspector(inspectorCtrl));
        bridge.onMouseReleasedEnd(canvasListener, release);
        assertEquals(1, host.inspectorCtrlUpdates.size());
        assertEquals(List.of("updated"), host.selectorUpdates);
    }

    @Test
    void safetyTimeoutClearsCanvasDragWhenReleaseIsMissing() {
        final Object canvasListener = new Object();
        host.canvasListener = canvasListener;
        bridge.onMousePressed(canvasListener, new Object());
        assertTrue(bridge.isCanvasDragging());
        nowNanos.addAndGet(35_000_000_000L);
        assertFalse(bridge.isCanvasDragging());
    }

    @Test
    void elidesIdenticalCursorsAndRetainsChangedOrCustomCursors() {
        final Object widget = new Object();
        final JPanel component = new JPanel();
        host.widgetComponent = component;

        final Cursor crosshair = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
        final Cursor hand = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);

        assertTrue(bridge.shouldElideCursor(widget, null));
        host.cursorToReturn = crosshair;
        assertFalse(bridge.shouldElideCursor(widget, new Object()));

        component.setCursor(crosshair);
        assertTrue(bridge.shouldElideCursor(widget, new Object()));

        host.cursorToReturn = new Cursor(Cursor.CROSSHAIR_CURSOR);
        assertTrue(bridge.shouldElideCursor(widget, new Object()));

        host.cursorToReturn = hand;
        assertFalse(bridge.shouldElideCursor(widget, new Object()));

        final Image img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final Cursor customA = Toolkit.getDefaultToolkit().createCustomCursor(img, new Point(0, 0), "custom");
        final Cursor customB = Toolkit.getDefaultToolkit().createCustomCursor(img, new Point(0, 0), "custom");
        component.setCursor(customA);
        host.cursorToReturn = customA;
        assertTrue(bridge.shouldElideCursor(widget, new Object()));
        host.cursorToReturn = customB;
        assertFalse(bridge.shouldElideCursor(widget, new Object()));

        assertFalse(bridge.shouldElideCursor(widget, null));
    }

    private static MouseEvent dragEvent(
            final Component source, final int x, final int y, final int modifiersEx, final int button) {
        return new MouseEvent(
                source, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(), modifiersEx, x, y, 1, false, button);
    }

    private static void drainQueue(final FifoEventQueue eventQueue) {
        while (eventQueue.peekEvent() != null) {
            eventQueue.getNextEvent();
        }
    }

    private static void restoreProperty(final String key, final String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class FifoEventQueue extends EventQueue {
        private final Deque<AWTEvent> events = new ArrayDeque<>();

        @Override
        public void postEvent(final AWTEvent event) {
            events.addLast(event);
        }

        @Override
        public AWTEvent peekEvent() {
            return events.peekFirst();
        }

        @Override
        public AWTEvent getNextEvent() {
            return events.pollFirst();
        }
    }

    private static final class RecordingHostBindings implements EditDragResponsivenessBridge.HostBindings {

        Object canvasListener;
        boolean openDragSession;
        Component widgetComponent;
        Cursor cursorToReturn;
        final List<String> inspectorCtrlUpdates = new ArrayList<>();
        final List<String> inspectorPanelUpdates = new ArrayList<>();
        final List<String> selectorUpdates = new ArrayList<>();
        final List<String> deformerUpdates = new ArrayList<>();

        @Override
        public boolean isCanvasListener(final Object listener) {
            return listener != null && listener == canvasListener;
        }

        @Override
        public boolean hasOpenDragSession(final Object listener) {
            return openDragSession;
        }

        @Override
        public Component componentOfWidget(final Object widget) {
            return widgetComponent;
        }

        @Override
        public Cursor cursorOfPack(final Object packCursor) {
            return cursorToReturn;
        }

        @Override
        public void updateInspectorCtrl(final Object ctrl, final int delay) {
            inspectorCtrlUpdates.add("ctrl:" + delay);
        }

        @Override
        public void updateInspectorPanel(final Object panel, final int delay) {
            inspectorPanelUpdates.add("panel:" + delay);
        }

        @Override
        public void updateDeformerSelector(final Object selector) {
            selectorUpdates.add("updated");
        }

        @Override
        public void updateDeformerCtrl(final Object ctrl, final boolean syncChildren, final boolean skipExpand) {
            deformerUpdates.add("sync=" + syncChildren + ",skip=" + skipExpand);
        }
    }
}
