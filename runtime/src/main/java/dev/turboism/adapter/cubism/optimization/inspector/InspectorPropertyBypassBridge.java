package dev.turboism.adapter.cubism.optimization.inspector;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Loader-neutral state machine for bypassing a full model-instance rebuild after an inspector
 * visual-property change.
 *
 * <p>The woven host bytecode only consults a JDK {@link Predicate} slot from
 * {@link System#getProperties()}. When the predicate answers {@code true}, the setter's native
 * full rebuild is skipped; the predicate has already refreshed the affected drawable's shader via
 * its public {@code setupShader(Map)} entry. The native source mutation and the setter's trailing
 * palette refresh still run, so the inspector stays consistent.</p>
 *
 * <p>Any reflection, host-shape or callback failure falls back to the unmodified native path.
 * Counters are exposed through {@value #STATS_PROPERTY}.</p>
 */
public final class InspectorPropertyBypassBridge implements AutoCloseable {

    /** Opt-in switch; the persisted policy default is supplied by the bootstrap installer. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.inspectorPropertyBypass";

    /** Validation-only switch used by host probes; explicit enable < this override < policy default. */
    public static final String VALIDATION_PROPERTY = "turboism.validation.inspectorPropertyBypass";

    /** Entry slot for {@code Predicate<Object>} receiving the inspector setter instance. */
    public static final String BYPASS_CALLBACK_PROPERTY = "turboism.inspector-property-bypass.bypass";

    /** Payload-free statistics slot. */
    public static final String STATS_PROPERTY = "turboism.inspector-property-bypass.stats";

    private static volatile boolean configuredDefault;

    private final Host host;
    private final AtomicBoolean active = new AtomicBoolean();
    private final Predicate<Object> bypass = this::shouldBypass;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private final LongAdder calls = new LongAdder(), bypassed = new LongAdder(), failures = new LongAdder();
    private Properties installedProperties;
    private volatile boolean closed;

    /**
     * Resolves the reflective path from each inspector setter to its drawable.
     *
     * @param loader the admitted host loader
     * @throws ReflectiveOperationException when a reviewed drawable or shader entry is absent
     */
    public InspectorPropertyBypassBridge(final ClassLoader loader) throws ReflectiveOperationException {
        this(ReflectiveHost.resolve(Objects.requireNonNull(loader, "loader")));
    }

    InspectorPropertyBypassBridge(final Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /** Updates the persisted-config fallback used when neither JVM switch is set. */
    public static void setConfiguredDefault(final boolean enabled) {
        configuredDefault = enabled;
    }

    /** Whether the preference and any local override permit the bypass. */
    public static boolean enabledByPreference() {
        try {
            final String explicit = System.getProperty(ENABLE_PROPERTY);
            if (explicit != null) return Boolean.parseBoolean(explicit);
            if (Boolean.parseBoolean(System.getProperty(VALIDATION_PROPERTY, "false"))) return true;
            return configuredDefault;
        } catch (SecurityException denied) {
            return false;
        }
    }

    /** Occupies every callback slot; refuses to replace another installation. */
    public synchronized void install() {
        if (active.get()) throw new IllegalStateException("inspector property bypass already installed");
        final Properties properties = System.getProperties();
        synchronized (properties) {
            for (final String key : propertyKeys()) {
                if (properties.containsKey(key)) {
                    throw new IllegalStateException("inspector property bypass slots occupied: " + key);
                }
            }
            try {
                properties.put(BYPASS_CALLBACK_PROPERTY, bypass);
                properties.put(STATS_PROPERTY, statistics);
                installedProperties = properties;
                active.set(true);
            } catch (RuntimeException | Error failure) {
                removeSlots(properties);
                throw failure;
            }
        }
    }

    private static String[] propertyKeys() {
        return new String[] {BYPASS_CALLBACK_PROPERTY, STATS_PROPERTY};
    }

    /**
     * Refreshes exactly the affected drawable's shader instead of rebuilding every model instance.
     *
     * @param setter the native inspector setter instance
     * @return true only when the direct shader refresh completed and the native rebuild can be skipped
     */
    boolean shouldBypass(final Object setter) {
        if (closed || !active.get() || setter == null || !enabledByPreference()) return false;
        calls.increment();
        try {
            final Object drawable = host.drawable(setter);
            if (drawable == null) {
                failures.increment();
                return false;
            }
            host.setupShader(drawable);
            bypassed.increment();
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return false;
        }
    }

    /** Snapshot for diagnostics and tests. */
    public Map<String, Long> snapshot() {
        return Map.of(
                "calls", calls.sum(),
                "bypassed", bypassed.sum(),
                "failures", failures.sum());
    }

    /** Clears all owned slots; outstanding callbacks immediately fall back to native. */
    @Override
    public synchronized void close() {
        closed = true;
        active.set(false);
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                removeSlots(properties);
            }
        }
    }

    private void removeSlots(final Properties properties) {
        properties.remove(BYPASS_CALLBACK_PROPERTY, bypass);
        properties.remove(STATS_PROPERTY, statistics);
    }

    /** Minimal host surface, kept separate so the state machine is unit-testable. */
    interface Host {
        Object drawable(Object setter) throws Throwable;

        void setupShader(Object drawable) throws Throwable;
    }

    /**
     * Resolves a setter to its drawable through stable public inspector getters and the synthetic
     * outer-instance field used by the alpha-composition inner classes.
     */
    private static final class ReflectiveHost implements Host {
        private static final List<String> DRAWABLE_GETTERS = List.of("getObj", "getArtMesh", "getPart");

        /** Exact descriptor of the reviewed shader entry, {@code setupShader(Map)}. */
        private static final String SHADER_DESCRIPTOR = "(Ljava/util/Map;)V";

        /** Erased receiver-first shape of every reference-returning accessor. */
        private static final MethodType REFERENCE = MethodType.methodType(Object.class, Object.class);

        /** Erased shape of {@code setupShader(Map)}. */
        private static final MethodType SHADER = MethodType.methodType(void.class, Object.class, Object.class);

        private final ConcurrentHashMap<Class<?>, Target> targets = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Class<?>, MethodHandle> shaders = new ConcurrentHashMap<>();

        static ReflectiveHost resolve(final ClassLoader loader) throws ReflectiveOperationException {
            final ReflectiveHost host = new ReflectiveHost();
            // Fail the install before weaving when the reviewed seams or shader entry are absent.
            for (final InspectorPropertyBypassTarget.Seam seam : InspectorPropertyBypassTarget.SEAMS) {
                host.target(HostHandles.load(loader, seam.owner()));
            }
            for (final String owner : InspectorPropertyBypassTarget.DRAWABLE_OWNERS) {
                host.shader(HostHandles.load(loader, owner));
            }
            return host;
        }

        @Override
        public Object drawable(final Object setter) throws Throwable {
            return target(setter.getClass()).drawable(setter);
        }

        @Override
        public void setupShader(final Object drawable) throws Throwable {
            shader(drawable.getClass()).invokeWithArguments(new Object[] {drawable, null});
        }

        private Target target(final Class<?> setterType) throws ReflectiveOperationException {
            Target target = targets.get(setterType);
            if (target != null) return target;
            target = resolveTarget(setterType);
            targets.putIfAbsent(setterType, target);
            return target;
        }

        private static Target resolveTarget(final Class<?> setterType) throws ReflectiveOperationException {
            final MethodHandle direct = drawableGetter(setterType);
            if (direct != null) return new Target(List.of(direct));
            for (Class<?> type = setterType; type != null && type != Object.class; type = type.getSuperclass()) {
                for (final Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    final MethodHandle inner = drawableGetter(field.getType());
                    if (inner == null) continue;
                    final MethodHandle outer = HostHandles.getter(
                            field.getDeclaringClass(),
                            field.getName(),
                            field.getType().descriptorString(),
                            REFERENCE);
                    return new Target(List.of(outer, inner));
                }
            }
            throw new NoSuchFieldException(setterType.getName() + " has no drawable wrapper");
        }

        /**
         * Discovers the reviewed drawable getter by name (its return type is whatever that wrapper
         * exposes), then opens the discovered member through {@link HostHandles} so the exact
         * descriptor decides which overload is bound.
         */
        private static MethodHandle drawableGetter(final Class<?> type) throws ReflectiveOperationException {
            for (final String name : DRAWABLE_GETTERS) {
                for (final Method method : MethodHandleCache.overloads(type, name, 0)) {
                    if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() == void.class) {
                        continue;
                    }
                    return HostHandles.virtual(type, name, descriptor(method), REFERENCE);
                }
            }
            return null;
        }

        private MethodHandle shader(final Class<?> drawableType) throws ReflectiveOperationException {
            MethodHandle shader = shaders.get(drawableType);
            if (shader != null) return shader;
            shader = HostHandles.virtual(drawableType, "setupShader", SHADER_DESCRIPTOR, SHADER);
            shaders.putIfAbsent(drawableType, shader);
            return shader;
        }

        private static String descriptor(final Method method) {
            return MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                    .descriptorString();
        }
    }

    private record Target(List<MethodHandle> steps) {
        Object drawable(final Object setter) throws Throwable {
            Object current = setter;
            for (final MethodHandle step : steps) {
                current = step.invoke(current);
            }
            return current;
        }
    }
}
