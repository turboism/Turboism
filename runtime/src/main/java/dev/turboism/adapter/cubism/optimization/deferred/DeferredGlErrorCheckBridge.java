package dev.turboism.adapter.cubism.optimization.deferred;

import dev.turboism.adapter.cubism.optimization.uniform.DeferredGlErrorReporter;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionBridge;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * Owns standalone deferred GL error lifecycle slots when {@link UniformLocationHookBridge} is absent.
 *
 * <p>Deferring per-draw {@code glGetError()} checks inside {@code SGFramework/g.render3d} eliminates
 * synchronous driver flushes even when {@code uploadElision} disables {@code uniformLocationCache}.
 * Frame-end real error results feed {@link SkippedFrameUploadElisionBridge#observeError} so upload
 * elision stays synchronized with real GPU state.</p>
 */
public final class DeferredGlErrorCheckBridge implements AutoCloseable {

    private static final String HOST_GL_IMPL = "jogamp.opengl.gl4.GL4bcImpl";

    private final MethodHandle frameToGl;
    private final MethodHandle glToContext;
    private final MethodHandle currentContext;
    private final MethodHandle contextCreated;
    private final UniformLocationHookBridge.DeferredAccessors deferredAccessors;
    private final Class<?> supportedGlType;
    private final Map<String, Object> callbacks = new LinkedHashMap<>();

    private boolean installed;
    private boolean closed;
    private boolean retired;
    private boolean frameContextValid;
    private Thread frameOwner;
    private Object frameGl;
    private long nextToken;
    private long token;
    private long frames;
    private long completedFrames;
    private long rejectedFrames;
    private long glErrors;
    private long failures;
    private long deferredChecks;
    private long deferredFrames;
    private long deferredErrors;
    private long deferredThrows;
    private long deferredFallbacks;
    private int deferredQueries;
    private String deferredContext;
    private boolean deferredThrowSite;
    private Throwable deferredReport;

    /**
     * Resolves host JOGL and deferred-error handles on the admitted host loader.
     *
     * @param loader host class loader attested by {@code VerifiedDeferredGlErrorCheckInstaller}
     * @throws ReflectiveOperationException when any required JOGL or host logger member is absent
     */
    public DeferredGlErrorCheckBridge(final ClassLoader loader) throws ReflectiveOperationException {
        this(
                DeferredGlErrorReporter.resolveFrameContext(loader),
                requireDeferredAccessors(loader),
                Class.forName(HOST_GL_IMPL, false, loader));
    }

    /** Creates a bridge from pre-bound handles for deterministic unit tests. */
    DeferredGlErrorCheckBridge(
            final DeferredGlErrorReporter.FrameContextAccessors frameAccessors,
            final UniformLocationHookBridge.DeferredAccessors deferredAccessors,
            final Class<?> supportedGlType) {
        Objects.requireNonNull(frameAccessors, "frameAccessors");
        this.frameToGl = frameAccessors.frameToGl();
        this.glToContext = frameAccessors.glToContext();
        this.currentContext = frameAccessors.currentContext();
        this.contextCreated = frameAccessors.contextCreated();
        this.deferredAccessors = Objects.requireNonNull(deferredAccessors, "deferredAccessors");
        this.supportedGlType = supportedGlType;
        try {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            callbacks.put(
                    UniformLocationHookBridge.BEGIN_PROPERTY,
                    lookup.findVirtual(getClass(), "begin", MethodType.methodType(long.class, Object.class))
                            .bindTo(this));
            callbacks.put(
                    UniformLocationHookBridge.END_PROPERTY,
                    lookup.findVirtual(getClass(), "end", MethodType.methodType(void.class, long.class))
                            .bindTo(this));
            callbacks.put(
                    UniformLocationHookBridge.DEFER_QUERY_PROPERTY,
                    lookup.findVirtual(
                                    getClass(),
                                    "deferQuery",
                                    MethodType.methodType(int.class, Object.class, String.class, boolean.class))
                            .bindTo(this));
            callbacks.put(
                    UniformLocationHookBridge.DEFER_REPORT_PROPERTY,
                    lookup.findVirtual(getClass(), "consumeReport", MethodType.methodType(Object.class))
                            .bindTo(this));
            callbacks.put(UniformLocationHookBridge.STATS_PROPERTY, (Supplier<Map<String, Long>>) this::statistics);
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException("deferred error callback signatures unavailable", impossible);
        }
    }

    private static UniformLocationHookBridge.DeferredAccessors requireDeferredAccessors(final ClassLoader loader)
            throws ReflectiveOperationException {
        final UniformLocationHookBridge.DeferredAccessors resolved =
                UniformLocationHookBridge.DeferredAccessors.resolve(loader);
        if (resolved == null) {
            throw new ReflectiveOperationException("deferred error check report path absent");
        }
        return resolved;
    }

    /**
     * Publishes standalone deferred-check slots atomically, failing if any slot is already occupied.
     */
    public synchronized void install() {
        if (closed || retired) {
            throw new IllegalStateException("deferred error bridge is closed or retired");
        }
        if (installed) {
            return;
        }
        final Properties properties = System.getProperties();
        synchronized (properties) {
            for (final String key : callbacks.keySet()) {
                if (properties.containsKey(key)) {
                    throw new IllegalStateException("deferred error callback slot occupied: " + key);
                }
            }
            properties.putAll(callbacks);
            installed = true;
        }
    }

    /**
     * Opens one frame render scope; returns zero when already inside an active scope or inactive.
     *
     * @param frame Cubism frame argument passed to {@code SGFramework/g.render3d}
     * @return non-zero token matching the subsequent {@link #end(long)}, or {@code 0L}
     */
    public synchronized long begin(final Object frame) {
        if (!installed || closed || retired) {
            return 0L;
        }
        // Clear any unconsumed report from a previously aborted frame before opening a new scope.
        deferredReport = null;
        if (token != 0L) {
            // Preserve the outer render scope on re-entrant render3d calls.
            return 0L;
        }
        try {
            final Object gl = (Object) frameToGl.invokeExact(frame);
            final Object context = gl == null ? null : (Object) glToContext.invokeExact(gl);
            final boolean contextValid = context != null
                    && DeferredGlErrorCheckTransformer.enabledByPreference()
                    && (supportedGlType == null || gl.getClass() == supportedGlType)
                    && (boolean) contextCreated.invokeExact(context)
                    && context == (Object) currentContext.invokeExact();
            long opened = ++nextToken;
            if (opened == 0L) {
                opened = ++nextToken;
            }
            frames++;
            if (!contextValid) {
                rejectedFrames++;
                SkippedFrameUploadElisionBridge.invalidateUploads();
            }
            token = opened;
            frameOwner = Thread.currentThread();
            frameGl = gl;
            frameContextValid = contextValid;
            deferredQueries = 0;
            deferredContext = null;
            deferredThrowSite = false;
            return opened;
        } catch (Throwable problem) {
            FatalErrors.rethrowIfFatal(problem);
            retire();
            return 0L;
        }
    }

    /**
     * Flushes deferred checkpoints at frame exit by executing one real {@code glGetError()} query.
     *
     * @param scope scope token returned by {@link #begin(Object)}
     */
    public synchronized void end(final long scope) {
        if (frameOwner != Thread.currentThread() || scope == 0L || scope != token) {
            return;
        }
        if (deferredQueries > 0) {
            try {
                final int observed = (int) deferredAccessors.glGetError().invokeExact(frameGl);
                deferredFrames++;
                SkippedFrameUploadElisionBridge.observeError(frameGl, observed);
                if (observed != 0) {
                    glErrors++;
                    deferredErrors++;
                    dev.turboism.adapter.cubism.shader.NativeShaderUniformBridge.invalidateAll();
                    final Throwable report = DeferredGlErrorReporter.buildReport(
                            deferredAccessors, observed, deferredContext, deferredThrowSite);
                    if (report != null) {
                        deferredReport = report;
                        deferredThrows++;
                    }
                }
            } catch (Throwable queryFailure) {
                FatalErrors.rethrowIfFatal(queryFailure);
                deferredReport = queryFailure;
                deferredThrows++;
                retire();
            }
            deferredQueries = 0;
            deferredContext = null;
            deferredThrowSite = false;
        }
        completedFrames++;
        frameOwner = null;
        frameGl = null;
        frameContextValid = false;
        token = 0L;
    }

    /**
     * Records a deferred error-check point inside an active frame, or requests inline fallback.
     *
     * @param gl JOGL {@code GL} instance observed at the shader checkpoint
     * @param context diagnostic label provided to {@code shader/A.a}
     * @param throwing whether the checkpoint requested throwing on nonzero error
     * @return {@code 0} when deferred, or {@link UniformLocationHookBridge#DEFERRED_FALLBACK}
     */
    public synchronized int deferQuery(final Object gl, final String context, final boolean throwing) {
        if (!installed
                || closed
                || retired
                || !frameContextValid
                || frameOwner != Thread.currentThread()
                || gl != frameGl
                || token == 0L) {
            deferredFallbacks++;
            return UniformLocationHookBridge.DEFERRED_FALLBACK;
        }
        deferredChecks++;
        deferredQueries++;
        if (deferredContext == null) {
            deferredContext = context;
            deferredThrowSite = throwing;
        }
        return 0;
    }

    /**
     * Returns and clears any pending throwable armed at frame exit.
     *
     * @return armed error exception or {@code null}
     */
    public synchronized Object consumeReport() {
        final Throwable report = deferredReport;
        deferredReport = null;
        return report;
    }

    /**
     * Returns immutable scalar counters for diagnostics and admission tests.
     *
     * @return point-in-time counter snapshot
     */
    public synchronized Map<String, Long> statistics() {
        final Map<String, Long> result = new LinkedHashMap<>();
        result.put("active", installed && !closed && !retired ? 1L : 0L);
        result.put("deferredReady", 1L);
        result.put("frames", frames);
        result.put("completedFrames", completedFrames);
        result.put("rejectedFrames", rejectedFrames);
        result.put("glErrors", glErrors);
        result.put("failures", failures);
        result.put("deferredChecks", deferredChecks);
        result.put("deferredFrames", deferredFrames);
        result.put("deferredErrors", deferredErrors);
        result.put("deferredThrows", deferredThrows);
        result.put("deferredFallbacks", deferredFallbacks);
        return Map.copyOf(result);
    }

    private void retire() {
        SkippedFrameUploadElisionBridge.invalidateUploads();
        dev.turboism.adapter.cubism.shader.NativeShaderUniformBridge.invalidateAll();
        frameContextValid = false;
        retired = true;
        failures++;
    }

    @Override
    public synchronized void close() {
        SkippedFrameUploadElisionBridge.invalidateUploads();
        closed = true;
        installed = false;
        frameContextValid = false;
        frameOwner = null;
        frameGl = null;
        token = 0L;
        deferredQueries = 0;
        deferredContext = null;
        deferredThrowSite = false;
        deferredReport = null;
        final Properties properties = System.getProperties();
        synchronized (properties) {
            callbacks.forEach((key, value) -> {
                if (properties.get(key) == value) {
                    properties.remove(key);
                }
            });
        }
    }
}
