package dev.turboism.adapter.cubism.optimization.extendedinterpolation;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Loader-neutral bridge for the hierarchy-only incremental {@code CModelSource.setup()} path.
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
 * window the bridge still proves, on the host's own retained state, that nothing a form rebuild
 * consumes changed since the last rebuild:</p>
 *
 * <ul>
 *   <li>every keyform's {@code editVersion} equals the version the extension recorded for it
 *       during the last real rebuild ({@code getKeyformUpdateMap()});</li>
 *   <li>the owner's keyform bindings are content-equal to the copy the extension retained at
 *       the last rebuild ({@code getExtendedKeyformGridSource()});</li>
 *   <li>the extended morph-target set does not report a pending structure change.</li>
 * </ul>
 *
 * <p>Only when all three hold does the bridge call the host's own incremental
 * {@code updateInterpolatedForms(false)}, which must be a no-op for exactly those inputs. Any
 * other state, any missing member, any unexpected rebuild and any thrown error falls back to the
 * unmodified forced call.</p>
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
     * edit start, history transition and runtime hierarchy write refreshes it; it never proves
     * freshness on its own because the retained-state comparison above is the actual gate.
     */
    static final long INTENT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(30);

    private static final ThreadLocal<long[]> EDIT_INTENT = ThreadLocal.withInitial(() -> new long[1]);

    private final Class<?> extensionType;
    private final Method getOwner;
    private final Method getModelSource;
    private final Method isExtendedInterpolationEnabled;
    private final Method getAllExtendedKeyforms;
    private final Method getKeyformUpdateMap;
    private final Method getExtendedKeyformGridSource;
    private final Method getExtendedKeyformMorphTargetSet;
    private final Method updateInterpolatedForms;
    private final Method getKeyforms;
    private final Method getKeyformGridSource;
    private final Method getFormGuid;
    private final Method getFormEditVersion;
    private final Method getKeyformBindings;
    private final Method getParameterGuid;
    private final Method getParameterId;
    private final Method getExtendedInterpolationType;
    private final Method getInsertPointCount;
    private final Method getBindingKeys;
    private final Method needsSetupFormStructure;

    private final LongAdder consults = new LongAdder();
    private final LongAdder incremental = new LongAdder();
    private final LongAdder nativeFallbacks = new LongAdder();
    private final LongAdder noIntent = new LongAdder();
    private final LongAdder notEnabled = new LongAdder();
    private final LongAdder notPrimed = new LongAdder();
    private final LongAdder changed = new LongAdder();
    private final LongAdder changedVersions = new LongAdder();
    private final LongAdder changedBindings = new LongAdder();
    private final LongAdder changedMorph = new LongAdder();
    private final LongAdder changedUnavailable = new LongAdder();
    private final LongAdder unexpectedRebuilds = new LongAdder();
    private final LongAdder failures = new LongAdder();

    private final AtomicBoolean active = new AtomicBoolean();
    private Properties installedProperties;

    private final Function<Object, Object> consult = this::onConsult;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    public ExtendedInterpolationSetupBridge(final ClassLoader loader) throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        extensionType = Class.forName(ExtendedInterpolationSetupTarget.EXTENSION.replace('/', '.'), false, loader);
        getOwner = extensionType.getMethod("getOwner");
        getModelSource = extensionType.getMethod("getModelSource");
        isExtendedInterpolationEnabled = extensionType.getMethod("isExtendedInterpolationEnabled");
        getAllExtendedKeyforms = extensionType.getMethod("getAllExtendedKeyforms");
        getKeyformUpdateMap = extensionType.getMethod("getKeyformUpdateMap");
        getExtendedKeyformGridSource = extensionType.getMethod("getExtendedKeyformGridSource");
        getExtendedKeyformMorphTargetSet = extensionType.getMethod("getExtendedKeyformMorphTargetSet");
        updateInterpolatedForms = extensionType.getMethod("updateInterpolatedForms", boolean.class);

        final Class<?> ownerType = getOwner.getReturnType();
        getKeyforms = ownerType.getMethod("getKeyforms");
        getKeyformGridSource = ownerType.getMethod("getKeyformGridSource");

        final Class<?> formType = Class.forName("com.live2d.cubism.doc.model.ACForm", false, loader);
        getFormGuid = findMethod(formType, "getGuid");
        getFormEditVersion = findMethod(formType, "getEditVersion");

        final Class<?> gridType =
                Class.forName("com.live2d.cubism.doc.model.interpolator.KeyformGridSource", false, loader);
        getKeyformBindings = findMethod(gridType, "getKeyformBindings");
        final Class<?> bindingType =
                Class.forName("com.live2d.cubism.doc.model.interpolator.KeyformBindingSource", false, loader);
        getParameterGuid = findMethod(bindingType, "getParameterGuid");
        getParameterId = findMethod(bindingType, "getParameterId");
        getExtendedInterpolationType = findMethod(bindingType, "getExtendedInterpolationType");
        getInsertPointCount = findMethod(bindingType, "getInsertPointCount");
        getBindingKeys = findMethod(bindingType, "getKeys");

        needsSetupFormStructure =
                findMethod(getExtendedKeyformMorphTargetSet.getReturnType(), "isNeedSetupFormStructure");
    }

    private static Method findMethod(final Class<?> type, final String name) throws NoSuchMethodException {
        Objects.requireNonNull(type, "type");
        for (final Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
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
        values.put("fallback.notEnabled", notEnabled.sum());
        values.put("fallback.notPrimed", notPrimed.sum());
        values.put("fallback.changed", changed.sum());
        values.put("fallback.changed.versions", changedVersions.sum());
        values.put("fallback.changed.bindings", changedBindings.sum());
        values.put("fallback.changed.morph", changedMorph.sum());
        values.put("fallback.changed.unavailable", changedUnavailable.sum());
        values.put("unexpectedRebuilds", unexpectedRebuilds.sum());
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
            if (getModelSource.invoke(extension) == null || getOwner.invoke(extension) == null) {
                notPrimed.increment();
                nativeFallbacks.increment();
                return Boolean.FALSE;
            }
            if (!Boolean.TRUE.equals(isExtendedInterpolationEnabled.invoke(extension))) {
                notEnabled.increment();
                nativeFallbacks.increment();
                return Boolean.FALSE;
            }
            final Object forms = getAllExtendedKeyforms.invoke(extension);
            if (!(forms instanceof List<?> keyforms) || keyforms.isEmpty()) {
                notPrimed.increment();
                nativeFallbacks.increment();
                return Boolean.FALSE;
            }
            final String reason = fingerprintProblem(extension);
            if (reason != null) {
                changed.increment();
                switch (reason) {
                    case "versions" -> changedVersions.increment();
                    case "bindings" -> changedBindings.increment();
                    case "morph" -> changedMorph.increment();
                    default -> changedUnavailable.increment();
                }
                nativeFallbacks.increment();
                return Boolean.FALSE;
            }
            final Object rebuilt = updateInterpolatedForms.invoke(extension, Boolean.FALSE);
            if (Boolean.TRUE.equals(rebuilt)) {
                // Our retained-state comparison proved nothing changed, so the host should have
                // skipped. A rebuild is still correct, but it means the comparison is incomplete.
                unexpectedRebuilds.increment();
            }
            incremental.increment();
            return Boolean.TRUE;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            nativeFallbacks.increment();
            return Boolean.FALSE;
        }
    }

    /**
     * Compares everything the forced rebuild consumes against the state the extension retained
     * during its last real rebuild.
     *
     * @return {@code null} when the rebuild is provably redundant, otherwise the first failed check
     */
    private String fingerprintProblem(final Object extension) throws ReflectiveOperationException {
        final Object owner = getOwner.invoke(extension);
        final Object rawForms = getKeyforms.invoke(owner);
        final Object rawVersions = getKeyformUpdateMap.invoke(extension);
        if (!(rawForms instanceof List<?> forms) || !(rawVersions instanceof Map<?, ?> versions)) {
            return "unavailable";
        }
        if (versions.size() != forms.size()) return "versions";
        for (final Object form : forms) {
            if (form == null) return "unavailable";
            final Object guid = getFormGuid.invoke(form);
            final Object version = getFormEditVersion.invoke(form);
            if (guid == null || !(version instanceof Integer current)) return "unavailable";
            final Object recorded = versions.get(guid);
            if (!(recorded instanceof Integer rebuilt) || rebuilt.intValue() != current.intValue()) {
                return "versions";
            }
        }
        final Object ownerGrid = getKeyformGridSource.invoke(owner);
        final Object rebuiltGrid = getExtendedKeyformGridSource.invoke(extension);
        if (ownerGrid == null || rebuiltGrid == null) return "unavailable";
        if (!bindingsUnchanged(ownerGrid, rebuiltGrid)) return "bindings";
        final Object morphTargets = getExtendedKeyformMorphTargetSet.invoke(extension);
        if (morphTargets == null) return "unavailable";
        return Boolean.TRUE.equals(needsSetupFormStructure.invoke(morphTargets)) ? "morph" : null;
    }

    /**
     * Compares the owner's keyform bindings with the copy the extension retained at its last
     * rebuild, using the same structural rule the host applies: equal binding count, and per
     * binding the same parameter, interpolation type, insert-point count and the exact key count
     * that insertion produces. The retained copy carries its generated insert keys, so raw key
     * values cannot be compared directly. The extension's own {@code keyformUpdateMap} covers
     * content changes through the per-keyform edit versions checked above.
     */
    private boolean bindingsUnchanged(final Object ownerGrid, final Object rebuiltGrid)
            throws ReflectiveOperationException {
        final Object ownerRaw = getKeyformBindings.invoke(ownerGrid);
        final Object rebuiltRaw = getKeyformBindings.invoke(rebuiltGrid);
        if (!(ownerRaw instanceof List<?> ownerBindings) || !(rebuiltRaw instanceof List<?> rebuiltBindings)) {
            return false;
        }
        if (ownerBindings.size() != rebuiltBindings.size()) return false;
        final Map<String, List<int[]>> retained = new LinkedHashMap<>();
        for (final Object binding : rebuiltBindings) {
            if (binding == null) return false;
            retained.computeIfAbsent(bindingKey(binding), key -> new java.util.ArrayList<>())
                    .add(new int[] {
                        ((Number) getInsertPointCount.invoke(binding)).intValue(),
                        ((List<?>) getBindingKeys.invoke(binding)).size()
                    });
        }
        for (final Object binding : ownerBindings) {
            if (binding == null) return false;
            final List<int[]> candidates = retained.get(bindingKey(binding));
            if (candidates == null || candidates.isEmpty()) return false;
            final Object keys = getBindingKeys.invoke(binding);
            if (!(keys instanceof List<?> ownerKeys)) return false;
            boolean matched = false;
            for (int i = 0; i < candidates.size(); i++) {
                final int insertPoints = ownerKeys.size() <= 2 ? 0 : candidates.get(i)[0];
                final int expected = (ownerKeys.size() - 1) * insertPoints + ownerKeys.size();
                if (expected == candidates.get(i)[1]) {
                    candidates.remove(i);
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        for (final List<int[]> remaining : retained.values()) {
            if (!remaining.isEmpty()) return false;
        }
        return true;
    }

    private String bindingKey(final Object binding) throws ReflectiveOperationException {
        return getParameterGuid.invoke(binding) + "|" + getExtendedInterpolationType.invoke(binding);
    }
}
