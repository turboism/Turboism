package dev.turboism.adapter;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.VerifiedProjectWorkspaceResolverFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Production composition helper using the pinned project/workspace resolver trust root. */
public final class VerifiedRuntimeHostAdaptersFactory {

    private final VerifiedProjectWorkspaceResolverFactory resolverFactory;

    public VerifiedRuntimeHostAdaptersFactory() {
        this(new VerifiedProjectWorkspaceResolverFactory());
    }

    VerifiedRuntimeHostAdaptersFactory(final VerifiedProjectWorkspaceResolverFactory resolverFactory) {
        this.resolverFactory = Objects.requireNonNull(resolverFactory, "resolverFactory");
    }

    public RuntimeHostAdapters projectWorkspace(
        final Path reviewedRecord,
        final Path hostArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        final VerifiedMemberResolver resolver = resolverFactory.create(
            reviewedRecord,
            hostArtifact,
            hostClassLoader
        );
        return RuntimeHostAdapters.withVerifiedProjectWorkspace(resolver);
    }
}
