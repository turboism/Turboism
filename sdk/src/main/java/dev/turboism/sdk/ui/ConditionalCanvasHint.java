package dev.turboism.sdk.ui;

import dev.turboism.sdk.plugin.Registration;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Keeps a native Cubism hint on screen for exactly as long as a condition holds.
 *
 * <p>This is the SDK-level equivalent of the native pattern where a re-validation
 * routine re-issues the same keyed hint while a problem persists, and stops issuing
 * it once the problem is gone. The hint therefore survives while the condition is
 * true and clears itself one linger duration after the condition first becomes
 * false, without the caller tracking a handle.</p>
 *
 * <p>The returned registration stops the watch and clears the hint immediately. The
 * condition is evaluated on the plugin's own UI scheduler, so the watch runs on the
 * same thread the plugin already uses for UI work; the condition must be cheap and
 * must not block.</p>
 */
public final class ConditionalCanvasHint {

    /** How often the condition is re-evaluated while the hint is being watched. */
    public static final Duration DEFAULT_CADENCE = Duration.ofSeconds(1);

    private ConditionalCanvasHint() {
    }

    /**
     * Shows {@code notification} and keeps renewing it while {@code condition} holds.
     *
     * <p>The hint is renewed at {@link #DEFAULT_CADENCE} until the condition reports
     * false; it is then cleared and the watch stops. Renewing preserves the
     * notification's message, key, click action, and position.</p>
     *
     * @param scheduler the plugin's UI scheduler, used to evaluate the condition
     * @param uiHost the UI host that owns the native hint
     * @param notification the hint to keep alive
     * @param condition evaluated on each tick; the hint stays while it returns true
     * @return a handle that stops the watch and clears the hint
     * @throws NullPointerException when any argument is null
     */
    public static Registration whileTrue(
        final UiScheduler scheduler,
        final UiHostCapabilityService uiHost,
        final CanvasHintNotification notification,
        final BooleanSupplier condition
    ) {
        return whileTrue(scheduler, uiHost, notification, condition, DEFAULT_CADENCE);
    }

    /**
     * Shows {@code notification} and keeps renewing it while {@code condition} holds,
     * re-evaluating the condition every {@code cadence}.
     *
     * @param scheduler the plugin's UI scheduler, used to evaluate the condition
     * @param uiHost the UI host that owns the native hint
     * @param notification the hint to keep alive
     * @param condition evaluated on each tick; the hint stays while it returns true
     * @param cadence how often the condition is re-evaluated
     * @return a handle that stops the watch and clears the hint
     * @throws NullPointerException when any argument is null
     * @throws IllegalArgumentException when {@code cadence} is zero or negative
     */
    public static Registration whileTrue(
        final UiScheduler scheduler,
        final UiHostCapabilityService uiHost,
        final CanvasHintNotification notification,
        final BooleanSupplier condition,
        final Duration cadence
    ) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(uiHost, "uiHost");
        Objects.requireNonNull(notification, "notification");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(cadence, "cadence");
        if (cadence.isZero() || cadence.isNegative()) {
            throw new IllegalArgumentException("cadence must be positive");
        }

        final CanvasHintHandle handle = uiHost.notifyCanvasHint(notification);
        final Watch watch = new Watch(scheduler, handle, condition, cadence);
        watch.arm();
        return watch;
    }

    /** One condition watch; owns the handle and the pending tick. */
    private static final class Watch implements Registration {

        private final UiScheduler scheduler;
        private final CanvasHintHandle handle;
        private final BooleanSupplier condition;
        private final Duration cadence;
        private boolean stopped;
        private Registration pendingTick;

        private Watch(
            final UiScheduler scheduler,
            final CanvasHintHandle handle,
            final BooleanSupplier condition,
            final Duration cadence
        ) {
            this.scheduler = scheduler;
            this.handle = handle;
            this.condition = condition;
            this.cadence = cadence;
        }

        private void arm() {
            pendingTick = scheduler.runOnUiThreadLater(this::tick, cadence);
        }

        private void tick() {
            if (stopped) {
                return;
            }
            if (condition.getAsBoolean()) {
                handle.renew();
                arm();
                return;
            }
            // The condition cleared: release the hint and stop watching. The native
            // linger decides when the message actually leaves the screen.
            stopped = true;
            handle.dismiss();
        }

        @Override
        public void close() {
            if (stopped) {
                return;
            }
            stopped = true;
            if (pendingTick != null) {
                pendingTick.close();
                pendingTick = null;
            }
            handle.dismiss();
        }
    }
}
