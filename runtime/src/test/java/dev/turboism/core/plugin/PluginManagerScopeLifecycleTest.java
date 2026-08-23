package dev.turboism.core.plugin;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.turboism.core.plugin.PluginManagerTestFixtures.PLUGIN_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void disableFailureEmitsDiagnosticProblem() throws Exception {
        // Given
        RuntimeScheduler scheduler = scheduler();
        PluginManager manager = new PluginManager(scheduler);
        PluginRuntime runtime = runtime(new FailingDisablePlugin());
        runtime.setContext(PluginManagerTestFixtures.context(new DisposableScope()));
        manager.registerDescriptor(runtime);

        // When
        manager.disable(PLUGIN_ID);
        awaitState(runtime, PluginLifecycleState.DISABLE_FAILED);

        // Then
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
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 4, events::add, CLOCK),
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

    private static AtomicBoolean registerCloseProbe(DisposableScope scope) {
        AtomicBoolean scopeClosed = new AtomicBoolean(false);
        scope.register(() -> scopeClosed.set(true));
        return scopeClosed;
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
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
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
