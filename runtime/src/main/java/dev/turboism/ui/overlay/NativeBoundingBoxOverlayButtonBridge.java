package dev.turboism.ui.overlay;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Loader-neutral System-properties ingress for the exact bounding-box update augmentation.
 *
 * <p>Transformed host bytecode never references Turboism classes; it reads a JDK-only
 * {@link BiFunction} (custom button entities) and a JDK-only {@link Consumer} (bounded
 * diagnostics) from {@link System#getProperties()}. Missing, replaced, malformed or
 * throwing callbacks fail open inside the transformed host code.</p>
 */
public final class NativeBoundingBoxOverlayButtonBridge {

    static final String SETUP_PROPERTY = "turboism.bounding-box-overlay.buttons";
    static final String FAILURE_PROPERTY = "turboism.bounding-box-overlay.setup-failure";

    private static final long FAILURE_REPORT_INTERVAL_NANOS = 30_000_000_000L;
    private static final AtomicReference<SetupHandler> HANDLER = new AtomicReference<>();
    private static final AtomicLong NEXT_GENERATION = new AtomicLong();
    private static final AtomicLong ACTIVE_GENERATION = new AtomicLong();
    private static final Object[] EMPTY_BUTTONS = new Object[0];
    private static final AtomicLong LAST_FAILURE_REPORT = new AtomicLong(Long.MIN_VALUE);

    private NativeBoundingBoxOverlayButtonBridge() {
    }

    /**
     * Installs the JDK-only callback properties and returns a registration whose close
     * identity-removes only this installation's property values.
     */
    static Registration install(final SetupHandler handler) {
        final SetupHandler requested = Objects.requireNonNull(handler, "handler");
        final long generation = NEXT_GENERATION.updateAndGet(previous ->
            previous == Long.MAX_VALUE ? 1L : previous + 1L
        );
        if (!HANDLER.compareAndSet(null, requested)) {
            throw new IllegalStateException("bounding-box overlay bridge is already installed");
        }
        final BiFunction<Object, Object, Object> setup = (overlay, scene) ->
            isGenerationActive(generation)
                ? requested.customButtonEntities(overlay, scene)
                : EMPTY_BUTTONS;
        final Consumer<Object> failure = NativeBoundingBoxOverlayButtonBridge::reportSetupFailure;
        final Properties properties = System.getProperties();
        try {
            synchronized (properties) {
                if (properties.containsKey(SETUP_PROPERTY)
                    || properties.containsKey(FAILURE_PROPERTY)) {
                    throw new IllegalStateException(
                        "bounding-box overlay callback property is already installed"
                    );
                }
                properties.put(SETUP_PROPERTY, setup);
                properties.put(FAILURE_PROPERTY, failure);
                ACTIVE_GENERATION.set(generation);
            }
        } catch (RuntimeException | Error installFailure) {
            ACTIVE_GENERATION.compareAndSet(generation, 0L);
            synchronized (properties) {
                removeExpected(properties, SETUP_PROPERTY, setup, installFailure);
                removeExpected(properties, FAILURE_PROPERTY, failure, installFailure);
            }
            HANDLER.compareAndSet(requested, null);
            throw installFailure;
        }
        return () -> {
            ACTIVE_GENERATION.compareAndSet(generation, 0L);
            Throwable cleanupFailure = null;
            synchronized (properties) {
                cleanupFailure = removeExpected(properties, SETUP_PROPERTY, setup, cleanupFailure);
                cleanupFailure = removeExpected(properties, FAILURE_PROPERTY, failure, cleanupFailure);
            }
            HANDLER.compareAndSet(requested, null);
            rethrowCleanupFailure(cleanupFailure);
        };
    }

    /** Makes installed callbacks inert before host-bytecode restoration. */
    static void deactivateCallbacks() {
        ACTIVE_GENERATION.set(0L);
    }

    static long activeGeneration() {
        return ACTIVE_GENERATION.get();
    }

    static boolean isGenerationActive(final long generation) {
        return generation != 0L && ACTIVE_GENERATION.get() == generation;
    }

    private static Throwable removeExpected(
        final Properties properties,
        final String key,
        final Object expected,
        final Throwable prior
    ) {
        try {
            properties.remove(key, expected);
            return prior;
        } catch (RuntimeException | Error failure) {
            if (prior == null) {
                return failure;
            }
            prior.addSuppressed(failure);
            return prior;
        }
    }

    private static void rethrowCleanupFailure(final Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /** Rate-limited structured diagnostics for augmentation/setup failures. */

    private static void reportSetupFailure(final Object throwable) {
        final long now = System.nanoTime();
        final long previous = LAST_FAILURE_REPORT.get();
        if (!shouldReport(now, previous)
            || !LAST_FAILURE_REPORT.compareAndSet(previous, now)) {
            return;
        }
        final String detail = throwable == null
            ? "unknown"
            : throwable.getClass().getName()
                + (throwable instanceof Throwable t && t.getMessage() != null
                    ? ": " + t.getMessage()
                    : "");
        System.err.println("Turboism bounding-box overlay augmentation failed safely: " + detail);
    }

    /**
     * Overflow-safe first/burst/later report decision: the sentinel {@code Long.MIN_VALUE}
     * always reports, and later comparisons use unsigned elapsed-time arithmetic so a
     * {@code nanoTime} wrap cannot suppress a report. Package-private deterministic seam.
     */
    static boolean shouldReport(final long now, final long previous) {
        return previous == Long.MIN_VALUE
            || Long.compareUnsigned(now - previous, FAILURE_REPORT_INTERVAL_NANOS) >= 0;
    }

    @FunctionalInterface
    public interface SetupHandler {
        Object[] customButtonEntities(Object overlay, Object sceneGraph);
    }
}
