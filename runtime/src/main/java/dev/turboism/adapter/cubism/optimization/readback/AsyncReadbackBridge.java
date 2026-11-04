package dev.turboism.adapter.cubism.optimization.readback;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Pipelined pixel-pack-buffer readback for the rewritten JOGL {@code postGL}.
 *
 * <p>Frame N is read asynchronously into {@code PBO[N % 2]} and the caller receives frame N-1 from
 * {@code PBO[(N-1) % 2]}. The panel therefore keeps painting the previous frame and the EDT never
 * waits for the GPU to finish the frame it just queued. The first read after installation or after
 * a panel resize is synchronous, so there is always a real image to deliver.</p>
 *
 * <p>The bridge deliberately references no host type: the rewritten call passes the {@code GL}
 * instance as {@link Object} (assignable at the call site, so the verifier accepts it) and every GL
 * entry point is resolved reflectively once per GL class. Seven reflective calls per painted frame
 * are irrelevant next to a frame that costs tens of milliseconds, and it keeps this module free of
 * a compile-time JOGL dependency.</p>
 *
 * <p>Fail-closed: any doubt (inactive hook, unsupported destination, unmappable buffer, driver
 * error) falls back to the native client-memory read, and every fallback is counted. The GL
 * instance is held weakly, so nothing host-owned is retained.</p>
 */
public final class AsyncReadbackBridge {

    /** Production opt-in switch, persisted by the managed launcher as {@code launcher.asyncReadback}. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.asyncReadback";
    /** Diagnostics slot read by the perf harness; a {@code Supplier<Map<String,Long>>}. */
    public static final String STATS_PROPERTY = "turboism.async-readback.stats";

    private static final int GL_PIXEL_PACK_BUFFER = 0x88EB;
    private static final int GL_READ_ONLY = 0x88B8;
    private static final int GL_STREAM_READ = 0x88E1;
    private static final int BYTES_PER_PIXEL = 4;

    private static final AtomicBoolean active = new AtomicBoolean();
    private static final LongAdder calls = new LongAdder();
    private static final LongAdder asyncReads = new LongAdder();
    private static final LongAdder syncReads = new LongAdder();
    private static final LongAdder mapped = new LongAdder();
    private static final LongAdder fallbacks = new LongAdder();
    private static final LongAdder bytes = new LongAdder();
    private static final LongAdder failures = new LongAdder();
    private static final LongAdder verified = new LongAdder();
    private static final LongAdder mismatched = new LongAdder();

    private static volatile WeakReference<Object> boundGl;
    private static volatile State state = new State();

    private AsyncReadbackBridge() {}

    /**
     * Replacement for the GLSL-path {@code GL.glReadPixels(int, int, int, int, int, int, Buffer)}.
     *
     * <p>Called from rewritten JOGL bytecode with the operands already on the stack. The receiver
     * is typed {@link Object} so this module needs no JOGL dependency; a {@code GL} is assignable
     * to it, so the call site verifies.</p>
     */
    public static void read(
            final Object gl,
            final int x,
            final int y,
            final int width,
            final int height,
            final int format,
            final int type,
            final Buffer destination) {
        calls.increment();
        if (!active.get()) {
            nativeRead(gl, x, y, width, height, format, type, destination);
            return;
        }
        try {
            pipelined(gl, x, y, width, height, format, type, destination);
        } catch (final Throwable problem) {
            FatalErrors.rethrowIfFatal(problem);
            failures.increment();
            fallbacks.increment();
            try {
                GlCalls.bindBuffer(gl, GL_PIXEL_PACK_BUFFER, 0);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                // The fallback read below is the last resort; a broken unbind must not mask it.
            }
            nativeRead(gl, x, y, width, height, format, type, destination);
        }
    }

    private static void pipelined(
            final Object gl,
            final int x,
            final int y,
            final int width,
            final int height,
            final int format,
            final int type,
            final Buffer destination) {
        final WeakReference<Object> reference = boundGl;
        State current = state;
        if (reference == null || reference.get() != gl) {
            current = reset(false);
            boundGl = new WeakReference<>(gl);
        } else if (current.failed) {
            current = reset(true);
        }
        final int expected = width * height * BYTES_PER_PIXEL;
        if (current.names[0] == 0 || current.bytes != expected) {
            allocate(gl, current, expected);
        }

        // 1. Queue the current frame; this call does not wait for the GPU.
        GlCalls.bindBuffer(gl, GL_PIXEL_PACK_BUFFER, current.names[current.cur]);
        GlCalls.readPixelsIntoBuffer(gl, x, y, width, height, format, type);
        GlCalls.bindBuffer(gl, GL_PIXEL_PACK_BUFFER, 0);
        asyncReads.increment();
        bytes.add(expected);

        // 2. Deliver the frame that was queued one call earlier.
        if (!current.primed) {
            nativeRead(gl, x, y, width, height, format, type, destination);
            current.primed = true;
            captureVerificationFrame(current, destination);
        } else {
            GlCalls.bindBuffer(gl, GL_PIXEL_PACK_BUFFER, current.names[1 - current.cur]);
            final ByteBuffer image = GlCalls.mapBuffer(gl, GL_PIXEL_PACK_BUFFER);
            if (image == null) {
                GlCalls.bindBuffer(gl, GL_PIXEL_PACK_BUFFER, 0);
                current.failed = true;
                fallbacks.increment();
                nativeRead(gl, x, y, width, height, format, type, destination);
                return;
            }
            copy(image, destination, expected);
            GlCalls.unmapBuffer(gl, GL_PIXEL_PACK_BUFFER);
            GlCalls.bindBuffer(gl, GL_PIXEL_PACK_BUFFER, 0);
            mapped.increment();
            verifyPipelinedFrame(current, destination);
        }
        current.cur = 1 - current.cur;
    }

    /** Client-memory read; JOGL range-checks {@code remaining()}, so always rewind first. */
    private static void nativeRead(
            final Object gl,
            final int x,
            final int y,
            final int width,
            final int height,
            final int format,
            final int type,
            final Buffer destination) {
        destination.position(0);
        GlCalls.readPixelsIntoClient(gl, x, y, width, height, format, type, destination);
        destination.position(0);
        syncReads.increment();
    }

    /**
     * Keeps the primed frame so the first pipelined delivery can be compared against it: both
     * reads see the same FBO, so a mismatch means the pipeline itself is wrong.
     */
    private static void captureVerificationFrame(final State current, final Buffer destination) {
        if (current.verify != null || !(destination instanceof IntBuffer ints)) {
            return;
        }
        current.verify = new int[ints.capacity()];
        for (int i = 0; i < current.verify.length; i++) {
            current.verify[i] = ints.get(i);
        }
        current.verifyPending = true;
    }

    private static void verifyPipelinedFrame(final State current, final Buffer destination) {
        if (!current.verifyPending || !(destination instanceof IntBuffer ints)) {
            return;
        }
        current.verifyPending = false;
        int differences = 0;
        for (int i = 0; i < current.verify.length; i++) {
            if (ints.get(i) != current.verify[i]) {
                differences++;
            }
        }
        verified.increment();
        if (differences != 0) {
            mismatched.add(differences);
            // The pipelined bytes must equal the bytes the synchronous read produced for the same
            // frame. Anything else means the rewrite is not sound here: stop pipelining.
            current.failed = true;
            failures.increment();
            dev.turboism.runtime.log.RuntimeDiagnostics.warn(
                    "bootstrap",
                    "TURBOISM_ASYNC_READBACK self-check failed: mismatchedInts=" + differences
                            + "; falling back to synchronous readback");
        }
    }

    private static State reset(final boolean sameContext) {
        final State previous = state;
        final WeakReference<Object> reference = boundGl;
        if (sameContext && reference != null && reference.get() != null && previous.names[0] != 0) {
            try {
                GlCalls.deleteBuffers(reference.get(), previous.names);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                // A context change may already have dropped the names; they are recreated below.
            }
        }
        final State fresh = new State();
        state = fresh;
        return fresh;
    }

    private static void allocate(final Object gl, final State target, final int expected) {
        if (target.names[0] != 0) {
            GlCalls.deleteBuffers(gl, target.names);
            target.names[0] = 0;
            target.names[1] = 0;
        }
        GlCalls.genBuffers(gl, target.names);
        for (final int name : target.names) {
            GlCalls.bindBuffer(gl, GL_PIXEL_PACK_BUFFER, name);
            GlCalls.bufferData(gl, GL_PIXEL_PACK_BUFFER, expected, GL_STREAM_READ);
        }
        GlCalls.bindBuffer(gl, GL_PIXEL_PACK_BUFFER, 0);
        target.bytes = expected;
        target.cur = 0;
        target.primed = false;
    }

    private static void copy(final ByteBuffer source, final Buffer destination, final int expected) {
        final ByteBuffer pixels = source.duplicate();
        pixels.clear();
        pixels.limit(expected);
        pixels.order(ByteOrder.nativeOrder());
        if (destination instanceof ByteBuffer target) {
            target.clear();
            target.limit(Math.min(target.capacity(), expected));
            target.put(pixels);
            target.position(0);
        } else if (destination instanceof IntBuffer target) {
            target.clear();
            final IntBuffer ints = pixels.asIntBuffer();
            ints.limit(Math.min(ints.capacity(), target.remaining()));
            target.put(ints);
            target.position(0);
        } else if (destination instanceof ShortBuffer target) {
            target.clear();
            final ShortBuffer shorts = pixels.asShortBuffer();
            shorts.limit(Math.min(shorts.capacity(), target.remaining()));
            target.put(shorts);
            target.position(0);
        } else if (destination instanceof FloatBuffer target) {
            target.clear();
            final FloatBuffer floats = pixels.asFloatBuffer();
            floats.limit(Math.min(floats.capacity(), target.remaining()));
            target.put(floats);
            target.position(0);
        } else {
            throw new IllegalStateException("unsupported readback destination: " + destination.getClass());
        }
    }

    /** Arms the pipelined path; the installer calls this only after the rewrite was admitted. */
    public static void install() {
        resetCounters();
        active.set(true);
        System.getProperties()
                .put(STATS_PROPERTY, (java.util.function.Supplier<Map<String, Long>>) AsyncReadbackBridge::snapshot);
    }

    /** Disarms the pipelined path and forgets the GL buffer names. */
    public static void uninstall() {
        active.set(false);
        resetCounters();
        System.getProperties().remove(STATS_PROPERTY);
        state = new State();
        boundGl = null;
    }

    private static void resetCounters() {
        calls.reset();
        asyncReads.reset();
        syncReads.reset();
        mapped.reset();
        fallbacks.reset();
        bytes.reset();
        failures.reset();
        verified.reset();
        mismatched.reset();
    }

    /** Work counters for diagnostics; no host object is retained. */
    public static Map<String, Long> snapshot() {
        final Map<String, Long> values = new LinkedHashMap<>();
        values.put("active", active.get() ? 1L : 0L);
        values.put("calls", calls.sum());
        values.put("asyncReads", asyncReads.sum());
        values.put("syncReads", syncReads.sum());
        values.put("maps", mapped.sum());
        values.put("fallbacks", fallbacks.sum());
        values.put("bytes", bytes.sum());
        values.put("failures", failures.sum());
        values.put("verified", verified.sum());
        values.put("mismatched", mismatched.sum());
        return values;
    }

    private static final class State {
        final int[] names = new int[2];
        int cur;
        int bytes;
        boolean primed;
        boolean failed;
        int[] verify;
        boolean verifyPending;
    }

    /** Reflective GL entry points, resolved once per GL class and cached by class identity. */
    private static final class GlCalls {
        private static volatile Class<?> cachedClass;
        private static volatile Method m_bindBuffer;
        private static volatile Method m_readPixelsIntoClient;
        private static volatile Method m_readPixelsIntoBuffer;
        private static volatile Method m_mapBuffer;
        private static volatile Method m_unmapBuffer;
        private static volatile Method m_genBuffers;
        private static volatile Method m_bufferData;
        private static volatile Method m_deleteBuffers;

        private GlCalls() {}

        private static void resolve(final Object gl) {
            final Class<?> type = gl.getClass();
            if (type == cachedClass) {
                return;
            }
            synchronized (GlCalls.class) {
                if (type == cachedClass) {
                    return;
                }
                try {
                    m_bindBuffer = type.getMethod("glBindBuffer", int.class, int.class);
                    m_readPixelsIntoClient = type.getMethod(
                            "glReadPixels",
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            Buffer.class);
                    m_readPixelsIntoBuffer = type.getMethod(
                            "glReadPixels",
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            int.class,
                            long.class);
                    m_mapBuffer = type.getMethod("glMapBuffer", int.class, int.class);
                    m_unmapBuffer = type.getMethod("glUnmapBuffer", int.class);
                    m_genBuffers = type.getMethod("glGenBuffers", int.class, int[].class, int.class);
                    m_bufferData = type.getMethod("glBufferData", int.class, long.class, Buffer.class, int.class);
                    m_deleteBuffers = type.getMethod("glDeleteBuffers", int.class, int[].class, int.class);
                    cachedClass = type;
                } catch (final ReflectiveOperationException unavailable) {
                    throw new IllegalStateException("readback GL entry points unavailable", unavailable);
                }
            }
        }

        private static void call(final Method method, final Object receiver, final Object... arguments) {
            try {
                method.invoke(receiver, arguments);
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("readback GL call failed", failure);
            }
        }

        static void bindBuffer(final Object gl, final int target, final int name) {
            resolve(gl);
            call(m_bindBuffer, gl, target, name);
        }

        static void readPixelsIntoClient(
                final Object gl,
                final int x,
                final int y,
                final int width,
                final int height,
                final int format,
                final int type,
                final Buffer destination) {
            resolve(gl);
            call(m_readPixelsIntoClient, gl, x, y, width, height, format, type, destination);
        }

        static void readPixelsIntoBuffer(
                final Object gl,
                final int x,
                final int y,
                final int width,
                final int height,
                final int format,
                final int type) {
            resolve(gl);
            call(m_readPixelsIntoBuffer, gl, x, y, width, height, format, type, 0L);
        }

        static ByteBuffer mapBuffer(final Object gl, final int target) {
            resolve(gl);
            try {
                return (ByteBuffer) m_mapBuffer.invoke(gl, target, GL_READ_ONLY);
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("readback map failed", failure);
            }
        }

        static void unmapBuffer(final Object gl, final int target) {
            resolve(gl);
            call(m_unmapBuffer, gl, target);
        }

        static void genBuffers(final Object gl, final int[] names) {
            resolve(gl);
            call(m_genBuffers, gl, 2, names, 0);
        }

        static void bufferData(final Object gl, final int target, final long size, final int usage) {
            resolve(gl);
            call(m_bufferData, gl, target, size, null, usage);
        }

        static void deleteBuffers(final Object gl, final int[] names) {
            resolve(gl);
            call(m_deleteBuffers, gl, 2, names, 0);
        }
    }
}
