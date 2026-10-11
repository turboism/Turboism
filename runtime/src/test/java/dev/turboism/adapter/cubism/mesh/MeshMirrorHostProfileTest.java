package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import org.junit.jupiter.api.Test;

class MeshMirrorHostProfileTest {

    @Test
    void exact5303UsesTheReviewedNative53TupleWithoutBackports() {
        final MeshMirrorHostProfile profile = MeshMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03)
                .orElseThrow();

        assertEquals(MeshMirrorHostProfile.reviewed52And53(), profile);
        assertNull(profile.toolEligibility());
        assertNull(profile.selectedPointMove());
        assertNull(profile.linkedDeletion());
    }

    @Test
    void exact5051UsesTheReviewed52TupleWithTheLegacyAxisDrawAndBackports() {
        for (final String version : List.of("5.0.00", "5.0.06", "5.1.00", "5.1.03")) {
            final MeshMirrorHostProfile profile =
                    MeshMirrorHostProfile.forReviewedVersion(version).orElseThrow();
            assertNull(profile.mirrorAxisDrawOwner(), version);
            assertNull(profile.mirrorAxisDrawMethod(), version);
            assertNull(profile.mirrorAxisDrawDescriptor(), version);
            assertEquals("C", profile.legacyAxisDraw().enclosingMethod(), version);
            assertEquals("()V", profile.legacyAxisDraw().enclosingDescriptor(), version);
            assertEquals(
                    version.startsWith("5.0.")
                            ? "com/live2d/cubism/view/context/J"
                            : "com/live2d/cubism/view/context/K",
                    profile.legacyAxisDraw().enclosingOwner(),
                    version);
            assertEquals(0, profile.legacyAxisDraw().callOrdinal(), version);
            assertNotNull(profile.toolEligibility(), version);
            assertNotNull(profile.selectedPointMove(), version);
            assertNotNull(profile.linkedDeletion(), version);
        }
    }

    @Test
    void unknownArtifactsRemainRejected() {
        assertTrue(MeshMirrorHostProfile.forArtifact(new HostArtifactDigest(1L, "0".repeat(64)))
                .isEmpty());
    }
}
