package dev.turboism.sdk.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubismLogServiceTest {
    @Test
    void filterMatchesConfiguredLevelsAndKeywordCaseInsensitively() {
        final CubismLogService.LogFilter filter =
            new CubismLogService.LogFilter(false, true, true, "Needle");

        assertFalse(filter.matches(entry(CubismLogService.LogLevel.INFO, "needle")));
        assertTrue(filter.matches(entry(CubismLogService.LogLevel.WARN, "has NEEDLE")));
        assertFalse(filter.matches(entry(CubismLogService.LogLevel.ERROR, "other")));
    }

    private static CubismLogService.LogEntry entry(
        final CubismLogService.LogLevel level,
        final String message
    ) {
        return new CubismLogService.LogEntry(level, message, 1L);
    }
}
