package dev.turboism.sdk.config;

/**
 * Upgrades a persisted {@link ConfigDocument} across exactly one schema version step.
 *
 * <p>Registered migrations must form an unbroken, non-branching chain; gaps, branches and cycles
 * are rejected at schema registration time.
 */
public interface ConfigMigration {

    int fromVersion();

    int toVersion();

    ConfigDocument migrate(ConfigDocument input) throws ConfigMigrationException;
}
