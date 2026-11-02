package dev.turboism.test.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.time.Duration;
import java.util.Objects;

/**
 * {@link UiScheduler} fakes with explicit, easy-to-recognise semantics.
 *
 * <p>The default instance runs both {@code runOnUiThread} and {@code runOnUiThreadLater} work
 * immediately on the calling thread, mirroring a direct executor. Tests that need a different
 * delayed-work behaviour should use the named factories instead of writing another anonymous
 * implementation.
 */
public final class FakeDirectUiScheduler implements UiScheduler {

    private static final UiScheduler IMMEDIATE_ONLY = new ImmediateOnlyScheduler();
    private static final UiScheduler IMMEDIATE_ASSERTION_LATER = new ImmediateWithFailureScheduler(false, null);
    private static final UiScheduler IMMEDIATE_UNSUPPORTED_LATER = new ImmediateWithFailureScheduler(true, null);

    /** Returns a scheduler whose delayed work also runs immediately. */
    public static UiScheduler direct() {
        return new FakeDirectUiScheduler();
    }

    /** Returns a scheduler that runs immediate work and drops delayed work. */
    public static UiScheduler immediateOnly() {
        return IMMEDIATE_ONLY;
    }

    /** Returns a scheduler that runs immediate work and fails with a bare {@link AssertionError} on delay. */
    public static UiScheduler immediateWithAssertionLater() {
        return IMMEDIATE_ASSERTION_LATER;
    }

    /** Returns a scheduler that runs immediate work and fails with an {@link AssertionError} on delay. */
    public static UiScheduler immediateWithAssertionLater(final String message) {
        return new ImmediateWithFailureScheduler(false, message);
    }

    /** Returns a scheduler that runs immediate work and fails with {@link UnsupportedOperationException} on delay. */
    public static UiScheduler immediateWithUnsupportedLater() {
        return IMMEDIATE_UNSUPPORTED_LATER;
    }

    @Override
    public Registration runOnUiThread(Runnable work) {
        Objects.requireNonNull(work, "work").run();
        return () -> {};
    }

    @Override
    public Registration runOnUiThreadLater(Runnable work, Duration delay) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(work, "work").run();
        return () -> {};
    }

    private static final class ImmediateOnlyScheduler implements UiScheduler {
        @Override
        public Registration runOnUiThread(final Runnable work) {
            Objects.requireNonNull(work, "work").run();
            return () -> {};
        }

        @Override
        public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
            Objects.requireNonNull(work, "work");
            Objects.requireNonNull(delay, "delay");
            return () -> {};
        }
    }

    private static final class ImmediateWithFailureScheduler implements UiScheduler {
        private final boolean unsupported;
        private final String message;

        private ImmediateWithFailureScheduler(final boolean unsupported, final String message) {
            this.unsupported = unsupported;
            this.message = message;
        }

        @Override
        public Registration runOnUiThread(final Runnable work) {
            Objects.requireNonNull(work, "work").run();
            return () -> {};
        }

        @Override
        public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
            Objects.requireNonNull(work, "work");
            Objects.requireNonNull(delay, "delay");
            if (unsupported) {
                throw new UnsupportedOperationException();
            }
            throw message == null ? new AssertionError() : new AssertionError(message);
        }
    }
}
