package dev.turboism.adapter.cubism.optimization.geometry;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Caches per-transform local-to-parent matrices keyed by exact raw float bits and computes
 * world matrices without intermediate {@code GMatrix44} allocations.
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
    private static final int INITIAL_TABLE_CAPACITY = 128;
    private static final int MAX_TABLE_CAPACITY = 8192;
    private static final int MAX_LINEAR_PROBES = 64;
    private static final int LOAD_NUMERATOR = 3;
    private static final int LOAD_DENOMINATOR = 4;
    private static final int HASH_SHIFT = 16;
    private static final int MAX_CHAIN_DEPTH = 4096;
    private static final int CAPACITY_GROWTH_FACTOR = 2;

    private final Class<?> transformType;
    private final AtomicBoolean admission;
    private final MethodHandle transformEntityHandle;
    private final MethodHandle entityParentHandle;
    private final MethodHandle entityTransformHandle;
    private final MethodHandle staticMatrixHandle;
    private final MethodHandle positionHandle;
    private final MethodHandle eulerAnglesHandle;
    private final MethodHandle scaleHandle;
    private final MethodHandle vecXHandle;
    private final MethodHandle vecYHandle;
    private final MethodHandle vecZHandle;
    private final MethodHandle matrixConstructorHandle;
    private final MethodHandle matrixDataHandle;

    private final AtomicBoolean active = new AtomicBoolean();
    private final LongAdder calls = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder staticHits = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final ThreadLocal<LocalCache> localCache = ThreadLocal.withInitial(LocalCache::new);

    private final Function<Object, Object> callback = this::resolveWorldMatrix;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    /**
     * Resolves host transform, entity, vector, and matrix handles on the admitted loader.
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

        transformEntityHandle =
                HostHandles.virtual(transformType, GET_ENTITY_METHOD, GET_ENTITY_DESCRIPTOR, objectUnary);
        entityParentHandle =
                HostHandles.virtual(entityType, GET_PARENT_METHOD, GET_PARENT_DESCRIPTOR, objectUnary);
        entityTransformHandle =
                HostHandles.virtual(entityType, GET_TRANSFORM_METHOD, GET_TRANSFORM_DESCRIPTOR, objectUnary);
        staticMatrixHandle =
                HostHandles.getter(transformType, STATIC_MATRIX_FIELD, STATIC_MATRIX_DESCRIPTOR, objectUnary);
        positionHandle =
                HostHandles.virtual(transformType, GET_POSITION_METHOD, VECTOR3_DESCRIPTOR, objectUnary);
        eulerAnglesHandle =
                HostHandles.virtual(transformType, GET_EULER_METHOD, VECTOR3_DESCRIPTOR, objectUnary);
        scaleHandle = HostHandles.virtual(transformType, GET_SCALE_METHOD, VECTOR3_DESCRIPTOR, objectUnary);
        vecXHandle = HostHandles.virtual(vectorType, VECTOR_X_METHOD, FLOAT_GETTER_DESCRIPTOR, floatUnary);
        vecYHandle = HostHandles.virtual(vectorType, VECTOR_Y_METHOD, FLOAT_GETTER_DESCRIPTOR, floatUnary);
        vecZHandle = HostHandles.virtual(vectorType, VECTOR_Z_METHOD, FLOAT_GETTER_DESCRIPTOR, floatUnary);
        matrixConstructorHandle = HostHandles.construct(
                matrixType, MATRIX_CONSTRUCTOR_DESCRIPTOR, MethodType.methodType(Object.class));
        matrixDataHandle = HostHandles.virtual(
                matrixType, MATRIX_DATA_METHOD, MATRIX_DATA_DESCRIPTOR, MethodType.methodType(float[].class, Object.class));
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

    /** Returns an immutable snapshot of bridge telemetry counters. */
    public Map<String, Long> snapshot() {
        return Map.of(
                STAT_ACTIVE, active.get() ? 1L : 0L,
                STAT_CALLS, calls.sum(),
                STAT_CACHE_HITS, cacheHits.sum(),
                STAT_CACHE_MISSES, cacheMisses.sum(),
                STAT_STATIC_HITS, staticHits.sum(),
                STAT_FAILURES, failures.sum());
    }

    @Override
    public void close() {
        active.set(false);
        localCache.remove();
    }

    private Object resolveWorldMatrix(final Object transform) {
        if (!active.get() || !admission.get() || transform == null || transform.getClass() != transformType) {
            return null;
        }
        calls.increment();
        try {
            final Object selfEntity = (Object) transformEntityHandle.invokeExact(transform);
            if (selfEntity == null) {
                return null;
            }
            final LocalCache table = localCache.get();
            Object parentEntity = (Object) entityParentHandle.invokeExact(selfEntity);
            if (parentEntity == null) {
                // Return the static matrix instance directly to match root ownership in host code.
                final Object staticMatrix = (Object) staticMatrixHandle.invokeExact(transform);
                if (staticMatrix != null) {
                    staticHits.increment();
                    return staticMatrix;
                }
                final float[] selfData = resolveLocalToParentData(transform, table);
                if (selfData == null) {
                    return null;
                }
                final Object result = (Object) matrixConstructorHandle.invokeExact();
                final float[] out = (float[]) matrixDataHandle.invokeExact(result);
                if (out == null || out.length < MATRIX_LENGTH) {
                    return null;
                }
                System.arraycopy(selfData, 0, out, 0, MATRIX_LENGTH);
                return result;
            }

            final float[] current = resolveAnyLocalToParentData(transform, table);
            if (current == null) {
                return null;
            }
            Object parentTransform = (Object) entityTransformHandle.invokeExact(parentEntity);
            if (parentTransform == null) {
                return null;
            }
            float[] left = resolveAnyLocalToParentData(parentTransform, table);
            if (left == null) {
                return null;
            }
            Object nextParent = (Object) entityParentHandle.invokeExact(parentEntity);
            if (nextParent == null) {
                return allocateProduct(left, current);
            }

            float[] acc = table.scratchA;
            multiplyGeneral(left, current, acc);
            parentEntity = nextParent;
            for (int depth = 1; depth < MAX_CHAIN_DEPTH; depth++) {
                parentTransform = (Object) entityTransformHandle.invokeExact(parentEntity);
                if (parentTransform == null) {
                    return null;
                }
                left = resolveAnyLocalToParentData(parentTransform, table);
                if (left == null) {
                    return null;
                }
                nextParent = (Object) entityParentHandle.invokeExact(parentEntity);
                if (nextParent == null) {
                    return allocateProduct(left, acc);
                }
                final float[] dest = acc == table.scratchA ? table.scratchB : table.scratchA;
                multiplyGeneral(left, acc, dest);
                acc = dest;
                parentEntity = nextParent;
            }
            return null;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return null;
        }
    }

    private Object allocateProduct(final float[] left, final float[] right) throws Throwable {
        final Object result = (Object) matrixConstructorHandle.invokeExact();
        final float[] out = (float[]) matrixDataHandle.invokeExact(result);
        if (out == null || out.length < MATRIX_LENGTH) {
            return null;
        }
        multiplyGeneral(left, right, out);
        return result;
    }

    private float[] resolveAnyLocalToParentData(final Object transform, final LocalCache table) throws Throwable {
        final Object staticMatrix = (Object) staticMatrixHandle.invokeExact(transform);
        if (staticMatrix != null) {
            staticHits.increment();
            final float[] data = (float[]) matrixDataHandle.invokeExact(staticMatrix);
            return data != null && data.length >= MATRIX_LENGTH ? data : null;
        }
        return resolveLocalToParentData(transform, table);
    }

    private float[] resolveLocalToParentData(final Object transform, final LocalCache table) throws Throwable {
        final Object position = (Object) positionHandle.invokeExact(transform);
        final Object euler = (Object) eulerAnglesHandle.invokeExact(transform);
        final Object scale = (Object) scaleHandle.invokeExact(transform);
        if (position == null || euler == null || scale == null) {
            return null;
        }
        final float px = (float) vecXHandle.invokeExact(position);
        final float py = (float) vecYHandle.invokeExact(position);
        final float pz = (float) vecZHandle.invokeExact(position);
        final float ex = (float) vecXHandle.invokeExact(euler);
        final float ey = (float) vecYHandle.invokeExact(euler);
        final float ez = (float) vecZHandle.invokeExact(euler);
        final float sx = (float) vecXHandle.invokeExact(scale);
        final float sy = (float) vecYHandle.invokeExact(scale);
        final float sz = (float) vecZHandle.invokeExact(scale);

        final int pxBits = Float.floatToRawIntBits(px);
        final int pyBits = Float.floatToRawIntBits(py);
        final int pzBits = Float.floatToRawIntBits(pz);
        final int exBits = Float.floatToRawIntBits(ex);
        final int eyBits = Float.floatToRawIntBits(ey);
        final int ezBits = Float.floatToRawIntBits(ez);
        final int sxBits = Float.floatToRawIntBits(sx);
        final int syBits = Float.floatToRawIntBits(sy);
        final int szBits = Float.floatToRawIntBits(sz);

        final TransformEntry entry = table.findOrInsert(transform);
        if (entry.valid
                && entry.matches(pxBits, pyBits, pzBits, exBits, eyBits, ezBits, sxBits, syBits, szBits)) {
            cacheHits.increment();
            return entry.matrix;
        }
        cacheMisses.increment();
        computeLocalToParentMatrix(px, py, pz, ex, ey, ez, sx, sy, sz, entry.matrix);
        entry.recordState(pxBits, pyBits, pzBits, exBits, eyBits, ezBits, sxBits, syBits, szBits);
        return entry.matrix;
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

    private static final class TransformEntry extends WeakReference<Object> {
        final int identityHash;
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

        TransformEntry(final Object transform, final int identityHash) {
            super(transform);
            this.identityHash = identityHash;
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
     * Thread-confined weak identity cache eliminating synchronization and cross-thread tearing on hits.
     */
    private static final class LocalCache {
        final float[] scratchA = new float[MATRIX_LENGTH];
        final float[] scratchB = new float[MATRIX_LENGTH];
        private TransformEntry[] entries = new TransformEntry[INITIAL_TABLE_CAPACITY];
        private int occupiedSlots;
        private int nextRehashThreshold = (INITIAL_TABLE_CAPACITY * LOAD_NUMERATOR) / LOAD_DENOMINATOR;
        private int evictionCursor;

        TransformEntry findOrInsert(final Object transform) {
            final int rawHash = System.identityHashCode(transform);
            final int mask = entries.length - 1;
            final int start = mixHash(rawHash) & mask;
            int reusable = -1;
            final int probeLimit = entries.length >= MAX_TABLE_CAPACITY ? MAX_LINEAR_PROBES : entries.length;
            for (int step = 0; step < probeLimit; step++) {
                final int index = (start + step) & mask;
                final TransformEntry entry = entries[index];
                if (entry == null) {
                    return insertAt(transform, rawHash, reusable >= 0 ? reusable : index, reusable < 0);
                }
                if (entry.identityHash == rawHash && entry.refersTo(transform)) {
                    return entry;
                }
                if (reusable < 0 && entry.refersTo(null)) {
                    reusable = index;
                }
            }
            if (reusable >= 0) {
                return insertAt(transform, rawHash, reusable, false);
            }
            if (entries.length < MAX_TABLE_CAPACITY) {
                rehash(entries.length * CAPACITY_GROWTH_FACTOR);
                return findOrInsert(transform);
            }
            final int victim = (start + evictionCursor) & mask;
            evictionCursor = (evictionCursor + 1) & (MAX_LINEAR_PROBES - 1);
            return insertAt(transform, rawHash, victim, false);
        }

        private TransformEntry insertAt(
                final Object transform, final int rawHash, final int index, final boolean consumedNullSlot) {
            final TransformEntry created = new TransformEntry(transform, rawHash);
            entries[index] = created;
            if (consumedNullSlot) {
                occupiedSlots++;
                if (occupiedSlots >= nextRehashThreshold) {
                    rehash(entries.length < MAX_TABLE_CAPACITY
                            ? entries.length * CAPACITY_GROWTH_FACTOR
                            : entries.length);
                }
            }
            return created;
        }

        private void rehash(final int newCapacity) {
            final TransformEntry[] previous = entries;
            int live = 0;
            for (final TransformEntry entry : previous) {
                if (entry != null && !entry.refersTo(null)) {
                    live++;
                }
            }
            final boolean keepSize = live * LOAD_DENOMINATOR < previous.length * LOAD_NUMERATOR;
            final int targetCapacity = keepSize ? previous.length : Math.min(newCapacity, MAX_TABLE_CAPACITY);
            final TransformEntry[] next = new TransformEntry[targetCapacity];
            final int mask = targetCapacity - 1;
            final int probeLimit = targetCapacity >= MAX_TABLE_CAPACITY ? MAX_LINEAR_PROBES : targetCapacity;
            int nextOccupied = 0;
            for (final TransformEntry entry : previous) {
                if (entry == null || entry.refersTo(null)) {
                    continue;
                }
                final int start = mixHash(entry.identityHash) & mask;
                for (int step = 0; step < probeLimit; step++) {
                    final int index = (start + step) & mask;
                    if (next[index] == null) {
                        next[index] = entry;
                        nextOccupied++;
                        break;
                    }
                }
            }
            entries = next;
            occupiedSlots = nextOccupied;
            final int loadLimit = (targetCapacity * LOAD_NUMERATOR) / LOAD_DENOMINATOR;
            nextRehashThreshold = (targetCapacity >= MAX_TABLE_CAPACITY && nextOccupied >= loadLimit)
                    ? targetCapacity
                    : loadLimit;
        }
    }
}
