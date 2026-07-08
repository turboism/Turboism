package dev.turboism.core.runtime.sidecar;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.runtime.PluginTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SidecarSupervisorTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.demo";

    @Test
    void successfulSidecarTaskReturnsCompletedFutureAndKeepsHealthHealthy() {
        // Given
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        FakeDispatcher dispatcher = new FakeDispatcher(List.of(SidecarResult.success("{\"ok\":true}")));
        SidecarSupervisor supervisor = new SidecarSupervisor(dispatcher, 2, events::add);

        // When
        SidecarResult result = supervisor.dispatch(task("action.handle"), () -> { })
            .toCompletableFuture()
            .orTimeout(1, TimeUnit.SECONDS)
            .join();

        // Then
        assertAll(
            () -> assertEquals(SidecarResult.Kind.SUCCESS, result.kind()),
            () -> assertEquals("{\"ok\":true}", result.payload()),
            () -> assertEquals(SidecarHealth.HEALTHY, supervisor.health()),
            () -> assertEquals(0, supervisor.crashCount()),
            () -> assertEquals(1, dispatcher.dispatchCount()),
            () -> assertEquals(List.of(), events)
        );
    }

    @Test
    void crashIncrementsCrashCountAndRestartsBeforeReturningSuccessfulRetry() {
        // Given
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        FakeDispatcher dispatcher = new FakeDispatcher(List.of(
            SidecarResult.error("SIDECAR_EXIT_FAILED", "process exited 1"),
            SidecarResult.success("{\"retry\":true}")
        ));
        SidecarSupervisor supervisor = new SidecarSupervisor(dispatcher, 2, events::add);

        // When
        SidecarResult result = supervisor.dispatch(task("action.handle"), () -> { })
            .toCompletableFuture()
            .orTimeout(1, TimeUnit.SECONDS)
            .join();

        // Then
        assertAll(
            () -> assertEquals(SidecarResult.Kind.SUCCESS, result.kind()),
            () -> assertEquals("{\"retry\":true}", result.payload()),
            () -> assertEquals(SidecarHealth.HEALTHY, supervisor.health()),
            () -> assertEquals(1, supervisor.crashCount()),
            () -> assertEquals(2, dispatcher.dispatchCount()),
            () -> assertEquals(List.of(
                event("action.handle", CallbackBudgetEvent.Phase.FAILED, CallbackBudgetEvent.Severity.WARNING),
                event("action.handle", CallbackBudgetEvent.Phase.QUEUED, CallbackBudgetEvent.Severity.INFO)
            ), events)
        );
    }

    @Test
    void maxRestartsExceededMarksSidecarUnavailableAndSubsequentTasksFailFast() {
        // Given
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        FakeDispatcher dispatcher = new FakeDispatcher(List.of(
            SidecarResult.error("SIDECAR_EXIT_FAILED", "first crash"),
            SidecarResult.error("SIDECAR_EXIT_FAILED", "second crash")
        ));
        SidecarSupervisor supervisor = new SidecarSupervisor(dispatcher, 1, events::add);

        // When
        SidecarResult exhausted = supervisor.dispatch(task("action.handle"), () -> { })
            .toCompletableFuture()
            .orTimeout(1, TimeUnit.SECONDS)
            .join();
        CompletionException failFast = assertThrows(CompletionException.class, () -> supervisor
            .dispatch(task("ui.schedule"), () -> { })
            .toCompletableFuture()
            .join());

        // Then
        assertAll(
            () -> assertEquals(SidecarResult.Kind.ERROR, exhausted.kind()),
            () -> assertEquals("SIDECAR_UNAVAILABLE", exhausted.errorCode()),
            () -> assertEquals(SidecarHealth.UNAVAILABLE, supervisor.health()),
            () -> assertEquals(2, supervisor.crashCount()),
            () -> assertEquals(2, dispatcher.dispatchCount()),
            () -> assertInstanceOf(SidecarDispatchException.class, failFast.getCause()),
            () -> assertEquals("SIDECAR_UNAVAILABLE", ((SidecarDispatchException) failFast.getCause()).diagnosticCode())
        );
    }

    @Test
    void diagnosticsAreEmittedForCrashRestartAndUnavailableTransitions() {
        // Given
        List<CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        FakeDispatcher dispatcher = new FakeDispatcher(List.of(
            SidecarResult.error("SIDECAR_EXIT_FAILED", "first crash"),
            SidecarResult.error("SIDECAR_EXIT_FAILED", "second crash")
        ));
        SidecarSupervisor supervisor = new SidecarSupervisor(dispatcher, 1, events::add);

        // When
        supervisor.dispatch(task("action.handle"), () -> { })
            .toCompletableFuture()
            .orTimeout(1, TimeUnit.SECONDS)
            .join();

        // Then
        assertEquals(List.of(
            event("action.handle", CallbackBudgetEvent.Phase.FAILED, CallbackBudgetEvent.Severity.WARNING),
            event("action.handle", CallbackBudgetEvent.Phase.QUEUED, CallbackBudgetEvent.Severity.INFO),
            event("action.handle", CallbackBudgetEvent.Phase.FAILED, CallbackBudgetEvent.Severity.WARNING),
            event("action.handle", CallbackBudgetEvent.Phase.CIRCUIT_OPEN, CallbackBudgetEvent.Severity.ERROR)
        ), events);
    }

    private static CallbackBudgetEvent event(
        String taskId,
        CallbackBudgetEvent.Phase phase,
        CallbackBudgetEvent.Severity severity
    ) {
        return new CallbackBudgetEvent(
            PLUGIN_ID,
            taskId,
            phase,
            CallbackBudgetEvent.Decision.SIDECAR,
            severity
        );
    }

    private static PluginTask task(String type) {
        return new PluginTask(type, PLUGIN_ID, "payload for " + type, "sidecar");
    }

    private static final class FakeDispatcher implements SidecarDispatcher {

        private final Queue<SidecarResult> results;
        private final AtomicInteger dispatchCount = new AtomicInteger();

        private FakeDispatcher(List<SidecarResult> results) {
            this.results = new ArrayDeque<>(results);
        }

        @Override
        public CompletionStage<SidecarResult> dispatch(PluginTask task, Runnable callback) {
            dispatchCount.incrementAndGet();
            SidecarResult result = results.remove();
            if (result.kind() == SidecarResult.Kind.SUCCESS) {
                callback.run();
            }
            return CompletableFuture.completedFuture(result);
        }

        private int dispatchCount() {
            return dispatchCount.get();
        }
    }
}
