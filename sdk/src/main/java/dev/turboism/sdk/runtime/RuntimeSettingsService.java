package dev.turboism.sdk.runtime;

/** Bounded access to Turboism's canonical global runtime configuration. */
public interface RuntimeSettingsService {

    /** Returns the current global runtime settings. */
    RuntimeSettings read();

    /** Persists {@code settings} and returns the stored result. */
    RuntimeSettings save(RuntimeSettings settings);

    /** Prunes empty dock palette boxes from the host's live workspace split tree and reports what was done. */
    DockCleanupResult cleanEmptyDocks();

    /**
     * Reports whether a live runtime surface backs this instance.
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
    static RuntimeSettingsService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: unsupported calls throw a stable {@link UnsupportedOperationException}. */
    enum Unavailable implements RuntimeSettingsService {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public RuntimeSettings read() {
            throw unavailable();
        }

        @Override public RuntimeSettings save(final RuntimeSettings settings) {
            throw unavailable();
        }

        @Override public DockCleanupResult cleanEmptyDocks() {
            throw unavailable();
        }

        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("runtime settings service is not available");
        }
    }


    /** Result of {@link #cleanEmptyDocks()}; {@code message} is a non-blank summary. */
    record DockCleanupResult(String message) {
        public DockCleanupResult {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
        }
    }
}
