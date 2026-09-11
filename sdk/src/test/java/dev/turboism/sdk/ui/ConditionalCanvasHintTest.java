package dev.turboism.sdk.ui;

import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConditionalCanvasHintTest {

    @Test
    void renewsWhileTheConditionHoldsAndClearsTheHintOnceItFails() {
        FakeHost recorder = new FakeHost();
        UiHostCapabilityService host = host(recorder);
        FakeScheduler scheduler = new FakeScheduler();
        AtomicBoolean problem = new AtomicBoolean(true);

        Registration watch = ConditionalCanvasHint.whileTrue(
            scheduler, host,
            new CanvasHintNotification("screen-color", "Incompatible", 1.0f),
            problem::get,
            Duration.ofSeconds(1)
        );

        assertEquals(1, recorder.shown, "the hint is shown immediately");

        scheduler.tick();
        scheduler.tick();
        assertEquals(2, recorder.renewed, "each tick while true renews the hint");
        assertEquals(0, recorder.dismissed);

        problem.set(false);
        scheduler.tick();
        assertEquals(1, recorder.dismissed, "a false condition clears the hint");
        assertEquals(2, recorder.renewed, "no further renew happens after the condition fails");

        scheduler.tick();
        assertEquals(2, recorder.renewed, "the watch stops once the condition has failed");
    }

    @Test
    void closingTheWatchStopsTickingAndClearsTheHint() {
        FakeHost recorder = new FakeHost();
        UiHostCapabilityService host = host(recorder);
        FakeScheduler scheduler = new FakeScheduler();

        Registration watch = ConditionalCanvasHint.whileTrue(
            scheduler, host,
            new CanvasHintNotification("screen-color", "Incompatible", 1.0f),
            () -> true,
            Duration.ofSeconds(1)
        );
        watch.close();

        assertEquals(1, recorder.dismissed);
        assertEquals(1, scheduler.cancelled, "the pending tick must be cancelled");

        scheduler.tick();
        assertEquals(0, recorder.renewed, "a closed watch must not renew");
    }

    @Test
    void repeatedTicksRescheduleAndRejectANonPositiveCadence() {
        FakeHost recorder = new FakeHost();
        UiHostCapabilityService host = host(recorder);
        FakeScheduler scheduler = new FakeScheduler();

        ConditionalCanvasHint.whileTrue(
            scheduler, host,
            new CanvasHintNotification("screen-color", "Incompatible", 1.0f),
            () -> true,
            Duration.ofSeconds(1)
        );
        assertEquals(1, scheduler.scheduled);

        scheduler.tick();
        assertEquals(2, scheduler.scheduled, "each renew schedules the next evaluation");

        assertThrows(IllegalArgumentException.class, () -> ConditionalCanvasHint.whileTrue(
            scheduler, host,
            new CanvasHintNotification("screen-color", "Incompatible", 1.0f),
            () -> true,
            Duration.ZERO
        ));
    }

    /** Scheduler whose pending work runs only when the test ticks it. */
    private static final class FakeScheduler implements UiScheduler {

        private Runnable pending;
        private int scheduled;
        private int cancelled;

        @Override
        public Registration runOnUiThread(final Runnable work) {
            throw new UnsupportedOperationException("not used by the watch");
        }

        @Override
        public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
            pending = work;
            scheduled++;
            return () -> {
                cancelled++;
                pending = null;
            };
        }

        private void tick() {
            Runnable work = pending;
            pending = null;
            if (work != null) {
                work.run();
            }
        }
    }

    /** Host that counts show/renew/dismiss instead of touching a native hint. */
    private static UiHostCapabilityService host(final FakeHost recorder) {
        return (UiHostCapabilityService) java.lang.reflect.Proxy.newProxyInstance(
            UiHostCapabilityService.class.getClassLoader(),
            new Class<?>[] { UiHostCapabilityService.class },
            (proxy, method, args) -> {
                // Checked before isDefault(): notifyCanvasHint is itself a default method.
                if ("notifyCanvasHint".equals(method.getName())) {
                    recorder.shown++;
                    return recorder.handle();
                }
                if (method.isDefault()) {
                    return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    /** Counts the lifecycle calls the watch makes on the hint handle. */
    private static final class FakeHost {

        private int shown;
        private int renewed;
        private int dismissed;

        private CanvasHintHandle handle() {
            return new CanvasHintHandle() {
                @Override
                public void renew() {
                    renewed++;
                }

                @Override
                public void close() {
                    dismissed++;
                }
            };
        }
    }
}
