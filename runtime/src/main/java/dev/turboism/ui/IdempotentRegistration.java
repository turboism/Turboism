package dev.turboism.ui;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ensures a host or contribution registration is closed at most once.
 *
 * <p>Runtime UI host services auto-enroll registrations in {@link dev.turboism.sdk.plugin.DisposableScope}.
 * Official plugins may also enroll the returned handle for SDK stub hosts that do not auto-scope.
 * Real host registrations must remain safe under that dual-close pattern.</p>
 */
final class IdempotentRegistration implements Registration {

    private final Registration delegate;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private IdempotentRegistration(final Registration delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    static Registration of(final Registration delegate) {
        if (delegate instanceof IdempotentRegistration) {
            return delegate;
        }
        return new IdempotentRegistration(delegate);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            delegate.close();
        }
    }

    boolean isClosed() {
        return closed.get();
    }
}
