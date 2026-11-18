package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.live2d.cubism.view.context.action.U$b;
import java.awt.Canvas;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class NativeMirrorGestureTest {
    @ParameterizedTest
    @CsvSource({"1, 3, 0.75, 1.5", "2, 2, 1.25, 0.5"})
    void nativeDragReflectsBasePointRefsBeforeRelease(int axis, int counterpart, float expectedX, float expectedY) {
        var view = new TestView();
        var canvas = new Canvas();
        var form = new WarpForm(new float[] {0, 0, 1, 0, 2, 0, 0, 2, 1, 2, 2, 2});
        var driver = new BaseWarpRef(new WarpSource(), 0, form);
        var partner = new BaseWarpRef(driver.d(), counterpart, form);
        NativeWarpAltMirrorBridge.install();
        NativeWarpAltMirrorBridge.setWarpRefClassNameForTesting(BaseWarpRef.class.getName());
        NativeWarpAltMirrorBridge.setArmedAxis(axis);
        try (var registration = NativeWarpAltMirrorBridge.moveParticipation().participate()) {
            NativeWarpAltMirrorBridge.filterInput(
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
                            MouseEvent.BUTTON1));

            for (int tick = 0; tick < 2; tick++) {
                NativeWarpAltMirrorBridge.filterInput(
                        view,
                        new MouseEvent(
                                canvas,
                                MouseEvent.MOUSE_DRAGGED,
                                tick,
                                InputEvent.BUTTON1_DOWN_MASK,
                                11,
                                21,
                                0,
                                false));
                partner.drag(0.75f, 0.5f);
                driver.drag(0.75f, 0.5f);
            }

            assertEquals(new TestVector(0.75f, 0.5f), driver.getPos());
            assertEquals(new TestVector(expectedX, expectedY), partner.getPos());

            for (int tick = 2; tick < 10; tick++) {
                NativeWarpAltMirrorBridge.filterInput(
                        view,
                        new MouseEvent(
                                canvas,
                                MouseEvent.MOUSE_DRAGGED,
                                tick,
                                InputEvent.BUTTON1_DOWN_MASK,
                                11,
                                21,
                                0,
                                false));
                driver.drag(0.17f, 0.11f);
                partner.drag(0.17f, 0.11f);

                var point = driver.getPos();
                var mirrored = axis == 1
                        ? new TestVector(point.getX(), 2 - point.getY())
                        : new TestVector(2 - point.getX(), point.getY());
                assertEquals(mirrored, partner.getPos());
            }
        } finally {
            NativeWarpAltMirrorBridge.uninstall();
        }
    }

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

        public TestEditMode getCurrentEditMode() {
            return new TestEditMode();
        }
    }

    public static final class TestCamera {
        public TestVector componentToDocument(com.live2d.type.CPoint point) {
            return new TestVector(100 + point.x() * 2, 200 + point.y() * 3);
        }
    }

    public record TestVector(float getX, float getY) {}

    public static final class TestEditMode {
        public TestModel getCurrentModel() {
            return new TestModel();
        }
    }

    public static final class TestModel {}

    public static final class WarpSource {
        public int getCol() {
            return 2;
        }

        public int getRow() {
            return 1;
        }
    }

    public record WarpForm(float[] positions) {}

    public record BaseWarpRef(WarpSource d, int a, WarpForm f) {
        public float[] g() {
            return f.positions();
        }

        TestVector getPos() {
            return new TestVector(g()[a * 2], g()[a * 2 + 1]);
        }

        public TestVector getPosOnCanvas(TestModel model) {
            var point = getPos();
            return new TestVector(120 + 10 * point.getX(), 260 + 10 * point.getY());
        }

        void drag(float dx, float dy) {
            final float weight = 0.5f;
            var point = getPos();
            var target = new TestVector(point.getX() + dx, point.getY() + dy);
            if (!U$b.skipNativePointWrite(this, target, weight)) {
                g()[a * 2] = point.getX() * (1 - weight) + target.getX() * weight;
                g()[a * 2 + 1] = point.getY() * (1 - weight) + target.getY() * weight;
            }
        }
    }

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
