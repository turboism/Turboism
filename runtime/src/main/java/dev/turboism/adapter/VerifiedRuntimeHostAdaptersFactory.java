package dev.turboism.adapter;

import dev.turboism.adapter.host.HostVerificationEvidence;
import dev.turboism.mapping.verification.VerifiedClipMaskResolverFactory;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.VerifiedProjectWorkspaceResolverFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Production composition helper for pinned, reviewed host-adapter verification evidence.
 *
 * <p>The dual-slice production entrypoint is {@link #create(HostVerificationEvidence)}. It accepts
 * one evidence value whose constructor validates that project/workspace and clip-mask slices attest
 * the same artifact and defining classloader before either resolver factory is invoked.</p>
 */
public final class VerifiedRuntimeHostAdaptersFactory {

    private final VerifiedProjectWorkspaceResolverFactory projectResolverFactory;
    private final VerifiedClipMaskResolverFactory clipMaskResolverFactory;

    public VerifiedRuntimeHostAdaptersFactory() {
        this(new VerifiedProjectWorkspaceResolverFactory(), new VerifiedClipMaskResolverFactory());
    }

    VerifiedRuntimeHostAdaptersFactory(
        final VerifiedProjectWorkspaceResolverFactory projectResolverFactory,
        final VerifiedClipMaskResolverFactory clipMaskResolverFactory
    ) {
        this.projectResolverFactory = Objects.requireNonNull(projectResolverFactory, "projectResolverFactory");
        this.clipMaskResolverFactory = Objects.requireNonNull(clipMaskResolverFactory, "clipMaskResolverFactory");
    }

    public RuntimeHostAdapters create(final HostVerificationEvidence evidence) throws IOException {
        Objects.requireNonNull(evidence, "evidence");
        final HostVerificationEvidence.Slice project = evidence.projectWorkspace();
        if (evidence.clipMask().isEmpty()) {
            return projectWorkspace(
                project.reviewedRecord(),
                project.verifiedArtifact(),
                project.hostClassLoader()
            );
        }
        final HostVerificationEvidence.Slice clipMask = evidence.clipMask().orElseThrow();
        final VerifiedMemberResolver projectResolver = projectResolverFactory.create(
            project.reviewedRecord(),
            project.verifiedArtifact(),
            project.hostClassLoader()
        );
        final VerifiedMemberResolver clipMaskResolver = clipMaskResolverFactory.create(
            clipMask.reviewedRecord(),
            clipMask.verifiedArtifact(),
            clipMask.hostClassLoader()
        );
        return RuntimeHostAdapters.withVerifiedProjectWorkspaceAndClipMask(
            projectResolver,
            clipMaskResolver
        );
    }

    public RuntimeHostAdapters projectWorkspace(
        final Path reviewedRecord,
        final Path hostArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        final VerifiedMemberResolver resolver = projectResolverFactory.create(
            reviewedRecord,
            hostArtifact,
            hostClassLoader
        );
        return RuntimeHostAdapters.withVerifiedProjectWorkspace(resolver);
    }

}
