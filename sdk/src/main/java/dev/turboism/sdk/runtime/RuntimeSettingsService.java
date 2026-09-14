package dev.turboism.sdk.runtime;

/** Bounded access to Turboism's canonical global runtime configuration. */
public interface RuntimeSettingsService {

    /** Returns the current global runtime settings. */
    RuntimeSettings read();

    /** Persists {@code settings} and returns the stored result. */
    RuntimeSettings save(RuntimeSettings settings);

    /** Prunes empty dock palette boxes from the host's live workspace split tree and reports what was done. */
    DockCleanupResult cleanEmptyDocks();

    /** Result of {@link #cleanEmptyDocks()}; {@code message} is a non-blank summary. */
    record DockCleanupResult(String message) {
        public DockCleanupResult {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
        }
    }
}
