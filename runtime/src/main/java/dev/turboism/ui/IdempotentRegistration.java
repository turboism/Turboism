package dev.turboism.ui;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    /** Bounded wait for a concurrent close owner; the EDT must never block indefinitely. */
    private static final long CLOSE_JOIN_TIMEOUT_MILLIS = 5_000L;

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

    /**
     * Waits for a concurrent close attempt with a bounded timeout (fail closed): a delegate close
     * that does not finish within {@link #CLOSE_JOIN_TIMEOUT_MILLIS} raises {@link IllegalStateException}
     * instead of blocking the caller (potentially the EDT) indefinitely.
     */
    private static void await(final CompletableFuture<Void> completion) {
        try {
            completion.get(CLOSE_JOIN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException(
                "registration close did not finish within " + CLOSE_JOIN_TIMEOUT_MILLIS + "ms", timeout);
        } catch (ExecutionException exception) {
            final Throwable cause = exception.getCause();
            rethrow(cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting registration close", interrupted);
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
