package dev.turboism.graal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class GraalHostManagerUncheckedFailureTest {

    @Test
    void uncheckedSubmissionFailureSettlesAndRemovesTheExecution() throws Exception {
        final ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public <T extends JsonNode> T valueToTree(final Object fromValue) {
                throw new IllegalStateException("mapper failed");
            }
        };
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (GraalHostManager manager = new GraalHostManager(
            configuration(), diagnostics::add, failingMapper
        )) {
            final GraalHostManager.Execution execution = manager.submit(
                "unchecked", "", Map.of(), (operation, payload) -> "{}"
            );
            final GraalHostManager.TransportResult result = execution.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(GraalHostManager.Status.FAILED, result.status());
            assertEquals("GRAAL_HOST_SUBMISSION_FAILED", result.code());
            assertFalse(execution.cancel());
        }
    }

    private static GraalHostConfiguration configuration() throws Exception {
        final String javaBinary = Path.of(
            System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"
        ).toString();
        final String testClasses = Path.of(
            GraalHostManagerTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).toString();
        return new GraalHostConfiguration(
            true,
            javaBinary,
            testClasses,
            GraalHostManagerTest.EchoHost.class.getName(),
            5_000L
        );
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
