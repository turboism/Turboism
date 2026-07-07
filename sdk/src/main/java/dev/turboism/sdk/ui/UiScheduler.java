package dev.turboism.sdk.ui;

/**
 * Scheduler for UI-thread work.
 */
public interface UiScheduler {

    void runOnUiThread(Runnable work);

    void runOnUiThreadLater(Runnable work);
}
