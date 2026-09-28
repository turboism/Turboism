package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ReviewedHostArtifactCliTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void unreviewedArtifactFailsClosed() throws Exception {
        final Path artifact = temporaryDirectory.resolve("Live2D_Cubism.jar");
        Files.write(artifact, new byte[] {1, 2, 3, 4});

        assertTrue(ReviewedHostArtifactCli.versionOf(artifact).isEmpty());
    }
}
