package dev.turboism.adapter.cubism.shader;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Internal entrypoint for the verified shader uniform-location reuse hook. Host code calls
 * {@link #lookup} instead of a repeated {@code glGetUniformLocation} on every draw; the official
 * query stays the fallback for every ineligible or disabled call. The bridge holds no host type
 * dependency: GL, context and shader members are reached reflectively and cached per class.
 */
public final class NativeShaderUniformBridge {
    private static volatile boolean enabled;
    private static volatile boolean patched;
    private static final WeakHashMap<Object, State> CACHE = new WeakHashMap<>();
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong QUERIES = new AtomicLong();
    private static final AtomicLong INVALIDATIONS = new AtomicLong();
    private static final AtomicLong MATRIX_HITS = new AtomicLong();
    private static final AtomicLong MATRIX_UPLOADS = new AtomicLong();
    static final int MAX_SHADERS = 1024;
    static final int MAX_NAMES = 4096;

    /** Matrix uniform slot identifier for camera {@code projectionMatrix}. */
    public static final int MATRIX_KIND_PROJECTION = 0;

    /** Matrix uniform slot identifier for camera {@code worldToCameraMatrix}. */
    public static final int MATRIX_KIND_WORLD_TO_CAMERA = 1;

    private static final int MATRIX_FLOAT_COUNT = 16;
    private static final int UNKNOWN_LOCATION = Integer.MIN_VALUE;

    private static final int INITIAL_ENTRY_CAPACITY = 16;
    private static final int REENTRANT_POOL_LIMIT = 4;
    private static final Map.Entry<?, ?>[] EMPTY_ENTRIES = new Map.Entry<?, ?>[0];
    private static final ThreadLocal<EntryPool> ENTRY_POOL = ThreadLocal.withInitial(EntryPool::new);
    private static final ThreadLocal<ContextStamp> CONTEXT_SCRATCH = ThreadLocal.withInitial(ContextStamp::new);

    private static final String METHOD_GL_GET_UNIFORM_LOCATION = "glGetUniformLocation";
    private static final String METHOD_GET_CONTEXT = "getContext";
    private static final String METHOD_GET_HANDLE = "getHandle";
    private static final String METHOD_IS_CREATED = "isCreated";
    private static final String METHOD_GET_CURRENT = "getCurrent";
    private static final String METHOD_GET_GL_PROGRAM_ID = "getGlProgramID";
    private static final String METHOD_GET_UNIFORM_MAP = "getUniformMap";

    private static final MethodType GL_QUERY_TYPE =
            MethodType.methodType(int.class, Object.class, int.class, String.class);
    private static final MethodType OBJ_UNARY_TYPE = MethodType.methodType(Object.class, Object.class);
    private static final MethodType LONG_UNARY_TYPE = MethodType.methodType(long.class, Object.class);
    private static final MethodType BOOL_UNARY_TYPE = MethodType.methodType(boolean.class, Object.class);
    private static final MethodType OBJ_ZERO_TYPE = MethodType.methodType(Object.class);
    private static final MethodType INT_UNARY_TYPE = MethodType.methodType(int.class, Object.class);

    private NativeShaderUniformBridge() {}

    /** Installer lifecycle switch. Disabling leaves transformed calls on the official path. */
    public static void enabled(boolean value) {
        enabled = value;
    }

    /**
     * Returns whether verified uniform-location reuse is active.
     *
     * @return {@code true} while an admitted installation owns the hook
     */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Returns whether the exact reviewed host shader definition was transformed.
     *
     * @return {@code true} once the transformer emitted the patched first definition
     */
    public static boolean patched() {
        return patched;
    }

    /**
     * @return total cache hits served without a native uniform query
     */
    public static long hits() {
        return HITS.get();
    }

    /**
     * @return total calls that reached the official {@code glGetUniformLocation}
     */
    public static long queries() {
        return QUERIES.get();
    }

    /**
     * @return total shader lifecycle invalidations observed
     */
    public static long invalidations() {
        return INVALIDATIONS.get();
    }

    /**
     * Returns total camera-matrix uploads skipped because the shader program already held matching
     * matrix coefficients.
     *
     * @return elided camera-matrix upload count
     */
    public static long matrixHits() {
        return MATRIX_HITS.get();
    }

    /**
     * Returns total camera-matrix uploads executed through {@code glUniformMatrix4fv}.
     *
     * @return performed camera-matrix upload count
     */
    public static long matrixUploads() {
        return MATRIX_UPLOADS.get();
    }

    static void patched(boolean value) {
        patched = value;
    }

    /**
     * Copies map entries into a thread-local scratch array without allocating iterators.
     *
     * <p>Callers must pair each non-empty result with {@link #releaseMapEntries(Map.Entry[])} to
     * clear retained entry references and return the buffer to the current thread pool.</p>
     *
     * @param map host hash map to snapshot
     * @return a null-terminated scratch entry array, or an empty array when {@code map} is empty
     */
    public static Map.Entry<?, ?>[] borrowMapEntries(final HashMap<?, ?> map) {
        if (map == null || map.isEmpty()) {
            return EMPTY_ENTRIES;
        }
        return ENTRY_POOL.get().borrow(map);
    }

    /**
     * Returns a previously borrowed entry buffer to the current thread pool and clears references.
     *
     * @param entries scratch entry array previously returned by {@link #borrowMapEntries(HashMap)}
     */
    public static void releaseMapEntries(final Map.Entry<?, ?>[] entries) {
        if (entries == null || entries.length == 0) {
            return;
        }
        ENTRY_POOL.get().release(entries);
    }

    /**
     * Resolves a uniform location, reusing the recorded location while the same shader, GL
     * context identity and handle, and program stay current. Every ineligible call (disabled,
     * missing or empty host uniform table, mismatched program, inactive or non-current context)
     * delegates to the official query; the original exception is preserved exactly.
     *
     * @param gl host GL implementation object
     * @param program host program identifier
     * @param name uniform name as passed by the host draw path
     * @param shader host shader instance holding the uniform table
     * @return the uniform location
     */
    public static int lookup(Object gl, int program, String name, Object shader) {
        if (!enabled || program <= 0 || name == null) return original(gl, program, name);
        final ContextStamp stamp = verifyContextStamp(gl, program, shader);
        if (stamp == null) return original(gl, program, name);
        final Object context = stamp.context;
        final long handle = stamp.handle;
        stamp.clear();
        State state;
        synchronized (CACHE) {
            state = CACHE.get(shader);
            if (state == null || !state.same(context, handle, program)) {
                if (CACHE.size() >= MAX_SHADERS) CACHE.clear();
                state = new State(context, handle, program);
                CACHE.put(shader, state);
            }
            Integer location = state.locations.get(name);
            if (location != null) {
                HITS.incrementAndGet();
                return location;
            }
        }
        int location = original(gl, program, name);
        synchronized (CACHE) {
            if (CACHE.get(shader) == state && state.locations.size() < MAX_NAMES) state.locations.put(name, location);
        }
        return location;
    }

    /**
     * Reports whether a camera-invariant 4x4 matrix upload must run for the current shader program
     * and OpenGL context.
     *
     * <p>When the shader program on the current context already holds an identical 16-float matrix
     * at {@code location}, both {@code glUniformMatrix4fv} and its trailing {@code glGetError}
     * check can be skipped safely. Disabled or ineligible contexts fall back to returning
     * {@code true}.</p>
     *
     * @param gl host GL implementation object
     * @param program active host shader program identifier
     * @param shader host shader instance
     * @param kind {@link #MATRIX_KIND_PROJECTION} or {@link #MATRIX_KIND_WORLD_TO_CAMERA}
     * @param location resolved uniform location
     * @param values 16-element column-major matrix coefficients
     * @return {@code true} to execute the native upload, {@code false} to skip it
     */
    public static boolean shouldUploadCameraMatrix(
            final Object gl,
            final int program,
            final Object shader,
            final int kind,
            final int location,
            final float[] values) {
        if (!enabled
                || program <= 0
                || location < 0
                || values == null
                || values.length < MATRIX_FLOAT_COUNT
                || !validMatrixKind(kind)) {
            return true;
        }
        final ContextStamp stamp = verifyContextStampSafely(gl, program, shader);
        if (stamp == null) {
            return true;
        }
        final Object context = stamp.context;
        final long handle = stamp.handle;
        stamp.clear();
        synchronized (CACHE) {
            final State state = CACHE.get(shader);
            if (state != null && state.same(context, handle, program) && state.matchesMatrix(kind, location, values)) {
                MATRIX_HITS.incrementAndGet();
                return false;
            }
        }
        return true;
    }

    /**
     * Records a completed camera-matrix upload after both {@code glUniformMatrix4fv} and the host's
     * check return normally.
     *
     * @param gl host GL implementation object
     * @param program active host shader program identifier
     * @param shader host shader instance
     * @param kind {@link #MATRIX_KIND_PROJECTION} or {@link #MATRIX_KIND_WORLD_TO_CAMERA}
     * @param location resolved uniform location
     * @param values 16-element column-major matrix coefficients
     */
    public static void recordCameraMatrix(
            final Object gl,
            final int program,
            final Object shader,
            final int kind,
            final int location,
            final float[] values) {
        MATRIX_UPLOADS.incrementAndGet();
        if (!enabled
                || program <= 0
                || location < 0
                || values == null
                || values.length < MATRIX_FLOAT_COUNT
                || !validMatrixKind(kind)) {
            return;
        }
        final ContextStamp stamp = verifyContextStampSafely(gl, program, shader);
        if (stamp == null) {
            return;
        }
        final Object context = stamp.context;
        final long handle = stamp.handle;
        stamp.clear();
        synchronized (CACHE) {
            State state = CACHE.get(shader);
            if (state == null || !state.same(context, handle, program)) {
                if (CACHE.size() >= MAX_SHADERS) {
                    CACHE.clear();
                }
                state = new State(context, handle, program);
                CACHE.put(shader, state);
            }
            state.storeMatrix(kind, location, values);
        }
    }

    private static boolean validMatrixKind(final int kind) {
        return kind == MATRIX_KIND_PROJECTION || kind == MATRIX_KIND_WORLD_TO_CAMERA;
    }

    private static ContextStamp verifyContextStampSafely(final Object gl, final int program, final Object shader) {
        try {
            return verifyContextStamp(gl, program, shader);
        } catch (RuntimeException | Error ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return null;
        }
    }

    private static ContextStamp verifyContextStamp(final Object gl, final int program, final Object shader) {
        if (gl == null || shader == null) {
            return null;
        }
        try {
            final GlAccess glApi = GL.get(gl.getClass());
            final Object context = (Object) glApi.getContext().invokeExact(gl);
            if (context == null) {
                return null;
            }
            final ContextAccess ctxApi = CONTEXT.get(context.getClass());
            final long handle = (long) ctxApi.getHandle().invokeExact(context);
            final ShaderAccess shaderApi = SHADER.get(shader.getClass());
            final boolean eligible = handle != 0
                    && (boolean) ctxApi.isCreated().invokeExact(context)
                    && (Object) ctxApi.getCurrent().invokeExact() == context
                    && (int) shaderApi.getProgramId().invokeExact(shader) == program
                    && (Object) shaderApi.getUniformMap().invokeExact(shader) instanceof Map<?, ?> uniforms
                    && !uniforms.isEmpty();
            return eligible ? CONTEXT_SCRATCH.get().assign(context, handle) : null;
        } catch (IllegalStateException rejected) {
            return null;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw wrapOrRethrow(failure);
        }
    }

    /**
     * Drops every recorded location for a shader that reinitializes or changes program.
     *
     * @param shader host shader instance
     */
    public static void invalidate(Object shader) {
        synchronized (CACHE) {
            CACHE.remove(shader);
        }
        INVALIDATIONS.incrementAndGet();
    }

    /**
     * Drops every recorded uniform location across all shaders when GL programs mutate.
     */
    public static void invalidateAll() {
        synchronized (CACHE) {
            CACHE.clear();
        }
        INVALIDATIONS.incrementAndGet();
    }

    private static final class EntryPool {
        private final EntryBuffer[] buffers = new EntryBuffer[REENTRANT_POOL_LIMIT];

        EntryPool() {
            buffers[0] = new EntryBuffer();
        }

        Map.Entry<?, ?>[] borrow(final HashMap<?, ?> map) {
            for (int i = 0; i < REENTRANT_POOL_LIMIT; i++) {
                EntryBuffer buffer = buffers[i];
                if (buffer == null) {
                    buffer = new EntryBuffer();
                    buffers[i] = buffer;
                }
                if (!buffer.inUse) {
                    return buffer.fill(map);
                }
            }
            return allocateFallbackEntries(map);
        }

        void release(final Map.Entry<?, ?>[] entries) {
            for (int i = 0; i < REENTRANT_POOL_LIMIT; i++) {
                final EntryBuffer buffer = buffers[i];
                if (buffer == null) {
                    return;
                }
                if (buffer.entries == entries) {
                    buffer.release();
                    return;
                }
            }
        }

        private static Map.Entry<?, ?>[] allocateFallbackEntries(final HashMap<?, ?> map) {
            final Map.Entry<?, ?>[] snapshot = new Map.Entry<?, ?>[map.size() + 1];
            final int[] index = {0};
            map.forEach((key, value) -> snapshot[index[0]++] = Map.entry(key, value));
            return snapshot;
        }
    }

    private static final class EntryBuffer implements BiConsumer<Object, Object> {
        boolean inUse;
        private MutableEntry[] nodes;
        Map.Entry<?, ?>[] entries;
        private int size;

        EntryBuffer() {
            this.nodes = new MutableEntry[INITIAL_ENTRY_CAPACITY];
            this.entries = new Map.Entry<?, ?>[INITIAL_ENTRY_CAPACITY + 1];
            for (int i = 0; i < INITIAL_ENTRY_CAPACITY; i++) {
                this.nodes[i] = new MutableEntry();
            }
        }

        Map.Entry<?, ?>[] fill(final HashMap<?, ?> map) {
            inUse = true;
            size = 0;
            try {
                map.forEach(this);
                entries[size] = null;
                return entries;
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                release();
                throw wrapOrRethrow(failure);
            }
        }

        @Override
        public void accept(final Object key, final Object value) {
            if (size >= nodes.length) {
                grow();
            }
            final MutableEntry node = nodes[size];
            node.key = key;
            node.value = value;
            entries[size] = node;
            size++;
        }

        void release() {
            for (int i = 0; i < size; i++) {
                entries[i] = null;
                nodes[i].clear();
            }
            size = 0;
            inUse = false;
        }

        private void grow() {
            final int oldCap = nodes.length;
            final int newCap = oldCap << 1;
            final MutableEntry[] newNodes = new MutableEntry[newCap];
            System.arraycopy(nodes, 0, newNodes, 0, oldCap);
            for (int i = oldCap; i < newCap; i++) {
                newNodes[i] = new MutableEntry();
            }
            final Map.Entry<?, ?>[] newEntries = new Map.Entry<?, ?>[newCap + 1];
            System.arraycopy(entries, 0, newEntries, 0, size);
            nodes = newNodes;
            entries = newEntries;
        }
    }

    private static final class MutableEntry implements Map.Entry<Object, Object> {
        Object key;
        Object value;

        void clear() {
            key = null;
            value = null;
        }

        @Override
        public Object getKey() {
            return key;
        }

        @Override
        public Object getValue() {
            return value;
        }

        @Override
        public Object setValue(final Object newValue) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ContextStamp {
        Object context;
        long handle;

        ContextStamp assign(final Object context, final long handle) {
            this.context = context;
            this.handle = handle;
            return this;
        }

        void clear() {
            this.context = null;
            this.handle = 0L;
        }
    }

    private static final class State {
        final WeakReference<Object> context;
        final long handle;
        final int program;
        final Map<String, Integer> locations = new HashMap<>();
        int projectionLocation = UNKNOWN_LOCATION;
        float[] projectionValues;
        int worldToCameraLocation = UNKNOWN_LOCATION;
        float[] worldToCameraValues;

        State(Object context, long handle, int program) {
            this.context = new WeakReference<>(context);
            this.handle = handle;
            this.program = program;
        }

        boolean same(Object context, long handle, int program) {
            return this.context.get() == context && this.handle == handle && this.program == program;
        }

        boolean matchesMatrix(final int kind, final int location, final float[] values) {
            if (kind == MATRIX_KIND_PROJECTION) {
                return location == projectionLocation
                        && projectionValues != null
                        && Arrays.equals(projectionValues, 0, MATRIX_FLOAT_COUNT, values, 0, MATRIX_FLOAT_COUNT);
            }
            return location == worldToCameraLocation
                    && worldToCameraValues != null
                    && Arrays.equals(worldToCameraValues, 0, MATRIX_FLOAT_COUNT, values, 0, MATRIX_FLOAT_COUNT);
        }

        void storeMatrix(final int kind, final int location, final float[] values) {
            if (kind == MATRIX_KIND_PROJECTION) {
                if (projectionValues == null) {
                    projectionValues = new float[MATRIX_FLOAT_COUNT];
                }
                projectionLocation = location;
                System.arraycopy(values, 0, projectionValues, 0, MATRIX_FLOAT_COUNT);
            } else {
                if (worldToCameraValues == null) {
                    worldToCameraValues = new float[MATRIX_FLOAT_COUNT];
                }
                worldToCameraLocation = location;
                System.arraycopy(values, 0, worldToCameraValues, 0, MATRIX_FLOAT_COUNT);
            }
        }
    }

    private record GlAccess(MethodHandle glGetUniformLocation, MethodHandle getContext) {}

    private record ContextAccess(MethodHandle getHandle, MethodHandle isCreated, MethodHandle getCurrent) {}

    private record ShaderAccess(MethodHandle getProgramId, MethodHandle getUniformMap) {}

    private static MethodHandle unreflectHandle(
            final Class<?> type, final String name, final MethodType exactType, final Class<?>... args) {
        try {
            final Method target = findPublicMethod(type, name, args);
            final MethodHandle raw;
            if (target != null) {
                raw = MethodHandles.publicLookup().unreflect(target);
            } else {
                final Method fallback = type.getMethod(name, args);
                fallback.setAccessible(true);
                raw = MethodHandles.lookup().unreflect(fallback);
            }
            return raw.asType(exactType);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Method findPublicMethod(final Class<?> type, final String name, final Class<?>... args) {
        if (type == null) {
            return null;
        }
        if (Modifier.isPublic(type.getModifiers())) {
            try {
                final Method method = type.getMethod(name, args);
                if (Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        }
        for (final Class<?> iface : type.getInterfaces()) {
            final Method candidate = findPublicMethod(iface, name, args);
            if (candidate != null) {
                return candidate;
            }
        }
        return findPublicMethod(type.getSuperclass(), name, args);
    }

    private static final ClassValue<GlAccess> GL = new ClassValue<>() {
        @Override
        protected GlAccess computeValue(final Class<?> type) {
            return new GlAccess(
                    unreflectHandle(type, METHOD_GL_GET_UNIFORM_LOCATION, GL_QUERY_TYPE, int.class, String.class),
                    unreflectHandle(type, METHOD_GET_CONTEXT, OBJ_UNARY_TYPE));
        }
    };

    private static final ClassValue<ContextAccess> CONTEXT = new ClassValue<>() {
        @Override
        protected ContextAccess computeValue(final Class<?> type) {
            return new ContextAccess(
                    unreflectHandle(type, METHOD_GET_HANDLE, LONG_UNARY_TYPE),
                    unreflectHandle(type, METHOD_IS_CREATED, BOOL_UNARY_TYPE),
                    unreflectHandle(type, METHOD_GET_CURRENT, OBJ_ZERO_TYPE));
        }
    };

    private static final ClassValue<ShaderAccess> SHADER = new ClassValue<>() {
        @Override
        protected ShaderAccess computeValue(final Class<?> type) {
            return new ShaderAccess(
                    unreflectHandle(type, METHOD_GET_GL_PROGRAM_ID, INT_UNARY_TYPE),
                    unreflectHandle(type, METHOD_GET_UNIFORM_MAP, OBJ_UNARY_TYPE));
        }
    };

    private static RuntimeException wrapOrRethrow(final Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(failure);
    }

    private static int original(final Object gl, final int program, final String name) {
        QUERIES.incrementAndGet();
        try {
            return (int) GL.get(gl.getClass()).glGetUniformLocation().invokeExact(gl, program, name);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw wrapOrRethrow(failure);
        }
    }
}
