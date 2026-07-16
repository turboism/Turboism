package dev.turboism.core.runtime;

import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class RuntimeTimerToken implements RuntimeTimerHandle {

    private final Consumer<RuntimeTimerToken> release;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();

    RuntimeTimerToken(final Consumer<RuntimeTimerToken> release) {
        this.release = Objects.requireNonNull(release, "release");
    }

    void bind(final ScheduledFuture<?> scheduledFuture) {
        if (!future.compareAndSet(null, Objects.requireNonNull(scheduledFuture, "scheduledFuture"))) {
            throw new IllegalStateException("Runtime timer token is already bound");
        }
        if (!active.get()) {
            scheduledFuture.cancel(false);
        }
    }

    void executed() {
        releaseOnce();
    }

    void rejected() {
        releaseOnce();
    }

    @Override
    public boolean cancel() {
        final ScheduledFuture<?> scheduledFuture = future.get();
        final boolean canceled = scheduledFuture == null || scheduledFuture.cancel(false);
        return canceled && releaseOnce();
    }

    private boolean releaseOnce() {
        if (!active.compareAndSet(true, false)) {
            return false;
        }
        release.accept(this);
        return true;
    }
}
