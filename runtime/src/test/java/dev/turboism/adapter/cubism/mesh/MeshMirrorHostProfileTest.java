package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshMirrorHostProfileTest {

    @Test
    void exact5303UsesTheReviewedNative53TupleWithoutBackports() {
        final MeshMirrorHostProfile profile = MeshMirrorHostProfile.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_3_03
        ).orElseThrow();

        assertEquals(MeshMirrorHostProfile.reviewed52And53(), profile);
        assertNull(profile.toolEligibility());
        assertNull(profile.selectedPointMove());
        assertNull(profile.linkedDeletion());
    }

    @Test
    void unknownArtifactsRemainRejected() {
        assertTrue(MeshMirrorHostProfile.forArtifact(
            new HostArtifactDigest(1L, "0".repeat(64))
        ).isEmpty());
    }
}
