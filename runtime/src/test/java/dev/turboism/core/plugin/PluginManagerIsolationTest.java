package dev.turboism.core.plugin;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.TurboismPlugin;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static dev.turboism.core.plugin.PluginManagerTestFixtures.PLUGIN_ID;

class PluginManagerIsolationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void disableReturnsImmediatelyWhenPluginCallbackBlocks() throws Exception {
        // Given
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler scheduler = scheduler(events);
        PluginManager manager = new PluginManager(scheduler);
        CountDownLatch disableStarted = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        PluginRuntime runtime = runtime(new BlockingDisablePlugin(disableStarted, releaseDisable));
        manager.registerDescriptor(runtime);
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try {
            // When
            CompletableFuture<Void> disableCall = CompletableFuture.runAsync(() -> manager.disable(PLUGIN_ID), caller);
            assertTrue(disableStarted.await(1, TimeUnit.SECONDS));

            // Then
            assertDoesNotThrow(() -> disableCall.get(100, TimeUnit.MILLISECONDS),
                "disable() must not wait for the plugin callback to finish");
            releaseDisable.countDown();
        } finally {
            releaseDisable.countDown();
            caller.shutdownNow();
            scheduler.shutdown();
        }
    }

    @Test
    void shutdownClosesPluginDisposableScope() throws Exception {
        // Given
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler scheduler = scheduler(events);
        PluginManager manager = new PluginManager(scheduler);
        DisposableScope scope = new DisposableScope();
        AtomicBoolean scopeClosed = new AtomicBoolean(false);
        scope.register(() -> scopeClosed.set(true));
        PluginRuntime runtime = runtime(new TurboismPlugin() { });
        runtime.setContext(PluginManagerTestFixtures.context(scope));
        manager.registerDescriptor(runtime);

        // When
        manager.shutdown(PLUGIN_ID);
        awaitState(runtime, PluginLifecycleState.UNLOADED);

        // Then
        assertTrue(scopeClosed.get());
        scheduler.shutdown();
    }

    @Test
    void repeatedDisableCallsScheduleOnePluginCallback() throws Exception {
        // Given
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler scheduler = scheduler(events);
        PluginManager manager = new PluginManager(scheduler);
        CountDownLatch firstDisableStarted = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        AtomicInteger disableCount = new AtomicInteger();
        PluginRuntime runtime = runtime(new CountingDisablePlugin(firstDisableStarted, releaseDisable, disableCount));
        manager.registerDescriptor(runtime);

        try {
            // When
            manager.disable(PLUGIN_ID);
            manager.disable(PLUGIN_ID);
            assertTrue(firstDisableStarted.await(1, TimeUnit.SECONDS));
            releaseDisable.countDown();
            awaitDisableCount(disableCount, 1);

            // Then
            assertEquals(1, disableCount.get());
            assertEquals(PluginLifecycleState.DISABLED, runtime.state());
        } finally {
            releaseDisable.countDown();
            scheduler.shutdown();
        }
    }

    @Test
    void shutdownTimeoutEmitsCallbackBudgetEvent() throws Exception {
        // Given
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler scheduler = scheduler(events);
        PluginManager manager = new PluginManager(scheduler);
        CountDownLatch shutdownStarted = new CountDownLatch(1);
        PluginRuntime runtime = runtime(new BlockingShutdownPlugin(shutdownStarted));
        manager.registerDescriptor(runtime);

        try {
            // When
            manager.shutdown(PLUGIN_ID);
            assertTrue(shutdownStarted.await(1, TimeUnit.SECONDS));
            awaitEvent(events, CallbackBudgetEvent.Phase.TIMED_OUT);

            // Then
            assertEquals(List.of(new CallbackBudgetEvent(
                PLUGIN_ID,
                "lifecycle.shutdown",
                CallbackBudgetEvent.Phase.TIMED_OUT,
                CallbackBudgetEvent.Decision.LIGHTWEIGHT,
                CallbackBudgetEvent.Severity.WARNING
            )), events);
        } finally {
            scheduler.shutdown();
        }
    }

    private static RuntimeScheduler scheduler(List<CallbackBudgetEvent> events) {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 4, events::add, CLOCK),
            new PluginManagerTestFixtures.ImmediateSidecarDispatcher(),
            events::add
        );
    }

    private static PluginRuntime runtime(TurboismPlugin plugin) {
        PluginRuntime runtime = new PluginRuntime(PLUGIN_ID, PluginManagerTestFixtures.descriptor());
        runtime.setEntrypoints(List.of(plugin));
        runtime.transitionTo(PluginLifecycleState.ENABLED);
        return runtime;
    }

    private static void awaitState(PluginRuntime runtime, PluginLifecycleState expectedState) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadlineNanos && runtime.state() != expectedState) {
            Thread.sleep(10);
        }
        assertEquals(expectedState, runtime.state());
    }

    private static void awaitDisableCount(AtomicInteger disableCount, int expectedCount) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadlineNanos && disableCount.get() != expectedCount) {
            Thread.sleep(10);
        }
    }

    private static void awaitEvent(
        List<CallbackBudgetEvent> events,
        CallbackBudgetEvent.Phase expectedPhase
    ) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadlineNanos && events.stream().noneMatch(event -> event.phase() == expectedPhase)) {
            Thread.sleep(10);
        }
    }

    private record BlockingDisablePlugin(
        CountDownLatch disableStarted,
        CountDownLatch releaseDisable
    ) implements TurboismPlugin {

        @Override
        public void disable() throws Exception {
            disableStarted.countDown();
            assertTrue(releaseDisable.await(1, TimeUnit.SECONDS));
        }
    }

    private record CountingDisablePlugin(
        CountDownLatch firstDisableStarted,
        CountDownLatch releaseDisable,
        AtomicInteger disableCount
    ) implements TurboismPlugin {

        @Override
        public void disable() throws Exception {
            disableCount.incrementAndGet();
            firstDisableStarted.countDown();
            assertTrue(releaseDisable.await(1, TimeUnit.SECONDS));
        }
    }

    private record BlockingShutdownPlugin(CountDownLatch shutdownStarted) implements TurboismPlugin {

        @Override
        public void shutdown() throws Exception {
            shutdownStarted.countDown();
            Thread.sleep(TimeUnit.SECONDS.toMillis(2));
        }
    }
}
