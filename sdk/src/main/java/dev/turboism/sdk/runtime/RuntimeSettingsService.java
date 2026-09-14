package dev.turboism.sdk.runtime;

/** Bounded access to Turboism's canonical global runtime configuration. */
public interface RuntimeSettingsService {

    /** Returns the current global runtime settings. */
    RuntimeSettings read();

    /** Persists {@code settings} and returns the stored result. */
    RuntimeSettings save(RuntimeSettings settings);

    /** Removes empty dock entries from the stored settings and reports what was done. */
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
