package dev.turboism.sdk.config;

/**
 * Why a declared {@link ConfigSchema} (or its migration chain) is itself malformed.
 *
 * <p>Covers identity and path collisions across schemas, per-key defects, and migration chains that
 * are incomplete ({@code MIGRATION_GAP}), ambiguous ({@code MIGRATION_BRANCH}) or looping
 * ({@code MIGRATION_CYCLE}).
 */
public enum ConfigSchemaValidationError {
    INVALID_SCHEMA,
    INVALID_CONFIG_ID,
    INVALID_PATH,
    INVALID_VERSION,
    DUPLICATE_CONFIG_ID,
    DUPLICATE_PATH,
    INVALID_KEY,
    DUPLICATE_KEY,
    INVALID_CODEC,
    INVALID_DEFAULT_VALUE,
    INVALID_MIGRATION,
    MIGRATION_GAP,
    MIGRATION_BRANCH,
    MIGRATION_CYCLE
}
