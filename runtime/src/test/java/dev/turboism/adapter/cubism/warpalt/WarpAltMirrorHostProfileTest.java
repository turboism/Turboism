package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WarpAltMirrorHostProfileTest {

    @Test
    void admitsEveryReviewedArtifact() {
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03).isPresent());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_02).isPresent());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_2_03).isPresent());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03_JOGL).isEmpty());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(
            new HostArtifactDigest(1L, "0".repeat(64))).isEmpty());
    }

    @Test
    void sharedSelectorsPinTheReviewedDragTickSelector() {
        for (final HostArtifactDigest artifact : List.of(
            ReviewedHostArtifacts.CUBISM_5_2_03,
            ReviewedHostArtifacts.CUBISM_5_3_02,
            ReviewedHostArtifacts.CUBISM_5_3_03)) {
            final WarpAltMirrorHostProfile profile =
                WarpAltMirrorHostProfile.forArtifact(artifact).orElseThrow();
            assertEquals(
                "com/live2d/cubism/doc/model/deformer/warp/WarpPointRef", profile.pointMoveOwner());
            assertEquals("moveToOnLocal", profile.pointMoveMethod());
            assertEquals(
                "(Lcom/live2d/graphics3d/type/GVector2;F)V",
                profile.pointMoveDescriptor()
            );
            assertEquals(
                "com/live2d/cubism/view/context/temporaryHandler/a", profile.dragTickOwner());
            assertEquals("b", profile.dragTickMethod());
            assertEquals("com/live2d/cubism/view/context/a/b", profile.stripOwner());
            assertEquals("a", profile.stripLayoutMethod());
            assertEquals("com/live2d/cubism/doc/model/deformer/warp/a$b",
                profile.greenTickOwner());
        }
    }

    @Test
    void stripMountSelectorFollowsTheObfuscationGeneration() {
        assertEquals("L", WarpAltMirrorHostProfile
            .forArtifact(ReviewedHostArtifacts.CUBISM_5_2_03).orElseThrow().stripMountMethod());
        assertEquals("R", WarpAltMirrorHostProfile
            .forArtifact(ReviewedHostArtifacts.CUBISM_5_3_02).orElseThrow().stripMountMethod());
        assertEquals("R", WarpAltMirrorHostProfile
            .forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow().stripMountMethod());
    }

    @Test
    void everyCoveredArtifactIsListedByTheReviewedRegistry() {
        final List<HostArtifactDigest> reviewed = ReviewedHostArtifacts.all();
        assertTrue(reviewed.contains(ReviewedHostArtifacts.CUBISM_5_2_03));
        assertTrue(reviewed.contains(ReviewedHostArtifacts.CUBISM_5_3_02));
        assertTrue(reviewed.contains(ReviewedHostArtifacts.CUBISM_5_3_03));
    }
}
