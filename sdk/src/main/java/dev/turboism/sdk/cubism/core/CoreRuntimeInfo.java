package dev.turboism.sdk.cubism.core;

/** Permission-checked metadata for the admitted Cubism Core runtime. */
public interface CoreRuntimeInfo {

    /** Returns the admitted Cubism Core runtime version. */
    CoreVersion version();

    /** Returns the feature set reported by the admitted Core runtime. */
    CoreCapabilities capabilities();

    /** Returns the permission-checked MOC byte inspection service. */
    MocInspector mocInspector();

    /**
     * Returns the owned-Moc loader (plugin-owned Core models built from MOC bytes).
     *
     * <p>Fail-closed: without an admitted host Core runtime this default is unavailable.
     * The runtime backend overrides it with the verified loader.</p>
     *
     * @throws UnsupportedOperationException when no verified Core runtime is admitted
     */
    default MocLoader mocLoader() {
        throw new UnsupportedOperationException("Owned MOC loading is unavailable.");
    }

    /**
     * Reports whether a live runtime backend backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static CoreRuntimeInfo unavailable() {
        return Unavailable.INSTANCE;
    }

    /**
     * Sentinel returned by {@link #unavailable()}: every member throws a stable
     * {@link UnsupportedOperationException}; probe with {@link #isAvailable()} first.
     */
    enum Unavailable implements CoreRuntimeInfo {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public CoreVersion version() {
            throw unavailable();
        }

        @Override
        public CoreCapabilities capabilities() {
            throw unavailable();
        }

        @Override
        public MocInspector mocInspector() {
            throw unavailable();
        }

        @Override
        public MocLoader mocLoader() {
            throw unavailable();
        }

        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("Cubism Core runtime metadata is unavailable.");
        }
    }
}
