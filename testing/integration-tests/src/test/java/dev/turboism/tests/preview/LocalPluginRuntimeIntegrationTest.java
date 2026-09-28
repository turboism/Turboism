package dev.turboism.tests.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.preview.LocalPluginRuntime;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

class LocalPluginRuntimeIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsARealPluginJarAndIsolatesAnInvalidNeighbor() throws Exception {
        LocalPluginRuntimeLoadScenario.verify(temporaryDirectory);
    }

    @Test
    void hasPreviewPluginContextFactoryExtractionBoundary() throws Exception {
        final Class<?> factory = Class.forName(
                "dev.turboism.preview.PreviewPluginContextFactory", false, LocalPluginRuntime.class.getClassLoader());
        assertEquals("dev.turboism.preview.PreviewPluginContextFactory", factory.getName());
    }

    @Test
    @ResourceLock(PreviewContextServicesPluginJarFixture.MARKER_DIRECTORY_PROPERTY)
    void realPluginJarExposesPreviewContextServicesWithExactResults() throws Exception {
        PreviewContextServicesScenario.verify(temporaryDirectory);
    }
}
