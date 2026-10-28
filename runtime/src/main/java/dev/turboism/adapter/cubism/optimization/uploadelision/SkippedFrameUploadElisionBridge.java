package dev.turboism.adapter.cubism.optimization.uploadelision;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadTracker.ClearKind;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadTracker.Compare;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadTracker.Kind;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Loader-neutral bridge for the skipped-frame upload elision experiment.
 *
 * <p>Like {@link ModelUpdateSkipBridge}, the injected host bytecode consults JDK
 * functional-interface values stored in {@link System#getProperties()}, so no
 * Turboism class is referenced by the rewritten wrappers. The predicate slot
 * receives {@code (wrapper, gl)}; the wrapper's name-holder, payload buffer and
 * the reviewed {@code shader/A.a(Buffer)J} size are resolved once through
 * {@link MethodHandles#privateLookupIn} (the host JAR lives in the unnamed
 * module, so it is open to this lookup). Signature extraction failures are
 * counted and fall back to the native upload — see
 * {@link SkippedFrameUploadTracker} for the elision rules.</p>
 */
public final class SkippedFrameUploadElisionBridge implements AutoCloseable {

    /** Slot for the {@code BiPredicate<Object,Object>} upload consult. */
    public static final String PREDICATE_PROPERTY = "turboism.upload-elision.predicate";
    /** Slot for the {@code Consumer<Boolean>} arm/disarm control. */
    public static final String GATE_PROPERTY = "turboism.upload-elision.gate";
    /** Slot for the {@code Runnable} buffer-lifecycle clear notification. */
    public static final String LIFECYCLE_PROPERTY = "turboism.upload-elision.lifecycle";
    /** Slot for the {@code Runnable} upload-exception notification. */
    public static final String FAILURE_PROPERTY = "turboism.upload-elision.failure";
    /** Slot for a normal native-upload return; success still requires a real error check. */
    public static final String RETURN_PROPERTY = "turboism.upload-elision.return";
    /** Slot observing an existing real GL error result, never issuing a query. */
    public static final String ERROR_PROPERTY = "turboism.upload-elision.error";
    /** Payload-free statistics slot. */
    public static final String STATS_PROPERTY = "turboism.upload-elision.stats";
    /**
     * Baseline matching strategy: {@code identity} (default, object identity +
     * size + region) or {@code content} (meta signature plus a
     * {@link Buffer#mismatch} check against a retained payload snapshot —
     * reports {@code snapshotBytes} and {@code compareNanos}).
     */
    public static final String COMPARE_PROPERTY = "turboism.validation.skippedFrameUploadElision.compare";
    /**
     * Production opt-in: installs the elision in content-compare mode, armed
     * unconditionally (no per-leg gate). Default off.
     */
    public static final String ENABLE_PROPERTY = "turboism.optimization.uploadElision";
    /** Retained-payload ceiling in bytes for content mode. */
    public static final String SNAPSHOT_BUDGET_PROPERTY = "turboism.optimization.uploadElision.snapshotBudget";

    private static final int MAX_WRAPPER_KINDS = 8;

    private final MethodHandle nameHandle;
    private final MethodHandle sizeHandle;
    private final MethodHandle glContext, currentContext, contextCreated, contextShared, contextHandle;
    private Object observedGl, observedContext;
    private long observedHandle;
    private final Map<Class<?>, MethodHandle> bufferHandles = new ConcurrentHashMap<>();
    private final Map<Class<?>, Kind> kinds = new ConcurrentHashMap<>();
    private final SkippedFrameUploadTracker tracker;
    private final AtomicBoolean active = new AtomicBoolean();
    private final BiPredicate<Object, Object> predicate = this::test;
    private final Consumer<Boolean> gate = this::setArmed;
    private final Runnable lifecycle = this::lifecycleNotify;
    private final Runnable failureNotify = this::exceptionNotify;
    private final Runnable uploadReturn = this::uploadReturned;
    private final BiConsumer<Object, Integer> errorObserver = this::checkedError;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private volatile boolean armed;
    private Properties installedProperties;

    /**
     * Resolves the reviewed dependency handles on the host loader, in
     * validation mode (per-leg gate, compare mode from {@link #COMPARE_PROPERTY}).
     *
     * @throws ReflectiveOperationException when any reviewed member is absent
     */
    public SkippedFrameUploadElisionBridge(final ClassLoader loader) throws ReflectiveOperationException {
        this(loader, false);
    }

    /**
     * @param production true for the production opt-in: content compare, armed
     *                   at install, snapshot budget applied
     */
    public SkippedFrameUploadElisionBridge(final ClassLoader loader, final boolean production)
            throws ReflectiveOperationException {
        this(loader, production, SkippedFrameUploadElisionTarget.SIZE_OWNER);
    }

    public SkippedFrameUploadElisionBridge(final ClassLoader loader, final boolean production, final String sizeOwner)
            throws ReflectiveOperationException {
        final Class<?> base =
                Class.forName(SkippedFrameUploadElisionTarget.BASE_OWNER.replace('/', '.'), false, loader);
        nameHandle = MethodHandles.privateLookupIn(base, MethodHandles.lookup())
                .findVirtual(base, "i", MethodType.methodType(IntBuffer.class));
        final Class<?> helper = Class.forName(sizeOwner.replace('/', '.'), false, loader);
        final Object singleton = helper.getField("a").get(null);
        sizeHandle = MethodHandles.publicLookup()
                .unreflect(helper.getMethod("a", Buffer.class))
                .bindTo(singleton);
        final Class<?> gl = Class.forName("com.jogamp.opengl.GL", false, loader);
        final Class<?> context = Class.forName("com.jogamp.opengl.GLContext", false, loader);
        final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        glContext = lookup.findVirtual(gl, "getContext", MethodType.methodType(context))
                .asType(MethodType.methodType(Object.class, Object.class));
        currentContext = lookup.findStatic(context, "getCurrent", MethodType.methodType(context))
                .asType(MethodType.methodType(Object.class));
        contextCreated = lookup.findVirtual(context, "isCreated", MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        contextShared = lookup.findVirtual(context, "isShared", MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));
        contextHandle = lookup.findVirtual(context, "getHandle", MethodType.methodType(long.class))
                .asType(MethodType.methodType(long.class, Object.class));
        tracker = new SkippedFrameUploadTracker(
                production || "content".equals(System.getProperty(COMPARE_PROPERTY, "identity"))
                        ? Compare.CONTENT
                        : Compare.IDENTITY,
                snapshotBudget());
        armed = production;
    }

    /** Production preference; only an explicit opt-in enables reuse. */
    public static boolean enabledByPreference() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"));
    }

    private static long snapshotBudget() {
        try {
            return Long.parseLong(System.getProperty(
                    SNAPSHOT_BUDGET_PROPERTY, Long.toString(SkippedFrameUploadTracker.DEFAULT_SNAPSHOT_BUDGET)));
        } catch (NumberFormatException invalid) {
            return SkippedFrameUploadTracker.DEFAULT_SNAPSHOT_BUDGET;
        }
    }

    private synchronized void lifecycleNotify() {
        tracker.clearedExternally(ClearKind.LIFECYCLE);
        observedGl = null;
        observedContext = null;
        observedHandle = 0L;
    }

    /** Invalidates at render boundaries: no content is carried across unproven context generations. */
    public static void invalidateUploads() {
        try {
            Object slot = System.getProperties().get(LIFECYCLE_PROPERTY);
            if (slot instanceof Runnable callback) callback.run();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /** Called only for real host error results, including the real deferred frame-end query. */
    @SuppressWarnings("unchecked")
    public static void observeError(Object gl, int error) {
        try {
            Object slot = System.getProperties().get(ERROR_PROPERTY);
            if (slot instanceof BiConsumer<?, ?> callback) ((BiConsumer<Object, Integer>) callback).accept(gl, error);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            invalidateUploads();
        }
    }

    private boolean validContext(Object gl) throws Throwable {
        Object context = (Object) glContext.invokeExact(gl);
        if (context == null
                || context != (Object) currentContext.invokeExact()
                || !(boolean) contextCreated.invokeExact(context)
                || (boolean) contextShared.invokeExact(context)) {
            lifecycleNotify();
            return false;
        }
        long handle = (long) contextHandle.invokeExact(context);
        if (handle == 0L) {
            lifecycleNotify();
            return false;
        }
        if (observedGl != gl || observedContext != context || observedHandle != handle) {
            lifecycleNotify();
            observedGl = gl;
            observedContext = context;
            observedHandle = handle;
        }
        return true;
    }

    private synchronized void uploadReturned() {
        try {
            if (active.get() && observedGl != null && validContext(observedGl)) tracker.uploadReturned(observedGl);
            else lifecycleNotify();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            tracker.observerFailed();
        }
    }

    private synchronized void checkedError(Object gl, Integer error) {
        try {
            if (error != null && error != 0) tracker.checkedError(gl, error);
            else if (active.get() && error != null && validContext(gl)) tracker.checkedError(gl, error);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            tracker.observerFailed();
        }
    }

    private synchronized void exceptionNotify() {
        tracker.clearedExternally(ClearKind.EXCEPTION);
    }

    /** Occupies the consult/gate/notify/stats slots; refuses to replace another installation. */
    public synchronized void install() {
        if (active.get()) throw new IllegalStateException("upload elision already installed");
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(PREDICATE_PROPERTY)
                    || properties.containsKey(GATE_PROPERTY)
                    || properties.containsKey(LIFECYCLE_PROPERTY)
                    || properties.containsKey(FAILURE_PROPERTY)
                    || properties.containsKey(RETURN_PROPERTY)
                    || properties.containsKey(ERROR_PROPERTY)
                    || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("upload elision slots occupied");
            }
            try {
                properties.put(PREDICATE_PROPERTY, predicate);
                properties.put(GATE_PROPERTY, gate);
                properties.put(LIFECYCLE_PROPERTY, lifecycle);
                properties.put(FAILURE_PROPERTY, failureNotify);
                properties.put(RETURN_PROPERTY, uploadReturn);
                properties.put(ERROR_PROPERTY, errorObserver);
                properties.put(STATS_PROPERTY, statistics);
                installedProperties = properties;
                active.set(true);
            } catch (RuntimeException | Error failure) {
                properties.remove(PREDICATE_PROPERTY, predicate);
                properties.remove(GATE_PROPERTY, gate);
                properties.remove(LIFECYCLE_PROPERTY, lifecycle);
                properties.remove(FAILURE_PROPERTY, failureNotify);
                properties.remove(RETURN_PROPERTY, uploadReturn);
                properties.remove(ERROR_PROPERTY, errorObserver);
                properties.remove(STATS_PROPERTY, statistics);
                throw failure;
            }
        }
    }

    private void setArmed(final boolean value) {
        armed = value;
    }

    private boolean frameSkipped() {
        try {
            final Object slot = System.getProperties().get(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY);
            return slot instanceof AtomicBoolean flag && flag.get();
        } catch (Throwable denied) {
            FatalErrors.rethrowIfFatal(denied);
            return false;
        }
    }

    /**
     * Consulted before each guarded {@code glBufferData}/{@code glBufferSubData}
     * site: extracts the reviewed upload signature {@code (gl, name, byteSize,
     * buffer, position, limit)} and defers the decision to
     * {@link SkippedFrameUploadTracker#consider}.
     */
    private synchronized boolean test(final Object wrapper, final Object gl) {
        if (!active.get()) return false;
        try {
            if (!validContext(gl)) return false;
            final Class<?> wrapperType = wrapper.getClass();
            final MethodHandle bufferGet = bufferHandle(wrapperType);
            if (bufferGet == null) {
                tracker.observerFailed();
                return false;
            }
            final Kind kind = kinds.computeIfAbsent(
                    wrapperType,
                    type -> type.getName().replace('.', '/').equals(SkippedFrameUploadElisionTarget.INDEX_OWNER)
                            ? Kind.INDEX
                            : Kind.FLOAT);
            final IntBuffer names = (IntBuffer) nameHandle.invoke(wrapper);
            final Buffer buffer = (Buffer) bufferGet.invoke(wrapper);
            if (names == null || names.capacity() < 1 || buffer == null) {
                tracker.observerFailed();
                return false;
            }
            final int name = names.get(0);
            final long size = (long) sizeHandle.invoke(buffer);
            final int position = buffer.position(), limit = buffer.limit();
            if (name <= 0 || size <= 0L || size > Integer.MAX_VALUE * 4L || position < 0 || limit < position) {
                tracker.observerFailed();
                return false;
            }
            return tracker.consider(gl, name, size, buffer, position, limit, frameSkipped(), armed, kind);
        } catch (Throwable observerFailure) {
            FatalErrors.rethrowIfFatal(observerFailure);
            tracker.observerFailed();
            return false;
        }
    }

    /** Per-class {@code b()} payload-buffer handle; unknown wrapper kinds fail closed. */
    private MethodHandle bufferHandle(final Class<?> wrapperType) {
        if (!SkippedFrameUploadElisionTarget.OWNERS.contains(
                wrapperType.getName().replace('.', '/'))) {
            return null;
        }
        if (bufferHandles.size() >= MAX_WRAPPER_KINDS && !bufferHandles.containsKey(wrapperType)) {
            return null;
        }
        return bufferHandles.computeIfAbsent(wrapperType, type -> {
            try {
                final Method method = type.getMethod("b");
                if (!Buffer.class.isAssignableFrom(method.getReturnType())) return null;
                return MethodHandles.publicLookup().unreflect(method);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return null;
            }
        });
    }

    /** Clears owned slots; outstanding consults fall back to the native path. */
    @Override
    public synchronized void close() {
        active.set(false);
        armed = false;
        lifecycleNotify();
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null)
            synchronized (properties) {
                properties.remove(PREDICATE_PROPERTY, predicate);
                properties.remove(GATE_PROPERTY, gate);
                properties.remove(LIFECYCLE_PROPERTY, lifecycle);
                properties.remove(FAILURE_PROPERTY, failureNotify);
                properties.remove(RETURN_PROPERTY, uploadReturn);
                properties.remove(ERROR_PROPERTY, errorObserver);
                properties.remove(STATS_PROPERTY, statistics);
            }
    }

    /** Work counts only; no interaction benefit is inferred from them. */
    public synchronized Map<String, Long> snapshot() {
        return tracker.snapshot(armed);
    }
}
