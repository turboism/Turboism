package dev.turboism.preview;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CubismLoggerBridgeTest {

    @Test
    void invokesTheMatchingCubismLog4jMethodWithoutEmbeddingHostOwnedPrefixes() {
        final RecordingLogger logger = new RecordingLogger();
        final CubismLoggerBridge bridge = new CubismLoggerBridge(logger);

        for (PreviewLog.Level level : PreviewLog.Level.values()) {
            bridge.write(level, "probe", level.name().toLowerCase(), null);
        }

        assertEquals(
            List.of(
                "[probe] trace",
                "[probe] debug",
                "[probe] info",
                "[probe] warn",
                "[probe] error",
                "[probe] fatal"
            ),
            logger.calls
        );
    }

    public static final class RecordingLogger {
        private final List<String> calls = new ArrayList<>();

        public void trace(final String message) { calls.add(message); }
        public void debug(final String message) { calls.add(message); }
        public void info(final String message) { calls.add(message); }
        public void warn(final String message) { calls.add(message); }
        public void error(final String message) { calls.add(message); }
        public void fatal(final String message) { calls.add(message); }

        public void trace(final String message, final Throwable failure) { calls.add(message); }
        public void debug(final String message, final Throwable failure) { calls.add(message); }
        public void info(final String message, final Throwable failure) { calls.add(message); }
        public void warn(final String message, final Throwable failure) { calls.add(message); }
        public void error(final String message, final Throwable failure) { calls.add(message); }
        public void fatal(final String message, final Throwable failure) { calls.add(message); }
    }
}
