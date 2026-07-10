package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.VerifiedRuntimeHostAdaptersFactory;

import java.nio.file.Path;
import java.util.Objects;

/** Production connector pinned to the reviewed project/workspace verification trust root. */
final class VerifiedHostAdapterConnector implements HostAdapterConnector {

    private final VerifiedAdapterFactory factory;

    VerifiedHostAdapterConnector() {
        this(new VerifiedRuntimeHostAdaptersFactory()::projectWorkspace);
    }

    VerifiedHostAdapterConnector(final VerifiedAdapterFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public HostAdapterConnection connect(final HostInstanceDescriptor descriptor) throws Exception {
        Objects.requireNonNull(descriptor, "descriptor");
        final RuntimeHostAdapters adapters = factory.projectWorkspace(
            descriptor.reviewedVerificationRecord(),
            descriptor.verifiedHostArtifact(),
            descriptor.hostClassLoader()
        );
        return HostAdapterConnection.of(adapters);
    }

    @FunctionalInterface
    interface VerifiedAdapterFactory {
        RuntimeHostAdapters projectWorkspace(
            Path reviewedVerificationRecord,
            Path verifiedHostArtifact,
            ClassLoader hostClassLoader
        ) throws Exception;
    }
}
