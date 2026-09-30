package dev.turboism.sdk.cubism.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EditorModelingStatisticsRequestTest {
    @Test
    void exposesOnlyTheObservedBooleanSetting() {
        assertEquals(
                EditorParameterizedCommand.MODELING_STATISTICS, new EditorModelingStatisticsRequest(true).command());
        assertEquals("modeling.statistics", new EditorModelingStatisticsRequest(false).commandId());
    }
}
