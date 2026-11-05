package dev.turboism.bootstrap;

/**
 * A hook's own slot bridge: the {@code System.getProperties()} handles its woven bytes call.
 *
 * <p>Installed before any transformer is registered and closed after the restoration proof, so a woven
 * site can never call an unpublished slot and a restored class never keeps calling a removed one.</p>
 */
interface BridgeLayer extends AutoCloseable {

    /** A hook whose woven bytes call no published slot. */
    BridgeLayer NONE = new BridgeLayer() {};

    /** Publishes every slot the hook's woven bytes call. */
    default void install() throws Exception {}

    @Override
    default void close() {}
}
