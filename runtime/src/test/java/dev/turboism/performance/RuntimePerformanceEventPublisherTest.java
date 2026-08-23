package dev.turboism.performance;

import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.performance.PerformanceSampleEvent;
import dev.turboism.sdk.performance.PerformanceSnapshot;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimePerformanceEventPublisherTest {

    @Test
    void coalescesIntermediateSamplesAndPublishesTheLatestSnapshot() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner observer = broker.admit("plugin.performance-observer");
        final AtomicReference<PerformanceSampleEvent> delivered = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        broker.subscribe(observer.key(), PerformanceSampleEvent.class, event -> {
            delivered.set(event);
            latch.countDown();
        });
        observer.activate();
        final RecordingProbe probe = new RecordingProbe();
        final RuntimePerformanceEventPublisher publisher =
            new RuntimePerformanceEventPublisher(
                probe,
                broker,
                scheduler,
                Duration.ofMillis(5),
                Duration.ofMillis(30)
            );

        probe.publish(PerformanceSnapshot.of(1L, 1.0, 1L, 1L, 1.0, 1L));
        probe.publish(PerformanceSnapshot.of(2L, 2.0, 2L, 2L, 2.0, 2L));
        probe.publish(PerformanceSnapshot.of(3L, 3.0, 3L, 3L, 3.0, 3L));

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(3L, delivered.get().snapshot().timestampEpochMs());
        assertEquals(2L, delivered.get().coalescedSamples());
        publisher.close();
        assertEquals(1, probe.closedRegistrations.get());
        scheduler.shutdown();
    }

    @Test
    void closeDropsPendingSampleAndRejectsLaterSourceCallbacks() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner observer = broker.admit("plugin.performance-close");
        final AtomicInteger deliveries = new AtomicInteger();
        broker.subscribe(
            observer.key(),
            PerformanceSampleEvent.class,
            ignored -> deliveries.incrementAndGet()
        );
        observer.activate();
        final RecordingProbe probe = new RecordingProbe();
        final RuntimePerformanceEventPublisher publisher =
            new RuntimePerformanceEventPublisher(
                probe,
                broker,
                scheduler,
                Duration.ofMillis(5),
                Duration.ofMillis(40)
            );

        probe.publish(PerformanceSnapshot.of(1L, 1.0, 1L, 1L, 1.0, 1L));
        publisher.close();
        probe.publish(PerformanceSnapshot.of(2L, 2.0, 2L, 2L, 2.0, 2L));
        Thread.sleep(100L);

        assertEquals(0, deliveries.get());
        scheduler.shutdown();
    }

    private static RuntimeScheduler scheduler() {
        final Clock clock = Clock.fixed(
            Instant.parse("2026-08-23T00:00:00Z"),
            ZoneOffset.UTC
        );
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, ignored -> { }, clock),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private static final class RecordingProbe implements PerformanceProbeService {
        private final AtomicInteger closedRegistrations = new AtomicInteger();
        private Consumer<PerformanceSnapshot> consumer;

        @Override
        public PerformanceSnapshot snapshot() {
            return PerformanceSnapshot.unavailable(0L);
        }

        @Override
        public Registration sample(
            final Duration interval,
            final Consumer<PerformanceSnapshot> consumer
        ) {
            this.consumer = consumer;
            return closedRegistrations::incrementAndGet;
        }

        void publish(final PerformanceSnapshot snapshot) {
            final Consumer<PerformanceSnapshot> current = consumer;
            if (current != null) {
                current.accept(snapshot);
            }
        }
    }
}
