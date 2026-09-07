package dev.turboism.adapter.cubism.optimization.geometry;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/** Fresh read-only position projection. Attested native classes are supplied by bootstrap. */
public final class WarpPositionProjectionBridge implements AutoCloseable {
    /** Default-off request and live disable switch. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.warpPositionProjection";
    /** Loader-neutral callback slot; never exposes host objects through SDK. */
    public static final String CALLBACK_PROPERTY = "turboism.warp-position-projection.callback";
    /** Payload-free statistics. */
    public static final String STATS_PROPERTY = "turboism.warp-position-projection.stats";
    private static final int MAX_POINTS = 131_072;
    private final Class<?> formType;
    private final MethodHandle positions, source, vector;
    private final AtomicBoolean active = new AtomicBoolean();
    private final LongAdder calls = new LongAdder(), projected = new LongAdder(), points = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final Function<Object, Object> callback = this::project;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private Properties installedProperties;

    /** Uses only reviewed pure accessors and the native fresh vector constructor. */
    public WarpPositionProjectionBridge(Class<?> formType, Class<?> vectorType) throws ReflectiveOperationException {
        this.formType = formType;
        var lookup = MethodHandles.publicLookup();
        positions = lookup.unreflect(formType.getMethod("getPositions"))
            .asType(MethodType.methodType(float[].class, Object.class));
        source = lookup.unreflect(formType.getMethod("getSource"))
            .asType(MethodType.methodType(Object.class, Object.class));
        vector = lookup.unreflectConstructor(vectorType.getConstructor(float.class, float.class))
            .asType(MethodType.methodType(Object.class, float.class, float.class));
    }

    /** Rejects occupied slots rather than replacing another installation. */
    public synchronized void install() {
        if (active.get()) throw new IllegalStateException("warp projection already installed");
        Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(CALLBACK_PROPERTY) || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("warp projection slots occupied");
            }
            try {
                properties.put(CALLBACK_PROPERTY, callback);
                properties.put(STATS_PROPERTY, statistics);
                installedProperties = properties;
                active.set(true);
            } catch (RuntimeException | Error failure) {
                properties.remove(CALLBACK_PROPERTY, callback);
                properties.remove(STATS_PROPERTY, statistics);
                throw failure;
            }
        }
    }

    private Object project(Object form) {
        if (!active.get() || !Boolean.getBoolean(ENABLE_PROPERTY)) return null;
        calls.increment();
        if (form == null || form.getClass() != formType) return null;
        try {
            float[] input = (float[]) positions.invokeExact(form);
            if (input == null || input.length / 2 > MAX_POINTS) return null;
            int count = input.length / 2;
            // The native empty loop does not query/validate the source.
            Object owner = count == 0 ? null : (Object) source.invokeExact(form);
            if (count != 0 && owner == null) return null;
            ArrayList<Object> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                result.add((Object) vector.invokeExact(input[2 * i], input[2 * i + 1]));
            }
            if (input != (float[]) positions.invokeExact(form)
                || (count != 0 && owner != (Object) source.invokeExact(form))
                || !active.get() || !Boolean.getBoolean(ENABLE_PROPERTY)) return null;
            projected.increment(); points.add(count);
            return result;
        } catch (Throwable failure) {
            // No native mutation has occurred. The original block owns errors and fallback.
            failures.increment();
            return null;
        }
    }

    /** Work counts only; no allocation/RAM/CPU benefit is inferred from them. */
    public Map<String, Long> snapshot() {
        return Map.of("active", active.get() ? 1L : 0L, "calls", calls.sum(), "projected", projected.sum(),
            "points", points.sum(), "failures", failures.sum());
    }

    /** Clears owned slots; outstanding callbacks discard results after closure. */
    @Override public synchronized void close() {
        active.set(false);
        Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) synchronized (properties) {
            properties.remove(CALLBACK_PROPERTY, callback);
            properties.remove(STATS_PROPERTY, statistics);
        }
    }
}
