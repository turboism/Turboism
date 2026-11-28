package dev.turboism.adapter.cubism.optimization.physics;

import dev.turboism.core.runtime.work.FatalErrors;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Publishes runtime enablement predicates for the woven physics simulation fast path.
 *
 * <p>Rewritten host classes consult {@link #ACTIVE_PROPERTY} as a JDK {@link Predicate} loaded
 * via {@code ConstantDynamic}, ensuring rollback and validation toggling require zero direct
 * references to Turboism runtime classes.</p>
 */
public final class PhysicsAccelerationBridge implements AutoCloseable {

    /** Shared startup toggle property gating edit-mode acceleration hooks. */
    public static final String ENABLE_PROPERTY = PhysicsAccelerationTarget.ENABLE_PROPERTY;

    /** Explicit validation override property isolated from launcher policy. */
    public static final String VALIDATION_PROPERTY = PhysicsAccelerationTarget.VALIDATION_PROPERTY;

    /** System property slot holding the active {@link Predicate} gate for woven physics methods. */
    public static final String ACTIVE_PROPERTY = "turboism.physics-acceleration.active";

    private static final Set<PhysicsAccelerationBridge> LIVE_BRIDGES =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile boolean configuredDefault = true;
    private static volatile boolean resolvedEnabled = computeEnabled();

    private final AtomicBoolean active = new AtomicBoolean();
    private volatile boolean operational;
    private final Predicate<Object> activeGate = ignored -> operational;
    private Properties installedProperties;
    private Object previousActiveSlot;

    /** Constructs a physics acceleration bridge ready to install on a host launch. */
    public PhysicsAccelerationBridge() {}

    /**
     * Records the policy-derived default activation state evaluated when system overrides are unset.
     *
     * @param enabled policy toggle state for the current launch
     */
    public static void setConfiguredDefault(final boolean enabled) {
        configuredDefault = enabled;
        refreshEnabled();
    }

    /**
     * Re-evaluates system override properties against {@code configuredDefault} and refreshes live bridges.
     *
     * @return updated activation state visible to {@link #isEnabled()} and installed gates
     */
    public static boolean refreshEnabled() {
        final boolean enabled = computeEnabled();
        resolvedEnabled = enabled;
        synchronized (LIVE_BRIDGES) {
            for (final PhysicsAccelerationBridge bridge : LIVE_BRIDGES) {
                bridge.updateOperational();
            }
        }
        return enabled;
    }

    /**
     * Reports whether physics simulation acceleration should run under the cached launch and
     * override configuration.
     *
     * @return {@code true} when active override or policy default enables acceleration
     */
    public static boolean isEnabled() {
        return resolvedEnabled;
    }

    private static boolean computeEnabled() {
        try {
            final String validation = System.getProperty(VALIDATION_PROPERTY);
            if (validation != null) {
                return Boolean.parseBoolean(validation);
            }
            final String property = System.getProperty(ENABLE_PROPERTY);
            return property == null ? configuredDefault : Boolean.parseBoolean(property);
        } catch (final SecurityException denied) {
            return false;
        }
    }

    /**
     * Publishes the active gate predicate into {@link System#getProperties()} before weaving.
     */
    public synchronized void install() {
        final Properties properties = System.getProperties();
        if (installedProperties != null) {
            return;
        }
        refreshEnabled();
        previousActiveSlot = properties.get(ACTIVE_PROPERTY);
        active.set(true);
        LIVE_BRIDGES.add(this);
        updateOperational();
        properties.put(ACTIVE_PROPERTY, activeGate);
        installedProperties = properties;
    }

    @Override
    public synchronized void close() {
        active.set(false);
        operational = false;
        LIVE_BRIDGES.remove(this);
        final Properties properties = installedProperties;
        if (properties == null) {
            return;
        }
        installedProperties = null;
        try {
            if (properties.get(ACTIVE_PROPERTY) == activeGate) {
                if (previousActiveSlot == null) {
                    properties.remove(ACTIVE_PROPERTY);
                } else {
                    properties.put(ACTIVE_PROPERTY, previousActiveSlot);
                }
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        } finally {
            previousActiveSlot = null;
        }
    }

    private void updateOperational() {
        operational = active.get() && resolvedEnabled;
    }
}
