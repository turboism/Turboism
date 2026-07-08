package dev.turboism.core.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class CallbackBudgetEventTest {

    @Test
    void givenCallbackBudgetEvent_whenConstructed_thenExposesAllFieldsAndCode() {
        final CallbackBudgetEvent event = new CallbackBudgetEvent(
            "dev.turboism.plugin.demo",
            "task-42",
            CallbackBudgetEvent.Phase.TIMED_OUT,
            CallbackBudgetEvent.Decision.SIDECAR,
            CallbackBudgetEvent.Severity.WARNING
        );

        final CallbackBudgetEvent result = event;

        assertAll(
            () -> assertEquals("dev.turboism.plugin.demo", result.pluginId()),
            () -> assertEquals("task-42", result.taskId()),
            () -> assertEquals(CallbackBudgetEvent.Phase.TIMED_OUT, result.phase()),
            () -> assertEquals(CallbackBudgetEvent.Decision.SIDECAR, result.decision()),
            () -> assertEquals(CallbackBudgetEvent.Severity.WARNING, result.severity()),
            () -> assertEquals(CallbackBudgetEvent.CODE, result.code())
        );
    }

    @Test
    void givenCallbackBudgetEvent_whenUsingRecordType_thenIsImmutableValueCarrier() {
        final CallbackBudgetEvent event = new CallbackBudgetEvent(
            "dev.turboism.plugin.demo",
            "9a1d6d3d-3c3f-4f9d-8fd0-3d9e8dd3f8a1",
            CallbackBudgetEvent.Phase.SUBMITTED,
            CallbackBudgetEvent.Decision.LIGHTWEIGHT,
            CallbackBudgetEvent.Severity.INFO
        );

        final Object type = event.getClass();

        assertInstanceOf(CallbackBudgetEvent.class, event);
        assertEquals("dev.turboism.plugin.demo", event.pluginId());
        assertEquals("9a1d6d3d-3c3f-4f9d-8fd0-3d9e8dd3f8a1", event.taskId());
        assertEquals(CallbackBudgetEvent.Phase.SUBMITTED, event.phase());
        assertEquals(CallbackBudgetEvent.Decision.LIGHTWEIGHT, event.decision());
        assertEquals(CallbackBudgetEvent.Severity.INFO, event.severity());
        assertEquals(CallbackBudgetEvent.class, type);
    }
}
