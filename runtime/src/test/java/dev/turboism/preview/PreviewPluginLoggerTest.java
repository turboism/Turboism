package dev.turboism.preview;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewPluginLoggerTest {

    @TempDir
    Path temporary;

    @Test
    void automaticallyAttachesPluginIdToHostAndSessionRecords() throws Exception {
        final Path file = temporary.resolve("turboism.log");
        final List<String> hostRecords = new ArrayList<>();
        try (PreviewLog log = new PreviewLog(
            file,
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            (level, component, message, failure) ->
                hostRecords.add("[" + level + "][" + component + "] " + message)
        )) {
            new PreviewPluginLogger(log, "this-is-a-plugin").info("some msg");
        }

        assertEquals(List.of("[INFO][this-is-a-plugin] some msg"), hostRecords);
        assertTrue(Files.readString(file).contains(
            "1970-01-01T00:00:00Z [INFO] [this-is-a-plugin] some msg"
        ));
    }
}
