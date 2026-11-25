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
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.InvocationEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.PaintEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

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
            EditDragResponsivenessBridge.PRE_RENDER_DRAG_PROPERTY,
            EditDragResponsivenessBridge.REPAINT_ELIDE_PROPERTY,
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
    void coalescesAcrossQueuedInvocationAndPaintEventsUnlessBoundaryIsQueued() {
        final Object canvasListener = new Object();
        final JPanel canvas = new JPanel();
        host.canvasListener = canvasListener;
        host.openDragSession = true;

        final MouseEvent drag1 = dragEvent(canvas, 10, 10, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
        final MouseEvent drag2 = dragEvent(canvas, 20, 20, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
        final InvocationEvent repaintInvoke = new InvocationEvent(canvas, () -> {});
        final PaintEvent repaint = new PaintEvent(canvas, PaintEvent.PAINT, new Rectangle(0, 0, 64, 64));

        // Interleaved InvocationEvent / PaintEvent ahead of newer drag must not block coalescing.
        queue.postEvent(repaintInvoke);
        queue.postEvent(repaint);
        queue.postEvent(drag2);
        assertTrue(bridge.shouldCoalesceDrag(canvasListener, drag1));

        // A queued MOUSE_RELEASED anywhere in the queue blocks coalescing cleanly.
        final MouseEvent release = new MouseEvent(
                canvas, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, 20, 20, 1, false, MouseEvent.BUTTON1);
        queue.postEvent(release);
        assertFalse(bridge.shouldCoalesceDrag(canvasListener, drag1));
        drainQueue(queue);
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

    @Test
    void preRenderDragPullAppliesQueuedDragOnceElidesRepaintAndDeduplicatesEdtDelivery() {
        final Object canvasListener = new Object();
        final Object canvasWidget = new Object();
        final JPanel canvas = new JPanel();
        host.canvasListener = canvasListener;
        host.canvasWidget = canvasWidget;
        host.openDragSession = true;

        final MouseEvent press = new MouseEvent(
                canvas,
                MouseEvent.MOUSE_PRESSED,
                10L,
                InputEvent.BUTTON1_DOWN_MASK,
                10,
                10,
                1,
                false,
                MouseEvent.BUTTON1);
        final MouseEvent drag1 = new MouseEvent(
                canvas, MouseEvent.MOUSE_DRAGGED, 11L, InputEvent.BUTTON1_DOWN_MASK, 20, 20, 1, false, 0);
        final MouseEvent drag2 = new MouseEvent(
                canvas, MouseEvent.MOUSE_DRAGGED, 12L, InputEvent.BUTTON1_DOWN_MASK, 40, 45, 1, false, 0);
        final MouseEvent drag3 = new MouseEvent(
                canvas, MouseEvent.MOUSE_DRAGGED, 13L, InputEvent.BUTTON1_DOWN_MASK, 60, 65, 1, false, 0);

        bridge.onMousePressed(canvasListener, press);
        assertTrue(EditDragResponsivenessBridge.isAnyCanvasDragging());
        assertFalse(bridge.shouldCoalesceDrag(canvasListener, drag1));

        // Frame render runs while drag2 is queued behind an InvocationEvent; pre-render pulls drag2 inline.
        queue.postEvent(new InvocationEvent(canvas, () -> {}));
        queue.postEvent(drag2);
        host.repaintDuringMouseDragged = () -> assertTrue(bridge.shouldElideCanvasRepaint(canvasWidget));

        assertTrue(bridge.pumpLatestDragBeforeRender(canvasWidget));
        assertEquals(List.of(drag2), host.draggedDispatched);
        assertEquals(1L, bridge.snapshot().get("preRenderDragPumped"));
        assertEquals(1L, bridge.snapshot().get("repaintElided"));

        // Outside the pre-render pump, normal canvas repaints are never elided.
        assertFalse(bridge.shouldElideCanvasRepaint(canvasWidget));

        // When EDT later delivers the exact pumped drag2, it is deduplicated with no double-paint.
        assertEquals(InvocationEvent.INVOCATION_DEFAULT, queue.getNextEvent().getID());
        assertEquals(drag2, queue.getNextEvent());
        assertTrue(bridge.shouldCoalesceDrag(canvasListener, drag2));
        assertEquals(1L, bridge.snapshot().get("preRenderDragDeduplicated"));

        // Head drag event is safely drained from queue when pumped before render, while same-timestamp moved drag3
        // still passes.
        final MouseEvent drag2Next = new MouseEvent(
                canvas, MouseEvent.MOUSE_DRAGGED, 13L, InputEvent.BUTTON1_DOWN_MASK, 50, 55, 1, false, 0);
        queue.postEvent(drag2Next);
        assertTrue(bridge.pumpLatestDragBeforeRender(canvasWidget));
        assertNull(queue.peekEvent());
        assertFalse(bridge.shouldCoalesceDrag(canvasListener, drag3));

        final MouseEvent release =
                new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 14L, 0, 60, 65, 1, false, MouseEvent.BUTTON1);
        bridge.onMouseReleasedBegin(canvasListener, release);
        assertFalse(EditDragResponsivenessBridge.isAnyCanvasDragging());
        bridge.onMouseReleasedEnd(canvasListener, release);
    }

    @Test
    void preRenderDragPullStopsOnBoundaryEventsAndPreventsReentrancy() {
        final Object canvasListener = new Object();
        final Object canvasWidget = new Object();
        final JPanel canvas = new JPanel();
        host.canvasListener = canvasListener;
        host.canvasWidget = canvasWidget;
        host.openDragSession = true;

        final MouseEvent press = new MouseEvent(
                canvas,
                MouseEvent.MOUSE_PRESSED,
                20L,
                InputEvent.BUTTON1_DOWN_MASK,
                10,
                10,
                1,
                false,
                MouseEvent.BUTTON1);
        final MouseEvent drag1 = new MouseEvent(
                canvas, MouseEvent.MOUSE_DRAGGED, 21L, InputEvent.BUTTON1_DOWN_MASK, 15, 15, 1, false, 0);
        final MouseEvent drag2 = new MouseEvent(
                canvas, MouseEvent.MOUSE_DRAGGED, 22L, InputEvent.BUTTON1_DOWN_MASK, 35, 35, 1, false, 0);
        final MouseEvent release =
                new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 23L, 0, 35, 35, 1, false, MouseEvent.BUTTON1);

        bridge.onMousePressed(canvasListener, press);
        assertFalse(bridge.shouldCoalesceDrag(canvasListener, drag1));

        // Queued MOUSE_RELEASED blocks pre-render drag pump so release/undo order stays intact.
        queue.postEvent(drag2);
        queue.postEvent(release);
        assertFalse(bridge.pumpLatestDragBeforeRender(canvasWidget));
        assertTrue(host.draggedDispatched.isEmpty());
        drainQueue(queue);

        // Recursive render triggered inside mouseDragged must never re-enter pre-render pump.
        queue.postEvent(drag2);
        host.onMouseDraggedHook = () -> assertFalse(bridge.pumpLatestDragBeforeRender(canvasWidget));
        assertTrue(bridge.pumpLatestDragBeforeRender(canvasWidget));
        assertEquals(1, host.draggedDispatched.size());
    }

    @Test
    void elidesRepaintOnAncestorContainerCWidgetAndIgnoresUnrelatedWidgetsDuringPreRenderPump() {
        final Object canvasListener = new Object();
        final Object canvasWidget = new Object();
        final Object ancestorContainerWidget = new Object();
        final Object unrelatedWidget = new Object();

        final JPanel parentContainer = new JPanel();
        final JPanel canvasPanel = new JPanel();
        final JPanel siblingPanel = new JPanel();
        parentContainer.add(canvasPanel);

        host.canvasListener = canvasListener;
        host.canvasWidget = canvasWidget;
        host.openDragSession = true;
        host.widgetComponents.put(canvasWidget, canvasPanel);
        host.widgetComponents.put(ancestorContainerWidget, parentContainer);
        host.widgetComponents.put(unrelatedWidget, siblingPanel);

        final MouseEvent press = new MouseEvent(
                canvasPanel, MouseEvent.MOUSE_PRESSED, 100L, InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false);
        final MouseEvent drag1 = new MouseEvent(
                canvasPanel, MouseEvent.MOUSE_DRAGGED, 101L, InputEvent.BUTTON1_DOWN_MASK, 10, 10, 1, false, 0);
        final MouseEvent drag2 = new MouseEvent(
                canvasPanel, MouseEvent.MOUSE_DRAGGED, 102L, InputEvent.BUTTON1_DOWN_MASK, 20, 25, 1, false, 0);

        bridge.onMousePressed(canvasListener, press);
        assertFalse(bridge.shouldCoalesceDrag(canvasListener, drag1));

        queue.postEvent(drag2);
        host.repaintDuringMouseDragged = () -> {
            assertTrue(bridge.shouldElideCanvasRepaint(ancestorContainerWidget));
            assertFalse(bridge.shouldElideCanvasRepaint(unrelatedWidget));
        };

        assertTrue(bridge.pumpLatestDragBeforeRender(canvasWidget));
        assertEquals(1L, bridge.snapshot().get("repaintElided"));
        assertFalse(bridge.shouldElideCanvasRepaint(ancestorContainerWidget));
    }

    @Test
    void drainsConsecutiveHeadDragsStopsAtNonDragAndDeduplicatesByMonotonicWhenXY() {
        final Object canvasListener = new Object();
        final Object canvasWidget = new Object();
        final JPanel canvas = new JPanel();
        host.canvasListener = canvasListener;
        host.canvasWidget = canvasWidget;
        host.openDragSession = true;

        final MouseEvent press =
                new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 200L, InputEvent.BUTTON1_DOWN_MASK, 10, 10, 1, false);
        final MouseEvent drag1 = new MouseEvent(
                canvas, MouseEvent.MOUSE_DRAGGED, 201L, InputEvent.BUTTON1_DOWN_MASK, 15, 15, 1, false, 0);
        final MouseEvent drag2 = new MouseEvent(
                canvas, MouseEvent.MOUSE_DRAGGED, 202L, InputEvent.BUTTON1_DOWN_MASK, 20, 20, 1, false, 0);
        final MouseEvent drag3 = new MouseEvent(
                canvas, MouseEvent.MOUSE_DRAGGED, 203L, InputEvent.BUTTON1_DOWN_MASK, 30, 30, 1, false, 0);
        final MouseEvent drag4 = new MouseEvent(
                canvas, MouseEvent.MOUSE_DRAGGED, 204L, InputEvent.BUTTON1_DOWN_MASK, 40, 40, 1, false, 0);
        final InvocationEvent nonDragTail = new InvocationEvent(canvas, () -> {});

        bridge.onMousePressed(canvasListener, press);
        assertFalse(bridge.shouldCoalesceDrag(canvasListener, drag1));

        // Consecutive head drags drain down to drag4 while leaving nonDragTail at queue head.
        queue.postEvent(drag2);
        queue.postEvent(drag3);
        queue.postEvent(drag4);
        queue.postEvent(nonDragTail);

        assertTrue(bridge.pumpLatestDragBeforeRender(canvasWidget));
        assertEquals(List.of(drag4), host.draggedDispatched);
        assertEquals(1L, bridge.snapshot().get("preRenderDragPumped"));
        assertEquals(2L, bridge.snapshot().get("dragCoalesced"));
        assertEquals(nonDragTail, queue.getNextEvent());
        assertNull(queue.peekEvent());

        // Distinct MouseEvent instance with identical (when, x, y) or older timestamp is deduplicated.
        final MouseEvent drag4RetargetedCopy = new MouseEvent(
                canvas, MouseEvent.MOUSE_DRAGGED, 204L, InputEvent.BUTTON1_DOWN_MASK, 40, 40, 1, false, 0);
        final MouseEvent staleOlderDrag = new MouseEvent(
                canvas, MouseEvent.MOUSE_DRAGGED, 203L, InputEvent.BUTTON1_DOWN_MASK, 99, 99, 1, false, 0);
        assertTrue(bridge.shouldCoalesceDrag(canvasListener, drag4RetargetedCopy));
        assertTrue(bridge.shouldCoalesceDrag(canvasListener, staleOlderDrag));
        assertEquals(2L, bridge.snapshot().get("preRenderDragDeduplicated"));

        // Same timestamp with moved coordinates passes cleanly.
        final MouseEvent sameMsMoved = new MouseEvent(
                canvas, MouseEvent.MOUSE_DRAGGED, 204L, InputEvent.BUTTON1_DOWN_MASK, 41, 40, 1, false, 0);
        assertFalse(bridge.shouldCoalesceDrag(canvasListener, sameMsMoved));
    }

    @Test
    void deformerSelectorAcceleratorMaintainsMutationAndVersionParityAcross500To5305() throws Exception {
        bridge.close();
        // 1. 5.0.00-style host: no AppSetting, no formAnimationLock, and combo.setEnabled is never invoked.
        final SyntheticSelectorHost host500 = new SyntheticSelectorHost(false, false);
        try (EditDragResponsivenessBridge v500Bridge = new EditDragResponsivenessBridge(
                host, () -> queue, nowNanos::get, () -> true, DeformerSelectorAccelerator.tryCreate(host500))) {
            v500Bridge.install(true);
            final Object modelSrc = host500.newModelSource();
            final Object alpha = host500.newDeformer("G_A", "ROOT", "Alpha", "D_A", null);
            final Object childA = host500.newDeformer("G_A1", "G_A", "ChildA", "D_A1", null);
            final Object factory = host500.newFactory(modelSrc, List.of(alpha, childA), "G_A", true, false, false);
            assertTrue(v500Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(List.of("[Root]", " Alpha", "  ChildA"), host500.comboLabels(factory));
            assertEquals(0, host500.setEnabledCalls(factory));
            assertEquals(" Alpha", host500.selectedLabel(factory));
            assertTrue(v500Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(1, host500.setModelCalls(factory));
        }

        // 2. 5.0.03-5.2.03-style host: AppSetting showPartName toggle invalidates cleanly, still no setEnabled.
        final SyntheticSelectorHost host520 = new SyntheticSelectorHost(true, false);
        try (EditDragResponsivenessBridge v520Bridge = new EditDragResponsivenessBridge(
                host, () -> queue, nowNanos::get, () -> true, DeformerSelectorAccelerator.tryCreate(host520))) {
            v520Bridge.install(true);
            final Object modelSrc = host520.newModelSource();
            final Object part = host520.newPartSource("HeadPart", "PART_HEAD");
            final Object alpha = host520.newDeformer("G_A", "ROOT", "Alpha", "D_A", part);
            final Object factory = host520.newFactory(modelSrc, List.of(alpha), "ROOT", true, false, false);
            assertTrue(v520Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(List.of("[Root]", " Alpha"), host520.comboLabels(factory));
            host520.setShowPartName(true);
            assertTrue(v520Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(2, host520.setModelCalls(factory));
            assertEquals(List.of("[Root]", " Alpha  [HeadPart]"), host520.comboLabels(factory));
            assertEquals(0, host520.setEnabledCalls(factory));
        }

        // 3. 5.3.00-5.3.05-style host: full mutation matrix, enable/lock sync, drag deferral, and O(D) scale.
        final SyntheticSelectorHost host535 = new SyntheticSelectorHost(true, true);
        try (EditDragResponsivenessBridge v535Bridge = new EditDragResponsivenessBridge(
                host, () -> queue, nowNanos::get, () -> true, DeformerSelectorAccelerator.tryCreate(host535))) {
            v535Bridge.install(true);
            final Object modelSrc1 = host535.newModelSource();
            final Object part1 = host535.newPartSource("BodyPart", "P_BODY");
            final Object part2 = host535.newPartSource("ArmPart", "P_ARM");
            final Object alpha = host535.newDeformer("G_A", "ROOT", "Alpha", "D_A", part1);
            final Object childA = host535.newDeformer("G_A1", "G_A", "ChildA", "D_A1", part1);
            final Object childB = host535.newDeformer("G_A2", "G_A", "ChildB", "D_A2", part1);
            final Object grandB = host535.newDeformer("G_B1", "G_A2", "GrandB", "D_B1", part1);
            final Object beta = host535.newDeformer("G_B", "ROOT", "Beta", "D_B", part2);
            final List<Object> deformers = new ArrayList<>(List.of(alpha, childA, childB, grandB, beta));

            final Object factory = host535.newFactory(modelSrc1, deformers, "G_A2", true, true, false);
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(1L, v535Bridge.snapshot().get("deformerSelectorRebuilt"));
            assertEquals(1, host535.setModelCalls(factory));
            assertEquals(1, host535.setSelectedCalls(factory));
            assertEquals(
                    List.of("[Root]", " Alpha", "  ChildA", "  ChildB", "   GrandB", " Beta"),
                    host535.comboLabels(factory));
            assertEquals("  ChildB", host535.selectedLabel(factory));
            assertTrue(host535.combo(factory).isEnabled());

            // Steady-state repeat (e.g. mouse release): skipped with zero setModel / setSelectedItem calls.
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(1L, v535Bridge.snapshot().get("deformerSelectorSkipped"));
            assertEquals(1, host535.setModelCalls(factory));
            assertEquals(1, host535.setSelectedCalls(factory));

            // Selection-only change updates selectedItem without rebuilding CComboBox$a.
            host535.setSelectedTarget(factory, "G_B");
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(1, host535.setModelCalls(factory));
            assertEquals(2, host535.setSelectedCalls(factory));
            assertEquals(" Beta", host535.selectedLabel(factory));

            // Form animation lock & readonly toggle update setEnabled even on a fingerprint hit.
            host535.setInspectorFlags(factory, true, true, true);
            host535.setLockActive(true);
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertFalse(host535.combo(factory).isEnabled());
            assertEquals(1, host535.setModelCalls(factory));

            host535.setLockActive(false);
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertTrue(host535.combo(factory).isEnabled());
            assertEquals(1, host535.setModelCalls(factory));

            // Deformer rename rebuilds model cleanly.
            host535.renameDeformer(alpha, "AlphaRenamed");
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(2, host535.setModelCalls(factory));
            assertEquals(" AlphaRenamed", host535.comboLabels(factory).get(1));

            // Clearing localName and changing ID rebuilds model with ID fallback.
            host535.renameDeformer(alpha, null);
            host535.setDeformerId(alpha, "ID_ALPHA_NEW");
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(3, host535.setModelCalls(factory));
            assertEquals(" ID_ALPHA_NEW", host535.comboLabels(factory).get(1));

            // Reparenting ChildB from Alpha to ROOT flattens its subtree indentation.
            host535.reparentDeformer(childB, "ROOT");
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(4, host535.setModelCalls(factory));
            assertEquals(
                    List.of("[Root]", " ID_ALPHA_NEW", "  ChildA", " ChildB", "  GrandB", " Beta"),
                    host535.comboLabels(factory));

            // Enabling showPartName, renaming part, and switching part parent all invalidate fingerprint.
            host535.setShowPartName(true);
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(5, host535.setModelCalls(factory));
            assertTrue(host535.comboLabels(factory).get(1).endsWith("  [BodyPart]"));

            host535.renamePart(part1, "BodyRenamed");
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(6, host535.setModelCalls(factory));
            assertTrue(host535.comboLabels(factory).get(1).endsWith("  [BodyRenamed]"));

            host535.setDeformerPart(alpha, part2);
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(7, host535.setModelCalls(factory));
            assertTrue(host535.comboLabels(factory).get(1).endsWith("  [ArmPart]"));
            host535.setShowPartName(false);
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(8, host535.setModelCalls(factory));

            // Reordering siblings in model.getAllDeformers() invalidates fingerprint and preserves order.
            deformers.remove(beta);
            deformers.add(0, beta);
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(9, host535.setModelCalls(factory));
            assertEquals(" Beta", host535.comboLabels(factory).get(1));

            // Adding and removing a deformer invalidate fingerprint cleanly.
            final Object gamma = host535.newDeformer("G_C", "ROOT", "Gamma", "D_C", part2);
            deformers.add(gamma);
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(10, host535.setModelCalls(factory));
            assertTrue(host535.comboLabels(factory).contains(" Gamma"));

            deformers.remove(gamma);
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(11, host535.setModelCalls(factory));
            assertFalse(host535.comboLabels(factory).contains(" Gamma"));

            // Switching to another CModelSource rebuilds for the second model, then hits on repeat.
            final Object modelSrc2 = host535.newModelSource();
            final Object otherDef = host535.newDeformer("G_M2", "ROOT", "ModelTwoRoot", "D_M2", null);
            final Object model2 = host535.newModel(List.of(otherDef));
            host535.switchWrapModel(factory, modelSrc2, model2, "G_M2");
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(12, host535.setModelCalls(factory));
            assertEquals(List.of("[Root]", " ModelTwoRoot"), host535.comboLabels(factory));
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(12, host535.setModelCalls(factory));

            // Mixed modelSource selection (!sameModel) falls back cleanly and invalidates cached state.
            host535.addSecondSelectableFromDifferentModel(factory, host535.newModelSource(), model2);
            assertFalse(v535Bridge.shouldDeferDeformerSelector(factory));
            host535.trimToSingleSelectable(factory);
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(13, host535.setModelCalls(factory));

            // Canvas drag defers updateWidget cleanly and release flush invokes accelerated rebuild/skip.
            final Object canvasListener = new Object();
            host.canvasListener = canvasListener;
            v535Bridge.onMousePressed(canvasListener, new Object());
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(13, host535.setModelCalls(factory));
            assertEquals(1L, v535Bridge.snapshot().get("deformerSelectorDeferred"));
            v535Bridge.onMouseReleasedBegin(canvasListener, new Object());
            v535Bridge.onMouseReleasedEnd(canvasListener, new Object());
            assertEquals(1, host.selectorUpdates.size());

            // O(D) grouping & fingerprint skip scale to 1,200 deformers without stack overflow or model churn.
            final List<Object> largeDeformers = new ArrayList<>(1200);
            String parentGuid = "ROOT";
            for (int i = 0; i < 1200; i++) {
                final String g = "G_L_" + i;
                largeDeformers.add(host535.newDeformer(g, parentGuid, "Def" + i, "ID" + i, null));
                if ((i % 8) == 0) {
                    parentGuid = g;
                }
            }
            final Object largeModel = host535.newModel(largeDeformers);
            final Object largeSrc = host535.newModelSource();
            host535.switchWrapModel(factory, largeSrc, largeModel, "G_L_0");
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(14, host535.setModelCalls(factory));
            assertEquals(1201, host535.comboLabels(factory).size());
            assertTrue(v535Bridge.shouldDeferDeformerSelector(factory));
            assertEquals(14, host535.setModelCalls(factory));
        }
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
        public AWTEvent peekEvent(final int id) {
            for (final AWTEvent event : events) {
                if (event.getID() == id) {
                    return event;
                }
            }
            return null;
        }

        @Override
        public AWTEvent getNextEvent() {
            return events.pollFirst();
        }
    }

    private static final class RecordingHostBindings implements EditDragResponsivenessBridge.HostBindings {

        Object canvasListener;
        Object canvasWidget;
        boolean openDragSession;
        Component widgetComponent;
        final java.util.Map<Object, Component> widgetComponents = new java.util.IdentityHashMap<>();
        Cursor cursorToReturn;
        Runnable onMouseDraggedHook;
        Runnable repaintDuringMouseDragged;
        final List<MouseEvent> draggedDispatched = new ArrayList<>();
        final List<String> inspectorCtrlUpdates = new ArrayList<>();
        final List<String> inspectorPanelUpdates = new ArrayList<>();
        final List<String> selectorUpdates = new ArrayList<>();
        final List<String> deformerUpdates = new ArrayList<>();

        @Override
        public boolean isCanvasListener(final Object listener) {
            return listener != null && listener == canvasListener;
        }

        @Override
        public Object widgetOfListener(final Object listener) {
            return canvasWidget;
        }

        @Override
        public boolean hasOpenDragSession(final Object listener) {
            return openDragSession;
        }

        @Override
        public void dispatchMouseDragged(final Object listener, final MouseEvent event) {
            draggedDispatched.add(event);
            if (onMouseDraggedHook != null) {
                onMouseDraggedHook.run();
            }
            if (repaintDuringMouseDragged != null) {
                repaintDuringMouseDragged.run();
            }
        }

        @Override
        public Component componentOfWidget(final Object widget) {
            final Component mapped = widgetComponents.get(widget);
            return mapped != null ? mapped : widgetComponent;
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

    private static final class SyntheticSelectorHost extends ClassLoader {
        private static final String ID_INT = "com/live2d/core/id/Id";
        private static final String CTRL_ID_INT = "com/live2d/cubism/doc/model/id/CParameterControllableId";
        private static final String DEF_ID_INT = "com/live2d/cubism/doc/model/id/CDeformerId";
        private static final String GUID_INT = "com/live2d/type/CDeformerGuid";
        private static final String GUID_COMP_INT = "com/live2d/type/CDeformerGuid$a";
        private static final String MODEL_SRC_INT = "com/live2d/cubism/doc/model/CModelSource";
        private static final String CTRL_SRC_INT = "com/live2d/cubism/doc/model/ACParameterControllableSource";
        private static final String PART_SRC_INT = "com/live2d/cubism/doc/model/parts/CPartSource";
        private static final String DEF_SRC_INT = "com/live2d/cubism/doc/model/deformer/ACDeformerSource";
        private static final String CTRL_INT = "com/live2d/cubism/doc/model/ACParameterControllable";
        private static final String DEF_INT = "com/live2d/cubism/doc/model/deformer/ACDeformer";
        private static final String MODEL_INT = "com/live2d/cubism/doc/model/CModel";
        private static final String DOC_INT = "com/live2d/cubism/doc/modeling/CModelingDocument";
        private static final String WRAP_INT =
                "com/live2d/cubism/view/palette/inspector/editPalette/ParameterControllable_wrapperForInspector";
        private static final String SELECTABLE_INT = WRAP_INT + "$DeformerSelectable";
        private static final String FACTORY_INT = WRAP_INT + "$DeformerSelectorUiFactory";
        private static final String ITEM_INT = FACTORY_INT + "$DeformerComboBoxItem";
        private static final String SWING_COMBO_INT = "com/live2d/ui/swingImpl/l";
        private static final String COMBO_INT = "com/live2d/ui/control/CComboBox";
        private static final String COMBO_MODEL_INT = "com/live2d/ui/control/CComboBox$a";
        private static final String INSP_INT = "com/live2d/inspector/simpleInspectors/CInspector_CInspectableObject";
        private static final String LOCK_INT = "com/live2d/inspector/i";
        private static final String APP_SET_INT = "com/live2d/cubism/setting/AppSetting";
        private static final String DEF_SET_INT = "com/live2d/cubism/setting/AppSetting$Deformer";

        private final Map<String, Class<?>> classes = new HashMap<>();
        private final Map<String, Object> guids = new HashMap<>();
        private final boolean hasAppSetting;
        private final boolean hasAnimLock;

        SyntheticSelectorHost(final boolean hasAppSetting, final boolean hasAnimLock) throws Exception {
            super(EditDragResponsivenessTest.class.getClassLoader());
            this.hasAppSetting = hasAppSetting;
            this.hasAnimLock = hasAnimLock;
            emitIdHierarchy();
            emitGuidTypes();
            emitModelTypes();
            emitUiTypes();
            if (hasAppSetting) {
                emitAppSetting();
            }
            if (hasAnimLock) {
                emitInspectorLock();
            }
            emitComboItem();
            emitSelectorFactory();
            final Object rootGuid = load(GUID_INT).getField("ROOT").get(null);
            guids.put("ROOT", rootGuid);
        }

        private Class<?> load(final String internal) {
            return classes.get(internal);
        }

        private void define(final String internal, final ClassWriter cw) {
            cw.visitEnd();
            final byte[] bytes = cw.toByteArray();
            classes.put(internal, defineClass(internal.replace('/', '.'), bytes, 0, bytes.length));
        }

        private Object guid(final String key) throws Exception {
            Object existing = guids.get(key);
            if (existing == null) {
                existing = load(GUID_INT).getConstructor(String.class).newInstance(key);
                guids.put(key, existing);
            }
            return existing;
        }

        Object newModelSource() throws Exception {
            return load(MODEL_SRC_INT).getConstructor().newInstance();
        }

        Object newPartSource(final String localName, final String idStr) throws Exception {
            final Object part = load(PART_SRC_INT).getConstructor().newInstance();
            part.getClass().getField("localName").set(part, localName);
            part.getClass()
                    .getField("id")
                    .set(part, load(CTRL_ID_INT).getConstructor(String.class).newInstance(idStr));
            return part;
        }

        Object newDeformer(
                final String guidKey,
                final String targetGuidKey,
                final String localName,
                final String idStr,
                final Object part)
                throws Exception {
            final Object g = guid(guidKey);
            final Object tg = guid(targetGuidKey);
            final Object src = load(DEF_SRC_INT).getConstructor().newInstance();
            src.getClass().getField("guid").set(src, g);
            src.getClass().getField("localName").set(src, localName);
            src.getClass()
                    .getField("id")
                    .set(src, load(DEF_ID_INT).getConstructor(String.class).newInstance(idStr));
            src.getClass().getField("parent").set(src, part);
            src.getClass().getField("targetDeformerGuid").set(src, tg);
            final Object def = load(DEF_INT).getConstructor().newInstance();
            def.getClass().getField("guid").set(def, g);
            def.getClass().getField("targetDeformerGuid").set(def, tg);
            def.getClass().getField("defSource").set(def, src);
            def.getClass().getField("source").set(def, src);
            return def;
        }

        @SuppressWarnings("unchecked")
        Object newModel(final List<Object> deformers) throws Exception {
            final Object model = load(MODEL_INT).getConstructor().newInstance();
            ((List<Object>) model.getClass().getField("allDeformers").get(model)).addAll(deformers);
            return model;
        }

        @SuppressWarnings("unchecked")
        Object newFactory(
                final Object modelSource,
                final List<Object> deformers,
                final String selectedTargetKey,
                final boolean enabled,
                final boolean editable,
                final boolean formAnimLock)
                throws Exception {
            final Object doc = load(DOC_INT).getConstructor().newInstance();
            doc.getClass().getField("modelSource").set(doc, modelSource);
            final Object model = load(MODEL_INT).getConstructor().newInstance();
            model.getClass().getField("allDeformers").set(model, deformers);
            final Object objSrc = load(CTRL_SRC_INT).getConstructor().newInstance();
            objSrc.getClass().getField("targetDeformerGuid").set(objSrc, guid(selectedTargetKey));
            final Object obj = load(CTRL_INT).getConstructor().newInstance();
            obj.getClass().getField("source").set(obj, objSrc);
            final Object wrap = load(WRAP_INT).getConstructor().newInstance();
            wrap.getClass().getField("doc").set(wrap, doc);
            wrap.getClass().getField("model").set(wrap, model);
            wrap.getClass().getField("obj").set(wrap, obj);
            final Object selectable = load(SELECTABLE_INT).getConstructor().newInstance();
            selectable.getClass().getField("wrap").set(selectable, wrap);
            final Object factory = load(FACTORY_INT).getConstructor().newInstance();
            ((List<Object>) factory.getClass().getField("values").get(factory)).add(selectable);
            setInspectorFlags(factory, enabled, editable, formAnimLock);
            return factory;
        }

        void setSelectedTarget(final Object factory, final String targetKey) throws Exception {
            final List<?> values =
                    (List<?>) factory.getClass().getField("values").get(factory);
            for (final Object selectable : values) {
                final Object wrap = selectable.getClass().getField("wrap").get(selectable);
                final Object obj = wrap.getClass().getField("obj").get(wrap);
                final Object src = obj.getClass().getField("source").get(obj);
                src.getClass().getField("targetDeformerGuid").set(src, guid(targetKey));
            }
        }

        void setInspectorFlags(
                final Object factory, final boolean enabled, final boolean editable, final boolean formAnimLock)
                throws Exception {
            final Object insp = factory.getClass().getField("inspector").get(factory);
            insp.getClass().getField("enabled").setBoolean(insp, enabled);
            insp.getClass().getField("editable").setBoolean(insp, editable);
            if (hasAnimLock) {
                insp.getClass().getField("formAnimationLock").setBoolean(insp, formAnimLock);
            }
        }

        void setShowPartName(final boolean show) throws Exception {
            final Object inst = load(APP_SET_INT).getField("INSTANCE").get(null);
            final Object def = inst.getClass().getField("deformer").get(inst);
            def.getClass().getField("showPartNameOfDeformer").setBoolean(def, show);
        }

        void setLockActive(final boolean active) throws Exception {
            final Object inst = load(LOCK_INT).getField("a").get(null);
            inst.getClass().getField("active").setBoolean(inst, active);
        }

        void renameDeformer(final Object deformer, final String newName) throws Exception {
            final Object src = deformer.getClass().getField("defSource").get(deformer);
            src.getClass().getField("localName").set(src, newName);
        }

        void setDeformerId(final Object deformer, final String newId) throws Exception {
            final Object src = deformer.getClass().getField("defSource").get(deformer);
            final Object id = src.getClass().getField("id").get(src);
            id.getClass().getMethod("setIdString", String.class).invoke(id, newId);
        }

        void reparentDeformer(final Object deformer, final String parentKey) throws Exception {
            final Object tg = guid(parentKey);
            deformer.getClass().getField("targetDeformerGuid").set(deformer, tg);
            final Object src = deformer.getClass().getField("defSource").get(deformer);
            src.getClass().getField("targetDeformerGuid").set(src, tg);
        }

        void renamePart(final Object part, final String newName) throws Exception {
            part.getClass().getField("localName").set(part, newName);
        }

        void setDeformerPart(final Object deformer, final Object part) throws Exception {
            final Object src = deformer.getClass().getField("defSource").get(deformer);
            src.getClass().getField("parent").set(src, part);
        }

        void switchWrapModel(
                final Object factory, final Object modelSource, final Object model, final String selectedTargetKey)
                throws Exception {
            final List<?> values =
                    (List<?>) factory.getClass().getField("values").get(factory);
            final Object wrap = values.get(0).getClass().getField("wrap").get(values.get(0));
            final Object doc = wrap.getClass().getField("doc").get(wrap);
            doc.getClass().getField("modelSource").set(doc, modelSource);
            wrap.getClass().getField("model").set(wrap, model);
            setSelectedTarget(factory, selectedTargetKey);
        }

        @SuppressWarnings("unchecked")
        void addSecondSelectableFromDifferentModel(
                final Object factory, final Object otherModelSource, final Object otherModel) throws Exception {
            final Object doc = load(DOC_INT).getConstructor().newInstance();
            doc.getClass().getField("modelSource").set(doc, otherModelSource);
            final Object src = load(CTRL_SRC_INT).getConstructor().newInstance();
            src.getClass().getField("targetDeformerGuid").set(src, guid("ROOT"));
            final Object obj = load(CTRL_INT).getConstructor().newInstance();
            obj.getClass().getField("source").set(obj, src);
            final Object wrap = load(WRAP_INT).getConstructor().newInstance();
            wrap.getClass().getField("doc").set(wrap, doc);
            wrap.getClass().getField("model").set(wrap, otherModel);
            wrap.getClass().getField("obj").set(wrap, obj);
            final Object sel = load(SELECTABLE_INT).getConstructor().newInstance();
            sel.getClass().getField("wrap").set(sel, wrap);
            ((List<Object>) factory.getClass().getField("values").get(factory)).add(sel);
        }

        @SuppressWarnings("unchecked")
        void trimToSingleSelectable(final Object factory) throws Exception {
            final List<Object> values =
                    (List<Object>) factory.getClass().getField("values").get(factory);
            while (values.size() > 1) {
                values.remove(values.size() - 1);
            }
        }

        JComboBox<?> combo(final Object factory) throws Exception {
            final Object cCombo = factory.getClass().getField("combo").get(factory);
            return (JComboBox<?>) cCombo.getClass().getField("jComboBox").get(cCombo);
        }

        int setModelCalls(final Object factory) throws Exception {
            final Object cCombo = factory.getClass().getField("combo").get(factory);
            return cCombo.getClass().getField("setModelCalls").getInt(cCombo);
        }

        int setSelectedCalls(final Object factory) throws Exception {
            final Object cCombo = factory.getClass().getField("combo").get(factory);
            return cCombo.getClass().getField("setSelectedCalls").getInt(cCombo);
        }

        int setEnabledCalls(final Object factory) throws Exception {
            final Object cCombo = factory.getClass().getField("combo").get(factory);
            return cCombo.getClass().getField("setEnabledCalls").getInt(cCombo);
        }

        List<String> comboLabels(final Object factory) throws Exception {
            final JComboBox<?> jc = combo(factory);
            final List<String> labels = new ArrayList<>(jc.getItemCount());
            for (int i = 0; i < jc.getItemCount(); i++) {
                labels.add(String.valueOf(jc.getItemAt(i)));
            }
            return labels;
        }

        String selectedLabel(final Object factory) throws Exception {
            final Object sel = combo(factory).getSelectedItem();
            return sel == null ? null : sel.toString();
        }

        private void emitIdHierarchy() {
            final ClassWriter id = newWriter(ID_INT, "java/lang/Object");
            id.visitField(Opcodes.ACC_PUBLIC, "idString", "Ljava/lang/String;", null, null)
                    .visitEnd();
            emitDefaultCtor(id, "java/lang/Object");
            final MethodVisitor sc = id.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
            sc.visitCode();
            sc.visitVarInsn(Opcodes.ALOAD, 0);
            sc.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            sc.visitVarInsn(Opcodes.ALOAD, 0);
            sc.visitVarInsn(Opcodes.ALOAD, 1);
            sc.visitFieldInsn(Opcodes.PUTFIELD, ID_INT, "idString", "Ljava/lang/String;");
            sc.visitInsn(Opcodes.RETURN);
            sc.visitMaxs(0, 0);
            sc.visitEnd();
            emitFieldGetter(id, ID_INT, "getIdString", "()Ljava/lang/String;", "idString");
            emitFieldGetter(id, ID_INT, "toString", "()Ljava/lang/String;", "idString");
            final MethodVisitor sis =
                    id.visitMethod(Opcodes.ACC_PUBLIC, "setIdString", "(Ljava/lang/String;)V", null, null);
            sis.visitCode();
            sis.visitVarInsn(Opcodes.ALOAD, 0);
            sis.visitVarInsn(Opcodes.ALOAD, 1);
            sis.visitFieldInsn(Opcodes.PUTFIELD, ID_INT, "idString", "Ljava/lang/String;");
            sis.visitInsn(Opcodes.RETURN);
            sis.visitMaxs(0, 0);
            sis.visitEnd();
            define(ID_INT, id);

            for (final String[] sub : new String[][] {{CTRL_ID_INT, ID_INT}, {DEF_ID_INT, CTRL_ID_INT}}) {
                final ClassWriter w = newWriter(sub[0], sub[1]);
                emitDefaultCtor(w, sub[1]);
                final MethodVisitor c =
                        w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
                c.visitCode();
                c.visitVarInsn(Opcodes.ALOAD, 0);
                c.visitVarInsn(Opcodes.ALOAD, 1);
                c.visitMethodInsn(Opcodes.INVOKESPECIAL, sub[1], "<init>", "(Ljava/lang/String;)V", false);
                c.visitInsn(Opcodes.RETURN);
                c.visitMaxs(0, 0);
                c.visitEnd();
                define(sub[0], w);
            }
        }

        private void emitGuidTypes() {
            final ClassWriter comp = newWriter(GUID_COMP_INT, "java/lang/Object");
            emitDefaultCtor(comp, "java/lang/Object");
            final MethodVisitor ga = comp.visitMethod(Opcodes.ACC_PUBLIC, "a", "()L" + GUID_INT + ";", null, null);
            ga.visitCode();
            ga.visitFieldInsn(Opcodes.GETSTATIC, GUID_INT, "ROOT", "L" + GUID_INT + ";");
            ga.visitInsn(Opcodes.ARETURN);
            ga.visitMaxs(0, 0);
            ga.visitEnd();
            define(GUID_COMP_INT, comp);

            final ClassWriter g = newWriter(GUID_INT, "java/lang/Object");
            g.visitField(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                            "Companion",
                            "L" + GUID_COMP_INT + ";",
                            null,
                            null)
                    .visitEnd();
            g.visitField(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                            "ROOT",
                            "L" + GUID_INT + ";",
                            null,
                            null)
                    .visitEnd();
            g.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "key", "Ljava/lang/String;", null, null)
                    .visitEnd();
            final MethodVisitor gc = g.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
            gc.visitCode();
            gc.visitVarInsn(Opcodes.ALOAD, 0);
            gc.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            gc.visitVarInsn(Opcodes.ALOAD, 0);
            gc.visitVarInsn(Opcodes.ALOAD, 1);
            gc.visitFieldInsn(Opcodes.PUTFIELD, GUID_INT, "key", "Ljava/lang/String;");
            gc.visitInsn(Opcodes.RETURN);
            gc.visitMaxs(0, 0);
            gc.visitEnd();

            final MethodVisitor hc = g.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null);
            hc.visitCode();
            hc.visitVarInsn(Opcodes.ALOAD, 0);
            hc.visitFieldInsn(Opcodes.GETFIELD, GUID_INT, "key", "Ljava/lang/String;");
            hc.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false);
            hc.visitInsn(Opcodes.IRETURN);
            hc.visitMaxs(0, 0);
            hc.visitEnd();

            final MethodVisitor eq = g.visitMethod(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
            eq.visitCode();
            final Label lFalse = new Label();
            eq.visitVarInsn(Opcodes.ALOAD, 1);
            eq.visitTypeInsn(Opcodes.INSTANCEOF, GUID_INT);
            eq.visitJumpInsn(Opcodes.IFEQ, lFalse);
            eq.visitVarInsn(Opcodes.ALOAD, 0);
            eq.visitFieldInsn(Opcodes.GETFIELD, GUID_INT, "key", "Ljava/lang/String;");
            eq.visitVarInsn(Opcodes.ALOAD, 1);
            eq.visitTypeInsn(Opcodes.CHECKCAST, GUID_INT);
            eq.visitFieldInsn(Opcodes.GETFIELD, GUID_INT, "key", "Ljava/lang/String;");
            eq.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            eq.visitInsn(Opcodes.IRETURN);
            eq.visitLabel(lFalse);
            eq.visitInsn(Opcodes.ICONST_0);
            eq.visitInsn(Opcodes.IRETURN);
            eq.visitMaxs(0, 0);
            eq.visitEnd();

            final MethodVisitor clinit = g.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode();
            clinit.visitTypeInsn(Opcodes.NEW, GUID_COMP_INT);
            clinit.visitInsn(Opcodes.DUP);
            clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, GUID_COMP_INT, "<init>", "()V", false);
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, GUID_INT, "Companion", "L" + GUID_COMP_INT + ";");
            clinit.visitTypeInsn(Opcodes.NEW, GUID_INT);
            clinit.visitInsn(Opcodes.DUP);
            clinit.visitLdcInsn("ROOT");
            clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, GUID_INT, "<init>", "(Ljava/lang/String;)V", false);
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, GUID_INT, "ROOT", "L" + GUID_INT + ";");
            clinit.visitInsn(Opcodes.RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
            define(GUID_INT, g);
        }

        private void emitModelTypes() {
            final ClassWriter ms = newWriter(MODEL_SRC_INT, "java/lang/Object");
            emitDefaultCtor(ms, "java/lang/Object");
            define(MODEL_SRC_INT, ms);

            final ClassWriter cs = newWriter(CTRL_SRC_INT, "java/lang/Object");
            cs.visitField(Opcodes.ACC_PUBLIC, "localName", "Ljava/lang/String;", null, null)
                    .visitEnd();
            cs.visitField(Opcodes.ACC_PUBLIC, "id", "L" + CTRL_ID_INT + ";", null, null)
                    .visitEnd();
            cs.visitField(Opcodes.ACC_PUBLIC, "parent", "L" + PART_SRC_INT + ";", null, null)
                    .visitEnd();
            cs.visitField(Opcodes.ACC_PUBLIC, "targetDeformerGuid", "L" + GUID_INT + ";", null, null)
                    .visitEnd();
            emitDefaultCtor(cs, "java/lang/Object");
            emitFieldGetter(cs, CTRL_SRC_INT, "getLocalName", "()Ljava/lang/String;", "localName");
            emitFieldGetter(cs, CTRL_SRC_INT, "getId", "()L" + CTRL_ID_INT + ";", "id");
            emitFieldGetter(cs, CTRL_SRC_INT, "getParent", "()L" + PART_SRC_INT + ";", "parent");
            emitFieldGetter(cs, CTRL_SRC_INT, "getTargetDeformerGuid", "()L" + GUID_INT + ";", "targetDeformerGuid");
            define(CTRL_SRC_INT, cs);

            final ClassWriter ps = newWriter(PART_SRC_INT, CTRL_SRC_INT);
            emitDefaultCtor(ps, CTRL_SRC_INT);
            define(PART_SRC_INT, ps);

            final ClassWriter ds = newWriter(DEF_SRC_INT, CTRL_SRC_INT);
            ds.visitField(Opcodes.ACC_PUBLIC, "guid", "L" + GUID_INT + ";", null, null)
                    .visitEnd();
            emitDefaultCtor(ds, CTRL_SRC_INT);
            emitFieldGetter(ds, DEF_SRC_INT, "getGuid", "()L" + GUID_INT + ";", "guid");
            define(DEF_SRC_INT, ds);

            final ClassWriter c = newWriter(CTRL_INT, "java/lang/Object");
            c.visitField(Opcodes.ACC_PUBLIC, "source", "L" + CTRL_SRC_INT + ";", null, null)
                    .visitEnd();
            emitDefaultCtor(c, "java/lang/Object");
            emitFieldGetter(c, CTRL_INT, "getSource", "()L" + CTRL_SRC_INT + ";", "source");
            define(CTRL_INT, c);

            final ClassWriter d = newWriter(DEF_INT, CTRL_INT);
            d.visitField(Opcodes.ACC_PUBLIC, "guid", "L" + GUID_INT + ";", null, null)
                    .visitEnd();
            d.visitField(Opcodes.ACC_PUBLIC, "targetDeformerGuid", "L" + GUID_INT + ";", null, null)
                    .visitEnd();
            d.visitField(Opcodes.ACC_PUBLIC, "defSource", "L" + DEF_SRC_INT + ";", null, null)
                    .visitEnd();
            emitDefaultCtor(d, CTRL_INT);
            emitFieldGetter(d, DEF_INT, "getGuid", "()L" + GUID_INT + ";", "guid");
            emitFieldGetter(d, DEF_INT, "getTargetDeformerGuid", "()L" + GUID_INT + ";", "targetDeformerGuid");
            emitFieldGetter(d, DEF_INT, "getSource", "()L" + DEF_SRC_INT + ";", "defSource");
            define(DEF_INT, d);

            final ClassWriter m = newWriter(MODEL_INT, "java/lang/Object");
            m.visitField(Opcodes.ACC_PUBLIC, "allDeformers", "Ljava/util/List;", null, null)
                    .visitEnd();
            emitListInitCtor(m, MODEL_INT, "allDeformers");
            emitFieldGetter(m, MODEL_INT, "getAllDeformers", "()Ljava/util/List;", "allDeformers");
            define(MODEL_INT, m);

            final ClassWriter doc = newWriter(DOC_INT, "java/lang/Object");
            doc.visitField(Opcodes.ACC_PUBLIC, "modelSource", "L" + MODEL_SRC_INT + ";", null, null)
                    .visitEnd();
            emitDefaultCtor(doc, "java/lang/Object");
            emitFieldGetter(doc, DOC_INT, "getModelSource", "()L" + MODEL_SRC_INT + ";", "modelSource");
            define(DOC_INT, doc);

            final ClassWriter wrap = newWriter(WRAP_INT, "java/lang/Object");
            wrap.visitField(Opcodes.ACC_PUBLIC, "doc", "L" + DOC_INT + ";", null, null)
                    .visitEnd();
            wrap.visitField(Opcodes.ACC_PUBLIC, "model", "L" + MODEL_INT + ";", null, null)
                    .visitEnd();
            wrap.visitField(Opcodes.ACC_PUBLIC, "obj", "L" + CTRL_INT + ";", null, null)
                    .visitEnd();
            emitDefaultCtor(wrap, "java/lang/Object");
            emitFieldGetter(wrap, WRAP_INT, "getDoc", "()L" + DOC_INT + ";", "doc");
            emitFieldGetter(wrap, WRAP_INT, "getModel", "()L" + MODEL_INT + ";", "model");
            emitFieldGetter(wrap, WRAP_INT, "getObj", "()L" + CTRL_INT + ";", "obj");
            define(WRAP_INT, wrap);

            final ClassWriter sel = newWriter(SELECTABLE_INT, "java/lang/Object");
            sel.visitField(Opcodes.ACC_PUBLIC, "wrap", "L" + WRAP_INT + ";", null, null)
                    .visitEnd();
            emitDefaultCtor(sel, "java/lang/Object");
            emitFieldGetter(sel, SELECTABLE_INT, "getWrap", "()L" + WRAP_INT + ";", "wrap");
            define(SELECTABLE_INT, sel);
        }

        private void emitUiTypes() {
            final ClassWriter sc = newWriter(SWING_COMBO_INT, "javax/swing/JComboBox");
            emitDefaultCtor(sc, "javax/swing/JComboBox");
            define(SWING_COMBO_INT, sc);

            final ClassWriter cm = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cm.visit(
                    Opcodes.V17,
                    Opcodes.ACC_PUBLIC,
                    COMBO_MODEL_INT,
                    null,
                    "javax/swing/AbstractListModel",
                    new String[] {"javax/swing/ComboBoxModel"});
            cm.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "items", "Ljava/util/List;", null, null)
                    .visitEnd();
            cm.visitField(Opcodes.ACC_PUBLIC, "selected", "Ljava/lang/Object;", null, null)
                    .visitEnd();
            final MethodVisitor cmc = cm.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/util/List;)V", null, null);
            cmc.visitCode();
            cmc.visitVarInsn(Opcodes.ALOAD, 0);
            cmc.visitMethodInsn(Opcodes.INVOKESPECIAL, "javax/swing/AbstractListModel", "<init>", "()V", false);
            cmc.visitVarInsn(Opcodes.ALOAD, 0);
            cmc.visitVarInsn(Opcodes.ALOAD, 1);
            cmc.visitFieldInsn(Opcodes.PUTFIELD, COMBO_MODEL_INT, "items", "Ljava/util/List;");
            cmc.visitInsn(Opcodes.RETURN);
            cmc.visitMaxs(0, 0);
            cmc.visitEnd();
            emitFieldGetter(cm, COMBO_MODEL_INT, "a", "()Ljava/util/List;", "items");
            final MethodVisitor gs = cm.visitMethod(Opcodes.ACC_PUBLIC, "getSize", "()I", null, null);
            gs.visitCode();
            gs.visitVarInsn(Opcodes.ALOAD, 0);
            gs.visitFieldInsn(Opcodes.GETFIELD, COMBO_MODEL_INT, "items", "Ljava/util/List;");
            gs.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true);
            gs.visitInsn(Opcodes.IRETURN);
            gs.visitMaxs(0, 0);
            gs.visitEnd();
            final MethodVisitor ge =
                    cm.visitMethod(Opcodes.ACC_PUBLIC, "getElementAt", "(I)Ljava/lang/Object;", null, null);
            ge.visitCode();
            ge.visitVarInsn(Opcodes.ALOAD, 0);
            ge.visitFieldInsn(Opcodes.GETFIELD, COMBO_MODEL_INT, "items", "Ljava/util/List;");
            ge.visitVarInsn(Opcodes.ILOAD, 1);
            ge.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
            ge.visitInsn(Opcodes.ARETURN);
            ge.visitMaxs(0, 0);
            ge.visitEnd();
            final MethodVisitor ssi =
                    cm.visitMethod(Opcodes.ACC_PUBLIC, "setSelectedItem", "(Ljava/lang/Object;)V", null, null);
            ssi.visitCode();
            ssi.visitVarInsn(Opcodes.ALOAD, 0);
            ssi.visitVarInsn(Opcodes.ALOAD, 1);
            ssi.visitFieldInsn(Opcodes.PUTFIELD, COMBO_MODEL_INT, "selected", "Ljava/lang/Object;");
            ssi.visitInsn(Opcodes.RETURN);
            ssi.visitMaxs(0, 0);
            ssi.visitEnd();
            emitFieldGetter(cm, COMBO_MODEL_INT, "getSelectedItem", "()Ljava/lang/Object;", "selected");
            define(COMBO_MODEL_INT, cm);

            final ClassWriter cb = newWriter(COMBO_INT, "java/lang/Object");
            cb.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "jComboBox", "L" + SWING_COMBO_INT + ";", null, null)
                    .visitEnd();
            cb.visitField(Opcodes.ACC_PUBLIC, "setModelCalls", "I", null, null).visitEnd();
            cb.visitField(Opcodes.ACC_PUBLIC, "setSelectedCalls", "I", null, null)
                    .visitEnd();
            cb.visitField(Opcodes.ACC_PUBLIC, "setEnabledCalls", "I", null, null)
                    .visitEnd();
            final MethodVisitor cbc = cb.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            cbc.visitCode();
            cbc.visitVarInsn(Opcodes.ALOAD, 0);
            cbc.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            cbc.visitVarInsn(Opcodes.ALOAD, 0);
            cbc.visitTypeInsn(Opcodes.NEW, SWING_COMBO_INT);
            cbc.visitInsn(Opcodes.DUP);
            cbc.visitMethodInsn(Opcodes.INVOKESPECIAL, SWING_COMBO_INT, "<init>", "()V", false);
            cbc.visitFieldInsn(Opcodes.PUTFIELD, COMBO_INT, "jComboBox", "L" + SWING_COMBO_INT + ";");
            cbc.visitInsn(Opcodes.RETURN);
            cbc.visitMaxs(0, 0);
            cbc.visitEnd();
            emitFieldGetter(cb, COMBO_INT, "getJComboBox", "()L" + SWING_COMBO_INT + ";", "jComboBox");

            final MethodVisitor sm =
                    cb.visitMethod(Opcodes.ACC_PUBLIC, "setModel", "(L" + COMBO_MODEL_INT + ";)V", null, null);
            sm.visitCode();
            emitIncrementField(sm, COMBO_INT, "setModelCalls");
            sm.visitVarInsn(Opcodes.ALOAD, 0);
            sm.visitFieldInsn(Opcodes.GETFIELD, COMBO_INT, "jComboBox", "L" + SWING_COMBO_INT + ";");
            sm.visitVarInsn(Opcodes.ALOAD, 1);
            sm.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, SWING_COMBO_INT, "setModel", "(Ljavax/swing/ComboBoxModel;)V", false);
            sm.visitInsn(Opcodes.RETURN);
            sm.visitMaxs(0, 0);
            sm.visitEnd();

            final MethodVisitor ss =
                    cb.visitMethod(Opcodes.ACC_PUBLIC, "setSelectedItem", "(Ljava/lang/Object;)V", null, null);
            ss.visitCode();
            emitIncrementField(ss, COMBO_INT, "setSelectedCalls");
            ss.visitVarInsn(Opcodes.ALOAD, 0);
            ss.visitFieldInsn(Opcodes.GETFIELD, COMBO_INT, "jComboBox", "L" + SWING_COMBO_INT + ";");
            ss.visitVarInsn(Opcodes.ALOAD, 1);
            ss.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL, SWING_COMBO_INT, "setSelectedItem", "(Ljava/lang/Object;)V", false);
            ss.visitInsn(Opcodes.RETURN);
            ss.visitMaxs(0, 0);
            ss.visitEnd();

            final MethodVisitor se = cb.visitMethod(Opcodes.ACC_PUBLIC, "setEnabled", "(Z)V", null, null);
            se.visitCode();
            emitIncrementField(se, COMBO_INT, "setEnabledCalls");
            se.visitVarInsn(Opcodes.ALOAD, 0);
            se.visitFieldInsn(Opcodes.GETFIELD, COMBO_INT, "jComboBox", "L" + SWING_COMBO_INT + ";");
            se.visitVarInsn(Opcodes.ILOAD, 1);
            se.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SWING_COMBO_INT, "setEnabled", "(Z)V", false);
            se.visitInsn(Opcodes.RETURN);
            se.visitMaxs(0, 0);
            se.visitEnd();
            define(COMBO_INT, cb);

            final ClassWriter insp = newWriter(INSP_INT, "java/lang/Object");
            insp.visitField(Opcodes.ACC_PUBLIC, "enabled", "Z", null, null).visitEnd();
            insp.visitField(Opcodes.ACC_PUBLIC, "editable", "Z", null, null).visitEnd();
            if (hasAnimLock) {
                insp.visitField(Opcodes.ACC_PUBLIC, "formAnimationLock", "Z", null, null)
                        .visitEnd();
            }
            emitDefaultCtor(insp, "java/lang/Object");
            emitBooleanGetter(insp, INSP_INT, "isEnabled", "enabled");
            emitBooleanGetter(insp, INSP_INT, "isEditable", "editable");
            if (hasAnimLock) {
                emitBooleanGetter(insp, INSP_INT, "getFormAnimationLock", "formAnimationLock");
            }
            define(INSP_INT, insp);
        }

        private void emitAppSetting() {
            final ClassWriter ds = newWriter(DEF_SET_INT, "java/lang/Object");
            ds.visitField(Opcodes.ACC_PUBLIC, "showPartNameOfDeformer", "Z", null, null)
                    .visitEnd();
            emitDefaultCtor(ds, "java/lang/Object");
            emitBooleanGetter(ds, DEF_SET_INT, "getShowPartNameOfDeformer", "showPartNameOfDeformer");
            define(DEF_SET_INT, ds);

            final ClassWriter as = newWriter(APP_SET_INT, "java/lang/Object");
            as.visitField(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                            "INSTANCE",
                            "L" + APP_SET_INT + ";",
                            null,
                            null)
                    .visitEnd();
            as.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "deformer", "L" + DEF_SET_INT + ";", null, null)
                    .visitEnd();
            final MethodVisitor asc = as.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            asc.visitCode();
            asc.visitVarInsn(Opcodes.ALOAD, 0);
            asc.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            asc.visitVarInsn(Opcodes.ALOAD, 0);
            asc.visitTypeInsn(Opcodes.NEW, DEF_SET_INT);
            asc.visitInsn(Opcodes.DUP);
            asc.visitMethodInsn(Opcodes.INVOKESPECIAL, DEF_SET_INT, "<init>", "()V", false);
            asc.visitFieldInsn(Opcodes.PUTFIELD, APP_SET_INT, "deformer", "L" + DEF_SET_INT + ";");
            asc.visitInsn(Opcodes.RETURN);
            asc.visitMaxs(0, 0);
            asc.visitEnd();
            emitFieldGetter(as, APP_SET_INT, "getDeformer", "()L" + DEF_SET_INT + ";", "deformer");
            final MethodVisitor cl = as.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            cl.visitCode();
            cl.visitTypeInsn(Opcodes.NEW, APP_SET_INT);
            cl.visitInsn(Opcodes.DUP);
            cl.visitMethodInsn(Opcodes.INVOKESPECIAL, APP_SET_INT, "<init>", "()V", false);
            cl.visitFieldInsn(Opcodes.PUTSTATIC, APP_SET_INT, "INSTANCE", "L" + APP_SET_INT + ";");
            cl.visitInsn(Opcodes.RETURN);
            cl.visitMaxs(0, 0);
            cl.visitEnd();
            define(APP_SET_INT, as);
        }

        private void emitInspectorLock() {
            final ClassWriter lk = newWriter(LOCK_INT, "java/lang/Object");
            lk.visitField(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                            "a",
                            "L" + LOCK_INT + ";",
                            null,
                            null)
                    .visitEnd();
            lk.visitField(Opcodes.ACC_PUBLIC, "active", "Z", null, null).visitEnd();
            emitDefaultCtor(lk, "java/lang/Object");
            emitBooleanGetter(lk, LOCK_INT, "b", "active");
            final MethodVisitor cl = lk.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            cl.visitCode();
            cl.visitTypeInsn(Opcodes.NEW, LOCK_INT);
            cl.visitInsn(Opcodes.DUP);
            cl.visitMethodInsn(Opcodes.INVOKESPECIAL, LOCK_INT, "<init>", "()V", false);
            cl.visitFieldInsn(Opcodes.PUTSTATIC, LOCK_INT, "a", "L" + LOCK_INT + ";");
            cl.visitInsn(Opcodes.RETURN);
            cl.visitMaxs(0, 0);
            cl.visitEnd();
            define(LOCK_INT, lk);
        }

        private void emitComboItem() {
            final ClassWriter cw = newWriter(ITEM_INT, "java/lang/Object");
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "deformer", "L" + DEF_SRC_INT + ";", null, null)
                    .visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "indent", "Ljava/lang/String;", null, null)
                    .visitEnd();
            final MethodVisitor init = cw.visitMethod(
                    Opcodes.ACC_PUBLIC, "<init>", "(L" + DEF_SRC_INT + ";Ljava/lang/String;)V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(Opcodes.ALOAD, 1);
            init.visitFieldInsn(Opcodes.PUTFIELD, ITEM_INT, "deformer", "L" + DEF_SRC_INT + ";");
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(Opcodes.ALOAD, 2);
            init.visitFieldInsn(Opcodes.PUTFIELD, ITEM_INT, "indent", "Ljava/lang/String;");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();

            final MethodVisitor gg = cw.visitMethod(Opcodes.ACC_PUBLIC, "getGuid", "()L" + GUID_INT + ";", null, null);
            gg.visitCode();
            final Label lNull = new Label();
            gg.visitVarInsn(Opcodes.ALOAD, 0);
            gg.visitFieldInsn(Opcodes.GETFIELD, ITEM_INT, "deformer", "L" + DEF_SRC_INT + ";");
            gg.visitJumpInsn(Opcodes.IFNULL, lNull);
            gg.visitVarInsn(Opcodes.ALOAD, 0);
            gg.visitFieldInsn(Opcodes.GETFIELD, ITEM_INT, "deformer", "L" + DEF_SRC_INT + ";");
            gg.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DEF_SRC_INT, "getGuid", "()L" + GUID_INT + ";", false);
            gg.visitInsn(Opcodes.ARETURN);
            gg.visitLabel(lNull);
            gg.visitFieldInsn(Opcodes.GETSTATIC, GUID_INT, "Companion", "L" + GUID_COMP_INT + ";");
            gg.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GUID_COMP_INT, "a", "()L" + GUID_INT + ";", false);
            gg.visitInsn(Opcodes.ARETURN);
            gg.visitMaxs(0, 0);
            gg.visitEnd();

            final MethodVisitor lb = cw.visitMethod(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "label",
                    "(L" + CTRL_SRC_INT + ";)Ljava/lang/String;",
                    null,
                    null);
            lb.visitCode();
            final Label lNonNullSrc = new Label();
            final Label lUseId = new Label();
            final Label lNonNullId = new Label();
            lb.visitVarInsn(Opcodes.ALOAD, 0);
            lb.visitJumpInsn(Opcodes.IFNONNULL, lNonNullSrc);
            lb.visitLdcInsn("");
            lb.visitInsn(Opcodes.ARETURN);
            lb.visitLabel(lNonNullSrc);
            lb.visitVarInsn(Opcodes.ALOAD, 0);
            lb.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CTRL_SRC_INT, "getLocalName", "()Ljava/lang/String;", false);
            lb.visitVarInsn(Opcodes.ASTORE, 1);
            lb.visitVarInsn(Opcodes.ALOAD, 1);
            lb.visitJumpInsn(Opcodes.IFNULL, lUseId);
            lb.visitVarInsn(Opcodes.ALOAD, 1);
            lb.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "isEmpty", "()Z", false);
            lb.visitJumpInsn(Opcodes.IFNE, lUseId);
            lb.visitVarInsn(Opcodes.ALOAD, 1);
            lb.visitInsn(Opcodes.ARETURN);
            lb.visitLabel(lUseId);
            lb.visitVarInsn(Opcodes.ALOAD, 0);
            lb.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CTRL_SRC_INT, "getId", "()L" + CTRL_ID_INT + ";", false);
            lb.visitVarInsn(Opcodes.ASTORE, 2);
            lb.visitVarInsn(Opcodes.ALOAD, 2);
            lb.visitJumpInsn(Opcodes.IFNONNULL, lNonNullId);
            lb.visitLdcInsn("");
            lb.visitInsn(Opcodes.ARETURN);
            lb.visitLabel(lNonNullId);
            lb.visitVarInsn(Opcodes.ALOAD, 2);
            lb.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ID_INT, "getIdString", "()Ljava/lang/String;", false);
            lb.visitInsn(Opcodes.ARETURN);
            lb.visitMaxs(0, 0);
            lb.visitEnd();

            final MethodVisitor ts = cw.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
            ts.visitCode();
            final Label lHasDef = new Label();
            final Label lRetBase = new Label();
            ts.visitVarInsn(Opcodes.ALOAD, 0);
            ts.visitFieldInsn(Opcodes.GETFIELD, ITEM_INT, "deformer", "L" + DEF_SRC_INT + ";");
            ts.visitVarInsn(Opcodes.ASTORE, 1);
            ts.visitVarInsn(Opcodes.ALOAD, 1);
            ts.visitJumpInsn(Opcodes.IFNONNULL, lHasDef);
            ts.visitVarInsn(Opcodes.ALOAD, 0);
            ts.visitFieldInsn(Opcodes.GETFIELD, ITEM_INT, "indent", "Ljava/lang/String;");
            ts.visitLdcInsn("[Root]");
            ts.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/String",
                    "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    false);
            ts.visitInsn(Opcodes.ARETURN);
            ts.visitLabel(lHasDef);
            ts.visitVarInsn(Opcodes.ALOAD, 1);
            ts.visitMethodInsn(
                    Opcodes.INVOKESTATIC, ITEM_INT, "label", "(L" + CTRL_SRC_INT + ";)Ljava/lang/String;", false);
            ts.visitVarInsn(Opcodes.ASTORE, 2);
            ts.visitVarInsn(Opcodes.ALOAD, 0);
            ts.visitFieldInsn(Opcodes.GETFIELD, ITEM_INT, "indent", "Ljava/lang/String;");
            ts.visitVarInsn(Opcodes.ALOAD, 2);
            ts.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/String",
                    "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    false);
            ts.visitVarInsn(Opcodes.ASTORE, 3);
            if (hasAppSetting) {
                ts.visitFieldInsn(Opcodes.GETSTATIC, APP_SET_INT, "INSTANCE", "L" + APP_SET_INT + ";");
                ts.visitMethodInsn(Opcodes.INVOKEVIRTUAL, APP_SET_INT, "getDeformer", "()L" + DEF_SET_INT + ";", false);
                ts.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DEF_SET_INT, "getShowPartNameOfDeformer", "()Z", false);
                ts.visitJumpInsn(Opcodes.IFEQ, lRetBase);
                ts.visitVarInsn(Opcodes.ALOAD, 1);
                ts.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CTRL_SRC_INT, "getParent", "()L" + PART_SRC_INT + ";", false);
                ts.visitMethodInsn(
                        Opcodes.INVOKESTATIC, ITEM_INT, "label", "(L" + CTRL_SRC_INT + ";)Ljava/lang/String;", false);
                ts.visitVarInsn(Opcodes.ASTORE, 4);
                ts.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                ts.visitInsn(Opcodes.DUP);
                ts.visitVarInsn(Opcodes.ALOAD, 3);
                ts.visitMethodInsn(
                        Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
                ts.visitLdcInsn("  [");
                ts.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                        false);
                ts.visitVarInsn(Opcodes.ALOAD, 4);
                ts.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                        false);
                ts.visitLdcInsn("]");
                ts.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "java/lang/StringBuilder",
                        "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
                        false);
                ts.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                ts.visitInsn(Opcodes.ARETURN);
            }
            ts.visitLabel(lRetBase);
            ts.visitVarInsn(Opcodes.ALOAD, 3);
            ts.visitInsn(Opcodes.ARETURN);
            ts.visitMaxs(0, 0);
            ts.visitEnd();
            define(ITEM_INT, cw);
        }

        private void emitSelectorFactory() {
            final ClassWriter fw = newWriter(FACTORY_INT, "java/lang/Object");
            fw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "values", "Ljava/util/List;", null, null)
                    .visitEnd();
            fw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "inspector", "L" + INSP_INT + ";", null, null)
                    .visitEnd();
            fw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "combo", "L" + COMBO_INT + ";", null, null)
                    .visitEnd();
            final MethodVisitor init = fw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            init.visitFieldInsn(Opcodes.PUTFIELD, FACTORY_INT, "values", "Ljava/util/List;");
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitTypeInsn(Opcodes.NEW, INSP_INT);
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, INSP_INT, "<init>", "()V", false);
            init.visitFieldInsn(Opcodes.PUTFIELD, FACTORY_INT, "inspector", "L" + INSP_INT + ";");
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitTypeInsn(Opcodes.NEW, COMBO_INT);
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, COMBO_INT, "<init>", "()V", false);
            init.visitFieldInsn(Opcodes.PUTFIELD, FACTORY_INT, "combo", "L" + COMBO_INT + ";");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            emitFieldGetter(fw, FACTORY_INT, "getValues", "()Ljava/util/List;", "values");
            emitFieldGetter(fw, FACTORY_INT, "getInspector", "()L" + INSP_INT + ";", "inspector");
            emitFieldGetter(fw, FACTORY_INT, "getCombo", "()L" + COMBO_INT + ";", "combo");
            define(FACTORY_INT, fw);
        }

        private static ClassWriter newWriter(final String internalName, final String superName) {
            final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
            return cw;
        }

        private static void emitDefaultCtor(final ClassWriter cw, final String superName) {
            final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void emitListInitCtor(final ClassWriter cw, final String owner, final String fieldName) {
            final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
            mv.visitFieldInsn(Opcodes.PUTFIELD, owner, fieldName, "Ljava/util/List;");
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void emitFieldGetter(
                final ClassWriter cw, final String owner, final String method, final String desc, final String field) {
            final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, desc, null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, owner, field, desc.substring(2));
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void emitBooleanGetter(
                final ClassWriter cw, final String owner, final String method, final String field) {
            final MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, "()Z", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, owner, field, "Z");
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void emitIncrementField(final MethodVisitor mv, final String owner, final String field) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.DUP);
            mv.visitFieldInsn(Opcodes.GETFIELD, owner, field, "I");
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.IADD);
            mv.visitFieldInsn(Opcodes.PUTFIELD, owner, field, "I");
        }
    }
}
