package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Generic factory for attested, verified runtime member resolution across reviewed host slices.
 */
public final class VerifiedSliceResolverFactory implements SliceResolverFactory {

    private final SliceDescriptor descriptor;
    private final PinnedVerifiedResolverWorkflow workflow = new PinnedVerifiedResolverWorkflow();

    /**
     * Creates a verified resolver factory bound to the given slice descriptor.
     *
     * @param descriptor slice descriptor
     */
    public VerifiedSliceResolverFactory(final SliceDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    /**
     * Returns the slice descriptor this factory is bound to.
     *
     * @return the slice descriptor
     */
    public SliceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public VerifiedMemberResolver create(
            final Path reviewedRecord, final Path verifiedArtifact, final ClassLoader hostClassLoader)
            throws IOException {
        final HostArtifactDigest artifact = HostArtifactDigest.from(verifiedArtifact);
        return workflow.create(
                reviewedRecord,
                verifiedArtifact,
                hostClassLoader,
                descriptor.manifestFor(artifact),
                descriptor.runtimeScopeFor(artifact));
    }

    @Override
    public VerifiedMemberResolver createCompatible(
            final Path reviewedRecord,
            final Path hostArtifact,
            final ClassLoader hostClassLoader,
            final SliceContract contract)
            throws IOException {
        return workflow.createCompatible(reviewedRecord, hostArtifact, hostClassLoader, contract);
    }
}
