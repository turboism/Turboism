package dev.turboism.adapter.cubism.optimization.camera;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Caches {@code GCamera.getWorldToCameraMatrix()} results across bounded ancestor hierarchies whose
 * position, Euler angles, and scale bits remain identical.
 */
public final class CameraMatrixCacheBridge implements AutoCloseable {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.cameraMatrixCache";

    /** Property key for the {@code Function<Object, Object>} cache lookup callback. */
    public static final String GET_PROPERTY = "turboism.camera-matrix.get";

    /** Property key for the {@code BiFunction<Object, Object, Object>} cache store callback. */
    public static final String PUT_PROPERTY = "turboism.camera-matrix.put";

    /** Property key exposing diagnostic counters. */
    public static final String STATS_PROPERTY = "turboism.camera-matrix.stats";

    private static final int CACHE_CAPACITY = 8;
    private static final int MAX_ANCESTOR_DEPTH = 4;
    private static final int LEVEL_STRIDE = 10;
    private static final int STATE_LENGTH = MAX_ANCESTOR_DEPTH * LEVEL_STRIDE;

    private final MethodHandle cameraTransformHandle;
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

    private final AtomicBoolean active = new AtomicBoolean();
    private final WeakReference<?>[] cameraRefs = new WeakReference<?>[CACHE_CAPACITY];
    private final int[] stateBits = new int[CACHE_CAPACITY * STATE_LENGTH];
    private final Object[] cachedMatrices = new Object[CACHE_CAPACITY];
    private final ThreadLocal<int[]> scratchBits = ThreadLocal.withInitial(() -> new int[STATE_LENGTH]);

    private final Function<Object, Object> getCallback = this::lookup;
    private final BiFunction<Object, Object, Object> putCallback = this::store;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    private Properties installedProperties;
    private long lookups;
    private long hits;
    private long misses;
    private long observerFailures;

    /**
     * Resolves exact-typed method handles on the admitted host class loader.
     *
     * @param loader admitted host class loader
     * @throws ReflectiveOperationException when any reviewed member is unavailable
     */
    public CameraMatrixCacheBridge(final ClassLoader loader) throws ReflectiveOperationException {
        final Class<?> cameraType = HostHandles.load(loader, CameraMatrixCacheTarget.CAMERA_OWNER);
        final Class<?> transformType = HostHandles.load(loader, CameraMatrixCacheTarget.TRANSFORM_OWNER);
        final Class<?> entityType = HostHandles.load(loader, CameraMatrixCacheTarget.ENTITY_OWNER);
        final Class<?> vectorType = HostHandles.load(loader, CameraMatrixCacheTarget.VECTOR3_OWNER);
        final MethodType objectUnary = MethodType.methodType(Object.class, Object.class);
        final MethodType floatUnary = MethodType.methodType(float.class, Object.class);

        cameraTransformHandle = HostHandles.virtual(
                cameraType,
                CameraMatrixCacheTarget.GET_TRANSFORM_METHOD,
                CameraMatrixCacheTarget.GET_TRANSFORM_DESCRIPTOR,
                objectUnary);
        transformEntityHandle = HostHandles.virtual(
                transformType,
                CameraMatrixCacheTarget.GET_ENTITY_METHOD,
                CameraMatrixCacheTarget.GET_ENTITY_DESCRIPTOR,
                objectUnary);
        entityParentHandle = HostHandles.virtual(
                entityType,
                CameraMatrixCacheTarget.GET_PARENT_ENTITY_METHOD,
                CameraMatrixCacheTarget.GET_PARENT_ENTITY_DESCRIPTOR,
                objectUnary);
        entityTransformHandle = HostHandles.virtual(
                entityType,
                CameraMatrixCacheTarget.GET_TRANSFORM_METHOD,
                CameraMatrixCacheTarget.GET_TRANSFORM_DESCRIPTOR,
                objectUnary);
        staticMatrixHandle = HostHandles.getter(
                transformType,
                CameraMatrixCacheTarget.STATIC_MATRIX_FIELD,
                CameraMatrixCacheTarget.MATRIX_FIELD_DESCRIPTOR,
                objectUnary);
        positionHandle = HostHandles.virtual(
                transformType,
                CameraMatrixCacheTarget.GET_POSITION_METHOD,
                CameraMatrixCacheTarget.VECTOR3_GETTER_DESCRIPTOR,
                objectUnary);
        eulerAnglesHandle = HostHandles.virtual(
                transformType,
                CameraMatrixCacheTarget.GET_EULER_ANGLES_METHOD,
                CameraMatrixCacheTarget.VECTOR3_GETTER_DESCRIPTOR,
                objectUnary);
        scaleHandle = HostHandles.virtual(
                transformType,
                CameraMatrixCacheTarget.GET_SCALE_METHOD,
                CameraMatrixCacheTarget.VECTOR3_GETTER_DESCRIPTOR,
                objectUnary);
        vecXHandle = HostHandles.virtual(
                vectorType,
                CameraMatrixCacheTarget.GET_X_METHOD,
                CameraMatrixCacheTarget.FLOAT_GETTER_DESCRIPTOR,
                floatUnary);
        vecYHandle = HostHandles.virtual(
                vectorType,
                CameraMatrixCacheTarget.GET_Y_METHOD,
                CameraMatrixCacheTarget.FLOAT_GETTER_DESCRIPTOR,
                floatUnary);
        vecZHandle = HostHandles.virtual(
                vectorType,
                CameraMatrixCacheTarget.GET_Z_METHOD,
                CameraMatrixCacheTarget.FLOAT_GETTER_DESCRIPTOR,
                floatUnary);
    }

    /** Publishes the camera-matrix lookup and store callbacks into {@link System#getProperties()}. */
    public synchronized void install() {
        if (active.get()) {
            throw new IllegalStateException("camera matrix cache already installed");
        }
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(GET_PROPERTY)
                    || properties.containsKey(PUT_PROPERTY)
                    || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("camera matrix cache slots occupied");
            }
            properties.put(GET_PROPERTY, getCallback);
            properties.put(PUT_PROPERTY, putCallback);
            properties.put(STATS_PROPERTY, statistics);
            installedProperties = properties;
            clearEntries();
            active.set(true);
        }
    }

    @Override
    public synchronized void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        clearEntries();
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                properties.remove(GET_PROPERTY, getCallback);
                properties.remove(PUT_PROPERTY, putCallback);
                properties.remove(STATS_PROPERTY, statistics);
            }
        }
    }

    /** Returns an immutable snapshot of cache counters. */
    public Map<String, Long> snapshot() {
        return Map.of(
                "lookups", lookups,
                "hits", hits,
                "misses", misses,
                "observerFailures", observerFailures);
    }

    private void clearEntries() {
        Arrays.fill(cameraRefs, null);
        Arrays.fill(cachedMatrices, null);
        Arrays.fill(stateBits, 0);
    }

    private Object lookup(final Object camera) {
        lookups++;
        if (!active.get() || camera == null) {
            misses++;
            return null;
        }
        try {
            final int[] bits = scratchBits.get();
            if (!readCameraHierarchyState(camera, bits)) {
                misses++;
                return null;
            }
            synchronized (this) {
                for (int slot = 0; slot < CACHE_CAPACITY; slot++) {
                    final WeakReference<?> ref = cameraRefs[slot];
                    if (ref != null && ref.get() == camera && matchesBits(slot * STATE_LENGTH, bits)) {
                        final Object matrix = cachedMatrices[slot];
                        if (matrix != null) {
                            hits++;
                            return matrix;
                        }
                    }
                }
            }
            misses++;
            return null;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
            misses++;
            return null;
        }
    }

    private Object store(final Object camera, final Object matrix) {
        if (!active.get() || camera == null || matrix == null) {
            return matrix;
        }
        try {
            final int[] bits = scratchBits.get();
            if (!readCameraHierarchyState(camera, bits)) {
                return matrix;
            }
            synchronized (this) {
                int targetSlot = 0;
                for (int slot = 0; slot < CACHE_CAPACITY; slot++) {
                    final WeakReference<?> ref = cameraRefs[slot];
                    if (ref == null || ref.get() == null || ref.get() == camera) {
                        targetSlot = slot;
                        break;
                    }
                }
                System.arraycopy(bits, 0, stateBits, targetSlot * STATE_LENGTH, STATE_LENGTH);
                cachedMatrices[targetSlot] = matrix;
                cameraRefs[targetSlot] = new WeakReference<>(camera);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailures++;
        }
        return matrix;
    }

    private boolean matchesBits(final int offset, final int[] candidate) {
        for (int i = 0; i < STATE_LENGTH; i++) {
            if (stateBits[offset + i] != candidate[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean readCameraHierarchyState(final Object camera, final int[] outBits) throws Throwable {
        Object transform = (Object) cameraTransformHandle.invokeExact(camera);
        if (transform == null) {
            return false;
        }
        Object entity = (Object) transformEntityHandle.invokeExact(transform);
        if (entity == null) {
            return false;
        }
        Arrays.fill(outBits, 0);
        for (int depth = 0; depth < MAX_ANCESTOR_DEPTH; depth++) {
            if ((Object) staticMatrixHandle.invokeExact(transform) != null) {
                return false;
            }
            if (!writeTransformState(entity, transform, outBits, depth * LEVEL_STRIDE)) {
                return false;
            }
            entity = (Object) entityParentHandle.invokeExact(entity);
            if (entity == null) {
                return true;
            }
            transform = (Object) entityTransformHandle.invokeExact(entity);
            if (transform == null) {
                return false;
            }
        }
        return false;
    }

    private boolean writeTransformState(
            final Object entity, final Object transform, final int[] outBits, final int base)
            throws Throwable {
        final Object position = (Object) positionHandle.invokeExact(transform);
        final Object euler = (Object) eulerAnglesHandle.invokeExact(transform);
        final Object scale = (Object) scaleHandle.invokeExact(transform);
        if (position == null || euler == null || scale == null) {
            return false;
        }
        outBits[base] = System.identityHashCode(entity);
        outBits[base + 1] = Float.floatToRawIntBits((float) vecXHandle.invokeExact(position));
        outBits[base + 2] = Float.floatToRawIntBits((float) vecYHandle.invokeExact(position));
        outBits[base + 3] = Float.floatToRawIntBits((float) vecZHandle.invokeExact(position));
        outBits[base + 4] = Float.floatToRawIntBits((float) vecXHandle.invokeExact(euler));
        outBits[base + 5] = Float.floatToRawIntBits((float) vecYHandle.invokeExact(euler));
        outBits[base + 6] = Float.floatToRawIntBits((float) vecZHandle.invokeExact(euler));
        outBits[base + 7] = Float.floatToRawIntBits((float) vecXHandle.invokeExact(scale));
        outBits[base + 8] = Float.floatToRawIntBits((float) vecYHandle.invokeExact(scale));
        outBits[base + 9] = Float.floatToRawIntBits((float) vecZHandle.invokeExact(scale));
        return true;
    }
}
