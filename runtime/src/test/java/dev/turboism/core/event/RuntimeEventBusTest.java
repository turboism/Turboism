package dev.turboism.core.event;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.event.EventBus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEventBusTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.demo";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void subscriberReceivesEventAsynchronously_whenEventIsPublished() throws InterruptedException {
        // Given
        RuntimeScheduler scheduler = scheduler();
        RuntimeEventBus eventBus = new RuntimeEventBus(scheduler, PLUGIN_ID, PermissionChecker.allowAll());
        TestEvent event = new TestEvent("model-opened");
        CountDownLatch delivered = new CountDownLatch(1);
        AtomicReference<TestEvent> receivedEvent = new AtomicReference<>();
        AtomicReference<String> listenerThread = new AtomicReference<>();
        String publisherThread = Thread.currentThread().getName();
        eventBus.subscribe(TestEvent.class, received -> {
            receivedEvent.set(received);
            listenerThread.set(Thread.currentThread().getName());
            delivered.countDown();
        });

        // When
        eventBus.publish(event);

        // Then
        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertSame(event, receivedEvent.get());
        assertNotEquals(publisherThread, listenerThread.get());
        scheduler.shutdown();
    }

    @Test
    void closedRegistrationStopsFutureDeliveries_whenEventIsPublishedAgain() throws InterruptedException {
        // Given
        RuntimeScheduler scheduler = scheduler();
        RuntimeEventBus eventBus = new RuntimeEventBus(scheduler, PLUGIN_ID, PermissionChecker.allowAll());
        CountDownLatch firstDelivery = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        var registration = eventBus.subscribe(TestEvent.class, ignored -> {
            deliveries.incrementAndGet();
            firstDelivery.countDown();
        });
        eventBus.publish(new TestEvent("before-close"));
        assertTrue(firstDelivery.await(1, TimeUnit.SECONDS));

        // When
        registration.close();
        CountDownLatch sentinelDelivery = new CountDownLatch(1);
        eventBus.subscribe(TestEvent.class, ignored -> sentinelDelivery.countDown());
        eventBus.publish(new TestEvent("after-close"));

        // Then
        assertTrue(sentinelDelivery.await(1, TimeUnit.SECONDS));
        assertEquals(1, deliveries.get());
        scheduler.shutdown();
    }

    @Test
    void multipleSubscribersReceiveSameEventType_whenEventIsPublished() throws InterruptedException {
        // Given
        RuntimeScheduler scheduler = scheduler();
        RuntimeEventBus eventBus = new RuntimeEventBus(scheduler, PLUGIN_ID, PermissionChecker.allowAll());
        CountDownLatch delivered = new CountDownLatch(2);
        AtomicInteger deliveries = new AtomicInteger();
        eventBus.subscribe(TestEvent.class, ignored -> {
            deliveries.incrementAndGet();
            delivered.countDown();
        });
        eventBus.subscribe(TestEvent.class, ignored -> {
            deliveries.incrementAndGet();
            delivered.countDown();
        });

        // When
        eventBus.publish(new TestEvent("both-listeners"));

        // Then
        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals(2, deliveries.get());
        scheduler.shutdown();
    }

    @Test
    void publisherThreadReturnsImmediately_whenSubscriberIsBlocked() throws InterruptedException {
        // Given
        RuntimeScheduler scheduler = scheduler();
        RuntimeEventBus eventBus = new RuntimeEventBus(scheduler, PLUGIN_ID, PermissionChecker.allowAll());
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        CountDownLatch listenerCompleted = new CountDownLatch(1);
        eventBus.subscribe(TestEvent.class, ignored -> {
            listenerStarted.countDown();
            try {
                assertTrue(releaseListener.await(200, TimeUnit.MILLISECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            listenerCompleted.countDown();
        });

        // When
        eventBus.publish(new TestEvent("blocked-listener"));

        // Then
        assertTrue(listenerStarted.await(1, TimeUnit.SECONDS));
        assertEquals(1, listenerCompleted.getCount());
        releaseListener.countDown();
        assertTrue(listenerCompleted.await(1, TimeUnit.SECONDS));
        scheduler.shutdown();
    }

    private static RuntimeScheduler scheduler() {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 8, events::add, CLOCK),
            new NoOpSidecarDispatcher(),
            events::add
        );
    }

    private record TestEvent(String name) implements EventBus.TurboismEvent {
    }

    private static final class NoOpSidecarDispatcher implements SidecarDispatcher {

        @Override
        public CompletionStage<SidecarResult> dispatch(PluginTask task, Runnable callback) {
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        }
    }
}
