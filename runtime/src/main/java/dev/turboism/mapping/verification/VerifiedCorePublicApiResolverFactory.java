package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Sole public resolver entrypoint pinned to reviewed Cubism Core public-API evidence. */
public final class VerifiedCorePublicApiResolverFactory {

    private final PinnedVerifiedResolverWorkflow workflow = new PinnedVerifiedResolverWorkflow();

    public VerifiedMemberResolver create(
        final String profile,
        final Path reviewedRecord,
        final Path verifiedArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        return workflow.create(
            reviewedRecord,
            verifiedArtifact,
            hostClassLoader,
            CorePublicApiVerificationManifest.require(profile)
        );
    }
}
