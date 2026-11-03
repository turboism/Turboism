package dev.turboism.adapter.cubism.optimization.meshsetup;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Loader-neutral bridge for the skipped-frame {@code GMesh2.setup} elision.
 *
 * <p>The woven host bytecode consults JDK functional-interface values stored in
 * {@link System#getProperties()}, so no Turboism class is referenced by the
 * rewritten mesh class. A setup may be suppressed only when all of the
 * following hold: the current frame's native model update was skipped, the
 * mesh was set up at least once in the current GL context generation, and
 * every persistent buffer wrapper that already owns a GL name reports a clean
 * dirty flag ({@code k() == false}). The dirty flag is precisely the wrapper's
 * own "content not yet uploaded" marker: the host marks it with {@code l()}
 * before geometry refreshes, and a render bind clears it only after the upload.
 * A wrapper with GL name 0 has never been uploaded by this shader (an unused
 * attribute, an empty array, or a setup that has not reached its first render
 * bind yet); its CPU payload was already filled by the completed native setup,
 * so it does not block the elision.</p>
 *
 * <p>Context identity is not inferred from the mesh. A GL context-generation
 * change invalidates every tracked instance, so the first setup pass in the
 * new context always runs natively; a thread change clears the table for the
 * same reason. Any extraction or callback failure clears the table and falls
 * back to the native setup.</p>
 */
public final class SkippedFrameMeshSetupElisionBridge implements AutoCloseable {

    /** Slot for the {@code BiPredicate<Object,Object>} (mesh, frame) setup consult. */
    public static final String PREDICATE_PROPERTY = "turboism.mesh-setup-elision.predicate";
    /** Slot for the {@code Consumer<Object>} after a normal native setup return. */
    public static final String COMPLETE_PROPERTY = "turboism.mesh-setup-elision.complete";
    /** Slot for the {@code Consumer<Object>} after a setup exception. */
    public static final String FAILURE_PROPERTY = "turboism.mesh-setup-elision.failure";
    /** Slot for the payload-free statistics supplier. */
    public static final String STATS_PROPERTY = "turboism.mesh-setup-elision.stats";

    private final MethodHandle[] bufferGetters;
    private final MethodHandle nameHandle, dirtyHandle;
    private final MethodHandle frameGl, glContext, currentContext, contextCreated, contextShared, contextHandle;

    private final WeakHashMap<Object, Long> generations = new WeakHashMap<>();
    private Object observedGl, observedContext;
    private long observedHandle;
    private long generation;
    private Thread observedThread;

    private final AtomicBoolean active = new AtomicBoolean();
    private final BiPredicate<Object, Object> predicate = this::test;
    private final Consumer<Object> complete = this::complete;
    private final Consumer<Object> failed = this::failed;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private volatile AtomicBoolean skippedFrameSlot;
    private Properties installedProperties;

    private long calls,
            skippedCalls,
            elided,
            passed,
            passNotSkipped,
            passContext,
            passThread,
            passNoBaseline,
            passDirty,
            completions,
            failures,
            observerFailures,
            contextChanges,
            threadChanges;

    /**
     * Resolves every reviewed member on the admitted host loader.
     *
     * @throws ReflectiveOperationException when a reviewed member is absent
     */
    public SkippedFrameMeshSetupElisionBridge(final ClassLoader loader) throws ReflectiveOperationException {
        final Class<?> mesh = Class.forName(SkippedFrameMeshSetupElisionTarget.OWNER.replace('/', '.'), false, loader);
        bufferGetters = new MethodHandle[SkippedFrameMeshSetupElisionTarget.BUFFER_GETTERS.size()];
        for (int i = 0; i < bufferGetters.length; i++) {
            final Method method = mesh.getMethod(SkippedFrameMeshSetupElisionTarget.BUFFER_GETTERS.get(i));
            bufferGetters[i] = MethodHandles.publicLookup()
                    .unreflect(method)
                    .asType(MethodType.methodType(Object.class, Object.class));
        }

        final Class<?> base =
                Class.forName(SkippedFrameMeshSetupElisionTarget.BASE_OWNER.replace('/', '.'), false, loader);
        final MethodHandles.Lookup baseLookup = MethodHandles.privateLookupIn(base, MethodHandles.lookup());
        nameHandle = baseLookup
                .findVirtual(
                        base, SkippedFrameMeshSetupElisionTarget.NAME_METHOD, MethodType.methodType(IntBuffer.class))
                .asType(MethodType.methodType(IntBuffer.class, Object.class));
        dirtyHandle = baseLookup
                .findVirtual(
                        base, SkippedFrameMeshSetupElisionTarget.DIRTY_METHOD, MethodType.methodType(boolean.class))
                .asType(MethodType.methodType(boolean.class, Object.class));

        final Class<?> frame =
                Class.forName(SkippedFrameMeshSetupElisionTarget.FRAME_OWNER.replace('/', '.'), false, loader);
        final Class<?> gl3 = Class.forName("com.jogamp.opengl.GL3", false, loader);
        frameGl = MethodHandles.publicLookup()
                .findVirtual(frame, SkippedFrameMeshSetupElisionTarget.FRAME_METHOD, MethodType.methodType(gl3))
                .asType(MethodType.methodType(Object.class, Object.class));

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
    }

    /** Occupies the predicate/complete/failure/stats slots; refuses another installation. */
    public synchronized void install() {
        if (active.get()) throw new IllegalStateException("mesh setup elision already installed");
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(PREDICATE_PROPERTY)
                    || properties.containsKey(COMPLETE_PROPERTY)
                    || properties.containsKey(FAILURE_PROPERTY)
                    || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("mesh setup elision slots occupied");
            }
            try {
                properties.put(PREDICATE_PROPERTY, predicate);
                properties.put(COMPLETE_PROPERTY, complete);
                properties.put(FAILURE_PROPERTY, failed);
                properties.put(STATS_PROPERTY, statistics);
                installedProperties = properties;
                active.set(true);
            } catch (RuntimeException | Error failure) {
                properties.remove(PREDICATE_PROPERTY, predicate);
                properties.remove(COMPLETE_PROPERTY, complete);
                properties.remove(FAILURE_PROPERTY, failed);
                properties.remove(STATS_PROPERTY, statistics);
                throw failure;
            }
        }
    }

    private synchronized boolean test(final Object mesh, final Object frame) {
        if (!active.get()) return false;
        calls++;
        try {
            if (mesh == null || frame == null) {
                observerFailed();
                passed++;
                return false;
            }
            if (!frameSkipped()) {
                passNotSkipped++;
                passed++;
                return false;
            }
            skippedCalls++;
            final Thread current = Thread.currentThread();
            if (observedThread == null) {
                observedThread = current;
            } else if (observedThread != current) {
                observedThread = current;
                threadChanges++;
                observerFailures++;
                clearInstances();
                passThread++;
                passed++;
                return false;
            }
            if (!validContext(frame)) {
                passContext++;
                passed++;
                return false;
            }
            final Long known = generations.get(mesh);
            if (known == null || known.longValue() != generation) {
                passNoBaseline++;
                passed++;
                return false;
            }
            for (final MethodHandle getter : bufferGetters) {
                final Object wrapper = (Object) getter.invokeExact(mesh);
                if (wrapper == null) {
                    observerFailed();
                    passed++;
                    return false;
                }
                if ((boolean) dirtyHandle.invokeExact(wrapper)) {
                    final IntBuffer names = (IntBuffer) nameHandle.invokeExact(wrapper);
                    if (names == null || names.capacity() < 1) {
                        observerFailed();
                        passed++;
                        return false;
                    }
                    if (names.get(0) > 0) {
                        passDirty++;
                        passed++;
                        return false;
                    }
                }
            }
            elided++;
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailed();
            passed++;
            return false;
        }
    }

    /** Records one successful native setup for the current context generation. */
    private synchronized void complete(final Object mesh) {
        if (!active.get() || mesh == null) return;
        try {
            if (observedThread != null && observedThread != Thread.currentThread()) return;
            final Long existing = generations.get(mesh);
            if (existing == null || existing.longValue() != generation) {
                generations.put(mesh, generation);
            }
            completions++;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observerFailed();
        }
    }

    /** Removes an instance whose pointer/value refresh failed; the next setup runs natively. */
    private synchronized void failed(final Object mesh) {
        if (!active.get()) return;
        failures++;
        if (mesh != null) generations.remove(mesh);
    }

    private void observerFailed() {
        observerFailures++;
        clearInstances();
    }

    private void clearInstances() {
        generations.clear();
    }

    /**
     * Requires the current thread's live, non-shared GL context. A new context
     * generation invalidates every tracked mesh so the first setup pass in that
     * context cannot reuse stale buffer names.
     */
    private boolean validContext(final Object frame) throws Throwable {
        final Object gl = (Object) frameGl.invokeExact(frame);
        if (gl == null) return false;
        final Object context = (Object) glContext.invokeExact(gl);
        if (context == null || context != (Object) currentContext.invokeExact()) return false;
        if (!(boolean) contextCreated.invokeExact(context)) {
            return false;
        }
        final long handle = (long) contextHandle.invokeExact(context);
        if (handle == 0L) return false;
        if (observedGl != gl || observedContext != context || observedHandle != handle) {
            final boolean hadPrevious = observedGl != null;
            observedGl = gl;
            observedContext = context;
            observedHandle = handle;
            if (hadPrevious) {
                generation++;
                contextChanges++;
                clearInstances();
            }
        }
        return true;
    }

    private boolean frameSkipped() {
        try {
            final AtomicBoolean flag = skippedFrameSlot;
            if (flag != null) return flag.get();
            final Object slot = System.getProperties().get(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY);
            if (!(slot instanceof AtomicBoolean published)) return false;
            skippedFrameSlot = published;
            return published.get();
        } catch (Throwable denied) {
            FatalErrors.rethrowIfFatal(denied);
            return false;
        }
    }

    /** Work counts and live state only; no interaction benefit is inferred from them. */
    public synchronized Map<String, Long> snapshot() {
        final Map<String, Long> result = new java.util.LinkedHashMap<>();
        result.put("calls", calls);
        result.put("skippedCalls", skippedCalls);
        result.put("elided", elided);
        result.put("passed", passed);
        result.put("passNotSkipped", passNotSkipped);
        result.put("passContext", passContext);
        result.put("passThread", passThread);
        result.put("passNoBaseline", passNoBaseline);
        result.put("passDirty", passDirty);
        result.put("completions", completions);
        result.put("failures", failures);
        result.put("observerFailures", observerFailures);
        result.put("contextChanges", contextChanges);
        result.put("threadChanges", threadChanges);
        result.put("tracked", (long) generations.size());
        result.put("active", active.get() ? 1L : 0L);
        return Map.copyOf(result);
    }

    /** Clears owned slots; outstanding consults fall back to the native setup. */
    @Override
    public synchronized void close() {
        active.set(false);
        skippedFrameSlot = null;
        observedThread = null;
        observedGl = null;
        observedContext = null;
        observedHandle = 0L;
        generation = 0L;
        clearInstances();
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null)
            synchronized (properties) {
                properties.remove(PREDICATE_PROPERTY, predicate);
                properties.remove(COMPLETE_PROPERTY, complete);
                properties.remove(FAILURE_PROPERTY, failed);
                properties.remove(STATS_PROPERTY, statistics);
            }
    }
}
