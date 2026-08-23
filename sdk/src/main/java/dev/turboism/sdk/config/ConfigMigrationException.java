package dev.turboism.sdk.config;

/**
 * Thrown by a {@link ConfigMigration} that cannot upgrade the document it was given.
 *
 * <p>The runtime turns this into a fallback read carrying
 * {@link ConfigErrorCode#MIGRATION_FAILED}; it is not propagated to the calling plugin.
 */
public class ConfigMigrationException extends Exception {

    public ConfigMigrationException(final String message) {
        super(message);
    }

    public ConfigMigrationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
