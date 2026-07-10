package dev.turboism.ui;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Ensures a host or contribution registration is closed once after a successful delegate close.
 *
 * <p>Runtime UI host services auto-enroll registrations in {@link dev.turboism.sdk.plugin.DisposableScope}.
 * Official plugins may also enroll the returned handle for SDK stub hosts that do not auto-scope.
 * Concurrent callers share one close attempt; a failed delegate close remains retryable.</p>
 */
final class IdempotentRegistration implements Registration {

    private final Registration delegate;
    private State state = State.OPEN;
    private CompletableFuture<Void> inFlight;
    private Thread closeOwner;

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
        final CloseAttempt attempt = beginClose();
        if (attempt.owner()) {
            executeClose(attempt.completion());
            return;
        }
        await(attempt.completion());
    }

    synchronized boolean isClosed() {
        return state == State.CLOSED;
    }

    private synchronized CloseAttempt beginClose() {
        if (state == State.CLOSED) {
            return new CloseAttempt(false, CompletableFuture.completedFuture(null));
        }
        if (state == State.CLOSING) {
            if (closeOwner == Thread.currentThread()) {
                return new CloseAttempt(false, CompletableFuture.completedFuture(null));
            }
            return new CloseAttempt(false, inFlight);
        }
        state = State.CLOSING;
        closeOwner = Thread.currentThread();
        inFlight = new CompletableFuture<>();
        return new CloseAttempt(true, inFlight);
    }

    private void executeClose(final CompletableFuture<Void> completion) {
        try {
            delegate.close();
            synchronized (this) {
                state = State.CLOSED;
                closeOwner = null;
            }
            completion.complete(null);
        } catch (Throwable throwable) {
            synchronized (this) {
                state = State.OPEN;
                closeOwner = null;
                inFlight = null;
            }
            completion.completeExceptionally(throwable);
            rethrow(throwable);
        }
    }

    private static void await(final CompletableFuture<Void> completion) {
        try {
            completion.join();
        } catch (CompletionException exception) {
            final Throwable cause = exception.getCause();
            rethrow(cause);
        }
    }

    private static void rethrow(final Throwable throwable) {
        if (throwable instanceof Error error) {
            throw error;
        }
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new CompletionException(throwable);
    }

    private enum State {
        OPEN,
        CLOSING,
        CLOSED
    }

    private record CloseAttempt(boolean owner, CompletableFuture<Void> completion) {
    }
}
