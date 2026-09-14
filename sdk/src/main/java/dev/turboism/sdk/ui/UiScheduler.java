package dev.turboism.sdk.ui;

import dev.turboism.sdk.plugin.Registration;
import java.time.Duration;

/**
 * Scheduler for UI-thread work.
 */
public interface UiScheduler {

    /**
     * Schedules {@code work} on the UI thread. Closing the returned {@link Registration}
     * cancels the pending work when it has not run yet.
     */
    Registration runOnUiThread(Runnable work);

    /**
     * Schedules {@code work} on the UI thread after {@code delay}. Closing the returned
     * {@link Registration} cancels the pending work when it has not run yet.
     */
    Registration runOnUiThreadLater(Runnable work, Duration delay);
}
