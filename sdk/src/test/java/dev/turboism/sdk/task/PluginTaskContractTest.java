package dev.turboism.sdk.task;

import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginTaskContractTest {

    @Test
    void exposesTheFrozenClosedEnumSets() {
        assertEquals(
            "COMPUTE,LOW_FREQUENCY_REFRESH",
            names(PluginTaskKind.values())
        );
        assertEquals("NORMAL,LOW", names(PluginTaskPriority.values()));
        assertEquals("ACCEPTED,REJECTED", names(TaskSubmissionStatus.values()));
        assertEquals(
            "DUPLICATE_ACTIVE_ID,PLUGIN_INACTIVE,BACKPRESSURE,CIRCUIT_OPEN,RUNTIME_UNAVAILABLE,POLICY_REJECTED",
            names(TaskRejectionReason.values())
        );
        assertEquals("SUCCEEDED,FAILED,TIMED_OUT,CANCELED", names(TaskRunOutcomeStatus.values()));
        assertEquals("SUCCEEDED,FAILED,TIMED_OUT,CANCELED,REJECTED", names(TaskOutcomeStatus.values()));
    }

    @Test
    void validatesTaskIdentityAndRequests() {
        final TaskId id = new TaskId("refresh.project-inspector");
        final PluginTaskAction action = token -> { };

        assertEquals("refresh.project-inspector", id.value());
        assertThrows(IllegalArgumentException.class, () -> new TaskId(" "));
        assertThrows(IllegalArgumentException.class, () -> new TaskId("x".repeat(129)));
        assertNotNull(new PluginTaskRequest(
            id,
            PluginTaskKind.COMPUTE,
            PluginTaskPriority.NORMAL,
            action
        ));
        assertThrows(NullPointerException.class, () -> new PluginTaskRequest(
            id,
            null,
            PluginTaskPriority.NORMAL,
            action
        ));
        assertNotNull(new FixedDelayTaskRequest(
            id,
            PluginTaskKind.LOW_FREQUENCY_REFRESH,
            PluginTaskPriority.LOW,
            Duration.ZERO,
            Duration.ofSeconds(1),
            action
        ));
        assertThrows(IllegalArgumentException.class, () -> new FixedDelayTaskRequest(
            id,
            PluginTaskKind.LOW_FREQUENCY_REFRESH,
            PluginTaskPriority.LOW,
            Duration.ofMillis(-1),
            Duration.ofSeconds(1),
            action
        ));
        assertThrows(IllegalArgumentException.class, () -> new FixedDelayTaskRequest(
            id,
            PluginTaskKind.LOW_FREQUENCY_REFRESH,
            PluginTaskPriority.LOW,
            Duration.ZERO,
            Duration.ZERO,
            action
        ));
    }

    @Test
    void validatesProgressOutcomeAndFailureAlgebra() {
        final TaskId id = new TaskId("task");
        final TaskFailure failure = new TaskFailure("TASK_FAILED", "Task failed safely.");
        final TaskRunOutcome succeeded = new TaskRunOutcome(
            1,
            TaskRunOutcomeStatus.SUCCEEDED,
            Optional.empty()
        );
        final TaskRunOutcome failed = new TaskRunOutcome(
            2,
            TaskRunOutcomeStatus.FAILED,
            Optional.of(failure)
        );

        assertEquals(new TaskProgress(2, Optional.of(failed)), new TaskProgress(2, Optional.of(failed)));
        assertThrows(IllegalArgumentException.class, () -> new TaskRunOutcome(
            0,
            TaskRunOutcomeStatus.SUCCEEDED,
            Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new TaskRunOutcome(
            1,
            TaskRunOutcomeStatus.SUCCEEDED,
            Optional.of(failure)
        ));
        assertThrows(IllegalArgumentException.class, () -> new TaskRunOutcome(
            1,
            TaskRunOutcomeStatus.FAILED,
            Optional.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new TaskProgress(
            0,
            Optional.of(succeeded)
        ));

        assertNotNull(new TaskOutcome(
            id,
            TaskOutcomeStatus.SUCCEEDED,
            1,
            Optional.of(succeeded),
            Optional.empty()
        ));
        assertNotNull(new TaskOutcome(
            id,
            TaskOutcomeStatus.REJECTED,
            0,
            Optional.empty(),
            Optional.of(failure)
        ));
        assertThrows(IllegalArgumentException.class, () -> new TaskOutcome(
            id,
            TaskOutcomeStatus.REJECTED,
            1,
            Optional.of(succeeded),
            Optional.of(failure)
        ));
        assertThrows(IllegalArgumentException.class, () -> new TaskOutcome(
            id,
            TaskOutcomeStatus.CANCELED,
            0,
            Optional.empty(),
            Optional.of(failure)
        ));
    }

    @Test
    void taskSubmissionRequiresAHandleAndExactRejectionReasonPresence() {
        final TaskHandle handle = handle(new TaskId("task"));
        final TaskSubmission accepted = new TaskSubmission(
            TaskSubmissionStatus.ACCEPTED,
            handle,
            Optional.empty()
        );
        final TaskSubmission rejected = new TaskSubmission(
            TaskSubmissionStatus.REJECTED,
            handle,
            Optional.of(TaskRejectionReason.BACKPRESSURE)
        );

        assertTrue(accepted.accepted());
        assertFalse(rejected.accepted());
        assertThrows(IllegalArgumentException.class, () -> new TaskSubmission(
            TaskSubmissionStatus.ACCEPTED,
            handle,
            Optional.of(TaskRejectionReason.BACKPRESSURE)
        ));
        assertThrows(IllegalArgumentException.class, () -> new TaskSubmission(
            TaskSubmissionStatus.REJECTED,
            handle,
            Optional.empty()
        ));
    }

    @Test
    void pluginContextDefaultAccessorFailsWithTheFrozenCompatibilityMessage() {
        final PluginContext context = (PluginContext) Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[] {PluginContext.class},
            PluginTaskContractTest::invokeDefault
        );

        final UnsupportedOperationException error = assertThrows(
            UnsupportedOperationException.class,
            context::tasks
        );
        assertEquals("task scheduler is not available", error.getMessage());
    }

    private static TaskHandle handle(final TaskId id) {
        return new TaskHandle() {
            @Override public TaskId id() { return id; }
            @Override public TaskProgress progress() { return new TaskProgress(0, Optional.empty()); }
            @Override public boolean cancel() { return false; }
            @Override public java.util.concurrent.CompletionStage<TaskOutcome> completion() {
                return CompletableFuture.completedFuture(new TaskOutcome(
                    id,
                    TaskOutcomeStatus.REJECTED,
                    0,
                    Optional.empty(),
                    Optional.of(new TaskFailure("REJECTED", "Rejected."))
                ));
            }
            @Override public void close() { }
        };
    }

    private static Object invokeDefault(
        final Object proxy,
        final Method method,
        final Object[] arguments
    ) throws Throwable {
        if (!method.isDefault()) {
            throw new AssertionError("Unexpected abstract method invocation: " + method);
        }
        return InvocationHandler.invokeDefault(proxy, method, arguments);
    }

    private static String names(final Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(java.util.stream.Collectors.joining(","));
    }
}
