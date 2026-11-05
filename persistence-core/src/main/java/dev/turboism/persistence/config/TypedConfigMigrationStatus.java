package dev.turboism.persistence.config;

/** How one stored typed-config document was classified by {@link TypedConfigMigrationPolicy}. */
public enum TypedConfigMigrationStatus {
    /** The document already is, or was upgraded to, the registered schema version. */
    MIGRATED,
    /** The stored document is newer than the registered schema. */
    FUTURE_VERSION,
    /** The migration chain has no step for the stored version. */
    MIGRATION_GAP,
    /** A migration failed or produced a document that does not match its own transition. */
    MIGRATION_FAILED,
    /** The document carries a key or value the registered schema does not accept. */
    INVALID_VALUES
}
