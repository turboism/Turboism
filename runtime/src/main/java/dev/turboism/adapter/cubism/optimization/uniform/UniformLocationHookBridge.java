package dev.turboism.adapter.cubism.optimization.uniform;

import dev.turboism.core.runtime.work.FatalErrors;
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
 * results and uses cached public context accessors. Shared contexts require explicit
 * complete mutation coverage; noncurrent, uncreated or failed contexts remain native. Installation of these callbacks alone does
 * not establish lifecycle coverage; the verified installer must install all
 * corresponding transforms before exposing the enabled render path.</p>
 */
public final class UniformLocationHookBridge implements AutoCloseable {
    /** Default-on preference sampled per frame; exact host and safety admission still apply. */
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
    /** Typed {@code ()long} entry around covered native program mutations. */
    public static final String MUTATION_BEGIN_PROPERTY = "turboism.uniform-location.mutation.begin";
    /** Typed {@code (long)void} completion on normal and exceptional mutation exits. */
    public static final String MUTATION_END_PROPERTY = "turboism.uniform-location.mutation.end";
    /**
     * Typed {@code (Object,String,boolean)int} deferred error-check checkpoint.
     * Installed by the deferred-GL-error transform on the shader helper: inside
     * an owned frame it records the checkpoint and returns {@code GL_NO_ERROR}
     * without a native query; anywhere else it returns
     * {@link #DEFERRED_FALLBACK}, which the emitted code replaces with the
     * real inline {@code glGetError} — the native call then runs exactly once
     * and its own exception propagates unchanged.
     */
    public static final String DEFER_QUERY_PROPERTY = "turboism.deferred-error.query";
    /**
     * Return value telling the emitted checkpoint to run the real
     * {@code glGetError} inline. {@code glGetError} never produces a negative
     * value, so {@link Integer#MIN_VALUE} cannot collide with a real result.
     */
    public static final int DEFERRED_FALLBACK = Integer.MIN_VALUE;
    /**
     * Typed {@code ()Object} frame-exit report consult: returns the throwable the
     * frame-end deferred check armed (host-equivalent error reporting), or null.
     */
    public static final String DEFER_REPORT_PROPERTY = "turboism.deferred-error.report";
    /** Loader-neutral supplier of scalar diagnostics. */
    public static final String STATS_PROPERTY = "turboism.uniform-location.stats";

    /**
     * Extra handles the deferred error-check path needs: the real
     * {@code GL.glGetError} for frame-end queries, the host
     * error logger and the host {@code GLException(String)} constructor so the
     * frame-end report reproduces {@code shader/A.a(GL,String,Z)}'s semantics —
     * known codes log through {@code util/log/a.b} and throw only when the
     * first deferred checkpoint passed {@code z=true}; unmapped codes arm
     * {@code IllegalStateException("Not impl : " + code)} exactly like the
     * host's default branch. {@code null} keeps deferred checkpoints inert:
     * {@link #deferQuery} returns {@link #DEFERRED_FALLBACK} for the emitted
     * real inline query.
     */
    public record DeferredAccessors(
            MethodHandle glGetError, Object logger, MethodHandle log, MethodHandle exceptionNew) {
        /**
         * Resolves the deferred-report host handles without initializing the
         * Editor; {@code null} when any required shape is absent, which keeps
         * deferred checkpoints in fail-closed pass-through.
         *
         * @param loader the host loader attested by the verified installer
         * @return the bound accessors, or null when the host shape is absent
         */
        public static DeferredAccessors resolve(final ClassLoader loader) {
            try {
                final Class<?> gl = Class.forName("com.jogamp.opengl.GL", false, loader);
                final Class<?> logClass = Class.forName("com.live2d.util.log.a", false, loader);
                final Class<?> exception = Class.forName("com.jogamp.opengl.GLException", false, loader);
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new DeferredAccessors(
                        lookup.findVirtual(gl, "glGetError", MethodType.methodType(int.class))
                                .asType(MethodType.methodType(int.class, Object.class)),
                        lookup.findStaticGetter(logClass, "a", logClass).invoke(),
                        lookup.findStatic(
                                        logClass,
                                        "b",
                                        MethodType.methodType(
                                                void.class,
                                                logClass,
                                                Object.class,
                                                boolean.class,
                                                String.class,
                                                int.class,
                                                Object.class))
                                .asType(MethodType.methodType(
                                        void.class,
                                        Object.class,
                                        Object.class,
                                        boolean.class,
                                        String.class,
                                        int.class,
                                        Object.class)),
                        lookup.findConstructor(exception, MethodType.methodType(void.class, String.class)));
            } catch (Throwable absent) {
                FatalErrors.rethrowIfFatal(absent);
                return null;
            }
        }
    }

    private final FrameUniformLocationCache cache = new FrameUniformLocationCache(4096);
    private final MethodHandle frameToGl, glToContext, currentContext, contextShared, contextCreated;
    private final DeferredAccessors deferredAccessors;
    private final Map<String, Object> callbacks = new LinkedHashMap<>();
    private final Map<Long, Thread> mutations = new LinkedHashMap<>();
    private long nextMutation;
    private boolean mutationCoverage;
    private Class<?> supportedGlType;
    private boolean installed, closed, retired, frameSupported, shadow;
    private Thread frameOwner;
    private Object frameGl;
    private long token;
    private long frames,
            completedFrames,
            rejectedFrames,
            sharedFrames,
            queries,
            hits,
            nativeResults,
            failures,
            glErrors,
            invalidations,
            shadowQueries,
            shadowMismatches;
    private long deferredChecks, deferredFrames, deferredErrors, deferredThrows, deferredFallbacks;
    private int deferredQueries;
    private String deferredContext;
    private boolean deferredThrowSite;
    /**
     * Set once an owned deferred checkpoint is observed. With complete
     * program-mutation coverage, the uniform cache may keep
     * entries across frame boundaries (pending entries are confirmed only by
     * the real frame-end query, never by the checkpoint's synthetic zero).
     */
    private boolean deferredMode;

    private Throwable deferredReport;
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
        this(accessors(loader), DeferredAccessors.resolve(loader));
        supportedGlType = Class.forName("jogamp.opengl.gl4.GL4bcImpl", false, loader);
    }

    private UniformLocationHookBridge(MethodHandle[] accessors) {
        this(accessors, null);
    }

    private UniformLocationHookBridge(MethodHandle[] accessors, DeferredAccessors deferred) {
        this(accessors[0], accessors[1], accessors[2], accessors[3], accessors[4], deferred);
    }

    UniformLocationHookBridge(
            MethodHandle frame, MethodHandle context, MethodHandle current, MethodHandle shared, MethodHandle created) {
        this(frame, context, current, shared, created, null);
    }

    UniformLocationHookBridge(
            MethodHandle frame,
            MethodHandle context,
            MethodHandle current,
            MethodHandle shared,
            MethodHandle created,
            DeferredAccessors deferred) {
        frameToGl = Objects.requireNonNull(frame, "frame");
        glToContext = Objects.requireNonNull(context, "context");
        currentContext = Objects.requireNonNull(current, "current");
        contextShared = Objects.requireNonNull(shared, "shared");
        contextCreated = Objects.requireNonNull(created, "created");
        deferredAccessors = deferred;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            callbacks.put(
                    UniformLocationCallSiteTransformer.LOOKUP_PROPERTY,
                    lookup.findVirtual(
                                    getClass(),
                                    "lookup",
                                    MethodType.methodType(int.class, Object.class, int.class, String.class))
                            .bindTo(this));
            callbacks.put(
                    UniformLocationCallSiteTransformer.RECORD_PROPERTY,
                    lookup.findVirtual(
                                    getClass(),
                                    "record",
                                    MethodType.methodType(void.class, Object.class, int.class, String.class, int.class))
                            .bindTo(this));
            callbacks.put(
                    BEGIN_PROPERTY,
                    lookup.findVirtual(getClass(), "begin", MethodType.methodType(long.class, Object.class))
                            .bindTo(this));
            callbacks.put(
                    END_PROPERTY,
                    lookup.findVirtual(getClass(), "end", MethodType.methodType(void.class, long.class))
                            .bindTo(this));
            callbacks.put(
                    ERROR_PROPERTY,
                    lookup.findVirtual(getClass(), "error", MethodType.methodType(void.class, Object.class, int.class))
                            .bindTo(this));
            callbacks.put(
                    INVALIDATE_PROPERTY,
                    lookup.findVirtual(getClass(), "invalidate", MethodType.methodType(void.class))
                            .bindTo(this));
            callbacks.put(
                    MUTATION_BEGIN_PROPERTY,
                    lookup.findVirtual(getClass(), "beginMutation", MethodType.methodType(long.class))
                            .bindTo(this));
            callbacks.put(
                    MUTATION_END_PROPERTY,
                    lookup.findVirtual(getClass(), "endMutation", MethodType.methodType(void.class, long.class))
                            .bindTo(this));
            callbacks.put(
                    DEFER_QUERY_PROPERTY,
                    lookup.findVirtual(
                                    getClass(),
                                    "deferQuery",
                                    MethodType.methodType(int.class, Object.class, String.class, boolean.class))
                            .bindTo(this));
            callbacks.put(
                    DEFER_REPORT_PROPERTY,
                    lookup.findVirtual(getClass(), "consumeReport", MethodType.methodType(Object.class))
                            .bindTo(this));
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
        return new MethodHandle[] {
            lookup.findVirtual(frame, "a", MethodType.methodType(gl3))
                    .asType(MethodType.methodType(Object.class, Object.class)),
            lookup.findVirtual(gl, "getContext", MethodType.methodType(context))
                    .asType(MethodType.methodType(Object.class, Object.class)),
            lookup.findStatic(context, "getCurrent", MethodType.methodType(context))
                    .asType(MethodType.methodType(Object.class)),
            lookup.findVirtual(context, "isShared", MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class, Object.class)),
            lookup.findVirtual(context, "isCreated", MethodType.methodType(boolean.class))
                    .asType(MethodType.methodType(boolean.class, Object.class))
        };
    }

    static List<String> slots() {
        return List.of(
                UniformLocationCallSiteTransformer.LOOKUP_PROPERTY,
                UniformLocationCallSiteTransformer.RECORD_PROPERTY,
                BEGIN_PROPERTY,
                END_PROPERTY,
                ERROR_PROPERTY,
                INVALIDATE_PROPERTY,
                MUTATION_BEGIN_PROPERTY,
                MUTATION_END_PROPERTY,
                DEFER_QUERY_PROPERTY,
                DEFER_REPORT_PROPERTY,
                STATS_PROPERTY);
    }

    /** Returns the default-on preference; explicit false or malformed overrides disable reuse. */
    public static boolean enabledByPreference() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "true"));
    }

    /** Confirms complete, installer-attested shared program mutation coverage. */
    public synchronized void confirmMutationCoverage() {
        if (installed || closed || retired)
            throw new IllegalStateException("mutation coverage must precede publication");
        mutationCoverage = true;
    }
    /** Opens a program-mutation scope; zero means caching is not installed. */
    public synchronized long beginMutation() {
        if (!installed || closed || retired) return 0L;
        invalidate();
        try {
            if (mutations.size() >= 4096 || nextMutation == Long.MAX_VALUE) {
                retire();
                return 0L;
            }
            long opened = ++nextMutation;
            mutations.put(opened, Thread.currentThread());
            return opened;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            retire();
            return 0L;
        }
    }
    /** Completes a matching native mutation on its owner thread. */
    public synchronized void endMutation(long scope) {
        if (scope == 0L || closed) return;
        if (mutations.get(scope) != Thread.currentThread()) {
            retire();
            return;
        }
        mutations.remove(scope);
        // Do not revive a retired frame. A new frame must establish new native results.
        invalidate();
    }

    /** Publishes all owned slots atomically, rejecting collisions without overwriting. */
    public synchronized void install() {
        if (closed || retired) throw new IllegalStateException("uniform bridge is closed or retired");
        if (installed) return;
        Properties properties = System.getProperties();
        synchronized (properties) {
            for (String key : callbacks.keySet())
                if (properties.containsKey(key)) {
                    throw new IllegalStateException("uniform callback slot occupied: " + key);
                }
            properties.putAll(callbacks);
            installed = true;
        }
    }
    /** Opens an explicitly scoped render invocation; zero means no nested ownership. */
    public synchronized long begin(Object frame) {
        if (!installed || closed || retired) return 0L;
        // A report armed by an aborted frame must not be consumed by a nested
        // or later render3d return; any new scope entry disarms it.
        deferredReport = null;
        try {
            Object gl = (Object) frameToGl.invokeExact(frame);
            Object context = (Object) glToContext.invokeExact(gl);
            boolean shared = context != null && (boolean) contextShared.invokeExact(context);
            boolean supported = enabledByPreference()
                    && context != null
                    && (!shared || mutationCoverage)
                    && mutations.isEmpty()
                    && (supportedGlType == null || gl.getClass() == supportedGlType)
                    && (boolean) contextCreated.invokeExact(context)
                    && context == (Object) currentContext.invokeExact();
            long opened = cache.begin(context, supported, deferredMode && mutationCoverage);
            frames++;
            if (opened == 0L) {
                frameSupported = false;
                return 0L;
            }
            if (!supported) rejectedFrames++;
            if (shared) sharedFrames++;
            token = opened;
            frameOwner = Thread.currentThread();
            frameGl = gl;
            frameSupported = supported;
            shadow = Boolean.getBoolean(SHADOW_PROPERTY);
            expected = false;
            deferredQueries = 0;
            deferredContext = null;
            deferredThrowSite = false;
            deferredReport = null;
            return opened;
        } catch (Throwable problem) {
            FatalErrors.rethrowIfFatal(problem);
            retire();
            return 0L;
        }
    }
    /** Releases only the matching render token and its retained host references. */
    public synchronized void end(long scope) {
        if (frameOwner != Thread.currentThread() || scope == 0L || scope != token) return;
        // Deferred error checking: the frame's checkpoints skipped their native
        // queries, so this frame boundary performs the one real glGetError the
        // host would have consumed at its first deferred checkpoint. A clean
        // result confirms the frame's pending locations — they stay usable in
        // the next frame — while a nonzero result invalidates the whole cache
        // and arms the host-equivalent report, which the frame-exit emission
        // rethrows after this callback.
        boolean deferredClean = false;
        if (deferredQueries > 0) {
            try {
                int observed = (int) deferredAccessors.glGetError().invokeExact(frameGl);
                deferredFrames++;
                if (observed == 0) {
                    deferredClean = true;
                } else {
                    glErrors++;
                    frameSupported = false;
                    cache.invalidate();
                    reportDeferred(observed);
                }
            } catch (Throwable queryFailure) {
                FatalErrors.rethrowIfFatal(queryFailure);
                // The real frame-end query itself failed: never swallow it —
                // retire the cache and arm the original throwable so the
                // emitted frame-exit consult rethrows it unchanged.
                deferredReport = queryFailure;
                deferredThrows++;
                retire();
            }
            deferredQueries = 0;
            deferredContext = null;
            deferredThrowSite = false;
        }
        cache.end(scope, deferredClean && canRetainDeferredResults());
        completedFrames++;
        frameOwner = null;
        frameGl = null;
        frameSupported = false;
        token = 0L;
        expected = false;
        expectedName = null;
    }

    private boolean canRetainDeferredResults() {
        if (!frameSupported || !mutationCoverage) return false;
        try {
            return ownedContext(frameGl) != null;
        } catch (Throwable observerFailure) {
            FatalErrors.rethrowIfFatal(observerFailure);
            retire();
            return false;
        }
    }

    private Object ownedContext(Object gl) throws Throwable {
        if (!installed || closed || retired || !frameSupported || frameOwner != Thread.currentThread()) return null;
        Object context = (Object) glToContext.invokeExact(gl);
        if (gl != frameGl
                || context == null
                || context != (Object) currentContext.invokeExact()
                // With complete mutation coverage both shared and unshared contexts
                // are admitted. JOGL isShared() allocates a temporary weak key; do not
                // repeat that irrelevant query for every shader error check.
                || (!mutationCoverage && (boolean) contextShared.invokeExact(context))
                || !mutations.isEmpty()
                || !(boolean) contextCreated.invokeExact(context)) {
            invalidate();
            return null;
        }
        return context;
    }
    /** Returns a confirmed location or {@link Integer#MIN_VALUE} to retain the native query. */
    public synchronized int lookup(Object gl, int program, String name) {
        queries++;
        expected = false;
        try {
            Object context = ownedContext(gl);
            if (context == null) return FrameUniformLocationCache.MISS;
            int found = cache.lookup(context, program, name);
            if (found == FrameUniformLocationCache.MISS) return found;
            hits++;
            if (!shadow) return found;
            expected = true;
            expectedName = name;
            expectedProgram = program;
            expectedLocation = found;
            shadowQueries++;
            return FrameUniformLocationCache.MISS;
        } catch (Throwable problem) {
            FatalErrors.rethrowIfFatal(problem);
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
            if (expected
                    && expectedProgram == program
                    && Objects.equals(expectedName, name)
                    && expectedLocation != result) {
                shadowMismatches++;
                retire();
                return;
            }
            expected = false;
            expectedName = null;
            cache.record(context, program, name, result);
        } catch (Throwable problem) {
            FatalErrors.rethrowIfFatal(problem);
            retire();
        }
    }
    /** Observes an existing error result from the current frame's exact GL context. */
    public synchronized void error(Object gl, int error) {
        // A real out-of-frame error must also discard deferred carry-over.
        if (error != 0 && frameOwner == null) {
            glErrors++;
            invalidate();
            return;
        }
        try {
            Object context = ownedContext(gl);
            if (context == null) return;
            // Inside a deferred frame every checkpoint zero is synthetic — the
            // real query only happens at the frame boundary. A synthetic zero
            // must not confirm pending locations: an invalid location (-1)
            // would otherwise become hittable mid-frame on nothing but the
            // checkpoint's constant return. Real errors still invalidate.
            if (deferredMode && error == 0) return;
            if (error != 0) {
                glErrors++;
                frameSupported = false;
            }
            cache.checkedError(context, error);
        } catch (Throwable problem) {
            FatalErrors.rethrowIfFatal(problem);
            retire();
        }
    }
    /**
     * Deferred error-check checkpoint emitted by the deferred-GL-error
     * transform. Inside the owned render frame it records the checkpoint —
     * first call wins the report context — and returns {@code GL_NO_ERROR}
     * without a native query. The returned zero still flows through the
     * uniform lifecycle transform's emitted error callback, but the bridge
     * suppresses that synthetic confirmation — pending locations are promoted
     * only by the frame-end real query, so a stale or invalid location can
     * never become hittable mid-frame on a constant return.
     *
     * <p>Outside the frame, after close/retire, or without resolved accessors
     * it returns {@link #DEFERRED_FALLBACK} instead of running the query
     * itself: the emitted code then performs the real {@code glGetError}
     * inline exactly once and its own exception propagates unchanged. The
     * emitted catch still re-runs the inline query if this callback itself
     * fails, because an observer failure must not reach the host.</p>
     */
    public synchronized int deferQuery(Object gl, String context, boolean throwing) {
        if (!installed
                || closed
                || retired
                || deferredAccessors == null
                || frameOwner != Thread.currentThread()
                || gl != frameGl
                || token == 0L) {
            deferredFallbacks++;
            return DEFERRED_FALLBACK;
        }
        deferredChecks++;
        deferredQueries++;
        deferredMode = true;
        if (deferredContext == null) {
            deferredContext = context;
            deferredThrowSite = throwing;
        }
        return 0;
    }
    /**
     * Frame-exit report consult emitted at every {@code render3d} RETURN. The
     * throwable armed by the frame-end deferred check is consumed once; a
     * frame that aborted before its consult leaves nothing behind because
     * {@link #begin} clears the armed state.
     */
    public synchronized Object consumeReport() {
        final Throwable report = deferredReport;
        deferredReport = null;
        return report;
    }
    /**
     * Reports the frame-end error through the host-equivalent path: known
     * codes log through {@code util/log/a.b} with the first deferred
     * checkpoint's message and throw {@code GLException} only when that
     * checkpoint passed {@code z=true}; unmapped codes arm
     * {@code IllegalStateException("Not impl : " + error)} without logging —
     * exactly the host method's branches. The exact error location is lost by
     * design; the error itself is never swallowed.
     */
    private void reportDeferred(final int error) {
        if (error == 0) return;
        deferredErrors++;
        final String mapped =
                switch (error) {
                    case 1280 -> "GL_INVALID_ENUM (1280　無効な列挙　GLenum型の引数が範囲を超えている)";
                    case 1281 -> "GL_INVALID_VALUE ( 1281\t無効な値　引数が範囲を超えている)";
                    case 1282 -> "GL_INVALID_OPERATION ( 1282\t　無効な演算)";
                    case 1285 -> "GL_OUT_OF_MEMORY (1285 実行するのにメモリが足りない)";
                    case 1286 -> "GL_INVALID_FRAMEBUFFER_OPERATION ( 1286 完全じゃないフレームバッファを書いたり読んだりしようとしている)";
                    default -> null;
                };
        if (mapped == null) {
            deferredReport = new IllegalStateException("Not impl : " + error);
            deferredThrows++;
            return;
        }
        final String message = deferredContext + ": glError " + mapped;
        try {
            deferredAccessors
                    .log()
                    .invokeExact(deferredAccessors.logger(), (Object) message, false, (String) null, 6, (Object) null);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
        if (deferredThrowSite) {
            deferredReport = newException(message);
            deferredThrows++;
        }
    }
    /** Builds the host {@code GLException} or a same-message stand-in when unreachable. */
    private Throwable newException(final String message) {
        try {
            if (deferredAccessors != null) {
                // invoke, not invokeExact: the resolved constructor returns the
                // host exception type, not Throwable.
                return (Throwable) deferredAccessors.exceptionNew().invoke(message);
            }
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
        return new IllegalStateException(message);
    }
    /** Conservatively retires the current frame on any covered program mutation. */
    public synchronized void invalidate() {
        cache.invalidate();
        frameSupported = false;
        expected = false;
        expectedName = null;
        invalidations++;
    }
    /** Permanently disables reuse after a failed lifecycle transform or callback. */
    public synchronized void retire() {
        cache.invalidate();
        frameSupported = false;
        retired = true;
        expected = false;
        expectedName = null;
        failures++;
    }
    /** Returns scalar diagnostics only, never live host objects. */
    public synchronized Map<String, Long> statistics() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("active", installed && !closed && !retired ? 1L : 0L);
        result.put("frames", frames);
        result.put("completedFrames", completedFrames);
        result.put("rejectedFrames", rejectedFrames);
        result.put("sharedFrames", sharedFrames);
        result.put("queries", queries);
        result.put("hits", hits);
        result.put("nativeResults", nativeResults);
        result.put("glErrors", glErrors);
        result.put("failures", failures);
        result.put("invalidations", invalidations);
        result.put("shadowQueries", shadowQueries);
        result.put("shadowMismatches", shadowMismatches);
        result.put("deferredChecks", deferredChecks);
        result.put("deferredFrames", deferredFrames);
        result.put("deferredErrors", deferredErrors);
        result.put("deferredThrows", deferredThrows);
        result.put("deferredFallbacks", deferredFallbacks);
        result.put("deferredReady", deferredAccessors != null ? 1L : 0L);
        result.put("retained", (long) cache.retained());
        result.put("mutationCoverage", mutationCoverage ? 1L : 0L);
        result.put("mutationsInFlight", (long) mutations.size());
        return Map.copyOf(result);
    }

    @Override
    public synchronized void close() {
        closed = true;
        installed = false;
        frameSupported = false;
        cache.close();
        mutations.clear();
        frameOwner = null;
        frameGl = null;
        token = 0L;
        expected = false;
        expectedName = null;
        deferredQueries = 0;
        deferredContext = null;
        deferredThrowSite = false;
        deferredReport = null;
        Properties properties = System.getProperties();
        synchronized (properties) {
            callbacks.forEach((key, value) -> {
                if (properties.get(key) == value) properties.remove(key);
            });
        }
    }
}
