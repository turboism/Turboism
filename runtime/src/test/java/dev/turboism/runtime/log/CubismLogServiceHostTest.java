package dev.turboism.runtime.log;

import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.runtime.CubismLogBatchEvent;
import dev.turboism.sdk.runtime.CubismLogService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubismLogServiceHostTest {
    @Test
    void subscribersReceiveTheUnfilteredStreamUntilRegistrationCloses() {
        final CubismLogServiceHost host = new CubismLogServiceHost();
        final List<CubismLogService.LogEntry> entries = new ArrayList<>();
        final var registration = host.subscribe(entries::add);
        host.setFilter(new CubismLogService.LogFilter(false, false, true, "needle"));

        host.publish(CubismLogService.LogLevel.INFO, "not filtered for subscribers", 1L);
        assertEquals(1, entries.size());
        assertEquals("needle", host.filter().keyword());

        registration.close();
        host.publish(CubismLogService.LogLevel.ERROR, "needle", 2L);
        assertEquals(1, entries.size());

        host.close();
        host.close();
    }

    @Test
    void eventObservationRedactsBoundsAndDetachesEntries() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner observer = broker.admit("plugin.log-observer");
        final AtomicReference<CubismLogBatchEvent> delivered = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        broker.subscribe(observer.key(), CubismLogBatchEvent.class, event -> {
            delivered.set(event);
            latch.countDown();
        });
        observer.activate();
        final CubismLogServiceHost host = new CubismLogServiceHost(
            4,
            2,
            Duration.ofMillis(10)
        );
        host.attachEventBroker(broker, scheduler);
        final String longSuffix = "x".repeat(CubismLogServiceHost.MAX_EVENT_MESSAGE_LENGTH + 20);

        host.publish(
            CubismLogService.LogLevel.ERROR,
            "file=<local-home>/private/model.cmo3 Authorization: BearerToken " + longSuffix,
            7L
        );

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        final CubismLogBatchEvent event = delivered.get();
        assertEquals(1, event.entries().size());
        assertEquals(0L, event.droppedEntries());
        assertEquals(7L, event.entries().get(0).timestampNanos());
        assertTrue(event.entries().get(0).message().contains("<redacted-path>"));
        assertTrue(event.entries().get(0).message().contains("<redacted-secret>"));
        assertTrue(
            event.entries().get(0).message().length()
                <= CubismLogServiceHost.MAX_EVENT_MESSAGE_LENGTH
        );
        host.close();
        scheduler.shutdown();
    }

    @Test
    void boundedQueueReportsDropsAndCloseStopsPublication() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner observer = broker.admit("plugin.log-pressure");
        final List<CubismLogBatchEvent> events = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        broker.subscribe(observer.key(), CubismLogBatchEvent.class, event -> {
            events.add(event);
            latch.countDown();
        });
        observer.activate();
        final CubismLogServiceHost host = new CubismLogServiceHost(
            2,
            2,
            Duration.ofMillis(50)
        );
        host.attachEventBroker(broker, scheduler);

        host.publish(CubismLogService.LogLevel.INFO, "one", 1L);
        host.publish(CubismLogService.LogLevel.INFO, "two", 2L);
        host.publish(CubismLogService.LogLevel.INFO, "three", 3L);

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(2, events.get(0).entries().size());
        assertEquals(1L, events.get(0).droppedEntries());
        final int deliveredBeforeClose = events.size();
        host.close();
        host.publish(CubismLogService.LogLevel.ERROR, "late", 4L);
        Thread.sleep(100L);
        assertEquals(deliveredBeforeClose, events.size());
        scheduler.shutdown();
    }

    @Test
    void rejectedObservationTimerDoesNotBreakDirectStream() {
        final RuntimeScheduler scheduler = scheduler();
        scheduler.shutdown();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final CubismLogServiceHost host = new CubismLogServiceHost(
            1,
            1,
            Duration.ofMillis(10)
        );
        host.attachEventBroker(broker, scheduler);
        final List<CubismLogService.LogEntry> entries = new ArrayList<>();
        host.subscribe(entries::add);

        host.publish(CubismLogService.LogLevel.WARN, "still direct", 1L);

        assertEquals(1, entries.size());
        assertFalse(entries.get(0).message().isBlank());
        host.close();
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
}
