package dev.turboism.sdk.ui;

import dev.turboism.sdk.plugin.Registration;

/**
 * Handle for a live native Cubism drawing-area hint.
 *
 * <p>The handle mirrors the native hint lifecycle. Closing it (or calling
 * {@link #dismiss()}) clears the hint immediately. {@link #renew()} re-arms the
 * hint's native timeout, which is how native code keeps a condition-driven message
 * on screen: re-showing the same keyed hint refreshes its deadline instead of
 * creating a second hint.</p>
 *
 * <p>After {@link #close()} the handle is spent; later {@link #renew()} calls do
 * nothing and {@link #close()} stays idempotent.</p>
 */
public interface CanvasHintHandle extends Registration {

    /**
     * Re-arms the native timeout so the hint stays visible for another full
     * duration, keeping its message, key, click action, and position.
     *
     * <p>Does nothing once the handle has been closed.</p>
     */
    void renew();

    /** Clears the hint now; equivalent to {@link #close()}. */
    default void dismiss() {
        close();
    }
}
