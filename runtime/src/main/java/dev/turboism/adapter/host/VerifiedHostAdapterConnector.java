package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.VerifiedRuntimeHostAdaptersFactory;

import java.util.Objects;

/** Production connector pinned to the reviewed project/workspace verification trust root. */
final class VerifiedHostAdapterConnector implements HostAdapterConnector {

    private final VerifiedAdapterFactory factory;

    VerifiedHostAdapterConnector() {
        this(new VerifiedRuntimeHostAdaptersFactory()::create);
    }

    VerifiedHostAdapterConnector(final VerifiedAdapterFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public HostAdapterConnection connect(final HostInstanceDescriptor descriptor) throws Exception {
        Objects.requireNonNull(descriptor, "descriptor");
        final RuntimeHostAdapters adapters = factory.create(descriptor.verificationEvidence());
        return HostAdapterConnection.of(adapters);
    }

    @FunctionalInterface
    interface VerifiedAdapterFactory {
        RuntimeHostAdapters create(HostVerificationEvidence evidence) throws Exception;
    }
}
