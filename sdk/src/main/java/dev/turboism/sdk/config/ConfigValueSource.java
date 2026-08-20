package dev.turboism.sdk.config;

/**
 * Where a {@link ConfigValue} came from: the store, or a default and the reason it was substituted.
 *
 * <p>{@code STORED} and {@code DEFAULT_MISSING} are clean outcomes with no accompanying error; the
 * remaining constants each admit a specific set of {@link ConfigErrorCode} values, enforced by
 * {@link ConfigReadResult}.
 */
public enum ConfigValueSource {
    STORED,
    DEFAULT_MISSING,
    DEFAULT_INVALID,
    DEFAULT_FUTURE_VERSION,
    DEFAULT_MIGRATION_FAILED,
    DEFAULT_UNAVAILABLE
}
