package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;

/** Unforgeable runtime composition handle for a verified, fail-closed host session. */
public sealed interface RuntimeHostAdapterAccess permits HostSession, SessionRuntimeHostAdapterAccess {

    RuntimeHostAdapters adapters();
}

/** Non-closeable adapter view used when lifecycle ownership remains with bootstrap ingress. */
final class SessionRuntimeHostAdapterAccess implements RuntimeHostAdapterAccess {

    private final RuntimeHostAdapters adapters;

    SessionRuntimeHostAdapterAccess(final RuntimeHostAdapters adapters) {
        this.adapters = java.util.Objects.requireNonNull(adapters, "adapters");
    }

    @Override
    public RuntimeHostAdapters adapters() {
        return adapters;
    }
}
