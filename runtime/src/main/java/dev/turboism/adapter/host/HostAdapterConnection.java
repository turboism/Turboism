package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;

import java.util.Objects;

/** Owns the adapter bundle and all resources allocated for one exact host connection. */
interface HostAdapterConnection extends AutoCloseable {

    RuntimeHostAdapters adapters();

    @Override
    void close() throws Exception;

    static HostAdapterConnection of(final RuntimeHostAdapters adapters) {
        final RuntimeHostAdapters ownedAdapters = Objects.requireNonNull(adapters, "adapters");
        return new HostAdapterConnection() {
            @Override
            public RuntimeHostAdapters adapters() {
                return ownedAdapters;
            }

            @Override
            public void close() {
            }
        };
    }
}
