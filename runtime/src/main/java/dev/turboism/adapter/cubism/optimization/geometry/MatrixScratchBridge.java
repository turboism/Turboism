package dev.turboism.adapter.cubism.optimization.geometry;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Caches per-transform local-to-parent matrices keyed by exact raw float bits and resolves world
 * matrices through direct host accessors without intermediate allocations.
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

    private static final String OBJECT_INTERNAL = "java/lang/Object";
    private static final String OBJECT_ARRAY_DESCRIPTOR = "[Ljava/lang/Object;";
    private static final String INT_ARRAY_INTERNAL = "[I";
    private static final String VAR_HANDLE_INTERNAL = "java/lang/invoke/VarHandle";
    private static final String VAR_HANDLE_DESCRIPTOR = "L" + VAR_HANDLE_INTERNAL + ";";
    private static final String FLOAT_INTERNAL = "java/lang/Float";
    private static final String FLOAT_TO_RAW_BITS_METHOD = "floatToRawIntBits";
    private static final String FLOAT_TO_RAW_BITS_DESCRIPTOR = "(F)I";
    private static final String CONSTRUCTOR_NAME = "<init>";
    private static final String CLINIT_NAME = "<clinit>";
    private static final String VOID_DESCRIPTOR = "()V";
    private static final String BI_PREDICATE_INTERNAL = "java/util/function/BiPredicate";
    private static final String BI_FUNCTION_INTERNAL = "java/util/function/BiFunction";
    private static final String CONSUMER_INTERNAL = "java/util/function/Consumer";
    private static final String ACCESSOR_PACKAGE = "dev/turboism/adapter/cubism/optimization/geometry/";
    private static final String BOOT_INTERNAL = ACCESSOR_PACKAGE + "MatrixAccessorBoot";
    private static final String ACCESSOR_INTERNAL = ACCESSOR_PACKAGE + "MatrixHostAccessor";
    private static final String BOOT_HANDLE_FIELD = "handle";
    private static final String ACCESSOR_VAR_FIELD = "STATIC_MATRIX";
    private static final String READ_NODE_METHOD = "readNode";
    private static final String READ_NODE_DESCRIPTOR =
            "(L" + MatrixScratchTransformer.OWNER + ";" + OBJECT_ARRAY_DESCRIPTOR + ")Z";

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
    private static final int HOLDER_MATRIX_INDEX = 1;
    private static final int HOLDER_SIZE = 2;

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
    private final BiPredicate<Object, Object> selfReader;
    private final BiFunction<Object, Object, Object> parentReader;
    private final Consumer<Object> matrixAllocator;

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
     * Resolves every reviewed host member and compiles direct host accessors on the admitted loader.
     *
     * @param loader admitted host class loader
     * @param admission installer-owned gate controlling live execution
     * @throws ReflectiveOperationException when any required host member is absent or closed
     */
    @SuppressWarnings("unchecked")
    public MatrixScratchBridge(final ClassLoader loader, final AtomicBoolean admission)
            throws ReflectiveOperationException {
        this.admission = Objects.requireNonNull(admission, "admission");
        transformType = HostHandles.load(loader, MatrixScratchTransformer.OWNER);
        final Class<?> entityType = HostHandles.load(loader, ENTITY_OWNER);
        final Class<?> vectorType = HostHandles.load(loader, VECTOR3_OWNER);
        final Class<?> matrixType = HostHandles.load(loader, MatrixScratchTransformer.MATRIX);

        final MethodType objectUnary = MethodType.methodType(Object.class, Object.class);
        final MethodType floatUnary = MethodType.methodType(float.class, Object.class);

        final MethodHandle getEntityHandle =
                HostHandles.virtual(transformType, GET_ENTITY_METHOD, GET_ENTITY_DESCRIPTOR, objectUnary);
        final MethodHandle getParentEntityHandle =
                HostHandles.virtual(entityType, GET_PARENT_METHOD, GET_PARENT_DESCRIPTOR, objectUnary);
        final MethodHandle getTransformHandle =
                HostHandles.virtual(entityType, GET_TRANSFORM_METHOD, GET_TRANSFORM_DESCRIPTOR, objectUnary);
        final MethodHandle getPositionHandle =
                HostHandles.virtual(transformType, GET_POSITION_METHOD, VECTOR3_DESCRIPTOR, objectUnary);
        final MethodHandle getEulerAnglesHandle =
                HostHandles.virtual(transformType, GET_EULER_METHOD, VECTOR3_DESCRIPTOR, objectUnary);
        final MethodHandle getScaleHandle =
                HostHandles.virtual(transformType, GET_SCALE_METHOD, VECTOR3_DESCRIPTOR, objectUnary);
        final MethodHandle vectorXHandle =
                HostHandles.virtual(vectorType, VECTOR_X_METHOD, FLOAT_GETTER_DESCRIPTOR, floatUnary);
        final MethodHandle vectorYHandle =
                HostHandles.virtual(vectorType, VECTOR_Y_METHOD, FLOAT_GETTER_DESCRIPTOR, floatUnary);
        final MethodHandle vectorZHandle =
                HostHandles.virtual(vectorType, VECTOR_Z_METHOD, FLOAT_GETTER_DESCRIPTOR, floatUnary);
        final MethodHandle matrixConstructorHandle =
                HostHandles.construct(matrixType, MATRIX_CONSTRUCTOR_DESCRIPTOR, MethodType.methodType(Object.class));
        final MethodHandle matrixDataHandle = HostHandles.virtual(
                matrixType,
                MATRIX_DATA_METHOD,
                MATRIX_DATA_DESCRIPTOR,
                MethodType.methodType(float[].class, Object.class));
        final MethodHandle staticMatrixHandle =
                HostHandles.getter(transformType, STATIC_MATRIX_FIELD, STATIC_MATRIX_DESCRIPTOR, objectUnary);

        final Object compiled = compileHostAccessor(loader, transformType);
        if (compiled != null) {
            this.selfReader = (BiPredicate<Object, Object>) compiled;
            this.parentReader = (BiFunction<Object, Object, Object>) compiled;
            this.matrixAllocator = (Consumer<Object>) compiled;
        } else {
            final FallbackAccessors fallback = new FallbackAccessors(
                    getEntityHandle,
                    getParentEntityHandle,
                    getTransformHandle,
                    getPositionHandle,
                    getEulerAnglesHandle,
                    getScaleHandle,
                    vectorXHandle,
                    vectorYHandle,
                    vectorZHandle,
                    matrixConstructorHandle,
                    matrixDataHandle,
                    staticMatrixHandle);
            this.selfReader = fallback;
            this.parentReader = fallback;
            this.matrixAllocator = fallback;
        }
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
            if (!selfReader.test(transform, packet)) {
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
                matrixAllocator.accept(state.matrixOutHolder);
                final float[] out = (float[]) state.matrixOutHolder[HOLDER_ARRAY_INDEX];
                if (out == null || out.length < MATRIX_LENGTH) {
                    return null;
                }
                System.arraycopy(selfData, 0, out, 0, MATRIX_LENGTH);
                return state.matrixOutHolder[HOLDER_MATRIX_INDEX];
            }

            final float[] current = resolveNodeData(transform, packet, state);
            if (current == null) {
                return null;
            }
            Object parentTransform = parentReader.apply(parentEntity, packet);
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
                parentTransform = parentReader.apply(parentEntity, packet);
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

    private Object allocateProduct(final float[] left, final float[] right, final ThreadState state) {
        matrixAllocator.accept(state.matrixOutHolder);
        final float[] out = (float[]) state.matrixOutHolder[HOLDER_ARRAY_INDEX];
        if (out == null || out.length < MATRIX_LENGTH) {
            return null;
        }
        multiplyGeneral(left, right, out);
        return state.matrixOutHolder[HOLDER_MATRIX_INDEX];
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

    private static Object compileHostAccessor(final ClassLoader loader, final Class<?> transformType) {
        try {
            final Field staticField = transformType.getDeclaredField(STATIC_MATRIX_FIELD);
            final MethodHandles.Lookup privateLookup =
                    MethodHandles.privateLookupIn(transformType, MethodHandles.lookup());
            final VarHandle staticMatrixVarHandle = privateLookup.unreflectVarHandle(staticField);
            final AccessorClassLoader accessorLoader = new AccessorClassLoader(loader);
            final Class<?> bootClass = accessorLoader.define(BOOT_INTERNAL.replace('/', '.'), buildBootClass());
            bootClass.getField(BOOT_HANDLE_FIELD).set(null, staticMatrixVarHandle);
            final Class<?> accessorClass =
                    accessorLoader.define(ACCESSOR_INTERNAL.replace('/', '.'), buildAccessorClass(loader));
            return accessorClass.getConstructor().newInstance();
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static byte[] buildBootClass() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                BOOT_INTERNAL,
                null,
                OBJECT_INTERNAL,
                null);
        writer.visitField(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        BOOT_HANDLE_FIELD,
                        VAR_HANDLE_DESCRIPTOR,
                        null,
                        null)
                .visitEnd();
        final MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, CONSTRUCTOR_NAME, VOID_DESCRIPTOR, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, OBJECT_INTERNAL, CONSTRUCTOR_NAME, VOID_DESCRIPTOR, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] buildAccessorClass(final ClassLoader loader) {
        final ClassWriter writer = HostClassWriters.newFrameWriter(loader);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                ACCESSOR_INTERNAL,
                null,
                OBJECT_INTERNAL,
                new String[] {BI_PREDICATE_INTERNAL, BI_FUNCTION_INTERNAL, CONSUMER_INTERNAL});
        writer.visitField(
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                        ACCESSOR_VAR_FIELD,
                        VAR_HANDLE_DESCRIPTOR,
                        null,
                        null)
                .visitEnd();

        final MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, CLINIT_NAME, VOID_DESCRIPTOR, null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(Opcodes.GETSTATIC, BOOT_INTERNAL, BOOT_HANDLE_FIELD, VAR_HANDLE_DESCRIPTOR);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, ACCESSOR_INTERNAL, ACCESSOR_VAR_FIELD, VAR_HANDLE_DESCRIPTOR);
        clinit.visitInsn(Opcodes.ACONST_NULL);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, BOOT_INTERNAL, BOOT_HANDLE_FIELD, VAR_HANDLE_DESCRIPTOR);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        final MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, CONSTRUCTOR_NAME, VOID_DESCRIPTOR, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, OBJECT_INTERNAL, CONSTRUCTOR_NAME, VOID_DESCRIPTOR, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        emitReadNodeMethod(writer);
        emitTestSelfMethod(writer);
        emitApplyParentMethod(writer);
        emitApplyAllocateMatrixMethod(writer);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitReadNodeMethod(final ClassWriter writer) {
        final MethodVisitor mv = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, READ_NODE_METHOD, READ_NODE_DESCRIPTOR, null, null);
        mv.visitCode();

        final Label noStatic = new Label();
        final Label missingVector = new Label();

        mv.visitFieldInsn(Opcodes.GETSTATIC, ACCESSOR_INTERNAL, ACCESSOR_VAR_FIELD, VAR_HANDLE_DESCRIPTOR);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                VAR_HANDLE_INTERNAL,
                "get",
                "(L" + MatrixScratchTransformer.OWNER + ";)" + STATIC_MATRIX_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNULL, noStatic);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                MatrixScratchTransformer.MATRIX,
                MATRIX_DATA_METHOD,
                MATRIX_DATA_DESCRIPTOR,
                false);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(noStatic);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_3);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.AASTORE);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, MatrixScratchTransformer.OWNER, GET_POSITION_METHOD, VECTOR3_DESCRIPTOR, false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, MatrixScratchTransformer.OWNER, GET_EULER_METHOD, VECTOR3_DESCRIPTOR, false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, MatrixScratchTransformer.OWNER, GET_SCALE_METHOD, VECTOR3_DESCRIPTOR, false);
        mv.visitVarInsn(Opcodes.ASTORE, 5);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitJumpInsn(Opcodes.IFNULL, missingVector);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitJumpInsn(Opcodes.IFNULL, missingVector);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitJumpInsn(Opcodes.IFNULL, missingVector);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitTypeInsn(Opcodes.CHECKCAST, INT_ARRAY_INTERNAL);
        mv.visitVarInsn(Opcodes.ASTORE, 6);

        emitWriteVectorBits(mv, 6, 3, PX_BITS);
        emitWriteVectorBits(mv, 6, 4, EX_BITS);
        emitWriteVectorBits(mv, 6, 5, SX_BITS);

        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(missingVector);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitWriteVectorBits(
            final MethodVisitor mv, final int bitsLocal, final int vectorLocal, final int baseOffset) {
        emitWriteComponentBits(mv, bitsLocal, vectorLocal, baseOffset, VECTOR_X_METHOD);
        emitWriteComponentBits(mv, bitsLocal, vectorLocal, baseOffset + 1, VECTOR_Y_METHOD);
        emitWriteComponentBits(mv, bitsLocal, vectorLocal, baseOffset + 2, VECTOR_Z_METHOD);
    }

    private static void emitWriteComponentBits(
            final MethodVisitor mv,
            final int bitsLocal,
            final int vectorLocal,
            final int bitIndex,
            final String getterMethod) {
        mv.visitVarInsn(Opcodes.ALOAD, bitsLocal);
        mv.visitIntInsn(Opcodes.BIPUSH, bitIndex);
        mv.visitVarInsn(Opcodes.ALOAD, vectorLocal);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VECTOR3_OWNER, getterMethod, FLOAT_GETTER_DESCRIPTOR, false);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, FLOAT_INTERNAL, FLOAT_TO_RAW_BITS_METHOD, FLOAT_TO_RAW_BITS_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.IASTORE);
    }

    private static void emitTestSelfMethod(final ClassWriter writer) {
        final MethodVisitor mv = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "test", "(L" + OBJECT_INTERNAL + ";L" + OBJECT_INTERNAL + ";)Z", null, null);
        mv.visitCode();
        final Label missingEntity = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, MatrixScratchTransformer.OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, 3);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, MatrixScratchTransformer.OWNER, GET_ENTITY_METHOD, GET_ENTITY_DESCRIPTOR, false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitJumpInsn(Opcodes.IFNULL, missingEntity);

        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, OBJECT_ARRAY_DESCRIPTOR);
        mv.visitVarInsn(Opcodes.ASTORE, 5);

        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY_OWNER, GET_PARENT_METHOD, GET_PARENT_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.AASTORE);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ACCESSOR_INTERNAL, READ_NODE_METHOD, READ_NODE_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(missingEntity);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitApplyParentMethod(final ClassWriter writer) {
        final MethodVisitor mv = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                "apply",
                "(L" + OBJECT_INTERNAL + ";L" + OBJECT_INTERNAL + ";)L" + OBJECT_INTERNAL + ";",
                null,
                null);
        mv.visitCode();
        final Label returnNull = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ENTITY_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, 3);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY_OWNER, GET_TRANSFORM_METHOD, GET_TRANSFORM_DESCRIPTOR, false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitJumpInsn(Opcodes.IFNULL, returnNull);

        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, OBJECT_ARRAY_DESCRIPTOR);
        mv.visitVarInsn(Opcodes.ASTORE, 5);

        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY_OWNER, GET_PARENT_METHOD, GET_PARENT_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.AASTORE);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, ACCESSOR_INTERNAL, READ_NODE_METHOD, READ_NODE_DESCRIPTOR, false);
        mv.visitJumpInsn(Opcodes.IFEQ, returnNull);

        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(returnNull);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitApplyAllocateMatrixMethod(final ClassWriter writer) {
        final MethodVisitor mv = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "accept", "(L" + OBJECT_INTERNAL + ";)V", null, null);
        mv.visitCode();

        mv.visitTypeInsn(Opcodes.NEW, MatrixScratchTransformer.MATRIX);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                MatrixScratchTransformer.MATRIX,
                CONSTRUCTOR_NAME,
                MATRIX_CONSTRUCTOR_DESCRIPTOR,
                false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, OBJECT_ARRAY_DESCRIPTOR);
        mv.visitVarInsn(Opcodes.ASTORE, 3);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                MatrixScratchTransformer.MATRIX,
                MATRIX_DATA_METHOD,
                MATRIX_DATA_DESCRIPTOR,
                false);
        mv.visitInsn(Opcodes.AASTORE);

        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.AASTORE);

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static final class AccessorClassLoader extends ClassLoader {
        AccessorClassLoader(final ClassLoader parent) {
            super(parent);
        }

        Class<?> define(final String binaryName, final byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }

    private record FallbackAccessors(
            MethodHandle getEntityHandle,
            MethodHandle getParentEntityHandle,
            MethodHandle getTransformHandle,
            MethodHandle getPositionHandle,
            MethodHandle getEulerAnglesHandle,
            MethodHandle getScaleHandle,
            MethodHandle vectorXHandle,
            MethodHandle vectorYHandle,
            MethodHandle vectorZHandle,
            MethodHandle matrixConstructorHandle,
            MethodHandle matrixDataHandle,
            MethodHandle staticMatrixHandle)
            implements BiPredicate<Object, Object>, BiFunction<Object, Object, Object>, Consumer<Object> {

        @Override
        public boolean test(final Object transform, final Object packetObj) {
            try {
                final Object entity = (Object) getEntityHandle.invokeExact(transform);
                if (entity == null) {
                    return false;
                }
                final Object[] packet = (Object[]) packetObj;
                packet[PACKET_PARENT_INDEX] = (Object) getParentEntityHandle.invokeExact(entity);
                return readNode(transform, packet);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw wrapThrowable(failure);
            }
        }

        @Override
        public Object apply(final Object entity, final Object packetObj) {
            try {
                final Object transform = (Object) getTransformHandle.invokeExact(entity);
                if (transform == null) {
                    return null;
                }
                final Object[] packet = (Object[]) packetObj;
                packet[PACKET_PARENT_INDEX] = (Object) getParentEntityHandle.invokeExact(entity);
                return readNode(transform, packet) ? transform : null;
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw wrapThrowable(failure);
            }
        }

        @Override
        public void accept(final Object holderObj) {
            try {
                final Object matrix = (Object) matrixConstructorHandle.invokeExact();
                final Object[] holder = (Object[]) holderObj;
                holder[HOLDER_ARRAY_INDEX] = (float[]) matrixDataHandle.invokeExact(matrix);
                holder[HOLDER_MATRIX_INDEX] = matrix;
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw wrapThrowable(failure);
            }
        }

        private boolean readNode(final Object transform, final Object[] packet) throws Throwable {
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

        private static RuntimeException wrapThrowable(final Throwable failure) {
            if (failure instanceof RuntimeException runtime) {
                return runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return new IllegalStateException(failure);
        }
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

    private static final class TransformSlot extends WeakReference<Object> {
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
        final float[] matrix;

        TransformSlot(final Object transform, final float[] matrix) {
            super(transform);
            this.matrix = matrix;
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
     * Thread-confined two-way set-associative identity cache storing flat hashes and lazy slots.
     */
    private static final class LocalCache {
        private final TransformSlot[] slots = new TransformSlot[CACHE_TOTAL_SLOTS];
        private final int[] hashes = new int[CACHE_TOTAL_SLOTS];
        private final byte[] mruWay = new byte[CACHE_SET_COUNT];

        TransformSlot findOrBind(final Object transform) {
            final int rawHash = System.identityHashCode(transform);
            final int setIndex = mixHash(rawHash) & CACHE_SET_MASK;
            final int base = setIndex << CACHE_WAY_SHIFT;

            if (hashes[base] == rawHash) {
                final TransformSlot slot0 = slots[base];
                if (slot0 != null && slot0.refersTo(transform)) {
                    mruWay[setIndex] = WAY_0;
                    return slot0;
                }
            }
            final int way1Index = base + WAY_1;
            if (hashes[way1Index] == rawHash) {
                final TransformSlot slot1 = slots[way1Index];
                if (slot1 != null && slot1.refersTo(transform)) {
                    mruWay[setIndex] = WAY_1;
                    return slot1;
                }
            }
            final TransformSlot slot0 = slots[base];
            if (slot0 == null || slot0.refersTo(null)) {
                return bindSlot(base, setIndex, WAY_0, rawHash, transform, slot0);
            }
            final TransformSlot slot1 = slots[way1Index];
            if (slot1 == null || slot1.refersTo(null)) {
                return bindSlot(way1Index, setIndex, WAY_1, rawHash, transform, slot1);
            }
            final byte victimWay = (byte) (mruWay[setIndex] ^ WAY_1);
            final int victimIndex = base + victimWay;
            return bindSlot(victimIndex, setIndex, victimWay, rawHash, transform, slots[victimIndex]);
        }

        private TransformSlot bindSlot(
                final int slotIndex,
                final int setIndex,
                final byte way,
                final int rawHash,
                final Object transform,
                final TransformSlot previous) {
            final float[] matrix = previous != null ? previous.matrix : new float[MATRIX_LENGTH];
            final TransformSlot bound = new TransformSlot(transform, matrix);
            slots[slotIndex] = bound;
            hashes[slotIndex] = rawHash;
            mruWay[setIndex] = way;
            return bound;
        }
    }
}
