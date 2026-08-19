package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Sole public resolver entrypoint pinned to the reviewed clip-mask trust roots.
 *
 * <p>The manifest dispatches on the artifact digest, so the exact 5.2.03 and 5.3.02 artifacts each
 * resolve their own reviewed record and nothing else is admitted.</p>
 */
public final class VerifiedClipMaskResolverFactory {

    private final PinnedVerifiedResolverWorkflow workflow = new PinnedVerifiedResolverWorkflow();

    /**
     * Creates a resolver for an admitted clip-mask host artifact.
     *
     * @param reviewedRecord path to the reviewed verification record
     * @param verifiedArtifact path to the host artifact being admitted
     * @param hostClassLoader the loader that must define the reviewed classes
     * @return a resolver bound to the reviewed record for that exact artifact
     * @throws IOException when the record or artifact cannot be read
     * @throws IllegalArgumentException when the artifact is not a reviewed clip-mask artifact
     */
    public VerifiedMemberResolver create(
        final Path reviewedRecord,
        final Path verifiedArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        return workflow.create(
            reviewedRecord,
            verifiedArtifact,
            hostClassLoader,
            ClipMaskVerificationManifest.forArtifact(
                HostArtifactDigest.from(verifiedArtifact)
            )
        );
    }
}
