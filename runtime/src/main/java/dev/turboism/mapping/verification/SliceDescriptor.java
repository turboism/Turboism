package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/**
 * Descriptor binding an adapter slice to its verified manifest lookup.
 */
public final class SliceDescriptor implements SliceResolverFactory {

    private final String sliceId;
    private final Function<HostArtifactDigest, PinnedVerifiedResolverWorkflow.Manifest> manifestLookup;
    private final Function<HostArtifactDigest, PinnedVerifiedResolverWorkflow.RuntimeScope> runtimeScopeLookup;
    private final SliceResolverFactory factory;

    SliceDescriptor(
            final String sliceId,
            final Function<HostArtifactDigest, PinnedVerifiedResolverWorkflow.Manifest> manifestLookup) {
        this(sliceId, manifestLookup, ignored -> null);
    }

    SliceDescriptor(
            final String sliceId,
            final Function<HostArtifactDigest, PinnedVerifiedResolverWorkflow.Manifest> manifestLookup,
            final Function<HostArtifactDigest, PinnedVerifiedResolverWorkflow.RuntimeScope> runtimeScopeLookup) {
        this.sliceId = Objects.requireNonNull(sliceId, "sliceId");
        this.manifestLookup = Objects.requireNonNull(manifestLookup, "manifestLookup");
        this.runtimeScopeLookup = Objects.requireNonNull(runtimeScopeLookup, "runtimeScopeLookup");
        this.factory = new VerifiedSliceResolverFactory(this);
    }

    /**
     * Returns the slice identifier.
     *
     * @return the slice identifier
     */
    public String sliceId() {
        return sliceId;
    }

    /**
     * Returns the resolver factory for this slice.
     *
     * @return the resolver factory
     */
    public SliceResolverFactory resolverFactory() {
        return factory;
    }

    PinnedVerifiedResolverWorkflow.Manifest manifestFor(final HostArtifactDigest artifact) {
        return manifestLookup.apply(artifact);
    }

    PinnedVerifiedResolverWorkflow.RuntimeScope runtimeScopeFor(final HostArtifactDigest artifact) {
        return runtimeScopeLookup.apply(artifact);
    }

    @Override
    public VerifiedMemberResolver create(
            final Path reviewedRecord, final Path verifiedArtifact, final ClassLoader hostClassLoader)
            throws IOException {
        return factory.create(reviewedRecord, verifiedArtifact, hostClassLoader);
    }

    @Override
    public VerifiedMemberResolver createCompatible(
            final Path reviewedRecord,
            final Path hostArtifact,
            final ClassLoader hostClassLoader,
            final SliceContract contract)
            throws IOException {
        return factory.createCompatible(reviewedRecord, hostArtifact, hostClassLoader, contract);
    }
}
