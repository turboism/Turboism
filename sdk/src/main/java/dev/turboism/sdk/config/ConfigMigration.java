package dev.turboism.sdk.config;

/**
 * Upgrades a persisted {@link ConfigDocument} across exactly one schema version step.
 *
 * <p>Registered migrations must form an unbroken, non-branching chain; gaps, branches and cycles
 * are rejected at schema registration time.
 */
public interface ConfigMigration {

    /** Returns the schema version this migration upgrades from. */
    int fromVersion();

    /** Returns the schema version this migration upgrades to; {@code fromVersion() + 1}. */
    int toVersion();

    /**
     * Produces the {@code toVersion()} form of a persisted document.
     *
     * @param input the document stored at {@code fromVersion()}
     * @return the migrated document; never {@code null}
     * @throws ConfigMigrationException when the input cannot be upgraded
     */
    ConfigDocument migrate(ConfigDocument input) throws ConfigMigrationException;
}
