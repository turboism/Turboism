package dev.turboism.core.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class PluginWorkBudgetEventTest {

    @Test
    void givenPluginWorkBudgetEvent_whenConstructed_thenExposesAllFieldsAndCode() {
        final PluginWorkBudgetEvent event = new PluginWorkBudgetEvent(
            "dev.turboism.plugin.demo",
            "task-42",
            PluginWorkBudgetEvent.Phase.TIMED_OUT,
            PluginWorkBudgetEvent.Decision.SIDECAR,
            PluginWorkBudgetEvent.Severity.WARNING
        );

        final PluginWorkBudgetEvent result = event;

        assertAll(
            () -> assertEquals("dev.turboism.plugin.demo", result.pluginId()),
            () -> assertEquals("task-42", result.taskId()),
            () -> assertEquals(PluginWorkBudgetEvent.Phase.TIMED_OUT, result.phase()),
            () -> assertEquals(PluginWorkBudgetEvent.Decision.SIDECAR, result.decision()),
            () -> assertEquals(PluginWorkBudgetEvent.Severity.WARNING, result.severity()),
            () -> assertEquals(PluginWorkBudgetEvent.CODE, result.code())
        );
    }

    @Test
    void givenPluginWorkBudgetEvent_whenUsingRecordType_thenIsImmutableValueCarrier() {
        final PluginWorkBudgetEvent event = new PluginWorkBudgetEvent(
            "dev.turboism.plugin.demo",
            "9a1d6d3d-3c3f-4f9d-8fd0-3d9e8dd3f8a1",
            PluginWorkBudgetEvent.Phase.SUBMITTED,
            PluginWorkBudgetEvent.Decision.LIGHTWEIGHT,
            PluginWorkBudgetEvent.Severity.INFO
        );

        final Object type = event.getClass();

        assertInstanceOf(PluginWorkBudgetEvent.class, event);
        assertEquals("dev.turboism.plugin.demo", event.pluginId());
        assertEquals("9a1d6d3d-3c3f-4f9d-8fd0-3d9e8dd3f8a1", event.taskId());
        assertEquals(PluginWorkBudgetEvent.Phase.SUBMITTED, event.phase());
        assertEquals(PluginWorkBudgetEvent.Decision.LIGHTWEIGHT, event.decision());
        assertEquals(PluginWorkBudgetEvent.Severity.INFO, event.severity());
        assertEquals(PluginWorkBudgetEvent.class, type);
    }
}
