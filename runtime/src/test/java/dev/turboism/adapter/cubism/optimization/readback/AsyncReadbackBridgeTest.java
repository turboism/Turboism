package dev.turboism.adapter.cubism.optimization.readback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural test for the pipelined readback bridge using a fake GL implementation.
 *
 * <p>The fake records one "frame" per asynchronous read and hands the bound pixel-pack buffer back
 * from {@code glMapBuffer}, so the test observes exactly what the panel would paint: the frame that
 * was queued one call earlier. The bridge is reflective, so the fake only needs the public JOGL
 * method names and signatures.</p>
 */
class AsyncReadbackBridgeTest {

    private static final int WIDTH = 4;
    private static final int HEIGHT = 3;

    @AfterEach
    void disarm() {
        AsyncReadbackBridge.uninstall();
    }

    @Test
    void deliversThePreviousFrameAndNeverFallsBack() {
        AsyncReadbackBridge.install();
        final FakeGl gl = new FakeGl();

        final IntBuffer first = frameBuffer();
        AsyncReadbackBridge.read(gl, 0, 0, WIDTH, HEIGHT, 0x80E1, 0x1401, first);
        assertValues(first, 1, "first paint must be the real current frame");

        final IntBuffer second = frameBuffer();
        AsyncReadbackBridge.read(gl, 0, 0, WIDTH, HEIGHT, 0x80E1, 0x1401, second);
        assertValues(second, 1, "second paint must deliver the frame queued one call earlier");

        final IntBuffer third = frameBuffer();
        AsyncReadbackBridge.read(gl, 0, 0, WIDTH, HEIGHT, 0x80E1, 0x1401, third);
        assertValues(third, 2, "third paint must deliver the second frame");

        final Map<String, Long> stats = AsyncReadbackBridge.snapshot();
        assertEquals(1L, stats.get("active"));
        assertEquals(3L, stats.get("calls"));
        assertEquals(3L, stats.get("asyncReads"));
        assertEquals(2L, stats.get("maps"));
        assertEquals(1L, stats.get("syncReads"), "only the priming paint reads synchronously");
        assertEquals(0L, stats.get("fallbacks"));
        assertEquals(0L, stats.get("failures"));
    }

    @Test
    void inactiveBridgeReadsSynchronously() {
        final FakeGl gl = new FakeGl();
        final IntBuffer first = frameBuffer();
        AsyncReadbackBridge.read(gl, 0, 0, WIDTH, HEIGHT, 0x80E1, 0x1401, first);
        assertValues(first, 1, "an inactive bridge must pass the real frame through");
        assertEquals(0L, AsyncReadbackBridge.snapshot().get("asyncReads"));
    }

    @Test
    void resizeReallocatesAndReprimes() {
        AsyncReadbackBridge.install();
        final FakeGl gl = new FakeGl();
        final IntBuffer first = frameBuffer();
        AsyncReadbackBridge.read(gl, 0, 0, WIDTH, HEIGHT, 0x80E1, 0x1401, first);
        final IntBuffer second = frameBuffer();
        AsyncReadbackBridge.read(gl, 0, 0, WIDTH, HEIGHT, 0x80E1, 0x1401, second);

        final IntBuffer resized = IntBuffer.allocate(WIDTH * HEIGHT * 2);
        AsyncReadbackBridge.read(gl, 0, 0, WIDTH, HEIGHT * 2, 0x80E1, 0x1401, resized);
        assertValues(resized, 3, "a resize must prime the new buffers with the current frame");

        final Map<String, Long> stats = AsyncReadbackBridge.snapshot();
        assertEquals(0L, stats.get("fallbacks"));
        assertEquals(2L, stats.get("syncReads"), "initial prime plus resize prime");
    }

    private static IntBuffer frameBuffer() {
        return IntBuffer.allocate(WIDTH * HEIGHT);
    }

    private static void assertValues(final IntBuffer buffer, final int expected, final String message) {
        for (int i = 0; i < buffer.capacity(); i++) {
            assertEquals(expected, buffer.get(i), message + " (index " + i + ")");
        }
        assertEquals(0, buffer.position(), "delivered buffers must be rewound");
    }

    /** Minimal GL surface: one frame counter, one bound buffer name, direct pixel-pack buffers. */
    public static final class FakeGl {
        private final Map<Integer, ByteBuffer> pixelPacks = new HashMap<>();
        private int nextName = 1;
        private int bound;
        private int frame;
        private int currentFrame;

        public void glBindBuffer(final int target, final int name) {
            bound = name;
        }

        public void glReadPixels(
                final int x,
                final int y,
                final int width,
                final int height,
                final int format,
                final int type,
                final long offset) {
            currentFrame = ++frame;
            final ByteBuffer pixels = pixelPacks.get(bound);
            if (pixels == null) {
                throw new IllegalStateException("pixel-pack buffer not allocated: " + bound);
            }
            fill(pixels, currentFrame);
        }

        public void glReadPixels(
                final int x,
                final int y,
                final int width,
                final int height,
                final int format,
                final int type,
                final Buffer destination) {
            if (currentFrame == 0) {
                currentFrame = ++frame;
            }
            destination.position(0);
            if (destination instanceof IntBuffer ints) {
                for (int i = 0; i < ints.capacity(); i++) {
                    ints.put(i, currentFrame);
                }
            } else {
                throw new IllegalStateException("unexpected destination: " + destination.getClass());
            }
        }

        public ByteBuffer glMapBuffer(final int target, final int access) {
            return pixelPacks.get(bound);
        }

        public boolean glUnmapBuffer(final int target) {
            return true;
        }

        public void glGenBuffers(final int count, final int[] names, final int offset) {
            for (int i = 0; i < count; i++) {
                names[offset + i] = nextName++;
            }
        }

        public void glBufferData(final int target, final long size, final Buffer data, final int usage) {
            final ByteBuffer pixels = ByteBuffer.allocateDirect((int) size);
            fill(pixels, 0);
            pixelPacks.put(bound, pixels);
        }

        public void glDeleteBuffers(final int count, final int[] names, final int offset) {
            for (int i = 0; i < count; i++) {
                pixelPacks.remove(names[offset + i]);
            }
        }

        private static void fill(final ByteBuffer pixels, final int value) {
            final IntBuffer ints =
                    pixels.order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
            for (int i = 0; i < ints.capacity(); i++) {
                ints.put(i, value);
            }
        }
    }

    @Test
    void snapshotIsPublishedWhileInstalled() {
        AsyncReadbackBridge.install();
        assertTrue(
                System.getProperties().get(AsyncReadbackBridge.STATS_PROPERTY)
                        instanceof java.util.function.Supplier<?>);
        AsyncReadbackBridge.uninstall();
        assertEquals(null, System.getProperties().get(AsyncReadbackBridge.STATS_PROPERTY));
    }
}
