package dev.turboism.adapter.cubism.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupHostArtifactLocatorTest {

    @TempDir
    Path workingDirectory;

    @Test
    void resolvesExactlyOneRelativeOfficialJarEntryAgainstTheJvmWorkingDirectory() throws Exception {
        final Path artifact = workingDirectory.resolve("app/lib/Live2D_Cubism.jar");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, new byte[] {1, 2, 3});
        final String classPath = String.join(File.pathSeparator, "app/lib/Live2D_Cubism.jar", "app/lib/other.jar");

        final StartupHostArtifactLocator.Result result = StartupHostArtifactLocator.locate(classPath, workingDirectory);

        assertEquals(StartupHostArtifactLocator.Status.FOUND, result.status());
        assertEquals(artifact.toRealPath(), result.artifact());
    }
}
