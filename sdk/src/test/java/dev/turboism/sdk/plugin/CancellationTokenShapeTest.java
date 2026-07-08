package dev.turboism.sdk.plugin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CancellationTokenShapeTest {

    @Test
    void cancellationTokenDeclaresMinimalSdkSafeContract_whenInspectingSourceContract() throws IOException {
        String source = source("CancellationToken");

        assertTrue(source.contains("public interface CancellationToken"));
        assertTrue(source.contains("boolean isCancellationRequested()"));
        assertTrue(source.contains("void checkCanceled() throws TaskCanceledException"));
        assertTrue(!source.contains("java.util.concurrent"));
        assertTrue(!source.contains("io.github.resilience4j"));
    }

    @Test
    void taskCanceledExceptionIsPublicFinalRuntimeException_whenInspectingSourceContract() throws IOException {
        String source = source("TaskCanceledException");

        assertTrue(source.contains("public final class TaskCanceledException extends RuntimeException"));
        assertTrue(!source.contains("java.util.concurrent"));
        assertTrue(!source.contains("io.github.resilience4j"));
    }

    private static String source(final String sourceName) throws IOException {
        return Files.readString(Path.of("src/main/java/dev/turboism/sdk/plugin", sourceName + ".java"));
    }
}
