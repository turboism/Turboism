package dev.turboism.sdk.config;

public interface ConfigMigration {

    int fromVersion();

    int toVersion();

    ConfigDocument migrate(ConfigDocument input) throws ConfigMigrationException;
}
