package dev.turboism.sdk.ui;

import dev.turboism.sdk.plugin.Registration;
import java.time.Duration;

/**
 * Scheduler for UI-thread work.
 */
public interface UiScheduler {

    Registration runOnUiThread(Runnable work);

    Registration runOnUiThreadLater(Runnable work, Duration delay);
}
