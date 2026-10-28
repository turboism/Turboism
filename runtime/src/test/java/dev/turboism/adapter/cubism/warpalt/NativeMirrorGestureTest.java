package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Canvas;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import org.junit.jupiter.api.Test;

final class NativeMirrorGestureTest {
    @Test
    void pressUsesNativeComponentPointAndReleaseEndsGesture() throws Exception {
        var view = new TestView();
        var canvas = new Canvas();
        NativeWarpPointGesture.clear();
        try {
            NativeWarpPointGesture.input(
                    view,
                    new MouseEvent(
                            canvas,
                            MouseEvent.MOUSE_PRESSED,
                            0,
                            InputEvent.BUTTON1_DOWN_MASK,
                            10,
                            20,
                            1,
                            false,
                            MouseEvent.BUTTON1),
                    true);
            var field = NativeWarpPointGesture.class.getDeclaredField("CURRENT");
            field.setAccessible(true);
            var current = (ThreadLocal<?>) field.get(null);
            var gesture = (NativeWarpPointGesture.Gesture) current.get();
            assertNotNull(gesture, "Native camera conversion must capture the drag origin");
            assertEquals(120f, gesture.x);
            assertEquals(260f, gesture.y);
            NativeWarpPointGesture.input(
                    view,
                    new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 0, 0, 30, 40, 1, false, MouseEvent.BUTTON1),
                    true);
            assertNull(current.get());
        } finally {
            NativeWarpPointGesture.clear();
        }
    }

    public static final class TestView {
        public TestCamera getCameraManager() {
            return new TestCamera();
        }
    }

    public static final class TestCamera {
        public TestVector componentToDocument(com.live2d.type.CPoint point) {
            return new TestVector(100 + point.x() * 2, 200 + point.y() * 3);
        }
    }

    public record TestVector(float getX, float getY) {}

    @Test
    void eitherSideRemainsDriverRegardlessOfNativeIterationOrder() {
        for (int pressed = 0; pressed < 2; pressed++) {
            for (int first = 0; first < 2; first++) {
                var gesture = new NativeWarpPointGesture.Gesture(null, pressed * 10, 20);
                float[] positions = {0, 20, 10, 20};
                assertEquals(
                        pressed, gesture.driver(positions, first, 1 - first, first * 10, 20, (1 - first) * 10, 20));
                assertEquals(
                        pressed, gesture.driver(positions, 1 - first, first, (1 - first) * 10, 20, first * 10, 20));
                // Crossing the axis during a drag must not change which point follows the mouse.
                assertEquals(pressed, gesture.driver(positions, 0, 1, 30, 20, -30, 20));
            }
        }
    }

    @Test
    void pairingUsesCanvasPositionsAndDoesNotMixDifferentArrays() {
        var gesture = new NativeWarpPointGesture.Gesture(null, 100, 200);
        float[] first = {0, 0, 1, 0};
        float[] second = first.clone();
        assertEquals(1, gesture.driver(first, 0, 1, 500, 200, 100, 200));
        assertEquals(0, gesture.driver(second, 0, 1, 100, 200, 500, 200));
    }

    @Test
    void storedAwtEventIsReadWithoutCallingObfuscatedMethods() throws Exception {
        var event =
                new MouseEvent(new Canvas(), MouseEvent.MOUSE_DRAGGED, 0, InputEvent.ALT_DOWN_MASK, 10, 20, 0, false);
        var pack = new HostilePack(event);
        assertSame(event, NativeMirrorInput.awt(pack));
        assertEquals(0, pack.calls);
        assertSame(event, NativeMirrorInput.awt(event));
        assertNull(NativeMirrorInput.awt(new Object()));
        assertNull(NativeMirrorInput.awt(new AmbiguousPack(event)));
    }

    static final class HostilePack {
        private final InputEvent input;
        int calls;

        HostilePack(InputEvent input) {
            this.input = input;
        }

        public void ab() {
            calls++;
        }

        public boolean aD() {
            calls++;
            return false;
        }
    }

    static final class AmbiguousPack {
        private final InputEvent first;
        private final InputEvent second;

        AmbiguousPack(InputEvent event) {
            first = event;
            second = event;
        }
    }
}
