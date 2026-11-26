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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
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
import java.util.function.ToLongBiFunction;
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

    /** Property key publishing the {@code Function<Object, Object>} render-scratch matrix resolver. */
    public static final String RENDER_CALLBACK_PROPERTY = "turboism.matrix-scratch.render-callback";

    /** Property key publishing the {@code ToLongBiFunction<Object, Object>} sorting-Z resolver. */
    public static final String SORTING_CALLBACK_PROPERTY = "turboism.matrix-scratch.sorting-callback";

    /** Property key exposing cache telemetry counters. */
    public static final String STATS_PROPERTY = "turboism.matrix-scratch.stats";

    private static final String STAT_ACTIVE = "active";
    private static final String STAT_CALLS = "calls";
    private static final String STAT_CACHE_HITS = "cacheHits";
    private static final String STAT_IDENTITY_HITS = "identityHits";
    private static final String STAT_CACHE_MISSES = "cacheMisses";
    private static final String STAT_STATIC_HITS = "staticHits";
    private static final String STAT_FAILURES = "failures";

    private static final String CAMERA_CACHE_GET_PROPERTY = "turboism.camera-matrix.get";
    private static final String MESH_CONTAINER_OWNER = "com/live2d/graphics3d/component/GMeshContainer";
    private static final String MESH_OWNER = "com/live2d/graphics3d/mesh/a";
    private static final String CAMERA_OWNER = "com/live2d/graphics3d/GCamera";
    private static final String GET_MESH_CONTAINER_METHOD = "getMeshContainer";
    private static final String GET_MESH_CONTAINER_DESCRIPTOR = "()L" + MESH_CONTAINER_OWNER + ";";
    private static final String GET_MESH_METHOD = "getMesh";
    private static final String GET_MESH_DESCRIPTOR = "()L" + MESH_OWNER + ";";
    private static final String GET_POSITIONS_METHOD = "getPositions";
    private static final String FLOAT_ARRAY_GETTER_DESCRIPTOR = "()[F";
    private static final String GET_STEP_METHOD = "getStep";
    private static final String INT_GETTER_DESCRIPTOR = "()I";
    private static final String MATRIX_INVERT_METHOD = "b";
    private static final String MATRIX_INVERT_DESCRIPTOR =
            "(L" + MatrixScratchTransformer.MATRIX + ";)L" + MatrixScratchTransformer.MATRIX + ";";

    private static final String ENTITY_OWNER = "com/live2d/graphics3d/entity/GEntity";
    private static final String COMPONENTS_OWNER = "com/live2d/graphics3d/entity/Components";
    private static final String VECTOR3_OWNER = "com/live2d/graphics3d/type/GVector3";
    private static final String GET_ENTITY_METHOD = "getEntity";
    private static final String GET_ENTITY_DESCRIPTOR = "()L" + ENTITY_OWNER + ";";
    private static final String GET_COMPONENTS_METHOD = "getComponents";
    private static final String GET_COMPONENTS_DESCRIPTOR = "()L" + COMPONENTS_OWNER + ";";
    private static final String COMPONENTS_LIST_METHOD = "a";
    private static final String COMPONENTS_LIST_DESCRIPTOR = "()Ljava/util/List;";
    private static final String LIST_INTERNAL = "java/util/List";
    private static final String RANDOM_ACCESS_INTERNAL = "java/util/RandomAccess";
    private static final String LIST_SIZE_METHOD = "size";
    private static final String LIST_SIZE_DESCRIPTOR = "()I";
    private static final String LIST_GET_METHOD = "get";
    private static final String LIST_GET_DESCRIPTOR = "(I)Ljava/lang/Object;";
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
    private static final String FUNCTION_INTERNAL = "java/util/function/Function";
    private static final String CONSUMER_INTERNAL = "java/util/function/Consumer";
    private static final String ACCESSOR_PACKAGE = "dev/turboism/adapter/cubism/optimization/geometry/";
    private static final String BOOT_INTERNAL = ACCESSOR_PACKAGE + "MatrixAccessorBoot";
    private static final String ACCESSOR_INTERNAL = ACCESSOR_PACKAGE + "MatrixHostAccessor";
    private static final String SORTING_ACCESSOR_INTERNAL = ACCESSOR_PACKAGE + "SortingHostAccessor";
    private static final String BOOT_HANDLE_FIELD = "handle";
    private static final String ACCESSOR_VAR_FIELD = "STATIC_MATRIX";
    private static final String READ_NODE_METHOD = "readNode";
    private static final String READ_NODE_DESCRIPTOR =
            "(L" + MatrixScratchTransformer.OWNER + ";" + OBJECT_ARRAY_DESCRIPTOR + ")Z";
    private static final String TEST_METHOD = "test";
    private static final String TEST_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
    private static final String FUNCTION_APPLY_METHOD = "apply";
    private static final String FUNCTION_APPLY_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String BI_FUNCTION_APPLY_DESCRIPTOR =
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

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

    private static final int CACHE_SET_COUNT = 4096;
    private static final int CACHE_WAY_SHIFT = 2;
    private static final int CACHE_WAY_COUNT = 4;
    private static final int CACHE_TOTAL_SLOTS = CACHE_SET_COUNT * CACHE_WAY_COUNT;
    private static final int CACHE_SET_MASK = CACHE_SET_COUNT - 1;
    private static final int WAY_0 = 0;
    private static final int WAY_1 = 1;
    private static final int WAY_2 = 2;
    private static final int WAY_3 = 3;
    private static final int PLRU_RIGHT_MASK = 0x01;
    private static final int PLRU_LEFT_SUB_MASK = 0x02;
    private static final int PLRU_RIGHT_SUB_MASK = 0x04;
    private static final int PLRU_SET_WAY0 = 0x03;
    private static final int PLRU_SET_WAY1 = 0x01;
    private static final int PLRU_CLEAR_WAY1 = ~0x02;
    private static final int PLRU_SET_WAY2 = 0x04;
    private static final int PLRU_CLEAR_WAY2 = ~0x01;
    private static final int PLRU_CLEAR_WAY3 = ~0x05;
    private static final int PLRU_BYTE_MASK = 0xFF;
    private static final int HASH_SHIFT = 16;
    private static final int FIBONACCI_HASH_MULTIPLIER = 0x9e3779b9;
    private static final int MAX_CHAIN_DEPTH = 4096;
    private static final int ABS_INT_MASK = 0x7fffffff;
    private static final int EXPONENT_MASK = 0x7f800000;
    private static final int NEGATIVE_ZERO_BITS = 0x80000000;
    private static final int FLOAT_ONE_BITS = 0x3f800000;
    private static final long SORTING_VALID_BIT = 1L << 32;
    private static final long SORTING_FALLBACK = 0L;
    private static final long FLOAT_BITS_MASK = 0xffffffffL;
    private static final int MESH_STEP_2D = 2;
    private static final int POS_X_INDEX = 0;
    private static final int POS_Y_INDEX = 1;
    private static final int POS_Z_INDEX = 2;
    private static final int SORTING_PACKET_FLAGS_INDEX = 0;
    private static final int SORTING_PACKET_POSITIONS_INDEX = 1;
    private static final int SORTING_PACKET_TRANSFORM_INDEX = 2;
    private static final int SORTING_PACKET_SIZE = 3;
    private static final int SORTING_STATUS_FALLBACK = 0;
    private static final int SORTING_STATUS_ZERO = 1;
    private static final int FLAG_ARRAY_INDEX = 0;
    private static final int MATRIX_IDX_X0 = 0;
    private static final int MATRIX_IDX_Y0 = 1;
    private static final int MATRIX_IDX_Z0 = 2;
    private static final int MATRIX_IDX_X1 = 4;
    private static final int MATRIX_IDX_Y1 = 5;
    private static final int MATRIX_IDX_Z1 = 6;
    private static final int MATRIX_IDX_X2 = 8;
    private static final int MATRIX_IDX_Y2 = 9;
    private static final int MATRIX_IDX_Z2 = 10;
    private static final int MATRIX_IDX_TX = 12;
    private static final int MATRIX_IDX_TY = 13;
    private static final int MATRIX_IDX_TZ = 14;
    private static final float[] IDENTITY_MATRIX_DATA = {
        ONE_F, ZERO_F, ZERO_F, ZERO_F,
        ZERO_F, ONE_F, ZERO_F, ZERO_F,
        ZERO_F, ZERO_F, ONE_F, ZERO_F,
        ZERO_F, ZERO_F, ZERO_F, ONE_F
    };

    private final Class<?> transformType;
    private final AtomicBoolean admission;
    private final BiPredicate<Object, Object> selfReader;
    private final BiFunction<Object, Object, Object> parentReader;
    private final Consumer<Object> matrixAllocator;
    private final RenderAccessors renderAccessors;

    private final AtomicBoolean active = new AtomicBoolean();
    private final Set<ThreadState> threadStates = ConcurrentHashMap.newKeySet();
    private final AtomicLong retiredCalls = new AtomicLong();
    private final AtomicLong retiredCacheHits = new AtomicLong();
    private final AtomicLong retiredIdentityHits = new AtomicLong();
    private final AtomicLong retiredCacheMisses = new AtomicLong();
    private final AtomicLong retiredStaticHits = new AtomicLong();
    private final AtomicLong retiredFailures = new AtomicLong();
    private final ThreadLocal<ThreadState> threadState = ThreadLocal.withInitial(this::registerThreadState);

    private final Function<Object, Object> callback = this::resolveWorldMatrix;
    private final Function<Object, Object> renderCallback = this::resolveRenderMatrix;
    private final ToLongBiFunction<Object, Object> sortingCallback = this::resolveSortingZOrder;
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
        this.renderAccessors =
                resolveRenderAccessors(loader, entityType, transformType, matrixType, getTransformHandle);

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

    /** Returns the non-escaping render scratch callback registered under {@link #RENDER_CALLBACK_PROPERTY}. */
    public Function<Object, Object> renderCallback() {
        return renderCallback;
    }

    /** Returns the scalar sorting-Z callback registered under {@link #SORTING_CALLBACK_PROPERTY}. */
    public ToLongBiFunction<Object, Object> sortingCallback() {
        return sortingCallback;
    }

    /** Returns the counter snapshot supplier registered under {@link #STATS_PROPERTY}. */
    public Supplier<Map<String, Long>> statistics() {
        return statistics;
    }

    /** Returns whether optional renderer and camera fast-path accessors resolved on this loader. */
    public boolean supportsRenderFastPaths() {
        return renderAccessors != null;
    }

    /** Returns whether sorting-Z resolution uses the compiled monomorphic host accessor. */
    boolean usesCompiledRenderAccessors() {
        return renderAccessors != null && renderAccessors.compiled();
    }

    /** Returns an immutable snapshot of bridge telemetry counters across all threads. */
    public Map<String, Long> snapshot() {
        long totalCalls = retiredCalls.get();
        long totalHits = retiredCacheHits.get();
        long totalIdentityHits = retiredIdentityHits.get();
        long totalMisses = retiredCacheMisses.get();
        long totalStatic = retiredStaticHits.get();
        long totalFailures = retiredFailures.get();
        final Iterator<ThreadState> iterator = threadStates.iterator();
        while (iterator.hasNext()) {
            final ThreadState state = iterator.next();
            totalCalls += state.calls;
            totalHits += state.cacheHits;
            totalIdentityHits += state.identityHits;
            totalMisses += state.cacheMisses;
            totalStatic += state.staticHits;
            totalFailures += state.failures;
            if (!state.owner.isAlive()) {
                retiredCalls.addAndGet(state.calls);
                retiredCacheHits.addAndGet(state.cacheHits);
                retiredIdentityHits.addAndGet(state.identityHits);
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
                STAT_IDENTITY_HITS, totalIdentityHits,
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
            final float[] worldData = resolveWorldData(transform, state, state.staticHolder, state.scratchC, false);
            if (worldData == null) {
                return null;
            }
            if (state.staticHolder[0] != null) {
                return state.staticHolder[0];
            }
            matrixAllocator.accept(state.matrixOutHolder);
            final float[] out = (float[]) state.matrixOutHolder[HOLDER_ARRAY_INDEX];
            if (out == null || out.length < MATRIX_LENGTH) {
                return null;
            }
            System.arraycopy(worldData, 0, out, 0, MATRIX_LENGTH);
            return state.matrixOutHolder[HOLDER_MATRIX_INDEX];
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            state.failures++;
            return null;
        }
    }

    private Object resolveRenderMatrix(final Object transform) {
        if (!active.get() || !admission.get() || transform == null || transform.getClass() != transformType) {
            return null;
        }
        final ThreadState state = threadState.get();
        state.calls++;
        try {
            final float[] worldData = resolveWorldData(transform, state, state.staticHolder, state.scratchC, false);
            if (worldData == null) {
                return null;
            }
            if (state.staticHolder[0] != null) {
                return state.staticHolder[0];
            }
            if (!ensureScratchMatrix(state, state.renderScratch)) {
                return null;
            }
            System.arraycopy(worldData, 0, state.renderScratch.data, 0, MATRIX_LENGTH);
            return state.renderScratch.instance;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            state.failures++;
            return null;
        }
    }

    private long resolveSortingZOrder(final Object meshRenderer, final Object camera) {
        if (!active.get() || !admission.get() || renderAccessors == null || meshRenderer == null) {
            return SORTING_FALLBACK;
        }
        if (meshRenderer.getClass() != renderAccessors.meshRendererType()
                || (camera != null && camera.getClass() != renderAccessors.cameraType())) {
            return SORTING_FALLBACK;
        }
        final ThreadState state = threadState.get();
        state.calls++;
        try {
            if (!renderAccessors.rendererProbe().test(meshRenderer, state.sortingPacket)) {
                state.sortingPacket[SORTING_PACKET_POSITIONS_INDEX] = null;
                state.sortingPacket[SORTING_PACKET_TRANSFORM_INDEX] = null;
                return state.sortingFlags[FLAG_ARRAY_INDEX] == SORTING_STATUS_ZERO
                        ? encodeValidFloat(ZERO_F)
                        : SORTING_FALLBACK;
            }
            final float[] positions = (float[]) state.sortingPacket[SORTING_PACKET_POSITIONS_INDEX];
            final int step = state.sortingFlags[FLAG_ARRAY_INDEX];
            final Object transform = state.sortingPacket[SORTING_PACKET_TRANSFORM_INDEX];
            state.sortingPacket[SORTING_PACKET_POSITIONS_INDEX] = null;
            state.sortingPacket[SORTING_PACKET_TRANSFORM_INDEX] = null;

            final float vx = positions[POS_X_INDEX];
            final float vy = positions[POS_Y_INDEX];
            final float vz = step == MESH_STEP_2D ? ZERO_F : positions[POS_Z_INDEX];
            final float[] world = resolveWorldData(transform, state, null, state.scratchC, false);
            if (world == null) {
                return SORTING_FALLBACK;
            }
            final float wz = transformZ(world, vx, vy, vz);
            if (camera == null) {
                return encodeValidFloat(wz);
            }
            final float wx = transformX(world, vx, vy, vz);
            final float wy = transformY(world, vx, vy, vz);

            final float[] cameraData = resolveWorldToCameraData(camera, state);
            if (cameraData == null) {
                return SORTING_FALLBACK;
            }
            return encodeValidFloat(transformZ(cameraData, wx, wy, wz));
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            // Let native bytecode reproduce ArrayIndexOutOfBoundsException or NoninvertibleTransformException.
            state.sortingPacket[SORTING_PACKET_POSITIONS_INDEX] = null;
            state.sortingPacket[SORTING_PACKET_TRANSFORM_INDEX] = null;
            state.failures++;
            return SORTING_FALLBACK;
        }
    }

    @SuppressWarnings("unchecked")
    private float[] resolveWorldToCameraData(final Object camera, final ThreadState state) {
        final Object camCacheFn = System.getProperties().get(CAMERA_CACHE_GET_PROPERTY);
        if (camCacheFn instanceof Function<?, ?> getter) {
            final Object cachedMatrix = ((Function<Object, Object>) getter).apply(camera);
            if (cachedMatrix != null) {
                final float[] cachedData =
                        (float[]) renderAccessors.matrixDataReader().apply(cachedMatrix);
                if (cachedData != null && cachedData.length >= MATRIX_LENGTH) {
                    return cachedData;
                }
            }
        }
        final Object camTransform = renderAccessors.cameraOrInvert().apply(camera, null);
        if (camTransform == null) {
            return null;
        }
        final float[] camWorld = resolveWorldData(camTransform, state, null, state.scratchC, false);
        if (camWorld == null
                || !ensureScratchMatrix(state, state.cameraWorldScratch)
                || !ensureScratchMatrix(state, state.cameraInverseScratch)) {
            return null;
        }
        System.arraycopy(camWorld, 0, state.cameraWorldScratch.data, 0, MATRIX_LENGTH);
        final Object inverted = renderAccessors
                .cameraOrInvert()
                .apply(state.cameraWorldScratch.instance, state.cameraInverseScratch.instance);
        return inverted == null ? null : state.cameraInverseScratch.data;
    }

    private float[] resolveWorldData(
            final Object transform,
            final ThreadState state,
            final Object[] outStaticMatrixHolder,
            final float[] scratchFinal,
            final boolean destRequired) {
        final Object[] packet = state.packet;
        if (!selfReader.test(transform, packet)) {
            return null;
        }
        if (outStaticMatrixHolder != null) {
            outStaticMatrixHolder[0] = null;
        }
        Object parentEntity = packet[PACKET_PARENT_INDEX];
        if (parentEntity == null) {
            // Return the static matrix instance directly to match root ownership in host code.
            final Object staticMatrix = packet[PACKET_STATIC_MATRIX_INDEX];
            if (staticMatrix != null) {
                state.staticHits++;
                if (outStaticMatrixHolder != null) {
                    outStaticMatrixHolder[0] = staticMatrix;
                }
                final float[] data = (float[]) packet[PACKET_STATIC_DATA_INDEX];
                return data != null && data.length >= MATRIX_LENGTH ? data : null;
            }
            return resolveCachedLocalToParent(transform, state);
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
            return combineOrMultiply(left, current, scratchFinal, state, destRequired);
        }

        float[] acc = combineOrMultiply(left, current, state.scratchA, state, false);
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
                return combineOrMultiply(left, acc, scratchFinal, state, destRequired);
            }
            final float[] dest = acc == state.scratchA ? state.scratchB : state.scratchA;
            acc = combineOrMultiply(left, acc, dest, state, false);
            parentEntity = nextParent;
        }
        return null;
    }

    private static float[] combineOrMultiply(
            final float[] left,
            final float[] right,
            final float[] dest,
            final ThreadState state,
            final boolean destRequired) {
        if (left == IDENTITY_MATRIX_DATA && right == IDENTITY_MATRIX_DATA) {
            if (!destRequired) {
                return IDENTITY_MATRIX_DATA;
            }
            System.arraycopy(IDENTITY_MATRIX_DATA, 0, dest, 0, MATRIX_LENGTH);
            return dest;
        }
        if (left == IDENTITY_MATRIX_DATA && canSkipIdentityMultiply(right)) {
            // Avoid aliasing a PLRU cache slot into the accumulator across subsequent lookups.
            if (!destRequired
                    && (right == state.scratchA || right == state.scratchB || right == IDENTITY_MATRIX_DATA)) {
                return right;
            }
            System.arraycopy(right, 0, dest, 0, MATRIX_LENGTH);
            return dest;
        }
        if (right == IDENTITY_MATRIX_DATA && canSkipIdentityMultiply(left)) {
            if (!destRequired && (left == state.scratchA || left == state.scratchB || left == IDENTITY_MATRIX_DATA)) {
                return left;
            }
            System.arraycopy(left, 0, dest, 0, MATRIX_LENGTH);
            return dest;
        }
        multiplyGeneral(left, right, dest);
        return dest;
    }

    private static boolean canSkipIdentityMultiply(final float[] other) {
        for (int i = 0; i < MATRIX_LENGTH; i++) {
            final int bits = Float.floatToRawIntBits(other[i]);
            // Signed negative zero flips to +0.0f on addition, and 0 * Inf/NaN yields NaN.
            if (bits == NEGATIVE_ZERO_BITS || (bits & EXPONENT_MASK) == EXPONENT_MASK) {
                return false;
            }
        }
        return true;
    }

    private boolean ensureScratchMatrix(final ThreadState state, final ScratchMatrix slot) {
        if (slot.data != null) {
            return true;
        }
        matrixAllocator.accept(state.matrixOutHolder);
        final float[] data = (float[]) state.matrixOutHolder[HOLDER_ARRAY_INDEX];
        final Object instance = state.matrixOutHolder[HOLDER_MATRIX_INDEX];
        if (data == null || data.length < MATRIX_LENGTH || instance == null) {
            return false;
        }
        slot.data = data;
        slot.instance = instance;
        return true;
    }

    private static float transformX(final float[] m, final float x, final float y, final float z) {
        return ((m[MATRIX_IDX_X0] * x + m[MATRIX_IDX_X1] * y) + m[MATRIX_IDX_X2] * z) + m[MATRIX_IDX_TX];
    }

    private static float transformY(final float[] m, final float x, final float y, final float z) {
        return ((m[MATRIX_IDX_Y0] * x + m[MATRIX_IDX_Y1] * y) + m[MATRIX_IDX_Y2] * z) + m[MATRIX_IDX_TY];
    }

    private static float transformZ(final float[] m, final float x, final float y, final float z) {
        return ((m[MATRIX_IDX_Z0] * x + m[MATRIX_IDX_Z1] * y) + m[MATRIX_IDX_Z2] * z) + m[MATRIX_IDX_TZ];
    }

    private static long encodeValidFloat(final float value) {
        return SORTING_VALID_BIT | (Float.floatToRawIntBits(value) & FLOAT_BITS_MASK);
    }

    private static RenderAccessors resolveRenderAccessors(
            final ClassLoader loader,
            final Class<?> entityType,
            final Class<?> transformType,
            final Class<?> matrixType,
            final MethodHandle entityGetTransform) {
        try {
            final Class<?> meshRendererType = Class.forName(RenderMatrixTransformer.MESH_RENDERER_DOT, false, loader);
            final Class<?> containerType = Class.forName(MESH_CONTAINER_OWNER.replace('/', '.'), false, loader);
            final Class<?> meshType = Class.forName(MESH_OWNER.replace('/', '.'), false, loader);
            final Class<?> cameraType = Class.forName(CAMERA_OWNER.replace('/', '.'), false, loader);
            final Class<?> componentsType = tryLoadOptionalClass(loader, COMPONENTS_OWNER.replace('/', '.'));

            final Object compiled = compileRenderAccessor(
                    loader,
                    meshRendererType,
                    containerType,
                    meshType,
                    entityType,
                    componentsType,
                    transformType,
                    cameraType,
                    matrixType);
            if (compiled != null) {
                @SuppressWarnings("unchecked")
                final var rendererProbe = (BiPredicate<Object, Object>) compiled;
                @SuppressWarnings("unchecked")
                final var matrixDataReader = (Function<Object, Object>) compiled;
                @SuppressWarnings("unchecked")
                final var cameraOrInvert = (BiFunction<Object, Object, Object>) compiled;
                return new RenderAccessors(
                        meshRendererType, cameraType, true, rendererProbe, matrixDataReader, cameraOrInvert);
            }

            final MethodType objectUnary = MethodType.methodType(Object.class, Object.class);
            final MethodType floatArrayUnary = MethodType.methodType(float[].class, Object.class);
            final MethodType intUnary = MethodType.methodType(int.class, Object.class);
            final MethodType objectBinary = MethodType.methodType(Object.class, Object.class, Object.class);

            final FallbackRenderAccessors fallback = new FallbackRenderAccessors(
                    HostHandles.virtual(
                            meshRendererType, GET_MESH_CONTAINER_METHOD, GET_MESH_CONTAINER_DESCRIPTOR, objectUnary),
                    HostHandles.virtual(containerType, GET_MESH_METHOD, GET_MESH_DESCRIPTOR, objectUnary),
                    HostHandles.virtual(meshType, GET_POSITIONS_METHOD, FLOAT_ARRAY_GETTER_DESCRIPTOR, floatArrayUnary),
                    HostHandles.virtual(meshType, GET_STEP_METHOD, INT_GETTER_DESCRIPTOR, intUnary),
                    HostHandles.virtual(meshRendererType, GET_ENTITY_METHOD, GET_ENTITY_DESCRIPTOR, objectUnary),
                    entityGetTransform,
                    HostHandles.virtual(cameraType, GET_TRANSFORM_METHOD, GET_TRANSFORM_DESCRIPTOR, objectUnary),
                    HostHandles.virtual(matrixType, MATRIX_DATA_METHOD, MATRIX_DATA_DESCRIPTOR, floatArrayUnary),
                    HostHandles.virtual(matrixType, MATRIX_INVERT_METHOD, MATRIX_INVERT_DESCRIPTOR, objectBinary));
            return new RenderAccessors(
                    meshRendererType, cameraType, false, fallback, fallback, fallback::applyCameraOrInvert);
        } catch (final ReflectiveOperationException optionalMissing) {
            return null;
        }
    }

    private static Class<?> tryLoadOptionalClass(final ClassLoader loader, final String binaryName) {
        try {
            return Class.forName(binaryName, false, loader);
        } catch (final ClassNotFoundException missing) {
            return null;
        }
    }

    private static Object compileRenderAccessor(
            final ClassLoader loader,
            final Class<?> meshRendererType,
            final Class<?> containerType,
            final Class<?> meshType,
            final Class<?> entityType,
            final Class<?> componentsType,
            final Class<?> transformType,
            final Class<?> cameraType,
            final Class<?> matrixType) {
        if (componentsType == null
                || !Modifier.isPublic(meshRendererType.getModifiers())
                || !Modifier.isPublic(containerType.getModifiers())
                || !Modifier.isPublic(meshType.getModifiers())
                || !Modifier.isPublic(entityType.getModifiers())
                || !Modifier.isPublic(componentsType.getModifiers())
                || !Modifier.isFinal(componentsType.getModifiers())
                || !Modifier.isPublic(transformType.getModifiers())
                || !Modifier.isPublic(cameraType.getModifiers())
                || !Modifier.isPublic(matrixType.getModifiers())) {
            return null;
        }
        if (!hasPublicMethod(meshRendererType, GET_MESH_CONTAINER_METHOD, containerType)
                || !hasPublicMethod(meshRendererType, GET_ENTITY_METHOD, entityType)
                || !hasPublicMethod(entityType, GET_COMPONENTS_METHOD, componentsType)
                || !hasPublicMethod(componentsType, COMPONENTS_LIST_METHOD, List.class)
                || !hasPublicMethod(containerType, GET_MESH_METHOD, meshType)
                || !hasPublicMethod(meshType, GET_POSITIONS_METHOD, float[].class)
                || !hasPublicMethod(meshType, GET_STEP_METHOD, int.class)
                || !hasPublicMethod(entityType, GET_TRANSFORM_METHOD, transformType)
                || !hasPublicMethod(cameraType, GET_TRANSFORM_METHOD, transformType)
                || !hasPublicMethod(matrixType, MATRIX_DATA_METHOD, float[].class)
                || !hasPublicMethod(matrixType, MATRIX_INVERT_METHOD, matrixType, matrixType)) {
            return null;
        }
        try {
            final AccessorClassLoader accessorLoader = new AccessorClassLoader(loader);
            final Class<?> accessorClass = accessorLoader.define(
                    SORTING_ACCESSOR_INTERNAL.replace('/', '.'), buildSortingAccessorClass(loader));
            return accessorClass.getConstructor().newInstance();
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static boolean hasPublicMethod(
            final Class<?> owner, final String name, final Class<?> returnType, final Class<?>... parameterTypes) {
        for (final Method method : owner.getMethods()) {
            if (name.equals(method.getName())
                    && Modifier.isPublic(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == returnType
                    && Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] buildSortingAccessorClass(final ClassLoader loader) {
        final ClassWriter writer = HostClassWriters.newFrameWriter(loader);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                SORTING_ACCESSOR_INTERNAL,
                null,
                OBJECT_INTERNAL,
                new String[] {BI_PREDICATE_INTERNAL, FUNCTION_INTERNAL, BI_FUNCTION_INTERNAL});

        final MethodVisitor init =
                writer.visitMethod(Opcodes.ACC_PUBLIC, CONSTRUCTOR_NAME, VOID_DESCRIPTOR, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, OBJECT_INTERNAL, CONSTRUCTOR_NAME, VOID_DESCRIPTOR, false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        emitSortingTestMethod(writer);
        emitSortingMatrixDataMethod(writer);
        emitSortingCameraOrInvertMethod(writer);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitSortingTestMethod(final ClassWriter writer) {
        final int argRenderer = 1;
        final int argPacket = 2;
        final int localRenderer = 3;
        final int localPacket = 4;
        final int localFlags = 5;
        final int localEntity = 6;
        final int localComponents = 7;
        final int localCount = 8;
        final int localIndex = 9;
        final int localMesh = 10;
        final int localPositions = 11;
        final int localTransform = 12;

        final MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, TEST_METHOD, TEST_DESCRIPTOR, null, null);
        mv.visitCode();

        final Label loopHead = new Label();
        final Label nonRandomAccess = new Label();
        final Label foundContainer = new Label();
        final Label containerReady = new Label();
        final Label popZero = new Label();
        final Label returnZero = new Label();
        final Label popFallback = new Label();

        mv.visitVarInsn(Opcodes.ALOAD, argRenderer);
        mv.visitTypeInsn(Opcodes.CHECKCAST, RenderMatrixTransformer.MESH_RENDERER_OWNER);
        mv.visitVarInsn(Opcodes.ASTORE, localRenderer);

        mv.visitVarInsn(Opcodes.ALOAD, argPacket);
        mv.visitTypeInsn(Opcodes.CHECKCAST, OBJECT_ARRAY_DESCRIPTOR);
        mv.visitVarInsn(Opcodes.ASTORE, localPacket);

        mv.visitVarInsn(Opcodes.ALOAD, localPacket);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitTypeInsn(Opcodes.CHECKCAST, INT_ARRAY_INTERNAL);
        mv.visitVarInsn(Opcodes.ASTORE, localFlags);

        mv.visitVarInsn(Opcodes.ALOAD, localRenderer);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                RenderMatrixTransformer.MESH_RENDERER_OWNER,
                GET_ENTITY_METHOD,
                GET_ENTITY_DESCRIPTOR,
                false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, popFallback);
        mv.visitVarInsn(Opcodes.ASTORE, localEntity);

        // Scan Components.a() by index on RandomAccess lists so GMeshContainer lookup allocates no Iterator.
        mv.visitVarInsn(Opcodes.ALOAD, localEntity);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, ENTITY_OWNER, GET_COMPONENTS_METHOD, GET_COMPONENTS_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, popFallback);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, COMPONENTS_OWNER, COMPONENTS_LIST_METHOD, COMPONENTS_LIST_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, popFallback);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, RANDOM_ACCESS_INTERNAL);
        mv.visitJumpInsn(Opcodes.IFEQ, nonRandomAccess);

        mv.visitVarInsn(Opcodes.ASTORE, localComponents);
        mv.visitVarInsn(Opcodes.ALOAD, localComponents);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_INTERNAL, LIST_SIZE_METHOD, LIST_SIZE_DESCRIPTOR, true);
        mv.visitVarInsn(Opcodes.ISTORE, localCount);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, localIndex);

        mv.visitLabel(loopHead);
        mv.visitVarInsn(Opcodes.ILOAD, localIndex);
        mv.visitVarInsn(Opcodes.ILOAD, localCount);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, returnZero);
        mv.visitVarInsn(Opcodes.ALOAD, localComponents);
        mv.visitVarInsn(Opcodes.ILOAD, localIndex);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST_INTERNAL, LIST_GET_METHOD, LIST_GET_DESCRIPTOR, true);
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, MESH_CONTAINER_OWNER);
        mv.visitJumpInsn(Opcodes.IFNE, foundContainer);
        mv.visitInsn(Opcodes.POP);
        mv.visitIincInsn(localIndex, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loopHead);

        mv.visitLabel(nonRandomAccess);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, localRenderer);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                RenderMatrixTransformer.MESH_RENDERER_OWNER,
                GET_MESH_CONTAINER_METHOD,
                GET_MESH_CONTAINER_DESCRIPTOR,
                false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, popZero);
        mv.visitJumpInsn(Opcodes.GOTO, containerReady);

        mv.visitLabel(foundContainer);
        mv.visitTypeInsn(Opcodes.CHECKCAST, MESH_CONTAINER_OWNER);

        mv.visitLabel(containerReady);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MESH_CONTAINER_OWNER, GET_MESH_METHOD, GET_MESH_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, popZero);
        mv.visitVarInsn(Opcodes.ASTORE, localMesh);

        mv.visitVarInsn(Opcodes.ALOAD, localMesh);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, MESH_OWNER, GET_POSITIONS_METHOD, FLOAT_ARRAY_GETTER_DESCRIPTOR, false);
        mv.visitVarInsn(Opcodes.ASTORE, localPositions);
        mv.visitVarInsn(Opcodes.ALOAD, localPositions);
        mv.visitJumpInsn(Opcodes.IFNULL, returnZero);
        mv.visitVarInsn(Opcodes.ALOAD, localPositions);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IFEQ, returnZero);

        mv.visitVarInsn(Opcodes.ALOAD, localPacket);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ALOAD, localPositions);
        mv.visitInsn(Opcodes.AASTORE);

        mv.visitVarInsn(Opcodes.ALOAD, localFlags);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, localMesh);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MESH_OWNER, GET_STEP_METHOD, INT_GETTER_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.IASTORE);

        mv.visitVarInsn(Opcodes.ALOAD, localEntity);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY_OWNER, GET_TRANSFORM_METHOD, GET_TRANSFORM_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, popFallback);
        mv.visitVarInsn(Opcodes.ASTORE, localTransform);

        mv.visitVarInsn(Opcodes.ALOAD, localPacket);
        mv.visitInsn(Opcodes.ICONST_2);
        mv.visitVarInsn(Opcodes.ALOAD, localTransform);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(popZero);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(returnZero);
        mv.visitVarInsn(Opcodes.ALOAD, localFlags);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IASTORE);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(popFallback);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, localFlags);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IASTORE);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitSortingMatrixDataMethod(final ClassWriter writer) {
        final MethodVisitor mv =
                writer.visitMethod(Opcodes.ACC_PUBLIC, FUNCTION_APPLY_METHOD, FUNCTION_APPLY_DESCRIPTOR, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, MatrixScratchTransformer.MATRIX);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                MatrixScratchTransformer.MATRIX,
                MATRIX_DATA_METHOD,
                MATRIX_DATA_DESCRIPTOR,
                false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitSortingCameraOrInvertMethod(final ClassWriter writer) {
        final MethodVisitor mv =
                writer.visitMethod(Opcodes.ACC_PUBLIC, FUNCTION_APPLY_METHOD, BI_FUNCTION_APPLY_DESCRIPTOR, null, null);
        mv.visitCode();
        final Label invertMatrix = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNONNULL, invertMatrix);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, CAMERA_OWNER);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CAMERA_OWNER, GET_TRANSFORM_METHOD, GET_TRANSFORM_DESCRIPTOR, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(invertMatrix);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, MatrixScratchTransformer.MATRIX);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitTypeInsn(Opcodes.CHECKCAST, MatrixScratchTransformer.MATRIX);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                MatrixScratchTransformer.MATRIX,
                MATRIX_INVERT_METHOD,
                MATRIX_INVERT_DESCRIPTOR,
                false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
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

        // Short-circuit exact identity transforms before PLRU cache lookup; -0.0f translates to +0.0f.
        if (((pxBits | pyBits | pzBits | exBits | eyBits | ezBits) & ABS_INT_MASK) == 0
                && sxBits == FLOAT_ONE_BITS
                && syBits == FLOAT_ONE_BITS
                && szBits == FLOAT_ONE_BITS) {
            state.identityHits++;
            return IDENTITY_MATRIX_DATA;
        }

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
        return (hash ^ (hash >>> HASH_SHIFT)) * FIBONACCI_HASH_MULTIPLIER;
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
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, BOOT_HANDLE_FIELD, VAR_HANDLE_DESCRIPTOR, null, null)
                .visitEnd();
        final MethodVisitor init =
                writer.visitMethod(Opcodes.ACC_PUBLIC, CONSTRUCTOR_NAME, VOID_DESCRIPTOR, null, null);
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

        final MethodVisitor init =
                writer.visitMethod(Opcodes.ACC_PUBLIC, CONSTRUCTOR_NAME, VOID_DESCRIPTOR, null, null);
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
        final MethodVisitor mv =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "accept", "(L" + OBJECT_INTERNAL + ";)V", null, null);
        mv.visitCode();

        // Always allocate an unshared GMatrix44 so callers mutating .a() cannot alias cached entries.
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

    private record FallbackRenderAccessors(
            MethodHandle getMeshContainer,
            MethodHandle getMesh,
            MethodHandle getPositions,
            MethodHandle getStep,
            MethodHandle rendererGetEntity,
            MethodHandle entityGetTransform,
            MethodHandle cameraGetTransform,
            MethodHandle matrixDataReader,
            MethodHandle matrixInvertInto)
            implements BiPredicate<Object, Object>, Function<Object, Object> {

        @Override
        public boolean test(final Object renderer, final Object packetObj) {
            final Object[] packet = (Object[]) packetObj;
            final int[] flags = (int[]) packet[SORTING_PACKET_FLAGS_INDEX];
            try {
                final Object container = (Object) getMeshContainer.invokeExact(renderer);
                if (container == null) {
                    flags[FLAG_ARRAY_INDEX] = SORTING_STATUS_ZERO;
                    return false;
                }
                final Object mesh = (Object) getMesh.invokeExact(container);
                if (mesh == null) {
                    flags[FLAG_ARRAY_INDEX] = SORTING_STATUS_ZERO;
                    return false;
                }
                final float[] positions = (float[]) getPositions.invokeExact(mesh);
                if (positions == null || positions.length == 0) {
                    flags[FLAG_ARRAY_INDEX] = SORTING_STATUS_ZERO;
                    return false;
                }
                packet[SORTING_PACKET_POSITIONS_INDEX] = positions;
                flags[FLAG_ARRAY_INDEX] = (int) getStep.invokeExact(mesh);
                final Object entity = (Object) rendererGetEntity.invokeExact(renderer);
                if (entity == null) {
                    flags[FLAG_ARRAY_INDEX] = SORTING_STATUS_FALLBACK;
                    return false;
                }
                final Object transform = (Object) entityGetTransform.invokeExact(entity);
                if (transform == null) {
                    flags[FLAG_ARRAY_INDEX] = SORTING_STATUS_FALLBACK;
                    return false;
                }
                packet[SORTING_PACKET_TRANSFORM_INDEX] = transform;
                return true;
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw FallbackAccessors.wrapThrowable(failure);
            }
        }

        @Override
        public Object apply(final Object matrix) {
            try {
                return (float[]) matrixDataReader.invokeExact(matrix);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw FallbackAccessors.wrapThrowable(failure);
            }
        }

        private Object applyCameraOrInvert(final Object first, final Object second) {
            try {
                if (second == null) {
                    return (Object) cameraGetTransform.invokeExact(first);
                }
                return (Object) matrixInvertInto.invokeExact(first, second);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw FallbackAccessors.wrapThrowable(failure);
            }
        }
    }

    private record RenderAccessors(
            Class<?> meshRendererType,
            Class<?> cameraType,
            boolean compiled,
            BiPredicate<Object, Object> rendererProbe,
            Function<Object, Object> matrixDataReader,
            BiFunction<Object, Object, Object> cameraOrInvert) {}

    private static final class ScratchMatrix {
        Object instance;
        float[] data;
    }

    private static final class ThreadState {
        final Thread owner = Thread.currentThread();
        long calls;
        long cacheHits;
        long identityHits;
        long cacheMisses;
        long staticHits;
        long failures;
        final int[] bits = new int[VECTOR_BITS_COUNT];
        final int[] sortingFlags = new int[1];
        final Object[] packet = new Object[PACKET_SIZE];
        final Object[] sortingPacket = new Object[SORTING_PACKET_SIZE];
        final Object[] matrixOutHolder = new Object[HOLDER_SIZE];
        final Object[] staticHolder = new Object[1];
        final float[] scratchA = new float[MATRIX_LENGTH];
        final float[] scratchB = new float[MATRIX_LENGTH];
        final float[] scratchC = new float[MATRIX_LENGTH];
        final ScratchMatrix renderScratch = new ScratchMatrix();
        final ScratchMatrix cameraWorldScratch = new ScratchMatrix();
        final ScratchMatrix cameraInverseScratch = new ScratchMatrix();
        final LocalCache cache = new LocalCache();

        ThreadState() {
            packet[PACKET_BITS_INDEX] = bits;
            sortingPacket[SORTING_PACKET_FLAGS_INDEX] = sortingFlags;
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
     * Thread-confined four-way PLRU identity cache preventing 2-way conflict thrashing.
     */
    private static final class LocalCache {
        private final TransformSlot[] slots = new TransformSlot[CACHE_TOTAL_SLOTS];
        private final int[] hashes = new int[CACHE_TOTAL_SLOTS];
        private final byte[] plruBits = new byte[CACHE_SET_COUNT];

        TransformSlot findOrBind(final Object transform) {
            final int rawHash = System.identityHashCode(transform);
            final int setIndex = mixHash(rawHash) & CACHE_SET_MASK;
            final int base = setIndex << CACHE_WAY_SHIFT;

            for (int way = 0; way < CACHE_WAY_COUNT; way++) {
                final int index = base + way;
                if (hashes[index] == rawHash) {
                    final TransformSlot slot = slots[index];
                    if (slot != null && slot.refersTo(transform)) {
                        markUsed(setIndex, way);
                        return slot;
                    }
                }
            }
            for (int way = 0; way < CACHE_WAY_COUNT; way++) {
                final int index = base + way;
                final TransformSlot slot = slots[index];
                if (slot == null || slot.refersTo(null)) {
                    return bindSlot(index, setIndex, way, rawHash, transform, slot);
                }
            }
            final int victimWay = selectVictimWay(setIndex);
            final int victimIndex = base + victimWay;
            return bindSlot(victimIndex, setIndex, victimWay, rawHash, transform, slots[victimIndex]);
        }

        private void markUsed(final int setIndex, final int way) {
            final int bits = plruBits[setIndex] & PLRU_BYTE_MASK;
            final int next =
                    switch (way) {
                        case WAY_0 -> bits | PLRU_SET_WAY0;
                        case WAY_1 -> (bits | PLRU_SET_WAY1) & PLRU_CLEAR_WAY1;
                        case WAY_2 -> (bits & PLRU_CLEAR_WAY2) | PLRU_SET_WAY2;
                        default -> bits & PLRU_CLEAR_WAY3;
                    };
            plruBits[setIndex] = (byte) next;
        }

        private int selectVictimWay(final int setIndex) {
            final int bits = plruBits[setIndex] & PLRU_BYTE_MASK;
            if ((bits & PLRU_RIGHT_MASK) == 0) {
                return (bits & PLRU_LEFT_SUB_MASK) == 0 ? WAY_0 : WAY_1;
            }
            return (bits & PLRU_RIGHT_SUB_MASK) == 0 ? WAY_2 : WAY_3;
        }

        private TransformSlot bindSlot(
                final int slotIndex,
                final int setIndex,
                final int way,
                final int rawHash,
                final Object transform,
                final TransformSlot previous) {
            final float[] matrix = previous != null ? previous.matrix : new float[MATRIX_LENGTH];
            final TransformSlot bound = new TransformSlot(transform, matrix);
            slots[slotIndex] = bound;
            hashes[slotIndex] = rawHash;
            markUsed(setIndex, way);
            return bound;
        }
    }
}
