package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Pinned resolver factory for the protected-export orchestration slice. */
public final class VerifiedProtectedExportResolverFactory {

    private final PinnedVerifiedResolverWorkflow workflow = new PinnedVerifiedResolverWorkflow();

    /**
     * Builds a resolver for protected-export orchestration after the whole pinned chain
     * checks out: reviewed record hash, record/manifest agreement, byte-identical reviewed
     * artifact, static selector verification, host classloader attestation, and a stable
     * artifact digest afterwards.
     *
     * @param reviewedRecord path to the reviewed verification record JSON
     * @param verifiedArtifact path to the host jar being admitted
     * @param hostClassLoader loader the verified members will be resolved against
     * @return a resolver limited to the aliases the manifest authorizes
     * @throws IOException if the record or artifact cannot be read
     * @throws IllegalArgumentException if any link in that chain fails, so an
     *     unrecognized or tampered host yields no resolver at all
     * @throws NullPointerException if any argument is {@code null}
     */
    public VerifiedMemberResolver create(
        final Path reviewedRecord,
        final Path verifiedArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        final HostArtifactDigest artifact = HostArtifactDigest.from(verifiedArtifact);
        return workflow.create(
            reviewedRecord,
            verifiedArtifact,
            hostClassLoader,
            ProtectedExportVerificationManifest.forArtifact(artifact)
        );
    }
}
