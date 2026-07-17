package dev.turboism.core.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal lifecycle lease that prevents the shared runtime scheduler from
 * shutting down while a plugin-scoped task facade still owns work or
 * completion continuations.
 */
public final class RuntimeSchedulerLease implements AutoCloseable {

    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    RuntimeSchedulerLease(final Runnable release) {
        this.release = Objects.requireNonNull(release, "release");
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            release.run();
        }
    }
}
