package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Sole resolver entrypoint pinned to reviewed exact-version workspace-control records. */
public final class VerifiedWorkspaceControlResolverFactory {
    private final PinnedVerifiedResolverWorkflow workflow = new PinnedVerifiedResolverWorkflow();

    /**
     * Builds a resolver for the workspace control slice, but only after the
     * whole pinned chain checks out: the reviewed record must hash to the
     * trust-root value, its fields must match the manifest, the host artifact
     * must be byte-identical to the reviewed Cubism build, every selector must
     * verify statically, the loaded host classes must attest to that artifact,
     * and the artifact must be unchanged afterwards.
     *
     * @param reviewedRecord path to the reviewed verification record JSON
     * @param verifiedArtifact path to the host jar being admitted
     * @param hostClassLoader loader the verified members will be resolved
     *     against
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
        return workflow.create(
            reviewedRecord,
            verifiedArtifact,
            hostClassLoader,
            WorkspaceControlVerificationManifest.forArtifact(HostArtifactDigest.from(verifiedArtifact))
        );
    }
}
