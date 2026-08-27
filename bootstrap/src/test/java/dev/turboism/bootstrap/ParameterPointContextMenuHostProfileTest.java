package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterPointContextMenuHostProfileTest {

    @Test
    void selectsIndependentExactOwnersForEveryReviewedArtifact() {
        final ParameterPointContextMenuHostProfile v52 = ParameterPointContextMenuHostProfile
            .forArtifact(ReviewedHostArtifacts.CUBISM_5_2_03).orElseThrow();
        final ParameterPointContextMenuHostProfile v5302 = ParameterPointContextMenuHostProfile
            .forArtifact(ReviewedHostArtifacts.CUBISM_5_3_02).orElseThrow();
        final ParameterPointContextMenuHostProfile v5303 = ParameterPointContextMenuHostProfile
            .forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow();

        assertProfile(v52, "Q");
        assertProfile(v5302, "ab");
        assertProfile(v5303, "ac");
    }

    @Test
    void rejectsUnknownArtifacts() {
        assertTrue(ParameterPointContextMenuHostProfile.forArtifact(
            new HostArtifactDigest(1L, "0".repeat(64))
        ).isEmpty());
    }

    private static void assertProfile(
        final ParameterPointContextMenuHostProfile profile,
        final String ownerSuffix
    ) {
        final String owner = "com/live2d/cubism/view/palette/parameter/ui/" + ownerSuffix;
        assertEquals(owner, profile.owner());
        assertEquals("L" + owner + "$b;", profile.contextDescriptor());
    }
}
