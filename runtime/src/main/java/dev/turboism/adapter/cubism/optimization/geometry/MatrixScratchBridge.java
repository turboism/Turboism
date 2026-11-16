package dev.turboism.adapter.cubism.optimization.geometry;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Caches per-transform local-to-parent matrices keyed by exact raw float bits and resolves world
 * matrices through pre-resolved host method handles without intermediate allocations.
 */
public final class MatrixScratchBridge implements AutoCloseable {

    /** Property key publishing the {@code Function<Object, Object>} world-matrix resolver. */
    public static final String CALLBACK_PROPERTY = "turboism.matrix-scratch.callback";

    /** Property key exposing cache telemetry counters. */
    public static final String STATS_PROPERTY = "turboism.matrix-scratch.stats";

    private static final String STAT_ACTIVE = "active";
    private static final String STAT_CALLS = "calls";
    private static final String STAT_CACHE_HITS = "cacheHits";
    private static final String STAT_CACHE_MISSES = "cacheMisses";
    private static final String STAT_STATIC_HITS = "staticHits";
    private static final String STAT_FAILURES = "failures";

    private static final String ENTITY_OWNER = "com/live2d/graphics3d/entity/GEntity";
    private static final String VECTOR3_OWNER = "com/live2d/graphics3d/type/GVector3";
    private static final String GET_ENTITY_METHOD = "getEntity";
    private static final String GET_ENTITY_DESCRIPTOR = "()L" + ENTITY_OWNER + ";";
    private static final String GET_PARENT_METHOD = "getParentEntity";
    private static final String GET_PARENT_DESCRIPTOR = "()L" + ENTITY_OWNER + ";";
    private static final String GET_TRANSFORM_METHOD = "getTransform";
    private static final String GET_TRANSFORM_DESCRIPTOR = "()L" + MatrixScratchTransformer.OWNER + ";";
    private static final String GET_POSITION_METHOD = "getPosition";
    private static final String GET_EULER_METHOD = "getEulerAngles";
    private static final String GET_SCALE_METHOD = "getScale";
    private static final String VECTOR3_DESCRIPTOR = "()L" + VECTOR3_OWNER + ";";
    private static final String STATIC_MATRIX_FIELD = "internalStaticMatrixLocalToParent";
    private static final String STATIC_MATRIX_DESCRIPTOR = "L" + MatrixScratchTransformer.MATRIX + ";";
    private static final String MATRIX_CONSTRUCTOR_DESCRIPTOR = "()V";
    private static final String MATRIX_DATA_METHOD = "a";
    private static final String MATRIX_DATA_DESCRIPTOR = "()[F";
    private static final String VECTOR_X_METHOD = "getX";
    private static final String VECTOR_Y_METHOD = "getY";
    private static final String VECTOR_Z_METHOD = "getZ";
    private static final String FLOAT_GETTER_DESCRIPTOR = "()F";

    private static final float DEGREES_TO_RADIANS = (float) (Math.PI / 180.0);
    private static final float ZERO_F = 0.0f;
    private static final float ONE_F = 1.0f;
    private static final int MATRIX_DIM = 4;
    private static final int MATRIX_LENGTH = 16;
    private static final int COL_0_OFFSET = 0;
    private static final int COL_1_OFFSET = 4;
    private static final int COL_2_OFFSET = 8;
    private static final int COL_3_OFFSET = 12;
    private static final int ROW_0 = 0;
    private static final int ROW_1 = 1;
    private static final int ROW_2 = 2;
    private static final int ROW_3 = 3;

    private static final int PACKET_BITS_INDEX = 0;
    private static final int PACKET_STATIC_DATA_INDEX = 1;
    private static final int PACKET_PARENT_INDEX = 2;
    private static final int PACKET_STATIC_MATRIX_INDEX = 3;
    private static final int PACKET_SIZE = 4;
    private static final int HOLDER_ARRAY_INDEX = 0;
    private static final int HOLDER_SIZE = 1;

    private static final int PX_BITS = 0;
    private static final int PY_BITS = 1;
    private static final int PZ_BITS = 2;
    private static final int EX_BITS = 3;
    private static final int EY_BITS = 4;
    private static final int EZ_BITS = 5;
    private static final int SX_BITS = 6;
    private static final int SY_BITS = 7;
    private static final int SZ_BITS = 8;
    private static final int VECTOR_BITS_COUNT = 9;

    private static final int CACHE_SET_COUNT = 8192;
    private static final int CACHE_WAY_SHIFT = 1;
    private static final int CACHE_WAY_COUNT = 2;
    private static final int CACHE_TOTAL_SLOTS = CACHE_SET_COUNT * CACHE_WAY_COUNT;
    private static final int CACHE_SET_MASK = CACHE_SET_COUNT - 1;
    private static final byte WAY_0 = 0;
    private static final byte WAY_1 = 1;
    private static final int HASH_SHIFT = 16;
    private static final int MAX_CHAIN_DEPTH = 4096;

    private final Class<?> transformType;
    private final AtomicBoolean admission;
    private final MethodHandle getEntityHandle;
    private final MethodHandle getParentEntityHandle;
    private final MethodHandle getTransformHandle;
    private final MethodHandle getPositionHandle;
    private final MethodHandle getEulerAnglesHandle;
    private final MethodHandle getScaleHandle;
    private final MethodHandle vectorXHandle;
    private final MethodHandle vectorYHandle;
    private final MethodHandle vectorZHandle;
    private final MethodHandle matrixConstructorHandle;
    private final MethodHandle matrixDataHandle;
    private final MethodHandle staticMatrixHandle;

    private final AtomicBoolean active = new AtomicBoolean();
    private final Set<ThreadState> threadStates = ConcurrentHashMap.newKeySet();
    private final AtomicLong retiredCalls = new AtomicLong();
    private final AtomicLong retiredCacheHits = new AtomicLong();
    private final AtomicLong retiredCacheMisses = new AtomicLong();
    private final AtomicLong retiredStaticHits = new AtomicLong();
    private final AtomicLong retiredFailures = new AtomicLong();
    private final ThreadLocal<ThreadState> threadState = ThreadLocal.withInitial(this::registerThreadState);

    private final Function<Object, Object> callback = this::resolveWorldMatrix;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    /**
     * Resolves every reviewed host member into an exact-typed handle on the admitted host loader.
     *
     * @param loader admitted host class loader
     * @param admission installer-owned gate controlling live execution
     * @throws ReflectiveOperationException when any required host member is absent or closed
     */
    public MatrixScratchBridge(final ClassLoader loader, final AtomicBoolean admission)
            throws ReflectiveOperationException {
        this.admission = Objects.requireNonNull(admission, "admission");
        transformType = HostHandles.load(loader, MatrixScratchTransformer.OWNER);
        final Class<?> entityType = HostHandles.load(loader, ENTITY_OWNER);
        final Class<?> vectorType = HostHandles.load(loader, VECTOR3_OWNER);
        final Class<?> matrixType = HostHandles.load(loader, MatrixScratchTransformer.MATRIX);

        final MethodType objectUnary = MethodType.methodType(Object.class, Object.class);
        final MethodType floatUnary = MethodType.methodType(float.class, Object.class);

        getEntityHandle = HostHandles.virtual(transformType, GET_ENTITY_METHOD, GET_ENTITY_DESCRIPTOR, objectUnary);
        getParentEntityHandle = HostHandles.virtual(entityType, GET_PARENT_METHOD, GET_PARENT_DESCRIPTOR, objectUnary);
        getTransformHandle =
                HostHandles.virtual(entityType, GET_TRANSFORM_METHOD, GET_TRANSFORM_DESCRIPTOR, objectUnary);
        getPositionHandle = HostHandles.virtual(transformType, GET_POSITION_METHOD, VECTOR3_DESCRIPTOR, objectUnary);
        getEulerAnglesHandle = HostHandles.virtual(transformType, GET_EULER_METHOD, VECTOR3_DESCRIPTOR, objectUnary);
        getScaleHandle = HostHandles.virtual(transformType, GET_SCALE_METHOD, VECTOR3_DESCRIPTOR, objectUnary);
        vectorXHandle = HostHandles.virtual(vectorType, VECTOR_X_METHOD, FLOAT_GETTER_DESCRIPTOR, floatUnary);
        vectorYHandle = HostHandles.virtual(vectorType, VECTOR_Y_METHOD, FLOAT_GETTER_DESCRIPTOR, floatUnary);
        vectorZHandle = HostHandles.virtual(vectorType, VECTOR_Z_METHOD, FLOAT_GETTER_DESCRIPTOR, floatUnary);
        matrixConstructorHandle =
                HostHandles.construct(matrixType, MATRIX_CONSTRUCTOR_DESCRIPTOR, MethodType.methodType(Object.class));
        matrixDataHandle = HostHandles.virtual(
                matrixType,
                MATRIX_DATA_METHOD,
                MATRIX_DATA_DESCRIPTOR,
                MethodType.methodType(float[].class, Object.class));
        staticMatrixHandle =
                HostHandles.getter(transformType, STATIC_MATRIX_FIELD, STATIC_MATRIX_DESCRIPTOR, objectUnary);
    }

    /** Activates bridge resolution once bytecode verification completes. */
    public void activate() {
        active.set(true);
    }

    /** Disarms bridge resolution immediately before restoration or teardown. */
    public void deactivate() {
        active.set(false);
    }

    /** Returns the callback registered under {@link #CALLBACK_PROPERTY}. */
    public Function<Object, Object> callback() {
        return callback;
    }

    /** Returns the counter snapshot supplier registered under {@link #STATS_PROPERTY}. */
    public Supplier<Map<String, Long>> statistics() {
        return statistics;
    }

    /** Returns an immutable snapshot of bridge telemetry counters across all threads. */
    public Map<String, Long> snapshot() {
        long totalCalls = retiredCalls.get();
        long totalHits = retiredCacheHits.get();
        long totalMisses = retiredCacheMisses.get();
        long totalStatic = retiredStaticHits.get();
        long totalFailures = retiredFailures.get();
        final Iterator<ThreadState> iterator = threadStates.iterator();
        while (iterator.hasNext()) {
            final ThreadState state = iterator.next();
            totalCalls += state.calls;
            totalHits += state.cacheHits;
            totalMisses += state.cacheMisses;
            totalStatic += state.staticHits;
            totalFailures += state.failures;
            if (!state.owner.isAlive()) {
                retiredCalls.addAndGet(state.calls);
                retiredCacheHits.addAndGet(state.cacheHits);
                retiredCacheMisses.addAndGet(state.cacheMisses);
                retiredStaticHits.addAndGet(state.staticHits);
                retiredFailures.addAndGet(state.failures);
                iterator.remove();
            }
        }
        return Map.of(
                STAT_ACTIVE, active.get() ? 1L : 0L,
                STAT_CALLS, totalCalls,
                STAT_CACHE_HITS, totalHits,
                STAT_CACHE_MISSES, totalMisses,
                STAT_STATIC_HITS, totalStatic,
                STAT_FAILURES, totalFailures);
    }

    @Override
    public void close() {
        active.set(false);
        threadState.remove();
    }

    private ThreadState registerThreadState() {
        final ThreadState state = new ThreadState();
        threadStates.add(state);
        return state;
    }

    private Object resolveWorldMatrix(final Object transform) {
        if (!active.get() || !admission.get() || transform == null || transform.getClass() != transformType) {
            return null;
        }
        final ThreadState state = threadState.get();
        state.calls++;
        try {
            final Object[] packet = state.packet;
            if (!readSelfState(transform, packet)) {
                return null;
            }
            Object parentEntity = packet[PACKET_PARENT_INDEX];
            if (parentEntity == null) {
                // Return the static matrix instance directly to match root ownership in host code.
                final Object staticMatrix = packet[PACKET_STATIC_MATRIX_INDEX];
                if (staticMatrix != null) {
                    state.staticHits++;
                    return staticMatrix;
                }
                final float[] selfData = resolveCachedLocalToParent(transform, state);
                final Object result = allocateMatrix(state.matrixOutHolder);
                final float[] out = (float[]) state.matrixOutHolder[HOLDER_ARRAY_INDEX];
                if (out == null || out.length < MATRIX_LENGTH) {
                    return null;
                }
                System.arraycopy(selfData, 0, out, 0, MATRIX_LENGTH);
                return result;
            }

            final float[] current = resolveNodeData(transform, packet, state);
            if (current == null) {
                return null;
            }
            Object parentTransform = readParentState(parentEntity, packet);
            if (parentTransform == null) {
                return null;
            }
            float[] left = resolveNodeData(parentTransform, packet, state);
            if (left == null) {
                return null;
            }
            Object nextParent = packet[PACKET_PARENT_INDEX];
            if (nextParent == null) {
                return allocateProduct(left, current, state);
            }

            float[] acc = state.scratchA;
            multiplyGeneral(left, current, acc);
            parentEntity = nextParent;
            for (int depth = 1; depth < MAX_CHAIN_DEPTH; depth++) {
                parentTransform = readParentState(parentEntity, packet);
                if (parentTransform == null) {
                    return null;
                }
                left = resolveNodeData(parentTransform, packet, state);
                if (left == null) {
                    return null;
                }
                nextParent = packet[PACKET_PARENT_INDEX];
                if (nextParent == null) {
                    return allocateProduct(left, acc, state);
                }
                final float[] dest = acc == state.scratchA ? state.scratchB : state.scratchA;
                multiplyGeneral(left, acc, dest);
                acc = dest;
                parentEntity = nextParent;
            }
            return null;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            state.failures++;
            return null;
        }
    }

    /**
     * Reads the transform's own entity, its parent entity, and the local-to-parent inputs.
     *
     * @return {@code false} when the host reports no entity or no vector triple
     */
    private boolean readSelfState(final Object transform, final Object[] packet) throws Throwable {
        final Object entity = (Object) getEntityHandle.invokeExact(transform);
        if (entity == null) {
            return false;
        }
        packet[PACKET_PARENT_INDEX] = (Object) getParentEntityHandle.invokeExact(entity);
        return readNodeState(transform, packet);
    }

    /**
     * Reads one ancestor entity's transform and its parent link.
     *
     * @return the ancestor transform, or {@code null} when the entity carries none
     */
    private Object readParentState(final Object entity, final Object[] packet) throws Throwable {
        final Object transform = (Object) getTransformHandle.invokeExact(entity);
        if (transform == null) {
            return null;
        }
        packet[PACKET_PARENT_INDEX] = (Object) getParentEntityHandle.invokeExact(entity);
        return readNodeState(transform, packet) ? transform : null;
    }

    /**
     * Fills the packet with the static-matrix fast path or the raw position, Euler, and scale bits.
     *
     * @return {@code false} when a reviewed vector member is absent
     */
    private boolean readNodeState(final Object transform, final Object[] packet) throws Throwable {
        final Object staticMatrix = (Object) staticMatrixHandle.invokeExact(transform);
        if (staticMatrix != null) {
            packet[PACKET_STATIC_MATRIX_INDEX] = staticMatrix;
            packet[PACKET_STATIC_DATA_INDEX] = (float[]) matrixDataHandle.invokeExact(staticMatrix);
            return true;
        }
        packet[PACKET_STATIC_MATRIX_INDEX] = null;
        final Object position = (Object) getPositionHandle.invokeExact(transform);
        final Object euler = (Object) getEulerAnglesHandle.invokeExact(transform);
        final Object scale = (Object) getScaleHandle.invokeExact(transform);
        if (position == null || euler == null || scale == null) {
            return false;
        }
        final int[] bits = (int[]) packet[PACKET_BITS_INDEX];
        writeVectorBits(bits, position, PX_BITS);
        writeVectorBits(bits, euler, EX_BITS);
        writeVectorBits(bits, scale, SX_BITS);
        return true;
    }

    private void writeVectorBits(final int[] bits, final Object vector, final int base) throws Throwable {
        bits[base] = Float.floatToRawIntBits((float) vectorXHandle.invokeExact(vector));
        bits[base + 1] = Float.floatToRawIntBits((float) vectorYHandle.invokeExact(vector));
        bits[base + 2] = Float.floatToRawIntBits((float) vectorZHandle.invokeExact(vector));
    }

    /**
     * Allocates one host matrix and publishes its backing array into the thread-local holder.
     *
     * @return the allocated matrix instance
     */
    private Object allocateMatrix(final Object[] holder) throws Throwable {
        final Object matrix = (Object) matrixConstructorHandle.invokeExact();
        holder[HOLDER_ARRAY_INDEX] = (float[]) matrixDataHandle.invokeExact(matrix);
        return matrix;
    }

    private Object allocateProduct(final float[] left, final float[] right, final ThreadState state) throws Throwable {
        final Object result = allocateMatrix(state.matrixOutHolder);
        final float[] out = (float[]) state.matrixOutHolder[HOLDER_ARRAY_INDEX];
        if (out == null || out.length < MATRIX_LENGTH) {
            return null;
        }
        multiplyGeneral(left, right, out);
        return result;
    }

    private static float[] resolveNodeData(final Object transform, final Object[] packet, final ThreadState state) {
        if (packet[PACKET_STATIC_MATRIX_INDEX] != null) {
            state.staticHits++;
            final float[] data = (float[]) packet[PACKET_STATIC_DATA_INDEX];
            return data != null && data.length >= MATRIX_LENGTH ? data : null;
        }
        return resolveCachedLocalToParent(transform, state);
    }

    private static float[] resolveCachedLocalToParent(final Object transform, final ThreadState state) {
        final int[] b = state.bits;
        final int pxBits = b[PX_BITS];
        final int pyBits = b[PY_BITS];
        final int pzBits = b[PZ_BITS];
        final int exBits = b[EX_BITS];
        final int eyBits = b[EY_BITS];
        final int ezBits = b[EZ_BITS];
        final int sxBits = b[SX_BITS];
        final int syBits = b[SY_BITS];
        final int szBits = b[SZ_BITS];

        final TransformSlot slot = state.cache.findOrBind(transform);
        if (slot.valid && slot.matches(pxBits, pyBits, pzBits, exBits, eyBits, ezBits, sxBits, syBits, szBits)) {
            state.cacheHits++;
            return slot.matrix;
        }
        state.cacheMisses++;
        computeLocalToParentMatrix(
                Float.intBitsToFloat(pxBits),
                Float.intBitsToFloat(pyBits),
                Float.intBitsToFloat(pzBits),
                Float.intBitsToFloat(exBits),
                Float.intBitsToFloat(eyBits),
                Float.intBitsToFloat(ezBits),
                Float.intBitsToFloat(sxBits),
                Float.intBitsToFloat(syBits),
                Float.intBitsToFloat(szBits),
                slot.matrix);
        slot.recordState(pxBits, pyBits, pzBits, exBits, eyBits, ezBits, sxBits, syBits, szBits);
        return slot.matrix;
    }

    private static void computeLocalToParentMatrix(
            final float px,
            final float py,
            final float pz,
            final float ex,
            final float ey,
            final float ez,
            final float sx,
            final float sy,
            final float sz,
            final float[] m) {
        m[COL_0_OFFSET + ROW_0] = ONE_F;
        m[COL_0_OFFSET + ROW_1] = ZERO_F;
        m[COL_0_OFFSET + ROW_2] = ZERO_F;
        m[COL_0_OFFSET + ROW_3] = ZERO_F;
        m[COL_1_OFFSET + ROW_0] = ZERO_F;
        m[COL_1_OFFSET + ROW_1] = ONE_F;
        m[COL_1_OFFSET + ROW_2] = ZERO_F;
        m[COL_1_OFFSET + ROW_3] = ZERO_F;
        m[COL_2_OFFSET + ROW_0] = ZERO_F;
        m[COL_2_OFFSET + ROW_1] = ZERO_F;
        m[COL_2_OFFSET + ROW_2] = ONE_F;
        m[COL_2_OFFSET + ROW_3] = ZERO_F;
        // Preserve exact IEEE-754 signed-zero and NaN semantics of GMatrix44.a(px, py, pz).
        m[COL_3_OFFSET + ROW_0] = ((ONE_F * px + ZERO_F * py) + ZERO_F * pz) + ZERO_F;
        m[COL_3_OFFSET + ROW_1] = ((ZERO_F * px + ONE_F * py) + ZERO_F * pz) + ZERO_F;
        m[COL_3_OFFSET + ROW_2] = ((ZERO_F * px + ZERO_F * py) + ONE_F * pz) + ZERO_F;
        m[COL_3_OFFSET + ROW_3] = ((ZERO_F * px + ZERO_F * py) + ZERO_F * pz) + ONE_F;

        rotateY(m, ey * DEGREES_TO_RADIANS);
        rotateX(m, ex * DEGREES_TO_RADIANS);
        rotateZ(m, ez * DEGREES_TO_RADIANS);
        scale(m, sx, sy, sz);
    }

    private static void rotateY(final float[] m, final float radians) {
        final float cos = (float) Math.cos(radians);
        final float sin = (float) Math.sin(radians);
        for (int row = 0; row < MATRIX_DIM; row++) {
            final float left = m[COL_0_OFFSET + row];
            final float right = m[COL_2_OFFSET + row];
            m[COL_0_OFFSET + row] = left * cos + right * (-sin);
            m[COL_2_OFFSET + row] = left * sin + right * cos;
        }
    }

    private static void rotateX(final float[] m, final float radians) {
        final float cos = (float) Math.cos(radians);
        final float sin = (float) Math.sin(radians);
        for (int row = 0; row < MATRIX_DIM; row++) {
            final float left = m[COL_1_OFFSET + row];
            final float right = m[COL_2_OFFSET + row];
            m[COL_1_OFFSET + row] = left * cos + right * sin;
            m[COL_2_OFFSET + row] = left * (-sin) + right * cos;
        }
    }

    private static void rotateZ(final float[] m, final float radians) {
        final float cos = (float) Math.cos(radians);
        final float sin = (float) Math.sin(radians);
        for (int row = 0; row < MATRIX_DIM; row++) {
            final float left = m[COL_0_OFFSET + row];
            final float right = m[COL_1_OFFSET + row];
            m[COL_0_OFFSET + row] = left * cos + right * sin;
            m[COL_1_OFFSET + row] = left * (-sin) + right * cos;
        }
    }

    private static void scale(final float[] m, final float sx, final float sy, final float sz) {
        for (int row = 0; row < MATRIX_DIM; row++) {
            m[COL_0_OFFSET + row] = m[COL_0_OFFSET + row] * sx;
            m[COL_1_OFFSET + row] = m[COL_1_OFFSET + row] * sy;
            m[COL_2_OFFSET + row] = m[COL_2_OFFSET + row] * sz;
        }
    }

    private static void multiplyGeneral(final float[] l, final float[] r, final float[] out) {
        // Keep exact row-major four-term summation order matching GMatrix44.b(..., false).
        for (int row = 0; row < MATRIX_DIM; row++) {
            final float l0 = l[COL_0_OFFSET + row];
            final float l1 = l[COL_1_OFFSET + row];
            final float l2 = l[COL_2_OFFSET + row];
            final float l3 = l[COL_3_OFFSET + row];
            out[COL_0_OFFSET + row] =
                    ((l0 * r[COL_0_OFFSET + ROW_0] + l1 * r[COL_0_OFFSET + ROW_1]) + l2 * r[COL_0_OFFSET + ROW_2])
                            + l3 * r[COL_0_OFFSET + ROW_3];
            out[COL_1_OFFSET + row] =
                    ((l0 * r[COL_1_OFFSET + ROW_0] + l1 * r[COL_1_OFFSET + ROW_1]) + l2 * r[COL_1_OFFSET + ROW_2])
                            + l3 * r[COL_1_OFFSET + ROW_3];
            out[COL_2_OFFSET + row] =
                    ((l0 * r[COL_2_OFFSET + ROW_0] + l1 * r[COL_2_OFFSET + ROW_1]) + l2 * r[COL_2_OFFSET + ROW_2])
                            + l3 * r[COL_2_OFFSET + ROW_3];
            out[COL_3_OFFSET + row] =
                    ((l0 * r[COL_3_OFFSET + ROW_0] + l1 * r[COL_3_OFFSET + ROW_1]) + l2 * r[COL_3_OFFSET + ROW_2])
                            + l3 * r[COL_3_OFFSET + ROW_3];
        }
    }

    private static int mixHash(final int hash) {
        return hash ^ (hash >>> HASH_SHIFT);
    }

    private static final class ThreadState {
        final Thread owner = Thread.currentThread();
        long calls;
        long cacheHits;
        long cacheMisses;
        long staticHits;
        long failures;
        final int[] bits = new int[VECTOR_BITS_COUNT];
        final Object[] packet = new Object[PACKET_SIZE];
        final Object[] matrixOutHolder = new Object[HOLDER_SIZE];
        final float[] scratchA = new float[MATRIX_LENGTH];
        final float[] scratchB = new float[MATRIX_LENGTH];
        final LocalCache cache = new LocalCache();

        ThreadState() {
            packet[PACKET_BITS_INDEX] = bits;
        }
    }

    private static final class TransformSlot {
        WeakReference<Object> ref;
        int identityHash;
        boolean valid;
        int pxBits;
        int pyBits;
        int pzBits;
        int exBits;
        int eyBits;
        int ezBits;
        int sxBits;
        int syBits;
        int szBits;
        final float[] matrix = new float[MATRIX_LENGTH];

        void bind(final Object transform, final int hash) {
            ref = new WeakReference<>(transform);
            identityHash = hash;
            valid = false;
        }

        boolean matches(
                final int px,
                final int py,
                final int pz,
                final int ex,
                final int ey,
                final int ez,
                final int sx,
                final int sy,
                final int sz) {
            return pxBits == px
                    && pyBits == py
                    && pzBits == pz
                    && exBits == ex
                    && eyBits == ey
                    && ezBits == ez
                    && sxBits == sx
                    && syBits == sy
                    && szBits == sz;
        }

        void recordState(
                final int px,
                final int py,
                final int pz,
                final int ex,
                final int ey,
                final int ez,
                final int sx,
                final int sy,
                final int sz) {
            pxBits = px;
            pyBits = py;
            pzBits = pz;
            exBits = ex;
            eyBits = ey;
            ezBits = ez;
            sxBits = sx;
            syBits = sy;
            szBits = sz;
            valid = true;
        }
    }

    /**
     * Thread-confined two-way set-associative identity cache with pre-allocated reusable slots.
     */
    private static final class LocalCache {
        private final TransformSlot[] slots = new TransformSlot[CACHE_TOTAL_SLOTS];
        private final byte[] mruWay = new byte[CACHE_SET_COUNT];

        LocalCache() {
            for (int i = 0; i < CACHE_TOTAL_SLOTS; i++) {
                slots[i] = new TransformSlot();
            }
        }

        TransformSlot findOrBind(final Object transform) {
            final int rawHash = System.identityHashCode(transform);
            final int setIndex = mixHash(rawHash) & CACHE_SET_MASK;
            final int base = setIndex << CACHE_WAY_SHIFT;

            final TransformSlot slot0 = slots[base];
            final WeakReference<Object> ref0 = slot0.ref;
            if (slot0.identityHash == rawHash && ref0 != null && ref0.refersTo(transform)) {
                mruWay[setIndex] = WAY_0;
                return slot0;
            }
            final TransformSlot slot1 = slots[base + WAY_1];
            final WeakReference<Object> ref1 = slot1.ref;
            if (slot1.identityHash == rawHash && ref1 != null && ref1.refersTo(transform)) {
                mruWay[setIndex] = WAY_1;
                return slot1;
            }
            if (ref0 == null || ref0.refersTo(null)) {
                slot0.bind(transform, rawHash);
                mruWay[setIndex] = WAY_0;
                return slot0;
            }
            if (ref1 == null || ref1.refersTo(null)) {
                slot1.bind(transform, rawHash);
                mruWay[setIndex] = WAY_1;
                return slot1;
            }
            final byte victimWay = (byte) (mruWay[setIndex] ^ WAY_1);
            final TransformSlot victim = slots[base + victimWay];
            victim.bind(transform, rawHash);
            mruWay[setIndex] = victimWay;
            return victim;
        }
    }
}
