package dev.turboism.sdk.runtime;

/** Bounded access to Turboism's canonical global runtime configuration. */
public interface RuntimeSettingsService {

    RuntimeSettings read();

    RuntimeSettings save(RuntimeSettings settings);

    DockCleanupResult cleanEmptyDocks();

    record DockCleanupResult(String message) {
        public DockCleanupResult {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
        }
    }
}
