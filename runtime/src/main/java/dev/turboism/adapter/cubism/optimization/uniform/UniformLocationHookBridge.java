package dev.turboism.adapter.cubism.optimization.uniform;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Typed, loader-neutral callback owner for an explicitly admitted render lifecycle.
 *
 * <p>The bridge performs no GL query, draw or write itself. It observes native
 * results and uses cached public context accessors. Shared, noncurrent, uncreated
 * or failed contexts remain native. Installation of these callbacks alone does
 * not establish lifecycle coverage; the verified installer must install all
 * corresponding transforms before exposing the enabled render path.</p>
 */
public final class UniformLocationHookBridge implements AutoCloseable {
    /** Explicit opt-in switch, sampled at each frame entry. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.uniformLocationCache";
    /** Diagnostic mode retaining native queries and comparing cached results. */
    public static final String SHADOW_PROPERTY = "turboism.uniform-location.shadow";
    /** Typed {@code (Object)long} render-scope entry callback. */
    public static final String BEGIN_PROPERTY = "turboism.uniform-location.begin";
    /** Typed {@code (long)void} render-scope completion callback. */
    public static final String END_PROPERTY = "turboism.uniform-location.end";
    /** Typed {@code (Object,int)void} native error result callback. */
    public static final String ERROR_PROPERTY = "turboism.uniform-location.error";
    /** Typed {@code ()void} conservative program mutation callback. */
    public static final String INVALIDATE_PROPERTY = "turboism.uniform-location.invalidate";
    /** Loader-neutral supplier of scalar diagnostics. */
    public static final String STATS_PROPERTY = "turboism.uniform-location.stats";
    private final FrameUniformLocationCache cache = new FrameUniformLocationCache(4096);
    private final MethodHandle frameToGl, glToContext, currentContext, contextShared, contextCreated;
    private final Map<String, Object> callbacks = new LinkedHashMap<>();
    private Class<?> supportedGlType;
    private boolean installed, closed, retired, frameSupported, shadow;
    private Thread frameOwner;
    private Object frameGl;
    private long token;
    private long frames, completedFrames, rejectedFrames, sharedFrames, queries, hits, nativeResults,
        failures, glErrors, invalidations, shadowQueries, shadowMismatches;
    private String expectedName;
    private int expectedProgram, expectedLocation;
    private boolean expected;

    /**
     * Resolves public host/context accessors once without initializing the Editor.
     *
     * @param loader the host loader attested by the verified installer
     * @throws ReflectiveOperationException when the required accessor shape is absent
     */
    public UniformLocationHookBridge(ClassLoader loader) throws ReflectiveOperationException {
        this(accessors(loader));
        supportedGlType = Class.forName("jogamp.opengl.gl4.GL4bcImpl", false, loader);
    }
    private UniformLocationHookBridge(MethodHandle[] accessors) {
        this(accessors[0], accessors[1], accessors[2], accessors[3], accessors[4]);
    }
    UniformLocationHookBridge(MethodHandle frame, MethodHandle context, MethodHandle current,
                              MethodHandle shared, MethodHandle created) {
        frameToGl = Objects.requireNonNull(frame, "frame");
        glToContext = Objects.requireNonNull(context, "context");
        currentContext = Objects.requireNonNull(current, "current");
        contextShared = Objects.requireNonNull(shared, "shared");
        contextCreated = Objects.requireNonNull(created, "created");
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            callbacks.put(UniformLocationCallSiteTransformer.LOOKUP_PROPERTY, lookup.findVirtual(getClass(), "lookup",
                MethodType.methodType(int.class, Object.class, int.class, String.class)).bindTo(this));
            callbacks.put(UniformLocationCallSiteTransformer.RECORD_PROPERTY, lookup.findVirtual(getClass(), "record",
                MethodType.methodType(void.class, Object.class, int.class, String.class, int.class)).bindTo(this));
            callbacks.put(BEGIN_PROPERTY, lookup.findVirtual(getClass(), "begin", MethodType.methodType(long.class, Object.class)).bindTo(this));
            callbacks.put(END_PROPERTY, lookup.findVirtual(getClass(), "end", MethodType.methodType(void.class, long.class)).bindTo(this));
            callbacks.put(ERROR_PROPERTY, lookup.findVirtual(getClass(), "error", MethodType.methodType(void.class, Object.class, int.class)).bindTo(this));
            callbacks.put(INVALIDATE_PROPERTY, lookup.findVirtual(getClass(), "invalidate", MethodType.methodType(void.class)).bindTo(this));
            callbacks.put(STATS_PROPERTY, (Supplier<Map<String, Long>>) this::statistics);
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException("uniform callback signatures unavailable", impossible);
        }
    }
    private static MethodHandle[] accessors(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> frame = Class.forName("com.live2d.graphics3d.a", false, loader);
        Class<?> gl = Class.forName("com.jogamp.opengl.GL", false, loader);
        Class<?> gl3 = Class.forName("com.jogamp.opengl.GL3", false, loader);
        Class<?> context = Class.forName("com.jogamp.opengl.GLContext", false, loader);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        return new MethodHandle[]{
            lookup.findVirtual(frame, "a", MethodType.methodType(gl3)).asType(MethodType.methodType(Object.class, Object.class)),
            lookup.findVirtual(gl, "getContext", MethodType.methodType(context)).asType(MethodType.methodType(Object.class, Object.class)),
            lookup.findStatic(context, "getCurrent", MethodType.methodType(context)).asType(MethodType.methodType(Object.class)),
            lookup.findVirtual(context, "isShared", MethodType.methodType(boolean.class)).asType(MethodType.methodType(boolean.class, Object.class)),
            lookup.findVirtual(context, "isCreated", MethodType.methodType(boolean.class)).asType(MethodType.methodType(boolean.class, Object.class))
        };
    }
    static List<String> slots() {
        return List.of(UniformLocationCallSiteTransformer.LOOKUP_PROPERTY, UniformLocationCallSiteTransformer.RECORD_PROPERTY,
            BEGIN_PROPERTY, END_PROPERTY, ERROR_PROPERTY, INVALIDATE_PROPERTY, STATS_PROPERTY);
    }

    /** Publishes all owned slots atomically, rejecting collisions without overwriting. */
    public synchronized void install() {
        if (closed || retired) throw new IllegalStateException("uniform bridge is closed or retired");
        if (installed) return;
        Properties properties = System.getProperties();
        synchronized (properties) {
            for (String key : callbacks.keySet()) if (properties.containsKey(key)) {
                throw new IllegalStateException("uniform callback slot occupied: " + key);
            }
            properties.putAll(callbacks);
            installed = true;
        }
    }
    /** Opens an explicitly scoped render invocation; zero means no nested ownership. */
    public synchronized long begin(Object frame) {
        if (!installed || closed || retired) return 0L;
        try {
            Object gl = (Object) frameToGl.invokeExact(frame);
            Object context = (Object) glToContext.invokeExact(gl);
            boolean shared = context != null && (boolean) contextShared.invokeExact(context);
            boolean supported = Boolean.getBoolean(ENABLE_PROPERTY) && context != null && !shared
                && (supportedGlType == null || gl.getClass() == supportedGlType)
                && (boolean) contextCreated.invokeExact(context)
                && context == (Object) currentContext.invokeExact();
            long opened = cache.begin(context, supported);
            frames++;
            if (opened == 0L) { frameSupported = false; return 0L; }
            if (!supported) rejectedFrames++;
            if (shared) sharedFrames++;
            token = opened; frameOwner = Thread.currentThread(); frameGl = gl; frameSupported = supported;
            shadow = Boolean.getBoolean(SHADOW_PROPERTY); expected = false;
            return opened;
        } catch (Throwable problem) {
            retire();
            return 0L;
        }
    }
    /** Releases only the matching render token and its retained host references. */
    public synchronized void end(long scope) {
        if (frameOwner != Thread.currentThread() || scope == 0L || scope != token) return;
        cache.end(scope);
        completedFrames++;
        frameOwner = null; frameGl = null; frameSupported = false; token = 0L; expected = false; expectedName = null;
    }
    private Object ownedContext(Object gl) throws Throwable {
        if (!installed || closed || retired || !frameSupported || frameOwner != Thread.currentThread()) return null;
        Object context = (Object) glToContext.invokeExact(gl);
        if (gl != frameGl || context == null || context != (Object) currentContext.invokeExact()
            || (boolean) contextShared.invokeExact(context) || !(boolean) contextCreated.invokeExact(context)) {
            invalidate();
            return null;
        }
        return context;
    }
    /** Returns a confirmed location or {@link Integer#MIN_VALUE} to retain the native query. */
    public synchronized int lookup(Object gl, int program, String name) {
        queries++; expected = false;
        try {
            Object context = ownedContext(gl);
            if (context == null) return FrameUniformLocationCache.MISS;
            int found = cache.lookup(context, program, name);
            if (found == FrameUniformLocationCache.MISS) return found;
            hits++;
            if (!shadow) return found;
            expected = true; expectedName = name; expectedProgram = program; expectedLocation = found;
            shadowQueries++;
            return FrameUniformLocationCache.MISS;
        } catch (Throwable problem) {
            retire();
            return FrameUniformLocationCache.MISS;
        }
    }
    /** Records an original native result without assuming that normal return proves GL success. */
    public synchronized void record(Object gl, int program, String name, int result) {
        nativeResults++;
        try {
            Object context = ownedContext(gl);
            if (context == null) return;
            if (expected && expectedProgram == program && Objects.equals(expectedName, name) && expectedLocation != result) {
                shadowMismatches++; retire(); return;
            }
            expected = false; expectedName = null;
            cache.record(context, program, name, result);
        } catch (Throwable problem) { retire(); }
    }
    /** Observes an existing error result from the current frame's exact GL context. */
    public synchronized void error(Object gl, int error) {
        try {
            Object context = ownedContext(gl);
            if (context == null) return;
            if (error != 0) { glErrors++; frameSupported = false; }
            cache.checkedError(context, error);
        } catch (Throwable problem) { retire(); }
    }
    /** Conservatively retires the current frame on any covered program mutation. */
    public synchronized void invalidate() {
        cache.invalidate(); frameSupported = false; expected = false; expectedName = null; invalidations++;
    }
    /** Permanently disables reuse after a failed lifecycle transform or callback. */
    public synchronized void retire() {
        cache.invalidate(); frameSupported = false; retired = true; expected = false; expectedName = null; failures++;
    }
    /** Returns scalar diagnostics only, never live host objects. */
    public synchronized Map<String, Long> statistics() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("active", installed && !closed && !retired ? 1L : 0L);
        result.put("frames", frames); result.put("completedFrames", completedFrames);
        result.put("rejectedFrames", rejectedFrames); result.put("sharedFrames", sharedFrames);
        result.put("queries", queries); result.put("hits", hits); result.put("nativeResults", nativeResults);
        result.put("glErrors", glErrors); result.put("failures", failures); result.put("invalidations", invalidations);
        result.put("shadowQueries", shadowQueries); result.put("shadowMismatches", shadowMismatches);
        result.put("retained", (long) cache.retained());
        return Map.copyOf(result);
    }
    @Override public synchronized void close() {
        closed = true; installed = false; frameSupported = false; cache.close();
        frameOwner = null; frameGl = null; token = 0L; expected = false; expectedName = null;
        Properties properties = System.getProperties();
        synchronized (properties) {
            callbacks.forEach((key, value) -> { if (properties.get(key) == value) properties.remove(key); });
        }
    }
}
