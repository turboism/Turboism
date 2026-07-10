package dev.turboism.adapter.host;

@FunctionalInterface
interface HostAdapterConnector {
    /**
     * Connects one exact descriptor. Implementations must release partial resources before
     * propagating a failure; ownership transfers to the caller only on successful return.
     */
    HostAdapterConnection connect(HostInstanceDescriptor descriptor) throws Exception;
}
