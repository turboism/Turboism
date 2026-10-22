package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards the reviewed host artifact identities without restating them.
 *
 * <p>{@link ReviewedHostArtifacts} is the single declaration of the size and SHA-256 literals. The
 * per-version editor-model verification records under {@code compatibility/cubism/verification}
 * are the independent, byte-anchored copy of the same identity, so a typo in the shared constants
 * is caught by disagreement with those records instead of by a third literal copy here.</p>
 */
final class ReviewedHostArtifactsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final Path LOCAL_CUBISM_52 = Path.of("/tmp/cubism-5.2-exact.jar");
    private static final Path LOCAL_CUBISM_53 = Path.of("/tmp/cubism-5.3-exact.jar");

    @Test
    void reviewedArtifactsAgreeWithTheirVerificationRecords() throws IOException {
        for (final HostArtifactDigest digest : ReviewedHostArtifacts.all()) {
            final String version = ReviewedHostArtifacts.cubismVersionOf(digest).orElseThrow();
            final JsonNode artifact = JSON.readTree(Files.readString(
                            root().resolve("build/generated/verification/cubism-" + version + "-editor-model.json")))
                    .path("artifact");
            assertEquals(artifact.path("size").asLong(), digest.size(), version);
            assertEquals(artifact.path("sha256").asText(), digest.sha256(), version);
        }
    }

    @Test
    void reviewedDeclarationsAreWellFormedAndDistinct() {
        final List<HostArtifactDigest> all = ReviewedHostArtifacts.all();
        assertEquals(all.size(), ReviewedHostArtifacts.reviewedSha256s().size());
        assertEquals(all.size(), ReviewedHostArtifacts.reviewedCubismVersions().size());
        assertEquals(
                all.size(),
                all.stream()
                        .map(HostArtifactDigest::size)
                        .collect(Collectors.toSet())
                        .size());
        for (final HostArtifactDigest digest : all) {
            assertTrue(SHA256.matcher(digest.sha256()).matches(), digest.sha256());
            assertTrue(digest.size() > 0L);
        }
    }

    @Test
    void versionLookupIsExactAndFailsClosedForAnythingElse() {
        for (final HostArtifactDigest digest : ReviewedHostArtifacts.all()) {
            final String version = ReviewedHostArtifacts.cubismVersionOf(digest).orElseThrow();
            assertTrue(ReviewedHostArtifacts.reviewedCubismVersions().contains(version));
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
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            assertTrue(ReviewedHostArtifacts.admitsFullRuntime(version));
        }
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime("5.9.99"));
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime("5.3.3"));
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime(" 5.3.03"));
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime("4.2.03"));
        assertThrows(NullPointerException.class, () -> ReviewedHostArtifacts.admitsFullRuntime(null));
    }

    @Test
    void allCoversTheReviewedIdentityMatrixOldestFirst() {
        final List<String> versions = ReviewedHostArtifacts.all().stream()
                .map(digest -> ReviewedHostArtifacts.cubismVersionOf(digest).orElseThrow())
                .toList();
        assertEquals(ReviewedHostArtifacts.reviewedCubismVersions(), Set.copyOf(versions));
        assertEquals(
                versions.stream()
                        .sorted(Comparator.comparingLong(ReviewedHostArtifactsTest::order))
                        .toList(),
                versions);
    }

    @Test
    void localExactArtifactsMatchTheReviewedIdentitiesWhenPresent() throws IOException {
        assumeTrue(
                Files.isRegularFile(LOCAL_CUBISM_52) && Files.isRegularFile(LOCAL_CUBISM_53),
                "local exact Cubism artifacts are not staged on this machine");
        assertEquals(ReviewedHostArtifacts.CUBISM_5_2_03, HostArtifactDigest.from(LOCAL_CUBISM_52));
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02, HostArtifactDigest.from(LOCAL_CUBISM_53));
    }

    private static long order(final String version) {
        final String[] parts = version.split("\\.");
        return Long.parseLong(parts[0]) * 1_000_000L + Long.parseLong(parts[1]) * 1_000L + Long.parseLong(parts[2]);
    }

    private static Path root() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null && !Files.exists(cursor.resolve("settings.gradle.kts"))) {
            cursor = cursor.getParent();
        }
        if (cursor == null) {
            throw new IllegalStateException("project root not found");
        }
        return cursor;
    }
}
