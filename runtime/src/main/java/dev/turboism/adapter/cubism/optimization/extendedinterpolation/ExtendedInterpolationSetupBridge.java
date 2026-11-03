package dev.turboism.adapter.cubism.optimization.extendedinterpolation;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Loader-neutral bridge for the incremental {@code CModelSource.setup()} path.
 *
 * <p>The woven {@code CModelSource.setup()} gate consults one JDK functional-interface slot in
 * {@link System#getProperties()}:</p>
 *
 * <ul>
 *   <li>{@link #CONSULT_PROPERTY} - {@code Function<Object, Object>} receiving the
 *       {@code CExtendedInterpolationExtension} the setup entry was about to rebuild. Returning
 *       {@link Boolean#TRUE} means the bridge already applied the host's own incremental update,
 *       so the forced whole-model form rebuild is skipped.</li>
 *   <li>{@link #STATS_PROPERTY} - {@code Supplier<Map<String, Long>>} reporting how often the
 *       incremental path was taken and why the native forced rebuild was kept.</li>
 * </ul>
 *
 * <p>Admission requires an active native-edit intent recorded on the current thread: runtime
 * hierarchy writes, native edit starts and native history transitions mark it. Within that
 * window the bridge delegates to the host's own {@code updateInterpolatedForms(false)}. That
 * method is the authoritative per-form dirty set: it compares every live {@code editVersion}
 * with the extension's retained {@code keyformUpdateMap} using direct host calls, updates only
 * the forms whose versions changed, and performs its own structural rebuild when the binding or
 * morph-target shape changed. Re-implementing that comparison in the bridge required a
 * reflective read of every form; on the 763 MB fixture that scan alone cost about 248 ms, while
 * the host's direct comparison of the same inputs costs a few milliseconds.</p>
 *
 * <p>Any missing member, any thrown error and any unexpected host state falls back to the
 * unmodified forced call, so a bridge failure can only make setup slower, never different.</p>
 */
public final class ExtendedInterpolationSetupBridge implements AutoCloseable {

    /** Explicit opt-out; the optimization is enabled by default. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.extendedInterpolationIncrementalSetup";

    /** Validation-only switch for host probes. */
    public static final String VALIDATION_PROPERTY = "turboism.validation.extendedInterpolationIncrementalSetup";

    /** Consult slot the woven setup entry calls. */
    public static final String CONSULT_PROPERTY = "turboism.extended-interpolation-setup.consult";

    /** General incremental switch; when true, admits fingerprint-proven unchanged extensions even without an edit intent. */
    public static final String GENERAL_INCREMENTAL_PROPERTY =
            "turboism.optimization.extendedInterpolationGeneralIncremental";

    /** Statistics slot. */
    public static final String STATS_PROPERTY = "turboism.extended-interpolation-setup.stats";

    /**
     * How long a recorded native-edit intent keeps admitting the incremental path. Every native
     * edit start, history transition and runtime hierarchy write refreshes it; the host's own
     * per-form version comparison remains the actual gate.
     */
    static final long INTENT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(30);

    private static final ThreadLocal<long[]> EDIT_INTENT = ThreadLocal.withInitial(() -> new long[1]);

    private final Class<?> extensionType;
    private final MethodHandle updateInterpolatedForms;

    private final LongAdder consults = new LongAdder();
    private final LongAdder incremental = new LongAdder();
    private final LongAdder nativeFallbacks = new LongAdder();
    private final LongAdder noIntent = new LongAdder();
    private final LongAdder hostRebuilds = new LongAdder();
    private final LongAdder failures = new LongAdder();

    private final AtomicBoolean active = new AtomicBoolean();
    private Properties installedProperties;

    private final Function<Object, Object> consult = this::onConsult;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    public ExtendedInterpolationSetupBridge(final ClassLoader loader) throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        extensionType = Class.forName(ExtendedInterpolationSetupTarget.EXTENSION.replace('/', '.'), false, loader);
        final Method update = extensionType.getMethod("updateInterpolatedForms", boolean.class);
        try {
            updateInterpolatedForms = MethodHandles.publicLookup()
                    .unreflect(update)
                    .asType(MethodType.methodType(boolean.class, Object.class, boolean.class));
        } catch (IllegalAccessException unavailable) {
            throw new ReflectiveOperationException("extended interpolation access unavailable", unavailable);
        }
    }

    /** {@return whether the feature is requested for this launch; enabled by default} */
    public static boolean enabledByPreference() {
        final String explicit = System.getProperty(ENABLE_PROPERTY);
        if (explicit != null) return Boolean.parseBoolean(explicit);
        return Boolean.parseBoolean(System.getProperty(VALIDATION_PROPERTY, "true"));
    }

    /**
     * Records that a native edit was started on the current thread. Called from the native edit
     * begin hook, the native history listener and the runtime hierarchy writes; it never reads
     * host state and never throws.
     */
    public static void noteNativeEditIntent() {
        try {
            EDIT_INTENT.get()[0] = System.nanoTime();
        } catch (Throwable ignored) {
            // Recording must never disturb the host.
        }
    }

    /** Removes the current thread's recorded intent; test and teardown support. */
    public static void clearNativeEditIntent() {
        EDIT_INTENT.remove();
    }

    private static boolean nativeEditInFlight() {
        final long recorded = EDIT_INTENT.get()[0];
        return recorded != 0 && System.nanoTime() - recorded <= INTENT_WINDOW_NANOS;
    }

    /** Installs the consult and statistics slots. */
    public synchronized void install() {
        if (!active.compareAndSet(false, true)) {
            throw new IllegalStateException("extended interpolation setup bridge already active");
        }
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(CONSULT_PROPERTY)) {
                active.set(false);
                throw new IllegalStateException("extended interpolation setup slot occupied");
            }
            properties.put(CONSULT_PROPERTY, consult);
            properties.put(STATS_PROPERTY, statistics);
            installedProperties = properties;
        }
    }

    @Override
    public synchronized void close() {
        if (!active.compareAndSet(true, false)) return;
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                properties.remove(CONSULT_PROPERTY, consult);
                properties.remove(STATS_PROPERTY, statistics);
            }
        }
    }

    /** {@return one immutable snapshot of the bridge counters} */
    public Map<String, Long> snapshot() {
        final Map<String, Long> values = new LinkedHashMap<>();
        values.put("consults", consults.sum());
        values.put("incremental", incremental.sum());
        values.put("nativeFallbacks", nativeFallbacks.sum());
        values.put("fallback.noIntent", noIntent.sum());
        values.put("hostRebuilds", hostRebuilds.sum());
        values.put("failures", failures.sum());
        return Collections.unmodifiableMap(values);
    }

    private static boolean generalIncrementalEnabled() {
        return Boolean.getBoolean(GENERAL_INCREMENTAL_PROPERTY);
    }

    private Object onConsult(final Object extension) {
        if (!active.get() || extension == null || !extensionType.isInstance(extension)) {
            return Boolean.FALSE;
        }
        consults.increment();
        if (!nativeEditInFlight() && !generalIncrementalEnabled()) {
            noIntent.increment();
            nativeFallbacks.increment();
            return Boolean.FALSE;
        }
        try {
            // This is the host's own dirty-set-driven update: live edit versions against the
            // retained keyformUpdateMap, plus its own binding and morph-target structure check.
            final boolean rebuilt = (boolean) updateInterpolatedForms.invokeExact(extension, false);
            if (rebuilt) hostRebuilds.increment();
            incremental.increment();
            return Boolean.TRUE;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            nativeFallbacks.increment();
            return Boolean.FALSE;
        }
    }
}
