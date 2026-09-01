package dev.turboism.core.plugin;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.turboism.core.plugin.PluginManagerTestFixtures.PLUGIN_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginManagerScopeLifecycleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void enableFailureClosesPluginDisposableScope() throws Exception {
        // Given
        RuntimeScheduler scheduler = scheduler();
        PluginManager manager = new PluginManager(scheduler);
        DisposableScope scope = new DisposableScope();
        AtomicBoolean scopeClosed = registerCloseProbe(scope);
        PluginRuntime runtime = runtime(new FailingEnablePlugin());
        runtime.setContext(PluginManagerTestFixtures.context(scope));
        runtime.transitionTo(PluginLifecycleState.LOADED);
        manager.registerDescriptor(runtime);

        // When
        manager.enable(PLUGIN_ID);
        awaitState(runtime, PluginLifecycleState.ENABLE_FAILED);

        // Then
        assertTrue(scopeClosed.get());
        assertEquals("ENABLE_FAILED", manager.report().problems().get(0).code());
        scheduler.shutdown();
    }

    @Test
    void disableClosesPluginDisposableScope() throws Exception {
        // Given
        RuntimeScheduler scheduler = scheduler();
        PluginManager manager = new PluginManager(scheduler);
        DisposableScope scope = new DisposableScope();
        AtomicBoolean scopeClosed = registerCloseProbe(scope);
        PluginRuntime runtime = runtime(new TurboismPlugin() { });
        runtime.setContext(PluginManagerTestFixtures.context(scope));
        manager.registerDescriptor(runtime);

        // When
        manager.disable(PLUGIN_ID);
        awaitState(runtime, PluginLifecycleState.DISABLED);

        // Then
        assertTrue(scopeClosed.get());
        scheduler.shutdown();
    }

    @Test
    void delayedDispatchLeavesPluginEnabledAndScopeOpenUntilLifecycleTaskRuns() throws Exception {
        // Given
        RuntimeScheduler scheduler = scheduler(5_000L, 4);
        PluginManager manager = new PluginManager(scheduler);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        assertTrue(scheduler.dispatch(
            new PluginTask("event.subscribe", PLUGIN_ID, "delay lifecycle lane", "none"),
            () -> {
                blockerStarted.countDown();
                await(releaseBlocker);
            }
        ));
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
        DisposableScope scope = new DisposableScope();
        AtomicBoolean scopeClosed = registerCloseProbe(scope);
        AtomicInteger disableCount = new AtomicInteger();
        PluginRuntime runtime = runtime(new CountOnlyDisablePlugin(disableCount));
        runtime.setContext(PluginManagerTestFixtures.context(scope));
        manager.registerDescriptor(runtime);

        try {
            // When
            CompletionStage<PluginLifecycleState> completion = manager.disable(PLUGIN_ID);

            // Then
            assertFalse(completion.toCompletableFuture().isDone());
            assertEquals(PluginLifecycleState.ENABLED, runtime.state());
            assertEquals(0, disableCount.get());
            assertFalse(scopeClosed.get());

            releaseBlocker.countDown();
            assertEquals(
                PluginLifecycleState.DISABLED,
                completion.toCompletableFuture().get(1, TimeUnit.SECONDS)
            );
            assertEquals(1, disableCount.get());
            assertTrue(scopeClosed.get());
        } finally {
            releaseBlocker.countDown();
            scheduler.shutdown();
        }
    }

    @Test
    void dispatchRejectionFailsCompletionWithoutRunningTeardownOrChangingState() throws Exception {
        // Given
        RuntimeScheduler scheduler = scheduler();
        PluginManager manager = new PluginManager(scheduler);
        DisposableScope scope = new DisposableScope();
        AtomicBoolean scopeClosed = registerCloseProbe(scope);
        AtomicInteger disableCount = new AtomicInteger();
        PluginRuntime runtime = runtime(new CountOnlyDisablePlugin(disableCount));
        runtime.setContext(PluginManagerTestFixtures.context(scope));
        manager.registerDescriptor(runtime);
        scheduler.shutdown();

        // When
        CompletionStage<PluginLifecycleState> completion = manager.disable(PLUGIN_ID);
        ExecutionException failure = assertThrows(
            ExecutionException.class,
            () -> completion.toCompletableFuture().get(1, TimeUnit.SECONDS)
        );

        // Then
        IllegalStateException rejection = assertInstanceOf(
            IllegalStateException.class,
            failure.getCause()
        );
        assertEquals("Plugin lifecycle disable dispatch was rejected", rejection.getMessage());
        assertEquals(PluginLifecycleState.ENABLED, runtime.state());
        assertEquals(0, disableCount.get());
        assertFalse(scopeClosed.get());
        assertEquals("DISABLE_FAILED", manager.report().problems().get(0).code());
    }

    @Test
    void disableOrdersReverseEntrypointsScopeStateAndCompletionOnLifecycleThread() throws Exception {
        // Given
        RuntimeScheduler scheduler = scheduler(5_000L, 4);
        PluginManager manager = new PluginManager(scheduler);
        List<String> events = new CopyOnWriteArrayList<>();
        List<String> threads = new CopyOnWriteArrayList<>();
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        TurboismPlugin first = recordingDisablePlugin("first", events, threads);
        TurboismPlugin second = new TurboismPlugin() {
            @Override
            public void disable() {
                events.add("disable:second");
                threads.add(Thread.currentThread().getName());
                secondStarted.countDown();
                await(releaseSecond);
            }
        };
        PluginRuntime runtime = runtime(List.of(first, second));
        DisposableScope scope = new DisposableScope();
        scope.register(() -> {
            events.add("scope:" + runtime.state());
            threads.add(Thread.currentThread().getName());
        });
        runtime.setContext(PluginManagerTestFixtures.context(scope));
        manager.registerDescriptor(runtime);

        try {
            // When
            CompletionStage<PluginLifecycleState> completion = manager.disable(PLUGIN_ID);
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
            CompletionStage<PluginLifecycleState> observed = completion.thenApply(state -> {
                events.add("completion:" + runtime.state());
                threads.add(Thread.currentThread().getName());
                return state;
            });
            releaseSecond.countDown();

            // Then
            assertEquals(
                PluginLifecycleState.DISABLED,
                observed.toCompletableFuture().get(1, TimeUnit.SECONDS)
            );
            assertEquals(List.of(
                "disable:second",
                "disable:first",
                "scope:ENABLED",
                "completion:DISABLED"
            ), events);
            assertEquals(1, threads.stream().distinct().count());
        } finally {
            releaseSecond.countDown();
            scheduler.shutdown();
        }
    }

    @Test
    void concurrentDisableCallsShareOneLifecycleCompletion() throws Exception {
        // Given
        RuntimeScheduler scheduler = scheduler(5_000L, 4);
        PluginManager manager = new PluginManager(scheduler);
        CountDownLatch disableStarted = new CountDownLatch(1);
        CountDownLatch releaseDisable = new CountDownLatch(1);
        PluginRuntime runtime = runtime(new TurboismPlugin() {
            @Override
            public void disable() {
                disableStarted.countDown();
                await(releaseDisable);
            }
        });
        manager.registerDescriptor(runtime);

        try {
            // When
            CompletionStage<PluginLifecycleState> first = manager.disable(PLUGIN_ID);
            assertTrue(disableStarted.await(1, TimeUnit.SECONDS));
            CompletionStage<PluginLifecycleState> second = manager.disable(PLUGIN_ID);

            // Then
            assertSame(first, second);
            releaseDisable.countDown();
            assertEquals(
                PluginLifecycleState.DISABLED,
                first.toCompletableFuture().get(1, TimeUnit.SECONDS)
            );
        } finally {
            releaseDisable.countDown();
            scheduler.shutdown();
        }
    }

    @Test
    void successfulLifecycleStagesAreLoggedThroughPluginLogger() throws Exception {
        // Given
        RuntimeScheduler scheduler = scheduler();
        PluginManager manager = new PluginManager(scheduler);
        List<String> messages = new CopyOnWriteArrayList<>();
        PluginRuntime runtime = runtime(new TurboismPlugin() { });
        runtime.setContext(PluginManagerTestFixtures.context(
            new DisposableScope(),
            recordingLogger(messages)
        ));
        runtime.transitionTo(PluginLifecycleState.LOADED);
        manager.registerDescriptor(runtime);

        try {
            // When
            manager.enable(PLUGIN_ID);
            awaitState(runtime, PluginLifecycleState.ENABLED);
            manager.disable(PLUGIN_ID);
            awaitState(runtime, PluginLifecycleState.DISABLED);
            manager.shutdown(PLUGIN_ID);
            awaitState(runtime, PluginLifecycleState.UNLOADED);

            // Then
            assertTrue(messages.contains("Plugin lifecycle: enable started"));
            assertTrue(messages.contains("Plugin lifecycle: enable succeeded entrypoints=1"));
            assertTrue(messages.contains("Plugin lifecycle: disable started"));
            assertTrue(messages.contains("Plugin lifecycle: disable succeeded entrypoints=1"));
            assertTrue(messages.contains("Plugin lifecycle: shutdown started"));
            assertTrue(messages.contains("Plugin lifecycle: shutdown succeeded entrypoints=1"));
            assertTrue(messages.contains("Plugin lifecycle: unload succeeded"));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void disableReturnsImmediatelyWhenPluginIsNotEnabled() {
        // Given
        RuntimeScheduler scheduler = scheduler();
        PluginManager manager = new PluginManager(scheduler);
        AtomicInteger disableCount = new AtomicInteger();
        PluginRuntime runtime = runtime(new CountOnlyDisablePlugin(disableCount));
        runtime.transitionTo(PluginLifecycleState.DISABLED);
        manager.registerDescriptor(runtime);

        // When
        manager.disable(PLUGIN_ID);

        // Then
        assertEquals(0, disableCount.get());
        assertEquals(PluginLifecycleState.DISABLED, runtime.state());
        scheduler.shutdown();
    }

    @Test
    void shutdownClosesPluginDisposableScopeFromPartiallyLoadedState() throws Exception {
        // Given
        RuntimeScheduler scheduler = scheduler();
        PluginManager manager = new PluginManager(scheduler);
        DisposableScope scope = new DisposableScope();
        AtomicBoolean scopeClosed = registerCloseProbe(scope);
        PluginRuntime runtime = runtime(new TurboismPlugin() { });
        runtime.setContext(PluginManagerTestFixtures.context(scope));
        runtime.transitionTo(PluginLifecycleState.CONSTRUCTED);
        manager.registerDescriptor(runtime);

        // When
        manager.shutdown(PLUGIN_ID);
        awaitState(runtime, PluginLifecycleState.UNLOADED);

        // Then
        assertTrue(scopeClosed.get());
        scheduler.shutdown();
    }

    @Test
    void disableFailureEmitsDiagnosticProblemAndCompletesAfterScopeClosure() throws Exception {
        // Given
        RuntimeScheduler scheduler = scheduler();
        PluginManager manager = new PluginManager(scheduler);
        DisposableScope scope = new DisposableScope();
        AtomicBoolean scopeClosed = registerCloseProbe(scope);
        PluginRuntime runtime = runtime(new FailingDisablePlugin());
        runtime.setContext(PluginManagerTestFixtures.context(scope));
        manager.registerDescriptor(runtime);

        // When
        PluginLifecycleState result = manager.disable(PLUGIN_ID)
            .toCompletableFuture()
            .get(1, TimeUnit.SECONDS);

        // Then
        assertEquals(PluginLifecycleState.DISABLE_FAILED, result);
        assertEquals(PluginLifecycleState.DISABLE_FAILED, runtime.state());
        assertTrue(scopeClosed.get());
        assertEquals("DISABLE_FAILED", manager.report().problems().get(0).code());
        scheduler.shutdown();
    }

    @Test
    void shutdownFailureEmitsDiagnosticProblem() throws Exception {
        // Given
        RuntimeScheduler scheduler = scheduler();
        PluginManager manager = new PluginManager(scheduler);
        PluginRuntime runtime = runtime(new FailingShutdownPlugin());
        runtime.setContext(PluginManagerTestFixtures.context(new DisposableScope()));
        manager.registerDescriptor(runtime);

        // When
        manager.shutdown(PLUGIN_ID);
        awaitState(runtime, PluginLifecycleState.SHUTDOWN_FAILED);

        // Then
        assertEquals("SHUTDOWN_FAILED", manager.report().problems().get(0).code());
        scheduler.shutdown();
    }

    private static RuntimeScheduler scheduler() {
        return scheduler(500L, 4);
    }

    private static RuntimeScheduler scheduler(long timeoutMillis, int queueCapacity) {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(timeoutMillis, 1, queueCapacity, events::add, CLOCK),
            new PluginManagerTestFixtures.ImmediateSidecarDispatcher(),
            events::add
        );
    }

    private static PluginRuntime runtime(TurboismPlugin plugin) {
        return runtime(List.of(plugin));
    }

    private static PluginRuntime runtime(List<TurboismPlugin> plugins) {
        PluginRuntime runtime = new PluginRuntime(PLUGIN_ID, PluginManagerTestFixtures.descriptor());
        runtime.setEntrypoints(plugins);
        runtime.transitionTo(PluginLifecycleState.ENABLED);
        return runtime;
    }

    private static AtomicBoolean registerCloseProbe(DisposableScope scope) {
        AtomicBoolean scopeClosed = new AtomicBoolean(false);
        scope.register(() -> scopeClosed.set(true));
        return scopeClosed;
    }

    private static TurboismPlugin recordingDisablePlugin(
        String name,
        List<String> events,
        List<String> threads
    ) {
        return new TurboismPlugin() {
            @Override
            public void disable() {
                events.add("disable:" + name);
                threads.add(Thread.currentThread().getName());
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static PluginLogger recordingLogger(List<String> messages) {
        return new PluginLogger() {
            @Override
            public void debug(String message) {
                messages.add(message);
            }

            @Override
            public void info(String message) {
                messages.add(message);
            }

            @Override
            public void warn(String message) {
                messages.add(message);
            }

            @Override
            public void error(String message) {
                messages.add(message);
            }

            @Override
            public void error(String message, Throwable throwable) {
                messages.add(message);
            }
        };
    }

    private static void awaitState(PluginRuntime runtime, PluginLifecycleState expectedState) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadlineNanos && runtime.state() != expectedState) {
            Thread.sleep(10);
        }
        assertEquals(expectedState, runtime.state());
    }

    private record CountOnlyDisablePlugin(AtomicInteger disableCount) implements TurboismPlugin {

        @Override
        public void disable() {
            disableCount.incrementAndGet();
        }
    }

    private static final class FailingEnablePlugin implements TurboismPlugin {

        @Override
        public void enable() throws Exception {
            throw new Exception("enable failed");
        }
    }

    private static final class FailingDisablePlugin implements TurboismPlugin {

        @Override
        public void disable() throws Exception {
            throw new Exception("disable failed");
        }
    }

    private static final class FailingShutdownPlugin implements TurboismPlugin {

        @Override
        public void shutdown() throws Exception {
            throw new Exception("shutdown failed");
        }
    }
}
