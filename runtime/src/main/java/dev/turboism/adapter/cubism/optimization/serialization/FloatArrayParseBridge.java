package dev.turboism.adapter.cubism.optimization.serialization;

import dev.turboism.sdk.plugin.Registration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Owns the JDK-only callback for the exact native float-array serializer. */
public final class FloatArrayParseBridge implements AutoCloseable {
    /** Default-off startup opt-in and live disable switch. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.floatArrayParseCache";
    /** Loader-neutral callback; occupied slots are never replaced by installation. */
    public static final String CALLBACK_PROPERTY = "turboism.float-array-parse-cache.callback";
    /** Payload-free diagnostic supplier. */
    public static final String STATS_PROPERTY = "turboism.float-array-parse-cache.stats";
    private final FloatArrayParseCache cache = new FloatArrayParseCache(8192, 262144);
    private final AtomicBoolean active = new AtomicBoolean();
    private final LongAdder fallback = new LongAdder();
    private final BiFunction<Object, Object, Object> callback = this::invoke;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private Properties installedProperties;

    /** Installs one callback/statistics pair; lifetime is owned by the bootstrap installer. */
    public synchronized Registration install() {
        if (active.get()) throw new IllegalStateException("float array bridge already installed");
        Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(CALLBACK_PROPERTY) || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("float array callback slot occupied");
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
        return this::close;
    }

    // Serialize admission and parsing with close so cleared keys cannot be repopulated.
    private synchronized Object invoke(Object count, Object list) {
        if (!active.get()) return null;
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            cache.clear();
            return null;
        }
        try {
            if (!(count instanceof Integer length)) return null;
            float[] result = cache.parse(length, list);
            if (result != null && active.get() && Boolean.getBoolean(ENABLE_PROPERTY)) return result;
        } catch (RuntimeException failure) {
            // The pure native parser remains the owner of input/format exceptions.
        }
        fallback.increment();
        return null;
    }

    /** Reports actual token hits/parses, not inferred CPU time or allocation savings. */
    public Map<String, Long> snapshot() {
        Map<String, Long> result = new HashMap<>(cache.snapshot());
        result.put("active", active.get() ? 1L : 0L);
        result.put("fallback", fallback.sum());
        return Map.copyOf(result);
    }

    /** Stops callbacks, releases numeric strings and identity-removes only this instance's slots. */
    @Override public synchronized void close() {
        active.set(false);
        Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) synchronized (properties) {
            properties.remove(CALLBACK_PROPERTY, callback);
            properties.remove(STATS_PROPERTY, statistics);
        }
        cache.clear();
    }
}
