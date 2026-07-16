package dev.turboism.sdk.config;

public class ConfigMigrationException extends Exception {

    public ConfigMigrationException(final String message) {
        super(message);
    }

    public ConfigMigrationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
