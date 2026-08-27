package dev.turboism.adapter.cubism.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorModelStatisticsAccessProfileTest {

    @Test
    void admitsOffscreenStatisticsOnlyForIndependentExact53Profiles() {
        assertFalse(EditorModelStatisticsAccess.supportsOffscreenStatistics("5.2.03"));
        assertTrue(EditorModelStatisticsAccess.supportsOffscreenStatistics("5.3.02"));
        assertTrue(EditorModelStatisticsAccess.supportsOffscreenStatistics("5.3.03"));
        assertFalse(EditorModelStatisticsAccess.supportsOffscreenStatistics("5.3.04"));
        assertFalse(EditorModelStatisticsAccess.supportsOffscreenStatistics("5.3"));
    }
}
