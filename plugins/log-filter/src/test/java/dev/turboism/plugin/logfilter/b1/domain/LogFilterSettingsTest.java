package dev.turboism.plugin.logfilter.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class LogFilterSettingsTest {

    @Test
    void exposesFrozenDefaultsAndNormalizesThroughMatchPolicy() {
        assertEquals(
            new LogFilterSettings(LogLevel.INFO, KeywordMode.ANY, false, List.of()),
            LogFilterSettings.defaults()
        );
        final LogFilterSettings settings = new LogFilterSettings(
            LogLevel.WARNING,
            KeywordMode.ALL,
            false,
            List.of(" Alpha ", "ALPHA", "Beta")
        );
        assertEquals(List.of("Alpha", "Beta"), settings.keywords());
        assertEquals(settings, LogFilterSettings.fromPolicy(settings.toPolicy()));
    }

    @Test
    void rejectsInvalidKeywordBounds() {
        assertThrows(IllegalArgumentException.class, () -> new LogFilterSettings(
            LogLevel.INFO, KeywordMode.ANY, false, List.of("\uD800")
        ));
    }
}
