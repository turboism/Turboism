package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pins the reviewed host artifact identities.
 *
 * <p>This is the only test allowed to restate the reviewed size and SHA-256 literals. Every other
 * test and all production code reference {@link ReviewedHostArtifacts}, so a typo in the shared
 * constants is caught here instead of silently agreeing with itself everywhere else.</p>
 */
final class ReviewedHostArtifactsTest {

    private static final Path LOCAL_CUBISM_52 = Path.of("/tmp/cubism-5.2-exact.jar");
    private static final Path LOCAL_CUBISM_53 = Path.of("/tmp/cubism-5.3-exact.jar");

    @Test
    void reviewedArtifactsDeclareTheExactReviewedIdentities() {
        assertEquals(40_805_584L, ReviewedHostArtifacts.CUBISM_5_2_03.size());
        assertEquals(
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
            ReviewedHostArtifacts.CUBISM_5_2_03.sha256()
        );
        assertEquals(41_922_739L, ReviewedHostArtifacts.CUBISM_5_3_02.size());
        assertEquals(
            "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
            ReviewedHostArtifacts.CUBISM_5_3_02.sha256()
        );
        assertEquals(42_010_633L, ReviewedHostArtifacts.CUBISM_5_3_03.size());
        assertEquals(
            "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166",
            ReviewedHostArtifacts.CUBISM_5_3_03.sha256()
        );
    }

    @Test
    void versionLookupIsExactAndFailsClosedForAnythingElse() {
        assertEquals(
            ReviewedHostArtifacts.CUBISM_5_2_03_VERSION,
            ReviewedHostArtifacts.cubismVersionOf(ReviewedHostArtifacts.CUBISM_5_2_03).orElseThrow()
        );
        assertEquals(
            ReviewedHostArtifacts.CUBISM_5_3_02_VERSION,
            ReviewedHostArtifacts.cubismVersionOf(ReviewedHostArtifacts.CUBISM_5_3_02).orElseThrow()
        );
        assertEquals(
            ReviewedHostArtifacts.CUBISM_5_3_03_VERSION,
            ReviewedHostArtifacts.cubismVersionOf(ReviewedHostArtifacts.CUBISM_5_3_03).orElseThrow()
        );

        final HostArtifactDigest sizeOnlyMatch = new HostArtifactDigest(
            ReviewedHostArtifacts.CUBISM_5_2_03.size(),
            ReviewedHostArtifacts.CUBISM_5_3_02.sha256()
        );
        final HostArtifactDigest shaOnlyMatch = new HostArtifactDigest(
            ReviewedHostArtifacts.CUBISM_5_3_02.size(),
            ReviewedHostArtifacts.CUBISM_5_2_03.sha256()
        );
        assertTrue(ReviewedHostArtifacts.cubismVersionOf(sizeOnlyMatch).isEmpty());
        assertTrue(ReviewedHostArtifacts.cubismVersionOf(shaOnlyMatch).isEmpty());
        assertFalse(ReviewedHostArtifacts.isReviewed(sizeOnlyMatch));
        assertFalse(ReviewedHostArtifacts.isReviewed(shaOnlyMatch));
    }

    @Test
    void fullRuntimeAdmissionIsExactAndFailsClosedForUnreviewedVersions() {
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.2.03"));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.02"));
        assertTrue(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime("5.3.04"));
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime("5.3.3"));
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime(" 5.3.03"));
    }

    @Test
    void allCoversTheReviewedIdentityMatrixOldestFirst() {
        assertEquals(
            java.util.List.of(
                ReviewedHostArtifacts.CUBISM_5_2_03,
                ReviewedHostArtifacts.CUBISM_5_3_02,
                ReviewedHostArtifacts.CUBISM_5_3_03
            ),
            ReviewedHostArtifacts.all()
        );
    }

    @Test
    void localExactArtifactsMatchTheReviewedIdentitiesWhenPresent() throws IOException {
        assumeTrue(
            Files.isRegularFile(LOCAL_CUBISM_52) && Files.isRegularFile(LOCAL_CUBISM_53),
            "local exact Cubism artifacts are not staged on this machine"
        );
        assertEquals(ReviewedHostArtifacts.CUBISM_5_2_03, HostArtifactDigest.from(LOCAL_CUBISM_52));
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02, HostArtifactDigest.from(LOCAL_CUBISM_53));
    }
}
