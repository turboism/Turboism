package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Sole public resolver entrypoint pinned to the reviewed clip-mask trust roots.
 *
 * <p>The manifest dispatches on the artifact digest, so each exact reviewed artifact resolves its
 * own versioned record and nothing else is admitted.</p>
 */
public final class VerifiedClipMaskResolverFactory implements SliceResolverFactory {

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
    /**
     * Creates a resolver for a slice admitted by structural compatibility. The
     * catalog-pinned record still anchors verification, but the host artifact
     * is a structurally matching binary rather than a reviewed release.
     *
     * @param reviewedRecord catalog-pinned verification record
     * @param hostArtifact located host artifact admitted structurally
     * @param hostClassLoader loader the verified members resolve against
     * @param contract admitted compatibility contract
     * @return a resolver limited to the aliases the record authorizes
     * @throws IOException if the record or artifact cannot be read
     * @throws IllegalArgumentException if any link in the chain fails
     * @throws NullPointerException if any argument is {@code null}
     */
    public VerifiedMemberResolver createCompatible(
        final Path reviewedRecord,
        final Path hostArtifact,
        final ClassLoader hostClassLoader,
        final SliceContract contract
    ) throws IOException {
        return workflow.createCompatible(
            reviewedRecord,
            hostArtifact,
            hostClassLoader,
            contract
        );
    }

}
