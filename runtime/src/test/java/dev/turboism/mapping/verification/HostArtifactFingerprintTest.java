package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostArtifactFingerprintTest {

    @Test
    void exactVersionSizeAndSha256MustAllMatch() throws Exception {
        Path artifact = Files.createTempFile("turboism-host-artifact", ".jar");
        Files.writeString(artifact, "cubism-5.3.02-static-fixture");

        HostArtifactFingerprint actual = HostArtifactFingerprint.from("5.3.02", artifact);

        assertTrue(actual.matches(new HostArtifactFingerprint(
            "5.3.02",
            Files.size(artifact),
            actual.sha256()
        )));
        assertFalse(actual.matches(new HostArtifactFingerprint(
            "5.3.01",
            Files.size(artifact),
            actual.sha256()
        )));
        assertFalse(actual.matches(new HostArtifactFingerprint(
            "5.3.02",
            Files.size(artifact) + 1,
            actual.sha256()
        )));
        assertFalse(actual.matches(new HostArtifactFingerprint(
            "5.3.02",
            Files.size(artifact),
            "0".repeat(64)
        )));
        assertEquals(actual.sha256(), actual.sha256().toLowerCase());
    }
}
