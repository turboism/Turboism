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

    /**
     * Adapts a hook bridge that keeps its own slots, so the routine can drive a bridge it did not
     * write: the bridge publishes on {@code install} and withdraws on {@code close}.
     *
     * @param publisher publishes the bridge's slots
     * @param bridge the bridge those slots belong to
     * @return the layer the routine installs before any transformer and closes after the proof
     */
    static BridgeLayer of(final SlotPublisher publisher, final AutoCloseable bridge) {
        return new BridgeLayer() {
            @Override
            public void install() throws Exception {
                publisher.publish();
            }

            @Override
            public void close() throws Exception {
                bridge.close();
            }
        };
    }

    /** Publishes every slot the hook's woven bytes call. */
    default void install() throws Exception {}

    @Override
    default void close() throws Exception {}

    /** Publishes a hook bridge's slots. */
    @FunctionalInterface
    interface SlotPublisher {

        /**
         * @throws Exception when a slot is already occupied and the weave must not proceed
         */
        void publish() throws Exception;
    }
}
