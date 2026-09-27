package dev.turboism.performance;

import dev.turboism.adapter.cubism.performance.PerformanceFpsHook;
import dev.turboism.adapter.cubism.performance.PerformanceFpsHookRegistry;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.performance.PerformanceSnapshot;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for real sampler subscription identity and independent delivery cadence. */
class PerformanceSubscriptionsTest {
    private final AtomicInteger installs = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final PerformanceFpsHook hook = new PerformanceFpsHook() {
        @Override public void install() { installs.incrementAndGet(); }
        @Override public boolean isInstalled() { return installs.get() > closes.get(); }
        @Override public long renderSceneCalls() { return 0; }
        @Override public void close() { closes.incrementAndGet(); }
    };

    @BeforeEach void publish() { PerformanceFpsHookRegistry.publish(hook); }
    @AfterEach void clear() { PerformanceFpsHookRegistry.clear(hook); }

    private static RuntimePerformanceProbeService service() {
        return new RuntimePerformanceProbeService("subscription-test", PermissionChecker.allowAll(), Clock.systemUTC());
    }

    @Test void oldHandleCannotCancelNewRegistrationWithIdenticalConsumer() throws Exception {
        try (var service = service()) {
            Consumer<PerformanceSnapshot> same = ignored -> { };
            Registration old = service.sample(Duration.ofHours(1), same);
            old.close();
            Registration current = service.sample(Duration.ofHours(1), same);
            old.close();
            assertEquals(1, closes.get(), "old handle must not unmount the new sampling generation");
            current.close();
            assertEquals(2, closes.get());
        }
    }

    @Test void slowSubscriberDoesNotInheritEarlierFastCadence() throws Exception {
        try (var service = service()) {
            var fastTicks = new CountDownLatch(4);
            var slowTicks = new AtomicInteger();
            service.sample(Duration.ofMillis(30), ignored -> fastTicks.countDown());
            service.sample(Duration.ofHours(1), ignored -> slowTicks.incrementAndGet());
            assertTrue(fastTicks.await(3, TimeUnit.SECONDS));
            assertEquals(0, slowTicks.get(), "each subscriber must own its delivery deadline");
            assertEquals(1, installs.get());
        }
    }

    @Test void fasterSubscriberRetimesAlreadySlowCollectionWithoutRemounting() throws Exception {
        try (var service = service()) {
            var fastTicks = new CountDownLatch(2);
            var slowTicks = new AtomicInteger();
            service.sample(Duration.ofHours(1), ignored -> slowTicks.incrementAndGet());
            service.sample(Duration.ofMillis(30), ignored -> fastTicks.countDown());
            assertTrue(fastTicks.await(3, TimeUnit.SECONDS), "late fast subscriber must not wait for first subscriber's hour");
            assertEquals(0, slowTicks.get());
            assertEquals(1, installs.get(), "retiming is not a new hook session");
        }
    }

    @Test void retimingKeepsAnExistingSubscriberDeadline() throws Exception {
        try (var service = service()) {
            final Registration slow = service.sample(Duration.ofHours(1), ignored -> { });
            final Registration fast = service.sample(Duration.ofMinutes(30), ignored -> { });
            // Move the stored deadline as a controlled clock advance, without a long sleep.
            // The real timer must use this existing deadline when the fast subscriber leaves.
            final Field deadline = slow.getClass().getDeclaredField("nextDeliveryNanos");
            deadline.setAccessible(true);
            deadline.setLong(slow, System.nanoTime() + TimeUnit.MINUTES.toNanos(5));
            fast.close();
            final Field task = RuntimePerformanceProbeService.class.getDeclaredField("samplingTask");
            task.setAccessible(true);
            final long delay = ((ScheduledFuture<?>) task.get(service)).getDelay(TimeUnit.MINUTES);
            assertTrue(delay <= 5, "retiming must not reset the remaining deadline to a full hour");
            assertEquals(1, installs.get());
        }
    }

    @Test void earlierGenerationCallbackCannotStarveAReplacementSampler() throws Exception {
        try (var service = service()) {
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            final CountDownLatch replacementTick = new CountDownLatch(1);
            final Registration old = service.sample(Duration.ofMillis(20), ignored -> {
                entered.countDown();
                boolean interrupted = false;
                for (;;) {
                    try {
                        if (release.await(3, TimeUnit.SECONDS)) break;
                        break;
                    } catch (InterruptedException failure) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            });
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                old.close();
                service.sample(Duration.ofMillis(20), ignored -> replacementTick.countDown());
                assertTrue(replacementTick.await(1, TimeUnit.SECONDS),
                    "a callback admitted by the retired generation must not own the new timer");
            } finally {
                release.countDown();
            }
        }
    }

    @Test void closedServiceCannotBeResurrected() {
        var service = service();
        service.close();
        try {
            assertThrows(IllegalStateException.class, () -> service.sample(Duration.ofHours(1), ignored -> { }));
            assertThrows(IllegalStateException.class, service::snapshot);
        } finally {
            service.close();
        }
    }

    @Test void positiveSubMillisecondIntervalDoesNotLeakAMountedHook() throws Exception {
        try (var service = service()) {
            var tick = new CountDownLatch(1);
            Registration registration = service.sample(Duration.ofNanos(500_000), ignored -> tick.countDown());
            assertTrue(tick.await(3, TimeUnit.SECONDS));
            registration.close();
            assertEquals(1, installs.get());
            assertEquals(1, closes.get());
        }
    }

    @Test void callbackMayCloseItselfWithoutHoldingGlobalLifecycleLock() throws Exception {
        try (var service = service()) {
            var handle = new AtomicReference<Registration>();
            var finished = new CountDownLatch(1);
            handle.set(service.sample(Duration.ofMillis(60), ignored -> {
                Registration current = handle.get();
                if (current != null) {
                    current.close();
                    finished.countDown();
                }
            }));
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertFalse(hook.isInstalled());
        }
    }

    @Test void cancellationSuppressesCallbackAlreadySelectedBehindABlockedConsumer() throws Exception {
        try (var service = service()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var victimCalls = new AtomicInteger();
            service.sample(Duration.ofMillis(40), ignored -> {
                entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            Registration victim = service.sample(Duration.ofMillis(40), ignored -> victimCalls.incrementAndGet());
            var afterVictim = new CountDownLatch(1);
            service.sample(Duration.ofMillis(40), ignored -> afterVictim.countDown());
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                victim.close();
            } finally {
                release.countDown();
            }
            // Observe a later callback, so an assertion cannot race an old dispatch snapshot.
            assertTrue(afterVictim.await(3, TimeUnit.SECONDS));
            assertEquals(0, victimCalls.get());
        }
    }
}
