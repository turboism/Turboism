package dev.turboism.core.runtime;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.plugin.Registration;
import java.util.List;

/** Closes registration lists in reverse order and preserves every cleanup failure. */
public final class Registrations {

    /**
     * Closes every registration in reverse order and throws the first failure with the rest
     * suppressed on it.
     *
     * @param registrations the registrations to close
     */
    public static void closeReverseThrowingFirst(final List<? extends Registration> registrations) {
        final RuntimeException first = closeReverseReturningFirst(registrations);
        if (first != null) {
            throw first;
        }
    }

    /**
     * Closes every registration in reverse order and merges each failure into {@code failure}
     * so a fatal JVM condition stays primary.
     *
     * @param registrations the registrations to close
     * @param failure the primary failure, nullable
     */
    public static void closeReverseSuppressing(
            final List<? extends Registration> registrations, final Throwable failure) {
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    /**
     * Closes every registration in reverse order and returns the first failure with the rest
     * suppressed on it.
     *
     * @param registrations the registrations to close
     * @return the first cleanup failure, or {@code null} when every close succeeded
     */
    public static RuntimeException closeReverseReturningFirst(final List<? extends Registration> registrations) {
        RuntimeException first = null;
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException failure) {
                if (first == null) {
                    first = failure;
                } else {
                    first.addSuppressed(failure);
                }
            }
        }
        return first;
    }

    /**
     * Closes every registration in reverse order and merges each failure into {@code failure}
     * so a fatal JVM condition stays primary.
     *
     * @param registrations the registrations to close
     * @param failure the primary failure, nullable
     * @return the prioritized failure, or {@code null} when nothing failed
     */
    public static Throwable closeReverseMergingFatal(
            final List<? extends Registration> registrations, final Throwable failure) {
        Throwable accumulated = failure;
        for (int index = registrations.size() - 1; index >= 0; index--) {
            try {
                registrations.get(index).close();
            } catch (RuntimeException | Error cleanupFailure) {
                // @containment-exempt: cleanup failure merged across all registrations with fatal prioritized
                accumulated = FatalErrors.mergeForCleanup(accumulated, cleanupFailure);
            }
        }
        return accumulated;
    }

    private Registrations() {}
}
