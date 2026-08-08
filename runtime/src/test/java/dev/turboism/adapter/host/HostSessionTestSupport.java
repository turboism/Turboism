package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;

import java.nio.file.Path;

/**
 * Public test-only bridge for composing a {@link HostSession} with fixture
 * adapters from test packages outside {@code dev.turboism.adapter.host}.
 */
public final class HostSessionTestSupport {

    private HostSessionTestSupport() {
    }

    public static HostSession connectedSession(
        final HostInstanceSource source,
        final java.util.function.Function<HostInstanceDescriptor, RuntimeHostAdapters> adapters
    ) {
        return new HostSession(source, descriptor -> HostAdapterConnection.of(adapters.apply(descriptor)));
    }

    public static HostInstanceDescriptor descriptor(final String sessionId) {
        return new HostInstanceDescriptor(
            sessionId,
            HostVerificationEvidence.projectOnly(new HostVerificationEvidence.Slice(
                Path.of("records/reviewed-project.json"),
                Path.of("host/Live2D_Cubism.jar"),
                HostSessionTestSupport.class.getClassLoader()
            ))
        );
    }
}
