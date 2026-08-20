package dev.turboism.sdk.config;

/**
 * Classification of a config read or write failure.
 *
 * <p>Which codes are legal is constrained by context: {@link ConfigReadResult} accepts only the
 * codes matching its {@link ConfigValueSource}, and {@link ConfigWriteResult} accepts only the
 * write-side subset (schema, validation, revision conflict, permission, persistence, runtime).
 */
public enum ConfigErrorCode {
    SCHEMA_NOT_REGISTERED,
    INVALID_VALUE,
    FUTURE_SCHEMA_VERSION,
    MIGRATION_GAP,
    MIGRATION_FAILED,
    REVISION_CONFLICT,
    PERMISSION_DENIED,
    PERSISTENCE_FAILED,
    RUNTIME_UNAVAILABLE
}
