package dev.turboism.adapter.cubism.optimization.lineupload;

import dev.turboism.adapter.cubism.optimization.lineupload.LineRendererUploadSplitTarget.Dep;
import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Loader-neutral bridge for the line-renderer dirty-buffer split.
 *
 * <p>The woven {@code updateMeshData()} entry consults the JDK
 * {@link Predicate} stored at {@link #PREDICATE_PROPERTY}. The predicate marks the
 * positions buffer unconditionally and marks the colors/indices buffers only when
 * their current arrays differ from the last marked arrays. A content comparison is
 * required because the host may mutate a previously assigned array in place.</p>
 *
 * <p>Snapshots are weak-keyed and bounded; an extraction failure, a thread change or
 * an over-budget array falls back to the native three-buffer mark. No host class is
 * referenced by the woven bytecode.</p>
 */
public final class LineRendererUploadSplitBridge implements AutoCloseable {

    /** Slot for the {@code Predicate<Object>} update consult. */
    public static final String PREDICATE_PROPERTY = "turboism.line-renderer-upload-split.predicate";
    /** Payload-free statistics slot. */
    public static final String STATS_PROPERTY = "turboism.line-renderer-upload-split.stats";

    private static final int MAX_ENTRIES = 16_384;
    private static final int MAX_COMPARE_ELEMENTS = 65_536;
    private static final float[] EMPTY_FLOATS = new float[0];
    private static final int[] EMPTY_INTS = new int[0];

    private final MethodHandle positionsBuffer, colorsBuffer, indicesBuffer, colors, indices, dirty;
    private final WeakHashMap<Object, float[]> colorSnapshots = new WeakHashMap<>();
    private final WeakHashMap<Object, int[]> indexSnapshots = new WeakHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean();
    private final Predicate<Object> predicate = this::test;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private Thread observedThread;
    private Properties installedProperties;

    private long calls, colorMarks, indexMarks, colorSkips, indexSkips, oversize, failures, clears;
    private long colorElements, indexElements;

    /**
     * Resolves the reviewed renderer and buffer members on the admitted loader.
     *
     * @throws ReflectiveOperationException when a reviewed member is absent
     */
    public LineRendererUploadSplitBridge(final ClassLoader loader) throws ReflectiveOperationException {
        final Class<?> renderer = HostHandles.load(loader, LineRendererUploadSplitTarget.OWNER);
        final Class<?> base = HostHandles.load(loader, LineRendererUploadSplitTarget.BUFFER_BASE_OWNER);
        positionsBuffer = reviewed(renderer, "getPositionsBuffer", MethodType.methodType(Object.class, Object.class));
        colorsBuffer = reviewed(renderer, "getColorsBuffer", MethodType.methodType(Object.class, Object.class));
        indicesBuffer = reviewed(renderer, "getIndicesBuffer", MethodType.methodType(Object.class, Object.class));
        colors = reviewed(renderer, "getColors", MethodType.methodType(float[].class, Object.class));
        indices = reviewed(renderer, "getIndices", MethodType.methodType(int[].class, Object.class));
        dirty = reviewed(
                base, LineRendererUploadSplitTarget.DIRTY_METHOD, MethodType.methodType(void.class, Object.class));
    }

    /**
     * Resolves one reviewed dependency by the exact descriptor its target table declares.
     *
     * <p>The table is the same one the verified installer attests against the official artifact, so the
     * bridge, the admission gate and the body check cannot disagree about which member is bound.</p>
     *
     * @throws NoSuchMethodException when the dependency is not in the reviewed table
     */
    private static MethodHandle reviewed(final Class<?> owner, final String name, final MethodType shape)
            throws ReflectiveOperationException {
        for (final Dep dep : LineRendererUploadSplitTarget.DEPENDENCIES) {
            if (dep.name().equals(name) && dep.owner().equals(owner.getName().replace('.', '/'))) {
                return HostHandles.virtual(owner, dep.name(), dep.descriptor(), shape);
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + " is not a reviewed dependency");
    }

    /** Occupies the predicate/statistics slots; refuses another installation. */
    public synchronized void install() {
        if (active.get()) throw new IllegalStateException("line-renderer upload split already installed");
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(PREDICATE_PROPERTY) || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("line-renderer upload split slots occupied");
            }
            try {
                properties.put(PREDICATE_PROPERTY, predicate);
                properties.put(STATS_PROPERTY, statistics);
                installedProperties = properties;
                active.set(true);
            } catch (RuntimeException | Error failure) {
                properties.remove(PREDICATE_PROPERTY, predicate);
                properties.remove(STATS_PROPERTY, statistics);
                throw failure;
            }
        }
    }

    private synchronized boolean test(final Object renderer) {
        if (!active.get()) return false;
        calls++;
        try {
            if (renderer == null) {
                failures++;
                return false;
            }
            final Thread current = Thread.currentThread();
            if (observedThread == null) {
                observedThread = current;
            } else if (observedThread != current) {
                observedThread = current;
                clears++;
                clearSnapshots();
                return false;
            }
            final Object positions = (Object) positionsBuffer.invokeExact(renderer);
            if (positions == null) {
                failures++;
                return false;
            }
            dirty.invokeExact(positions);

            float[] currentColors = (float[]) colors.invokeExact(renderer);
            currentColors = currentColors == null ? EMPTY_FLOATS : currentColors;
            final float[] lastColors = colorSnapshots.get(renderer);
            if (currentColors.length > MAX_COMPARE_ELEMENTS) {
                oversize++;
                colorSnapshots.remove(renderer);
                markColors(renderer);
            } else if (currentColors.length == 0 && lastColors == null) {
                // Empty attribute data is never uploaded by the host renderer; record the
                // empty baseline but do not dirty a buffer that cannot be consumed.
                colorSnapshots.put(renderer, EMPTY_FLOATS);
                colorSkips++;
            } else if (lastColors == null || !Arrays.equals(lastColors, currentColors)) {
                colorSnapshots.put(renderer, currentColors.clone());
                markColors(renderer);
                colorMarks++;
                colorElements += currentColors.length;
            } else {
                colorSkips++;
            }

            int[] currentIndices = (int[]) indices.invokeExact(renderer);
            currentIndices = currentIndices == null ? EMPTY_INTS : currentIndices;
            final int[] lastIndices = indexSnapshots.get(renderer);
            if (currentIndices.length > MAX_COMPARE_ELEMENTS) {
                oversize++;
                indexSnapshots.remove(renderer);
                markIndices(renderer);
            } else if (currentIndices.length == 0 && lastIndices == null) {
                indexSnapshots.put(renderer, EMPTY_INTS);
                indexSkips++;
            } else if (lastIndices == null || !Arrays.equals(lastIndices, currentIndices)) {
                indexSnapshots.put(renderer, currentIndices.clone());
                markIndices(renderer);
                indexMarks++;
                indexElements += currentIndices.length;
            } else {
                indexSkips++;
            }

            if (colorSnapshots.size() > MAX_ENTRIES || indexSnapshots.size() > MAX_ENTRIES) {
                clears++;
                clearSnapshots();
            }
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures++;
            colorSnapshots.remove(renderer);
            indexSnapshots.remove(renderer);
            return false;
        }
    }

    private void markColors(final Object renderer) throws Throwable {
        final Object buffer = (Object) colorsBuffer.invokeExact(renderer);
        if (buffer == null) throw new IllegalStateException("colors buffer absent");
        dirty.invokeExact(buffer);
    }

    private void markIndices(final Object renderer) throws Throwable {
        final Object buffer = (Object) indicesBuffer.invokeExact(renderer);
        if (buffer == null) throw new IllegalStateException("indices buffer absent");
        dirty.invokeExact(buffer);
    }

    private void clearSnapshots() {
        colorSnapshots.clear();
        indexSnapshots.clear();
    }

    /** Work counters for diagnostics; no host object is retained. */
    public synchronized Map<String, Long> snapshot() {
        final Map<String, Long> map = new LinkedHashMap<>();
        map.put("active", active.get() ? 1L : 0L);
        map.put("calls", calls);
        map.put("colorMarks", colorMarks);
        map.put("indexMarks", indexMarks);
        map.put("colorSkips", colorSkips);
        map.put("colorElements", colorElements);
        map.put("indexSkips", indexSkips);
        map.put("indexElements", indexElements);
        map.put("oversize", oversize);
        map.put("failures", failures);
        map.put("clears", clears);
        map.put("colorEntries", (long) colorSnapshots.size());
        map.put("indexEntries", (long) indexSnapshots.size());
        return map;
    }

    /** Removes owned slots and drops every snapshot. */
    @Override
    public synchronized void close() {
        active.set(false);
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                properties.remove(PREDICATE_PROPERTY, predicate);
                properties.remove(STATS_PROPERTY, statistics);
            }
        }
        clearSnapshots();
        observedThread = null;
    }
}
