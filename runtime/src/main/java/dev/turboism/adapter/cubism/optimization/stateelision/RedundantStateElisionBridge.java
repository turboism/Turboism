package dev.turboism.adapter.cubism.optimization.stateelision;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Supplier;

/**
 * System-property bridge between the transformed {@code GL4bcImpl} bytecode
 * (bootstrap class loader) and this runtime tracker. Mirrors the
 * upload-elision slot protocol: an {@link java.lang.invoke.MethodHandle}
 * consult, an invalidate handle, an exception-notify handle, a gate consumer
 * and a stats supplier. Consults run through {@code invokeExact} so no boxing
 * or array allocation happens on the call path.
 */
public final class RedundantStateElisionBridge {

    /** {@code (Ljava/lang/Object;IIIII)Z} — receiver GL impl, site id, four int args. */
    public static final String CONSULT_PROPERTY = "turboism.state-elision.consult";
    /** {@code (Ljava/lang/Object;I)V} — receiver GL impl, invalidator site id. */
    public static final String INVALIDATE_PROPERTY = "turboism.state-elision.invalidate";
    /** {@code (Ljava/lang/Object;)V} — receiver GL impl after a tracked call threw. */
    public static final String EXCEPTION_PROPERTY = "turboism.state-elision.exception";
    /** {@code java.util.function.Consumer<Boolean>} — leg-level elision gate. */
    public static final String GATE_PROPERTY = "turboism.state-elision.gate";
    /** {@code java.util.function.Supplier<Map<String,Long>>} — stats snapshot. */
    public static final String STATS_PROPERTY = "turboism.state-elision.stats";

    private final RedundantStateTracker tracker;
    private final MethodHandle consult;
    private final MethodHandle invalidate;
    private final MethodHandle exception;

    public RedundantStateElisionBridge() throws ReflectiveOperationException {
        this(new RedundantStateTracker());
    }

    RedundantStateElisionBridge(final RedundantStateTracker tracker)
            throws ReflectiveOperationException {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        final MethodHandles.Lookup lookup = MethodHandles.lookup();
        consult = lookup.bind(tracker, "consult",
            MethodType.methodType(boolean.class, Object.class, int.class,
                int.class, int.class, int.class, int.class));
        invalidate = lookup.bind(tracker, "invalidate",
            MethodType.methodType(void.class, Object.class, int.class));
        exception = lookup.bind(tracker, "exception",
            MethodType.methodType(void.class, Object.class));
    }

    /** Publishes all slots; the transformer consults them via exact invoke. */
    public void install() {
        final Properties properties = System.getProperties();
        properties.put(CONSULT_PROPERTY, consult);
        properties.put(INVALIDATE_PROPERTY, invalidate);
        properties.put(EXCEPTION_PROPERTY, exception);
        properties.put(GATE_PROPERTY,
            (java.util.function.Consumer<Boolean>) tracker::setArmed);
        properties.put(STATS_PROPERTY,
            (Supplier<Map<String, Long>>) () -> tracker.snapshot(tracker.armed()));
    }

    /** Removes every published slot. */
    public void uninstall() {
        final Properties properties = System.getProperties();
        properties.remove(CONSULT_PROPERTY);
        properties.remove(INVALIDATE_PROPERTY);
        properties.remove(EXCEPTION_PROPERTY);
        properties.remove(GATE_PROPERTY);
        properties.remove(STATS_PROPERTY);
    }

    /** Exposes the tracker for installer wiring (invalidator site names). */
    public RedundantStateTracker tracker() {
        return tracker;
    }
}
