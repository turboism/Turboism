package dev.turboism.plugin.projectinspector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProjectInspectorConsumerContractTest {

    @Test
    void consumesLocalizationAndRuntimeOwnedAsyncProjectWorkspaceRead() throws Exception {
        final String source = Files.readString(
                Path.of("src/main/java/dev/turboism/plugin/projectinspector/ProjectInspectorPlugin.java"));

        assertTrue(source.contains("context.localization()"));
        assertTrue(source.contains("context.hostReads()"));
        assertTrue(source.contains("AsyncHostReadIntent.PROJECT_WORKSPACE_SNAPSHOT"));
        assertTrue(source.contains("Duration.ofSeconds(2)"));
        assertFalse(source.contains("context.cubismRead()"));
        assertFalse(source.contains("ExecutorService"));
        assertFalse(source.contains("Executors."));
        assertFalse(source.contains("new Thread("));
        assertFalse(source.contains("ThreadFactory"));
    }
}
