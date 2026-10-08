package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.model.Point2;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

class RuntimeSelectionBrushTest {
    private static final int SHIFT = InputEvent.SHIFT_DOWN_MASK;
    private static final int CONTROL = InputEvent.CTRL_DOWN_MASK;
    private static final int ALT = InputEvent.ALT_DOWN_MASK;

    @Test
    void activationOwnsOneKeyboardDispatcherAndClosePreservesOtherDispatchers() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final KeyboardFocusManager previous = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            final InspectableKeyboard keyboard = new InspectableKeyboard();
            final KeyEventDispatcher unrelated = event -> false;
            keyboard.addKeyEventDispatcher(unrelated);
            final RecordingHost host = new RecordingHost(List.of());
            final RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
            KeyboardFocusManager.setCurrentKeyboardFocusManager(keyboard);
            try {
                brush.install();
                brush.install();
                assertEquals(2, keyboard.dispatchers().size(), "one interceptor per activation");
                assertTrue(keyboard.dispatchers().contains(unrelated));
                brush.close();
                brush.close();
                assertEquals(List.of(unrelated), keyboard.dispatchers(), "close removes only its interceptor");
                assertEquals(0, host.view.getComponentCount());
            } finally {
                brush.close();
                KeyboardFocusManager.setCurrentKeyboardFocusManager(previous);
            }
        });
    }

    @Test
    void closeInterruptedWhileEdtWedgedStillRunsCleanupExactlyOnce() throws Exception {
        final KeyboardFocusManager previous = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        final InspectableKeyboard keyboard = new InspectableKeyboard();
        final RecordingHost host = new RecordingHost(List.of());
        final SelectionBrushRoute route = new SelectionBrushRoute(host);
        javax.swing.SwingUtilities.invokeAndWait(() -> KeyboardFocusManager.setCurrentKeyboardFocusManager(keyboard));
        route.install();
        final CountDownLatch wedged = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        javax.swing.SwingUtilities.invokeLater(() -> {
            wedged.countDown();
            await(release);
        });
        try {
            assertTrue(wedged.await(5, TimeUnit.SECONDS));
            assertEquals(1, keyboard.dispatchers().size(), "install must register the key dispatcher");

            final CountDownLatch callerDone = new CountDownLatch(1);
            final AtomicReference<Throwable> outcome = new AtomicReference<>();
            final Thread caller = new Thread(
                    () -> {
                        try {
                            route.close();
                        } catch (Throwable failure) {
                            outcome.set(failure);
                        } finally {
                            callerDone.countDown();
                        }
                    },
                    "selection-brush-close-caller");
            caller.setDaemon(true);
            caller.start();
            caller.interrupt();
            assertTrue(callerDone.await(5, TimeUnit.SECONDS), "an interrupted close must not keep waiting");
            assertNull(outcome.get(), "an interrupted close defers the cleanup instead of failing");
            assertEquals(1, keyboard.dispatchers().size(), "the dispatcher stays while the EDT is wedged");
            route.close(); // a repeat close is a no-op; the queued cleanup must still run exactly once
            release.countDown();
            drainEdt();
            assertEquals(List.of(), keyboard.dispatchers(), "cleanup removes the key dispatcher");
            assertEquals(0, host.view.getComponentCount(), "cleanup removes the overlay");
            assertEquals(1, host.endStrokes.get(), "cleanup must run exactly once");
        } finally {
            release.countDown();
            route.close();
            javax.swing.SwingUtilities.invokeAndWait(
                    () -> KeyboardFocusManager.setCurrentKeyboardFocusManager(previous));
        }
    }

    @Test
    void pressSnapshotsRadiusDragUsesRoundCapsAndReleaseCommitsOneAdd() {
        RecordingHost host = new RecordingHost(List.of(new Point2(10, 10), new Point2(50, 10), new Point2(90, 10)));
        RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
        brush.install();
        JComponent overlay = brush.overlayForTests();

        MouseEvent press =
                mouse(overlay, MouseEvent.MOUSE_PRESSED, 10, 10, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK);
        overlay.dispatchEvent(press);
        brush.setRadiusPixels(8);
        overlay.dispatchEvent(
                mouse(overlay, MouseEvent.MOUSE_DRAGGED, 60, 10, MouseEvent.NOBUTTON, InputEvent.BUTTON1_DOWN_MASK));
        overlay.dispatchEvent(mouse(overlay, MouseEvent.MOUSE_RELEASED, 60, 10, MouseEvent.BUTTON1, 0));

        assertTrue(press.isConsumed());
        assertEquals(List.of(new Commit(List.of(0, 1, 2), SelectionMode.ADD)), host.commits);
        assertFalse(brush.previewVisibleForTests());
        brush.close();
    }

    @Test
    void capturesPrimaryInputButForwardsNonPrimaryAndWheelExactlyOnce() {
        RecordingHost host = new RecordingHost(List.of());
        AtomicInteger forwardedMouse = new AtomicInteger();
        AtomicInteger forwardedWheel = new AtomicInteger();
        host.view.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                forwardedMouse.incrementAndGet();
            }
        });
        host.view.addMouseWheelListener(event -> forwardedWheel.incrementAndGet());
        RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
        brush.install();
        JComponent overlay = brush.overlayForTests();

        overlay.dispatchEvent(
                mouse(overlay, MouseEvent.MOUSE_PRESSED, 2, 2, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK));
        assertEquals(0, forwardedMouse.get());
        overlay.dispatchEvent(
                mouse(overlay, MouseEvent.MOUSE_PRESSED, 2, 2, MouseEvent.BUTTON3, InputEvent.BUTTON3_DOWN_MASK));
        overlay.dispatchEvent(new MouseWheelEvent(
                overlay, MouseEvent.MOUSE_WHEEL, 1L, 0, 2, 2, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1));

        assertEquals(1, forwardedMouse.get());
        assertEquals(1, forwardedWheel.get());
        brush.close();
    }

    @Test
    void nativeCanvasConfirmationReceivesClickWhileBrushIsActiveAndClosesTheOverlay() throws Exception {
        RecordingHost host = new RecordingHost(List.of(new Point2(275, 20)));
        host.view.setSize(320, 200);
        host.nativeControlBounds = new java.awt.Rectangle(250, 8, 50, 30);
        AtomicInteger confirmations = new AtomicInteger();
        RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
        brush.install();
        // Cubism draws its confirmation as a GL entity in the same component, not a JButton.
        host.view.addMouseListener(new MouseAdapter() {
            private boolean pressed;

            @Override
            public void mousePressed(MouseEvent event) {
                pressed = host.nativeControlAt(event.getX(), event.getY());
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (pressed && host.nativeControlAt(event.getX(), event.getY())) {
                    confirmations.incrementAndGet();
                    host.current = false;
                    brush.close();
                }
            }
        });
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                JComponent target = (JComponent) javax.swing.SwingUtilities.getDeepestComponentAt(host.view, 275, 20);
                target.dispatchEvent(mouse(
                        target, MouseEvent.MOUSE_PRESSED, 275, 20, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK));
                target.dispatchEvent(mouse(target, MouseEvent.MOUSE_RELEASED, 275, 20, MouseEvent.BUTTON1, 0));
            });
            assertEquals(1, confirmations.get(), "the native check must receive the actual press/release");
            assertTrue(host.commits.isEmpty(), "a native control click must never be a vertex-selection stroke");
            assertFalse(host.current);
            assertEquals(0, host.view.getComponentCount(), "ending mesh edit must remove the brush overlay");
        } finally {
            brush.close();
        }
    }

    @Test
    void nativeControlHitRegionIsReadAgainAfterLayoutChanges() {
        RecordingHost host = new RecordingHost(List.of());
        host.view.setSize(320, 200);
        host.nativeControlBounds = new java.awt.Rectangle(250, 8, 50, 30);
        RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
        brush.install();
        try {
            JComponent overlay = brush.overlayForTests();
            assertFalse(overlay.contains(275, 20));
            assertTrue(overlay.contains(80, 60));
            host.nativeControlBounds = new java.awt.Rectangle(10, 150, 50, 30);
            assertTrue(overlay.contains(275, 20), "old button coordinates must return to the brush");
            assertFalse(overlay.contains(35, 165), "the moved native button must retain its input");
            host.nativeControlBounds = null;
            assertTrue(overlay.contains(35, 165), "a hidden native control must not reserve the canvas");
        } finally {
            brush.close();
        }
    }

    @Test
    void brushStrokeRetainsItsPressTargetWhenDraggedAcrossNativeConfirmation() {
        RecordingHost host = new RecordingHost(List.of(new Point2(80, 60)));
        host.view.setSize(320, 200);
        host.nativeControlBounds = new java.awt.Rectangle(250, 8, 50, 30);
        AtomicInteger forwarded = new AtomicInteger();
        host.view.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                forwarded.incrementAndGet();
            }
        });
        RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
        brush.install();
        try {
            JComponent overlay = brush.overlayForTests();
            overlay.dispatchEvent(
                    mouse(overlay, MouseEvent.MOUSE_PRESSED, 80, 60, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK));
            overlay.dispatchEvent(mouse(
                    overlay, MouseEvent.MOUSE_DRAGGED, 275, 20, MouseEvent.NOBUTTON, InputEvent.BUTTON1_DOWN_MASK));
            overlay.dispatchEvent(mouse(overlay, MouseEvent.MOUSE_RELEASED, 275, 20, MouseEvent.BUTTON1, 0));
            assertEquals(0, forwarded.get(), "a stroke release must not click the native confirmation");
            assertEquals(List.of(new Commit(List.of(0), SelectionMode.ADD)), host.commits);
            assertFalse(brush.previewVisibleForTests());
        } finally {
            brush.close();
        }
    }

    @Test
    void failedNativeControlReadLeavesInputWithTheHost() {
        RecordingHost host = new RecordingHost(List.of());
        host.view.setSize(320, 200);
        host.controlFailure = new IllegalStateException("native scene unavailable");
        RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
        brush.install();
        try {
            JComponent overlay = brush.overlayForTests();
            assertFalse(overlay.contains(80, 60));
            assertFalse(overlay.contains(80, 60));
            assertTrue(host.commits.isEmpty());
            host.controlFailure = null;
            assertTrue(overlay.contains(80, 60));
        } finally {
            brush.close();
        }
    }

    @Test
    void zeroHitReleaseCloseMidStrokeEscapeAndProjectionFailureNeverCommit() {
        RecordingHost host = new RecordingHost(List.of(new Point2(1000, 1000)));
        RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
        brush.install();
        JComponent overlay = brush.overlayForTests();
        overlay.dispatchEvent(
                mouse(overlay, MouseEvent.MOUSE_PRESSED, 1, 1, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK));
        overlay.dispatchEvent(mouse(overlay, MouseEvent.MOUSE_RELEASED, 1, 1, MouseEvent.BUTTON1, 0));
        assertTrue(host.commits.isEmpty());

        overlay.dispatchEvent(
                mouse(overlay, MouseEvent.MOUSE_PRESSED, 1, 1, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK));
        brush.close();
        assertTrue(host.commits.isEmpty());
        assertEquals(0, host.view.getComponentCount());

        RuntimeSelectionBrush escaped = new RuntimeSelectionBrush(host);
        escaped.install();
        Object actionKey = escaped.overlayForTests()
                .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        escaped.overlayForTests().getActionMap().get(actionKey).actionPerformed(null);
        assertEquals(1, host.deactivations.get());
        escaped.close();

        host.failure = new IllegalStateException("projection unavailable");
        RuntimeSelectionBrush failing = new RuntimeSelectionBrush(host);
        failing.install();
        assertDoesNotThrow(() -> failing.overlayForTests()
                .dispatchEvent(mouse(
                        failing.overlayForTests(),
                        MouseEvent.MOUSE_PRESSED,
                        1,
                        1,
                        MouseEvent.BUTTON1,
                        InputEvent.BUTTON1_DOWN_MASK)));
        assertTrue(host.commits.isEmpty());
        failing.close();
    }

    @Test
    void projectionFailureStillShowsTheTrailAndCommitsNothing() {
        // Regression: the preview used to start only after a successful projection, so a failing
        // projection cancelled the stroke and the operator saw no overlay at all.
        RecordingHost host = new RecordingHost(List.of(new Point2(10, 10)));
        host.failure = new IllegalStateException("evaluated join unavailable");
        RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
        brush.install();
        JComponent overlay = brush.overlayForTests();

        overlay.dispatchEvent(
                mouse(overlay, MouseEvent.MOUSE_PRESSED, 10, 10, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK));
        assertTrue(brush.previewVisibleForTests(), "the trail must not depend on mesh data");
        overlay.dispatchEvent(
                mouse(overlay, MouseEvent.MOUSE_DRAGGED, 40, 10, MouseEvent.NOBUTTON, InputEvent.BUTTON1_DOWN_MASK));
        assertTrue(brush.previewVisibleForTests());
        overlay.dispatchEvent(mouse(overlay, MouseEvent.MOUSE_RELEASED, 40, 10, MouseEvent.BUTTON1, 0));

        assertTrue(host.commits.isEmpty(), "no hits may be committed when hit testing was unavailable");
        assertFalse(brush.previewVisibleForTests(), "the trail must still clear on release");
        brush.close();
    }

    @Test
    void aPartialHitThenProjectionFailureInvalidatesTheEntireStrokeAndNextStrokeRecovers() {
        RecordingHost host = new RecordingHost(List.of(new Point2(10, 10)));
        RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
        brush.install();
        try {
            JComponent overlay = brush.overlayForTests();
            overlay.dispatchEvent(
                    mouse(overlay, MouseEvent.MOUSE_PRESSED, 10, 10, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK));
            assertTrue(host.commits.isEmpty(), "selection is unchanged until release");
            host.failure = new IllegalStateException("projection failed during drag");
            overlay.dispatchEvent(mouse(
                    overlay, MouseEvent.MOUSE_DRAGGED, 40, 10, MouseEvent.NOBUTTON, InputEvent.BUTTON1_DOWN_MASK));
            assertTrue(brush.previewVisibleForTests());
            host.failure = null;
            overlay.dispatchEvent(mouse(
                    overlay, MouseEvent.MOUSE_DRAGGED, 10, 10, MouseEvent.NOBUTTON, InputEvent.BUTTON1_DOWN_MASK));
            overlay.dispatchEvent(mouse(overlay, MouseEvent.MOUSE_RELEASED, 10, 10, MouseEvent.BUTTON1, 0));
            assertTrue(host.commits.isEmpty(), "earlier hits and recovered projection cannot rescue a failed stroke");
            assertFalse(brush.previewVisibleForTests());
            overlay.dispatchEvent(
                    mouse(overlay, MouseEvent.MOUSE_PRESSED, 10, 10, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK));
            overlay.dispatchEvent(mouse(overlay, MouseEvent.MOUSE_RELEASED, 10, 10, MouseEvent.BUTTON1, 0));
            assertEquals(List.of(new Commit(List.of(0), SelectionMode.ADD)), host.commits);
        } finally {
            brush.close();
        }
    }

    @Test
    void staleActivationFailsClosedAndCannotChangeRadiusOrCommit() {
        RecordingHost host = new RecordingHost(List.of(new Point2(1, 1)));
        RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
        brush.install();
        host.current = false;
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> brush.setRadiusPixels(64));
        brush.overlayForTests()
                .dispatchEvent(mouse(
                        brush.overlayForTests(),
                        MouseEvent.MOUSE_PRESSED,
                        1,
                        1,
                        MouseEvent.BUTTON1,
                        InputEvent.BUTTON1_DOWN_MASK));
        assertFalse(brush.previewVisibleForTests());
        assertTrue(host.commits.isEmpty());
        brush.close();
    }

    @Test
    void overlayStaysTopmostTracksViewBoundsAndCloseIsIdempotent() throws Exception {
        RecordingHost host = new RecordingHost(List.of());
        host.view.setSize(320, 200);
        host.view.setLayout(new java.awt.FlowLayout());
        host.view.add(new JPanel());
        RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
        brush.install();
        JComponent overlay = brush.overlayForTests();
        assertSame(overlay, host.view.getComponent(0));
        assertEquals(320, overlay.getWidth());
        assertEquals(200, overlay.getHeight());
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            host.view.doLayout();
            assertEquals(
                    new java.awt.Rectangle(0, 0, 320, 200),
                    overlay.getBounds(),
                    "hit-test coordinates must stay valid before queued component events run");
        });
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
        assertEquals(
                new java.awt.Rectangle(0, 0, 320, 200),
                overlay.getBounds(),
                "a later native layout pass must not shrink the brush's input surface");
        assertSame(overlay, host.view.getComponentAt(160, 100));
        host.view.setSize(640, 480);
        host.view.dispatchEvent(
                new java.awt.event.ComponentEvent(host.view, java.awt.event.ComponentEvent.COMPONENT_RESIZED));
        assertEquals(640, overlay.getWidth());
        assertEquals(480, overlay.getHeight());
        brush.close();
        brush.close();
        assertEquals(1, host.view.getComponentCount());
        assertEquals(0, overlay.getComponentListeners().length);
    }

    @Test
    void pressTimeModifiersLockTheStrokeAndCtrlWinsOverShift() {
        RecordingHost host = new RecordingHost(List.of(new Point2(10, 10), new Point2(50, 10)));
        host.tool = nativeConsistentTool();
        RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
        brush.install();
        JComponent overlay = brush.overlayForTests();

        overlay.dispatchEvent(mouse(
                overlay, MouseEvent.MOUSE_PRESSED, 10, 10, MouseEvent.BUTTON1, InputEvent.BUTTON1_DOWN_MASK | SHIFT));
        overlay.dispatchEvent(mouse(
                overlay,
                MouseEvent.MOUSE_DRAGGED,
                50,
                10,
                MouseEvent.NOBUTTON,
                InputEvent.BUTTON1_DOWN_MASK | CONTROL | ALT));
        overlay.dispatchEvent(mouse(overlay, MouseEvent.MOUSE_RELEASED, 50, 10, MouseEvent.BUTTON1, 0));

        assertEquals(
                List.of(new Commit(List.of(0, 1), SelectionMode.ADD)),
                host.commits,
                "the press-time mode must survive mid-stroke modifier changes");

        overlay.dispatchEvent(mouse(
                overlay,
                MouseEvent.MOUSE_PRESSED,
                10,
                10,
                MouseEvent.BUTTON1,
                InputEvent.BUTTON1_DOWN_MASK | SHIFT | CONTROL));
        overlay.dispatchEvent(mouse(overlay, MouseEvent.MOUSE_RELEASED, 10, 10, MouseEvent.BUTTON1, 0));

        assertEquals(SelectionMode.REMOVE, host.commits.get(1).mode(), "Ctrl must win over Shift");
        brush.close();
    }

    @Test
    void everyModifierCombinationCommitsTheModeDeclaredByTheContributingTool() {
        RecordingHost host = new RecordingHost(List.of(new Point2(10, 10)));
        host.tool = nativeConsistentTool();
        RuntimeSelectionBrush brush = new RuntimeSelectionBrush(host);
        brush.install();
        JComponent overlay = brush.overlayForTests();

        stroke(overlay, 0, 0);
        stroke(overlay, 0, SHIFT);
        stroke(overlay, 0, CONTROL);
        stroke(overlay, 0, ALT);

        assertEquals(
                List.of(
                        new Commit(List.of(0), SelectionMode.REPLACE),
                        new Commit(List.of(0), SelectionMode.ADD),
                        new Commit(List.of(0), SelectionMode.REMOVE),
                        new Commit(List.of(0), SelectionMode.REPLACE)),
                host.commits,
                "the official mapping must reach the native commit and Alt must stay meaningless");
        brush.close();
    }

    @Test
    void productionStrokeModeDelegatesToTheContributingToolAndDefaultsToAdd() {
        assertEquals(
                SelectionMode.REPLACE,
                RuntimeSelectionBrush.strokeModeFor(nativeConsistentTool(), false, false, false));
        assertEquals(
                SelectionMode.ADD, RuntimeSelectionBrush.strokeModeFor(nativeConsistentTool(), true, false, false));
        assertEquals(
                SelectionMode.REMOVE, RuntimeSelectionBrush.strokeModeFor(nativeConsistentTool(), false, true, false));
        assertEquals(
                SelectionMode.REMOVE, RuntimeSelectionBrush.strokeModeFor(nativeConsistentTool(), true, true, true));

        assertEquals(
                SelectionMode.ADD,
                RuntimeSelectionBrush.strokeModeFor(legacyTool(), false, false, false),
                "a tool that does not declare a gesture policy must keep the framework default");
    }

    private void stroke(JComponent overlay, int pressX, int modifiers) {
        overlay.dispatchEvent(mouse(
                overlay,
                MouseEvent.MOUSE_PRESSED,
                pressX + 10,
                10,
                MouseEvent.BUTTON1,
                InputEvent.BUTTON1_DOWN_MASK | modifiers));
        overlay.dispatchEvent(mouse(overlay, MouseEvent.MOUSE_RELEASED, pressX + 10, 10, MouseEvent.BUTTON1, 0));
    }

    private static MeshTool nativeConsistentTool() {
        return new MeshTool() {
            @Override
            public String id() {
                return "selection-brush";
            }

            @Override
            public String label() {
                return "Selection Brush";
            }

            @Override
            public String iconResourcePath() {
                return "icons/selection-brush.png";
            }

            @Override
            public void activate(final MeshToolContext context) {}

            @Override
            public SelectionMode strokeSelectionMode(
                    final boolean shiftDown, final boolean controlDown, final boolean altDown) {
                if (controlDown) return SelectionMode.REMOVE;
                return shiftDown ? SelectionMode.ADD : SelectionMode.REPLACE;
            }
        };
    }

    private static MeshTool legacyTool() {
        return new MeshTool() {
            @Override
            public String id() {
                return "legacy";
            }

            @Override
            public String label() {
                return "Legacy";
            }

            @Override
            public String iconResourcePath() {
                return "icons/legacy.png";
            }

            @Override
            public void activate(final MeshToolContext context) {}
        };
    }

    private static MouseEvent mouse(JComponent source, int id, int x, int y, int button, int modifiers) {
        return new MouseEvent(source, id, 1L, modifiers, x, y, 1, false, button);
    }

    private static void drainEdt() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private record Commit(List<Integer> indices, SelectionMode mode) {}

    private static final class InspectableKeyboard extends DefaultKeyboardFocusManager {
        List<KeyEventDispatcher> dispatchers() {
            final List<KeyEventDispatcher> current = getKeyEventDispatchers();
            return current == null ? List.of() : List.copyOf(current);
        }
    }

    private static final class RecordingHost implements RuntimeSelectionBrush.Host {
        final JPanel view = new JPanel(null);
        final List<Commit> commits = new ArrayList<>();
        final AtomicInteger deactivations = new AtomicInteger();
        final AtomicInteger endStrokes = new AtomicInteger();
        MeshTool tool = legacyTool();
        List<Point2> vertices;
        RuntimeException failure;
        RuntimeException controlFailure;
        java.awt.Rectangle nativeControlBounds;
        boolean current = true;

        RecordingHost(List<Point2> vertices) {
            this.vertices = vertices;
        }

        @Override
        public JComponent component() {
            return view;
        }

        @Override
        public boolean nativeControlAt(int x, int y) {
            if (controlFailure != null) throw controlFailure;
            return nativeControlBounds != null && nativeControlBounds.contains(x, y);
        }

        @Override
        public List<Point2> projectedVertices() {
            if (failure != null) throw failure;
            return vertices;
        }

        @Override
        public void commitSelection(List<Integer> indices, SelectionMode mode) {
            commits.add(new Commit(List.copyOf(indices), mode));
        }

        @Override
        public SelectionMode selectionMode(boolean shiftDown, boolean controlDown, boolean altDown) {
            return tool.strokeSelectionMode(shiftDown, controlDown, altDown);
        }

        @Override
        public void endStroke() {
            endStrokes.incrementAndGet();
        }

        @Override
        public boolean revalidate() {
            return current;
        }

        @Override
        public void deactivate() {
            deactivations.incrementAndGet();
        }
    }
}
