package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the reviewed host artifact identities.
 *
 * <p>This is the only test allowed to restate the reviewed size and SHA-256 literals. Every other
 * test and all production code reference {@link ReviewedHostArtifacts}, so a typo in the shared
 * constants is caught here instead of silently agreeing with itself everywhere else.</p>
 */
final class ReviewedHostArtifactsTest {

    private static final Set<String> REVIEWED_VERSIONS = Set.of(
            "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
            "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04");

    private static final Path LOCAL_CUBISM_52 = Path.of("/tmp/cubism-5.2-exact.jar");
    private static final Path LOCAL_CUBISM_53 = Path.of("/tmp/cubism-5.3-exact.jar");

    @Test
    void reviewedArtifactsDeclareTheExactReviewedIdentities() {
        assertEquals(36_091_669L, ReviewedHostArtifacts.CUBISM_5_0_00.size());
        assertEquals(
                "42ee222a05478f48739bb1fbf7e7bc7350b21004e506e1b555d1d55c584d99cd",
                ReviewedHostArtifacts.CUBISM_5_0_00.sha256());
        assertEquals(36_099_677L, ReviewedHostArtifacts.CUBISM_5_0_01.size());
        assertEquals(
                "1aa93b1d9d8da88e8c28de06b680c41a73fc70243307d856d4a2967e6e487a0b",
                ReviewedHostArtifacts.CUBISM_5_0_01.sha256());
        assertEquals(36_100_830L, ReviewedHostArtifacts.CUBISM_5_0_02.size());
        assertEquals(
                "39e73c8238acba5270422a6e0f228c66a733177d009c710721381285ab0af072",
                ReviewedHostArtifacts.CUBISM_5_0_02.sha256());
        assertEquals(36_098_490L, ReviewedHostArtifacts.CUBISM_5_0_03.size());
        assertEquals(
                "05bc9b872527fed3dc2641ff833b03d86e6fdafbe6faa294eb3d0afcc0f88a66",
                ReviewedHostArtifacts.CUBISM_5_0_03.sha256());
        assertEquals(36_098_501L, ReviewedHostArtifacts.CUBISM_5_0_04.size());
        assertEquals(
                "7298c5731525a2bd5ac4e6d5246f1f08603651b0ec37cf5dc3e8ad1802ad8758",
                ReviewedHostArtifacts.CUBISM_5_0_04.sha256());
        assertEquals(36_644_752L, ReviewedHostArtifacts.CUBISM_5_0_05.size());
        assertEquals(
                "1225893a466d486415773b0a62ec61a75badb2cb5cb2fce8ae744b0123becec4",
                ReviewedHostArtifacts.CUBISM_5_0_05.sha256());
        assertEquals(36_677_328L, ReviewedHostArtifacts.CUBISM_5_0_06.size());
        assertEquals(
                "cbb9d9ecddb9be0252733ece055ecae7d709de5c56647b7226f6c44607e646f2",
                ReviewedHostArtifacts.CUBISM_5_0_06.sha256());
        assertEquals(38_664_130L, ReviewedHostArtifacts.CUBISM_5_1_00.size());
        assertEquals(
                "cdda37ed4105cf5e4ecb5811f7065df35b9b9a3319bcb74f8e9189d91e8d3f05",
                ReviewedHostArtifacts.CUBISM_5_1_00.sha256());
        assertEquals(38_682_375L, ReviewedHostArtifacts.CUBISM_5_1_01.size());
        assertEquals(
                "ddb1f5569bc8da49f3c9f6b6eaec3fe57e4f7abec67c9c44079233ea51056c02",
                ReviewedHostArtifacts.CUBISM_5_1_01.sha256());
        assertEquals(38_910_839L, ReviewedHostArtifacts.CUBISM_5_1_02.size());
        assertEquals(
                "0567130093015c969d32b91c6a48d4603c28c8224c6a14fa969491f8974f63ca",
                ReviewedHostArtifacts.CUBISM_5_1_02.sha256());
        assertEquals(38_910_843L, ReviewedHostArtifacts.CUBISM_5_1_03.size());
        assertEquals(
                "a3d916077bd7a176345233da95f1eb0a8bbdf8967fd08162776d5bf6d746009b",
                ReviewedHostArtifacts.CUBISM_5_1_03.sha256());
        assertEquals(40_625_430L, ReviewedHostArtifacts.CUBISM_5_2_00.size());
        assertEquals(
                "25c41c81ddb3af884b7f0d61a02eba9b09f0412bdc025997c419de7ab7158cf1",
                ReviewedHostArtifacts.CUBISM_5_2_00.sha256());
        assertEquals(40_625_435L, ReviewedHostArtifacts.CUBISM_5_2_01.size());
        assertEquals(
                "c1b70ca8c571c4f4df4ebce63b3381f6bed1b132ab0ed539046fd0609e2b00ac",
                ReviewedHostArtifacts.CUBISM_5_2_01.sha256());
        assertEquals(40_626_770L, ReviewedHostArtifacts.CUBISM_5_2_02.size());
        assertEquals(
                "ba1f31a1846490c3f111a4ae99887c36bcc0c1bc1a3cf970eb5a7aeed7902aff",
                ReviewedHostArtifacts.CUBISM_5_2_02.sha256());
        assertEquals(40_805_584L, ReviewedHostArtifacts.CUBISM_5_2_03.size());
        assertEquals(
                "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
                ReviewedHostArtifacts.CUBISM_5_2_03.sha256());
        assertEquals(41_859_315L, ReviewedHostArtifacts.CUBISM_5_3_00.size());
        assertEquals(
                "c89dcaf17b6bfad7227db30bffc2f29cdf49b8e4b0cec1f12af2d85b1c13e767",
                ReviewedHostArtifacts.CUBISM_5_3_00.sha256());
        assertEquals(41_862_401L, ReviewedHostArtifacts.CUBISM_5_3_01.size());
        assertEquals(
                "694bb49c66ddcf15695dbd8f66e1cbaeb0097f651e2e77c327567244f801d2f4",
                ReviewedHostArtifacts.CUBISM_5_3_01.sha256());
        assertEquals(41_922_739L, ReviewedHostArtifacts.CUBISM_5_3_02.size());
        assertEquals(
                "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
                ReviewedHostArtifacts.CUBISM_5_3_02.sha256());
        assertEquals(42_010_633L, ReviewedHostArtifacts.CUBISM_5_3_03.size());
        assertEquals(
                "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166",
                ReviewedHostArtifacts.CUBISM_5_3_03.sha256());
        assertEquals(42_203_164L, ReviewedHostArtifacts.CUBISM_5_3_04.size());
        assertEquals(
                "c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d",
                ReviewedHostArtifacts.CUBISM_5_3_04.sha256());
    }

    @Test
    void versionLookupIsExactAndFailsClosedForAnythingElse() {
        for (final HostArtifactDigest digest : ReviewedHostArtifacts.all()) {
            final String version = ReviewedHostArtifacts.cubismVersionOf(digest).orElseThrow();
            assertTrue(REVIEWED_VERSIONS.contains(version));
            assertTrue(ReviewedHostArtifacts.isReviewed(digest));
        }

        final HostArtifactDigest sizeOnlyMatch = new HostArtifactDigest(
                ReviewedHostArtifacts.CUBISM_5_2_03.size(), ReviewedHostArtifacts.CUBISM_5_3_02.sha256());
        final HostArtifactDigest shaOnlyMatch = new HostArtifactDigest(
                ReviewedHostArtifacts.CUBISM_5_3_02.size(), ReviewedHostArtifacts.CUBISM_5_2_03.sha256());
        assertTrue(ReviewedHostArtifacts.cubismVersionOf(sizeOnlyMatch).isEmpty());
        assertTrue(ReviewedHostArtifacts.cubismVersionOf(shaOnlyMatch).isEmpty());
        assertFalse(ReviewedHostArtifacts.isReviewed(sizeOnlyMatch));
        assertFalse(ReviewedHostArtifacts.isReviewed(shaOnlyMatch));
    }

    @Test
    void fullRuntimeAdmissionIsExactAndFailsClosedForUnreviewedVersions() {
        for (final String version : REVIEWED_VERSIONS) {
            assertTrue(ReviewedHostArtifacts.admitsFullRuntime(version));
        }
        assertEquals(REVIEWED_VERSIONS, ReviewedHostArtifacts.reviewedCubismVersions());
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime("5.3.05"));
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime("5.3.3"));
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime(" 5.3.03"));
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime("4.2.03"));
        assertThrows(NullPointerException.class, () -> ReviewedHostArtifacts.admitsFullRuntime(null));
    }

    @Test
    void allCoversTheReviewedIdentityMatrixOldestFirst() {
        assertEquals(
                java.util.List.of(
                        ReviewedHostArtifacts.CUBISM_5_0_00,
                        ReviewedHostArtifacts.CUBISM_5_0_01,
                        ReviewedHostArtifacts.CUBISM_5_0_02,
                        ReviewedHostArtifacts.CUBISM_5_0_03,
                        ReviewedHostArtifacts.CUBISM_5_0_04,
                        ReviewedHostArtifacts.CUBISM_5_0_05,
                        ReviewedHostArtifacts.CUBISM_5_0_06,
                        ReviewedHostArtifacts.CUBISM_5_1_00,
                        ReviewedHostArtifacts.CUBISM_5_1_01,
                        ReviewedHostArtifacts.CUBISM_5_1_02,
                        ReviewedHostArtifacts.CUBISM_5_1_03,
                        ReviewedHostArtifacts.CUBISM_5_2_00,
                        ReviewedHostArtifacts.CUBISM_5_2_01,
                        ReviewedHostArtifacts.CUBISM_5_2_02,
                        ReviewedHostArtifacts.CUBISM_5_2_03,
                        ReviewedHostArtifacts.CUBISM_5_3_00,
                        ReviewedHostArtifacts.CUBISM_5_3_01,
                        ReviewedHostArtifacts.CUBISM_5_3_02,
                        ReviewedHostArtifacts.CUBISM_5_3_03,
                        ReviewedHostArtifacts.CUBISM_5_3_04),
                ReviewedHostArtifacts.all());
    }

    @Test
    void localExactArtifactsMatchTheReviewedIdentitiesWhenPresent() throws IOException {
        assumeTrue(
                Files.isRegularFile(LOCAL_CUBISM_52) && Files.isRegularFile(LOCAL_CUBISM_53),
                "local exact Cubism artifacts are not staged on this machine");
        assertEquals(ReviewedHostArtifacts.CUBISM_5_2_03, HostArtifactDigest.from(LOCAL_CUBISM_52));
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02, HostArtifactDigest.from(LOCAL_CUBISM_53));
    }
}
