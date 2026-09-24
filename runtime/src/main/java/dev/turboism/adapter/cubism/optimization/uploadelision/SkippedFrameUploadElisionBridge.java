package dev.turboism.adapter.cubism.optimization.uploadelision;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadTracker.ClearKind;
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
    /** Payload-free statistics slot. */
    public static final String STATS_PROPERTY = "turboism.upload-elision.stats";

    private static final int MAX_WRAPPER_KINDS = 8;

    private final MethodHandle nameHandle;
    private final MethodHandle sizeHandle;
    private final Map<Class<?>, MethodHandle> bufferHandles = new ConcurrentHashMap<>();
    private final SkippedFrameUploadTracker tracker = new SkippedFrameUploadTracker();
    private final AtomicBoolean active = new AtomicBoolean();
    private final BiPredicate<Object, Object> predicate = this::test;
    private final Consumer<Boolean> gate = this::setArmed;
    private final Runnable lifecycle = () -> tracker.clearedExternally(ClearKind.LIFECYCLE);
    private final Runnable failureNotify = () -> tracker.clearedExternally(ClearKind.EXCEPTION);
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private volatile boolean armed;
    private Properties installedProperties;

    /**
     * Resolves the reviewed dependency handles on the host loader.
     *
     * @throws ReflectiveOperationException when any reviewed member is absent
     */
    public SkippedFrameUploadElisionBridge(final ClassLoader loader)
            throws ReflectiveOperationException {
        final Class<?> base = Class.forName(
            SkippedFrameUploadElisionTarget.BASE_OWNER.replace('/', '.'), false, loader);
        nameHandle = MethodHandles.privateLookupIn(base, MethodHandles.lookup())
            .findVirtual(base, "i", MethodType.methodType(IntBuffer.class));
        final Class<?> helper = Class.forName(
            SkippedFrameUploadElisionTarget.SIZE_OWNER.replace('/', '.'), false, loader);
        final Object singleton = helper.getField("a").get(null);
        sizeHandle = MethodHandles.publicLookup()
            .unreflect(helper.getMethod("a", Buffer.class)).bindTo(singleton);
    }

    /** Occupies the consult/gate/notify/stats slots; refuses to replace another installation. */
    public synchronized void install() {
        if (active.get()) throw new IllegalStateException("upload elision already installed");
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(PREDICATE_PROPERTY) || properties.containsKey(GATE_PROPERTY)
                || properties.containsKey(LIFECYCLE_PROPERTY)
                || properties.containsKey(FAILURE_PROPERTY)
                || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("upload elision slots occupied");
            }
            try {
                properties.put(PREDICATE_PROPERTY, predicate);
                properties.put(GATE_PROPERTY, gate);
                properties.put(LIFECYCLE_PROPERTY, lifecycle);
                properties.put(FAILURE_PROPERTY, failureNotify);
                properties.put(STATS_PROPERTY, statistics);
                installedProperties = properties;
                active.set(true);
            } catch (RuntimeException | Error failure) {
                properties.remove(PREDICATE_PROPERTY, predicate);
                properties.remove(GATE_PROPERTY, gate);
                properties.remove(LIFECYCLE_PROPERTY, lifecycle);
                properties.remove(FAILURE_PROPERTY, failureNotify);
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
            final Object slot = System.getProperties()
                .get(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY);
            return slot instanceof AtomicBoolean flag && flag.get();
        } catch (Throwable denied) {
            return false;
        }
    }

    /**
     * Consulted before each guarded {@code glBufferData}/{@code glBufferSubData}
     * site: extracts the reviewed upload signature {@code (gl, name, byteSize,
     * buffer, position, limit)} and defers the decision to
     * {@link SkippedFrameUploadTracker#consider}.
     */
    private boolean test(final Object wrapper, final Object gl) {
        if (!active.get()) return false;
        try {
            final MethodHandle bufferGet = bufferHandle(wrapper.getClass());
            if (bufferGet == null) { tracker.observerFailed(); return false; }
            final IntBuffer names = (IntBuffer) nameHandle.invoke(wrapper);
            final Buffer buffer = (Buffer) bufferGet.invoke(wrapper);
            if (names == null || names.capacity() < 1 || buffer == null) {
                tracker.observerFailed();
                return false;
            }
            final int name = names.get(0);
            final long size = (long) sizeHandle.invoke(buffer);
            final int position = buffer.position(), limit = buffer.limit();
            if (name <= 0 || size <= 0L || size > Integer.MAX_VALUE * 4L
                || position < 0 || limit < position) {
                tracker.observerFailed();
                return false;
            }
            return tracker.consider(gl, name, size, buffer, position, limit,
                frameSkipped(), armed);
        } catch (Throwable observerFailure) {
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
        if (bufferHandles.size() >= MAX_WRAPPER_KINDS
            && !bufferHandles.containsKey(wrapperType)) {
            return null;
        }
        return bufferHandles.computeIfAbsent(wrapperType, type -> {
            try {
                final Method method = type.getMethod("b");
                if (!Buffer.class.isAssignableFrom(method.getReturnType())) return null;
                return MethodHandles.publicLookup().unreflect(method);
            } catch (Throwable failure) {
                return null;
            }
        });
    }

    /** Clears owned slots; outstanding consults fall back to the native path. */
    @Override public synchronized void close() {
        active.set(false);
        armed = false;
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) synchronized (properties) {
            properties.remove(PREDICATE_PROPERTY, predicate);
            properties.remove(GATE_PROPERTY, gate);
            properties.remove(LIFECYCLE_PROPERTY, lifecycle);
            properties.remove(FAILURE_PROPERTY, failureNotify);
            properties.remove(STATS_PROPERTY, statistics);
        }
    }

    /** Work counts only; no interaction benefit is inferred from them. */
    public Map<String, Long> snapshot() {
        return tracker.snapshot(armed);
    }
}
