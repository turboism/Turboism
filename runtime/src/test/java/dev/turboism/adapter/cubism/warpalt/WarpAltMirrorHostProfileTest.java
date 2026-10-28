package dev.turboism.adapter.cubism.warpalt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WarpAltMirrorHostProfileTest {

    @Test
    void latestStripMappingDoesNotChangeEarlierVersions() {
        for (final String version :
                WarpAltMirrorHostProfile.reviewedClassSha256().keySet()) {
            final var profile =
                    WarpAltMirrorHostProfile.forReviewedVersion(version).orElseThrow();
            final String expected = version.equals("5.3.04")
                    ? "com/live2d/cubism/view/context/b/b"
                    : "com/live2d/cubism/view/context/a/b";
            assertEquals(expected, profile.stripOwner(), version);
            assertTrue(
                    WarpAltMirrorHostProfile.reviewedClassSha256().get(version).containsKey(expected), version);
            assertEquals(version.startsWith("5.3.") ? "R" : "L", profile.stripMountMethod(), version);
            assertEquals(version.startsWith("5.3.") ? "H" : "E", profile.stripGroupField(), version);
            assertEquals("com/live2d/cubism/view/context/guiEntity/q", profile.stripIconSetOwner(), version);
        }
    }

    @Test
    void stripGroupFieldFollowsObfuscationGeneration() {
        for (final String version : List.of("5.0.00", "5.0.06", "5.1.00", "5.1.03", "5.2.03")) {
            final var profile =
                    WarpAltMirrorHostProfile.forReviewedVersion(version).orElseThrow();
            assertEquals("E", profile.stripGroupField(), version);
        }
        for (final String version : List.of("5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04")) {
            final var profile =
                    WarpAltMirrorHostProfile.forReviewedVersion(version).orElseThrow();
            assertEquals("H", profile.stripGroupField(), version);
        }
    }

    @Test
    void admitsEveryReviewedArtifact() {
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03)
                .isPresent());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_02)
                .isPresent());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_2_03)
                .isPresent());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_0_00)
                .isPresent());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_0_06)
                .isPresent());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_1_00)
                .isPresent());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_1_03)
                .isPresent());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03_JOGL)
                .isEmpty());
        assertTrue(WarpAltMirrorHostProfile.forArtifact(new HostArtifactDigest(1L, "0".repeat(64)))
                .isEmpty());
    }

    @Test
    void sharedSelectorsPinTheReviewedDragTickSelector() {
        for (final HostArtifactDigest artifact : List.of(
                ReviewedHostArtifacts.CUBISM_5_0_06,
                ReviewedHostArtifacts.CUBISM_5_1_03,
                ReviewedHostArtifacts.CUBISM_5_2_03,
                ReviewedHostArtifacts.CUBISM_5_3_02,
                ReviewedHostArtifacts.CUBISM_5_3_03)) {
            final WarpAltMirrorHostProfile profile =
                    WarpAltMirrorHostProfile.forArtifact(artifact).orElseThrow();
            assertEquals("com/live2d/cubism/doc/model/deformer/warp/WarpPointRef", profile.pointMoveOwner());
            assertEquals("moveToOnLocal", profile.pointMoveMethod());
            assertEquals("(Lcom/live2d/graphics3d/type/GVector2;F)V", profile.pointMoveDescriptor());
            assertEquals("com/live2d/cubism/view/context/temporaryHandler/a", profile.dragTickOwner());
            assertEquals("b", profile.dragTickMethod());
            assertEquals("com/live2d/cubism/view/context/a/b", profile.stripOwner());
            assertEquals("a", profile.stripLayoutMethod());
            assertEquals("com/live2d/cubism/doc/model/deformer/warp/a$b", profile.greenTickOwner());
        }
    }

    @Test
    void stripMountSelectorFollowsTheObfuscationGeneration() {
        assertEquals(
                "L",
                WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_0_06)
                        .orElseThrow()
                        .stripMountMethod());
        assertEquals(
                "L",
                WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_1_03)
                        .orElseThrow()
                        .stripMountMethod());
        assertEquals(
                "L",
                WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_2_03)
                        .orElseThrow()
                        .stripMountMethod());
        assertEquals(
                "R",
                WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_02)
                        .orElseThrow()
                        .stripMountMethod());
        assertEquals(
                "R",
                WarpAltMirrorHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03)
                        .orElseThrow()
                        .stripMountMethod());
    }

    @Test
    void everyCoveredArtifactIsListedByTheReviewedRegistry() {
        final List<HostArtifactDigest> reviewed = ReviewedHostArtifacts.all();
        assertTrue(reviewed.contains(ReviewedHostArtifacts.CUBISM_5_2_03));
        assertTrue(reviewed.contains(ReviewedHostArtifacts.CUBISM_5_3_02));
        assertTrue(reviewed.contains(ReviewedHostArtifacts.CUBISM_5_3_03));
    }
}
