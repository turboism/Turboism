package dev.turboism.core.runtime;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSchedulerTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.demo";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void lightweightTaskIsDispatchedToPluginExecutorAndRunsAsynchronously() throws InterruptedException {
        // Given
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler scheduler = scheduler(events, new RecordingSidecarDispatcher());
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();
        String callerThread = Thread.currentThread().getName();

        // When
        scheduler.dispatch(task("action.handle", "none"), () -> {
            workerThread.set(Thread.currentThread().getName());
            completed.countDown();
        });

        // Then
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertNotEquals(callerThread, workerThread.get());
        assertTrue(events.isEmpty());
        scheduler.shutdown();
    }

    @Test
    void rejectedTaskIsNotExecutedAndEmitsDiagnosticEvent() {
        // Given
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler scheduler = scheduler(events, new RecordingSidecarDispatcher());
        AtomicInteger executions = new AtomicInteger();

        // When
        scheduler.dispatch(task("network", "none"), executions::incrementAndGet);

        // Then
        assertEquals(0, executions.get());
        assertEquals(List.of(new CallbackBudgetEvent(
            PLUGIN_ID,
            "network",
            CallbackBudgetEvent.Phase.REJECTED,
            CallbackBudgetEvent.Decision.REJECTED,
            CallbackBudgetEvent.Severity.WARNING
        )), events);
        scheduler.shutdown();
    }

    @Test
    void sidecarTaskIsHandedToSidecarDispatcher() {
        // Given
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        RecordingSidecarDispatcher sidecar = new RecordingSidecarDispatcher();
        RuntimeScheduler scheduler = scheduler(events, sidecar);
        PluginTask task = task("ai", "sidecar");

        // When
        scheduler.dispatch(task, () -> { });

        // Then
        assertSame(task, sidecar.task.get());
        assertNotNull(sidecar.callback.get());
        assertTrue(events.isEmpty());
        scheduler.shutdown();
    }

    @Test
    void shutdownDrainsAllPluginExecutors() throws InterruptedException {
        // Given
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler scheduler = scheduler(events, new RecordingSidecarDispatcher());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        scheduler.dispatch(task("event.subscribe", "none"), () -> {
            firstStarted.countDown();
            await(releaseFirst);
            completed.countDown();
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        scheduler.dispatch(new PluginTask("action.handle", "dev.turboism.plugin.other", "payload", "none"), completed::countDown);

        // When
        releaseFirst.countDown();
        scheduler.shutdown();

        // Then
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertTrue(events.isEmpty());
    }

    @Test
    void cancellationContextIsClearedAfterCallbackFinishes() {
        // Given
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        RecordingSidecarDispatcher sidecar = new RecordingSidecarDispatcher();
        RuntimeScheduler scheduler = scheduler(events, sidecar);
        AtomicReference<RuntimeCancellationToken> tokenDuringCallback = new AtomicReference<>();

        // When
        scheduler.dispatch(task("ai", "sidecar"), () -> {
            tokenDuringCallback.set(CancellationContext.get());
        });
        sidecar.callback.get().run();

        // Then
        assertNotNull(tokenDuringCallback.get());
        assertNull(CancellationContext.get());
        assertTrue(events.isEmpty());
        scheduler.shutdown();
    }

    private static RuntimeScheduler scheduler(
        List<CallbackBudgetEvent> events,
        SidecarDispatcher sidecarDispatcher
    ) {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 2, events::add, CLOCK),
            sidecarDispatcher,
            events::add
        );
    }

    private static PluginTask task(String type, String capability) {
        return new PluginTask(type, PLUGIN_ID, "payload for " + type, capability);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class RecordingSidecarDispatcher implements SidecarDispatcher {

        private final AtomicReference<PluginTask> task = new AtomicReference<>();
        private final AtomicReference<Runnable> callback = new AtomicReference<>();

        @Override
        public CompletionStage<SidecarResult> dispatch(PluginTask task, Runnable callback) {
            this.task.set(task);
            this.callback.set(callback);
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        }
    }
}
