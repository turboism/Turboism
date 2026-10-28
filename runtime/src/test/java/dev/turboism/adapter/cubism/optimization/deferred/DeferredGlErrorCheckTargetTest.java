package dev.turboism.adapter.cubism.optimization.deferred;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Offline admission coverage for the deferred GL error check. The runner keeps
 * the combined {@code launcher.mesaGlThread} option effective only on
 * generations whose uniform-location lifecycle seam is reviewed, so the
 * admitted digest set must never drift from that seam coverage — a gap
 * surfaces as {@code deferred=INACTIVE reason=admission-refused} after ready
 * and the workload is refused on the T22 glthread-only regression guard.
 */
final class DeferredGlErrorCheckTargetTest {

    /** Exactly the uniform-seam-reviewed generations admit the deferred check. */
    @Test
    void coverageMatchesTheReviewedUniformSeamGenerations() {
        Set<String> admitted = DeferredGlErrorCheckTarget.all().stream()
                .map(DeferredGlErrorCheckTarget::version)
                .collect(Collectors.toSet());
        assertEquals(
                ReviewedHostArtifacts.all().stream()
                        .flatMap(a -> ReviewedHostArtifacts.cubismVersionOf(a).stream())
                        .collect(Collectors.toSet()),
                admitted,
                "runner resolves mesaGlThread on all uniform-seam-reviewed "
                        + "generations; an uncovered member refuses validation at ready time");
        for (HostArtifactDigest artifact : ReviewedHostArtifacts.all()) {
            String version = ReviewedHostArtifacts.cubismVersionOf(artifact).orElseThrow();
            assertEquals(
                    UniformLocationLifecycleTransformer.supportedEditorVersion(version),
                    DeferredGlErrorCheckTarget.of(artifact).isPresent(),
                    "deferred admission drifted from the uniform seam coverage on " + version);
        }
    }

    @Test
    void everyTargetResolvesItsDigestAndItsSeamOwners() {
        for (DeferredGlErrorCheckTarget target : DeferredGlErrorCheckTarget.all()) {
            assertEquals(target, DeferredGlErrorCheckTarget.of(target.digest()).orElseThrow());
            String version = target.version();
            assertEquals(UniformLocationLifecycleTransformer.Role.ERROR.ownerForVersion(version), target.errorOwner());
            assertEquals(UniformLocationLifecycleTransformer.Role.FRAME.ownerForVersion(version), target.frameOwner());
            // The installer's composed-shape probe resolves lifecycle owners by
            // artifact digest; a target whose digest lacked that mapping would
            // fail closed at install time on a seam-reviewed generation.
            assertTrue(UniformLocationLifecycleTransformer.supportedEditor(target.digest()), version);
        }
    }

    @Test
    void ownersFollowTheReviewedNamingSplit() {
        for (HostArtifactDigest artifact : ReviewedHostArtifacts.all()) {
            String version = ReviewedHostArtifacts.cubismVersionOf(artifact).orElseThrow();
            DeferredGlErrorCheckTarget target =
                    DeferredGlErrorCheckTarget.of(artifact).orElseThrow();
            assertEquals(DeferredGlErrorCheckTarget.FRAME_OWNER, target.frameOwner(), version);
            if (version.startsWith("5.0.") || version.startsWith("5.1.")) {
                assertEquals(DeferredGlErrorCheckTarget.ERROR_OWNER_5051, target.errorOwner(), version);
            } else if (version.startsWith("5.2.")) {
                assertEquals(DeferredGlErrorCheckTarget.ERROR_OWNER_5203, target.errorOwner(), version);
            } else {
                assertEquals(DeferredGlErrorCheckTarget.ERROR_OWNER, target.errorOwner(), version);
            }
        }
    }

    @Test
    void unreviewedGenerationsAreRejected() {
        HostArtifactDigest alien = new HostArtifactDigest(1L, "9".repeat(64));
        assertTrue(DeferredGlErrorCheckTarget.of(alien).isEmpty());
    }
}
