package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WarpAltMirrorHostProfileTest {

    @Test
    void admitsOnlyTheReviewed5303Artifact() {
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03).isPresent());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_02).isEmpty());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_2_03).isEmpty());
    }

    @Test
    void reviewed5303ProfilePinsTheReviewedDragTickSelector() {
        final WarpAltMirrorHostProfile profile =
            WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow();
        assertEquals(
            "com/live2d/cubism/doc/model/deformer/warp/WarpPointRef", profile.pointMoveOwner());
        assertEquals("moveToOnLocal", profile.pointMoveMethod());
        assertEquals(
            "(Lcom/live2d/graphics3d/type/GVector2;F)V",
            profile.pointMoveDescriptor()
        );
    }

    @Test
    void everyCoveredArtifactIsListedByTheReviewedRegistry() {
        final List<dev.turboism.mapping.verification.HostArtifactDigest> reviewed =
            ReviewedHostArtifacts.all();
        assertTrue(reviewed.contains(ReviewedHostArtifacts.CUBISM_5_3_03));
    }
}
