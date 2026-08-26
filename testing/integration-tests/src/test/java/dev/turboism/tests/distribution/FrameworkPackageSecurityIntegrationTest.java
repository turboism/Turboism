package dev.turboism.tests.distribution;

import dev.turboism.distribution.FrameworkPackageInspector;
import dev.turboism.distribution.LocalFrameworkPackageInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class FrameworkPackageSecurityIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void rejectsTraversalArchivePath() throws Exception {
        byte[] runtime = FrameworkPackageFixtures.jar("dev/turboism/bootstrap/Agent.class", "runtime");
        byte[] sdk = FrameworkPackageFixtures.jar("dev/turboism/sdk/Plugin.class", "sdk");
        Path input = tempDir.resolve("traversal.zip");
        Files.write(input, FrameworkPackageFixtures.frameworkZip(runtime, sdk,
            "../runtime.jar", "lib/runtime.jar", ""));

        FrameworkPackageInspector.Rejected rejected = assertInstanceOf(
            FrameworkPackageInspector.Rejected.class,
            new LocalFrameworkPackageInspector().inspect(input));

        assertEquals("ARCHIVE_PATH_UNSAFE", rejected.problems().get(0).code());
        assertEquals("../runtime.jar", rejected.problems().get(0).path());
    }

    @Test void rejectsPluginContaminationInRuntimeJar() throws Exception {
        assertRuntimeContamination("META-INF/turboism/plugin.json", "plugin-contamination.zip");
    }

    @Test void rejectsTestContaminationIncludingInnerTestClass() throws Exception {
        assertRuntimeContamination("dev/turboism/core/ManagerTest$Fixture.class", "test-contamination.zip");
    }

    @Test void rejectsCubismContentOutsideClassNamespace() throws Exception {
        assertRuntimeContamination("META-INF/cubism/host.properties", "cubism-contamination.zip");
    }

    private void assertRuntimeContamination(String entry, String file) throws Exception {
        byte[] runtime = FrameworkPackageFixtures.jar(entry, "forbidden",
            "dev/turboism/bootstrap/Agent.class", "runtime");
        byte[] sdk = FrameworkPackageFixtures.jar("dev/turboism/sdk/Plugin.class", "sdk");
        Path input = tempDir.resolve(file);
        Files.write(input, FrameworkPackageFixtures.frameworkZip(runtime, sdk));
        FrameworkPackageInspector.Rejected rejected = assertInstanceOf(
            FrameworkPackageInspector.Rejected.class, new LocalFrameworkPackageInspector().inspect(input));
        assertEquals("FRAMEWORK_CONTENT_CONTAMINATION", rejected.problems().get(0).code());
        assertEquals("artifacts[0]", rejected.problems().get(0).path());
    }
}
