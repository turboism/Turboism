package dev.turboism.sdk.performance;

import dev.turboism.sdk.plugin.Registration;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Permission-checked service for reading real-time performance statistics of
 * the Cubism editor process.
 *
 * <p>CPU and JVM memory are measured with the JDK management APIs; FPS is
 * derived from a renderScene call counter that the runtime instruments while
 * sampling is active. When the service is unavailable (no host, unsupported
 * host version, or the plugin is not granted the performance permission) the
 * runtime returns {@link #unavailable()} so callers fail closed without host
 * exceptions.
 */
public interface PerformanceProbeService {

    /**
     * Returns one current snapshot. CPU/FPS values are real measurements;
     * {@code diskReadBytes}/{@code diskWriteBytes} are unbound placeholders
     * (no disk I/O collection in this phase).
     */
    PerformanceSnapshot snapshot();

    /**
     * Registers a periodic consumer. Sampling runs on a background thread and
     * never blocks the host UI thread; callbacks are invoked on the sampling
     * thread. Closing the returned registration stops callbacks, releases the
     * sampling loop, and (when this was the last consumer) unmounts the
     * renderScene counter and restores the instrumented bytecode.
     *
     * @param interval positive sampling interval (1 second is the runtime
     *                 chart cadence)
     */
    Registration sample(Duration interval, Consumer<PerformanceSnapshot> consumer);

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Fail-closed default instance. {@link #snapshot()} and {@link #sample}
     * throw {@link UnsupportedOperationException} (SDK-level, never a host
     * exception); plugins that require statistics should degrade gracefully.
     */
    static PerformanceProbeService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: unsupported calls throw a stable {@link UnsupportedOperationException}. */
    enum Unavailable implements PerformanceProbeService {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public PerformanceSnapshot snapshot() {
            throw new UnsupportedOperationException("performance probe service is not available");
        }

        @Override public Registration sample(
            final Duration interval,
            final Consumer<PerformanceSnapshot> consumer
        ) {
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(consumer, "consumer");
            throw new UnsupportedOperationException("performance probe service is not available");
        }
    }
}
