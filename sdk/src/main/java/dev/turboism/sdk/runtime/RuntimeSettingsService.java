package dev.turboism.sdk.runtime;

/** Bounded access to Turboism's canonical global runtime configuration. */
public interface RuntimeSettingsService {

    RuntimeSettings read();

    RuntimeSettings save(RuntimeSettings settings);

    DockCleanupResult cleanEmptyDocks();

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    static RuntimeSettingsService unavailable() {
        return Unavailable.INSTANCE;
    }

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

    record DockCleanupResult(String message) {
        public DockCleanupResult {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
        }
    }
}
