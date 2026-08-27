package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class VerifiedPerformanceProbeInstallerProfileTest {

    @Test
    void exact5302And5303ArtifactsSelectIndependentProfiles() {
        final VerifiedPerformanceProbeInstaller.ProbeProfile cubism5302 =
            VerifiedPerformanceProbeInstaller.profileForArtifact(
                ReviewedHostArtifacts.CUBISM_5_3_02
            );
        final VerifiedPerformanceProbeInstaller.ProbeProfile cubism5303 =
            VerifiedPerformanceProbeInstaller.profileForArtifact(
                ReviewedHostArtifacts.CUBISM_5_3_03
            );

        assertEquals("5.3.02", cubism5302.cubismVersion());
        assertEquals(7, cubism5302.targets().size());
        assertEquals("5.3.03", cubism5303.cubismVersion());
        assertEquals(7, cubism5303.targets().size());
        assertEquals(cubism5302.targets(), cubism5303.targets());
    }

    @Test
    void everyOtherArtifactRemainsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
            VerifiedPerformanceProbeInstaller.profileForArtifact(
                new HostArtifactDigest(1L, "0".repeat(64))
            )
        );
    }
}
